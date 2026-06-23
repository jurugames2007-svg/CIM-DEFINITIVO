package com.industria.coordinacion

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.*
import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.industria.coordinacion.ui.*
import com.sistema.distribuido.network.*
import com.sistema.distribuido.network.PermissionDecision
import com.sistema.distribuido.network.protocol.AppType
import com.sistema.distribuido.network.protocol.CimProtocol
import com.sistema.distribuido.network.protocol.StationTopology
import com.sistema.distribuido.network.prefecto.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var bluetoothManager: BluetoothHardwareManager? = null
    private var sppManager: BluetoothSppManager? = null
    private var tcpServer: TcpServer? = null
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppIdentifier.init(this, AppType.COORDINADOR)
        GlobalPermissionManager.init(this)

        permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results[Manifest.permission.CAMERA] != true) {
                // El tab de ArUco mostrará el mensaje de permiso si la cámara no está autorizada.
            }
            if (results[Manifest.permission.BLUETOOTH_CONNECT] == true) {
                sppManager?.startServer()
            }
        }
        
        GlobalBluetoothManager.init(this, onLog = { msg ->
            // Se puede inyectar en el VM o logs globales
        }, onDataReceived = { mac, data ->
            try {
                val cim = com.sistema.distribuido.network.protocol.CimMessage.fromTransportString(data)
                if (cim != null) {
                    GlobalCommandBroker.getInstanceOrNull()?.let { broker ->
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            try { broker.handleResponse(cim) } catch (_: Exception) {}
                        }
                    }
                }
            } catch (_: Exception) {}
        })
        bluetoothManager = GlobalBluetoothManager.getInstance()
        sppManager = BluetoothSppManager(this, { msg -> Log.d("BT_SPP", msg) }, { _, data ->
            try {
                val cim = com.sistema.distribuido.network.protocol.CimMessage.fromTransportString(data)
                if (cim != null) {
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        try { GlobalCommandBroker.getInstanceOrNull()?.handleResponse(cim) } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        })
        tcpServer = TcpServer(8888)
        tcpServer?.onMessageReceived = { ip, data ->
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val cim = com.sistema.distribuido.network.protocol.CimMessage.fromTransportString(data)
                    if (cim != null && cim.commandType == com.sistema.distribuido.network.protocol.CommandType.REQUIRE_PERMISSION) {
                        handleTcpHandshake(ip, cim)
                    } else if (cim != null) {
                        try { GlobalCommandBroker.getInstanceOrNull()?.handleResponse(cim) } catch (_: Exception) {}
                    } else if (data.startsWith(CimProtocol.RED_VALIDA)) {
                        handleTcpHandshake(ip, data)
                    } else {
                        Log.d("TcpServer", "TCP mensaje no CIM de $ip: $data")
                    }
                } catch (e: Exception) {
                    Log.w("TcpServer", "Error procesando mensaje de $ip", e)
                }
            }
        }
        tcpServer?.onClientConnected = { ip ->
            Log.d("TcpServer", "Cliente TCP conectado: $ip")
        }
        tcpServer?.onClientDisconnected = { ip ->
            Log.d("TcpServer", "Cliente TCP desconectado: $ip")
        }
        tcpServer?.onError = { errorMsg ->
            Log.e("TcpServer", errorMsg)
        }
        val commandBroker = CommandBroker(bluetoothManager, sppManager!!, tcpServer, null)
        GlobalCommandBroker.init(commandBroker)

        requestBluetoothPermissions()

        setContent {
            val scope = rememberCoroutineScope()
            var currentGcodeFile by remember { mutableStateOf<String?>(null) }
            val vm: CoordinatorViewModel = viewModel()
            val gcodeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    scope.launch {
                        try {
                            val input = this@MainActivity.contentResolver.openInputStream(uri)
                            val bytes = input?.readBytes() ?: ByteArray(0)
                            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                            val filename = uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "archivo.gcode" } ?: "archivo.gcode"
                            currentGcodeFile = filename
                            vm.sendLaserLoadFile(filename, b64)
                        } catch (e: Exception) {
                            vm.log("✗ Error leyendo archivo G-code: ${e.message}")
                        }
                    }
                } else {
                    vm.log("✗ Selección de G-code cancelada")
                }
            }

            Surface(Modifier.fillMaxSize()) {
                val startServerAction: () -> Unit = {
                    tcpServer?.start()
                    sppManager?.startServer()
                    lifecycleScope.launch { vm.startTcpServer() }
                }
                val stopServerAction: () -> Unit = {
                    tcpServer?.stop()
                    sppManager?.stopServer()
                    lifecycleScope.launch { vm.stopTcpServer() }
                }
                val refreshBluetoothAction: () -> Unit = {
                    bluetoothManager?.startScan()
                    lifecycleScope.launch { vm.refreshBluetoothDevices() }
                }
                val exportTrackingAction = {
                    lifecycleScope.launch {
                        val csv = vm.buildTrackingCsv()
                        if (csv.isBlank()) {
                            vm.log("⚠ No hay datos para exportar")
                            return@launch
                        }
                        val filename = "tracking_${System.currentTimeMillis()}.csv"
                        try {
                            this@MainActivity.openFileOutput(filename, MODE_PRIVATE).use { output ->
                                output.write(csv.toByteArray())
                            }
                            vm.log("✓ CSV guardado en archivos internos: $filename")
                        } catch (e: Exception) {
                            vm.log("✗ Error guardando CSV: ${e.message}")
                        }
                    }
                }

                CoordinatorMasterScreen(
                    vm = vm,
                    onStartServer = {
                        tcpServer?.start()
                        sppManager?.startServer()
                        lifecycleScope.launch { vm.startTcpServer() }
                        Unit
                    },
                    onStopServer = {
                        tcpServer?.stop()
                        sppManager?.stopServer()
                        lifecycleScope.launch { vm.stopTcpServer() }
                        Unit
                    },
                    onRefreshBluetooth = {
                        bluetoothManager?.startScan()
                        lifecycleScope.launch { vm.refreshBluetoothDevices() }
                        Unit
                    },
                    onToggleAutoMode = { enabled -> vm.setAutoModeEnabled(enabled) },
                    onExportCsv = {
                        lifecycleScope.launch {
                            val csv = vm.buildTrackingCsv()
                            if (csv.isBlank()) {
                                vm.log("⚠ No hay datos para exportar")
                                return@launch
                            }
                            val filename = "tracking_${System.currentTimeMillis()}.csv"
                            try {
                                this@MainActivity.openFileOutput(filename, MODE_PRIVATE).use { output ->
                                    output.write(csv.toByteArray())
                                }
                                vm.log("✓ CSV guardado en archivos internos: $filename")
                            } catch (e: Exception) {
                                vm.log("✗ Error guardando CSV: ${e.message}")
                            }
                        }
                    },
                    onLaserLoad = { gcodeLauncher.launch(arrayOf("*/*")) },
                    currentGcodeFile = currentGcodeFile
                )
            }
        }
    }

    private fun requestBluetoothPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    override fun onDestroy() {
        super.onDestroy()
        tcpServer?.stop()
        sppManager?.stopServer()
        sppManager?.disconnectAll()
        bluetoothManager?.disconnectAll()
        bluetoothManager?.release()
    }

    private fun resolveStationAppType(stationName: String, stationUuid: String): AppType {
        val fromName = StationTopology.normalizeAppType(stationName)
        if (fromName != AppType.UNKNOWN) return fromName
        val fromUuid = CimProtocol.STATION_UUIDS.entries.firstOrNull { it.value.equals(stationUuid, ignoreCase = true) }?.key
        return fromUuid ?: AppType.UNKNOWN
    }

    private suspend fun handleTcpHandshake(ip: String, cim: com.sistema.distribuido.network.protocol.CimMessage) {
        val payload = cim.payload.split("|")
        if (payload.size < 4) {
            Log.w("TcpServer", "Handshake inválido desde $ip: ${cim.payload}")
            return
        }

        val stationName = payload[0].ifBlank { "UNKNOWN" }
        val password = payload[1]
        val mac = payload[2].ifBlank { ip }
        val stationUuid = payload[3]
        val appType = resolveStationAppType(stationName, stationUuid)

        try {
            val deviceInfo = com.sistema.distribuido.network.DeviceInfo(
                ip = ip,
                nombre = stationName,
                tipo = com.sistema.distribuido.network.DeviceType.UNKNOWN,
                mac = mac,
                appType = appType,
                isConnected = true
            )
            GlobalDeviceRegistry.registry.register(mac, deviceInfo)
        } catch (e: Exception) {
            Log.w("TcpServer", "No se pudo registrar dispositivo TCP: ${e.message}", e)
        }

        if (password != CimProtocol.PASSWORD_ACTUAL) {
            AuthorizationManager.deny(mac)
            val response = com.sistema.distribuido.network.protocol.CimMessage(
                sourceMac = AppIdentifier.getInstance().deviceMac,
                sourceApp = AppType.COORDINADOR,
                destMac = mac,
                destApp = cim.sourceApp,
                commandType = com.sistema.distribuido.network.protocol.CommandType.PERMISSION_DENIED,
                payload = CimProtocol.AUTH_BLOCKED
            )
            tcpServer?.sendToClientByMac(mac, response.toTransportString())
            Log.w("TcpServer", "Handshake DENIED por contraseña inválida: $mac")
            return
        }

        val decision = try {
            GlobalPermissionManager.getInstance().requestPermission(mac, appType, stationName)
        } catch (e: Exception) {
            Log.w("TcpServer", "Error solicitando permiso para $mac: ${e.message}", e)
            PermissionDecision.TIMEOUT
        }

        val response = com.sistema.distribuido.network.protocol.CimMessage(
            sourceMac = AppIdentifier.getInstance().deviceMac,
            sourceApp = AppType.COORDINADOR,
            destMac = mac,
            destApp = cim.sourceApp,
            commandType = if (decision == PermissionDecision.APPROVED) com.sistema.distribuido.network.protocol.CommandType.PERMISSION_GRANTED else com.sistema.distribuido.network.protocol.CommandType.PERMISSION_DENIED,
            payload = if (decision == PermissionDecision.APPROVED) CimProtocol.AUTH_AUTHORIZED else CimProtocol.AUTH_BLOCKED
        )

        if (decision == PermissionDecision.APPROVED) {
            AuthorizationManager.authorize(mac)
            try { GlobalDeviceRegistry.registry.authorize(mac) } catch (_: Exception) {}
            tcpServer?.sendToClientByMac(mac, response.toTransportString())
            Log.d("TcpServer", "Handshake autorizado y VALIDADO: $mac")
        } else {
            AuthorizationManager.deny(mac)
            tcpServer?.sendToClientByMac(mac, response.toTransportString())
            try { GlobalDeviceRegistry.registry.disconnect(mac) } catch (_: Exception) {}
            Log.d("TcpServer", "Handshake rechazado/timeout para: $mac")
        }
    }

    private suspend fun handleTcpHandshake(ip: String, data: String) {
        val parts = data.split(";")
        if (parts.size < 5) {
            Log.w("TcpServer", "Handshake inválido desde $ip: $data")
            return
        }

        val stationName = parts[1].ifBlank { "UNKNOWN" }
        val password = parts[2]
        val mac = parts[3].ifBlank { ip }
        val stationUuid = parts[4]
        val appType = resolveStationAppType(stationName, stationUuid)

        try {
            val deviceInfo = com.sistema.distribuido.network.DeviceInfo(
                ip = ip,
                nombre = stationName,
                tipo = com.sistema.distribuido.network.DeviceType.UNKNOWN,
                mac = mac,
                appType = appType,
                isConnected = true
            )
            GlobalDeviceRegistry.registry.register(mac, deviceInfo)
        } catch (e: Exception) {
            Log.w("TcpServer", "No se pudo registrar dispositivo TCP: ${e.message}", e)
        }

        if (password != CimProtocol.PASSWORD_ACTUAL) {
            AuthorizationManager.deny(mac)
            tcpServer?.sendToClientByMac(mac, CimProtocol.RESPONSE_DENIED)
            Log.w("TcpServer", "Handshake DENIED por contraseña inválida: $mac")
            return
        }

        val decision = try {
            GlobalPermissionManager.getInstance().requestPermission(mac, appType, stationName)
        } catch (e: Exception) {
            Log.w("TcpServer", "Error solicitando permiso para $mac: ${e.message}", e)
            PermissionDecision.TIMEOUT
        }

        when (decision) {
            PermissionDecision.APPROVED -> {
                AuthorizationManager.authorize(mac)
                try { GlobalDeviceRegistry.registry.authorize(mac) } catch (_: Exception) {}
                tcpServer?.sendToClientByMac(mac, CimProtocol.RESPONSE_AUTHORIZED)
                Log.d("TcpServer", "Handshake autorizado y VALIDADO: $mac")
            }
            else -> {
                AuthorizationManager.deny(mac)
                tcpServer?.sendToClientByMac(mac, CimProtocol.RESPONSE_DENIED)
                try { GlobalDeviceRegistry.registry.disconnect(mac) } catch (_: Exception) {}
                Log.d("TcpServer", "Handshake rechazado/timeout para: $mac")
            }
        }
    }
}

data class TabItem(val name: String, val icon: ImageVector, val index: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoordinatorMasterScreen(
    vm: CoordinatorViewModel,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onRefreshBluetooth: () -> Unit,
    onToggleAutoMode: (Boolean) -> Unit,
    onExportCsv: () -> Unit,
    onLaserLoad: () -> Unit,
    currentGcodeFile: String?
) {
    val state by vm.uiState.collectAsState()
    var selectedTabIndex by remember { mutableStateOf(state.currentTabIndex) }
    val scope = rememberCoroutineScope()
    var showAutomation by remember { mutableStateOf(false) }

    val tabs = listOf(
        TabItem("EXEC", Icons.Default.Dashboard, 0),
        TabItem("CINTA", Icons.Default.SettingsInputComponent, 1),
        TabItem("ROBOT", Icons.Default.PrecisionManufacturing, 2),
        TabItem("ARUCO", Icons.Default.QrCode, 3),
        TabItem("MAPA", Icons.Default.Radar, 4),
        TabItem("NODOS", Icons.Default.Lan, 5),
        TabItem("RACKS", Icons.Default.Inventory, 6)
    )
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showGlobalActions by remember { mutableStateOf(false) }
    val executiveState = state.executiveState

    IndustrialScaffold(
        titulo = "CIM HUB v6.0",
        subtitulo = "SISTEMA DE COORDINACIÓN GLOBAL",
        actions = {
            IconButton(onClick = { showAutomation = true }) {
                Icon(Icons.Default.Terminal, "Consola de automatización", tint = IndustrialTheme.Primario)
            }
        },
        floatingActionButton = { BluetoothConnectionFAB() }
    ) { paddingValues ->
        Column(Modifier.padding(paddingValues).fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(IndustrialTheme.Tarjeta)
                    .border(1.dp, IndustrialTheme.Borde, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Column {
                    Text(
                        text = if (state.isAutoModeEnabled) "AUTO MODE: AUTORIZACIÓN AUTOMÁTICA ACTIVADA" else "AUTO MODE: AUTORIZACIÓN MANUAL",
                        color = if (state.isAutoModeEnabled) IndustrialTheme.Exito else IndustrialTheme.TextoPrincipal,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (state.networkState.pendingRequestCount > 0) "Solicitudes pendientes: ${state.networkState.pendingRequestCount}" else "No hay solicitudes pendientes",
                        color = IndustrialTheme.TextoSecundario,
                        fontSize = 11.sp
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            // Navigation Bar custom
            NavigationBar(containerColor = androidx.compose.ui.graphics.Color.Black, contentColor = IndustrialTheme.Primario) {
                tabs.forEachIndexed { index, tab ->
                            NavigationBarItem(
                                selected = selectedTabIndex == index,
                                onClick = {
                                    selectedTabIndex = index
                                    vm.selectTab(index)
                                },
                                icon = { Icon(tab.icon, tab.name) },
                                label = { Text(tab.name, fontSize = 9.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = IndustrialTheme.Primario,
                            selectedTextColor = IndustrialTheme.Primario,
                            unselectedIconColor = androidx.compose.ui.graphics.Color.DarkGray,
                            unselectedTextColor = androidx.compose.ui.graphics.Color.DarkGray,
                            indicatorColor = androidx.compose.ui.graphics.Color.DarkGray.copy(alpha = 0.2f)
                        )
                    )
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (selectedTabIndex) {
                    0 -> {
                        Column(Modifier.fillMaxSize().padding(8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { vm.triggerEmergencyStop() },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                                    modifier = Modifier.weight(1f).height(52.dp)
                                ) {
                                    Icon(Icons.Default.Warning, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("E-STOP", fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick = { showGlobalActions = true },
                                    modifier = Modifier.weight(1f).height(52.dp)
                                ) {
                                    Icon(Icons.Default.Settings, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("ACCIONES")
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("Dashboard ejecutivo", fontWeight = FontWeight.Bold, color = IndustrialTheme.Primario)
                            Text("Estado en tiempo real de las estaciones", color = IndustrialTheme.TextoSecundario, fontSize = 12.sp)
                            Spacer(Modifier.height(12.dp))
                            LazyVerticalGrid(columns = GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(executiveState.stations.values.toList()) { station ->
                                    val cardColor = when (station.status) {
                                        ExecutiveStationStatus.STOPPED -> Color(0xFFB00020)
                                        ExecutiveStationStatus.WARNING -> Color(0xFFFFC107)
                                        ExecutiveStationStatus.BUSY -> Color(0xFF1565C0)
                                        ExecutiveStationStatus.READY -> Color(0xFF2E7D32)
                                        else -> IndustrialTheme.Tarjeta
                                    }
                                    ElevatedCard(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.elevatedCardColors(containerColor = cardColor.copy(alpha = 0.18f))
                                    ) {
                                        Column(Modifier.padding(12.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Box(Modifier.size(10.dp).background(color = when (station.status) { ExecutiveStationStatus.STOPPED -> Color.Red; ExecutiveStationStatus.WARNING -> Color.Yellow; ExecutiveStationStatus.BUSY -> Color.Blue; ExecutiveStationStatus.READY -> Color.Green; else -> Color.Gray }, shape = androidx.compose.foundation.shape.CircleShape))
                                                Spacer(Modifier.width(8.dp))
                                                Text(station.label, fontWeight = FontWeight.Bold, color = IndustrialTheme.TextoPrincipal)
                                            }
                                            Spacer(Modifier.height(6.dp))
                                            Text(station.detail, color = IndustrialTheme.TextoSecundario, fontSize = 12.sp)
                                            Text("Último: ${station.lastEvent}", color = IndustrialTheme.TextoSecundario, fontSize = 11.sp)
                                            Spacer(Modifier.height(8.dp))
                                            when (station.name) {
                                                "ALMACEN" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    TextButton(onClick = { vm.handleIncomingStationEvent("ALMACEN", "Pallet Liberado") }) { Text("Liberar pallet") }
                                                }
                                                else -> Unit
                                            }
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("Flujo activo: ${executiveState.currentFlow}", color = IndustrialTheme.Primario, fontWeight = FontWeight.SemiBold)
                            if (executiveState.isEmergencyStop) {
                                Text("EMERGENCIA ACTIVA", color = Color.Red, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    1 -> SystemTab(state.cintaState, { f, t -> vm.sendCintaCommand(f, t) }, { f, t -> vm.sendFreeCommand(f, t) }, { scope.launch { vm.connectCinta() } }, { vm.disconnectCinta() }, { vm.resetCinta() })
                    2 -> RobotLaserTab(
                        { vm.sendRobotCommand(it) },
                        { command -> if (command == "LASER_LOAD") onLaserLoad() else vm.sendLaserCommand(command) },
                        state.qcState,
                        { vm.startQcProgram(it) },
                        { vm.stopQcProgram(it) },
                        currentGcodeFile
                    )
                    3 -> CombinedArucoTab(
                        { vm.generateAruco(it) },
                        { vm.sendLaserCommand(it) },
                        { vm.handleArucoDetected(it) }
                    )
                    4 -> TrackingTab(state.trackingState, { vm.startTracking() }, { vm.stopTracking() }, onExportCsv)
                    5 -> NetworkTab(state.networkState, onStartServer, onStopServer, { vm.authorizeDevice(it) }, { vm.rejectDevice(it) }, { vm.disconnectDevice(it) }, { vm.sendNetworkMessage(it) }, onRefreshBluetooth, onToggleAutoMode, { vm.forceIdentify(it) }, { vm.reconnectDevice(it) })
                    6 -> StorageTab({ vm.sendStorageCommand(it) })
                }
            }
            
            // Global Terminal for Coordinator
            IndustrialTerminal(logs = state.logMessages, modifier = Modifier.height(150.dp))
        }
    }

    if (showGlobalActions) {
        ModalBottomSheet(
            onDismissRequest = { showGlobalActions = false },
            sheetState = sheetState,
            containerColor = IndustrialTheme.Tarjeta
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("ACCIONES GLOBALES", fontWeight = FontWeight.Bold, color = IndustrialTheme.Primario)
                Button(
                    onClick = {
                        vm.startFullPlant()
                        showGlobalActions = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = IndustrialTheme.Primario)
                ) {
                    Text("INICIAR PLANTA COMPLETA")
                }
                OutlinedButton(
                    onClick = {
                        vm.calibrateGlobal()
                        showGlobalActions = false
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("CALIBRACIÓN GLOBAL")
                }
            }
        }
    }

    if (showAutomation) {
        var scriptText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAutomation = false },
            containerColor = IndustrialTheme.Tarjeta,
            title = { Text("CONSOLA DE AUTOMATIZACIÓN", color = IndustrialTheme.Primario, fontWeight = FontWeight.Bold) },
            text = { 
                IndustrialTextField(valor = scriptText, onValueChange = { scriptText = it }, label = "Comando Secuencial")
            },
            confirmButton = {
                IndustrialActionButton(texto = "Run", icono = Icons.Default.PlayArrow, modifier = Modifier.width(100.dp)) {
                    try {
                        vm.runScript(scriptText)
                    } catch (_: Exception) {
                        // swallow errors from scripts to avoid crashing UI
                    }
                    showAutomation = false
                }
            },
            dismissButton = {
                IndustrialTextButton(
                    texto = "Cancelar",
                    textColor = IndustrialTheme.TextoSecundario,
                    onClick = { showAutomation = false }
                )
            }
        )
    }

    state.pendingPermissionRequest?.let { request ->
        PermissionDialog(
            requestId = request.id,
            mac = request.mac,
            appType = request.appType.name,
            deviceName = request.deviceName,
            onAuthorize = { mac, remember -> vm.authorizeDevice(mac, remember) },
            onReject = { mac -> vm.rejectDevice(mac) },
            onClose = { vm.clearPendingPermissionRequest() }
        )
    }
}
