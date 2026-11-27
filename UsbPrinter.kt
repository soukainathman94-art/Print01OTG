package app.usbprint

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Shared USB printing logic used by both the print service and the in-app preview screen. */
object UsbPrinter {

    /** A page ready to send: a portrait bitmap at 300 dpi plus its paper size in points. */
    class Rendered(val bitmap: Bitmap, val wPts: Int, val hPts: Int)

    fun localId(d: UsbDevice) = "usb-${d.vendorId}:${d.productId}"

    fun ippPrinters(usb: UsbManager): List<UsbDevice> =
        usb.deviceList.values.filter { IppUsbConnection.findIppInterface(it) != null }

    /** Blocking: call from a background thread. */
    fun ensurePermission(ctx: Context, usb: UsbManager, d: UsbDevice): Boolean {
        if (usb.hasPermission(d)) return true
        val action = "${ctx.packageName}.USB_PERMISSION"
        val latch = CountDownLatch(1)
        var granted = false
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                latch.countDown()
            }
        }
        val f = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
        else ctx.registerReceiver(r, f)
        try {
            val pi = PendingIntent.getBroadcast(
                ctx, 0, Intent(action).setPackage(ctx.packageName), PendingIntent.FLAG_MUTABLE
            )
            usb.requestPermission(d, pi)
            latch.await(60, TimeUnit.SECONDS)
        } finally {
            try { ctx.unregisterReceiver(r) } catch (_: Exception) {}
        }
        return granted || usb.hasPermission(d)
    }

    /** Renders a PDF page at 300 dpi, always portrait (landscape pages are rotated). */
    fun renderPdfPage(page: PdfRenderer.Page): Rendered {
        val s = Pwg.DPI / 72f
        val landscape = page.width > page.height
        val wPts = if (landscape) page.height else page.width
        val hPts = if (landscape) page.width else page.height
        val w = (wPts * s).roundToInt()
        val h = (hPts * s).roundToInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val m = Matrix()
        m.setScale(s, s)
        if (landscape) {
            m.postRotate(90f)
            m.postTranslate(w.toFloat(), 0f)
        }
        page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
        return Rendered(bmp, wPts, hPts)
    }

    /**
     * Sends [pageCount] pages to the printer over IPP-over-USB.
     * [render] is called once per page, in order, and its bitmap is recycled after sending.
     */
    fun send(
        usb: UsbManager, device: UsbDevice, label: String, copies: Int,
        mono: Boolean, sides: String?, pageCount: Int,
        cancelled: () -> Boolean, render: (Int) -> Rendered
    ) {
        IppUsbConnection(usb, device).use { c ->
            c.open()
            val capResp = c.postIpp { it.write(Ipp.getPrinterAttributes()) }
            val text = String(capResp, Charsets.ISO_8859_1)
            val usePwg = text.contains("image/pwg-raster")
            if (!usePwg && !text.contains("image/jpeg"))
                throw IOException("Printer supports neither PWG raster nor JPEG")

            if (usePwg) {
                val resp = c.postIpp { out ->
                    out.write(Ipp.printJobHeader(label, "image/pwg-raster", copies, sides, mono))
                    Pwg.writeSync(out)
                    for (i in 0 until pageCount) {
                        if (cancelled()) throw IOException("Cancelled")
                        val r = render(i)
                        Pwg.writePage(
                            out, r.bitmap, r.wPts, r.hPts, !mono,
                            sides != null, sides == "two-sided-short-edge"
                        )
                        r.bitmap.recycle()
                    }
                }
                check(resp)
            } else {
                for (i in 0 until pageCount) {
                    if (cancelled()) throw IOException("Cancelled")
                    val r = render(i)
                    val jpg = ByteArrayOutputStream()
                    r.bitmap.compress(Bitmap.CompressFormat.JPEG, 92, jpg)
                    r.bitmap.recycle()
                    val resp = c.postIpp { out ->
                        out.write(Ipp.printJobHeader(label, "image/jpeg", copies, null, mono))
                        out.write(jpg.toByteArray())
                    }
                    check(resp)
                }
            }
        }
    }

    private fun check(resp: ByteArray) {
        if (!Ipp.isOk(resp)) throw IOException("Printer rejected the job (IPP 0x%04x)".format(Ipp.status(resp)))
    }
}
