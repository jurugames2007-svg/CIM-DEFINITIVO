package com.industria.plc

/**
 * Protocolo PLC para cinta transportadora.
 *
 * Contiene los comandos hexadecimales reales del protocolo Omron Host-Link
 * extraídos de integrated_panel.py. Acceso O(1) mediante mapas inmutables.
 */
object PlcProtocol {

    const val BAUDRATE = 9600
    const val DATA_BITS = 7
    const val PARITY = 2      // EVEN
    const val STOP_BITS = 2

    /**
     * DELIVER: envía un pallet desde estación [station] a posición [pallet].
     * Mapa (station, pallet) -> comando hex Host-Link.
     */
    val DELIVER_COMMANDS: Map<Pair<Int, Int>, String> = mapOf(
        (1 to 1) to "@00WD000900015B*",
        (1 to 2) to "@00WD0010000153*",
        (1 to 3) to "@00WD0011000152*",
        (1 to 5) to "@00WD0013000150*",
        (1 to 6) to "@00WD0014000157*",
        (2 to 1) to "@00WD0009000258*",
        (2 to 2) to "@00WD0010000250*",
        (2 to 3) to "@00WD0011000251*",
        (2 to 5) to "@00WD0013000253*",
        (2 to 6) to "@00WD0014000254*",
        (3 to 1) to "@00WD0009000359*",
        (3 to 2) to "@00WD0010000351*",
        (3 to 3) to "@00WD0011000350*",
        (3 to 5) to "@00WD0013000352*",
        (3 to 6) to "@00WD0014000355*"
    )

    /** FREE estación: libera la estación de la cinta. */
    val FREE_STATION_COMMANDS: Map<Int, String> = mapOf(
        1 to "@00WD004800015E*",
        2 to "@00WD004900015F*",
        3 to "@00WD0050000157*"
    )

    /** FREE pallet: libera el pallet específico. */
    val FREE_PALLET_COMMANDS: Map<Int, String> = mapOf(
        1 to "@00WD000900995A*",
        2 to "@00WD0010009952*",
        3 to "@00WD0011009953*",
        5 to "@00WD0013009951*",
        6 to "@00WD0014009956*"
    )

    val STATIONS = listOf(1, 2, 3)
    val PALLETS  = listOf(1, 2, 3, 5, 6)

    /** Regex para parsear respuestas EX del PLC (estación + pallet). */
    val EX_RESPONSE_REGEX = Regex("EX(\\d{4})(\\d{4})")

    data class ExResponse(val station: Int, val pallet: Int)

    /** Parsea todos los eventos EX de una cadena de respuesta del PLC. */
    fun parseExResponses(raw: String): List<ExResponse> {
        return EX_RESPONSE_REGEX.findAll(raw.uppercase()).mapNotNull { match ->
            val station = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val pallet = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            ExResponse(station, pallet)
        }.toList()
    }

    fun deliverCommand(station: Int, pallet: Int): String? =
        DELIVER_COMMANDS[station to pallet]

    fun freeCommands(station: Int, pallet: Int): Pair<String, String>? {
        val cmdStation = FREE_STATION_COMMANDS[station] ?: return null
        val cmdPallet  = FREE_PALLET_COMMANDS[pallet] ?: return null
        return cmdStation to cmdPallet
    }
}
