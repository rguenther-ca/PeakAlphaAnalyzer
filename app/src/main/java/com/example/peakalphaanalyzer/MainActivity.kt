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
import kotlin.concurrent.thread

/**
 * MainActivity.kt
 *
 * - Pre-populates the three UI inputs with the values from PafAnalyzer:
 *     welchWindowSec, welchSubWindowSec, welchOverlap
 * - Keeps the fields functional: pressing Apply updates PafAnalyzer and re-runs analysis
 * - Integrates with SpectrogramView overlay API (setOverlayFrequencyHz)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var etWindow: EditText
    private lateinit var etSubWindow: EditText
    private lateinit var etOverlap: EditText
    private lateinit var btnApply: Button

    private lateinit var spectrogramView: SpectrogramView
    private lateinit var resultView: TextView
    private lateinit var noteView: TextView
    private lateinit var progressBar: ProgressBar

    private var lastUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etWindow = findViewById(R.id.etWindow)
        etSubWindow = findViewById(R.id.etSubWindow)
        etOverlap = findViewById(R.id.etOverlap)
        btnApply = findViewById(R.id.btnApply)

        spectrogramView = findViewById(R.id.spectrogramView)
        resultView = findViewById(R.id.resultTextView)
        noteView = findViewById(R.id.noteTextView)
        progressBar = findViewById(R.id.progressBar)

        resultView.setTextIsSelectable(true)

        // Pre-populate the UI fields with the current PafAnalyzer defaults
        etWindow.setText(PafAnalyzer.welchWindowSec.toString())
        etSubWindow.setText(PafAnalyzer.welchSubWindowSec.toString())
        etOverlap.setText(PafAnalyzer.welchOverlap.toString())

        // Receive ridge callback for decision logic or UI updates
        spectrogramView.setRidgeListener(object : SpectrogramView.RidgeListener {
            override fun onRidgeComputed(ridgeFreqs: DoubleArray, continuity: Double) {
                // runs on UI thread (SpectrogramView posts to UI)
                noteView.text = "Ridge continuity: ${"%.2f".format(continuity)}"
            }
        })

        btnApply.setOnClickListener {
            // Update PafAnalyzer parameters from UI (keep defaults if parsing fails)
            PafAnalyzer.welchWindowSec = etWindow.text.toString().toDoubleOrNull() ?: PafAnalyzer.welchWindowSec
            PafAnalyzer.welchSubWindowSec = etSubWindow.text.toString().toDoubleOrNull() ?: PafAnalyzer.welchSubWindowSec
            PafAnalyzer.welchOverlap = etOverlap.text.toString().toDoubleOrNull() ?: PafAnalyzer.welchOverlap

            // If a file was previously loaded, re-run analysis with new parameters
            lastUri?.let { handleZipUri(it) } ?: run {
                resultView.text = "No file selected. Share or view a ZIP containing CSV."
            }
        }

        try {
            when (intent?.action) {
                Intent.ACTION_VIEW -> intent.data?.also {
                    lastUri = it
                    handleZipUri(it)
                }
                Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.also {
                    lastUri = it
                    handleZipUri(it)
                }
                Intent.ACTION_SEND_MULTIPLE -> intent
                    .getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                    ?.firstOrNull()
                    ?.also {
                        lastUri = it
                        handleZipUri(it)
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
        // Clear any previous overlay while processing
        spectrogramView.setOverlayFrequencyHz(null)

        thread {
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
                            return@thread
                        }

                        val rawBytes = csvStream.readBytes()
                        val rawText = rawBytes.toString(Charsets.UTF_8)

                        val cleanedText = rawText
                            .lineSequence()
                            .filter { it.isNotBlank() }
                            .filter { it.contains(",") }
                            .joinToString("\n")

                        val cleanedStream = cleanedText.byteInputStream(Charsets.UTF_8)

                        val res = PafAnalyzer.analyze(cleanedStream)

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

                            // Render spectrogram and compute ridge (view will call back)
                            spectrogramView.setFromPafResult(res)

                            // Draw overlay horizontal marker at chosen IAF
                            spectrogramView.setOverlayFrequencyHz(res.chosenIafHz)

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
        }
    }
}
