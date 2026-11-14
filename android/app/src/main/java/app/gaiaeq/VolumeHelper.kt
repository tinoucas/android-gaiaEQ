package app.gaiaeq

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Changes the phone's media volume by an amount in dB, to compensate the loudness
 * a boosting EQ adds. Uses Android's per-step dB table for the current output when it
 * exposes one; otherwise estimates from the number of steps.
 */
class VolumeHelper(ctx: Context) {

    data class Change(val stepsMoved: Int, val changedDb: Double, val exact: Boolean)

    private val am = ctx.getSystemService(AudioManager::class.java)
    private val stream = AudioManager.STREAM_MUSIC

    private fun outputDeviceType(): Int {
        if (Build.VERSION.SDK_INT >= 33) {
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
            am.getAudioDevicesForAttributes(attrs).firstOrNull()?.let { return it.type }
        }
        return AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
    }

    /** dB for each volume index, or null if the platform doesn't give a usable curve. */
    private fun curve(min: Int, max: Int): DoubleArray? {
        val type = outputDeviceType()
        val db = try {
            DoubleArray(max - min + 1) { am.getStreamVolumeDb(stream, min + it, type).toDouble() }
        } catch (_: Exception) {
            return null
        }
        val finite = db.filter { it.isFinite() }
        // Absolute-volume Bluetooth outputs may report a flat curve: unusable.
        return if (finite.size >= 3 && finite.max() - finite.min() > 10) db else null
    }

    /** Move media volume by about [deltaDb] (negative = quieter). Shows the system volume UI. */
    fun changeBy(deltaDb: Double): Change {
        val min = am.getStreamMinVolume(stream)
        val max = am.getStreamMaxVolume(stream)
        val idx = am.getStreamVolume(stream)
        val db = curve(min, max)
        val target: Int
        val changed: Double
        val exact: Boolean
        if (db != null && db[idx - min].isFinite()) {
            val want = db[idx - min] + deltaDb
            target = (min..max).filter { db[it - min].isFinite() }
                .minBy { abs(db[it - min] - want) }
            changed = db[target - min] - db[idx - min]
            exact = true
        } else {
            // Rough guess: ~45 dB of usable range spread over the steps.
            val perStep = (45.0 / (max - min).coerceAtLeast(1)).coerceIn(1.0, 4.0)
            target = (idx + (deltaDb / perStep).roundToInt()).coerceIn(min, max)
            changed = (target - idx) * perStep
            exact = false
        }
        if (target != idx) am.setStreamVolume(stream, target, AudioManager.FLAG_SHOW_UI)
        return Change(target - idx, changed, exact)
    }

    /** Undo a previous change by moving back the same number of steps. */
    fun moveSteps(steps: Int) {
        if (steps == 0) return
        val min = am.getStreamMinVolume(stream)
        val max = am.getStreamMaxVolume(stream)
        val idx = am.getStreamVolume(stream)
        am.setStreamVolume(stream, (idx + steps).coerceIn(min, max), AudioManager.FLAG_SHOW_UI)
    }
}
