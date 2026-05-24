package com.example.peakalphaanalyzer

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.concurrent.thread
import kotlin.math.*

/**
 * SpectrogramView
 *
 * Ready-to-drop custom View that:
 * - Builds a spectrogram bitmap from a raw signal or from an explicit spectrogram matrix
 * - Detects an alpha ridge (8–13 Hz) per time frame and applies parabolic refinement
 * - Computes a simple ridge continuity metric (0..1)
 * - Draws the spectrogram, overlays the ridge, and shows a continuity badge
 *
 * Usage:
 *  - Call setFromPafResult(result) to render from the PafAnalyzer.IafResult (uses rawPosterior and fs)
 *  - Or call setFromSignal(signal, fs, windowSec, stepSec) to render from raw data
 *  - Or call setSpectrogram(freqs, times, spectrogramMatrix) to render a precomputed spectrogram
 *
 * Notes:
 *  - Heavy computation runs off the UI thread; the view invalidates when ready.
 *  - The FFT implementation requires power-of-two nfft; windows are zero-padded to nextPow2.
 *  - Default parameters chosen for a good balance of time/frequency resolution for fs=256.
 */

class SpectrogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    // Rendering state
    @Volatile private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ridgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Computed diagnostics
    @Volatile private var ridgeFreqs: DoubleArray? = null
    @Volatile private var ridgeContinuity: Double = 0.0
    @Volatile private var freqsForDisplay: DoubleArray? = null
    @Volatile private var timesForDisplay: DoubleArray? = null

    // Display parameters
    var alphaLowHz = 8.0
    var alphaHighHz = 13.0
    var ridgeToleranceHz = 0.5

    // Color mapping: map normalized 0..1 to HSV hue (240 -> 0)
    private fun colorForNorm(norm: Float): Int {
        val h = (1f - norm) * 240f
        val hsv = floatArrayOf(h, 1f, norm.coerceIn(0f, 1f))
        return Color.HSVToColor(hsv)
    }

    // -------------------------
    // Public setters
    // -------------------------

    /**
     * Build spectrogram from a PafAnalyzer.IafResult (uses rawPosterior and fs).
     * This integrates directly with the analyzer output.
     */
    fun setFromPafResult(result: Any?) {
        // Accept either the IafResult type or null; avoid compile-time coupling if names differ.
        if (result == null) return
        try {
            // Reflection-lite: expect fields rawPosterior: DoubleArray and fs: Double
            val rawPosterior = result::class.java.getDeclaredField("rawPosterior").let { f ->
                f.isAccessible = true; f.get(result) as? DoubleArray
            } ?: return
            val fs = result::class.java.getDeclaredField("fs").let { f ->
                f.isAccessible = true; (f.get(result) as Number).toDouble()
            }
            // default windowing tuned for 2s frames
            setFromSignal(rawPosterior, fs, windowSec = 2.0, stepSec = 0.25)
        } catch (ex: Exception) {
            // If reflection fails, silently ignore (caller can use setFromSignal)
        }
    }

    /**
     * Build spectrogram from raw signal.
     * windowSec: frame length in seconds (e.g., 2.0)
     * stepSec: hop length in seconds (e.g., 0.25)
     */
    fun setFromSignal(signal: DoubleArray, fs: Double, windowSec: Double = 2.0, stepSec: Double = 0.25) {
        thread {
            val winN = max(4, (windowSec * fs).roundToInt())
            val step = max(1, (stepSec * fs).roundToInt())
            val nfft = nextPow2(winN)
            val half = nfft / 2
            val win = hamming(winN)

            val frames = ArrayList<DoubleArray>()
            var off = 0
            while (off + winN <= signal.size) {
                val frame = DoubleArray(nfft) { 0.0 }
                for (i in 0 until winN) frame[i] = signal[off + i] * win[i]
                frames.add(frame)
                off += step
            }
            if (frames.isEmpty()) return@thread

            val times = DoubleArray(frames.size) { idx -> (idx * step).toDouble() / fs }
            val freqs = DoubleArray(half) { i -> i * fs / nfft }
            val spec = Array(frames.size) { DoubleArray(half) }

            for (t in frames.indices) {
                val (real, imag) = fftReal(frames[t])
                for (f in 0 until half) {
                    val p = real[f] * real[f] + imag[f] * imag[f]
                    spec[t][f] = p
                }
            }

            computeRidgeAndBitmap(freqs, times, spec)
        }
    }

    /**
     * Directly set a precomputed spectrogram matrix.
     * spectrogram[t][f] where t = time index (columns), f = frequency index (rows)
     */
    fun setSpectrogram(freqs: DoubleArray, times: DoubleArray, spectrogram: Array<DoubleArray>) {
        thread {
            computeRidgeAndBitmap(freqs, times, spectrogram)
        }
    }

    // -------------------------
    // Core processing
    // -------------------------
    private fun computeRidgeAndBitmap(freqs: DoubleArray, times: DoubleArray, spectrogram: Array<DoubleArray>) {
        // spectrogram: time x freq (power)
        val cols = spectrogram.size
        val rows = spectrogram[0].size
        // compute dB and normalize
        val dbSpec = Array(cols) { DoubleArray(rows) }
        var minDb = Double.POSITIVE_INFINITY
        var maxDb = Double.NEGATIVE_INFINITY
        for (t in 0 until cols) {
            for (f in 0 until rows) {
                val p = spectrogram[t][f]
                val db = 10.0 * ln(p + 1e-12) / ln(10.0)
                dbSpec[t][f] = db
                if (db < minDb) minDb = db
                if (db > maxDb) maxDb = db
            }
        }
        if (!minDb.isFinite() || !maxDb.isFinite()) {
            minDb = -80.0; maxDb = 0.0
        }
        // clamp range to [-80, maxDb]
        val floorDb = max(minDb, -80.0)
        val ceilDb = maxDb

        // compute ridge: for each time column, find max in alpha band and parabolic refine
        val ridge = DoubleArray(cols) { Double.NaN }
        val ridgePower = DoubleArray(cols) { Double.NaN }
        val alphaIdxs = freqs.indices.filter { freqs[it] >= alphaLowHz && freqs[it] <= alphaHighHz }
        for (t in 0 until cols) {
            var bestIdx = -1
            var bestVal = Double.NEGATIVE_INFINITY
            for (i in alphaIdxs) {
                val v = dbSpec[t][i]
                if (v > bestVal) { bestVal = v; bestIdx = i }
            }
            if (bestIdx >= 0) {
                val refined = parabolicRefine(freqs, dbSpec[t], bestIdx)
                ridge[t] = refined
                ridgePower[t] = bestVal
            }
        }

        // continuity: fraction of frames where ridge is within tolerance of median and above local baseline
        val validRidge = ridge.filter { it.isFinite() }
        val continuity = if (validRidge.isEmpty()) 0.0 else {
            val median = validRidge.sorted()[validRidge.size / 2]
            val within = ridge.mapIndexed { idx, v ->
                if (!v.isFinite()) 0 else {
                    val close = abs(v - median) <= ridgeToleranceHz
                    val powerOk = ridgePower[idx].isFinite() && ridgePower[idx] > (floorDb + (ceilDb - floorDb) * 0.2)
                    if (close && powerOk) 1 else 0
                }
            }.sum()
            within.toDouble() / cols.toDouble()
        }

        // build bitmap (cols x rows)
        val bmp = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
        for (t in 0 until cols) {
            for (f in 0 until rows) {
                val db = dbSpec[t][f].coerceIn(floorDb, ceilDb)
                val norm = ((db - floorDb) / (ceilDb - floorDb)).toFloat()
                val color = colorForNorm(norm)
                // invert vertical axis so low freq at bottom
                bmp.setPixel(t, rows - 1 - f, color)
            }
        }

        // store for drawing (swap atomically)
        freqsForDisplay = freqs
        timesForDisplay = times
        ridgeFreqs = ridge
        ridgeContinuity = continuity
        // scale bitmap to view size on draw; keep original for quality
        bitmap = bmp
        postInvalidate()
    }

    // -------------------------
    // Drawing
    // -------------------------
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val bmp = bitmap
        if (bmp == null) {
            // placeholder
            paint.color = Color.DKGRAY
            paint.textSize = 36f
            canvas.drawText("Spectrogram will appear after analysis", 20f, height / 2f, paint)
            return
        }
        // draw scaled bitmap
        val src = Rect(0, 0, bmp.width, bmp.height)
        val dst = Rect(0, 0, width, height)
        canvas.drawBitmap(bmp, src, dst, null)

        // overlay ridge
        val ridge = ridgeFreqs
        val freqs = freqsForDisplay
        val times = timesForDisplay
        if (ridge != null && freqs != null && times != null) {
            val cols = ridge.size
            val rows = freqs.size
            val path = Path()
            var first = true
            for (t in 0 until cols) {
                val rf = ridge[t]
                if (!rf.isFinite()) continue
                // map rf to y pixel
                val fIdx = freqs.indexOfFirst { it >= rf }.coerceAtLeast(0)
                val x = t.toFloat() / cols * width
                val y = (1f - fIdx.toFloat() / rows) * height
                if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
            }
            canvas.drawPath(path, ridgePaint)

            // continuity badge
            val cont = ridgeContinuity
            val badgeColor = when {
                cont >= 0.7 -> Color.GREEN
                cont >= 0.4 -> Color.YELLOW
                else -> Color.RED
            }
            badgePaint.color = badgeColor
            val cx = width - 120f
            val cy = 40f
            canvas.drawCircle(cx, cy, 28f, badgePaint)
            canvas.drawText("R: ${"%.2f".format(cont)}", cx - 80f, cy + 12f, textPaint)
        }
    }

    // -------------------------
    // DSP helpers (FFT, window, parabolic)
    // -------------------------
    private fun nextPow2(n: Int): Int {
        var v = 1
        while (v < n) v = v shl 1
        return v
    }

    private fun hamming(n: Int): DoubleArray {
        val w = DoubleArray(n)
        if (n <= 1) { if (n == 1) w[0] = 1.0; return w }
        for (i in 0 until n) w[i] = 0.54 - 0.46 * cos(2.0 * Math.PI * i / (n - 1))
        return w
    }

    // Cooley-Tukey in-place FFT for real input (returns real, imag arrays)
    private fun fftReal(input: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val n = input.size
        val levels = (ln(n.toDouble()) / ln(2.0)).roundToInt()
        if ((1 shl levels) != n) {
            // zero-pad to next pow2 and call again
            val n2 = nextPow2(n)
            val padded = DoubleArray(n2) { i -> if (i < n) input[i] else 0.0 }
            return fftReal(padded)
        }
        val real = input.copyOf()
        val imag = DoubleArray(n) { 0.0 }

        // bit-reverse
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tmp = real[i]; real[i] = real[j]; real[j] = tmp
            }
        }

        var size = 2
        while (size <= n) {
            val half = size / 2
            var i = 0
            while (i < n) {
                var j2 = 0
                while (j2 < half) {
                    val l = i + j2
                    val r = l + half
                    val angle = -2.0 * Math.PI * j2 / size
                    val wr = cos(angle)
                    val wi = sin(angle)
                    val tr = wr * real[r] - wi * imag[r]
                    val ti = wr * imag[r] + wi * real[r]
                    real[r] = real[l] - tr
                    imag[r] = imag[l] - ti
                    real[l] += tr
                    imag[l] += ti
                    j2++
                }
                i += size
            }
            size = size shl 1
        }
        return Pair(real, imag)
    }

    // Parabolic interpolation on a single time column (psd in dB)
    private fun parabolicRefine(freqs: DoubleArray, dbColumn: DoubleArray, peakIdx: Int): Double {
        val i = peakIdx
        if (i <= 0 || i >= dbColumn.size - 1) return freqs[i]
        val y0 = dbColumn[i - 1]; val y1 = dbColumn[i]; val y2 = dbColumn[i + 1]
        val denom = (y0 - 2.0 * y1 + y2)
        if (abs(denom) < 1e-12) return freqs[i]
        val delta = 0.5 * (y0 - y2) / denom
        val df = freqs[1] - freqs[0]
        return freqs[i] + delta * df
    }
}
