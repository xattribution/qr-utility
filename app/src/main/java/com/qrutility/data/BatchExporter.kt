package com.qrutility.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File
import java.io.FileOutputStream

data class BatchResult(val saved: Int, val failed: Int, val location: String)

/**
 * Encodes each [Row] to a QR PNG and saves it under Pictures/QRUtility/<folder>.
 * Blocking — call from [DbExecutor].
 */
object BatchExporter {

    fun export(
        context: Context,
        rows: List<Row>,
        ec: ErrorCorrectionLevel,
        folder: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): BatchResult {
        val safeFolder = sanitize(folder).ifBlank { "batch" }
        var saved = 0
        var failed = 0
        rows.forEachIndexed { i, row ->
            val value = row.content
            if (value.isEmpty()) { failed++; return@forEachIndexed }
            val name = "%04d-%s.png".format(i + 1, sanitize(row.label).ifBlank { "qr" }.take(48))
            try {
                val bmp = QrEncoder.encode(value, ec)
                if (savePng(context, bmp, safeFolder, name)) saved++ else failed++
                bmp.recycle()
            } catch (e: Exception) {
                failed++
            }
            onProgress(i + 1, rows.size)
        }
        return BatchResult(saved, failed, "Pictures/QRUtility/$safeFolder")
    }

    private fun savePng(context: Context, bmp: Bitmap, folder: String, name: String): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/QRUtility/$folder")
            }
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return false
            context.contentResolver.openOutputStream(uri)?.use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            } ?: return false
            true
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "QRUtility/$folder"
            )
            if (!dir.exists()) dir.mkdirs()
            FileOutputStream(File(dir, name)).use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            true
        }
    }

    private fun sanitize(s: String): String =
        s.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
}
