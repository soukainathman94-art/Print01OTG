package app.usbprint

import java.io.ByteArrayOutputStream

/** Minimal IPP 2.0 request builder (only what is needed to print). */
object Ipp {
    private const val OP_PRINT_JOB = 0x0002
    private const val OP_GET_PRINTER_ATTRIBUTES = 0x000B
    private const val URI = "ipp://localhost/ipp/print"

    private class Writer {
        val o = ByteArrayOutputStream()
        fun u8(v: Int) = o.write(v)
        fun u16(v: Int) { o.write(v shr 8); o.write(v) }
        fun u32(v: Int) { u16(v ushr 16); u16(v and 0xFFFF) }
        fun attr(tag: Int, name: String, value: ByteArray) {
            u8(tag)
            val n = name.toByteArray(Charsets.UTF_8)
            u16(n.size); o.write(n)
            u16(value.size); o.write(value)
        }
        fun str(tag: Int, name: String, v: String) = attr(tag, name, v.toByteArray(Charsets.UTF_8))
        fun int(name: String, v: Int) {
            u8(0x21); val n = name.toByteArray(); u16(n.size); o.write(n); u16(4); u32(v)
        }
    }

    private fun begin(op: Int): Writer {
        val w = Writer()
        w.u16(0x0200); w.u16(op); w.u32(1)
        w.u8(0x01) // operation-attributes group
        w.str(0x47, "attributes-charset", "utf-8")
        w.str(0x48, "attributes-natural-language", "en")
        w.str(0x45, "printer-uri", URI)
        return w
    }

    fun getPrinterAttributes(): ByteArray {
        val w = begin(OP_GET_PRINTER_ATTRIBUTES)
        w.str(0x44, "requested-attributes", "document-format-supported")
        w.u8(0x03)
        return w.o.toByteArray()
    }

    /** Returns the IPP header; the document data must be written right after it. */
    fun printJobHeader(
        jobName: String, format: String, copies: Int,
        sides: String?, monochrome: Boolean
    ): ByteArray {
        val w = begin(OP_PRINT_JOB)
        w.str(0x42, "requesting-user-name", "user")
        w.str(0x42, "job-name", jobName)
        w.str(0x49, "document-format", format)
        w.u8(0x02) // job-attributes group
        w.int("copies", copies.coerceAtLeast(1))
        if (sides != null) w.str(0x44, "sides", sides)
        if (monochrome) w.str(0x44, "print-color-mode", "monochrome")
        w.u8(0x03)
        return w.o.toByteArray()
    }

    fun status(resp: ByteArray): Int =
        if (resp.size < 4) -1 else ((resp[2].toInt() and 0xFF) shl 8) or (resp[3].toInt() and 0xFF)

    fun isOk(resp: ByteArray) = status(resp) in 0..0x00FF
}
