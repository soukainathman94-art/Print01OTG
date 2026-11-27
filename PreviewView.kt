package app.usbprint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/** Vertical list of sheets with pinch-zoom, pan and double-tap zoom. */
class PreviewView(ctx: Context) : View(ctx) {
    private var sheets: List<Bitmap> = emptyList()
    private var zoom = 1f
    private var tx = 0f
    private var ty = 0f
    private val density = resources.displayMetrics.density
    private val pad = 16f * density
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val border = Paint().apply {
        color = 0xFF888888.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f
    }

    fun setSheets(s: List<Bitmap>) {
        sheets = s
        clamp()
        invalidate()
    }

    private fun totalHeight(): Float {
        val w = width - 2 * pad
        var h = pad
        for (b in sheets) h += w * b.height / b.width + pad
        return h
    }

    private fun clamp() {
        val cw = width * zoom
        val ch = totalHeight() * zoom
        tx = if (cw <= width) 0f else tx.coerceIn(width - cw, 0f)
        ty = if (ch <= height) 0f else ty.coerceIn(height - ch, 0f)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { clamp() }

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF404040.toInt())
        c.translate(tx, ty)
        c.scale(zoom, zoom)
        val w = width - 2 * pad
        var y = pad
        for (b in sheets) {
            val h = w * b.height / b.width
            val r = RectF(pad, y, pad + w, y + h)
            c.drawBitmap(b, null, r, paint)
            c.drawRect(r, border)
            y += h + pad
        }
    }

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val nz = (zoom * d.scaleFactor).coerceIn(1f, 6f)
            val f = nz / zoom
            tx = d.focusX - (d.focusX - tx) * f
            ty = d.focusY - (d.focusY - ty) * f
            zoom = nz
            clamp(); invalidate()
            return true
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            tx -= dx; ty -= dy
            clamp(); invalidate()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom > 1f) {
                zoom = 1f; tx = 0f
            } else {
                val f = 2.5f
                tx = e.x - (e.x - tx) * f
                ty = e.y - (e.y - ty) * f
                zoom = f
            }
            clamp(); invalidate()
            return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        if (!scaleDetector.isInProgress) gestures.onTouchEvent(e)
        return true
    }
}
