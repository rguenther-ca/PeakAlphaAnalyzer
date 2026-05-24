package com.example.peakalphaanalyzer

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.concurrent.thread

/**
 * MainActivity.kt (simplified)
 *
 * - Minimal UI: pick CSV file, run analysis, show textual results.
 * - No spectrogram visual; avoids heavy UI drawing that can break some CI pipelines.
 * - Uses PafAnalyzer (same package) to analyze and export diagnostics CSV.
 *
 * Place this file at:
 * app/src/main/java/com/example/peakalphaanalyzer/MainActivity.kt
 */

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private lateinit var loadButton: Button
    private lateinit var statusText: TextView
    private lateinit var resultsText: TextView
    private val analyzer = PafAnalyzer(256.0)

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) handlePickedFile(uri) else runOnUiThread { statusText.text = "No file selected" }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Simple vertical layout
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        loadButton = Button(this).apply {
            text = "Pick EEG CSV file"
            setOnClickListener { pickFile() }
        }
        statusText = TextView(this).apply { text = "No file loaded" }
        resultsText = TextView(this).apply { text = "" }

        layout.addView(loadButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        layout.addView(statusText, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        layout.addView(resultsText, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        setContentView(layout)

        // request storage permission if needed
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1001)
        }
    }

    private fun pickFile() {
        // Accept text/csv files
        pickFileLauncher.launch("text/*")
    }

    private fun handlePickedFile(uri: Uri) {
        statusText.text = "Loading file..."
        thread {
            try {
                val file = copyUriToFile(uri)
                runOnUiThread { statusText.text = "Analyzing ${file.name} ..." }
                val result = analyzer.analyzeFile(file)
                runOnUiThread { displayResults(result, file) }
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
        sb.append("Center of Gravity: ${"%.2f".format(c.cogHz)} Hz\n")
        sb.append("Rapid IAF (median windows): ${"%.2f".format(c.rapidIafHz)} Hz\n")
        sb.append("SNR: ${"%.2f".format(c.snr)}\n")
        sb.append("Prominence: ${"%.4f".format(c.peakProminence)}\n")
        sb.append("Peak width (Hz): ${"%.2f".format(c.peakWidthHz)}\n")
        sb.append("Percent windows clean: ${"%.1f".format(c.percentWindowsClean * 100)}%\n")
        sb.append("PAF mean ± SD: ${"%.2f".format(c.pafMean)} ± ${"%.2f".format(c.pafStd)} Hz\n")
        resultsText.text = sb.toString()
        statusText.text = "Analysis complete"

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
}
