package com.example.peakalphaanalyzer

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.concurrent.thread
import kotlin.math.*

/**
 * SpectrogramView.kt
 *
 * - Builds a spectrogram bitmap from a raw signal or precomputed spectrogram
 * - Detects an alpha ridge (alphaLowHz..alphaHighHz) per time frame and applies parabolic refinement
 * - Computes a ridge continuity metric (0..1)
 * - Optional callback: notifies a listener with the ridge array and continuity when computation completes
 * - Draws labeled Y axis (Hz) and X axis (time in s or mm:ss)
 * - New API: setOverlayFrequencyHz(f: Double?) to draw a horizontal marker at a frequency (Hz)
 */
class SpectrogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    interface RidgeListener {
        /** Called on the UI thread when ridge computation completes */
        fun onRidgeComputed(ridgeFreqs: DoubleArray, continuity: Double)
    }

    // Rendering state
    @Volatile private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ridgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW; strokeWidth = 3f; style = Paint.Style.STROKE
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; strokeWidth = 2f; style = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 28f
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN; strokeWidth = 2.5f; style = Paint.Style.STROKE
    }
    private val overlayLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN; textSize = 30f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    // Diagnostics and display arrays
    @Volatile private var ridgeFreqs: DoubleArray? = null
    @Volatile private var ridgeContinuity: Double = 0.0
    @Volatile private var freqsForDisplay: DoubleArray? = null
    @Volatile private var timesForDisplay: DoubleArray? = null

    @Volatile private var ridgeListener: RidgeListener? = null

    // Display parameters
    var alphaLowHz = 8.0
    var alphaHighHz = 13.0
    var ridgeToleranceHz = 0.5

    // Axis margins (pixels)
    private val leftMarginPx = 84f
    private val bottomMarginPx = 56f
    private val topMarginPx = 12f
    private val rightMarginPx = 12f

    // Overlay frequency (Hz) — drawn as a horizontal line across the spectrogram inner rect
    @Volatile private var overlayFreqHz: Double? = null

    // Color mapping: map normalized 0..1 to HSV hue (240 -> 0)
    private fun colorForNorm(norm: Float): Int {
        val h = (1f - norm) * 240f
        val hsv = floatArrayOf(h, 1f, norm.coerceIn(0f, 1f))
        return Color.HSVToColor(hsv)
    }

    // -------------------------
    // Public API
    // -------------------------

    /** Register or replace the ridge listener. Pass null to remove. */
    fun setRidgeListener(listener: RidgeListener?) {
        ridgeListener = listener
    }

    /** Build spectrogram from a PafAnalyzer.IafResult (uses rawPosterior and fs). */
    fun setFromPafResult(result: PafAnalyzer.IafResult?) {
        if (result == null) return
        setFromSignal(result.rawPosterior, result.fs, windowSec = 2.0, stepSec = 0.25)
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

    /** Directly set a precomputed spectrogram matrix (time x freq). */
    fun setSpectrogram(freqs: DoubleArray, times: DoubleArray, spectrogram: Array<DoubleArray>) {
        thread {
            computeRidgeAndBitmap(freqs, times, spectrogram)
        }
    }

    /**
     * Set an overlay frequency in Hz. Pass null to clear.
     * The view will redraw on the UI thread.
     */
    fun setOverlayFrequencyHz(f: Double?) {
        overlayFreqHz = f
        postInvalidate()
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
        bitmap = bmp

        // notify listener on UI thread
        post {
            ridgeListener?.onRidgeComputed(ridge.copyOf(), continuity)
            invalidate()
        }
    }

    // -------------------------
    // Drawing (with axes + overlay)
    // -------------------------
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)

        val bmp = bitmap
        if (bmp == null) {
            paint.color = Color.DKGRAY
            paint.textSize = 36f
            canvas.drawText("Spectrogram will appear after analysis", 20f, height / 2f, paint)
            return
        }

        // compute inner drawing rect (leave space for axes)
        val innerLeft = leftMarginPx
        val innerTop = topMarginPx
        val innerRight = width - rightMarginPx
        val innerBottom = height - bottomMarginPx
        val innerWidth = (innerRight - innerLeft).coerceAtLeast(1f)
        val innerHeight = (innerBottom - innerTop).coerceAtLeast(1f)
        val src = Rect(0, 0, bmp.width, bmp.height)
        val dst = RectF(innerLeft, innerTop, innerLeft + innerWidth, innerTop + innerHeight)
        canvas.drawBitmap(bmp, src, dst, null)

        // draw axes lines
        axisPaint.color = Color.LTGRAY
        axisPaint.strokeWidth = 2f
        // Y axis (left)
        canvas.drawLine(innerLeft, innerTop, innerLeft, innerTop + innerHeight, axisPaint)
        // X axis (bottom)
        canvas.drawLine(innerLeft, innerTop + innerHeight, innerLeft + innerWidth, innerTop + innerHeight, axisPaint)

        // draw Y ticks and labels (Hz)
        val freqs = freqsForDisplay
        if (freqs != null && freqs.isNotEmpty()) {
            val fMin = freqs.first()
            val fMax = freqs.last()
            val rangeHz = fMax - fMin
            val approxTick = when {
                rangeHz <= 6 -> 1.0
                rangeHz <= 12 -> 2.0
                rangeHz <= 30 -> 5.0
                else -> 10.0
            }
            val startTick = ceil(fMin / approxTick) * approxTick
            var tick = startTick
            while (tick <= fMax + 1e-9) {
                val frac = ((tick - fMin) / (fMax - fMin)).coerceIn(0.0, 1.0)
                val y = innerTop + (1f - frac.toFloat()) * innerHeight
                canvas.drawLine(innerLeft - 8f, y, innerLeft, y, axisPaint)
                val label = String.format("%.0f Hz", tick)
                val textX = 6f
                val textY = y + labelPaint.textSize / 2f - 6f
                canvas.drawText(label, textX, textY, labelPaint)
                tick += approxTick
            }
        }

        // draw X ticks and labels (time)
        val times = timesForDisplay
        if (times != null && times.isNotEmpty()) {
            val tMin = times.first()
            val tMax = times.last()
            val duration = tMax - tMin
            val tickCandidates = doubleArrayOf(1.0, 2.0, 5.0, 10.0, 15.0, 30.0, 60.0, 120.0, 300.0, 600.0)
            var tickSec = tickCandidates.last()
            for (c in tickCandidates) {
                val nTicks = max(1, (duration / c).roundToInt())
                if (nTicks <= 8) { tickSec = c; break }
            }
            val startTick = ceil(tMin / tickSec) * tickSec
            var tick = startTick
            val labelY = innerTop + innerHeight + labelPaint.textSize + 6f
            while (tick <= tMax + 1e-9) {
                val frac = ((tick - tMin) / (tMax - tMin)).coerceIn(0.0, 1.0)
                val x = innerLeft + frac.toFloat() * innerWidth
                canvas.drawLine(x, innerTop + innerHeight, x, innerTop + innerHeight + 8f, axisPaint)
                val label = if (duration >= 120.0) {
                    val secs = tick.roundToInt()
                    val mm = secs / 60
                    val ss = secs % 60
                    String.format("%d:%02d", mm, ss)
                } else {
                    if (tickSec < 5.0) String.format("%.1f s", tick) else String.format("%.0f s", tick)
                }
                val textWidth = labelPaint.measureText(label)
                canvas.drawText(label, x - textWidth / 2f, labelY, labelPaint)
                tick += tickSec
            }
        }

        // overlay horizontal frequency marker (if set)
        val overlayF = overlayFreqHz
        if (overlayF != null && freqs != null && freqs.isNotEmpty()) {
            val fMin = freqs.first()
            val fMax = freqs.last()
            if (overlayF.isFinite() && overlayF >= fMin && overlayF <= fMax) {
                val fracF = ((overlayF - fMin) / (fMax - fMin)).coerceIn(0.0, 1.0)
                val y = innerTop + (1f - fracF.toFloat()) * innerHeight
                // horizontal line across inner rect
                canvas.drawLine(innerLeft, y, innerLeft + innerWidth, y, overlayPaint)
                // small label at right side
                val label = String.format("%.2f Hz", overlayF)
                val textW = overlayLabelPaint.measureText(label)
                val lx = innerLeft + innerWidth - textW - 8f
                val ly = y - 8f
                // draw a filled rounded rect behind label for readability
                val pad = 6f
                val rect = RectF(lx - pad, ly - overlayLabelPaint.textSize, lx + textW + pad, ly + 6f)
                val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(160, 0, 0, 0) }
                canvas.drawRoundRect(rect, 6f, 6f, bg)
                canvas.drawText(label, lx, ly, overlayLabelPaint)
            }
        }

        // overlay ridge (map ridge freq -> y in inner rect, ridge index -> x)
        val ridge = ridgeFreqs
        if (ridge != null && freqs != null && freqs.isNotEmpty()) {
            val cols = ridge.size
            val fMin = freqs.first()
            val fMax = freqs.last()
            val path = Path()
            var firstPoint = true
            for (t in 0 until cols) {
                val rf = ridge[t]
                if (!rf.isFinite()) continue
                val fracF = ((rf - fMin) / (fMax - fMin)).coerceIn(0.0, 1.0)
                val x = innerLeft + (t.toFloat() / cols.toFloat()) * innerWidth
                val y = innerTop + (1f - fracF.toFloat()) * innerHeight
                if (firstPoint) { path.moveTo(x, y); firstPoint = false } else path.lineTo(x, y)
            }
            canvas.drawPath(path, ridgePaint)

            // continuity badge (draw in top-right corner)
            val cont = ridgeContinuity
            val badgeColor = when {
                cont >= 0.7 -> Color.GREEN
                cont >= 0.4 -> Color.YELLOW
                else -> Color.RED
            }
            badgePaint.color = badgeColor
            val cx = innerLeft + innerWidth - 48f
            val cy = innerTop + 28f
            canvas.drawCircle(cx, cy, 20f, badgePaint)
            val contLabel = String.format("%.2f", cont)
            val textW = labelPaint.measureText(contLabel)
            canvas.drawText(contLabel, cx - textW / 2f, cy + labelPaint.textSize / 3f, labelPaint)
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
