package com.industria.almacenamiento

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sistema.distribuido.network.*
import com.sistema.distribuido.network.prefecto.*
import com.sistema.distribuido.network.protocol.AppType
import com.sistema.distribuido.network.protocol.CimProtocol
import dagger.hilt.android.AndroidEntryPoint
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var commCoordinator: CommunicationCoordinator
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        IndustrialErrorManager.install(this) {}
        AppIdentifier.init(this, AppType.ALMACEN)
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
            AlmacenApp(commCoordinator)
        }
    }
}

@Composable
fun AlmacenApp(commCoordinator: CommunicationCoordinator) {
    val context = LocalContext.current
    val logs = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()
    val bt = GlobalBluetoothManager.getInstance()
    val connectionStates by bt.connectionStates.collectAsState()
    val isConnectedBt by remember { derivedStateOf { connectionStates.values.any { it } } }

    var isConnectedNet by remember { mutableStateOf(false) }
    var authorizationState by remember { mutableStateOf(CimProtocol.AUTH_STATE_DISCONNECTED) }
    val isAuthorized by remember { derivedStateOf { authorizationState == CimProtocol.AUTH_STATE_VALIDATED } }
    var independentMode by remember { mutableStateOf(false) }
    var ipCoordinator by remember { mutableStateOf("192.168.1.100") }
    var selectedTab by remember { mutableStateOf(0) }
    var selectedRackPosition by remember { mutableStateOf(1) }

    // Rack occupancy: position (1-18) -> occupied
    val rackOccupancy = remember { mutableStateMapOf<Int, Boolean>() }
    var totalStored by remember { mutableStateOf(0) }
    var totalRetrieved by remember { mutableStateOf(0) }

    val isActive by remember { derivedStateOf { isConnectedBt && (isAuthorized || independentMode) } }

    fun addLog(msg: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logs.add(0, "[$time] $msg")
        if (logs.size > 200) logs.removeAt(logs.lastIndex)
    }

    val stationClient = remember(ipCoordinator) {
        StationClient(host = ipCoordinator, port = 8888, stationName = "ALMACEN", password = CimProtocol.PASSWORD_ACTUAL, stationUuid = "CIM-ALM-01").apply {
            onLog = { msg -> logs.add(0, "[NET] $msg") }
            onStatusChanged = { isConnectedNet = it }
            onAuthorizationStateChanged = { authorizationState = it }
            onCommandReceived = { cmd ->
                scope.launch {
                    addLog("CMD recibido: $cmd")
                    when {
                        cmd.startsWith("STO:") -> {
                            val pos = cmd.substringAfter("STO:").trim().toIntOrNull()
                            if (pos != null && pos in 1..18) {
                                rackOccupancy[pos] = true
                                totalStored++
                                addLog("Almacenado en POS $pos (remoto)")
                            }
                        }
                        cmd.startsWith("PICK:") -> {
                            val pos = cmd.substringAfter("PICK:").trim().toIntOrNull()
                            if (pos != null && pos in 1..18) {
                                rackOccupancy[pos] = false
                                totalRetrieved++
                                addLog("Retirado de POS $pos (remoto)")
                            }
                        }
                    }
                }
            }
        }
    }

    fun sendAuthorizedHardwareCommand(command: String, logText: String) {
        if (!isAuthorized && !independentMode) {
            addLog("No autorizado - activar modo autonomo o esperar VALIDADO")
            return
        }
        bt.send(command, requireAuthorization = !independentMode, authorized = isAuthorized)
        if (isAuthorized) {
            scope.launch {
                commCoordinator.routeCommand(AppIdentifier.getInstance().deviceMac, command)
            }
        }
        addLog(if (independentMode) "[AUTONOMO] $logText" else logText)
    }

    fun storeAtPosition(pos: Int) {
        sendAuthorizedHardwareCommand("STO:$pos", "CMD: STORE AT POS $pos")
        rackOccupancy[pos] = true
        totalStored++
        if (isConnectedNet && isAuthorized) {
            scope.launch { stationClient.sendEventSafe("STORED:$pos") }
        }
    }

    fun pickFromPosition(pos: Int) {
        sendAuthorizedHardwareCommand("PICK:$pos", "CMD: PICK FROM POS $pos")
        rackOccupancy[pos] = false
        totalRetrieved++
        if (isConnectedNet && isAuthorized) {
            scope.launch { stationClient.sendEventSafe("PICKED:$pos") }
        }
    }

    IndustrialScaffold(
        titulo = "Logistica Pro v6.0",
        subtitulo = "GESTION DE RACKS INDUSTRIAL",
        floatingActionButton = { BluetoothConnectionFAB() }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ScrollableTabRow(selectedTabIndex = selectedTab, containerColor = Color.Black, contentColor = IndustrialTheme.Primario, edgePadding = 16.dp, divider = {}) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("POSICIONES", fontSize = 12.sp) })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("BRAZO", fontSize = 12.sp) })
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("STATS", fontSize = 12.sp) })
                Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 }, text = { Text("SINCRO", fontSize = 12.sp) })
            }

            Column(Modifier.weight(1f).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (selectedTab) {
                    0 -> {
                        IndustrialCard("Matriz de Almacen (18 POS)", Icons.Default.Inventory2) {
                            IndustrialStatusRow("Conexion ESP32", if (isConnectedBt) "LINK OK" else "OFFLINE", isConnectedBt)
                            val occupiedCount = rackOccupancy.count { it.value }
                            IndustrialStatusRow("Ocupacion", "$occupiedCount / 18 posiciones", occupiedCount > 0)
                            Text("Selecciona posicion. Verde = ocupado, gris = libre.", color = IndustrialTheme.TextoSecundario, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))

                            repeat(3) { level ->
                                Text("NIVEL ${level + 1}", color = IndustrialTheme.TextoSecundario, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    repeat(6) { col ->
                                        val posId = level * 6 + col + 1
                                        val occupied = rackOccupancy[posId] == true
                                        val selected = selectedRackPosition == posId
                                        IndustrialActionButton(
                                            texto = "$posId",
                                            icono = if (occupied) Icons.Default.CheckCircle else Icons.Default.Inventory2,
                                            modifier = Modifier.weight(1f).height(36.dp),
                                            colorFondo = when {
                                                selected -> IndustrialTheme.Primario
                                                occupied -> IndustrialTheme.Exito.copy(alpha = 0.6f)
                                                else -> IndustrialTheme.Tarjeta
                                            },
                                            enabled = true,
                                            buttonHeight = 36.dp,
                                            fillMaxWidth = false,
                                            onClick = {
                                                selectedRackPosition = posId
                                                addLog("POSICION SELECCIONADA: $posId ${if (occupied) "(ocupada)" else "(libre)"}")
                                            }
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(12.dp))
                            val selOccupied = rackOccupancy[selectedRackPosition] == true
                            IndustrialStatusRow("POS $selectedRackPosition", if (selOccupied) "OCUPADA" else "LIBRE", selOccupied)

                            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                                IndustrialActionButton(
                                    texto = "ALMACENAR",
                                    icono = Icons.Default.Archive,
                                    modifier = Modifier.weight(1f),
                                    colorFondo = IndustrialTheme.Exito,
                                    enabled = isActive && !selOccupied,
                                    onClick = { storeAtPosition(selectedRackPosition) }
                                )
                                IndustrialActionButton(
                                    texto = "RETIRAR",
                                    icono = Icons.Default.Unarchive,
                                    modifier = Modifier.weight(1f),
                                    colorFondo = IndustrialTheme.Advertencia,
                                    enabled = isActive && selOccupied,
                                    onClick = { pickFromPosition(selectedRackPosition) }
                                )
                            }
                        }
                    }
                    1 -> {
                        IndustrialCard("Control Scorbot", Icons.Default.PrecisionManufacturing) {
                            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                                IndustrialActionButton("HOME", Icons.Default.Home, Modifier.weight(1f), enabled = isActive, onClick = { sendAuthorizedHardwareCommand("R:HOME", "CMD: HOME") })
                                IndustrialActionButton("READY", Icons.Default.Check, Modifier.weight(1f), enabled = isActive, onClick = { sendAuthorizedHardwareCommand("R:READY", "CMD: READY") })
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("MOVIMIENTO MANUAL", color = IndustrialTheme.TextoSecundario, fontSize = 10.sp)
                            listOf("X", "Y", "Z").forEach { axis ->
                                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(axis, modifier = Modifier.width(24.dp).align(Alignment.CenterVertically), color = Color.White, fontWeight = FontWeight.Bold)
                                    IndustrialActionButton("$axis-", Icons.Default.Remove, Modifier.weight(1f).height(40.dp), enabled = isActive, onClick = { sendAuthorizedHardwareCommand("R:MOVE:$axis:-10", "MOVE $axis -10") })
                                    IndustrialActionButton("$axis+", Icons.Default.Add, Modifier.weight(1f).height(40.dp), enabled = isActive, onClick = { sendAuthorizedHardwareCommand("R:MOVE:$axis:+10", "MOVE $axis +10") })
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            IndustrialActionButton("DESCARTAR PIEZA", Icons.Default.DeleteForever, colorFondo = IndustrialTheme.Error, enabled = isActive, onClick = { sendAuthorizedHardwareCommand("R:DISCARD", "CMD: DISCARD") })
                        }
                        ScorbotRunConsole(
                            enabled = isActive,
                            presets = listOf("ALMACENAR" to "STORE", "RETIRAR" to "PICK", "HOME" to "HOME"),
                            initialProgram = "STORE",
                            descripcion = "Ejecuta rutinas de almacenamiento en el controlador (estilo hyperterminal)",
                            manualLabel = "Programa (ej: STORE, PICK, HOME)",
                            onRun = { prog -> sendAuthorizedHardwareCommand("R:RUN $prog", "RUN $prog") },
                            onAuto = { sendAuthorizedHardwareCommand("R:AUTO", "AUTO") }
                        )
                    }
                    2 -> {
                        IndustrialCard("Estadisticas de Almacen", Icons.Default.BarChart) {
                            val occupiedCount = rackOccupancy.count { it.value }
                            val freeCount = 18 - occupiedCount
                            val occupancyRate = if (18 > 0) (occupiedCount * 100.0 / 18) else 0.0
                            Text("Tasa de Ocupacion: ${"%.1f".format(occupancyRate)}%", color = IndustrialTheme.Primario, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            IndustrialStatusRow("Posiciones Ocupadas", "$occupiedCount", occupiedCount > 0)
                            IndustrialStatusRow("Posiciones Libres", "$freeCount", freeCount > 0)
                            IndustrialStatusRow("Total Almacenados", "$totalStored", totalStored > 0)
                            IndustrialStatusRow("Total Retirados", "$totalRetrieved", totalRetrieved > 0)
                            Spacer(Modifier.height(16.dp))
                            IndustrialActionButton("Limpiar Contadores", Icons.Default.Delete, colorFondo = Color.DarkGray, onClick = {
                                totalStored = 0
                                totalRetrieved = 0
                                addLog("STATS: contadores reiniciados")
                            })
                            Spacer(Modifier.height(8.dp))
                            IndustrialActionButton("Reset Rack (vaciar todo)", Icons.Default.LayersClear, colorFondo = IndustrialTheme.Error, onClick = {
                                rackOccupancy.clear()
                                addLog("RACK: todas las posiciones vaciadas (solo UI)")
                            })
                        }
                    }
                    3 -> {
                        IndustrialCard("Red de Coordinacion", Icons.Default.Lan, headerColor = IndustrialTheme.Secundario) {
                            IndustrialTextField(valor = ipCoordinator, onValueChange = { ipCoordinator = it }, label = "IP Hub Central")
                            IndustrialStatusRow("Servicio Hub", if (isConnectedNet) "ACTIVO" else "DOWN", isConnectedNet)
                            IndustrialStatusRow("Autorizacion", authorizationState, isAuthorized)
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Modo Autonomo", color = IndustrialTheme.TextoSecundario)
                                Switch(checked = independentMode, onCheckedChange = { independentMode = it }, colors = SwitchDefaults.colors(checkedThumbColor = IndustrialTheme.Exito))
                            }
                            IndustrialStatusRow("Modo Autonomo", if (independentMode) "ACTIVO" else "DESACTIVADO", independentMode)
                            IndustrialActionButton(texto = "Sincronizar", icono = Icons.Default.Router, onClick = { stationClient.connect() })
                        }
                    }
                }

                IndustrialTerminal(logs = logs, modifier = Modifier.height(180.dp))
            }
        }
    }
}
