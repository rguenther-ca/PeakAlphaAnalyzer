// MainActivity.kt
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
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.ValueFormatter
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

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

        etWindow.setText("6.0")
        etSubWindow.setText("3.0")
        etOverlap.setText("0.25")

        chartWelch.description.isEnabled = false
        chartWelch.axisRight.isEnabled = false
        chartWelch.xAxis.position = XAxis.XAxisPosition.BOTTOM
        chartWelch.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float) = String.format("%.2f s", value)
        }
        chartWelch.axisLeft.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float) = String.format("%.1f dB", value)
        }
        chartWelch.marker = MyMarkerView(this)

        btnApply.setOnClickListener {
            PafAnalyzer.welchWindowSec = etWindow.text.toString().toDoubleOrNull() ?: 6.0
            PafAnalyzer.welchSubWindowSec = etSubWindow.text.toString().toDoubleOrNull() ?: 3.0
            PafAnalyzer.welchOverlap = etOverlap.text.toString().toDoubleOrNull() ?: 0.25

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

        Thread {
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    ZipInputStream(BufferedInputStream(stream)).use { zis ->
                        var entry = zis.nextEntry
                        var csv: InputStream? = null
                        while (entry != null) {
                            if (entry.name.endsWith(".csv")) {
                                csv = zis; break
                            }
                            entry = zis.nextEntry
                        }
                        if (csv == null) {
                            runOnUiThread {
                                resultView.text = "No CSV found in ZIP."
                                progressBar.visibility = View.GONE
                            }
                            return@Thread
                        }

                        val rawBytes = zis.readBytes()
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
    append("IAF (PAF): ${"%.2f".format(res.iafHz)} Hz\n")
    append("Confidence: ${"%.2f".format(res.confidence)} (0–1)\n")
    append("Welch parameters: window=${PafAnalyzer.welchWindowSec}s, ")
    append("sub-window=${PafAnalyzer.welchSubWindowSec}s, ")
    append("overlap=${(PafAnalyzer.welchOverlap * 100).toInt()}%.\n")
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
