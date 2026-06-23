package com.industria.manufactura

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.launch
import android.util.Base64
import android.content.Context
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
        AppIdentifier.init(this, AppType.MANUFACTURA)
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
            ManufacturaApp(commCoordinator)
        }
    }
}

@Composable
fun ManufacturaApp(commCoordinator: CommunicationCoordinator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logs = remember { mutableStateListOf<String>() }
    val bt = GlobalBluetoothManager.getInstance()
    val connectionStates by bt.connectionStates.collectAsState()
    val isConnectedBt by remember { derivedStateOf { connectionStates.values.any { it } } }

    var isConnectedNet by remember { mutableStateOf(false) }
    var authorizationState by remember { mutableStateOf(CimProtocol.AUTH_STATE_DISCONNECTED) }
    val isAuthorized by remember { derivedStateOf { authorizationState == CimProtocol.AUTH_STATE_VALIDATED } }
    var independentMode by remember { mutableStateOf(false) }
    var ipCoordinator by remember { mutableStateOf("192.168.1.100") }
    var laserPower by remember { mutableStateOf("80") }
    var laserSpeed by remember { mutableStateOf("1200") }
    var selectedTab by remember { mutableStateOf(0) }

    fun addLog(msg: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logs.add(0, "[$time] $msg")
    }

    fun sendAuthorizedHardwareCommand(command: String, logText: String) {
        if (!isAuthorized && !independentMode) {
            addLog("✗ No autorizado - activar modo autónomo o esperar VALIDADO por coordinador")
            return
        }
        bt.send(command, requireAuthorization = !independentMode, authorized = isAuthorized)
        if (isAuthorized) {
            scope.launch {
                commCoordinator.routeCommand(AppIdentifier.getInstance().deviceMac, command)
            }
        }
        addLog(if (independentMode) "[AUTÓNOMO] $logText" else logText)
    }

    fun handleIncomingCoordinatorCommand(command: String) {
        addLog("← COORDINADOR: $command")
        when {
            command.startsWith("LASER_LOAD:") -> {
                val parts = command.split(":", limit = 3)
                if (parts.size == 3) {
                    val filename = parts[1].ifBlank { "archivo.gcode" }
                    val base64 = parts[2]
                    try {
                        val bytes = Base64.decode(base64, Base64.NO_WRAP)
                        context.openFileOutput(filename, Context.MODE_PRIVATE).use { output ->
                            output.write(bytes)
                        }
                        addLog("✓ G-code recibido: $filename (${bytes.size} bytes)")
                    } catch (e: Exception) {
                        addLog("✗ Error guardando G-code: ${e.message ?: "desconocido"}")
                    }
                } else {
                    addLog("⚠ Formato LASER_LOAD inválido")
                }
            }
            command.startsWith("GCODE_LOAD;") -> {
                val parts = command.split(";", limit = 3)
                if (parts.size == 3) {
                    val filename = parts[1].ifBlank { "archivo.gcode" }
                    val base64 = parts[2]
                    try {
                        val bytes = Base64.decode(base64, Base64.NO_WRAP)
                        context.openFileOutput(filename, Context.MODE_PRIVATE).use { output ->
                            output.write(bytes)
                        }
                        addLog("✓ G-code recibido (legacy): $filename (${bytes.size} bytes)")
                    } catch (e: Exception) {
                        addLog("✗ Error guardando G-code legacy: ${e.message ?: "desconocido"}")
                    }
                } else {
                    addLog("⚠ Formato GCODE_LOAD inválido")
                }
            }
            command.startsWith("L:") || command.startsWith("R:") || command.startsWith("M:") || command.startsWith("C:") -> {
                sendAuthorizedHardwareCommand(command, "CMD RECIBIDO: $command")
            }
            else -> {
                addLog("⚠ Comando desconocido: $command")
            }
        }
    }

    val stationClient = remember(ipCoordinator) {
        StationClient(host = ipCoordinator, port = 8888, stationName = "MANUFACTURA", password = CimProtocol.PASSWORD_ACTUAL, stationUuid = "CIM-MAN-02").apply {
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

    // File picker for external G-code loading
    val gcodeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val input = context.contentResolver.openInputStream(uri)
                    val bytes = input?.readBytes() ?: ByteArray(0)
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    // Send as an EVENT to coordinator (stationClient will handle connection)
                    val filename = uri.lastPathSegment ?: "gcode"
                    val payload = "GCODE_LOAD;$filename;$b64"
                    val sent = stationClient.sendEventSafe(payload)
                    if (sent) addLog("IMG: archivo '$filename' cargado y enviado") else addLog("IMG: fallo al enviar archivo '$filename'")
                } catch (e: Exception) {
                    addLog("IMG: error leyendo archivo: ${e.message ?: "desconocido"}")
                }
            }
        } else {
            addLog("IMG: selección de archivo cancelada")
        }
    }

    IndustrialScaffold(
        titulo = "Manufactura Pro v6.0", 
        subtitulo = "ESTACIÓN DE MECANIZADO INTEGRADA",
        floatingActionButton = { BluetoothConnectionFAB() }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ScrollableTabRow(selectedTabIndex = selectedTab, containerColor = Color.Black, contentColor = IndustrialTheme.Primario, edgePadding = 16.dp, divider = {}) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("ROBOT", fontSize = 11.sp) })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("LÁSER", fontSize = 11.sp) })
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("IMAGEN", fontSize = 11.sp) })
                Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 }, text = { Text("SINCRO", fontSize = 11.sp) })
            }

            Column(Modifier.weight(1f).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (selectedTab) {
                    0 -> {
                        IndustrialCard("Control Scorbot", Icons.Default.PrecisionManufacturing) {
                            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                                IndustrialActionButton("HOME", Icons.Default.Home, Modifier.weight(1f), enabled = isConnectedBt && (isAuthorized || independentMode), onClick = { sendAuthorizedHardwareCommand("R:HOME", "CMD: HOME") })
                                IndustrialActionButton("READY", Icons.Default.Check, Modifier.weight(1f), enabled = isConnectedBt && (isAuthorized || independentMode), onClick = { sendAuthorizedHardwareCommand("R:READY", "CMD: READY") })
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("MOVIMIENTO MANUAL (JOGGING)", color = IndustrialTheme.TextoSecundario, fontSize = 10.sp)
                            listOf("X", "Y", "Z").forEach { axisName ->
                                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(axisName, modifier = Modifier.width(24.dp).align(Alignment.CenterVertically), color = Color.White, fontWeight = FontWeight.Bold)
                                    IndustrialActionButton("-", Icons.Default.Remove, Modifier.weight(1f).height(36.dp), enabled = isConnectedBt && (isAuthorized || independentMode), onClick = { sendAuthorizedHardwareCommand("R:MOVE:$axisName:-10", "CMD: MOVE $axisName -10") })
                                    IndustrialActionButton("+", Icons.Default.Add, Modifier.weight(1f).height(36.dp), enabled = isConnectedBt && (isAuthorized || independentMode), onClick = { sendAuthorizedHardwareCommand("R:MOVE:$axisName:+10", "CMD: MOVE $axisName +10") })
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            IndustrialActionButton("GUARDAR PUNTO", Icons.Default.Save, colorFondo = IndustrialTheme.Exito, enabled = isConnectedBt && (isAuthorized || independentMode), onClick = { sendAuthorizedHardwareCommand("R:SAVE", "CMD: SAVE") })
                        }
                        ScorbotRunConsole(
                            enabled = isConnectedBt && (isAuthorized || independentMode),
                            presets = listOf("ARU" to "ARU", "ARU1" to "ARU1", "ARU2" to "ARU2", "ARU3" to "ARU3", "ARU4" to "ARU4"),
                            initialProgram = "ARU",
                            manualLabel = "Programa (ej: ARU, MYPROG)",
                            onRun = { prog -> sendAuthorizedHardwareCommand("R:RUN $prog", "RUN $prog") },
                            onAuto = { sendAuthorizedHardwareCommand("R:AUTO", "AUTO") }
                        )
                        IndustrialCard("Secuencias Predefinidas", Icons.Default.PlaylistPlay, headerColor = IndustrialTheme.Secundario) {
                            Text("Ejecuta secuencias de programas en orden (espera entre cada paso)", color = IndustrialTheme.TextoSecundario, fontSize = 10.sp)
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                                IndustrialActionButton(
                                    "SEQ: ARU > ARU2", Icons.Default.DoubleArrow, Modifier.weight(1f),
                                    enabled = isConnectedBt && (isAuthorized || independentMode),
                                    colorFondo = IndustrialTheme.Secundario,
                                    onClick = {
                                        scope.launch {
                                            listOf("ARU", "ARU2").forEach { prog ->
                                                sendAuthorizedHardwareCommand("R:RUN $prog", "SEQ: RUN $prog")
                                                kotlinx.coroutines.delay(3000)
                                            }
                                            addLog("SEQ: ARU > ARU2 completada")
                                        }
                                    }
                                )
                                IndustrialActionButton(
                                    "SEQ: ARU1>ARU3>ARU4", Icons.Default.DoubleArrow, Modifier.weight(1f),
                                    enabled = isConnectedBt && (isAuthorized || independentMode),
                                    colorFondo = IndustrialTheme.Secundario,
                                    onClick = {
                                        scope.launch {
                                            listOf("ARU1", "ARU3", "ARU4").forEach { prog ->
                                                sendAuthorizedHardwareCommand("R:RUN $prog", "SEQ: RUN $prog")
                                                kotlinx.coroutines.delay(3000)
                                            }
                                            addLog("SEQ: ARU1 > ARU3 > ARU4 completada")
                                        }
                                    }
                                )
                            }
                        }
                    }
                    1 -> {
                        IndustrialCard("Grabado Láser CNC", Icons.Default.FlashOn, headerColor = IndustrialTheme.Advertencia) {
                            IndustrialActionButton("INICIAR GRABADO", Icons.Default.PlayArrow, colorFondo = IndustrialTheme.Exito, enabled = isConnectedBt && (isAuthorized || independentMode), onClick = { sendAuthorizedHardwareCommand("L:START", "CMD: L:START") })
                            Spacer(Modifier.height(8.dp))
                            IndustrialActionButton("STOP EMERGENCIA", Icons.Default.Stop, colorFondo = IndustrialTheme.Error, enabled = isConnectedBt && (isAuthorized || independentMode), onClick = { sendAuthorizedHardwareCommand("L:STOP", "CMD: L:STOP") })
                            Spacer(Modifier.height(16.dp))
                            Text("PARÁMETROS", color = IndustrialTheme.TextoSecundario, fontSize = 10.sp)
                            IndustrialTextField(
                                valor = "$laserPower%",
                                onValueChange = { laserPower = it.filter { char -> char.isDigit() }.take(3) },
                                label = "Potencia Láser"
                            )
                            IndustrialTextField(
                                valor = laserSpeed,
                                onValueChange = { laserSpeed = it.filter { char -> char.isDigit() }.take(5) },
                                label = "Velocidad (mm/min)"
                            )
                            Spacer(Modifier.height(12.dp))
                            IndustrialActionButton(
                                texto = "APLICAR PARÁMETROS",
                                icono = Icons.Default.Settings,
                                colorFondo = IndustrialTheme.Primario,
                                enabled = isConnectedBt && (isAuthorized || independentMode),
                                onClick = {
                                    val powerValue = laserPower.toIntOrNull() ?: 80
                                    val speedValue = laserSpeed.toIntOrNull() ?: 1200
                                    sendAuthorizedHardwareCommand("L:POWER:$powerValue", "CMD: POWER $powerValue")
                                    sendAuthorizedHardwareCommand("L:SPEED:$speedValue", "CMD: SPEED $speedValue")
                                }
                            )
                        }
                    }
                    2 -> {
                        var showArucoGenerator by remember { mutableStateOf(false) }
                        var arucoGenId by remember { mutableStateOf("1") }
                        var arucoGenSize by remember { mutableStateOf("250") }
                        var generatedArucoBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
                        var isGeneratingAruco by remember { mutableStateOf(false) }

                        IndustrialCard("Procesamiento de Imagen", Icons.Default.Image) {
                            if (!showArucoGenerator) {
                                // VISTA NORMAL: CÁMARA
                                Box(Modifier.fillMaxWidth().height(150.dp).background(Color.DarkGray).border(1.dp, Color.Gray), contentAlignment = androidx.compose.ui.Alignment.Center) {
                                    Text("VISTA PREVIA G-CODE", color = Color.Gray, fontSize = 12.sp)
                                }
                                Spacer(Modifier.height(12.dp))
                                CameraPreviewWithVision(
                                    isDetecting = isConnectedBt && (isAuthorized || independentMode),
                                    onArucoFound = { results ->
                                        if (results.isNotEmpty()) {
                                            addLog("VISIÓN: Detectado ArUco #${results[0].id}")
                                            scope.launch {
                                                stationClient.sendEventSafe("ARUCO_DETECTED:${results[0].id}")
                                            }
                                        }
                                    },
                                    onQrFound = { qr ->
                                        addLog("VISIÓN: QR Detectado -> $qr")
                                    }
                                )
                                Spacer(Modifier.height(12.dp))
                                IndustrialActionButton("GENERAR ArUco PARA GRABAR", Icons.Default.AutoFixHigh, colorFondo = IndustrialTheme.Secundario, onClick = { showArucoGenerator = true })
                                Spacer(Modifier.height(8.dp))
                                IndustrialActionButton("CARGAR ARCHIVO EXTERNO", Icons.Default.Folder, onClick = {
                                    try {
                                        gcodeLauncher.launch(arrayOf("*/*"))
                                    } catch (e: Exception) {
                                        addLog("IMG: error abriendo selector de archivos: ${e.message ?: "desconocido"}")
                                    }
                                })
                            } else {
                                // VISTA GENERADOR: ArUco
                                Text("Generador de ArUco para Láser", color = IndustrialTheme.Primario, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(8.dp))
                                IndustrialTextField(
                                    valor = arucoGenId,
                                    onValueChange = { arucoGenId = it.take(2) },
                                    label = "ID Marcador (0-49)"
                                )
                                Spacer(Modifier.height(8.dp))
                                IndustrialTextField(
                                    valor = arucoGenSize,
                                    onValueChange = { arucoGenSize = it.take(4) },
                                    label = "Tamaño (PX, 200-500)"
                                )
                                Spacer(Modifier.height(12.dp))
                                IndustrialActionButton(
                                    texto = if (isGeneratingAruco) "Generando..." else "Generar Pattern",
                                    icono = Icons.Default.Autorenew,
                                    loading = isGeneratingAruco,
                                    onClick = {
                                        scope.launch {
                                            isGeneratingAruco = true
                                            try {
                                                val id = arucoGenId.toIntOrNull() ?: 1
                                                val size = arucoGenSize.toIntOrNull() ?: 250
                                                generatedArucoBitmap = IndustrialVisionAnalyzer.generateArucoMarker(id, size)
                                                if (generatedArucoBitmap != null) {
                                                    addLog("VISIÓN: ArUco #$id generado (${size}x${size}px)")
                                                }
                                            } catch (e: Exception) {
                                                addLog("ERROR: ${e.message ?: "desconocido"}")
                                            } finally {
                                                isGeneratingAruco = false
                                            }
                                        }
                                    }
                                )
                                
                                if (generatedArucoBitmap != null) {
                                    Spacer(Modifier.height(12.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(200.dp)
                                            .background(Color.White, androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                            .padding(8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Image(
                                            bitmap = generatedArucoBitmap!!.asImageBitmap(),
                                            contentDescription = "ArUco ${arucoGenId}",
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Fit
                                        )
                                    }
                                    Spacer(Modifier.height(12.dp))
                                    IndustrialActionButton(
                                        texto = "ENVIAR AL LÁSER",
                                        icono = Icons.Default.FlashOn,
                                        colorFondo = IndustrialTheme.Advertencia,
                                        onClick = {
                                            sendAuthorizedHardwareCommand(
                                                "L:ARUCO:${arucoGenId}",
                                                "LÁSER: Grabando ArUco #${arucoGenId}"
                                            )
                                            addLog("LÁSER: Enviando patrón ArUco ${arucoGenId} a grabar")
                                            showArucoGenerator = false
                                        }
                                    )
                                }
                                
                                Spacer(Modifier.height(12.dp))
                                IndustrialActionButton(
                                    texto = "Volver a Cámara",
                                    icono = Icons.Default.Close,
                                    colorFondo = IndustrialTheme.Error,
                                    onClick = { showArucoGenerator = false; generatedArucoBitmap = null }
                                )
                            }
                        }
                    }
                    3 -> {
                        IndustrialCard("Red Industrial", Icons.Default.Lan, headerColor = IndustrialTheme.Secundario) {
                            IndustrialTextField(valor = ipCoordinator, onValueChange = { ipCoordinator = it }, label = "IP Coordinador")
                            IndustrialStatusRow("Estado Red", if(isConnectedNet) "SINCRO OK" else "STANDBY", isConnectedNet)
                            IndustrialStatusRow("Autorización", authorizationState, isAuthorized)
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Modo Autónomo", color = IndustrialTheme.TextoSecundario)
                                Switch(checked = independentMode, onCheckedChange = { independentMode = it }, colors = SwitchDefaults.colors(checkedThumbColor = IndustrialTheme.Exito))
                            }
                            IndustrialStatusRow("Modo Autónomo", if(independentMode) "ACTIVO" else "DESACTIVADO", independentMode)
                            IndustrialActionButton(texto = "UNIR AL HUB", icono = Icons.Default.Router, onClick = { stationClient.connect() })
                        }
                    }
                }

                if (BuildConfig.DEBUG) {
                    IndustrialCard("Hardware debug", Icons.Default.DeveloperMode, headerColor = Color.Magenta) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IndustrialActionButton(
                                texto = "SIM ACK",
                                icono = Icons.Default.Check,
                                modifier = Modifier.weight(1f),
                                colorFondo = Color.DarkGray,
                                onClick = {
                                    scope.launch {
                                        val sent = stationClient.sendEventSafe("SIM_ACK")
                                        addLog(if (sent) "SIM_ESP32: ACK enviado" else "SIM_ESP32: ACK fallido")
                                    }
                                }
                            )
                            IndustrialActionButton(
                                texto = "SIM FINISH",
                                icono = Icons.Default.Flag,
                                modifier = Modifier.weight(1f),
                                colorFondo = Color.DarkGray,
                                onClick = {
                                    scope.launch {
                                        val sent = stationClient.sendEventSafe("SIM_FINISH")
                                        addLog(if (sent) "SIM_ESP32: FINISH enviado" else "SIM_ESP32: FINISH fallido")
                                    }
                                }
                            )
                        }
                    }
                }

                IndustrialTerminal(logs = logs, modifier = Modifier.height(200.dp))
            }
        }
    }
}
