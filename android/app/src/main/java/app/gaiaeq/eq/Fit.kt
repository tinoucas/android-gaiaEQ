package app.gaiaeq.eq

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Fit gains of fixed peaking bands (freq/Q set by the device) to a target curve.
 * Weighted least squares with a free overall offset (= volume, not sent to the device),
 * Gauss-Newton with numerical Jacobian, gains clipped to [minDb, maxDb].
 */
object Fit {

    data class Result(
        val gainsDb: List<Double>,
        val offsetDb: Double,
        val rmsErrorDb: Double,
        val target: DoubleArray,
        val fitted: DoubleArray,   // device response + offset, on Filters.GRID
    )

    fun deviceResponse(freqs: List<Double>, qs: List<Double>, gains: DoubleArray): DoubleArray =
        Filters.sumDb(freqs.indices.map { Filter(FilterKind.PK, freqs[it], gains[it], qs[it]) })

    fun fit(target: DoubleArray, freqs: List<Double>, qs: List<Double>,
            minDb: Double = -12.0, maxDb: Double = 6.0): Result {
        val w = Filters.WEIGHTS
        val n = freqs.size
        val m = target.size
        val g = DoubleArray(n)
        val h = 0.01
        for (iter in 0 until 60) {
            val base = deviceResponse(freqs, qs, g)
            // Jacobian columns: n gains + 1 offset
            val jac = Array(n + 1) { DoubleArray(m) }
            for (k in 0 until n) {
                val d = g.copyOf(); d[k] += h
                val r = deviceResponse(freqs, qs, d)
                for (i in 0 until m) jac[k][i] = (r[i] - base[i]) / h
            }
            jac[n].fill(1.0)
            val res = DoubleArray(m) { target[it] - base[it] }
            // Normal equations of the linearized problem: (J^T W J) x = J^T W r
            val p = n + 1
            val ata = Array(p) { DoubleArray(p) }
            val atb = DoubleArray(p)
            for (a in 0 until p) {
                for (b in 0 until p) {
                    var s = 0.0
                    for (i in 0 until m) s += jac[a][i] * w[i] * jac[b][i]
                    ata[a][b] = s
                }
                ata[a][a] += 1e-9
                var s = 0.0
                for (i in 0 until m) s += jac[a][i] * w[i] * res[i]
                atb[a] = s
            }
            // Box-constrained solve by coordinate descent: exact for this convex
            // quadratic, unlike clamping an unconstrained step (which can stall at
            // the bound, e.g. "cuts only" returning all zeros).
            val lo = DoubleArray(p) { if (it < n) minDb - g[it] else Double.NEGATIVE_INFINITY }
            val hi = DoubleArray(p) { if (it < n) maxDb - g[it] else Double.POSITIVE_INFINITY }
            val x = DoubleArray(p)
            x[n] = weightedMean(res, w)
            repeat(500) {
                for (k in 0 until p) {
                    var dot = 0.0
                    for (j in 0 until p) dot += ata[k][j] * x[j]
                    x[k] = (x[k] + (atb[k] - dot) / ata[k][k]).coerceIn(lo[k], hi[k])
                }
            }
            var maxStep = 0.0
            for (k in 0 until n) {
                g[k] = (g[k] + x[k]).coerceIn(minDb, maxDb)
                maxStep = maxOf(maxStep, abs(x[k]))
            }
            if (maxStep < 1e-6) break
        }
        val resp = deviceResponse(freqs, qs, g)
        val off = weightedMean(DoubleArray(m) { target[it] - resp[it] }, w)
        val fitted = DoubleArray(m) { resp[it] + off }
        val err = sqrt(weightedMean(DoubleArray(m) { (target[it] - fitted[it]).let { e -> e * e } }, w))
        return Result(g.toList(), off, err, target, fitted)
    }

    /** Average level change (dB, weighted like the fit) the device EQ causes: how much louder it plays. */
    fun meanLevelDb(freqs: List<Double>, qs: List<Double>, gains: List<Double>): Double =
        weightedMean(deviceResponse(freqs, qs, gains.toDoubleArray()), Filters.WEIGHTS)

    /** Largest boost anywhere in the response (dB): the clipping-risk figure. */
    fun peakDb(freqs: List<Double>, qs: List<Double>, gains: List<Double>): Double =
        deviceResponse(freqs, qs, gains.toDoubleArray()).max()

    private fun weightedMean(x: DoubleArray, w: DoubleArray): Double {
        var s = 0.0; var ws = 0.0
        for (i in x.indices) { s += x[i] * w[i]; ws += w[i] }
        return s / ws
    }
}
