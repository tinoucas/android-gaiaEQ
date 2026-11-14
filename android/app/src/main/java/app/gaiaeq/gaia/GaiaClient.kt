package app.gaiaeq.gaia

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Qualcomm GAIA v3 over classic RFCOMM (SPP).
 *
 * Frame:  0xFF | version | flags | len | vendor(2) | command(2) | payload [| checksum]
 *   flags bit0 = checksum present, bit1 = 2-byte length.
 * command = feature(7 bits) << 9 | type(2 bits) << 7 | id(7 bits)
 *   type: 0 command, 1 notification, 2 response, 3 error
 *
 * Verified on QCC5181 neckbands (BGVP M4, BT-5181Pro) running ADK reference firmware.
 */
class GaiaClient(private val device: BluetoothDevice) {

    class GaiaException(msg: String) : Exception(msg)

    data class Reply(val ok: Boolean, val payload: ByteArray) {
        val errorCode: Int get() = if (!ok && payload.isNotEmpty()) payload[0].toInt() and 0xFF else -1
    }

    private var socket: BluetoothSocket? = null
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    private val lock = Mutex()
    @Volatile var connected = false
        private set

    @SuppressLint("MissingPermission")
    suspend fun connect() = withContext(Dispatchers.IO) {
        val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
        s.connect()
        socket = s
        connected = true
        thread(name = "gaia-reader", isDaemon = true) { readLoop(s) }
    }

    fun close() {
        connected = false
        try { socket?.close() } catch (_: IOException) {}
        socket = null
    }

    private fun readLoop(s: BluetoothSocket) {
        val input = s.inputStream
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(1024)
        try {
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                buf.write(chunk, 0, n)
                var data = buf.toByteArray()
                var pos = 0
                while (true) {
                    // resync on start-of-frame
                    while (pos < data.size && data[pos] != 0xFF.toByte()) pos++
                    if (data.size - pos < 4) break
                    val flags = data[pos + 2].toInt() and 0xFF
                    val (len, hdr) = if (flags and 2 != 0) {
                        if (data.size - pos < 5) break
                        (((data[pos + 3].toInt() and 0xFF) shl 8) or (data[pos + 4].toInt() and 0xFF)) to 5
                    } else {
                        (data[pos + 3].toInt() and 0xFF) to 4
                    }
                    val total = hdr + 4 + len + (if (flags and 1 != 0) 1 else 0)
                    if (data.size - pos < total) break
                    incoming.trySend(data.copyOfRange(pos + hdr, pos + hdr + 4 + len))
                    pos += total
                }
                buf.reset()
                buf.write(data, pos, data.size - pos)
            }
        } catch (_: IOException) {
        } finally {
            connected = false
        }
    }

    /** Send one command and wait for its response/error. Returns null on timeout. */
    suspend fun command(feature: Int, id: Int, payload: ByteArray = ByteArray(0),
                        timeoutMs: Long = 3000): Reply? = lock.withLock {
        val s = socket ?: throw GaiaException("not connected")
        while (incoming.tryReceive().isSuccess) { /* drop stale notifications */ }
        val cid = (feature shl 9) or (id and 0x7F)
        val pdu = byteArrayOf(
            (VENDOR_QTIL shr 8).toByte(), VENDOR_QTIL.toByte(),
            (cid shr 8).toByte(), cid.toByte()) + payload
        val frame = byteArrayOf(0xFF.toByte(), 0x01, 0x00, payload.size.toByte()) + pdu
        withContext(Dispatchers.IO) {
            s.outputStream.write(frame)
            s.outputStream.flush()
        }
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                val p = incoming.receive()
                if (p.size < 4) continue
                val vendor = ((p[0].toInt() and 0xFF) shl 8) or (p[1].toInt() and 0xFF)
                val c = ((p[2].toInt() and 0xFF) shl 8) or (p[3].toInt() and 0xFF)
                val type = (c shr 7) and 3
                if (vendor == VENDOR_QTIL && c shr 9 == feature && c and 0x7F == id && type >= 2) {
                    return@withTimeoutOrNull Reply(type == 2, p.copyOfRange(4, p.size))
                }
            }
            @Suppress("UNREACHABLE_CODE") null
        }
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
        const val VENDOR_QTIL = 0x001D

        const val FEATURE_CORE = 0x00
        const val FEATURE_MUSIC = 0x05

        fun errorText(code: Int) = when (code) {
            0 -> "feature not supported"
            1 -> "command not supported"
            2 -> "not authenticated"
            3 -> "insufficient resources"
            4 -> "in progress"
            5 -> "invalid parameter"
            else -> "error $code"
        }
    }
}
