package com.sistema.distribuido.network.protocol

enum class TransportMode {
    SPP,
    BLE,
    TCP,
    HYBRID
}

data class StationDefinition(
    val appType: AppType,
    val packageName: String,
    val displayName: String,
    val preferredTransport: TransportMode,
    val stationId: String
)

object StationTopology {
    private val definitions = listOf(
        StationDefinition(
            appType = AppType.COORDINADOR,
            packageName = "com.industria.coordinacion",
            displayName = "Coordinador",
            preferredTransport = TransportMode.TCP,
            stationId = "CIM-ST-COR-X0"
        ),
        StationDefinition(
            appType = AppType.PLC,
            packageName = "com.industria.plc",
            displayName = "PLC",
            preferredTransport = TransportMode.TCP,
            stationId = "CIM-ST-PLC-X4"
        ),
        StationDefinition(
            appType = AppType.MANUFACTURA,
            packageName = "com.industria.manufactura",
            displayName = "Manufactura",
            preferredTransport = TransportMode.SPP,
            stationId = "CIM-ST-MAN-X2"
        ),
        StationDefinition(
            appType = AppType.CALIDAD,
            packageName = "com.industria.calidad",
            displayName = "Calidad",
            preferredTransport = TransportMode.BLE,
            stationId = "CIM-ST-CAL-X3"
        ),
        StationDefinition(
            appType = AppType.ALMACEN,
            packageName = "com.industria.almacenamiento",
            displayName = "Almacen",
            preferredTransport = TransportMode.SPP,
            stationId = "CIM-ST-ALM-X1"
        )
    )

    val stationIdsByAppType: Map<AppType, String> = definitions.associate { it.appType to it.stationId }
    val definitionsByAppType: Map<AppType, StationDefinition> = definitions.associateBy { it.appType }
    val definitionsByPackageName: Map<String, StationDefinition> = definitions.associateBy { it.packageName }

    fun appTypeForPackage(packageName: String): AppType {
        return definitionsByPackageName[packageName]?.appType ?: AppType.UNKNOWN
    }

    fun definitionFor(appType: AppType): StationDefinition? {
        return definitionsByAppType[appType]
    }

    fun definitionFor(packageName: String): StationDefinition? {
        return definitionsByPackageName[packageName]
    }

    fun normalizeAppType(value: String?): AppType {
        return value
            ?.trim()
            ?.uppercase()
            ?.let { runCatching { AppType.valueOf(it) }.getOrDefault(AppType.UNKNOWN) }
            ?: AppType.UNKNOWN
    }
}
