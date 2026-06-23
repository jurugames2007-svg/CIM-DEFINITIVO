package com.sistema.distribuido.network

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import com.sistema.distribuido.network.protocol.AppType
import com.sistema.distribuido.network.protocol.StationTopology
import java.util.*

/**
 * IDENTIFICADOR DE APLICACIÓN
 *
 * Singleton que proporciona identidad única a cada aplicación:
 * - MAC Address del dispositivo
 * - Tipo de App (COORDINADOR, PLC, etc)
 * - Versión de App
 * - Nombre único del dispositivo
 */

class AppIdentifier private constructor(private val context: Context, val appType: AppType) {

    private val sharedPrefs: SharedPreferences =
        context.getSharedPreferences("cim_app_identifier", Context.MODE_PRIVATE)

    // Identidad única del dispositivo (MAC address)
    val deviceMac: String = getOrCreateMac()

    // Versión de la app (sincronizar con AndroidManifest.xml)
    val appVersion: String = "1.0.0"

    // Nombre amigable del dispositivo
    val deviceName: String = getOrCreateDeviceName()

    /**
     * Obtiene o crea una MAC address única para este dispositivo.
     * En Android real, usaría ANDROID_ID + sanitización.
     */
    private fun getOrCreateMac(): String {
        var mac = sharedPrefs.getString("device_mac", "")

        if (mac.isNullOrEmpty()) {
            // Generar MAC partir de ANDROID_ID + AppType
            val androidId = try {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                    ?: UUID.randomUUID().toString()
            } catch (e: Exception) {
                UUID.randomUUID().toString()
            }

            // Convertir a formato MAC: AA:BB:CC:DD:EE:FF
            mac = generateMacFromId(androidId, appType)
            sharedPrefs.edit().putString("device_mac", mac).apply()
        }

        return mac ?: ""
    }

    /**
     * Obtiene o crea el nombre del dispositivo
     */
    private fun getOrCreateDeviceName(): String {
        var name = sharedPrefs.getString("device_name", "")

        if (name.isNullOrEmpty()) {
            val baseName = StationTopology.definitionFor(appType)?.displayName ?: "Device"
            name = "$baseName-${Build.MODEL}"
            sharedPrefs.edit().putString("device_name", name).apply()
        }

        return name ?: ""
    }

    companion object {
        @Volatile
        private var instance: AppIdentifier? = null

        /**
         * Obten la instancia singleton (DEBE inicializarse en MainActivity)
         */
        fun getInstance(): AppIdentifier {
            return instance ?: throw IllegalStateException(
                "AppIdentifier no inicializado. Llama a init() en MainActivity"
            )
        }

        /**
         * Inicializa el AppIdentifier (LLAMAR EN MainActivity.onCreate())
         */
        fun init(context: Context, appType: AppType) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        instance = AppIdentifier(context, appType)
                    }
                }
            }
        }
    }
}

/**
 * Genera un identificador MAC válido desde una cadena (17 caracteres con colones)
 */
fun generateMacFromId(id: String, appType: AppType): String {
    // Hash del ID + AppType para crear algo único
    val combined = (id + appType.name).hashCode().toLong()

    // Convertir a bytes y formatear como MAC
    var hash = Math.abs(combined)
    val bytes = ByteArray(6)

    for (i in 0 until 6) {
        bytes[i] = (hash and 0xFF).toByte()
        hash = hash shr 8
    }

    // Formatear como AA:BB:CC:DD:EE:FF
    return bytes.joinToString(":") { byte ->
        String.format("%02X", byte.toInt() and 0xFF)
    }
}

/**
 * Obtiene el AppType actual
 */
fun getCurrentAppType(context: Context): AppType {
    return StationTopology.appTypeForPackage(context.packageName)
}

/**
 * Extension function para acceder fácilmente desde cualquier lado
 */
fun Context.getAppIdentifier(): AppIdentifier {
    return AppIdentifier.getInstance()
}

