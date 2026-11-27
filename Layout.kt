package app.usbprint

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlin.math.ceil
import kotlin.math.roundToInt

/** A document that can be drawn page by page. Sizes are in PostScript points (1/72 inch). */
abstract class Source : AutoCloseable {
    abstract val count: Int
    abstract fun sizePts(i: Int): FloatArray
    /** Draws page [i] into [bmp]; [m] maps page points to bitmap pixels, drawing is limited to [clip]. */
    abstract fun draw(i: Int, bmp: Bitmap, m: Matrix, clip: Rect)
}

class PdfSource(pfd: ParcelFileDescriptor) : Source() {
    private val r = PdfRenderer(pfd)
    private val sizes = arrayOfNulls<FloatArray>(r.pageCount)
    override val count get() = r.pageCount

    @Synchronized
    override fun sizePts(i: Int): FloatArray {
        sizes[i]?.let { return it }
        val p = r.openPage(i)
        val s = floatArrayOf(p.width.toFloat(), p.height.toFloat())
        p.close()
        sizes[i] = s
        return s
    }

    @Synchronized
    override fun draw(i: Int, bmp: Bitmap, m: Matrix, clip: Rect) {
        val p = r.openPage(i)
        try {
            p.render(bmp, clip, m, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
        } finally { p.close() }
    }

    override fun close() { try { r.close() } catch (_: Exception) {} }
}

/** A single image, treated as 300 dpi (times [sample] if it was decoded at reduced size). */
class ImageSource(private val img: Bitmap, private val sample: Int) : Source() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    override val count = 1
    private val k = 72f / 300f * sample
    override fun sizePts(i: Int) = floatArrayOf(img.width * k, img.height * k)

    override fun draw(i: Int, bmp: Bitmap, m: Matrix, clip: Rect) {
        val c = Canvas(bmp)
        c.clipRect(clip)
        c.concat(m)
        c.scale(k, k)
        c.drawBitmap(img, 0f, 0f, paint)
    }

    override fun close() { img.recycle() }
}

data class Settings(
    val paperW: Float, val paperH: Float,
    val orient: Int,        // 0 auto, 1 portrait, 2 landscape
    val actualSize: Boolean,
    val percent: Int,       // 10..400
    val from: Int, val to: Int
)

/** One physical sheet of paper: which source page, at what scale, and where it sits on the sheet. */
class Sheet(
    val src: Int, val landscape: Boolean,
    val lw: Float, val lh: Float,     // sheet size in points, in its logical orientation
    val scale: Float, val offX: Float, val offY: Float
)

object Layout {
    const val MARGIN = 12f // points of unprintable border kept on every side

    /**
     * Builds the exact list of sheets that will come out of the printer. The preview and the
     * printer both use this list, so what you see is what is printed.
     * Content larger than the printable area continues on extra sheets (tiles).
     */
    fun plan(src: Source, st: Settings): List<Sheet> {
        val out = ArrayList<Sheet>()
        val from = st.from.coerceIn(1, src.count)
        val to = st.to.coerceIn(from, src.count)
        for (i in from - 1 until to) {
            val sz = src.sizePts(i)
            val pw = sz[0]; val ph = sz[1]
            val land = when (st.orient) { 1 -> false; 2 -> true; else -> pw > ph }
            val lw = if (land) st.paperH else st.paperW
            val lh = if (land) st.paperW else st.paperH
            val aw = lw - 2 * MARGIN
            val ah = lh - 2 * MARGIN
            val base = if (st.actualSize) 1f else minOf(aw / pw, ah / ph)
            val s = base * st.percent / 100f
            val cw = pw * s
            val ch = ph * s
            val cols = maxOf(1, ceil(cw / aw - 0.01f).toInt())
            val rows = maxOf(1, ceil(ch / ah - 0.01f).toInt())
            for (r in 0 until rows) for (c in 0 until cols) {
                val ox = if (cols == 1) MARGIN + (aw - cw) / 2 else MARGIN - c * aw
                val oy = if (rows == 1) MARGIN + (ah - ch) / 2 else MARGIN - r * ah
                out.add(Sheet(i, land, lw, lh, s, ox, oy))
            }
        }
        return out
    }

    /**
     * Renders a sheet. [physical] = true gives the orientation sent to the printer (always portrait),
     * false gives the orientation as the sheet is read (used for the preview).
     */
    fun renderSheet(src: Source, sh: Sheet, dpi: Float, physical: Boolean): Bitmap {
        val k = dpi / 72f
        val wl = (sh.lw * k).roundToInt()
        val hl = (sh.lh * k).roundToInt()
        val rotate = physical && sh.landscape
        val bmp = Bitmap.createBitmap(if (rotate) hl else wl, if (rotate) wl else hl, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val m = Matrix()
        m.setScale(sh.scale, sh.scale)
        m.postTranslate(sh.offX, sh.offY)
        m.postScale(k, k)
        val mg = (MARGIN * k).roundToInt()
        val clip: Rect
        if (rotate) {
            m.postRotate(90f)
            m.postTranslate(hl.toFloat(), 0f)
            clip = Rect(hl - (hl - mg), mg, hl - mg, wl - mg)
        } else {
            clip = Rect(mg, mg, wl - mg, hl - mg)
        }
        src.draw(sh.src, bmp, m, clip)
        return bmp
    }
}
