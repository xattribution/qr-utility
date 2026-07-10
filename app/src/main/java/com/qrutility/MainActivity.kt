package com.qrutility

import android.Manifest
import android.animation.ArgbEvaluator
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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
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
import com.qrutility.data.BatchExporter
import com.qrutility.data.ConnectionProfile
import com.qrutility.data.CsvUtil
import com.qrutility.data.DataSource
import com.qrutility.data.DbExecutor
import com.qrutility.data.DbType
import com.qrutility.data.JdbcSource
import com.qrutility.data.LanScanner
import com.qrutility.data.LocalDataSource
import com.qrutility.data.LocalDb
import com.qrutility.data.ProfileStore
import java.util.UUID
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

    private val localDb by lazy { LocalDb(this) }
    private val profileStore by lazy { ProfileStore(this) }
    private lateinit var dataSource: DataSource
    private var activeProfileId: String? = null
    private var saveScansToDb = false

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
    private val pickCsv = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { importCsv(it) }
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

        // data
        dataSource = LocalDataSource(localDb)
        b.tabData.setOnClickListener { showTab(3) }
        b.btnAddConn.setOnClickListener { showConnectionEditor(null) }
        b.tvDbConn.setOnClickListener { showConnectionChooser() }
        b.tvDbConn.setOnLongClickListener {
            val id = activeProfileId
            if (id != null) profileStore.all().find { it.id == id }?.let { showConnectionEditor(it) }
            true
        }
        updateConnUi()
        b.btnImportCsv.setOnClickListener { pickCsv.launch("*/*") }
        b.btnClearRecords.setOnClickListener {
            DbExecutor.run({ localDb.clearRecords() }) { refreshDataCounts() }
        }
        b.btnGenerateBatch.setOnClickListener { generateBatch() }
        b.swSaveScans.setOnCheckedChangeListener { _, checked -> saveScansToDb = checked }
        b.btnExportScans.setOnClickListener { exportScans() }
        b.btnClearScans.setOnClickListener {
            DbExecutor.run({ localDb.clearScans() }) { refreshDataCounts() }
        }

        setStatus("STANDBY", Status.OFF)
        showTab(0)   // opens the Scan tab and auto-starts the camera
    }

    /* ---------------- navigation ---------------- */
    private fun showTab(i: Int) {
        currentTab = i
        b.viewScan.visibility = if (i == 0) View.VISIBLE else View.GONE
        b.viewCreate.visibility = if (i == 1) View.VISIBLE else View.GONE
        b.viewLog.visibility = if (i == 2) View.VISIBLE else View.GONE
        b.viewData.visibility = if (i == 3) View.VISIBLE else View.GONE

        styleTab(b.tvTabScan, b.icTabScan, i == 0)
        styleTab(b.tvTabCreate, b.icTabCreate, i == 1)
        styleTab(b.tvTabLog, b.icTabLog, i == 2)
        styleTab(b.tvTabData, b.icTabData, i == 3)

        if (i == 0) {
            when {
                scanning -> if (hasCamera()) b.barcodeView.resume()
                // auto-open the camera when entering Scan, unless a result is on screen
                !awaitingRescan && b.resultPanel.visibility != View.VISIBLE -> startScan()
            }
        } else {
            b.barcodeView.pause()
        }
        if (i == 2) renderLog()
        if (i == 3) refreshDataCounts()
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
        b.reticle.setScanning(true)
        b.tvStartLabel.text = "STOP"
        b.tvVpHint.text = "ALIGN CODE WITHIN FRAME"
        setStatus("SCANNING", Status.OK)
    }

    private fun stopScan() {
        scanning = false
        b.barcodeView.pause()
        b.reticle.setScanning(false)
        if (torchOn) { torchOn = false; b.barcodeView.setTorch(false); b.btnTorch.setBackgroundResource(R.drawable.btn_panel) }
        b.tvStartLabel.text = "START"
        if (b.resultPanel.visibility != View.VISIBLE) {
            b.tvVpHint.text = "CAMERA OFF — TAP START TO RESUME"
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
        b.reticle.lock()
        if (torchOn) { torchOn = false; b.barcodeView.setTorch(false); b.btnTorch.setBackgroundResource(R.drawable.btn_panel) }
        buzz()
        b.tvStartLabel.text = "START"
        b.tvVpHint.text = ""
        b.tvResultValue.text = text
        b.btnOpen.visibility = if (uriRegex.containsMatchIn(text)) View.VISIBLE else View.GONE
        b.resultPanel.visibility = View.VISIBLE
        setStatus("MATCH", Status.OK)
        logAdd("scan", text)
        if (saveScansToDb) {
            DbExecutor.run({ dataSource.insertScan(text, "scan") }) { res ->
                res.onFailure { toast("DB save failed: ${it.message}") }
                if (currentTab == 3) refreshDataCounts()
            }
        }
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

    /* ---------------- data / database ---------------- */
    private fun activateLocal() {
        dataSource = LocalDataSource(localDb)
        activeProfileId = null
        updateConnUi()
        refreshDataCounts()
    }

    private fun activateProfile(p: ConnectionProfile) {
        dataSource = JdbcSource(p)
        activeProfileId = p.id
        updateConnUi()
        b.tvDbStatus.text = "Testing ${dataSource.label}…"
        DbExecutor.run({ dataSource.test() }) { res ->
            res.onSuccess { b.tvDbStatus.text = "Connected: ${dataSource.label}" }
                .onFailure { b.tvDbStatus.text = "Connect failed: ${it.message}" }
        }
    }

    /** Show/hide the on-device-only sections depending on the active source. */
    private fun updateConnUi() {
        b.tvDbConn.text = dataSource.label
        val local = activeProfileId == null
        val localVis = if (local) View.VISIBLE else View.GONE
        b.tvSourceLabel.visibility = localVis
        b.cardRecords.visibility = localVis
        b.rowScanExport.visibility = localVis
        b.tvScanCount.visibility = localVis
    }

    private fun showConnectionChooser() {
        val profiles = profileStore.all()
        val labels = ArrayList<String>()
        labels.add("On-device (SQLite)")
        profiles.forEach { labels.add("${it.name}  ·  ${it.type}") }
        labels.add("＋ Add connection…")
        labels.add("⌕ Scan local network…")
        AlertDialog.Builder(this)
            .setTitle("Data connection")
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    0 -> activateLocal()
                    labels.size - 2 -> showConnectionEditor(null)
                    labels.size - 1 -> showLanScan()
                    else -> activateProfile(profiles[which - 1])
                }
            }
            .show()
    }

    private fun showLanScan() {
        val progress = AlertDialog.Builder(this)
            .setTitle("Scanning local network…")
            .setMessage("Probing for PostgreSQL (5432) and MySQL (3306) hosts.")
            .setCancelable(true)
            .show()
        DbExecutor.run({ LanScanner.discover() }) { res ->
            progress.dismiss()
            res.onSuccess { found ->
                if (found.isEmpty()) {
                    toast("No databases found on the local network")
                } else {
                    val items = found.map { "${it.host}:${it.port}  ·  ${it.type}" }
                    AlertDialog.Builder(this)
                        .setTitle("Found ${found.size}")
                        .setItems(items.toTypedArray()) { _, i ->
                            val d = found[i]
                            showConnectionEditor(
                                null,
                                ConnectionProfile(
                                    id = UUID.randomUUID().toString(),
                                    name = "${d.type} @ ${d.host}",
                                    type = d.type, host = d.host, port = d.port
                                )
                            )
                        }
                        .show()
                }
            }.onFailure { toast("Scan failed: ${it.message}") }
        }
    }

    private fun showConnectionEditor(existing: ConnectionProfile?, prefill: ConnectionProfile? = null) {
        val view = layoutInflater.inflate(R.layout.dialog_connection, null)
        val etName = view.findViewById<EditText>(R.id.etName)
        val spType = view.findViewById<Spinner>(R.id.spType)
        val etHost = view.findViewById<EditText>(R.id.etHost)
        val etPort = view.findViewById<EditText>(R.id.etPort)
        val etDatabase = view.findViewById<EditText>(R.id.etDatabase)
        val etUser = view.findViewById<EditText>(R.id.etUser)
        val etPassword = view.findViewById<EditText>(R.id.etPassword)
        val etQuery = view.findViewById<EditText>(R.id.etQuery)
        val etInsert = view.findViewById<EditText>(R.id.etInsert)
        val btnTest = view.findViewById<Button>(R.id.btnTestConn)
        val tvTest = view.findViewById<TextView>(R.id.tvTestResult)

        val types = listOf(DbType.POSTGRES, DbType.MYSQL)
        spType.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, types.map { it.name }
        )

        (existing ?: prefill)?.let { p ->
            etName.setText(p.name)
            etHost.setText(p.host)
            if (p.port > 0) etPort.setText(p.port.toString())
            etDatabase.setText(p.database)
            etUser.setText(p.user)
            etPassword.setText(p.password)
            etQuery.setText(p.generateQuery)
            etInsert.setText(p.insertStatement)
            spType.setSelection(types.indexOf(p.type).coerceAtLeast(0))
        }

        fun collect(): ConnectionProfile {
            val type = types[spType.selectedItemPosition]
            val host = etHost.text.toString().trim()
            return ConnectionProfile(
                id = existing?.id ?: UUID.randomUUID().toString(),
                name = etName.text.toString().trim().ifBlank { "$type @ $host" },
                type = type,
                host = host,
                port = etPort.text.toString().toIntOrNull() ?: 0,
                database = etDatabase.text.toString().trim(),
                user = etUser.text.toString(),
                password = etPassword.text.toString(),
                generateQuery = etQuery.text.toString().trim(),
                insertStatement = etInsert.text.toString().trim()
            )
        }

        btnTest.setOnClickListener {
            tvTest.setTextColor(ContextCompat.getColor(this, R.color.muted))
            tvTest.text = "Testing…"
            DbExecutor.run({ JdbcSource(collect()).test() }) { res ->
                res.onSuccess {
                    tvTest.setTextColor(ContextCompat.getColor(this, R.color.ok))
                    tvTest.text = "OK — connection succeeded"
                }.onFailure {
                    tvTest.setTextColor(ContextCompat.getColor(this, R.color.warn))
                    tvTest.text = "Failed: ${it.message}"
                }
            }
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add connection" else "Edit connection")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val p = collect()
                if (p.host.isBlank()) { toast("Host is required"); return@setPositiveButton }
                profileStore.upsert(p)
                activateProfile(p)
            }
            .setNegativeButton("Cancel", null)
        if (existing != null) {
            builder.setNeutralButton("Delete") { _, _ ->
                profileStore.delete(existing.id)
                if (activeProfileId == existing.id) activateLocal() else toast("Connection deleted")
            }
        }
        builder.show()
    }

    private fun refreshDataCounts() {
        DbExecutor.run({ localDb.recordCount() to localDb.scanCount() }) { res ->
            res.onSuccess { (records, scans) ->
                b.tvRecordCount.text = "$records rows loaded"
                b.tvScanCount.text = "$scans scans stored"
            }
        }
    }

    private fun importCsv(uri: Uri) {
        b.tvDbStatus.text = "Importing…"
        DbExecutor.run({
            val text = contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            val rows = CsvUtil.parse(text)
            localDb.replaceRecords(rows)
            rows.size
        }) { res ->
            res.onSuccess { n ->
                b.tvDbStatus.text = "Imported $n rows"
                refreshDataCounts()
            }.onFailure { b.tvDbStatus.text = "Import failed: ${it.message}" }
        }
    }

    private fun generateBatch() {
        val ec = ecLevel
        b.btnGenerateBatch.isEnabled = false
        b.tvGenStatus.text = "Reading rows…"
        DbExecutor.run({
            val rows = dataSource.fetchRows()
            if (rows.isEmpty()) throw IllegalStateException("No rows to generate")
            BatchExporter.export(this, rows, ec, "batch-${System.currentTimeMillis()}")
        }) { res ->
            b.btnGenerateBatch.isEnabled = true
            res.onSuccess { r ->
                b.tvGenStatus.text = "Saved ${r.saved} PNGs → ${r.location}" +
                    if (r.failed > 0) "  (${r.failed} failed)" else ""
            }.onFailure { b.tvGenStatus.text = "Generate failed: ${it.message}" }
        }
    }

    private fun exportScans() {
        b.tvDbStatus.text = "Exporting…"
        DbExecutor.run({
            val scans = localDb.scans()
            if (scans.isEmpty()) throw IllegalStateException("No scans to export")
            val csv = CsvUtil.buildScansCsv(scans)
            val loc = saveCsvToDownloads("qr-scans-${System.currentTimeMillis()}.csv", csv)
            scans.size to loc
        }) { res ->
            res.onSuccess { (n, loc) -> b.tvDbStatus.text = "Exported $n scans → $loc" }
                .onFailure { b.tvDbStatus.text = "Export failed: ${it.message}" }
        }
    }

    private fun saveCsvToDownloads(name: String, text: String): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/QRUtility")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create file")
            contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                ?: throw IllegalStateException("Could not write file")
            "Download/QRUtility/$name"
        } else {
            val dir = File(getExternalFilesDir(null), "exports")
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, name)
            f.writeText(text)
            f.absolutePath
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

    /* ---------------- status indicator ---------------- */
    private enum class Status { OFF, OK, WARN }

    private fun setStatus(text: String, status: Status) {
        val colorRes = when (status) {
            Status.OFF -> R.color.muted
            Status.OK -> R.color.ok
            Status.WARN -> R.color.warn
        }
        val c = ContextCompat.getColor(this, colorRes)
        b.tvStatus.text = text
        b.tvStatus.setTextColor(c)
        b.statusDot.setBackgroundColor(c)
    }

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

/* ==================== reticle overlay ====================
 * Scanning state is shown by the corner brackets cycling colour
 * grey -> blue -> green (no sweeping line). A successful decode
 * snaps them to solid green.
 */
class ReticleView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val grey = Color.parseColor("#8A93A0")
    private val blue = Color.parseColor("#2F6BFF")
    private val green = Color.parseColor("#2FD07B")

    private val evaluator = ArgbEvaluator()
    private val bracket = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = grey; style = Paint.Style.STROKE; strokeCap = Paint.Cap.SQUARE
        strokeWidth = dp(3f)
    }

    private var scanning = false
    private var curColor = grey

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1600
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = LinearInterpolator()
        addUpdateListener { curColor = colorAt(it.animatedValue as Float); invalidate() }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    // 3 equal segments across one cycle: grey->blue, blue->green, green->grey
    private fun colorAt(t: Float): Int {
        val seg = t * 3f
        return when {
            seg < 1f -> evaluator.evaluate(seg, grey, blue) as Int
            seg < 2f -> evaluator.evaluate(seg - 1f, blue, green) as Int
            else     -> evaluator.evaluate(seg - 2f, green, grey) as Int
        }
    }

    /** Start/stop the colour cycle. Off = static grey. */
    fun setScanning(on: Boolean) {
        if (on == scanning) return
        scanning = on
        if (on) {
            if (!animator.isStarted) animator.start()
        } else {
            animator.cancel()
            curColor = grey
            invalidate()
        }
    }

    /** Snap to solid green on a successful decode. */
    fun lock() {
        scanning = false
        animator.cancel()
        curColor = green
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val box = minOf(width, height) * 0.62f
        val cx = width / 2f; val cy = height / 2f
        val half = box / 2f
        val l = cx - half; val t = cy - half; val r = cx + half; val btm = cy + half
        val len = dp(28f)

        bracket.color = curColor
        canvas.drawLine(l, t, l + len, t, bracket); canvas.drawLine(l, t, l, t + len, bracket)
        canvas.drawLine(r, t, r - len, t, bracket); canvas.drawLine(r, t, r, t + len, bracket)
        canvas.drawLine(l, btm, l + len, btm, bracket); canvas.drawLine(l, btm, l, btm - len, bracket)
        canvas.drawLine(r, btm, r - len, btm, bracket); canvas.drawLine(r, btm, r, btm - len, bracket)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }
}
