package com.qrutility

import android.Manifest
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contracts.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ResultPoint
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.qrutility.databinding.ActivityMainBinding
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private var currentTab = 0
    private var scanning = false
    private var awaitingRescan = false
    private var torchOn = false
    private var lastText: String? = null

    private var ecLevel = ErrorCorrectionLevel.M
    private var currentBitmap: Bitmap? = null
    private var pendingSave: Bitmap? = null

    private val prefs by lazy { getSharedPreferences("qrutil", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private val genRunnable = Runnable { generateQr() }
    private val uriRegex = Regex("^(https?://|mailto:|tel:|geo:|wifi:|smsto:|matmsg:)", RegexOption.IGNORE_CASE)

    private val cameraPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startScan() else {
            setStatus("CAMERA BLOCKED", Status.WARN)
            b.tvVpHint.text = "NO CAMERA ACCESS — USE IMAGE"
            toast("Camera permission denied")
        }
    }
    private val storagePerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val bmp = pendingSave; pendingSave = null
        if (granted && bmp != null) savePng(bmp) else if (!granted) toast("Storage permission needed to save")
    }
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { decodeImage(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // scanner engine
        b.barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
        b.barcodeView.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult) {
                if (!scanning || awaitingRescan) return
                onDecoded(result.text)
            }
            override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>) {}
        })

        // tabs
        b.tabScan.setOnClickListener { showTab(0) }
        b.tabCreate.setOnClickListener { showTab(1) }
        b.tabLog.setOnClickListener { showTab(2) }

        // scan controls
        b.btnStart.setOnClickListener { if (scanning) stopScan() else startScan() }
        b.btnTorch.setOnClickListener { toggleTorch() }
        b.btnImage.setOnClickListener { pickImage.launch("image/*") }

        // result actions
        b.btnCopy.setOnClickListener { copy(b.tvResultValue.text.toString()) }
        b.btnShare.setOnClickListener { share(b.tvResultValue.text.toString()) }
        b.btnOpen.setOnClickListener { openUri(b.tvResultValue.text.toString()) }
        b.btnRescan.setOnClickListener { rescan() }

        // create
        b.etInput.doAfterTextChanged {
            handler.removeCallbacks(genRunnable)
            handler.postDelayed(genRunnable, 140)
        }
        b.segL.setOnClickListener { selectEc(ErrorCorrectionLevel.L, it as TextView) }
        b.segM.setOnClickListener { selectEc(ErrorCorrectionLevel.M, it as TextView) }
        b.segQ.setOnClickListener { selectEc(ErrorCorrectionLevel.Q, it as TextView) }
        b.segH.setOnClickListener { selectEc(ErrorCorrectionLevel.H, it as TextView) }
        selectEc(ErrorCorrectionLevel.M, b.segM)  // default
        b.btnDownload.setOnClickListener { currentBitmap?.let { savePng(it) } ?: toast("Nothing to save") }
        b.btnAddLog.setOnClickListener {
            val t = b.etInput.text.toString().trim()
            if (t.isEmpty()) toast("Nothing to log") else { logAdd("gen", t); toast("Added to log") }
        }

        // log
        b.btnClear.setOnClickListener { onClearTap() }

        showTab(0)
        setStatus("STANDBY", Status.OFF)
    }

    /* ---------------- navigation ---------------- */
    private fun showTab(i: Int) {
        currentTab = i
        b.viewScan.visibility = if (i == 0) View.VISIBLE else View.GONE
        b.viewCreate.visibility = if (i == 1) View.VISIBLE else View.GONE
        b.viewLog.visibility = if (i == 2) View.VISIBLE else View.GONE

        styleTab(b.tvTabScan, b.icTabScan, i == 0)
        styleTab(b.tvTabCreate, b.icTabCreate, i == 1)
        styleTab(b.tvTabLog, b.icTabLog, i == 2)

        if (i == 0) {
            if (scanning && hasCamera()) b.barcodeView.resume()
        } else {
            b.barcodeView.pause()
        }
        if (i == 2) renderLog()
    }

    private fun styleTab(label: TextView, icon: ImageView, active: Boolean) {
        val c = ContextCompat.getColor(this, if (active) R.color.blue else R.color.muted)
        label.setTextColor(c)
        icon.setColorFilter(c)
    }

    /* ---------------- scanning ---------------- */
    private fun hasCamera() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startScan() {
        if (!hasCamera()) { cameraPerm.launch(Manifest.permission.CAMERA); return }
        scanning = true
        awaitingRescan = false
        b.resultPanel.visibility = View.GONE
        b.barcodeView.resume()
        b.tvStartLabel.text = "STOP"
        b.tvVpHint.text = "ALIGN CODE WITHIN FRAME"
        setStatus("SCANNING", Status.OK)
    }

    private fun stopScan() {
        scanning = false
        b.barcodeView.pause()
        if (torchOn) { torchOn = false; b.barcodeView.setTorch(false); b.btnTorch.setBackgroundResource(R.drawable.btn_panel) }
        b.tvStartLabel.text = "START"
        if (b.resultPanel.visibility != View.VISIBLE) {
            b.tvVpHint.text = "CAMERA OFF — PRESS START"
            setStatus("STANDBY", Status.OFF)
        }
    }

    private fun toggleTorch() {
        if (!scanning) { toast("Start the camera first"); return }
        torchOn = !torchOn
        b.barcodeView.setTorch(torchOn)
        b.btnTorch.setBackgroundResource(if (torchOn) R.drawable.btn_blue else R.drawable.btn_panel)
    }

    private fun onDecoded(text: String) {
        lastText = text
        awaitingRescan = true
        scanning = false
        b.barcodeView.pause()
        if (torchOn) { torchOn = false; b.barcodeView.setTorch(false); b.btnTorch.setBackgroundResource(R.drawable.btn_panel) }
        buzz()
        b.tvStartLabel.text = "START"
        b.tvVpHint.text = ""
        b.tvResultValue.text = text
        b.btnOpen.visibility = if (uriRegex.containsMatchIn(text)) View.VISIBLE else View.GONE
        b.resultPanel.visibility = View.VISIBLE
        setStatus("MATCH", Status.OK)
        logAdd("scan", text)
    }

    private fun rescan() {
        b.resultPanel.visibility = View.GONE
        lastText = null
        awaitingRescan = false
        startScan()
    }

    private fun decodeImage(uri: Uri) {
        val bmp = try {
            contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) { null }
        if (bmp == null) { toast("Could not read image"); return }
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val source = RGBLuminanceSource(w, h, px)
        val binary = BinaryBitmap(HybridBinarizer(source))
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true
        )
        try {
            val res = MultiFormatReader().decode(binary, hints)
            stopScan()
            onDecoded(res.text)
        } catch (e: Exception) {
            toast("No QR code found in image")
        }
    }

    /* ---------------- generating ---------------- */
    private fun selectEc(level: ErrorCorrectionLevel, selected: TextView) {
        ecLevel = level
        for (seg in listOf(b.segL, b.segM, b.segQ, b.segH)) {
            if (seg === selected) {
                seg.setBackgroundResource(R.drawable.btn_blue)
                seg.setTextColor(ContextCompat.getColor(this, R.color.white))
            } else {
                seg.setBackgroundColor(ContextCompat.getColor(this, R.color.panel))
                seg.setTextColor(ContextCompat.getColor(this, R.color.muted))
            }
        }
        generateQr()
    }

    private fun generateQr() {
        val text = b.etInput.text.toString()
        if (text.isEmpty()) {
            b.qrFrame.visibility = View.GONE
            b.tvQrEmpty.visibility = View.VISIBLE
            b.tvQrEmpty.text = "AWAITING INPUT"
            b.tvQrMeta.text = ""
            currentBitmap = null
            setDownloadEnabled(false)
            return
        }
        try {
            val hints = mapOf<EncodeHintType, Any>(
                EncodeHintType.ERROR_CORRECTION to ecLevel,
                EncodeHintType.MARGIN to 2,
                EncodeHintType.CHARACTER_SET to "UTF-8"
            )
            val size = 640
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
            val w = matrix.width; val h = matrix.height
            val pixels = IntArray(w * h)
            val black = Color.BLACK; val white = Color.WHITE
            for (y in 0 until h) {
                val off = y * w
                for (x in 0 until w) pixels[off + x] = if (matrix.get(x, y)) black else white
            }
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.setPixels(pixels, 0, w, 0, 0, w, h)
            currentBitmap = out
            b.ivQr.setImageBitmap(out)
            b.qrFrame.visibility = View.VISIBLE
            b.tvQrEmpty.visibility = View.GONE
            b.tvQrMeta.text = "EC ${ecLevel.name} · ${text.toByteArray().size} B"
            setDownloadEnabled(true)
        } catch (e: Exception) {
            b.qrFrame.visibility = View.GONE
            b.tvQrEmpty.visibility = View.VISIBLE
            b.tvQrEmpty.text = "TOO MUCH DATA FOR ONE CODE"
            b.tvQrMeta.text = ""
            currentBitmap = null
            setDownloadEnabled(false)
        }
    }

    private fun setDownloadEnabled(on: Boolean) {
        b.btnDownload.isEnabled = on
        b.btnDownload.alpha = if (on) 1f else 0.4f
    }

    private fun savePng(bmp: Bitmap) {
        val name = "qr-${System.currentTimeMillis()}.png"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/QRUtility")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) { toast("Save failed"); return }
            try {
                contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                toast("Saved to Pictures/QRUtility")
            } catch (e: Exception) { toast("Save failed") }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                pendingSave = bmp
                storagePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return
            }
            try {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "QRUtility")
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, name)
                FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf("image/png"), null)
                toast("Saved to Pictures/QRUtility")
            } catch (e: Exception) { toast("Save failed") }
        }
    }

    /* ---------------- log ---------------- */
    private fun logRead(): JSONArray =
        try { JSONArray(prefs.getString("log", "[]")) } catch (e: Exception) { JSONArray() }

    private fun logWrite(arr: JSONArray) = prefs.edit().putString("log", arr.toString()).apply()

    private fun logAdd(type: String, value: String) {
        val arr = logRead()
        if (arr.length() > 0) {
            val f = arr.getJSONObject(0)
            if (f.optString("value") == value && f.optString("type") == type) return
        }
        val next = JSONArray()
        next.put(JSONObject().put("type", type).put("value", value).put("ts", System.currentTimeMillis()))
        val keep = minOf(arr.length(), 199)
        for (i in 0 until keep) next.put(arr.getJSONObject(i))
        logWrite(next)
        if (currentTab == 2) renderLog()
    }

    private fun renderLog() {
        val container = b.logContainer
        container.removeAllViews()
        val arr = logRead()
        if (arr.length() == 0) {
            val empty = TextView(this).apply {
                text = "NO ENTRIES"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.muted))
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                letterSpacing = 0.15f
                setPadding(dp(20), dp(48), dp(20), dp(48))
                gravity = android.view.Gravity.CENTER
            }
            container.addView(empty)
            return
        }
        val fmt = SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.US)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val type = o.optString("type")
            val value = o.optString("value")
            val row = layoutInflater.inflate(R.layout.item_log, container, false)
            val tag = row.findViewById<TextView>(R.id.tvTag)
            val v = row.findViewById<TextView>(R.id.tvVal)
            val time = row.findViewById<TextView>(R.id.tvTime)
            if (type == "scan") { tag.text = "SCAN"; tag.setTextColor(ContextCompat.getColor(this, R.color.ok)) }
            else { tag.text = "GEN"; tag.setTextColor(ContextCompat.getColor(this, R.color.blue)) }
            v.text = value
            time.text = fmt.format(Date(o.optLong("ts")))
            row.setOnClickListener { copy(value) }
            container.addView(row)
            val divider = View(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.line))
            }
            container.addView(divider)
        }
    }

    private var clearArmed = false
    private val disarm = Runnable {
        clearArmed = false
        b.btnClear.text = "CLEAR"
        b.btnClear.setTextColor(ContextCompat.getColor(this, R.color.muted))
        b.btnClear.setBackgroundResource(R.drawable.outline_box)
    }
    private fun onClearTap() {
        if (!clearArmed) {
            clearArmed = true
            b.btnClear.text = "CONFIRM"
            b.btnClear.setTextColor(ContextCompat.getColor(this, R.color.blue))
            b.btnClear.setBackgroundResource(R.drawable.outline_blue)
            handler.postDelayed(disarm, 2500)
        } else {
            handler.removeCallbacks(disarm)
            prefs.edit().remove("log").apply()
            disarm.run()
            renderLog()
            toast("Log cleared")
        }
    }

    /* ---------------- utils ---------------- */
    private fun copy(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("qr", text))
        toast("Copied")
    }

    private fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "Share"))
    }

    private fun openUri(text: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(text)))
        } catch (e: Exception) { toast("Can't open this") }
    }

    private fun buzz() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
                v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (e: Exception) { /* no vibrator */ }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /* ---------------- lifecycle ---------------- */
    override fun onResume() {
        super.onResume()
        if (currentTab == 0 && scanning && hasCamera()) b.barcodeView.resume()
    }

    override fun onPause() {
        super.onPause()
        b.barcodeView.pause()
    }
}

/* ==================== reticle overlay ==================== */
class ReticleView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val blue = Color.parseColor("#2F6BFF")
    private val bracket = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = blue; style = Paint.Style.STROKE; strokeCap = Paint.Cap.SQUARE
        strokeWidth = dp(3f)
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = blue; strokeWidth = dp(2f) }

    private var frac = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2200
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = LinearInterpolator()
        addUpdateListener { frac = it.animatedValue as Float; invalidate() }
    }

    init { animator.start() }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val box = minOf(width, height) * 0.62f
        val cx = width / 2f; val cy = height / 2f
        val half = box / 2f
        val l = cx - half; val t = cy - half; val r = cx + half; val btm = cy + half
        val len = dp(28f)

        canvas.drawLine(l, t, l + len, t, bracket); canvas.drawLine(l, t, l, t + len, bracket)
        canvas.drawLine(r, t, r - len, t, bracket); canvas.drawLine(r, t, r, t + len, bracket)
        canvas.drawLine(l, btm, l + len, btm, bracket); canvas.drawLine(l, btm, l, btm - len, bracket)
        canvas.drawLine(r, btm, r - len, btm, bracket); canvas.drawLine(r, btm, r, btm - len, bracket)

        val y = t + box * frac
        canvas.drawLine(l + dp(2f), y, r - dp(2f), y, line)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }
}
