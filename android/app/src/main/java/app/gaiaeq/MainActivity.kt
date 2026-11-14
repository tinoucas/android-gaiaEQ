package app.gaiaeq

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.gaiaeq.eq.Filters
import app.gaiaeq.eq.Fit
import kotlin.math.ln
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val vm: EqViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val ctx = LocalContext.current
            val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            MaterialTheme(colorScheme = scheme) { App(vm) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: EqViewModel) {
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.permissionGranted = granted
        vm.refreshDevices()
    }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        vm.permissionGranted = granted
        if (granted) vm.refreshDevices() else launcher.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }
    LaunchedEffect(vm.message) {
        vm.message?.let { snackbar.showSnackbar(it); vm.message = null }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(vm.connectedDevice?.let { vm.variant.ifEmpty { it.name } } ?: "GAIA EQ") },
                actions = {
                    if (vm.connectedDevice != null) TextButton(onClick = vm::disconnect) { Text("Devices") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            vm.busy?.let {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            when {
                !vm.permissionGranted -> PermissionCard { launcher.launch(Manifest.permission.BLUETOOTH_CONNECT) }
                vm.connectedDevice == null -> DeviceList(vm)
                else -> EqScreen(vm)
            }
        }
    }
}

@Composable
fun PermissionCard(onRequest: () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Bluetooth permission needed", fontWeight = FontWeight.Bold)
            Text("The app talks to your paired neckband over Bluetooth to read and set its built-in EQ.")
            Button(onClick = onRequest) { Text("Grant") }
        }
    }
}

@Composable
fun DeviceList(vm: EqViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Paired devices", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = vm::refreshDevices) { Text("Refresh") }
    }
    Text(
        "Pick your neckband. It must be paired with this phone (Android Bluetooth settings). " +
            "Devices with a serial port profile are listed first.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (vm.devices.isEmpty()) Text("No paired devices.")
    vm.devices.forEach { d ->
        Card(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = vm.busy == null) { vm.connect(d) },
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(d.name, fontWeight = FontWeight.SemiBold)
                Text(
                    d.address + if (d.hasSpp) "  ·  serial port" else "",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
fun EqScreen(vm: EqViewModel) {
    val dev = vm.connectedDevice ?: return
    Text(
        "${dev.address}  ·  fw ${vm.firmware}",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )

    // Preset toggle
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = vm.selectedSet == 0,
            onClick = { vm.selectSet(0) },
            label = { Text("Flat") },
            enabled = vm.busy == null,
        )
        FilterChip(
            selected = vm.selectedSet == 63,
            onClick = { vm.selectSet(63) },
            label = { Text("Custom EQ") },
            enabled = vm.busy == null,
        )
        if (vm.selectedSet != 0 && vm.selectedSet != 63 && vm.selectedSet >= 0)
            Text("preset ${vm.selectedSet}", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = vm::refreshEq, enabled = vm.busy == null) { Text("Re-read") }
    }
    if (vm.eqState == 0) {
        Text(
            "Audio is idle: the device only accepts preset switches while something is playing. " +
                "Band gains are saved either way.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }

    // Graph
    ResponseGraph(vm)

    // Sliders
    vm.bands.forEachIndexed { i, b ->
        val g = vm.pendingGains.getOrElse(i) { 0.0 }
        Column {
            Row {
                Text(formatHz(b.freqHz), fontWeight = FontWeight.SemiBold, modifier = Modifier.width(72.dp))
                Text("Q %.2f".format(b.q), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text("%+.1f dB".format(g), fontFamily = FontFamily.Monospace)
            }
            Slider(
                value = g.toFloat(),
                onValueChange = { vm.setPendingGain(i, (it * 2).roundToInt() / 2.0) },
                valueRange = -12f..6f,
                steps = 35,
                enabled = vm.busy == null,
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { vm.applyGains() }, enabled = vm.busy == null && vm.dirty) { Text("Apply") }
        OutlinedButton(onClick = vm::revertPending, enabled = vm.busy == null && vm.dirty) { Text("Revert") }
        OutlinedButton(onClick = vm::resetPending, enabled = vm.busy == null) { Text("Zero") }
    }
    Text(
        "Range −12…+6 dB. The device has no preamp, so large boosts can clip on loud tracks.",
        style = MaterialTheme.typography.bodySmall,
    )

    HorizontalDivider()
    AutoEqSection(vm)
    HorizontalDivider()
    SavedSection(vm)
    Spacer(Modifier.height(32.dp))
}

@Composable
fun AutoEqSection(vm: EqViewModel) {
    Text("AutoEq", style = MaterialTheme.typography.titleMedium)
    Text(
        "Search your IEM. Its AutoEq correction is fitted to this device's ${vm.bands.size} fixed bands.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = vm.autoEqQuery,
            onValueChange = { vm.autoEqQuery = it },
            label = { Text("IEM / headphone") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.searchAutoEq() }),
            modifier = Modifier.weight(1f),
        )
        Button(onClick = vm::searchAutoEq, enabled = vm.busy == null && vm.autoEqQuery.isNotBlank()) {
            Text("Search")
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = vm.cutsOnly, onCheckedChange = { vm.cutsOnly = it; if (vm.fitResult != null) vm.refit() })
        Text("Cuts only (no boost, avoids clipping)", style = MaterialTheme.typography.bodySmall)
    }

    val fit = vm.fitResult
    if (fit != null) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(vm.autoEqEntry?.let { "${it.name} (${it.label})" } ?: "", fontWeight = FontWeight.SemiBold)
                Text(
                    "${vm.fitSource} · fit error %.2f dB RMS · level offset %+.1f dB".format(fit.rmsErrorDb, fit.offsetDb),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    vm.bands.zip(fit.gainsDb).joinToString("   ") { (b, g) -> "${formatHz(b.freqHz)} %+.1f".format(g) },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::applyFit, enabled = vm.busy == null) { Text("Apply to device") }
                }
            }
        }
    }

    vm.autoEqResults.forEach { e ->
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = vm.busy == null) { vm.pickAutoEq(e) }
                .padding(vertical = 6.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(e.name)
                Text(e.label, style = MaterialTheme.typography.bodySmall)
            }
            if (vm.autoEqEntry == e) Text("✓", color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun SavedSection(vm: EqViewModel) {
    var name by remember { mutableStateOf("") }
    Text("Saved on this phone", style = MaterialTheme.typography.titleMedium)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Name current sliders") }, singleLine = true, modifier = Modifier.weight(1f),
        )
        Button(onClick = { vm.savePreset(name); name = "" }, enabled = name.isNotBlank()) { Text("Save") }
    }
    vm.saved.forEach { p ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name)
                Text(
                    p.gains.joinToString(" ") { "%+.1f".format(it) },
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                )
            }
            TextButton(onClick = { vm.loadPreset(p) }) { Text("Load") }
            TextButton(onClick = { vm.deletePreset(p) }) { Text("Delete") }
        }
    }
    if (vm.saved.isNotEmpty())
        Text("Load fills the sliders; press Apply to send them.", style = MaterialTheme.typography.bodySmall)
}

/** Device response from the current sliders, plus AutoEq target/fit when present. */
@Composable
fun ResponseGraph(vm: EqViewModel) {
    val bands = vm.bands
    if (bands.isEmpty()) return
    val freqs = bands.map { it.freqHz.toDouble() }
    val qs = bands.map { it.q }
    val curve = remember(vm.pendingGains, bands) {
        Fit.deviceResponse(freqs, qs, vm.pendingGains.toDoubleArray())
    }
    val fit: Fit.Result? = vm.fitResult
    val grid = MaterialTheme.colorScheme.outlineVariant
    val main = MaterialTheme.colorScheme.primary
    val target = MaterialTheme.colorScheme.tertiary
    val label = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(160.dp),
    ) {
        val lo = ln(20.0); val hi = ln(20000.0)
        val dbMax = 12.0; val dbMin = -15.0
        fun x(f: Double) = ((ln(f) - lo) / (hi - lo) * size.width).toFloat()
        fun y(db: Double) = ((dbMax - db.coerceIn(dbMin, dbMax)) / (dbMax - dbMin) * size.height).toFloat()

        for (db in listOf(-12.0, -6.0, 0.0, 6.0)) {
            drawLine(grid, Offset(0f, y(db)), Offset(size.width, y(db)),
                strokeWidth = if (db == 0.0) 2f else 1f)
        }
        for (f in listOf(100.0, 1000.0, 10000.0)) {
            drawLine(grid, Offset(x(f), 0f), Offset(x(f), size.height), strokeWidth = 1f)
        }
        fun path(values: DoubleArray, offset: Double = 0.0) = Path().apply {
            Filters.GRID.forEachIndexed { i, f ->
                val p = Offset(x(f), y(values[i] - offset))
                if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
        }
        if (fit != null) {
            // target shown relative to the fit's level offset so both share the 0 dB line
            drawPath(path(fit.target, fit.offsetDb), target,
                style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))))
        }
        drawPath(path(curve), main, style = Stroke(width = 4f))
        bands.forEachIndexed { i, b ->
            drawCircle(main, radius = 6f, center = Offset(x(b.freqHz.toDouble()), y(vm.pendingGains.getOrElse(i) { 0.0 })))
        }
        drawContext.canvas.nativeCanvas.apply {
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb((label.alpha * 255).toInt(), (label.red * 255).toInt(),
                    (label.green * 255).toInt(), (label.blue * 255).toInt())
                textSize = 26f
            }
            drawText("100", x(100.0) + 4, size.height - 4, paint)
            drawText("1k", x(1000.0) + 4, size.height - 4, paint)
            drawText("10k", x(10000.0) + 4, size.height - 4, paint)
            drawText("+6", 4f, y(6.0) - 4, paint)
            drawText("-6", 4f, y(-6.0) - 4, paint)
        }
    }
    if (fit != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("— device (sliders)", color = main, style = MaterialTheme.typography.bodySmall)
            Text("- - AutoEq target", color = target, style = MaterialTheme.typography.bodySmall)
        }
    }
}

fun formatHz(hz: Int): String = if (hz >= 1000) {
    val k = hz / 1000.0
    if (k == k.toInt().toDouble()) "${k.toInt()}k Hz" else "%.1fk Hz".format(k)
} else "$hz Hz"
