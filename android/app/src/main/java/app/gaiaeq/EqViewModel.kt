package app.gaiaeq

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.gaiaeq.eq.AutoEq
import app.gaiaeq.eq.Fit
import app.gaiaeq.gaia.Band
import app.gaiaeq.gaia.GaiaClient
import app.gaiaeq.gaia.GaiaEq
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class DeviceItem(val name: String, val address: String, val hasSpp: Boolean, val device: BluetoothDevice)

data class SavedPreset(val name: String, val gains: List<Double>)

enum class FitChoice { FAITHFUL, SAFE }

class EqViewModel(app: Application) : AndroidViewModel(app) {

    // ---- UI state
    var permissionGranted by mutableStateOf(false)
    var devices by mutableStateOf<List<DeviceItem>>(emptyList())
        private set
    var connectedDevice by mutableStateOf<DeviceItem?>(null)
        private set
    var busy by mutableStateOf<String?>(null)          // non-null = operation in progress
        private set
    var message by mutableStateOf<String?>(null)
    var variant by mutableStateOf("")
        private set
    var firmware by mutableStateOf("")
        private set
    var bands by mutableStateOf<List<Band>>(emptyList())
        private set
    var pendingGains by mutableStateOf<List<Double>>(emptyList())
        private set
    var selectedSet by mutableStateOf(-1)
        private set
    var eqState by mutableStateOf(-1)
        private set

    var autoEqQuery by mutableStateOf("")
    var autoEqResults by mutableStateOf<List<AutoEq.Entry>>(emptyList())
        private set
    var autoEqEntry by mutableStateOf<AutoEq.Entry?>(null)
        private set
    /** Fit allowing boosts (closest to the preset) and cuts-only fit (no clipping risk). */
    var fitFaithful by mutableStateOf<Fit.Result?>(null)
        private set
    var fitSafe by mutableStateOf<Fit.Result?>(null)
        private set
    var fitChoice by mutableStateOf(FitChoice.FAITHFUL)
    val fitResult: Fit.Result? get() = if (fitChoice == FitChoice.SAFE) fitSafe else fitFaithful
    var fitSource by mutableStateOf("")
        private set
    var fitLabel by mutableStateOf("")
        private set
    var autoEqStatus by mutableStateOf<String?>(null)
        private set
    var pastedPreset by mutableStateOf("")

    var autoVolume by mutableStateOf(prefs.getBoolean("auto_volume", true))
        private set
    private val volume = VolumeHelper(app)

    var saved by mutableStateOf(loadSaved())
        private set

    private var client: GaiaClient? = null
    private var eq: GaiaEq? = null
    private var autoEqIndex: List<AutoEq.Entry>? = null

    val dirty: Boolean get() = bands.isNotEmpty() && pendingGains != bands.map { it.gainDb }

    private fun launchOp(label: String, block: suspend () -> Unit) {
        if (busy != null) {
            message = "Busy: $busy"
            return
        }
        busy = label
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                message = e.message ?: e.javaClass.simpleName
                if (client?.connected == false && connectedDevice != null) dropConnection()
            } finally {
                busy = null
            }
        }
    }

    // ---- devices

    @SuppressLint("MissingPermission")
    fun refreshDevices() {
        if (!permissionGranted) return
        val bm = getApplication<Application>().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bm.adapter ?: run { message = "No Bluetooth adapter"; return }
        if (!adapter.isEnabled) message = "Bluetooth is off"
        devices = adapter.bondedDevices.orEmpty().map { d ->
            val spp = d.uuids?.any { it.uuid == GaiaClient.SPP_UUID } == true
            DeviceItem(d.name ?: d.address, d.address, spp, d)
        }.sortedWith(compareBy({ !it.hasSpp }, { it.name }))
    }

    fun connect(item: DeviceItem) = launchOp("Connecting to ${item.name}…") {
        dropConnection()
        val c = GaiaClient(item.device)
        try {
            c.connect()
        } catch (e: Exception) {
            throw Exception("Couldn't open GAIA channel on ${item.name}: ${e.message}")
        }
        val e = GaiaEq(c)
        val features = try {
            e.supportedFeatures()
        } catch (ex: Exception) {
            c.close(); throw Exception("${item.name} doesn't answer GAIA v3 (${ex.message})")
        }
        if (GaiaClient.FEATURE_MUSIC !in features) {
            c.close(); throw Exception("${item.name} speaks GAIA but has no EQ feature")
        }
        client = c; eq = e
        connectedDevice = item
        variant = runCatching { e.variantName() }.getOrDefault(item.name)
        firmware = runCatching { e.appVersion() }.getOrDefault("?")
        readEq()
    }

    private suspend fun readEq() {
        val e = eq ?: return
        bands = e.bands()
        pendingGains = bands.map { it.gainDb }
        selectedSet = e.selectedSet()
        eqState = e.eqState()
        fitFaithful = null; fitSafe = null
    }

    fun refreshEq() = launchOp("Reading EQ…") { readEq() }

    fun disconnect() = dropConnection()

    private fun dropConnection() {
        client?.close()
        client = null; eq = null
        connectedDevice = null
        bands = emptyList(); pendingGains = emptyList()
        selectedSet = -1; eqState = -1; fitFaithful = null; fitSafe = null
    }

    // ---- EQ editing

    fun setPendingGain(i: Int, db: Double) {
        pendingGains = pendingGains.toMutableList().also { it[i] = db }
    }

    fun resetPending() { pendingGains = pendingGains.map { 0.0 } }

    fun revertPending() { pendingGains = bands.map { it.gainDb } }

    /** Write pending gains, then select the user set so they're audible. */
    fun applyGains(gains: List<Double> = pendingGains) = launchOp("Writing EQ…") {
        val e = eq ?: return@launchOp
        e.setGains(gains)
        bands = e.bands()
        pendingGains = bands.map { it.gainDb }
        selectSetInternal(GaiaEq.SET_USER)
    }

    fun selectSet(id: Int) = launchOp("Switching preset…") { selectSetInternal(id) }

    private suspend fun selectSetInternal(id: Int) {
        val e = eq ?: return
        eqState = e.eqState()
        selectedSet = e.selectSet(id)
        if (selectedSet == GaiaEq.SET_USER && id == GaiaEq.SET_USER && bands.isNotEmpty()) {
            // Firmware quirk (QCC5181 ADK): selecting the user set records the choice but
            // doesn't reload the stored gains into the DSP when coming from another preset.
            // Re-writing the gains while the user set is active makes them audible.
            e.setGains(bands.map { it.gainDb })
        }
        if (selectedSet != id) {
            message = if (eqState == 0) "Start playing audio, then switch again — the device ignores preset changes while idle"
            else "Device kept preset $selectedSet"
        } else {
            compensateVolume()
        }
    }

    // ---- volume compensation

    private fun volKey(k: String) = "vol_${k}_${connectedDevice?.address}"

    /** Average loudness the active EQ adds (0 when flat or when it mostly cuts). */
    private fun activeBoostDb(): Double {
        if (selectedSet != GaiaEq.SET_USER || bands.isEmpty()) return 0.0
        val lvl = Fit.meanLevelDb(bands.map { it.freqHz.toDouble() }, bands.map { it.q }, bands.map { it.gainDb })
        return lvl.coerceAtLeast(0.0)
    }

    /** Undo any previous compensation, then lower media volume by the active EQ's boost. */
    private fun compensateVolume() {
        if (!autoVolume || connectedDevice == null) return
        val prevSteps = prefs.getInt(volKey("steps"), 0)
        val prevDb = prefs.getFloat(volKey("db"), 0f).toDouble()
        val need = activeBoostDb()
        if (kotlin.math.abs(need - prevDb) < 0.5) return
        volume.moveSteps(-prevSteps)
        var steps = 0
        var db = 0.0
        if (need >= 0.5) {
            val c = volume.changeBy(-need)
            steps = c.stepsMoved; db = need
            message = "EQ adds ~%.1f dB: media volume lowered %.1f dB%s".format(
                need, -c.changedDb, if (c.exact) "" else " (estimated)")
        } else if (prevSteps != 0) {
            message = "Media volume restored (+%.1f dB)".format(prevDb)
        }
        prefs.edit().putInt(volKey("steps"), steps).putFloat(volKey("db"), db.toFloat()).apply()
    }

    fun changeAutoVolume(on: Boolean) {
        autoVolume = on
        prefs.edit().putBoolean("auto_volume", on).apply()
        if (connectedDevice == null) return
        if (on) {
            compensateVolume()
        } else {
            volume.moveSteps(-prefs.getInt(volKey("steps"), 0))
            prefs.edit().putInt(volKey("steps"), 0).putFloat(volKey("db"), 0f).apply()
        }
    }

    // ---- AutoEq

    fun searchAutoEq() {
        val query = autoEqQuery.trim()
        if (query.isEmpty()) return
        if (busy != null) {
            message = "Busy: $busy"
            return
        }
        autoEqStatus = "Searching…"
        launchOp("Searching AutoEq…") {
            try {
                val cache = getApplication<Application>().cacheDir
                val idx = autoEqIndex ?: withContext(Dispatchers.IO) { AutoEq.loadIndex(cache) }
                    .also { autoEqIndex = it }
                autoEqResults = AutoEq.search(idx, query)
                autoEqStatus = when (autoEqResults.size) {
                    0 -> "\"$query\" isn't in AutoEq's database (${idx.size} profiles). " +
                        "You can paste an EQ exported from a graph tool below."
                    1 -> "1 result for \"$query\""
                    else -> "${autoEqResults.size} results for \"$query\""
                }
            } catch (e: Exception) {
                autoEqStatus = "Search failed: ${e.message ?: e.javaClass.simpleName}"
                throw e
            }
        }
    }

    fun pickAutoEq(entry: AutoEq.Entry) = launchOp("Fitting ${entry.name}…") {
        val txt = withContext(Dispatchers.IO) { AutoEq.fetch(entry.parametricUrl) }
        autoEqEntry = entry
        fitTo(txt, "${entry.name} (${entry.label})")
    }

    /** Fit a pasted ParametricEQ (APO "Filter N: ON PK Fc …") or GraphicEQ text. */
    fun fitPasted() = launchOp("Fitting pasted preset…") {
        autoEqEntry = null
        fitTo(pastedPreset, "Pasted preset")
    }

    private suspend fun fitTo(presetText: String, label: String) {
        if (bands.isEmpty()) throw Exception("Connect to a device first")
        val (target, kind) = try {
            AutoEq.targetFromText(presetText)
        } catch (e: IllegalArgumentException) {
            throw Exception("Couldn't read preset: ${e.message}")
        }
        val freqs = bands.map { it.freqHz.toDouble() }
        val qs = bands.map { it.q }
        val (faithful, safe) = withContext(Dispatchers.Default) {
            Fit.fit(target, freqs, qs, minDb = -12.0, maxDb = 6.0) to
                Fit.fit(target, freqs, qs, minDb = -12.0, maxDb = 0.0)
        }
        fitFaithful = faithful
        fitSafe = safe
        fitChoice = FitChoice.FAITHFUL
        fitSource = kind
        fitLabel = label
    }

    fun levelOf(r: Fit.Result): Double =
        Fit.meanLevelDb(bands.map { it.freqHz.toDouble() }, bands.map { it.q }, r.gainsDb)

    fun peakOf(r: Fit.Result): Double =
        Fit.peakDb(bands.map { it.freqHz.toDouble() }, bands.map { it.q }, r.gainsDb)

    fun applyFit() {
        val f = fitResult ?: return
        val g = f.gainsDb.map { Math.round(it * 10) / 10.0 }
        pendingGains = g
        applyGains(g)
    }

    // ---- saved presets (on the phone)

    fun savePreset(name: String) {
        if (name.isBlank() || pendingGains.isEmpty()) return
        saved = saved.filter { it.name != name } + SavedPreset(name.trim(), pendingGains)
        persist()
    }

    fun deletePreset(p: SavedPreset) { saved = saved - p; persist() }

    fun loadPreset(p: SavedPreset) {
        if (p.gains.size != pendingGains.size) {
            message = "\"${p.name}\" has ${p.gains.size} bands, device has ${pendingGains.size}"
            return
        }
        pendingGains = p.gains
    }

    private val prefs get() = getApplication<Application>().getSharedPreferences("presets", Context.MODE_PRIVATE)

    private fun loadSaved(): List<SavedPreset> = runCatching {
        val arr = JSONArray(prefs.getString("list", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val g = o.getJSONArray("gains")
            SavedPreset(o.getString("name"), (0 until g.length()).map { g.getDouble(it) })
        }
    }.getOrDefault(emptyList())

    private fun persist() {
        val arr = JSONArray()
        saved.forEach { p -> arr.put(JSONObject().put("name", p.name).put("gains", JSONArray(p.gains))) }
        prefs.edit().putString("list", arr.toString()).apply()
    }

    override fun onCleared() {
        client?.close()
    }
}
