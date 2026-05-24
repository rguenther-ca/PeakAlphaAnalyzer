package com.example.pafanalyzer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.concurrent.thread

/**
 * MainActivity.kt
 *
 * Single-file UI + analyzer integration.
 * - Lets user pick a CSV file from storage
 * - Runs PafAnalyzer.analyzeFile in background
 * - Displays Argmax, Parabolic, CoG, Rapid-IAF, SNR, Prominence, Width, continuity
 * - Shows a spectrogram with ridge overlay (SpectrogramView embedded)
 *
 * NOTE: This activity is intentionally self-contained and uses the PafAnalyzer class above.
 */

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private lateinit var loadButton: Button
    private lateinit var statusText: TextView
    private lateinit var resultsText: TextView
    private lateinit var spectrogramView: SpectrogramView
    private val analyzer = PafAnalyzer(256.0)

    private val requestPermissionCode = 1234

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { handlePickedFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // simple vertical layout
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        loadButton = Button(this).apply {
            text = "Pick EEG CSV file"
            setOnClickListener { pickFile() }
        }
        statusText = TextView(this).apply { text = "No file loaded" }
        resultsText = TextView(this).apply { text = "" }
        spectrogramView = SpectrogramView(this)

        layout.addView(loadButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        layout.addView(statusText, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        layout.addView(resultsText, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        layout.addView(spectrogramView, LinearLayout.LayoutParams.MATCH_PARENT, 800)

        setContentView(layout)

        // request storage permission if needed
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), requestPermissionCode)
        }
    }

    private fun pickFile() {
        pickFileLauncher.launch("text/*")
    }

    private fun handlePickedFile(uri: Uri) {
        statusText.text = "Loading file..."
        // copy to cache
        thread {
            try {
                val file = copyUriToFile(uri)
                runOnUiThread { statusText.text = "Analyzing ${file.name} ..." }
                val result = analyzer.analyzeFile(file)
                runOnUiThread {
                    displayResults(result, file)
                }
            } catch (ex: Exception) {
                Log.e(TAG, "Error handling file: ${ex.message}", ex)
                runOnUiThread { statusText.text = "Error: ${ex.message}" }
            }
        }
    }

    private fun displayResults(result: PafAnalyzer.FullAnalysisResult, file: File) {
        val c = result.candidates
        val sb = StringBuilder()
        sb.append("File: ${file.name}\n")
        sb.append("Argmax IAF: ${"%.2f".format(c.argmaxHz)} Hz\n")
        sb.append("Parabolic refined: ${"%.2f".format(c.parabolicHz)} Hz\n")
        sb.append("Center of Gravity: ${"%.2f".format(c.cogHz)} Hz\n")
        sb.append("Rapid IAF (median windows): ${"%.2f".format(c.rapidIafHz)} Hz\n")
        sb.append("SNR: ${"%.2f".format(c.snr)}\n")
        sb.append("Prominence: ${"%.4f".format(c.peakProminence)}\n")
        sb.append("Peak width (Hz): ${"%.2f".format(c.peakWidthHz)}\n")
        sb.append("Percent windows clean: ${"%.1f".format(c.percentWindowsClean * 100)}%\n")
        sb.append("PAF mean ± SD: ${"%.2f".format(c.pafMean)} ± ${"%.2f".format(c.pafStd)} Hz\n")
        resultsText.text = sb.toString()
        statusText.text = "Analysis complete"

        // show spectrogram if available
        if (result.spectrogram != null) {
            spectrogramView.setSpectrogram(result.spectrogram)
        } else {
            spectrogramView.clear()
        }

        // write diagnostics CSV to external storage (optional)
        try {
            val outDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            if (outDir != null) {
                val outFile = File(outDir, file.nameWithoutExtension + "_diagnostics.csv")
                analyzer.writeDiagnosticsCsv(outFile.absolutePath, result.freqs, result.psd, result.windowDiags)
                Toast.makeText(this, "Diagnostics written to ${outFile.absolutePath}", Toast.LENGTH_LONG).show()
            }
        } catch (ex: Exception) {
            Log.w(TAG, "Could not write diagnostics: ${ex.message}")
        }
    }

    private fun copyUriToFile(uri: Uri): File {
        val cursor = contentResolver.query(uri, null, null, null, null)
        var name = "eeg.csv"
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = it.getString(idx)
            }
        }
        val input: InputStream = contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("Cannot open file")
        val outFile = File(cacheDir, name)
        FileOutputStream(outFile).use { out ->
            val buf = ByteArray(8192)
            var len: Int
            while (input.read(buf).also { len = it } > 0) {
                out.write(buf, 0, len)
            }
        }
        input.close()
        return outFile
    }

    // -------------------------
    // SpectrogramView (embedded)
    // -------------------------
    inner class SpectrogramView(context: android.content.Context) : View(context) {
        private var specResult: SpectrogramResult? = null
        private var bmp: Bitmap? = null
        private val paint = Paint()
        private val ridgePaint = Paint().apply {
            color = Color.YELLOW
            strokeWidth = 4f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 36f
            isAntiAlias = true
        }

        fun setSpectrogram(res: SpectrogramResult) {
            specResult = res
            // build bitmap on background thread
            thread {
                bmp = buildBitmapFromSpectrogram(res)
                postInvalidate()
            }
        }

        fun clear() {
            specResult = null
            bmp = null
            postInvalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(Color.BLACK)
            val w = width.toFloat(); val h = height.toFloat()
            val res = specResult
            if (bmp != null) {
                val src = Rect(0, 0, bmp!!.width, bmp!!.height)
                val dst = Rect(0, 0, width, height)
                canvas.drawBitmap(bmp!!, src, dst, null)
                // overlay ridge
                if (res != null) {
                    val cols = res.times.size
                    val rows = res.freqs.size
                    val path = Path()
                    var first = true
                    for (t in res.ridgeFreqs.indices) {
                        val rf = res.ridgeFreqs[t]
                        if (rf.isNaN()) continue
                        // map rf to y index
                        val fIdx = res.freqs.indexOfFirst { it >= rf }.coerceAtLeast(0)
                        val x = t.toFloat() / cols * w
                        val y = (1f - fIdx.toFloat() / rows) * h
                        if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
                    }
                    canvas.drawPath(path, ridgePaint)
                    // continuity badge
                    val continuity = res.ridgeContinuity
                    val badgeColor = when {
                        continuity >= 0.7 -> Color.GREEN
                        continuity >= 0.4 -> Color.YELLOW
                        else -> Color.RED
                    }
                    paint.color = badgeColor
                    canvas.drawCircle(w - 120f, 40f, 28f, paint)
                    canvas.drawText("R: ${"%.2f".format(continuity)}", w - 220f, 48f, textPaint)
                }
            } else {
                // placeholder text
                canvas.drawText("Spectrogram will appear here after analysis", 20f, h / 2f, textPaint)
            }
        }

        private fun buildBitmapFromSpectrogram(res: SpectrogramResult): Bitmap {
            val cols = res.times.size
            val rows = res.freqs.size
            val bmp = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
            for (t in 0 until cols) {
                for (f in 0 until rows) {
                    val p = res.spectrogram[t][f]
                    val db = (10 * ln(p + 1e-12) / ln(10.0)).toFloat()
                    // normalize -80..0 dB to 0..1
                    val norm = ((db + 80f) / 80f).coerceIn(0f, 1f)
                    val hsv = floatArrayOf((1f - norm) * 240f, 1f, norm)
                    val color = Color.HSVToColor(hsv)
                    bmp.setPixel(t, rows - 1 - f, color)
                }
            }
            return bmp
        }
    }
}
