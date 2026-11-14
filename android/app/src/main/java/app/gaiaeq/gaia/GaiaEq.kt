package app.gaiaeq.gaia

import app.gaiaeq.gaia.GaiaClient.Companion.FEATURE_CORE
import app.gaiaeq.gaia.GaiaClient.Companion.FEATURE_MUSIC
import kotlin.math.roundToInt

/** One user-EQ band as reported by the device. Gain is the only writable field. */
data class Band(val freqHz: Int, val q: Double, val filterType: Int, val gainDb: Double)

/**
 * GAIA v3 "Music Processing" (feature 0x05) user EQ, as verified on QCC5181 ADK firmware:
 *   0x00 get EQ state      -> [state]  (0 while audio idle, 1 while streaming)
 *   0x01 get presets       -> [count, id...]   (63 = user set, 0 = flat)
 *   0x02 get selected set  -> [id]
 *   0x03 set selected set  <- [id]             (only sticks while audio is streaming)
 *   0x04 get user band count -> [n]
 *   0x05 get user band cfg <- [start, end] -> [start, end, (freq u16, Q u16/4096, type u8, gain i16/60)...]
 *   0x06 set user band gains <- [start, end, gain i16 (dB*60)...]  (gain only; freq/Q fixed)
 */
class GaiaEq(private val c: GaiaClient) {

    class EqException(msg: String) : Exception(msg)

    private suspend fun ok(feature: Int, id: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val r = c.command(feature, id, payload) ?: throw EqException("no reply from device")
        if (!r.ok) throw EqException(GaiaClient.errorText(r.errorCode))
        return r.payload
    }

    /** Map of feature id -> version. */
    suspend fun supportedFeatures(): Map<Int, Int> {
        val out = linkedMapOf<Int, Int>()
        var p = ok(FEATURE_CORE, 0x01)
        while (true) {
            val more = p.isNotEmpty() && p[0].toInt() != 0
            var i = 1
            while (i + 1 < p.size) {
                out[p[i].toInt() and 0xFF] = p[i + 1].toInt() and 0xFF
                i += 2
            }
            if (!more) break
            p = ok(FEATURE_CORE, 0x02)
        }
        return out
    }

    suspend fun variantName(): String = String(ok(FEATURE_CORE, 0x04)).trimEnd('\u0000')
    suspend fun appVersion(): String = String(ok(FEATURE_CORE, 0x05)).trimEnd('\u0000')

    suspend fun eqState(): Int = ok(FEATURE_MUSIC, 0x00).firstOrNull()?.toInt()?.and(0xFF) ?: -1

    suspend fun presets(): List<Int> {
        val p = ok(FEATURE_MUSIC, 0x01)
        return if (p.isEmpty()) emptyList() else p.drop(1).map { it.toInt() and 0xFF }
    }

    suspend fun selectedSet(): Int = ok(FEATURE_MUSIC, 0x02).firstOrNull()?.toInt()?.and(0xFF) ?: -1

    /** Returns the set the device reports afterwards (differs if audio was idle). */
    suspend fun selectSet(id: Int): Int {
        ok(FEATURE_MUSIC, 0x03, byteArrayOf(id.toByte()))
        return selectedSet()
    }

    suspend fun bands(): List<Band> {
        val n = ok(FEATURE_MUSIC, 0x04).firstOrNull()?.toInt()?.and(0xFF) ?: 0
        return (0 until n).map { b ->
            val p = ok(FEATURE_MUSIC, 0x05, byteArrayOf(b.toByte(), b.toByte()))
            if (p.size < 9) throw EqException("short band config reply")
            fun u16(i: Int) = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
            val gainRaw = u16(7).toShort().toInt()
            Band(freqHz = u16(2), q = u16(4) / 4096.0, filterType = p[6].toInt() and 0xFF,
                gainDb = gainRaw / 60.0)
        }
    }

    suspend fun setGains(gainsDb: List<Double>) {
        require(gainsDb.isNotEmpty())
        val payload = ByteArray(2 + 2 * gainsDb.size)
        payload[0] = 0
        payload[1] = (gainsDb.size - 1).toByte()
        gainsDb.forEachIndexed { i, g ->
            val raw = (g * 60).roundToInt()
            payload[2 + 2 * i] = (raw shr 8).toByte()
            payload[3 + 2 * i] = raw.toByte()
        }
        ok(FEATURE_MUSIC, 0x06, payload)
    }

    companion object {
        const val SET_FLAT = 0
        const val SET_USER = 63
    }
}
