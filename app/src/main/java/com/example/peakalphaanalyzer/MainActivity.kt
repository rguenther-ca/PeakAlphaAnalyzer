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
 * Minimal UI integration:
 * - Accepts a shared ZIP (or VIEW intent) containing a CSV
 * - Extracts the first CSV, cleans it, and passes it to PafAnalyzer.analyze()
 * - Displays argmax, parabolic-refined, CoG, rapid-IAF, chosen IAF, and confidence
 * - Renders spectrogram and receives ridge callback for decision logic
 * - Uses SpectrogramView.setOverlayFrequencyHz(...) to draw chosen IAF on the spectrogram
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

        etWindow.setText("6.0")
        etSubWindow.setText("3.0")
        etOverlap.setText("0.25")

        // Receive ridge callback for decision logic or UI updates
        spectrogramView.setRidgeListener(object : SpectrogramView.RidgeListener {
            override fun onRidgeComputed(ridgeFreqs: DoubleArray, continuity: Double) {
                // runs on UI thread (SpectrogramView posts to UI)
                noteView.text = "Ridge continuity: ${"%.2f".format(continuity)}"
                // Optionally: use ridgeFreqs to refine chosen IAF or display timeline
            }
        })

        btnApply.setOnClickListener {
            PafAnalyzer.welchWindowSec = etWindow.text.toString().toDoubleOrNull() ?: 6.0
            PafAnalyzer.welchSubWindowSec = etSubWindow.text.toString().toDoubleOrNull() ?: 3.0
            PafAnalyzer.welchOverlap = etOverlap.text.toString().toDoubleOrNull() ?: 0.25

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
