package com.industria.plc

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sistema.distribuido.network.AppIdentifier
import com.sistema.distribuido.network.CommunicationCoordinator
import com.sistema.distribuido.network.GlobalBluetoothManager
import com.sistema.distribuido.network.GlobalPermissionManager
import com.sistema.distribuido.network.StationClient
import com.sistema.distribuido.network.protocol.CimProtocol
import com.sistema.distribuido.network.protocol.AppType
import com.sistema.distribuido.network.prefecto.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var commCoordinator: CommunicationCoordinator
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppIdentifier.init(this, AppType.PLC)
        GlobalPermissionManager.init(this)
        GlobalBluetoothManager.init(this)
        enableEdgeToEdge()
        setContent {
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
            LaunchedEffect(Unit) {
                val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.INTERNET)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    p.addAll(listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
                }
                launcher.launch(p.toTypedArray())
            }
            PLCApp(commCoordinator)
        }
    }
}

@Composable
fun PLCApp(commCoordinator: CommunicationCoordinator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logs = remember { mutableStateListOf<String>() }
    var isConnectedNet by remember { mutableStateOf(false) }
    var authorizationState by remember { mutableStateOf(CimProtocol.AUTH_STATE_DISCONNECTED) }
    val isAuthorized by remember { derivedStateOf { authorizationState == CimProtocol.AUTH_STATE_VALIDATED } }
    var independentMode by remember { mutableStateOf(false) }
    var ipCoordinator by remember { mutableStateOf("192.168.1.100") }
    var selectedTab by remember { mutableStateOf(0) }

    // Station state: station -> (hasPallet, palletId?)
    val stationPresent = remember { mutableStateMapOf<Int, Boolean>() }
    val stationPalletId = remember { mutableStateMapOf<Int, Int?>() }
    val holdStations = remember { mutableStateMapOf<Int, Boolean>() }
    var lastTrackingEvent by remember { mutableStateOf("--") }
    var monitorStation by remember { mutableStateOf<Int?>(null) }

    // Pass event history
    val passHistory = remember { mutableStateListOf<String>() }

    val bluetoothManager = GlobalBluetoothManager.getInstance()
    val connectionStates by bluetoothManager.connectionStates.collectAsState()
    val isConnectedBt by remember { derivedStateOf { connectionStates.values.any { it } } }

    val isActive by remember { derivedStateOf { isConnectedBt && (isAuthorized || independentMode) } }

    val stationClient = remember(ipCoordinator) {
        StationClient(
            host = ipCoordinator,
            port = 8888,
            stationName = "PLC",
            password = CimProtocol.PASSWORD_ACTUAL,
            stationUuid = "CIM-PLC-04",
            macAddress = "CIM-PLC-04"
        ).apply {
            onLog = { msg -> logs.add(0, "[NET] $msg") }
            onStatusChanged = { isConnectedNet = it }
            onAuthorizationStateChanged = { authorizationState = it }
        }
    }

    fun addLog(msg: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logs.add(0, "[$time] $msg")
        if (logs.size > 200) logs.removeAt(logs.lastIndex)
    }

    fun sendPlcHardwareCommand(command: String, logText: String) {
        if (!isAuthorized && !independentMode) {
            addLog("No autorizado - activar modo autonomo o esperar VALIDADO")
            return
        }
        bluetoothManager.send(command, requireAuthorization = !independentMode, authorized = isAuthorized)
        if (isAuthorized) {
            scope.launch {
                commCoordinator.routeCommand(AppIdentifier.getInstance().deviceMac, command)
            }
        }
        addLog(if (independentMode) "[AUTONOMO] $logText" else logText)
    }

    fun setStationState(station: Int, present: Boolean, palletId: Int? = null) {
        stationPresent[station] = present
        stationPalletId[station] = if (present) palletId else null
    }

    fun sendDeliver(station: Int, pallet: Int) {
        val cmd = PlcProtocol.deliverCommand(station, pallet)
        if (cmd == null) {
            addLog("Comando DELIVER no encontrado para E$station P$pallet")
            return
        }
        setStationState(station, true, pallet)
        addLog("Pallet $pallet llego a estacion $station")
        val time = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        passHistory.add(0, "[$time] DELIVER E$station P$pallet")

        sendPlcHardwareCommand("C:DELIVER|$station|$pallet", "DELIVER $station -> $pallet")
        if (isConnectedNet && isAuthorized) {
            scope.launch {
                stationClient.sendSafe("PLC:DELIVER|$station|$pallet")
            }
        }
    }

    fun sendFree(station: Int, pallet: Int) {
        val cmds = PlcProtocol.freeCommands(station, pallet)
        if (cmds == null) {
            addLog("Comando FREE invalido para E$station P$pallet")
            return
        }
        setStationState(station, false)
        addLog("Pallet $pallet liberado de estacion $station")
        val time = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        passHistory.add(0, "[$time] FREE E$station P$pallet")

        sendPlcHardwareCommand("C:FREE|$station|$pallet", "FREE E$station P$pallet")
        if (isConnectedNet && isAuthorized) {
            scope.launch {
                stationClient.sendSafe("PLC:FREE|$station|$pallet")
            }
        }
    }

    fun handlePlcResponse(raw: String) {
        val events = PlcProtocol.parseExResponses(raw)
        for (ev in events) {
            addLog("PLC EX: station=${ev.station} pallet=${ev.pallet}")
            setStationState(ev.station, true, ev.pallet)
            lastTrackingEvent = "Pallet ${ev.pallet} en estacion ${ev.station}"
            val time = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            passHistory.add(0, "[$time] DETECT E${ev.station} P${ev.pallet}")
            if (holdStations[ev.station] == true) {
                sendPlcHardwareCommand("C:STOP|${ev.station}", "PALLET DETENIDO en estacion ${ev.station}")
            }
            if (monitorStation == ev.station) {
                addLog("MONITOR: pallet ${ev.pallet} detectado en estacion monitoreada ${ev.station}")
            }
        }
    }

    fun handlePlcEvent(raw: String) {
        val cmd = raw.trim()
        val pos = Regex("POS:(\\d+)").find(cmd)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return
        when {
            cmd.startsWith("SENSOR_ACTIVATED") -> {
                stationPresent[pos] = true
                lastTrackingEvent = "Pallet detectado en estacion $pos"
                if (holdStations[pos] == true) {
                    sendPlcHardwareCommand("C:STOP|$pos", "PALLET DETENIDO en estacion $pos")
                } else {
                    addLog("TRACKING: pallet pasa por estacion $pos")
                }
            }
            cmd.startsWith("PALLET_CLEARED") -> {
                stationPresent[pos] = false
                stationPalletId[pos] = null
                lastTrackingEvent = "Estacion $pos liberada"
                addLog("TRACKING: estacion $pos liberada")
            }
        }
    }

    LaunchedEffect(stationClient) {
        stationClient.onCommandReceived = { cmd ->
            scope.launch {
                if (cmd.contains("EX")) handlePlcResponse(cmd)
                else handlePlcEvent(cmd)
            }
        }
    }

    val manager = remember { PlcStationManager(context) }

    IndustrialScaffold(
        titulo = "PLC Master v6.0",
        subtitulo = "CONTROL DE CINTA TRANSPORTADORA",
        floatingActionButton = { BluetoothConnectionFAB() }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Black,
                contentColor = IndustrialTheme.Primario,
                edgePadding = 16.dp,
                divider = {}
            ) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("CINTA", fontSize = 12.sp) })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("TRACKING", fontSize = 12.sp) })
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("HISTORIAL", fontSize = 12.sp) })
                Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 }, text = { Text("SINCRO", fontSize = 12.sp) })
            }

            Column(
                Modifier.weight(1f).padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                when (selectedTab) {
                    0 -> CintaTab(
                        isActive = isActive,
                        stationPresent = stationPresent,
                        stationPalletId = stationPalletId,
                        monitorStation = monitorStation,
                        independentMode = independentMode,
                        onIndependentModeChange = { independentMode = it },
                        onDeliver = { s, p -> sendDeliver(s, p) },
                        onFree = { s, p -> sendFree(s, p) },
                        onResetCinta = {
                            PlcProtocol.STATIONS.forEach { setStationState(it, false) }
                            addLog("Cinta UI reseteada")
                        },
                        onMonitorToggle = { station ->
                            monitorStation = if (monitorStation == station) null else station
                            addLog(if (monitorStation == station) "Monitor ON para estacion $station" else "Monitor OFF")
                        },
                        onStart = { sendPlcHardwareCommand("PLC:START", "PLC: START") },
                        onStop = { sendPlcHardwareCommand("PLC:STOP", "PLC: STOP") },
                        addLog = { addLog(it) }
                    )
                    1 -> TrackingTab(
                        isActive = isActive,
                        stationPresent = stationPresent,
                        holdStations = holdStations,
                        lastTrackingEvent = lastTrackingEvent,
                        onStop = { pos -> sendPlcHardwareCommand("C:STOP|$pos", "PALLET DETENIDO en estacion $pos") },
                        onFreeStation = { pos ->
                            stationPresent[pos] = false
                            stationPalletId[pos] = null
                            sendPlcHardwareCommand("C:FREE|$pos", "Estacion $pos liberada")
                        },
                        onSimulate = { pos -> handlePlcEvent("SENSOR_ACTIVATED|POS:$pos") },
                        addLog = { addLog(it) }
                    )
                    2 -> HistorialTab(passHistory = passHistory)
                    3 -> SincroTab(
                        ipCoordinator = ipCoordinator,
                        onIpChange = { ipCoordinator = it },
                        isConnectedNet = isConnectedNet,
                        authorizationState = authorizationState,
                        isAuthorized = isAuthorized,
                        onConnect = { stationClient.connect() }
                    )
                }

                IndustrialTerminal(logs = logs, modifier = Modifier.height(180.dp))
            }
        }
    }
}

// ─── Tab: Cinta ───────────────────────────────────────────

@Composable
private fun CintaTab(
    isActive: Boolean,
    stationPresent: Map<Int, Boolean>,
    stationPalletId: Map<Int, Int?>,
    monitorStation: Int?,
    independentMode: Boolean,
    onIndependentModeChange: (Boolean) -> Unit,
    onDeliver: (Int, Int) -> Unit,
    onFree: (Int, Int) -> Unit,
    onResetCinta: () -> Unit,
    onMonitorToggle: (Int) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    addLog: (String) -> Unit
) {
    IndustrialCard("Energia y Sistema", Icons.Default.PowerSettingsNew) {
        IndustrialStatusRow("Estado Operativo", if (isActive) "SISTEMA VINCULADO" else "STANDBY", isActive)
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Modo Autonomo", color = IndustrialTheme.TextoSecundario)
            Switch(
                checked = independentMode,
                onCheckedChange = onIndependentModeChange,
                colors = SwitchDefaults.colors(checkedThumbColor = IndustrialTheme.Exito)
            )
        }
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp)) {
            IndustrialActionButton("Arranque", Icons.Default.PlayArrow, Modifier.weight(1f), colorFondo = IndustrialTheme.Exito, enabled = isActive, onClick = onStart)
            IndustrialActionButton("Parada", Icons.Default.Stop, Modifier.weight(1f), colorFondo = IndustrialTheme.Error, enabled = isActive, onClick = onStop)
        }
    }

    // Station visualization
    IndustrialCard("Estaciones de Cinta", Icons.Default.Sensors) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
            PlcProtocol.STATIONS.forEach { station ->
                val present = stationPresent[station] == true
                val palletId = stationPalletId[station]
                val isMonitored = monitorStation == station
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable { onMonitorToggle(station) }
                        .padding(8.dp)
                ) {
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(if (present) IndustrialTheme.Exito else IndustrialTheme.Error)
                            .then(
                                if (isMonitored) Modifier.border(3.dp, IndustrialTheme.Primario, CircleShape)
                                else Modifier
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (palletId != null) {
                            Text("P$palletId", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("EST $station", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    if (isMonitored) {
                        Text("MONITOR", color = IndustrialTheme.Primario, fontSize = 9.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        IndustrialActionButton("Reset Cinta", Icons.Default.Refresh, colorFondo = Color.DarkGray, onClick = onResetCinta)
    }

    // DELIVER grid (3 estaciones x 5 pallets)
    IndustrialCard("DELIVER (Est x Pallet)", Icons.Default.GridView) {
        Text("Envia pallet a estacion", color = IndustrialTheme.TextoSecundario, fontSize = 10.sp)
        Spacer(Modifier.height(8.dp))
        // Header
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(2.dp)) {
            Text("E\\P", Modifier.width(32.dp), color = IndustrialTheme.TextoSecundario, fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            PlcProtocol.PALLETS.forEach { p ->
                Text("$p", Modifier.weight(1f), color = IndustrialTheme.TextoSecundario, fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
        }
        PlcProtocol.STATIONS.forEach { station ->
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$station", Modifier.width(32.dp), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                PlcProtocol.PALLETS.forEach { pallet ->
                    IndustrialActionButton(
                        texto = "$station>$pallet",
                        icono = Icons.Default.Send,
                        modifier = Modifier.weight(1f).height(34.dp),
                        colorFondo = if (isActive) IndustrialTheme.Primario.copy(alpha = 0.3f) else IndustrialTheme.Tarjeta,
                        enabled = isActive,
                        buttonHeight = 34.dp,
                        fillMaxWidth = false,
                        onClick = { onDeliver(station, pallet) }
                    )
                }
            }
        }
    }

    // FREE grid (3 estaciones x 5 pallets)
    IndustrialCard("FREE (Est x Pallet)", Icons.Default.LockOpen, headerColor = IndustrialTheme.Advertencia) {
        Text("Libera pallet de estacion", color = IndustrialTheme.TextoSecundario, fontSize = 10.sp)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(2.dp)) {
            Text("E\\P", Modifier.width(32.dp), color = IndustrialTheme.TextoSecundario, fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            PlcProtocol.PALLETS.forEach { p ->
                Text("$p", Modifier.weight(1f), color = IndustrialTheme.TextoSecundario, fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
        }
        PlcProtocol.STATIONS.forEach { station ->
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$station", Modifier.width(32.dp), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                PlcProtocol.PALLETS.forEach { pallet ->
                    IndustrialActionButton(
                        texto = "F$station>$pallet",
                        icono = Icons.Default.LockOpen,
                        modifier = Modifier.weight(1f).height(34.dp),
                        colorFondo = if (isActive) IndustrialTheme.Advertencia.copy(alpha = 0.3f) else IndustrialTheme.Tarjeta,
                        enabled = isActive,
                        buttonHeight = 34.dp,
                        fillMaxWidth = false,
                        onClick = { onFree(station, pallet) }
                    )
                }
            }
        }
    }
}

// ─── Tab: Tracking ────────────────────────────────────────

@Composable
private fun TrackingTab(
    isActive: Boolean,
    stationPresent: Map<Int, Boolean>,
    holdStations: MutableMap<Int, Boolean>,
    lastTrackingEvent: String,
    onStop: (Int) -> Unit,
    onFreeStation: (Int) -> Unit,
    onSimulate: (Int) -> Unit,
    addLog: (String) -> Unit
) {
    val trackingStations = listOf("ALMACEN" to 1, "MANUFACTURA" to 2, "CALIDAD" to 3, "PLC/SALIDA" to 4)

    IndustrialCard("Tracking de Pallets", Icons.Default.Sensors, headerColor = IndustrialTheme.Secundario) {
        IndustrialStatusRow("Ultimo evento", lastTrackingEvent, true)
        Text("Activa 'Detener' para frenar el pallet cuando pase por la estacion", color = IndustrialTheme.TextoSecundario, fontSize = 10.sp)
        Spacer(Modifier.height(8.dp))
        trackingStations.forEach { (name, pos) ->
            val present = stationPresent[pos] == true
            val hold = holdStations[pos] == true
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("$pos - $name", color = Color.White, fontWeight = FontWeight.Bold)
                    Text(
                        if (present) "PALLET" else "vacio",
                        color = if (present) IndustrialTheme.Exito else IndustrialTheme.TextoSecundario,
                        fontSize = 12.sp
                    )
                }
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text("Detener", color = IndustrialTheme.TextoSecundario, fontSize = 11.sp)
                        Switch(
                            checked = hold,
                            onCheckedChange = {
                                holdStations[pos] = it
                                addLog("TRACKING: estacion $pos ${if (it) "se detendra" else "paso libre"}")
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = IndustrialTheme.Advertencia)
                        )
                    }
                    IndustrialActionButton("STOP", Icons.Default.Stop, Modifier.weight(1f), colorFondo = IndustrialTheme.Error, enabled = isActive, onClick = { onStop(pos) })
                    IndustrialActionButton("Liberar", Icons.Default.PlayArrow, Modifier.weight(1f), colorFondo = IndustrialTheme.Exito, enabled = isActive, onClick = { onFreeStation(pos) })
                }
            }
            HorizontalDivider(color = IndustrialTheme.Borde)
        }
    }

    IndustrialCard("Simulador de Pallet", Icons.Default.Science, headerColor = Color.Magenta) {
        Text("Simula el paso de un pallet por una estacion (pruebas sin hardware)", color = IndustrialTheme.TextoSecundario, fontSize = 10.sp)
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
            trackingStations.forEach { (_, pos) ->
                IndustrialActionButton("POS $pos", Icons.Default.Sensors, Modifier.weight(1f), onClick = { onSimulate(pos) })
            }
        }
    }
}

// ─── Tab: Historial ───────────────────────────────────────

@Composable
private fun HistorialTab(passHistory: List<String>) {
    IndustrialCard("Historial de Eventos", Icons.Default.History, headerColor = IndustrialTheme.Secundario) {
        if (passHistory.isEmpty()) {
            Text("Sin eventos registrados", color = IndustrialTheme.TextoSecundario)
        } else {
            passHistory.take(50).forEach { entry ->
                Text(entry, color = IndustrialTheme.Exito.copy(alpha = 0.9f), fontSize = 11.sp, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

// ─── Tab: Sincro ──────────────────────────────────────────

@Composable
private fun SincroTab(
    ipCoordinator: String,
    onIpChange: (String) -> Unit,
    isConnectedNet: Boolean,
    authorizationState: String,
    isAuthorized: Boolean,
    onConnect: () -> Unit
) {
    IndustrialCard("Red Industrial", Icons.Default.Lan, headerColor = IndustrialTheme.Secundario) {
        IndustrialTextField(valor = ipCoordinator, onValueChange = onIpChange, label = "IP Coordinador")
        IndustrialStatusRow("Enlace de Datos", if (isConnectedNet) "SINCRO OK" else "OFFLINE", isConnectedNet)
        IndustrialStatusRow("Autorizacion", authorizationState, isAuthorized)
        IndustrialActionButton(texto = "Vincular al Hub", icono = Icons.Default.Router, onClick = onConnect)
    }
}
