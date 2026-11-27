package app.usbprint

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** Encoder for PWG Raster (PWG 5102.4), the format every IPP Everywhere printer must accept. */
object Pwg {
    const val DPI = 300

    fun writeSync(o: OutputStream) = o.write("RaS2".toByteArray(Charsets.US_ASCII))

    fun writePage(
        o: OutputStream, bmp: Bitmap, wPts: Int, hPts: Int,
        color: Boolean, duplex: Boolean, tumble: Boolean
    ) {
        val w = bmp.width
        val h = bmp.height
        val bpp = if (color) 24 else 8
        val bytesPerPixel = bpp / 8

        val hd = ByteArray(1796)
        fun put32(off: Int, v: Int) {
            hd[off] = (v ushr 24).toByte(); hd[off + 1] = (v ushr 16).toByte()
            hd[off + 2] = (v ushr 8).toByte(); hd[off + 3] = v.toByte()
        }
        val name = "PwgRaster".toByteArray(Charsets.US_ASCII)
        System.arraycopy(name, 0, hd, 0, name.size)
        put32(272, if (duplex) 1 else 0)   // Duplex
        put32(276, DPI); put32(280, DPI)   // HWResolution
        put32(292, wPts); put32(296, hPts) // ImagingBoundingBox
        put32(340, 1)                      // NumCopies
        put32(352, wPts); put32(356, hPts) // PageSize
        put32(368, if (tumble) 1 else 0)   // Tumble
        put32(372, w); put32(376, h)       // cupsWidth / cupsHeight
        put32(384, 8)                      // cupsBitsPerColor
        put32(388, bpp)                    // cupsBitsPerPixel
        put32(392, w * bytesPerPixel)      // cupsBytesPerLine
        put32(396, 0)                      // chunked colour order
        put32(400, if (color) 19 else 18)  // sRGB / sGray
        put32(420, if (color) 3 else 1)    // cupsNumColors
        put32(456, 1); put32(460, 1)       // cross-feed / feed transform
        o.write(hd)

        val px = IntArray(w)
        fun row(y: Int): ByteArray {
            bmp.getPixels(px, 0, w, 0, y, w, 1)
            val line = ByteArray(w * bytesPerPixel)
            if (color) {
                var k = 0
                for (p in px) {
                    line[k++] = (p shr 16).toByte(); line[k++] = (p shr 8).toByte(); line[k++] = p.toByte()
                }
            } else {
                for (i in 0 until w) {
                    val p = px[i]
                    val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                    line[i] = ((77 * r + 150 * g + 29 * b) shr 8).toByte()
                }
            }
            return line
        }

        if (h == 0) return
        var cur = row(0)
        var y = 0
        while (true) {
            var rep = 0
            var next: ByteArray? = null
            var yy = y + 1
            while (yy < h) {
                val r = row(yy)
                if (rep < 255 && r.contentEquals(cur)) { rep++; yy++ } else { next = r; break }
            }
            o.write(rep)
            o.write(encodeLine(cur, bytesPerPixel))
            y = yy
            if (y >= h) break
            cur = next!!
        }
    }

    private fun same(l: ByteArray, a: Int, b: Int, bpp: Int): Boolean {
        for (k in 0 until bpp) if (l[a * bpp + k] != l[b * bpp + k]) return false
        return true
    }

    private fun encodeLine(line: ByteArray, bpp: Int): ByteArray {
        val out = ByteArrayOutputStream(line.size / 2 + 16)
        val n = line.size / bpp
        var i = 0
        while (i < n) {
            var run = 1
            while (i + run < n && run < 128 && same(line, i, i + run, bpp)) run++
            if (run > 1) {
                out.write(run - 1)
                out.write(line, i * bpp, bpp)
                i += run
            } else {
                var j = i + 1
                while (j < n && j - i < 128 && !(j + 1 < n && same(line, j, j + 1, bpp))) j++
                val cnt = j - i
                out.write(257 - cnt)           // low byte: 0x00 for a single pixel, which is valid
                out.write(line, i * bpp, cnt * bpp)
                i = j
            }
        }
        return out.toByteArray()
    }
}
