package app.gaiaeq.eq

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import kotlin.math.ln

/** AutoEq (github.com/jaakkopasanen/AutoEq) result lookup and preset parsing. */
object AutoEq {

    const val RAW = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/"
    private val SOURCE_PRIORITY = listOf("oratory1990", "crinacle", "HypetheSonics", "Rtings",
        "Innerfidelity", "Headphone.com Legacy")

    data class Entry(val name: String, val path: String, val source: String, val rig: String?) {
        val label: String get() = if (rig != null) "$source on $rig" else source
        val parametricUrl: String
            get() = "$RAW$path/${path.substringAfterLast('/')}%20ParametricEQ.txt"
    }

    private val INDEX_RE = Regex("""^- \[(.+?)]\(\./(.+?)\) by (.+?)(?: on (.+))?$""", RegexOption.MULTILINE)
    private val FILTER_RE = Regex(
        """Filter\s*\d*:\s*ON\s+(PK|LSC|HSC|LS|HS)\s+Fc\s+([\d.]+)\s*Hz\s+Gain\s+(-?[\d.]+)\s*dB\s+Q\s+([\d.]+)""",
        RegexOption.IGNORE_CASE)

    fun parseIndex(md: String): List<Entry> = INDEX_RE.findAll(md).map {
        val (name, path, source, rig) = it.destructured
        Entry(name, path, source, rig.ifEmpty { null })
    }.toList()

    /** Case-insensitive search; results sorted by name, then preferred measurement source. */
    private fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    /**
     * Every query word must appear in the name (any order, punctuation ignored;
     * also matched against the name without spaces, so "blessing2" finds "Blessing 2").
     * Exact names first, then alphabetical, then preferred measurement source.
     */
    fun search(index: List<Entry>, query: String, limit: Int = 50): List<Entry> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        val words = q.split(' ')
        fun rank(e: Entry) = SOURCE_PRIORITY.indexOf(e.source).let { if (it < 0) 99 else it }
        return index.filter { e ->
            val n = normalize(e.name)
            val compact = n.replace(" ", "")
            words.all { it in n || it in compact }
        }.sortedWith(compareBy<Entry>({ normalize(it.name) != q }, { it.name.lowercase() }, { rank(it) }))
            .take(limit)
    }

    /** Target response (dB on Filters.GRID) from a ParametricEQ.txt or GraphicEQ.txt body. */
    fun targetFromText(txt: String): Pair<DoubleArray, String> {
        if ("GraphicEQ:" in txt) {
            val pts = txt.substringAfter("GraphicEQ:").split(';').mapNotNull { p ->
                val parts = p.trim().split(Regex("\\s+"))
                if (parts.size >= 2) parts[0].toDoubleOrNull()?.let { f -> parts[1].toDoubleOrNull()?.let { f to it } }
                else null
            }.sortedBy { it.first }
            require(pts.size >= 2) { "GraphicEQ has too few points" }
            val lf = pts.map { ln(it.first) }
            val out = DoubleArray(Filters.GRID.size) { i -> interp(ln(Filters.GRID[i]), lf, pts.map { it.second }) }
            return out to "GraphicEQ (${pts.size} points)"
        }
        val filters = FILTER_RE.findAll(txt).map {
            val (k, fc, g, q) = it.destructured
            val kind = when (k.uppercase()) { "PK" -> FilterKind.PK; "LSC", "LS" -> FilterKind.LSC; else -> FilterKind.HSC }
            Filter(kind, fc.toDouble(), g.toDouble(), q.toDouble())
        }.toList()
        require(filters.isNotEmpty()) { "no filters or GraphicEQ found in preset" }
        return Filters.sumDb(filters) to "ParametricEQ (${filters.size} filters)"
    }

    private fun interp(x: Double, xs: List<Double>, ys: List<Double>): Double {
        if (x <= xs.first()) return ys.first()
        if (x >= xs.last()) return ys.last()
        var i = xs.binarySearch(x)
        if (i >= 0) return ys[i]
        i = -i - 1
        val t = (x - xs[i - 1]) / (xs[i] - xs[i - 1])
        return ys[i - 1] + t * (ys[i] - ys[i - 1])
    }

    // ------------------------------------------------------------ network (blocking; call off main thread)

    fun fetch(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("User-Agent", "gaia-eq")
        try {
            if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode} for ${decode(url)}")
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    /** The index (~850 KB) is cached in [cacheDir] for a week. */
    fun loadIndex(cacheDir: File, forceRefresh: Boolean = false): List<Entry> {
        val f = File(cacheDir, "autoeq_INDEX.md")
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < 7L * 24 * 3600 * 1000
        val md = if (fresh && !forceRefresh) f.readText() else try {
            fetch(RAW + "INDEX.md").also { f.writeText(it) }
        } catch (e: Exception) {
            if (f.exists()) f.readText() else throw e
        }
        return parseIndex(md)
    }

    fun decode(url: String): String = URLDecoder.decode(url, "UTF-8")
}
