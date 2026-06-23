package com.sistema.distribuido.network.protocol

/**
 * Protocolo Prefecto CIM - Versión Definitiva "Industrial Hub" 2024
 * Optimizado para Seguridad de Red y Complejidad O(1)
 */
object CimProtocol {
    // Configuración de Servidor
    const val WIFI_PORT = 8888
    val SPP_UUID: java.util.UUID = java.util.UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    // Password Dinámica (Editable desde el Maestro)
    var PASSWORD_ACTUAL = "UBB_CIM_PRO_SECURE_2024"

    // Handshake Token (Seguridad por Ofuscación + Validación)
    const val RED_VALIDA = "CIM_MASTER_HUB_V1"
    const val RESPONSE_AUTHORIZED = "VALIDADO"
    const val RESPONSE_DENIED = "DENIED"
    const val RESPONSE_WAITING = "ESPERANDO"

    // Estados de Autorización legibles para UI
    const val AUTH_STATE_DISCONNECTED = "DESCONECTADO"
    const val AUTH_STATE_PENDING = "ESPERANDO AUTORIZACIÓN"
    const val AUTH_STATE_VALIDATED = "VALIDADO"
    const val AUTH_STATE_REJECTED = "RECHAZADO"

    // Estados de Operación
    const val READY = "READY"
    const val BUSY = "BUSY"
    const val ERROR = "ERROR"
    const val STOP = "STOP"
    const val IDLE = "IDLE" // Mantenido por compatibilidad previa

    // Señalización
    const val REQ_PERM = "REQ_PERM"
    const val GRANTED = "GRANTED"
    const val DENIED = "DENIED"
    const val ABORT = "ABORT"

    // Estados de Autorización por MAC
    const val AUTH_PENDING = "PENDING"
    const val AUTH_AUTHORIZED = "AUTHORIZED"
    const val AUTH_BLOCKED = "BLOCKED"
    const val AUTH_REMOVED = "REMOVED"

    // Comandos de Hardware (Basados en scripts de Python)
    object Hardware {
        const val SCORBOT_HOME = "HOME\r"
        const val SCORBOT_READY = "READY\r"
        const val SCORBOT_ABORT = "ABORT\r"
        const val SCORBOT_OPEN = "OPEN\r"
        const val SCORBOT_CLOSE = "CLOSE\r"
        const val SCORBOT_COFF = "COFF\r"

        const val CONVEYOR_START = "RUN_CINTA\n"
        const val CONVEYOR_STOP = "STOP_CINTA\n"

        const val SENSOR_SCAN = "SCAN_NOW\n"
    }

    // Mapeo de Identificadores de Estación (UUIDs de Software)
    val STATION_UUIDS = StationTopology.stationIdsByAppType

    /**
     * Formato Handshake Esperado:
     * CIM_MASTER_HUB_V1;NOMBRE_ESTACION;PASSWORD;MAC_DISPOSITIVO;UUID_STATION
     */

    fun formatLog(module: String, message: String, success: Boolean? = null): String {
        val time = java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())
        val status = when (success) {
            true -> "✓"
            false -> "✗"
            else -> "•"
        }
        return "[$time] [$status] [$module] $message"
    }
}
