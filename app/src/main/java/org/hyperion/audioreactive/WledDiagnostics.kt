package org.hyperion.audioreactive

/** Physical, bounded WLED diagnostics. The caller must select exactly this MAC; nothing persists or reconfigures WLED. */
enum class WledDiagnosticPattern(val label: String) {
    SINGLE_PIXEL_CHASE("Один піксель: chase"),
    DIRECTION_CHASE("Напрямок: chase"),
    FOUR_EDGE_COLORS("Чотири краї: кольори"),
    CORNER_MARKERS("Маркери кутів")
}

object WledDiagnosticPackets {
    private const val MAX_FRAMES = 16
    fun frames(calibration: WledScreenCalibration, pattern: WledDiagnosticPattern): List<ByteArray> {
        require(calibration.validFor())
        val count = calibration.physicalLedCount
        fun frame(): ByteArray = ByteArray(count * 3)
        fun put(bytes: ByteArray, logical: Int, r: Int, g: Int, b: Int) {
            val index = if (calibration.direction == PerimeterDirection.CW)
                (calibration.startPixel + logical) % count else (calibration.startPixel - logical + count) % count
            val at = index * 3; bytes[at] = r.toByte(); bytes[at + 1] = g.toByte(); bytes[at + 2] = b.toByte()
        }
        return when (pattern) {
            WledDiagnosticPattern.SINGLE_PIXEL_CHASE -> (0 until minOf(count, MAX_FRAMES)).map { n -> frame().also { put(it, n, 255, 255, 255) } }
            WledDiagnosticPattern.DIRECTION_CHASE -> (0 until minOf(count, MAX_FRAMES)).map { n -> frame().also { put(it, n, 0, 255, 255); put(it, (n + 1) % count, 255, 0, 255) } }
            WledDiagnosticPattern.FOUR_EDGE_COLORS -> listOf(frame().also { bytes ->
                var logical = 0
                arrayOf(intArrayOf(255, 0, 0), intArrayOf(0, 255, 0), intArrayOf(0, 0, 255), intArrayOf(255, 255, 0)).forEachIndexed { index, color ->
                    repeat(calibration.allocation(ScreenEdge.entries[index])) { put(bytes, logical++, color[0], color[1], color[2]) }
                }
            })
            WledDiagnosticPattern.CORNER_MARKERS -> listOf(frame().also { bytes ->
                var logical = 0
                val colors = arrayOf(intArrayOf(255, 0, 0), intArrayOf(0, 255, 0), intArrayOf(0, 0, 255), intArrayOf(255, 255, 255))
                ScreenEdge.entries.forEachIndexed { index, edge ->
                    if (calibration.allocation(edge) > 0) put(bytes, logical, colors[index][0], colors[index][1], colors[index][2])
                    logical += calibration.allocation(edge)
                }
            })
        }
    }
}

internal object WledDiagnosticAction {
    internal interface Output { fun send(frame: ByteArray); fun blackout(); fun close() }
    /** Fresh read-only preflight creates and consumes a one-shot binding before any UDP output. */
    fun execute(
        settings: AudioSettings,
        device: WledDevice,
        calibration: WledScreenCalibration,
        pattern: WledDiagnosticPattern,
        preflight: (AudioSettings) -> String? = WledCapturePreflight::bind,
        create: (WledDevice) -> Output = { target ->
            WledRealtimeOutput(target, sourceZones = target.leds).let { output -> object : Output {
                override fun send(frame: ByteArray) = output.send(frame)
                override fun blackout() = output.blackout()
                override fun close() = output.close()
            } }
        },
    ): Boolean {
        if (settings.outputMode != OutputMode.WLED || !calibration.validFor(device)) return false
        // A wizard draft is not persisted until the user saves it.  Scope it to this one
        // read-only preflight only, so diagnostics validate the exact draft without relaxing
        // production capture routeability or altering any saved calibration.
        val onlyTarget = settings.copy(
            selectedWledIdentities = setOf(device.identity),
            wledCalibrations = listOf(calibration),
        )
        val binding = preflight(onlyTarget) ?: return false
        val fresh = WledRouteBindings.consume(binding, onlyTarget)?.singleOrNull() ?: return false
        if (fresh != device) return false
        val output = create(fresh)
        try { WledDiagnosticPackets.frames(calibration, pattern).forEach(output::send) }
        finally { try { output.blackout() } finally { output.close() } }
        return true
    }
}
