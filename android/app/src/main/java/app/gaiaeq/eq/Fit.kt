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
            val off = weightedMean(DoubleArray(m) { target[it] - base[it] }, w)
            val res = DoubleArray(m) { target[it] - base[it] - off }
            // Normal equations (J^T W J + lambda I) x = J^T W r
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
            val step = solve(ata, atb)
            var maxStep = 0.0
            for (k in 0 until n) {
                g[k] = (g[k] + step[k]).coerceIn(minDb, maxDb)
                maxStep = maxOf(maxStep, abs(step[k]))
            }
            if (maxStep < 1e-4) break
        }
        val resp = deviceResponse(freqs, qs, g)
        val off = weightedMean(DoubleArray(m) { target[it] - resp[it] }, w)
        val fitted = DoubleArray(m) { resp[it] + off }
        val err = sqrt(weightedMean(DoubleArray(m) { (target[it] - fitted[it]).let { e -> e * e } }, w))
        return Result(g.toList(), off, err, target, fitted)
    }

    private fun weightedMean(x: DoubleArray, w: DoubleArray): Double {
        var s = 0.0; var ws = 0.0
        for (i in x.indices) { s += x[i] * w[i]; ws += w[i] }
        return s / ws
    }

    /** Gaussian elimination with partial pivoting. */
    private fun solve(a0: Array<DoubleArray>, b0: DoubleArray): DoubleArray {
        val n = b0.size
        val a = Array(n) { a0[it].copyOf() }
        val b = b0.copyOf()
        for (col in 0 until n) {
            var piv = col
            for (r in col + 1 until n) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
            val tr = a[col]; a[col] = a[piv]; a[piv] = tr
            val tb = b[col]; b[col] = b[piv]; b[piv] = tb
            val d = a[col][col]
            if (abs(d) < 1e-15) continue
            for (r in col + 1 until n) {
                val f = a[r][col] / d
                for (c in col until n) a[r][c] -= f * a[col][c]
                b[r] -= f * b[col]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = b[r]
            for (c in r + 1 until n) s -= a[r][c] * x[c]
            x[r] = if (abs(a[r][r]) < 1e-15) 0.0 else s / a[r][r]
        }
        return x
    }
}
