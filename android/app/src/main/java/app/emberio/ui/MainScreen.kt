package app.emberio.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.emberio.AppState
import app.emberio.LinkStatus
import app.emberio.MugRepository
import app.emberio.Permissions
import app.emberio.TempFormat
import app.emberio.UnitPref
import app.emberio.ember.FoundMug
import app.emberio.ember.LiquidState
import app.emberio.ember.MugScanner
import app.emberio.ember.TempUnit
import app.emberio.pebble.WatchProtocol
import app.emberio.service.MugService
import io.rebble.pebblekit2.client.DefaultPebbleInfoRetriever
import io.rebble.pebblekit2.model.ConnectedWatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private data class Preset(val name: String, val fahrenheit: Double, val celsius: Double)

/** Same presets as the watch: a latte drinks cooler than coffee, tea hotter. */
private val PRESETS = listOf(
    Preset("Latte", 130.0, 54.5),
    Preset("Coffee", 135.0, 57.0),
    Preset("Tea", 140.0, 60.0),
)

private val BATTERY_THRESHOLDS = listOf(0, 10, 15, 20, 25, 30, 40, 50)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(repository: MugRepository) {
    val state by repository.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var permissionsAsked by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionsAsked = true
        repository.refreshPermissions()
        MugService.startIfReady(context)
    }
    val needsPermissions = !Permissions.canConnect(context) || !Permissions.canScan(context)

    Scaffold(topBar = { TopAppBar(title = { Text("Emberio") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (needsPermissions || !Permissions.canNotify(context)) {
                item {
                    InfoCard(
                        title = "Bluetooth and notifications",
                        body = "Emberio needs Bluetooth to reach your mug, and notifications so alerts reach your Pebble.",
                        action = "Allow",
                        onAction = { permissionLauncher.launch(Permissions.all()) },
                    )
                }
            }
            if (state.link == LinkStatus.NO_MUG) {
                item { SetupCard(repository, enabled = Permissions.canScan(context)) }
            } else {
                item { MugCard(state, repository.writeFailed) }
                if (state.connected) item { TemperatureCard(state, repository) }
                item { WatchCard(repository) }
                item { AlertsCard(state, repository) }
                item { UnitsCard(state, repository) }
                item { BackgroundCard() }
                item { PluginCard() }
                item { ForgetCard(state, repository) }
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            if (action != null) Button(onClick = onAction) { Text(action) }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Setup

@Composable
private fun SetupCard(repository: MugRepository, enabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val found = remember { mutableStateMapOf<String, FoundMug>() }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Pair your mug", style = MaterialTheme.typography.titleLarge)
            Text(
                "Hold the button on the bottom of your mug until its light flashes blue, then tap Find. " +
                    "If the Ember app is open, close it first: the mug talks to one app at a time.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                enabled = enabled && !scanning,
                onClick = {
                    error = null
                    found.clear()
                    scanning = true
                    scope.launch {
                        withTimeoutOrNull(30_000) {
                            MugScanner(context).scan()
                                .catch { error = it.message }
                                .collect { found[it.address] = it }
                        }
                        scanning = false
                    }
                },
            ) {
                if (scanning) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Looking for mugs…")
                } else {
                    Text("Find my mug")
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            found.values.sortedByDescending { it.rssi }.forEach { mug ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            repository.pair(mug)
                            MugService.startIfReady(context)
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(mug.name, style = MaterialTheme.typography.titleMedium)
                        Text("${mug.model.displayName} · signal ${mug.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Pair", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The mug

private fun stateColor(state: AppState): Color {
    if (!state.connected) return Color(0xFF555555)
    if (state.readings.heaterOff) return Color(0xFF000055)
    return when (state.readings.liquidState) {
        LiquidState.HEATING -> Color(0xFFFF5500)
        LiquidState.COOLING -> Color(0xFF00AAFF)
        LiquidState.PERFECT -> Color(0xFF00AA00)
        LiquidState.FILLING -> Color(0xFF0055AA)
        else -> Color(0xFF000055)
    }
}

@Composable
private fun MugCard(state: AppState, writeFailed: Boolean) {
    val unit = state.displayUnit()
    val r = state.readings
    val bg = stateColor(state)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bg, contentColor = Color.White),
    ) {
        Column(Modifier.padding(20.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(state.displayName, style = MaterialTheme.typography.titleMedium)
            val label = when (state.link) {
                LinkStatus.CONNECTED -> if (r.heaterOff) "Heater off" else r.liquidState?.label ?: ""
                LinkStatus.SEARCHING -> "Looking for your mug. Pick it up to wake it."
                LinkStatus.BLUETOOTH_OFF -> "Bluetooth is off"
                LinkStatus.NEEDS_SETUP -> "Allow Bluetooth to connect"
                LinkStatus.NO_MUG -> ""
            }
            Text(label.uppercase(), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
            if (state.connected) {
                Text(
                    r.currentTempC?.let { TempFormat.format(it, unit, withUnit = false) } ?: "--",
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Bold,
                )
                val target = r.targetTempC
                if (target != null && target > 0) {
                    Text("Target ${TempFormat.format(target, unit)}", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.padding(4.dp))
                r.batteryPercent?.let { pct ->
                    LinearProgressIndicator(
                        progress = { pct / 100f },
                        modifier = Modifier.fillMaxWidth(0.6f),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.3f),
                    )
                    val detail = state.minutesLeft?.let { m ->
                        when {
                            r.charging == true && pct >= 100 -> "fully charged"
                            r.charging == true -> "charging, full in ${TempFormat.duration(m)}"
                            else -> "about ${TempFormat.duration(m)} of heat left"
                        }
                    } ?: if (r.charging == true) "charging" else "estimating…"
                    Text("$pct% · $detail", style = MaterialTheme.typography.bodyMedium)
                }
                if (writeFailed) {
                    Text(
                        "The mug didn't accept the change. Set it up once in the Ember app, close that app, and try again.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TemperatureCard(state: AppState, repository: MugRepository) {
    val unit = state.displayUnit()
    val r = state.readings
    val stepC = if (unit == TempUnit.FAHRENHEIT) 5.0 / 9.0 else 0.5
    val range = WatchProtocol.rangeTenths(unit).let { WatchProtocol.fromTenths(it.first, unit)..WatchProtocol.fromTenths(it.last, unit) }
    var pending by remember { mutableStateOf<Double?>(null) }
    val shown = pending ?: r.targetTempC?.takeIf { it > 0 } ?: state.settings.lastTargetC

    LaunchedEffect(pending) {
        val value = pending ?: return@LaunchedEffect
        delay(700)
        repository.setTarget(value)
        delay(3000)
        pending = null
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Temperature", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FilledTonalIconButton(onClick = { pending = (shown - stepC).coerceIn(range) }) { Text("−", fontSize = 24.sp) }
                Text(
                    if (r.heaterOff && pending == null) "Off" else TempFormat.format(shown, unit),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
                FilledTonalIconButton(onClick = { pending = (shown + stepC).coerceIn(range) }) {
                    Icon(Icons.Filled.Add, contentDescription = "Warmer")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PRESETS.forEach { preset ->
                    val celsius = if (unit == TempUnit.FAHRENHEIT) app.emberio.ember.fahrenheitToCelsius(preset.fahrenheit) else preset.celsius
                    FilterChip(
                        selected = !r.heaterOff && kotlin.math.abs((r.targetTempC ?: 0.0) - celsius) < 0.3,
                        onClick = { pending = null; repository.setTarget(celsius) },
                        label = { Text("${preset.name} ${TempFormat.format(celsius, unit, withUnit = false)}") },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Heater", modifier = Modifier.weight(1f))
                Switch(
                    checked = !r.heaterOff,
                    onCheckedChange = { on -> pending = null; if (on) repository.heaterOn() else repository.heaterOff() },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Pebble

@Composable
private fun WatchCard(repository: MugRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val watches by remember {
        runCatching { DefaultPebbleInfoRetriever(context.applicationContext).getConnectedWatches() }
            .getOrElse { flowOf(emptyList()) }
            .catch { emit(emptyList()) }
            .flowOn(Dispatchers.IO)
    }.collectAsStateWithLifecycle(initialValue = emptyList<ConnectedWatch>())
    val openOn by repository.bridge.openOn.collectAsState()
    val usingClassic by repository.bridge.usingClassic.collectAsState()
    var opening by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Pebble", style = MaterialTheme.typography.titleMedium)
            if (usingClassic) {
                // No PebbleKit 2 app: the original Pebble app or Gadgetbridge, over PebbleKit Classic.
                Text(
                    if (openOn.isNotEmpty()) {
                        "Emberio is open on your watch."
                    } else {
                        "Using the original Pebble app or Gadgetbridge. Install the Emberio watchapp " +
                            "(emberio.pbw) from it, then open Emberio on your watch."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = { scope.launch { repository.bridge.openOnWatch() } }) {
                    Text("Open Emberio on watch")
                }
            } else if (watches.isEmpty()) {
                Text(
                    "No watch connected. Open the Pebble app, and install the Emberio watchapp (emberio.pbw) from it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                watches.forEach { watch ->
                    val open = openOn.any { it == watch.id }
                    Text("${watch.name}${if (open) " · Emberio open" else ""}", style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedButton(
                    enabled = !opening,
                    onClick = {
                        opening = true
                        scope.launch {
                            repository.bridge.openOnWatch()
                            opening = false
                        }
                    },
                ) { Text("Open Emberio on watch") }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Settings

@Composable
private fun AlertsCard(state: AppState, repository: MugRepository) {
    val s = state.settings
    var picking by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Alerts on your watch", style = MaterialTheme.typography.titleMedium)
            Row(
                Modifier.fillMaxWidth().clickable { picking = true }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Low battery")
                    Text(
                        "Buzz when the mug's battery gets this low",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(if (s.lowBatteryAlert == 0) "Off" else "${s.lowBatteryAlert}%", color = MaterialTheme.colorScheme.primary)
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Drink ready")
                    Text("Buzz when it reaches the temperature you set", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = s.readyAlert, onCheckedChange = { on -> repository.updateSettings { it.copy(readyAlert = on) } })
            }
        }
    }
    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("Low battery alert") },
            text = {
                Column {
                    BATTERY_THRESHOLDS.forEach { value ->
                        Text(
                            if (value == 0) "Off" else "At $value%",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    repository.updateSettings { it.copy(lowBatteryAlert = value) }
                                    picking = false
                                }
                                .padding(vertical = 12.dp),
                            fontWeight = if (value == s.lowBatteryAlert) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("Close") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnitsCard(state: AppState, repository: MugRepository) {
    val options = listOf(UnitPref.MUG to "Same as mug", UnitPref.FAHRENHEIT to "°F", UnitPref.CELSIUS to "°C")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Units", style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, (pref, label) ->
                    SegmentedButton(
                        selected = state.settings.unitPref == pref,
                        onClick = { repository.updateSettings { it.copy(unitPref = pref) } },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    ) { Text(label) }
                }
            }
        }
    }
}

@SuppressLint("BatteryLife")
@Composable
private fun BackgroundCard() {
    val context = LocalContext.current
    val power = context.getSystemService(PowerManager::class.java)
    var exempt by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    if (exempt) return
    InfoCard(
        title = "Stay connected",
        body = "Some phones stop background apps to save battery, which would silence your alerts. " +
            "Let Emberio run in the background (it uses very little).",
        action = "Allow",
        onAction = {
            context.startActivity(
                Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
            )
            exempt = true
        },
    )
}

@Composable
private fun PluginCard() {
    InfoCard(
        title = "On your watchface",
        body = "The Emberio watchapp also brings a plugin: any plugin-aware watchface can show your drink's " +
            "temperature, battery and time left. In the Pebble app: Settings → Plugins → Complications, " +
            "then pick the Drink readings. The plugin reads from this app, so keep Emberio connected.",
    )
}

@Composable
private fun ForgetCard(state: AppState, repository: MugRepository) {
    val context = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        state.readings.firmware?.let {
            Text("${state.settings.mugModel.displayName} · firmware $it", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { confirm = true }) { Text("Forget this mug") }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Forget ${state.displayName}?") },
            text = { Text("Emberio will disconnect and stop sending its readings to your watch.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    repository.forget()
                    MugService.stop(context)
                }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}
