package com.industria.calidad

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
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
        AppIdentifier.init(this, AppType.CALIDAD)
        GlobalPermissionManager.init(this)
        GlobalBluetoothManager.init(this)
        enableEdgeToEdge()
        setContent {
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
            LaunchedEffect(Unit) {
                val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.INTERNET, Manifest.permission.CAMERA)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    p.addAll(listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
                }
                launcher.launch(p.toTypedArray())
            }
            CalidadApp(commCoordinator)
        }
    }
}

@Composable
fun CalidadApp(commCoordinator: CommunicationCoordinator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel: CalidadViewModel = hiltViewModel()
    val logs = remember { mutableStateListOf<String>() }
    val bt = GlobalBluetoothManager.getInstance()
    val connectionStates by bt.connectionStates.collectAsState()
    val isConnectedBt by remember { derivedStateOf { connectionStates.values.any { it } } }

    var isConnectedNet by remember { mutableStateOf(false) }
    var authorizationState by remember { mutableStateOf(CimProtocol.AUTH_STATE_DISCONNECTED) }
    val isAuthorized by remember { derivedStateOf { authorizationState == CimProtocol.AUTH_STATE_VALIDATED } }
    var independentMode by remember { mutableStateOf(false) }
    var ipCoordinator by remember { mutableStateOf("192.168.1.100") }
    var selectedTab by remember { mutableStateOf(0) }
    var approvedCount by remember { mutableStateOf(1240) }
    var rejectedCount by remember { mutableStateOf(68) }
    var yoloModeEnabled by remember { mutableStateOf(false) }
    var expectedAruco by remember { mutableStateOf("") }
    var lastDetectedAruco by remember { mutableStateOf<Int?>(null) }
    val arucoBitmap by viewModel.arucoBitmap.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val jobStatus by viewModel.status.collectAsState()

    fun addLog(msg: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logs.add(0, "[$time] $msg")
    }

    fun sendAuthorizedHardwareCommand(command: String, logText: String, routeToCoordinator: Boolean = true) {
        if (!isAuthorized && !independentMode) {
            addLog("✗ No autorizado - activar modo autónomo o esperar VALIDADO por coordinador")
            return
        }
        bt.send(command, requireAuthorization = !independentMode, authorized = isAuthorized)
        if (routeToCoordinator && isAuthorized) {
            scope.launch {
                commCoordinator.routeCommand(AppIdentifier.getInstance().deviceMac, command)
            }
        }
        addLog(if (independentMode) "[AUTÓNOMO] $logText" else logText)
    }

    fun handleIncomingCoordinatorCommand(command: String) {
        addLog("← COORDINADOR: $command")
        when {
            command == "STATS:RESET" -> {
                approvedCount = 0
                rejectedCount = 0
                addLog("✓ Contadores reiniciados desde coordinador")
            }
            command == "CAM:YOLO" -> {
                scope.launch {
                    yoloModeEnabled = true
                    addLog("YOLO activado desde coordinador")
                    kotlinx.coroutines.delay(6000)
                    yoloModeEnabled = false
                }
                sendAuthorizedHardwareCommand(command, "CMD RECIBIDO: $command", routeToCoordinator = false)
            }
            command.startsWith("CAM:") || command.startsWith("VAL:") || command.startsWith("R:") -> {
                sendAuthorizedHardwareCommand(command, "CMD RECIBIDO: $command", routeToCoordinator = false)
            }
            else -> {
                addLog("⚠ Comando desconocido: $command")
            }
        }
    }

    val stationClient = remember(ipCoordinator) {
        StationClient(host = ipCoordinator, port = 8888, stationName = "CALIDAD", password = CimProtocol.PASSWORD_ACTUAL, stationUuid = "CIM-CAL-03").apply {
            onLog = { msg -> logs.add(0, "[NET] $msg") }
            onStatusChanged = { isConnectedNet = it }
            onAuthorizationStateChanged = { authorizationState = it }
            onCommandReceived = { cmd ->
                scope.launch {
                    handleIncomingCoordinatorCommand(cmd)
                }
            }
        }
    }

    IndustrialScaffold(
        titulo = "Quality Pro v6.0", 
        subtitulo = "CONTROL DE CALIDAD & VISIÓN",
        floatingActionButton = { BluetoothConnectionFAB() }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ScrollableTabRow(selectedTabIndex = selectedTab, containerColor = Color.Black, contentColor = IndustrialTheme.Primario, edgePadding = 16.dp, divider = {}) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("VISIÓN", fontSize = 12.sp) })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("BRAZO", fontSize = 12.sp) })
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("STATS", fontSize = 12.sp) })
                Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 }, text = { Text("SINCRO", fontSize = 12.sp) })
            }

            Column(Modifier.weight(1f).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (selectedTab) {
                    0 -> {
                        IndustrialCard("Análisis ArUco / YOLO", Icons.Default.Camera) {
                            Box(modifier = Modifier.fillMaxWidth().height(260.dp).background(Color.Black).border(1.dp, IndustrialTheme.Borde), contentAlignment = Alignment.Center) {
                                if (arucoBitmap != null) {
                                    Image(
                                        bitmap = arucoBitmap!!.asImageBitmap(),
                                        contentDescription = "ArUco generado",
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    CameraPreviewWithVision(
                                        isDetecting = isConnectedBt && (isAuthorized || independentMode),
                                        visionMode = if (yoloModeEnabled) IndustrialVisionAnalyzer.VisionMode.YOLO else IndustrialVisionAnalyzer.VisionMode.ARUCO,
                                        onArucoFound = { results ->
                                            if (results.isNotEmpty()) {
                                                val id = results[0].id
                                                if (id != lastDetectedAruco) {
                                                    lastDetectedAruco = id
                                                    addLog("VISIÓN: Detectado ArUco #$id")
                                                    scope.launch { stationClient.sendEventSafe("ARUCO_DETECTED:$id") }
                                                    val exp = expectedAruco.trim().toIntOrNull()
                                                    if (exp != null) {
                                                        if (exp == id) {
                                                            addLog("✓ PATRÓN ArUco OK (#$id coincide)")
                                                            if (independentMode) {
                                                                approvedCount += 1
                                                                sendAuthorizedHardwareCommand("VAL:PASS", "RESULT: APPROVED (ArUco $id)")
                                                            }
                                                        } else {
                                                            addLog("✗ PATRÓN ArUco NO coincide (esperado #$exp, leído #$id)")
                                                            if (independentMode) {
                                                                rejectedCount += 1
                                                                sendAuthorizedHardwareCommand("VAL:FAIL", "RESULT: REJECTED (ArUco $id)")
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                        onQrFound = { qr ->
                                            addLog("VISIÓN: QR Detectado -> $qr")
                                        },
                                        onYoloFound = { results ->
                                            if (results.isNotEmpty()) {
                                                addLog("YOLO: ${results.size} objetos detectados")
                                                results.forEach { result ->
                                                    addLog("  • ${result.label} ${"%.0f".format(result.confidence * 100)}%")
                                                }
                                            }
                                        }
                                    )
                                }
                                if (yoloModeEnabled) {
                                    Box(
                                        Modifier
                                            .fillMaxSize()
                                            .background(Color(0x66000000))
                                            .padding(12.dp),
                                        contentAlignment = Alignment.TopStart
                                    ) {
                                        Text("YOLO ACTIVO", color = IndustrialTheme.Exito, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                                color = IndustrialTheme.Exito,
                                trackColor = Color.DarkGray
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("Estado: $jobStatus", color = IndustrialTheme.TextoSecundario)
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                                IndustrialActionButton("Generar ArUco", Icons.Default.AutoAwesome, modifier = Modifier.weight(1f), enabled = true, onClick = { viewModel.generateArUco() })
                                IndustrialActionButton("Grabar", Icons.Default.PlayArrow, modifier = Modifier.weight(1f), colorFondo = IndustrialTheme.Secundario, enabled = isConnectedBt && (isAuthorized || independentMode), onClick = { viewModel.sendLaserJob() })
                            }
                            Spacer(Modifier.height(8.dp))
                            IndustrialActionButton("Capturar y Validar", Icons.Default.Camera, enabled = isConnectedBt && (isAuthorized || independentMode), onClick = { sendAuthorizedHardwareCommand("CAM:SNAP", "CMD: TRIGGER SCAN") })
                            Spacer(Modifier.height(8.dp))
                            IndustrialActionButton("Ejecutar YOLO", Icons.Default.Search, enabled = isConnectedBt && (isAuthorized || independentMode), colorFondo = IndustrialTheme.Secundario, onClick = {
                                scope.launch {
                                    yoloModeEnabled = true
                                    sendAuthorizedHardwareCommand("CAM:YOLO", "CMD: YOLO SCAN")
                                    kotlinx.coroutines.delay(6000)
                                    yoloModeEnabled = false
                                }
                            })
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                                IndustrialActionButton("PASS", Icons.Default.CheckCircle, modifier = Modifier.weight(1f), colorFondo = IndustrialTheme.Exito, enabled = isConnectedBt && (isAuthorized || independentMode), onClick = {
                                    approvedCount += 1
                                    sendAuthorizedHardwareCommand("VAL:PASS", "RESULT: APPROVED")
                                })
                                IndustrialActionButton("FAIL", Icons.Default.Cancel, modifier = Modifier.weight(1f), colorFondo = IndustrialTheme.Error, enabled = isConnectedBt && (isAuthorized || independentMode), onClick = {
                                    rejectedCount += 1
                                    sendAuthorizedHardwareCommand("VAL:FAIL", "RESULT: REJECTED")
                                    sendAuthorizedHardwareCommand("R:DISCARD", "CMD: DISCARD FAILED PIECE")
                                })
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("RECONOCIMIENTO DE PATRÓN ArUco", color = IndustrialTheme.TextoSecundario, fontSize = 10.sp)
                            IndustrialTextField(valor = expectedAruco, onValueChange = { expectedAruco = it.filter { c -> c.isDigit() }.take(2) }, label = "ArUco esperado (0-49, vacío = solo leer)")
                            IndustrialStatusRow("Último ArUco leído", lastDetectedAruco?.let { "#$it" } ?: "--", lastDetectedAruco != null)
                            val expId = expectedAruco.trim().toIntOrNull()
                            if (expId != null && lastDetectedAruco != null) {
                                IndustrialStatusRow("Coincidencia patrón", if (expId == lastDetectedAruco) "OK" else "NO COINCIDE", expId == lastDetectedAruco)
                            }
                        }
                    }
                    1 -> {
                        IndustrialCard("Control Scorbot", Icons.Default.PrecisionManufacturing) {
                            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                                IndustrialActionButton("HOME", Icons.Default.Home, Modifier.weight(1f), enabled = isConnectedBt && (isAuthorized || independentMode), onClick = { sendAuthorizedHardwareCommand("R:HOME", "CMD: HOME") })
                                IndustrialActionButton("READY", Icons.Default.Check, Modifier.weight(1f), enabled = isConnectedBt && (isAuthorized || independentMode), onClick = { sendAuthorizedHardwareCommand("R:READY", "CMD: READY") })
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("MOVIMIENTO MANUAL", color = IndustrialTheme.TextoSecundario, fontSize = 10.sp)
                            listOf("X", "Y", "Z").forEach { axis ->
                                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(axis, modifier = Modifier.width(24.dp).align(Alignment.CenterVertically), color = Color.White, fontWeight = FontWeight.Bold)
                                    IndustrialActionButton("$axis-", Icons.Default.Remove, Modifier.weight(1f).height(44.dp), enabled = isConnectedBt && (isAuthorized || independentMode), onClick = { sendAuthorizedHardwareCommand("R:MOVE:$axis:-10", "CMD: MOVE $axis -10") })
                                    IndustrialActionButton("$axis+", Icons.Default.Add, Modifier.weight(1f).height(44.dp), enabled = isConnectedBt && (isAuthorized || independentMode), onClick = { sendAuthorizedHardwareCommand("R:MOVE:$axis:+10", "CMD: MOVE $axis +10") })
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            IndustrialActionButton("DESCARTAR PIEZA", Icons.Default.DeleteForever, colorFondo = IndustrialTheme.Error, enabled = isConnectedBt && (isAuthorized || independentMode), onClick = {
                                sendAuthorizedHardwareCommand("R:DISCARD", "CMD: DISCARD FAILED PIECE")
                            })
                        }
                    }
                    2 -> {
                        IndustrialCard("Estadísticas de Producción", Icons.Default.BarChart) {
                            val totalPieces = approvedCount + rejectedCount
                            val approvalRate = if (totalPieces > 0) (approvedCount * 100.0 / totalPieces) else 0.0
                            Text("Tasa de Aprobación: ${"%.1f".format(approvalRate)}%", color = IndustrialTheme.Exito, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text("Piezas Totales: $totalPieces", color = Color.White)
                            Text("Piezas Aprobadas: $approvedCount", color = IndustrialTheme.Exito)
                            Text("Piezas Rechazadas: $rejectedCount", color = IndustrialTheme.Error)
                            Spacer(Modifier.height(16.dp))
                            IndustrialActionButton("Limpiar Contador", Icons.Default.Delete, colorFondo = Color.DarkGray, onClick = {
                                approvedCount = 0
                                rejectedCount = 0
                                addLog("STATS: contadores reiniciados")
                                sendAuthorizedHardwareCommand("STATS:RESET", "CMD: STATS_RESET")
                            })
                        }
                    }
                    3 -> {
                        IndustrialCard("Enlace al Hub Maestro", Icons.Default.Lan, headerColor = IndustrialTheme.Secundario) {
                            IndustrialTextField(valor = ipCoordinator, onValueChange = { ipCoordinator = it }, label = "IP Coordinador")
                            IndustrialStatusRow("Conexión HUB", if(isConnectedNet) "SINCRONIZADO" else "STANDBY", isConnectedNet)
                            IndustrialStatusRow("Autorización", authorizationState, isAuthorized)
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Modo Autónomo", color = IndustrialTheme.TextoSecundario)
                                Switch(checked = independentMode, onCheckedChange = { independentMode = it }, colors = SwitchDefaults.colors(checkedThumbColor = IndustrialTheme.Exito))
                            }
                            IndustrialStatusRow("Modo Autónomo", if(independentMode) "ACTIVO" else "DESACTIVADO", independentMode)
                            IndustrialActionButton(texto = if(isConnectedNet) "OPERATIVO" else "VINCULAR", icono = Icons.Default.Router, colorFondo = if(isConnectedNet) IndustrialTheme.Exito else IndustrialTheme.Primario, onClick = { stationClient.connect() })
                        }
                    }
                }

                if (BuildConfig.DEBUG) {
                    IndustrialCard("Debug de Calidad", Icons.Default.DeveloperMode, headerColor = Color.Magenta) {
                        IndustrialActionButton(texto = "Simular Captura Exitosa", icono = Icons.Default.PhotoCamera, colorFondo = Color.DarkGray, onClick = { addLog("SIM_ESP32: CAM_SNAP_OK | DATA SENT") })
                    }
                }

                IndustrialTerminal(logs = logs, modifier = Modifier.height(180.dp))
            }
        }
    }
}
