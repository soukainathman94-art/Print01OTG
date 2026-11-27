package app.usbprint

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.usb.UsbManager
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * Pick a PDF or image, see exactly the sheets that will come out of the printer
 * (zoom, scale, page range, paper, orientation), then print over USB.
 */
class PreviewActivity : Activity() {

    private val ar by lazy { resources.configuration.locales[0].language == "ar" }
    private fun t(en: String, a: String) = if (ar) a else en
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val papers = listOf(
        "A4" to floatArrayOf(595.28f, 841.89f),
        "Letter" to floatArrayOf(612f, 792f),
        "A5" to floatArrayOf(419.53f, 595.28f)
    )

    private var src: Source? = null
    private val exec = Executors.newSingleThreadExecutor()
    private val gen = AtomicInteger()
    private val cancel = AtomicBoolean(false)
    @Volatile private var plan: List<Sheet> = emptyList()

    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var scaleLabel: TextView
    private lateinit var paperSp: Spinner
    private lateinit var orientSp: Spinner
    private lateinit var sizeSp: Spinner
    private lateinit var colorSp: Spinner
    private lateinit var duplexSp: Spinner
    private lateinit var seek: SeekBar
    private lateinit var fromEt: EditText
    private lateinit var toEt: EditText
    private lateinit var copiesEt: EditText
    private lateinit var printBtn: Button

    companion object { const val MAX_PREVIEW = 24 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        loadDocument()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancel.set(true)
        exec.shutdownNow()
        src?.close()
    }

    // ------------------------------------------------------------------ UI

    private fun spinner(items: List<String>): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(this@PreviewActivity, android.R.layout.simple_spinner_dropdown_item, items)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = refresh()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun number(initial: String, hint: String): EditText = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(initial)
        this.hint = hint
        addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = refresh()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    private fun row(vararg v: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (x in v) addView(x, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        preview = PreviewView(this)
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(0xFF2A2A2A.toInt())
        }
        status = TextView(this).apply { textSize = 16f; text = t("Loading…", "جارٍ التحميل…") }
        panel.addView(status)

        paperSp = spinner(papers.map { it.first })
        orientSp = spinner(listOf(t("Auto", "تلقائي"), t("Portrait", "عمودي"), t("Landscape", "أفقي")))
        sizeSp = spinner(listOf(t("Fit to page", "ملاءمة الصفحة"), t("Actual size", "الحجم الحقيقي")))
        panel.addView(row(paperSp, orientSp, sizeSp))

        scaleLabel = TextView(this).apply { text = t("Scale 100%", "التكبير 100%") }
        panel.addView(scaleLabel)
        seek = SeekBar(this).apply {
            max = 390
            progress = 90
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    scaleLabel.text = t("Scale ", "التكبير ") + (p + 10) + "%"
                    refresh()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        panel.addView(seek)

        fromEt = number("1", t("From page", "من صفحة"))
        toEt = number("1", t("To page", "إلى صفحة"))
        copiesEt = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("1")
            hint = t("Copies", "النسخ")
        }
        panel.addView(row(fromEt, toEt, copiesEt))

        colorSp = spinner(listOf(t("Color", "ألوان"), t("B&W", "أبيض وأسود")))
        duplexSp = spinner(listOf(t("One-sided", "وجه واحد"), t("Two-sided (long)", "وجهين (طويل)"), t("Two-sided (short)", "وجهين (قصير)")))
        panel.addView(row(colorSp, duplexSp))

        printBtn = Button(this).apply {
            text = t("Print", "طباعة")
            setOnClickListener { doPrint() }
        }
        panel.addView(printBtn)

        root.addView(ScrollView(this).apply { addView(panel) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun readSettings(): Settings {
        val p = papers[paperSp.selectedItemPosition].second
        return Settings(
            p[0], p[1], orientSp.selectedItemPosition, sizeSp.selectedItemPosition == 1,
            seek.progress + 10,
            fromEt.text.toString().toIntOrNull() ?: 1,
            toEt.text.toString().toIntOrNull() ?: 9999
        )
    }

    // ------------------------------------------------------------------ loading

    @Suppress("DEPRECATION")
    private fun loadDocument() {
        Thread {
            try {
                val uri: Uri = intent.data ?: intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    ?: throw IOException("No file")
                val f = File(cacheDir, "input")
                (contentResolver.openInputStream(uri) ?: throw IOException("Cannot open file")).use { i ->
                    f.outputStream().use { o -> i.copyTo(o) }
                }
                val magic = ByteArray(4)
                f.inputStream().use { it.read(magic) }
                val s: Source = if (String(magic, Charsets.ISO_8859_1) == "%PDF")
                    PdfSource(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY))
                else decodeImage(f)
                src = s
                runOnUiThread {
                    toEt.setText(s.count.toString())
                    refresh()
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = e.message ?: t("Cannot open file", "تعذر فتح الملف") }
            }
        }.start()
    }

    private fun decodeImage(f: File): Source {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var ss = 1
        while (maxOf(o.outWidth, o.outHeight) / ss > 4096) ss *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = ss })
            ?: throw IOException("Cannot read image")
        val deg = when (ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
            3 -> 180f; 6 -> 90f; 8 -> 270f; else -> 0f
        }
        val img = if (deg != 0f)
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
        else bmp
        return ImageSource(img, ss)
    }

    // ------------------------------------------------------------------ preview

    private fun refresh() {
        val s = src ?: return
        val st = readSettings()
        val g = gen.incrementAndGet()
        exec.execute {
            if (g != gen.get()) return@execute
            val p = Layout.plan(s, st)
            plan = p
            runOnUiThread {
                if (g == gen.get()) status.text =
                    t("Sheets: ${p.size}", "عدد الأوراق: ${p.size}") +
                        (if (p.size > MAX_PREVIEW) t("  (preview shows first $MAX_PREVIEW)", "  (المعاينة تعرض أول $MAX_PREVIEW)") else "")
            }
            val bmps = ArrayList<Bitmap>()
            for (i in 0 until minOf(p.size, MAX_PREVIEW)) {
                if (g != gen.get()) return@execute
                bmps.add(Layout.renderSheet(s, p[i], 96f, false))
            }
            runOnUiThread { if (g == gen.get()) preview.setSheets(bmps) }
        }
    }

    // ------------------------------------------------------------------ printing

    private fun doPrint() {
        val s = src ?: return
        val p = plan
        if (p.isEmpty()) return
        val st = readSettings()
        val copies = (copiesEt.text.toString().toIntOrNull() ?: 1).coerceIn(1, 99)
        val mono = colorSp.selectedItemPosition == 1
        val sides = when (duplexSp.selectedItemPosition) {
            1 -> "two-sided-long-edge"
            2 -> "two-sided-short-edge"
            else -> null
        }
        cancel.set(false)
        printBtn.isEnabled = false
        status.text = t("Printing ${p.size} sheet(s)…", "جارٍ طباعة ${p.size} ورقة…")
        Thread {
            val msg = try {
                val usb = getSystemService(USB_SERVICE) as UsbManager
                val dev = UsbPrinter.ippPrinters(usb).firstOrNull()
                    ?: throw IOException(t("No USB printer found", "لم يتم العثور على طابعة USB"))
                if (!UsbPrinter.ensurePermission(this, usb, dev)) throw IOException("USB permission denied")
                UsbPrinter.send(usb, dev, "UsbPrint", copies, mono, sides, p.size, { cancel.get() }) { i ->
                    val b = Layout.renderSheet(s, p[i], Pwg.DPI.toFloat(), true)
                    UsbPrinter.Rendered(b, st.paperW.roundToInt(), st.paperH.roundToInt())
                }
                t("Done", "تمت الطباعة")
            } catch (e: Exception) {
                e.message ?: t("Failed", "فشلت الطباعة")
            }
            runOnUiThread {
                status.text = msg
                printBtn.isEnabled = true
            }
        }.start()
    }
}
