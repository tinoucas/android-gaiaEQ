package app.gaiaeq.eq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cross-checks the Kotlin port against the Python reference (m4_autoeq.py). */
class FitTest {

    private val blessing2 = """
        Preamp: -3.3 dB
        Filter 1: ON LSC Fc 105 Hz Gain 3.3 dB Q 0.70
        Filter 2: ON PK Fc 1788 Hz Gain -3.0 dB Q 0.66
        Filter 3: ON PK Fc 820 Hz Gain 2.2 dB Q 1.20
        Filter 4: ON PK Fc 183 Hz Gain -1.4 dB Q 0.98
        Filter 5: ON PK Fc 4409 Hz Gain 2.4 dB Q 0.47
        Filter 6: ON HSC Fc 10000 Hz Gain -4.5 dB Q 0.70
        Filter 7: ON PK Fc 4129 Hz Gain 0.9 dB Q 3.28
        Filter 8: ON PK Fc 2785 Hz Gain -0.8 dB Q 3.98
        Filter 9: ON PK Fc 1939 Hz Gain 0.5 dB Q 3.63
        Filter 10: ON PK Fc 1358 Hz Gain -0.4 dB Q 4.13
    """.trimIndent()

    private val freqs = listOf(120.0, 500.0, 1500.0, 5000.0, 12000.0)
    private val qs = List(5) { 0x0B33 / 4096.0 }

    @Test
    fun matchesPythonReference() {
        val (target, kind) = AutoEq.targetFromText(blessing2)
        assertTrue(kind.startsWith("ParametricEQ (10"))
        // AutoEq curve at 20 Hz is dominated by the +3.3 dB low shelf
        assertEquals(3.3, target[0], 0.1)
        val r = Fit.fit(target, freqs, qs)
        val expected = listOf(-2.99, -2.09, -3.95, 0.42, -6.31)
        expected.zip(r.gainsDb).forEach { (e, g) -> assertEquals(e, g, 0.05) }
        assertEquals(1.06, r.rmsErrorDb, 0.02)
        assertEquals(3.3, r.offsetDb, 0.1)
    }

    @Test
    fun cutsOnlyNeverBoosts() {
        val (target, _) = AutoEq.targetFromText(blessing2)
        val r = Fit.fit(target, freqs, qs, maxDb = 0.0)
        assertTrue(r.gainsDb.all { it <= 0.0 })
    }

    @Test
    fun peakingFilterHitsGainAtCenter() {
        val r = Filters.responseDb(Filter(FilterKind.PK, 1500.0, -6.0, 0.7), doubleArrayOf(1500.0, 20.0))
        assertEquals(-6.0, r[0], 1e-6)
        assertEquals(0.0, r[1], 0.05)
    }

    @Test
    fun graphicEqParses() {
        val (t, kind) = AutoEq.targetFromText("GraphicEQ: 20 -1.0; 1000 0.0; 20000 2.0")
        assertTrue(kind.startsWith("GraphicEQ"))
        assertEquals(-1.0, t.first(), 1e-9)
        assertEquals(2.0, t.last(), 1e-9)
    }

    @Test
    fun searchMatchesWordsInAnyOrderAndCompact() {
        val md = """
            - [CrinEar Daybreak](./Super%20Review/in-ear/CrinEar%20Daybreak) by Super Review
            - [Moondrop Blessing 2](./oratory1990/in-ear/Moondrop%20Blessing%202) by oratory1990
            - [Moondrop Blessing 2 Dusk](./crinacle/711%20in-ear/Moondrop%20Blessing%202%20Dusk) by crinacle on 711
        """.trimIndent()
        val idx = AutoEq.parseIndex(md)
        assertEquals(listOf("CrinEar Daybreak"), AutoEq.search(idx, "daybreak crinear").map { it.name })
        assertEquals(2, AutoEq.search(idx, "blessing2").size)
        assertEquals("Moondrop Blessing 2", AutoEq.search(idx, "moondrop blessing 2").first().name)
        assertTrue(AutoEq.search(idx, "crinear nightfall").isEmpty())
        assertTrue(AutoEq.search(idx, "  ").isEmpty())
    }

    @Test
    fun pastedApoPresetParses() {
        val txt = "Preamp: -2.0 dB\r\nFilter 1: ON PK Fc 1000 Hz Gain -3.0 dB Q 1.00\r\n"
        val (t, kind) = AutoEq.targetFromText(txt)
        assertTrue(kind.startsWith("ParametricEQ (1"))
        val i = Filters.GRID.indices.minBy { kotlin.math.abs(Filters.GRID[it] - 1000.0) }
        assertEquals(-3.0, t[i], 0.1)
    }

    @Test
    fun indexParsesAndRanksSources() {
        val md = """
            - [Moondrop Blessing 2](./crinacle/711%20in-ear/Moondrop%20Blessing%202) by crinacle on 711
            - [Moondrop Blessing 2](./oratory1990/in-ear/Moondrop%20Blessing%202) by oratory1990
            - [Moondrop Blessing](./oratory1990/in-ear/Moondrop%20Blessing) by oratory1990
        """.trimIndent()
        val idx = AutoEq.parseIndex(md)
        assertEquals(3, idx.size)
        val hits = AutoEq.search(idx, "blessing 2")
        assertEquals("oratory1990", hits.first().source)
        assertEquals(
            AutoEq.RAW + "oratory1990/in-ear/Moondrop%20Blessing%202/Moondrop%20Blessing%202%20ParametricEQ.txt",
            hits.first().parametricUrl,
        )
    }
}
