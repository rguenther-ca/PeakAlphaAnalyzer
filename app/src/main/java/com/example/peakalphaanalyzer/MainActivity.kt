// MainActivity.kt
package com.example.peakalphaanalyzer

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
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
    private lateinit var chartFft: LineChart
    private lateinit var resultView: TextView
    private lateinit var noteView: TextView
    private lateinit var progressBar: ProgressBar

    private var lastUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // find views
        etWindow    = findViewById(R.id.etWindow)
        etSubWindow = findViewById(R.id.etSubWindow)
        etOverlap   = findViewById(R.id.etOverlap)
        btnApply    = findViewById(R.id.btnApply)

        chartWelch  = findViewById(R.id.lineChartWelch)
        chartFft    = findViewById(R.id.lineChartFFT)
        resultView  = findViewById(R.id.resultTextView)
        noteView    = findViewById(R.id.noteTextView)
        progressBar = findViewById(R.id.progressBar)

        resultView.setTextIsSelectable(true)

        // default parameter values
        etWindow.setText("6.0")
        etSubWindow.setText("3.0")
        etOverlap.setText("0.25")

        // configure charts
        listOf(chartWelch, chartFft).forEach { chart ->
            chart.description.isEnabled = false
            chart.axisRight.isEnabled   = false
            chart.setTouchEnabled(true)
            chart.setDrawMarkers(true)
            chart.xAxis.position = XAxis.XAxisPosition.BOTTOM
            chart.xAxis.valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float) = String.format("%.2f s", value)
            }
            chart.axisLeft.valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float) = String.format("%.1f dB", value)
            }
            // custom marker view
            chart.marker = MyMarkerView(this)
        }

        // Apply button
        btnApply.setOnClickListener {
            PafAnalyzer.welchWindowSec    = etWindow.text.toString().toDoubleOrNull() ?: 4.0
            PafAnalyzer.welchSubWindowSec = etSubWindow.text.toString().toDoubleOrNull() ?: 2.0
            PafAnalyzer.welchOverlap      = etOverlap.text.toString().toDoubleOrNull() ?: 0.50

            // update note
            noteView.text = buildString {

                append("Welch: averaged PSD over sub-windows (window=${PafAnalyzer.welchWindowSec}s, ")
                append("sub-window=${PafAnalyzer.welchSubWindowSec}s, ")
                append("overlap=${(PafAnalyzer.welchOverlap * 100).toInt()}%).")
                append("FFT: single-window PSD (window=${PafAnalyzer.welchWindowSec}s, ")
                append("overlap=${(PafAnalyzer.welchOverlap * 100).toInt()}%).\n")
            }

            // re-plot if already loaded
            lastUri?.let { handleZipUri(it) }
        }

        // handle incoming intent
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
        // show loading indicator
        progressBar.visibility = View.VISIBLE

        Thread {
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    ZipInputStream(BufferedInputStream(stream)).use { zis ->
                        // find CSV entry
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

                        // analyze data
                        //orig, issue with empty rows
                        //val res = PafAnalyzer.analyze(csv)
                        //copilot suggestion 
                        // Read entire CSV entry into memory
val rawBytes = zis.readBytes()
val rawText = rawBytes.toString(Charsets.UTF_8)

// Sanitize: remove empty lines, whitespace-only lines, and malformed rows
val cleanedText = rawText
    .lineSequence()
    .filter { it.isNotBlank() }                 // remove empty rows
    .filter { it.contains(",") }                // must contain at least one comma
    .joinToString("\n")

// Convert back to InputStream for analyzer
val cleanedStream = cleanedText.byteInputStream(Charsets.UTF_8)

// Now analyze the cleaned CSV
val res = PafAnalyzer.analyze(cleanedStream)


                        // compute series
                        val welchSeriesL = PafAnalyzer.welchPeakSeries(res.rawLeft,  res.fs)
                        val welchSeriesR = PafAnalyzer.welchPeakSeries(res.rawRight, res.fs)
                        val fftSeriesL   = PafAnalyzer.slidingFftPeakSeries(res.rawLeft,  res.fs)
                        val fftSeriesR   = PafAnalyzer.slidingFftPeakSeries(res.rawRight, res.fs)

                        // times of PAF peaks
                        val fftTimeL   = fftSeriesL.maxByOrNull { it.peakDb }?.time ?: Double.NaN
                        val fftTimeR   = fftSeriesR.maxByOrNull { it.peakDb }?.time ?: Double.NaN

                        val welchTimeL = welchSeriesL.maxByOrNull { it.peakDb }?.time ?: Double.NaN
                        val welchTimeR = welchSeriesR.maxByOrNull { it.peakDb }?.time ?: Double.NaN

                        runOnUiThread {
                            // update results
                            resultView.text = res.format()
                            plot(chartWelch, welchSeriesL, welchSeriesR, "Left Welch", Color.BLUE, "Right Welch", Color.RED)
                            plot(chartFft,   fftSeriesL,   fftSeriesR,   "Left FFT",   Color.CYAN,  "Right FFT",   Color.MAGENTA)

                            noteView.text = buildString {
                                append("Welch PAF: L=${"%.1f".format(res.welchL)} Hz @ ${"%.2f".format(welchTimeL)} s, ")
                                append("R=${"%.1f".format(res.welchR)} Hz @ ${"%.2f".format(welchTimeR)} s.\n")
                                append("FFT PAF:   L=${"%.1f".format(res.pafL)} Hz @ ${"%.2f".format(fftTimeL)} s, ")
                                append("R=${"%.1f".format(res.pafR)} Hz @ ${"%.2f".format(fftTimeR)} s.\n")


                                append("Welch: averaged PSD over sub-windows (window=${PafAnalyzer.welchWindowSec}s, ")
                                append("sub-window=${PafAnalyzer.welchSubWindowSec}s, ")
                                append("overlap=${(PafAnalyzer.welchOverlap * 100).toInt()}%).")
                                append("FFT: single-window PSD (window=${PafAnalyzer.welchWindowSec}s, ")
                                append("overlap=${(PafAnalyzer.welchOverlap * 100).toInt()}%).\n")

                            }

                            progressBar.visibility = View.GONE
                        }
                    }
                } ?: runOnUiThread {
                    resultView.text = "Unable to open ZIP."
                    progressBar.visibility = View.GONE
                }
            } catch (e: Exception) {
                runOnUiThread {
                    resultView.text = "Error: ${e.message}"
                    progressBar.visibility = View.GONE
                }
            }
        }.start()
    }
    
    private fun plot(
        chart: LineChart,
        seriesL: List<PafAnalyzer.PeakPoint>,
        seriesR: List<PafAnalyzer.PeakPoint>,
        labelL: String,
        colorL: Int,
        labelR: String,
        colorR: Int
    ) {
        chart.clear()
        val entriesL = seriesL.map { Entry(it.time.toFloat(), it.peakDb.toFloat()) }
        val entriesR = seriesR.map { Entry(it.time.toFloat(), it.peakDb.toFloat()) }
        val setL = LineDataSet(entriesL, labelL).apply {
            this.color = colorL
            setCircleColor(colorL)
        }
        val setR = LineDataSet(entriesR, labelR).apply {
            this.color = colorR
            setCircleColor(colorR)
        }
        chart.data = LineData(setL, setR)
        chart.invalidate()
    }
}
