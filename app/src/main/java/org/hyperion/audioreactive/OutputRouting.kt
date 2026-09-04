package org.hyperion.audioreactive

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** One-shot in-memory handoff from read-only WLED preflight to the capture service. */
internal object WledRouteBindings {
    private var nextId = 0L
    /** The route and every capture-shaping setting are frozen at preflight time. */
    private data class Binding(val settings: AudioSettings, val devices: List<WledDevice>)
    private val bindings = mutableMapOf<String, Binding>()

    @Synchronized fun bind(settings: AudioSettings, fresh: List<WledDevice>): String? {
        if (settings.outputMode != OutputMode.WLED) return null
        val selected = settings.selectedWledDevices()
        if (selected.isEmpty() || fresh.size != selected.size) return null
        val selectedById = selected.associateBy { it.identity }
        if (selectedById.size != selected.size || fresh.map { it.identity }.toSet() != selectedById.keys || fresh.any { it.identity !in selectedById || !it.valid() }) return null
        if (fresh.any { candidate -> candidate != selectedById[candidate.identity] }) return null
        if (settings.requiresVideo() && fresh.any { !WledCalibrationPolicy.routeable(settings, it) }) return null
        val id = (++nextId).toString()
        bindings[id] = Binding(settings, fresh)
        return id
    }

    @Synchronized fun has(id: String?, settings: AudioSettings): Boolean = id != null && bindings[id]?.let { it.settings == settings && it.devices.isNotEmpty() } == true
    /** A binding is consumed exactly once, so an old intent cannot route a later capture. */
    @Synchronized fun consume(id: String?, settings: AudioSettings): List<WledDevice>? = id?.let { bindings.remove(it) }?.takeIf { it.settings == settings }?.devices
    @Synchronized fun discard(id: String?) { if (id != null) bindings.remove(id) }
}

/** Read-only, bounded preflight seam. It never creates a UDP socket or output object. */
internal object WledCapturePreflight {
    /** Video is all-or-nothing: every selected WLED target needs valid matching calibration. */
    fun eligible(settings: AudioSettings): Boolean = settings.outputMode == OutputMode.WLED &&
        settings.selectedWledDevices().let { selected -> selected.isNotEmpty() && (!settings.requiresVideo() || selected.all { WledCalibrationPolicy.routeable(settings, it) }) }
    fun bind(settings: AudioSettings, revalidate: (Collection<WledDevice>) -> List<WledDevice> = WledDiscovery::revalidate): String? {
        if (!eligible(settings)) return null
        return WledRouteBindings.bind(settings, revalidate(settings.selectedWledDevices()))
    }
}

/** One-shot Hyperion handoff; identity and endpoints must exactly match the persisted selected record. */
internal object HyperionRouteBindings {
    private data class Binding(val settings: AudioSettings, val device: HyperionDevice)
    private var nextId = 0L; private val bindings = mutableMapOf<String, Binding>()
    @Synchronized fun bind(settings: AudioSettings, fresh: List<HyperionDevice>): String? {
        if (settings.outputMode != OutputMode.HYPERION) return null
        val selected = settings.selectedHyperionDevice() ?: return null
        val candidate = fresh.singleOrNull() ?: return null
        if (candidate != selected || !candidate.valid()) return null
        return (++nextId).toString().also { bindings[it] = Binding(settings, candidate) }
    }
    @Synchronized fun has(id: String?, settings: AudioSettings) = id != null && bindings[id]?.settings == settings
    @Synchronized fun consume(id: String?, settings: AudioSettings): HyperionDevice? = id?.let { bindings.remove(it) }?.takeIf { it.settings == settings }?.device
    @Synchronized fun discard(id: String?) { if (id != null) bindings.remove(id) }
}
internal object HyperionCapturePreflight {
    fun bind(settings: AudioSettings, revalidate: (Collection<HyperionDevice>) -> List<HyperionDevice> = HyperionDiscovery::revalidate): String? {
        if (settings.outputMode != OutputMode.HYPERION) return null
        return HyperionRouteBindings.bind(settings, settings.selectedHyperionDevice()?.let { revalidate(listOf(it)) } ?: emptyList())
    }
}

enum class OutputMode(val label: String) { HYPERION("Hyperion"), WLED("WLED") }

object SliderFormatters {
    fun sensitivity(value: Float) = String.format(java.util.Locale.US, "%.2f×", value)
    fun brightness(value: Float) = "${(value * 100f).toInt()}%"
    fun fps(value: Int) = "$value fps"
}

internal interface HyperionOutput { fun register(); fun send(frame: ByteArray); fun clear(); fun close() }
internal class HyperionRouteOutput(device: HyperionDevice, spec: SourceFrameSpec) : HyperionOutput {
    private val client = HyperionClient(device.host, device.dataPort, spec.width, spec.height)
    override fun register() = client.register()
    override fun send(frame: ByteArray) = client.sendImage(frame)
    override fun clear() { client.clearBestEffort() }
    override fun close() = client.close()
}

internal interface WledOutput { fun send(sourceFrame: ByteArray); fun requiresNativeFrame(): Boolean = false; fun blackout(); fun close() }
/**
 * Bounded WLED source-frame adapter. It owns one RGB24 zone buffer selected before capture;
 * video is spatially reduced into it and audio is copied into it. No device configuration or
 * capture-frame allocation occurs here.
 */
internal class WledSourceFrame(private val input: SourceFrameSpec, private val zones: Int) {
    private val frame = ByteArray(zones * 3)
    init { require(zones in 16..512 && zones % 16 == 0); require(input.bytes <= HyperionFlatbuffer.MAX_IMAGE_BYTES) }
    fun write(rgb24: ByteArray): ByteArray {
        require(rgb24.size == input.bytes)
        if (input.width == zones && input.height == 1) rgb24.copyInto(frame)
        else for (zone in 0 until zones) {
            val startX = zone * input.width / zones
            val endX = ((zone + 1) * input.width / zones).coerceAtLeast(startX + 1).coerceAtMost(input.width)
            var r = 0; var g = 0; var b = 0; var count = 0
            for (y in 0 until input.height) for (x in startX until endX) {
                val at = (y * input.width + x) * 3
                r += rgb24[at].toInt() and 255; g += rgb24[at + 1].toInt() and 255; b += rgb24[at + 2].toInt() and 255; count++
            }
            val at = zone * 3; frame[at] = (r / count).toByte(); frame[at + 1] = (g / count).toByte(); frame[at + 2] = (b / count).toByte()
        }
        return frame
    }
}
/** Owns preallocated UDP packet buffers; capture-frame sends allocate nothing. */
internal class WledRealtimePackets(address: InetAddress, port: Int, private val leds: Int, private val sourceZones: Int = 16) {
    private val realtimeData = ByteArray(2 + leds * 3).also { it[0] = 2; it[1] = 2 }
    private val blackoutData = ByteArray(realtimeData.size).also { it[0] = 2; it[1] = 2 }
    val realtime = DatagramPacket(realtimeData, realtimeData.size, address, port)
    val blackout = DatagramPacket(blackoutData, blackoutData.size, address, port)
    fun updateRealtime(sourceFrame: ByteArray) = resampleTo(sourceFrame, sourceZones, realtimeData, 2, leds)
}
internal class WledRealtimeOutput(device: WledDevice, sourceZones: Int = 16, calibration: WledScreenCalibration? = null, input: SourceFrameSpec? = null) : WledOutput {
    private val mapper = calibration?.let { WledPerimeterMapper(requireNotNull(input), it) }
    private val packets = WledRealtimePackets(InetAddress.getByName(device.host), device.realtimePort, device.leds, if (mapper == null) sourceZones else device.leds)
    private val socket = DatagramSocket()
    override fun requiresNativeFrame() = mapper != null
    override fun send(sourceFrame: ByteArray) { packets.updateRealtime(mapper?.map(sourceFrame) ?: sourceFrame); socket.send(packets.realtime) }
    override fun blackout() { socket.send(packets.blackout) }
    override fun close() = socket.close()
}

fun resample16To(source: ByteArray, destination: ByteArray, offset: Int, leds: Int) {
    resampleTo(source, 16, destination, offset, leds)
}
fun resampleTo(source: ByteArray, sourceZones: Int, destination: ByteArray, offset: Int, leds: Int) {
    require(sourceZones in 1..512 && source.size == sourceZones * 3 && destination.size >= offset + leds * 3)
    for (i in 0 until leds) { val from = ((i * sourceZones) / leds) * 3; val to = offset + i * 3; destination[to] = source[from]; destination[to + 1] = source[from + 1]; destination[to + 2] = source[from + 2] }
}

/** Created only after approved MediaProjection and AudioRecord are active. */
internal class OutputRouter private constructor(private val mode: OutputMode, private val hyperion: HyperionOutput?, private val wled: Array<WledOutput>, private val wledSource: WledSourceFrame? = null) {
    private var started = false; private var stopped = false; private var hyperionActivated = false; private val wledActivated = BooleanArray(wled.size)
    @Synchronized fun start() {
        if (started || stopped) return
        try { if (mode == OutputMode.HYPERION) hyperion?.register(); hyperionActivated = mode == OutputMode.HYPERION && hyperion != null; started = true } catch (failure: Exception) { stop(); throw failure }
    }
    @Synchronized fun send(frame: ByteArray) {
        if (!started || stopped) return
        if (mode == OutputMode.HYPERION) hyperion?.send(frame) else {
            var i = 0
            var hasLegacyOutput = false
            // Calibration samples the native capture dimensions.  Do this before (and, for an
            // all-calibrated route, instead of) the legacy horizontal source-zone reduction.
            while (i < wled.size) {
                if (wled[i].requiresNativeFrame()) { wledActivated[i] = true; wled[i].send(frame) }
                else hasLegacyOutput = true
                i++
            }
            if (hasLegacyOutput) {
                val source = wledSource?.write(frame) ?: frame
                i = 0
                while (i < wled.size) {
                    if (!wled[i].requiresNativeFrame()) { wledActivated[i] = true; wled[i].send(source) }
                    i++
                }
            }
        }
    }
    @Synchronized fun stop() {
        if (stopped) return
        stopped = true; started = false
        if (hyperionActivated) runCatching { hyperion?.clear() }
        var i = 0
        while (i < wled.size) { if (wledActivated[i]) runCatching { wled[i].blackout() }; runCatching { wled[i].close() }; i++ }
        runCatching { hyperion?.close() }; hyperionActivated = false
    }
    companion object {
        /** Both routes require exactly one fresh, one-shot binding and never reread mutable endpoints. */
        fun create(settings: AudioSettings, wledBindingId: String? = null, hyperionBindingId: String? = null): OutputRouter = when (settings.outputMode) {
            OutputMode.HYPERION -> {
                val fresh = HyperionRouteBindings.consume(hyperionBindingId, settings) ?: throw IllegalStateException("Missing fresh Hyperion route binding")
                OutputRouter(OutputMode.HYPERION, HyperionRouteOutput(fresh, settings.sourceFrame()), emptyArray())
            }
            OutputMode.WLED -> {
                val fresh = WledRouteBindings.consume(wledBindingId, settings)
                    ?.takeIf { it.isNotEmpty() && it.all(WledDevice::valid) }
                    ?: throw IllegalStateException("Missing fresh WLED route binding")
                val input = settings.captureFrame()
                OutputRouter(OutputMode.WLED, null, fresh.map { device -> WledRealtimeOutput(device, settings.wledSourceZones, if (settings.requiresVideo()) settings.calibrationFor(device) else null, input) }.toTypedArray(), WledSourceFrame(input, settings.wledSourceZones))
            }
        }
        internal fun forTest(mode: OutputMode, hyperion: HyperionOutput? = null, wled: Array<WledOutput> = emptyArray(), wledSource: WledSourceFrame? = null) = OutputRouter(mode, hyperion, wled, wledSource)
    }
}
