package com.example.peakalphaanalyzer

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread
import kotlin.math.ln

class MainActivity : AppCompatActivity() {

    private lateinit var etWindow: EditText
    private lateinit var etSubWindow: EditText
    private lateinit var etOverlap: EditText
    private lateinit var btnApply: Button

    private lateinit var chartWelch: LineChart
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

        chartWelch = findViewById(R.id.lineChartWelch)
        resultView = findViewById(R.id.resultTextView)
        noteView = findViewById(R.id.noteTextView)
        progressBar = findViewById(R.id.progressBar)

        resultView.setTextIsSelectable(true)

        etWindow.setText(PafAnalyzer.welchWindowSec.toString())
        etSubWindow.setText(PafAnalyzer.welchSubWindowSec.toString())
        etOverlap.setText(PafAnalyzer.welchOverlap.toString())

        chartWelch.description.isEnabled = false
        chartWelch.axisRight.isEnabled = false
        chartWelch.xAxis.position = XAxis.XAxisPosition.BOTTOM

        btnApply.setOnClickListener {
            PafAnalyzer.welchWindowSec = etWindow.text.toString().toDoubleOrNull() ?: PafAnalyzer.welchWindowSec
            PafAnalyzer.welchSubWindowSec = etSubWindow.text.toString().toDoubleOrNull() ?: PafAnalyzer.welchSubWindowSec
            PafAnalyzer.welchOverlap = etOverlap.text.toString().toDoubleOrNull() ?: PafAnalyzer.welchOverlap

            lastUri?.let { handleZipUri(it) }
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
        resultView.text = ""
        noteView.text = ""

        thread {
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    ZipInputStream(BufferedInputStream(stream)).use { zis ->
                        var entry = zis.nextEntry
                        var csvStream: InputStream? = null
                        while (entry != null) {
                            if (entry.name.endsWith(".csv", ignoreCase = true) || entry.name.endsWith(".txt", ignoreCase = true)) {
                                csvStream = zis; break
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
                        val cleanedText = rawBytes.toString(Charsets.UTF_8)
                            .lineSequence()
                            .filter { it.isNotBlank() }
                            .filter { it.contains(",") || it.contains("\t") }
                            .joinToString("\n")

                        val cleanedStream = cleanedText.byteInputStream(Charsets.UTF_8)

                        val res = PafAnalyzer.analyze(cleanedStream)

                        runOnUiThread {
                            resultView.text = res.format()

                            noteView.text = buildString {
                                append("IAF: ${"%.2f".format(res.iafHz)} Hz (${res.iafMethod})\n")
                                append("Confidence: ${"%.2f".format(res.confidence)} (0–1)\n")
                                append("Peak power: ${"%.4f".format(res.peakPower)}, alpha mean: ${"%.4f".format(res.alphaMeanPower)}\n")
                                append("Peak width: ${"%.2f".format(res.peakWidthHz)} Hz, PAF SD: ${"%.3f".format(res.pafStdAcrossWindows)}\n")
                                append("Welch: sub-window=${PafAnalyzer.welchSubWindowSec}s, overlap=${(PafAnalyzer.welchOverlap * 100).toInt()}%.\n")
                                if (res.confidence < 0.6) {
                                    append("\n**Warning:** IAF confidence is low. Re-record with relaxed jaw, supported head, eyes closed.\n")
                                }
                            }

                            plotPsd(res.freqs, res.psd)

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

    private fun plotPsd(freqs: DoubleArray, psd: DoubleArray) {
        val entries = mutableListOf<Entry>()
        for (i in freqs.indices) {
            val f = freqs[i].toFloat()
            val p = 10f * ln((psd[i].toFloat() + 1e-12f).toDouble()).toFloat()
            entries.add(Entry(f, p))
        }
        val set = LineDataSet(entries, "PSD (dB)")
        set.color = Color.BLUE
        set.setDrawCircles(false)
        set.lineWidth = 1.2f
        val data = LineData(set)
        chartWelch.data = data
        chartWelch.invalidate()
    }
}
