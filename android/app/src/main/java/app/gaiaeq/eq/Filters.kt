package app.gaiaeq.eq

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class FilterKind { PK, LSC, HSC }

data class Filter(val kind: FilterKind, val fc: Double, val gainDb: Double, val q: Double)

object Filters {
    const val FS = 48000.0

    /** Log-spaced evaluation grid, 20 Hz - 20 kHz. */
    val GRID: DoubleArray = DoubleArray(300) { i -> 20.0 * 1000.0.pow(i / 299.0) }

    /** Weight: trust measurements below 10 kHz more. */
    val WEIGHTS: DoubleArray = DoubleArray(GRID.size) { if (GRID[it] <= 10000) 1.0 else 0.3 }

    /** RBJ cookbook biquad magnitude in dB at each frequency in [f]. */
    fun responseDb(flt: Filter, f: DoubleArray = GRID): DoubleArray {
        val a = 10.0.pow(flt.gainDb / 40)
        val w0 = 2 * PI * flt.fc / FS
        val cw = cos(w0)
        val alpha = sin(w0) / (2 * flt.q)
        val b: DoubleArray
        val den: DoubleArray
        when (flt.kind) {
            FilterKind.PK -> {
                b = doubleArrayOf(1 + alpha * a, -2 * cw, 1 - alpha * a)
                den = doubleArrayOf(1 + alpha / a, -2 * cw, 1 - alpha / a)
            }
            FilterKind.LSC -> {
                val s = 2 * sqrt(a) * alpha
                b = doubleArrayOf(a * ((a + 1) - (a - 1) * cw + s), 2 * a * ((a - 1) - (a + 1) * cw),
                    a * ((a + 1) - (a - 1) * cw - s))
                den = doubleArrayOf((a + 1) + (a - 1) * cw + s, -2 * ((a - 1) + (a + 1) * cw),
                    (a + 1) + (a - 1) * cw - s)
            }
            FilterKind.HSC -> {
                val s = 2 * sqrt(a) * alpha
                b = doubleArrayOf(a * ((a + 1) + (a - 1) * cw + s), -2 * a * ((a - 1) + (a + 1) * cw),
                    a * ((a + 1) + (a - 1) * cw - s))
                den = doubleArrayOf((a + 1) - (a - 1) * cw + s, 2 * ((a - 1) - (a + 1) * cw),
                    (a + 1) - (a - 1) * cw - s)
            }
        }
        return DoubleArray(f.size) { i ->
            val w = 2 * PI * f[i] / FS
            // z^-1 = e^{-jw}
            val c1 = cos(w); val s1 = -sin(w)
            val c2 = cos(2 * w); val s2 = -sin(2 * w)
            val nr = b[0] + b[1] * c1 + b[2] * c2
            val ni = b[1] * s1 + b[2] * s2
            val dr = den[0] + den[1] * c1 + den[2] * c2
            val di = den[1] * s1 + den[2] * s2
            20 * log10(hypot(nr, ni) / hypot(dr, di))
        }
    }

    fun sumDb(filters: List<Filter>, f: DoubleArray = GRID): DoubleArray {
        val out = DoubleArray(f.size)
        for (flt in filters) {
            val r = responseDb(flt, f)
            for (i in out.indices) out[i] += r[i]
        }
        return out
    }
}
