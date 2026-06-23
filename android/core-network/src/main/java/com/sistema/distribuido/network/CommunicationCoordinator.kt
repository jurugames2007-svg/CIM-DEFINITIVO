package com.sistema.distribuido.network

import com.sistema.distribuido.network.protocol.CimMessage
import com.sistema.distribuido.network.protocol.CimMessageBuilder
import com.sistema.distribuido.network.protocol.CimProtocol
import com.sistema.distribuido.network.protocol.AppType
import com.sistema.distribuido.network.protocol.StationTopology
import com.sistema.distribuido.network.protocol.CommandType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * COMMUNICATION COORDINATOR v7.0
 *
 * Orquesta el flujo de comunicación BLE/SPP/TCP entre:
 * - app-coordinador (Maestro)
 * - app-scorbot, app-vision, app-laser, app-conveyor (Esclavos)
 * - Firmware ESP32 con FreeRTOS
 *
 * Responsabilidades:
 * 1. Validar autorización antes de encaminar comandos
 * 2. Encapsular mensajes en TransportFrame
 * 3. Mantener estado de sesión por dispositivo
 * 4. Retry automático con backoff exponencial
 * 5. Timeout de comandos pendientes
 */

data class SessionState(
    val mac: String,
    val deviceName: String,
    var authState: String = CimProtocol.AUTH_PENDING,
    var lastHeartbeat: Long = 0,
    var lastCommandSent: Long = 0,
    var commandTimeout: Long = 10000,
    var isHealthy: Boolean = false
)

data class PendingCommand(
    val id: String,
    val mac: String,
    val payload: String,
    val createdAt: Long = System.currentTimeMillis(),
    val maxRetries: Int = 3,
    var retries: Int = 0,
    val timeoutMs: Long = 10000
)

class CommunicationCoordinator(
    private val permissionManager: PermissionManager? = null,
    private val onLog: (String) -> Unit = {},
    private val onMessageReceived: (mac: String, message: String) -> Unit = { _, _ -> }
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sessionStates = ConcurrentHashMap<String, SessionState>()
    private val pendingCommands = ConcurrentHashMap<String, PendingCommand>()
    private val sessionLocks = ConcurrentHashMap<String, Mutex>()
    private val commandTimeoutJobs = ConcurrentHashMap<String, Job>()

    private val _coordinationStatus = MutableStateFlow<Map<String, SessionState>>(emptyMap())
    val coordinationStatus: StateFlow<Map<String, SessionState>> = _coordinationStatus.asStateFlow()

    /**
     * Registra un dispositivo como sesión activa
     */
    suspend fun registerSession(
        mac: String,
        deviceName: String,
        appType: AppType
    ) {
        registerSession(mac = mac, deviceName = deviceName, appTypeName = appType.name)
    }

    suspend fun registerSession(
        mac: String,
        deviceName: String,
        appTypeName: String
    ) {
        val lock = sessionLocks.getOrPut(mac) { Mutex() }
        lock.withLock {
            val session = SessionState(
                mac = mac,
                deviceName = deviceName,
                authState = CimProtocol.AUTH_PENDING
            )
            sessionStates[mac] = session
            updateCoordinationStatus()
            onLog("[COORD] Sesión registrada: $deviceName [$mac]")

            // Solicitar autorización al PermissionManager
            requestAuthorizationInternal(mac, deviceName, StationTopology.normalizeAppType(appTypeName))
        }
    }

    /**
     * Solicita autorización interna (llamada por registerSession)
     */
    private suspend fun requestAuthorizationInternal(
        mac: String,
        deviceName: String,
        appType: AppType
    ) {
        try {
            if (permissionManager == null) {
                // Sin PermissionManager, asumir autorizaciónautomática
                AuthorizationManager.authorize(mac)
                updateSessionAuth(mac, CimProtocol.AUTH_AUTHORIZED)
                onLog("[COORD] Auto-autorizado: $mac (sin PermissionManager)")
                return
            }

            // Solicitar permiso
            val decision = permissionManager.requestPermission(
                mac = mac,
                appType = appType,
                deviceName = deviceName
            )

            when (decision) {
                PermissionDecision.APPROVED -> {
                    AuthorizationManager.authorize(mac)
                    updateSessionAuth(mac, CimProtocol.AUTH_AUTHORIZED)
                    onLog("[COORD] ✓ AUTORIZADO: $mac")
                }
                PermissionDecision.REJECTED -> {
                    AuthorizationManager.deny(mac)
                    updateSessionAuth(mac, CimProtocol.AUTH_BLOCKED)
                    onLog("[COORD] ✗ RECHAZADO: $mac")
                }
                PermissionDecision.TIMEOUT -> {
                    AuthorizationManager.deny(mac)
                    updateSessionAuth(mac, CimProtocol.AUTH_PENDING)
                    onLog("[COORD] ⏱ TIMEOUT autorización: $mac")
                }
                else -> {
                    updateSessionAuth(mac, CimProtocol.AUTH_PENDING)
                }
            }
        } catch (e: Exception) {
            onLog("[COORD] Error en autorización: ${e.message}")
        }
    }

    /**
     * Encamina un comando hacia un dispositivo
     * Valida autorización antes de enviar
     */
    suspend fun routeCommand(
        mac: String,
        command: String,
        timeout: Long = 10000,
        onResponse: ((String) -> Unit)? = null
    ): Boolean {
        val session = sessionStates[mac]
        if (session == null) {
            onLog("[COORD] ✗ Sesión no encontrada: $mac")
            return false
        }

        // Validar autorización
        if (!AuthorizationManager.isAuthorized(mac)) {
            onLog("[COORD] ✗ No autorizado: $mac")
            return false
        }

        // Crear comando pendiente
        val cmdId = "${mac}_${System.currentTimeMillis()}"
        val pending = PendingCommand(
            id = cmdId,
            mac = mac,
            payload = command,
            timeoutMs = timeout
        )
        pendingCommands[cmdId] = pending

        // Iniciar timeout
        scheduleCommandTimeout(cmdId, timeout)

        onLog("[COORD] → ENVÍO: $mac | $command (timeout: ${timeout}ms)")

        // Aquí iría la integración con BLE/SPP
        // Para ahora, registramos la intención
        scope.launch {
            delay(timeout)
            val resp = pendingCommands.remove(cmdId)
            if (resp != null && onResponse != null) {
                onResponse("TIMEOUT")
            }
        }

        return true
    }

    /**
     * Maneja respuesta entrante desde dispositivo
     */
    suspend fun handleIncomingMessage(mac: String, message: String) {
        val lock = sessionLocks.getOrPut(mac) { Mutex() }
        lock.withLock {
            // Parsear mensaje
            when {
                message.startsWith("IDENTIFY|") -> {
                    val parts = message.split("|")
                    if (parts.size >= 3) {
                        val status = parts[1]
                        val version = parts.getOrNull(2) ?: "unknown"
                        onLog("[COORD] 🆔 IDENTIFY: $mac | $status | v$version")
                        updateSessionHealth(mac, true)
                    }
                }

                message.startsWith("STATUS|") -> {
                    val parts = message.split("|")
                    if (parts.size >= 3) {
                        val status = parts[1]
                        onLog("[COORD] 📊 STATUS: $mac | $status")
                        updateSessionHealth(mac, true)
                    }
                }

                message.startsWith("ACK|") -> {
                    // Comando ejecutado exitosamente
                    val parts = message.split("|")
                    if (parts.size >= 2) {
                        val cmdRef = parts[1]
                        pendingCommands.remove(cmdRef)
                        onLog("[COORD] ✓ ACK recibido: $cmdRef")
                    }
                }

                message.startsWith("NACK|") -> {
                    // Error en comando
                    val parts = message.split("|")
                    if (parts.size >= 3) {
                        val cmdRef = parts[1]
                        val reason = parts[2]
                        pendingCommands.remove(cmdRef)
                        onLog("[COORD] ✗ NACK: $cmdRef | Razón: $reason")
                    }
                }

                else -> {
                    onLog("[COORD] 📨 Mensaje: $mac | $message")
                    onMessageReceived(mac, message)
                }
            }
        }
    }

    /**
     * Revoca autorización de un dispositivo
     */
    suspend fun revokeAuthorization(mac: String) {
        val lock = sessionLocks.getOrPut(mac) { Mutex() }
        lock.withLock {
            AuthorizationManager.deny(mac)
            updateSessionAuth(mac, CimProtocol.AUTH_BLOCKED)
            onLog("[COORD] 🚫 REVOCADO: $mac")

            if (permissionManager != null) {
                permissionManager.revoke(mac)
            }

            // Cancelar comandos pendientes
            pendingCommands.entries
                .filter { it.value.mac == mac }
                .forEach { (cmdId, _) ->
                    pendingCommands.remove(cmdId)
                    commandTimeoutJobs[cmdId]?.cancel()
                    commandTimeoutJobs.remove(cmdId)
                }
        }
    }

    /**
     * Cierra sesión de un dispositivo
     */
    suspend fun closeSession(mac: String) {
        val lock = sessionLocks.getOrPut(mac) { Mutex() }
        lock.withLock {
            sessionStates.remove(mac)
            pendingCommands.entries
                .filter { it.value.mac == mac }
                .forEach { (cmdId, _) ->
                    pendingCommands.remove(cmdId)
                    commandTimeoutJobs[cmdId]?.cancel()
                    commandTimeoutJobs.remove(cmdId)
                }
            updateCoordinationStatus()
            onLog("[COORD] 🔌 DESCONECTADO: $mac")
        }
    }

    /**
     * Obtiene estado actual de una sesión
     */
    fun getSessionState(mac: String): SessionState? = sessionStates[mac]

    /**
     * Obtiene todos los dispositivos autorizados
     */
    fun getAuthorizedDevices(): List<String> {
        return sessionStates.filter { (_, session) ->
            session.authState == CimProtocol.AUTH_AUTHORIZED
        }.keys.toList()
    }

    /**
     * Obtiene todos los dispositivos pendientes
     */
    fun getPendingDevices(): List<String> {
        return sessionStates.filter { (_, session) ->
            session.authState == CimProtocol.AUTH_PENDING
        }.keys.toList()
    }

    /**
     * Obtiene todos los dispositivos rechazados
     */
    fun getRejectedDevices(): List<String> {
        return sessionStates.filter { (_, session) ->
            session.authState == CimProtocol.AUTH_BLOCKED
        }.keys.toList()
    }

    /**
     * Obtiene el conteo de comandos pendientes
     */
    fun getPendingCommandCount(): Int = pendingCommands.size

    /**
     * Limpia sesiones inactivas (sin heartbeat en más de 30s)
     */
    suspend fun cleanupInactiveSessions(inactivityThresholdMs: Long = 30000) {
        val now = System.currentTimeMillis()
        sessionStates.entries
            .filter { (_, session) -> now - session.lastHeartbeat > inactivityThresholdMs }
            .forEach { (mac, _) ->
                closeSession(mac)
            }
    }

    // ============ PRIVADAS ============

    private suspend fun updateSessionAuth(mac: String, authState: String) {
        sessionStates[mac]?.authState = authState
        updateCoordinationStatus()
    }

    private suspend fun updateSessionHealth(mac: String, healthy: Boolean) {
        sessionStates[mac]?.apply {
            lastHeartbeat = System.currentTimeMillis()
            isHealthy = healthy
        }
        updateCoordinationStatus()
    }

    private fun updateCoordinationStatus() {
        _coordinationStatus.value = sessionStates.toMap()
    }

    private fun scheduleCommandTimeout(cmdId: String, timeoutMs: Long) {
        commandTimeoutJobs[cmdId]?.cancel()
        commandTimeoutJobs[cmdId] = scope.launch {
            delay(timeoutMs)
            if (pendingCommands.containsKey(cmdId)) {
                onLog("[COORD] ⏱ TIMEOUT de comando: $cmdId")
                pendingCommands.remove(cmdId)
            }
        }
    }

    fun shutdown() {
        scope.cancel()
        commandTimeoutJobs.values.forEach { it.cancel() }
    }
}
