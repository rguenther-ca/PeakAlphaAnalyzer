package com.example.peakalphaanalyzer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * MainActivity.kt
 *
 * Simple UI that:
 * - Accepts a shared ZIP (or VIEW intent) containing a CSV
 * - Extracts the first CSV, cleans it, and passes it to PafAnalyzer.analyze()
 * - Displays argmax, parabolic-refined, CoG, rapid-IAF, chosen IAF, and confidence
 *
 * Ensure activity_main.xml contains:
 * - EditTexts: etWindow, etSubWindow, etOverlap
 * - Button: btnApply
 * - TextViews: resultTextView, noteTextView
 * - ProgressBar: progressBar
 */
class MainActivity : AppCompatActivity() {

    private lateinit var etWindow: EditText
    private lateinit var etSubWindow: EditText
    private lateinit var etOverlap: EditText
    private lateinit var btnApply: Button

    private lateinit var resultView: TextView
    private lateinit var noteView: TextView
    private lateinit var progressBar: ProgressBar

    private var lastUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val spectroView = findViewById<SpectrogramView>(R.id.spectrogramView)

// optional: receive ridge results for decision logic
spectroView.setRidgeListener(object : SpectrogramView.RidgeListener {
    override fun onRidgeComputed(ridgeFreqs: DoubleArray, continuity: Double) {
        // runs on UI thread
        // Example: update UI or feed back into PafAnalyzer decision logic
        runOnUiThread {
            noteView.text = "Ridge continuity: ${"%.2f".format(continuity)}"
            // store or use ridgeFreqs as needed
        }
    }
})

        etWindow = findViewById(R.id.etWindow)
        etSubWindow = findViewById(R.id.etSubWindow)
        etOverlap = findViewById(R.id.etOverlap)
        btnApply = findViewById(R.id.btnApply)

        resultView = findViewById(R.id.resultTextView)
        noteView = findViewById(R.id.noteTextView)
        progressBar = findViewById(R.id.progressBar)

        resultView.setTextIsSelectable(true)

        etWindow.setText("6.0")
        etSubWindow.setText("3.0")
        etOverlap.setText("0.25")

        btnApply.setOnClickListener {
            // update analyzer parameters
            PafAnalyzer.welchWindowSec = etWindow.text.toString().toDoubleOrNull() ?: 6.0
            PafAnalyzer.welchSubWindowSec = etSubWindow.text.toString().toDoubleOrNull() ?: 3.0
            PafAnalyzer.welchOverlap = etOverlap.text.toString().toDoubleOrNull() ?: 0.25

            lastUri?.let { uri -> handleZipUri(uri) } ?: run {
                resultView.text = "No file selected. Share or view a ZIP containing CSV."
            }
        }

        try {
            when (intent?.action) {
                Intent.ACTION_VIEW -> intent.data?.also { uri ->
                    lastUri = uri
                    handleZipUri(uri)
                }
                Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.also { uri ->
                    lastUri = uri
                    handleZipUri(uri)
                }
                Intent.ACTION_SEND_MULTIPLE -> intent
                    .getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                    ?.firstOrNull()
                    ?.also { uri ->
                        lastUri = uri
                        handleZipUri(uri)
                    }
                else -> resultView.text = "Share or view a ZIP containing CSV."
            }
        } catch (e: Exception) {
            resultView.text = "Error: ${e.message}"
        }
    }

    private fun handleZipUri(uri: Uri) {
        progressBar.visibility = View.VISIBLE
        resultView.text = "Processing..."

        Thread {
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    ZipInputStream(BufferedInputStream(stream)).use { zis ->
                        var entry = zis.nextEntry
                        var csvStream: InputStream? = null
                        while (entry != null) {
                            if (entry.name.endsWith(".csv", ignoreCase = true)) {
                                csvStream = zis
                                break
                            }
                            entry = zis.nextEntry
                        }
                        if (csvStream == null) {
                            runOnUiThread {
                                resultView.text = "No CSV found in ZIP."
                                progressBar.visibility = View.GONE
                            }
                            return@Thread
                        }

                        // Read CSV bytes from the current ZipInputStream entry
                        val rawBytes = csvStream.readBytes()
                        val rawText = rawBytes.toString(Charsets.UTF_8)

                        // Basic cleaning: remove blank lines and lines without commas
                        val cleanedText = rawText
                            .lineSequence()
                            .filter { it.isNotBlank() }
                            .filter { it.contains(",") }
                            .joinToString("\n")

                        val cleanedStream = cleanedText.byteInputStream(Charsets.UTF_8)

                        // Run analysis
                        val res = PafAnalyzer.analyze(cleanedStream)

                        val spectroView = findViewById<SpectrogramView>(R.id.spectrogramView)
                        spectroView.setFromPafResult(res)

                        runOnUiThread {
                            resultView.text = res.format()
                            noteView.text = buildString {
                                append("Argmax IAF: ${"%.2f".format(res.iafArgmaxHz)} Hz\n")
                                append("Parabolic refined IAF: ${"%.2f".format(res.iafParabolicHz)} Hz\n")
                                append("CoG IAF: ${"%.2f".format(res.iafCogHz)} Hz\n")
                                append("Rapid IAF (median windows): ${"%.2f".format(res.rapidIafHz)} Hz\n")
                                append("Chosen IAF: ${"%.2f".format(res.chosenIafHz)} Hz (${res.iafMethod})\n")
                                append("Confidence: ${"%.2f".format(res.confidence)} (0–1)\n")
                                append("Welch params: window=${PafAnalyzer.welchWindowSec}s, sub=${PafAnalyzer.welchSubWindowSec}s, overlap=${(PafAnalyzer.welchOverlap * 100).toInt()}%\n")
                            }
                            progressBar.visibility = View.GONE
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    resultView.text = "Error: ${e.message}"
                    progressBar.visibility = View.GONE
                }
            }
        }.start()
    }
}
