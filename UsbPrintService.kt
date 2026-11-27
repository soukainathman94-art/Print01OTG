package app.usbprint

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.pdf.PdfRenderer
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.print.PrintAttributes
import android.print.PrintJobId
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class UsbPrintService : PrintService() {

    private val usb by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }
    private val cancelled = ConcurrentHashMap.newKeySet<PrintJobId>()

    // ---------------------------------------------------------------- discovery

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession = Session()

    private inner class Session : PrinterDiscoverySession() {
        private val known = HashSet<PrinterId>()
        private var receiver: BroadcastReceiver? = null

        private fun refresh() {
            val infos = UsbPrinter.ippPrinters(usb).map { buildInfo(it) }
            val ids = infos.map { it.id }.toSet()
            val gone = known.filter { it !in ids }
            if (gone.isNotEmpty()) removePrinters(gone)
            known.clear(); known.addAll(ids)
            addPrinters(infos)
        }

        override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
            refresh()
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) = refresh()
            }
            val f = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(r, f)
            receiver = r
        }

        override fun onStopPrinterDiscovery() {
            receiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
            receiver = null
        }

        override fun onValidatePrinters(printerIds: MutableList<PrinterId>) = refresh()
        override fun onStartPrinterStateTracking(printerId: PrinterId) {}
        override fun onStopPrinterStateTracking(printerId: PrinterId) {}
        override fun onDestroy() = onStopPrinterDiscovery()
    }

    private fun buildInfo(d: UsbDevice): PrinterInfo {
        val id = generatePrinterId(UsbPrinter.localId(d))
        val caps = PrinterCapabilitiesInfo.Builder(id)
            .addMediaSize(PrintAttributes.MediaSize.ISO_A4, true)
            .addMediaSize(PrintAttributes.MediaSize.NA_LETTER, false)
            .addMediaSize(PrintAttributes.MediaSize.ISO_A5, false)
            .addResolution(PrintAttributes.Resolution("r300", "300 dpi", Pwg.DPI, Pwg.DPI), true)
            .setColorModes(
                PrintAttributes.COLOR_MODE_COLOR or PrintAttributes.COLOR_MODE_MONOCHROME,
                PrintAttributes.COLOR_MODE_COLOR
            )
            .setDuplexModes(
                PrintAttributes.DUPLEX_MODE_NONE or PrintAttributes.DUPLEX_MODE_LONG_EDGE or
                    PrintAttributes.DUPLEX_MODE_SHORT_EDGE,
                PrintAttributes.DUPLEX_MODE_NONE
            )
            .setMinMargins(PrintAttributes.Margins(170, 170, 170, 170))
            .build()
        val name = d.productName ?: "USB printer"
        return PrinterInfo.Builder(id, name, PrinterInfo.STATUS_IDLE).setCapabilities(caps).build()
    }

    // ---------------------------------------------------------------- printing

    override fun onRequestCancelPrintJob(job: PrintJob) {
        cancelled.add(job.id)
        job.cancel()
    }

    override fun onPrintJobQueued(job: PrintJob) {
        if (job.isQueued) job.start()
        Thread { runJob(job) }.start()
    }

    private fun runJob(job: PrintJob) {
        try {
            doPrint(job)
            if (!cancelled.contains(job.id)) job.complete()
        } catch (e: Exception) {
            if (!cancelled.contains(job.id)) job.fail(e.message ?: "Print failed")
        } finally {
            cancelled.remove(job.id)
        }
    }

    private fun doPrint(job: PrintJob) {
        val info = job.info
        val lid = info.printerId?.localId ?: throw IOException("No printer selected")
        val device = UsbPrinter.ippPrinters(usb).firstOrNull { UsbPrinter.localId(it) == lid }
            ?: throw IOException("Printer is not connected")
        if (!UsbPrinter.ensurePermission(this, usb, device)) throw IOException("USB permission denied")

        val attrs = info.attributes
        val mono = attrs.colorMode == PrintAttributes.COLOR_MODE_MONOCHROME
        val sides = when (attrs.duplexMode) {
            PrintAttributes.DUPLEX_MODE_LONG_EDGE -> "two-sided-long-edge"
            PrintAttributes.DUPLEX_MODE_SHORT_EDGE -> "two-sided-short-edge"
            else -> null
        }

        val pfd = job.document.data ?: throw IOException("No document data")
        val renderer = PdfRenderer(pfd)
        try {
            UsbPrinter.send(
                usb, device, info.label ?: "Document", info.copies, mono, sides,
                renderer.pageCount, { cancelled.contains(job.id) }
            ) { i ->
                val page = renderer.openPage(i)
                try { UsbPrinter.renderPdfPage(page) } finally { page.close() }
            }
        } finally {
            renderer.close()
            try { pfd.close() } catch (_: Exception) {}
        }
    }
}
