package app.usbprint

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * IPP-over-USB (class 7, subclass 1, protocol 4): plain HTTP carried over USB bulk endpoints.
 * Supported by most printers made after ~2015.
 */
class IppUsbConnection(
    private val manager: UsbManager,
    private val device: UsbDevice
) : AutoCloseable {

    private var conn: UsbDeviceConnection? = null
    private var iface: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null

    companion object {
        private val CRLF = "\r\n".toByteArray()

        fun findIppInterface(d: UsbDevice): UsbInterface? {
            for (i in 0 until d.interfaceCount) {
                val f = d.getInterface(i)
                if (f.interfaceClass == UsbConstants.USB_CLASS_PRINTER &&
                    f.interfaceSubclass == 1 && f.interfaceProtocol == 4
                ) return f
            }
            return null
        }
    }

    fun open() {
        val f = findIppInterface(device) ?: throw IOException("Printer has no IPP-over-USB interface")
        val c = manager.openDevice(device) ?: throw IOException("Cannot open USB device")
        if (!c.claimInterface(f, true)) {
            c.close(); throw IOException("Cannot claim USB interface")
        }
        c.setInterface(f)
        for (i in 0 until f.endpointCount) {
            val e = f.getEndpoint(i)
            if (e.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (e.direction == UsbConstants.USB_DIR_IN) epIn = e else epOut = e
            }
        }
        if (epIn == null || epOut == null) {
            c.releaseInterface(f); c.close(); throw IOException("Bulk endpoints not found")
        }
        conn = c; iface = f
    }

    override fun close() {
        try { iface?.let { conn?.releaseInterface(it) } } catch (_: Exception) {}
        try { conn?.close() } catch (_: Exception) {}
        conn = null
    }

    private fun writeAll(data: ByteArray, off: Int = 0, len: Int = data.size) {
        var o = off
        var l = len
        while (l > 0) {
            val c = minOf(l, 16384)
            val r = conn!!.bulkTransfer(epOut, data, o, c, 15000)
            if (r <= 0) throw IOException("USB write failed ($r)")
            o += r; l -= r
        }
    }

    private inner class ChunkedOut : OutputStream() {
        private val buf = ByteArray(32768)
        private var n = 0

        override fun write(b: Int) {
            if (n == buf.size) flushChunk()
            buf[n++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var l = len
            while (l > 0) {
                if (n == buf.size) flushChunk()
                val c = minOf(l, buf.size - n)
                System.arraycopy(b, o, buf, n, c)
                n += c; o += c; l -= c
            }
        }

        private fun flushChunk() {
            if (n == 0) return
            writeAll(Integer.toHexString(n).toByteArray() + CRLF)
            writeAll(buf, 0, n)
            writeAll(CRLF)
            n = 0
        }

        fun finish() {
            flushChunk()
            writeAll("0\r\n\r\n".toByteArray())
        }
    }

    /** Sends one IPP request (body written by [body]) and returns the IPP response bytes. */
    fun postIpp(body: (OutputStream) -> Unit): ByteArray {
        val head = "POST /ipp/print HTTP/1.1\r\nHost: localhost:631\r\n" +
            "User-Agent: UsbPrint/1.0\r\nContent-Type: application/ipp\r\n" +
            "Transfer-Encoding: chunked\r\n\r\n"
        writeAll(head.toByteArray())
        val out = ChunkedOut()
        body(out)
        out.finish()
        return readResponse()
    }

    private fun indexOf(h: ByteArray, s: String, from: Int = 0): Int {
        val n = s.toByteArray(Charsets.ISO_8859_1)
        outer@ for (i in from..h.size - n.size) {
            for (j in n.indices) if (h[i + j] != n[j]) continue@outer
            return i
        }
        return -1
    }

    private fun readResponse(): ByteArray {
        val acc = ByteArrayOutputStream()
        val tmp = ByteArray(16384)
        val deadline = System.currentTimeMillis() + 120_000
        var headEnd = -1
        var contentLen = -1
        var chunked = false
        while (System.currentTimeMillis() < deadline) {
            val n = conn!!.bulkTransfer(epIn, tmp, tmp.size, 2000)
            if (n <= 0) continue
            acc.write(tmp, 0, n)
            val bytes = acc.toByteArray()
            if (headEnd < 0) {
                headEnd = indexOf(bytes, "\r\n\r\n")
                if (headEnd >= 0) {
                    val head = String(bytes, 0, headEnd, Charsets.ISO_8859_1).lowercase()
                    val first = head.lineSequence().first()
                    if (!first.contains(" 200")) throw IOException("Printer answered: $first")
                    chunked = head.contains("transfer-encoding: chunked")
                    Regex("content-length:\\s*(\\d+)").find(head)?.let {
                        contentLen = it.groupValues[1].toInt()
                    }
                }
            }
            if (headEnd >= 0) {
                val body = bytes.copyOfRange(headEnd + 4, bytes.size)
                if (contentLen >= 0 && body.size >= contentLen) return body.copyOf(contentLen)
                if (chunked && indexOf(body, "0\r\n\r\n") >= 0) return dechunk(body)
            }
        }
        throw IOException("Printer did not respond (timeout)")
    }

    private fun dechunk(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var p = 0
        while (p < b.size) {
            val e = indexOf(b, "\r\n", p)
            if (e < 0) break
            val size = String(b, p, e - p, Charsets.ISO_8859_1).trim().substringBefore(';').toInt(16)
            p = e + 2
            if (size == 0) break
            out.write(b, p, size)
            p += size + 2
        }
        return out.toByteArray()
    }
}
