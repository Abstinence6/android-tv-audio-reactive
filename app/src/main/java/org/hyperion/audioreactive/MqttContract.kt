package org.hyperion.audioreactive

/** Pure HA MQTT contract. Only the two explicit command topics are ever subscribed to. */
object MqttContract {
    const val BROKER_URI = "tcp://192.168.1.1:1883"
    const val DEVICE_ID = "audio_reactive_tv"
    const val ROOT = "audio_reactive_tv"
    const val DISCOVERY_ROOT = "homeassistant"
    const val AVAILABILITY = "$ROOT/availability"
    const val STATUS = "$ROOT/status"
    const val CAPTURE_STATE = "$ROOT/capture/state"
    const val CAPTURE_COMMAND = "$ROOT/capture/set"
    const val EFFECT_STATE = "$ROOT/effect/state"
    const val EFFECT_COMMAND = "$ROOT/effect/set"
    const val CAPTURE_DISCOVERY = "$DISCOVERY_ROOT/switch/$DEVICE_ID/capture/config"
    const val EFFECT_DISCOVERY = "$DISCOVERY_ROOT/select/$DEVICE_ID/effect/config"
    const val DIAGNOSTIC_DISCOVERY = "$DISCOVERY_ROOT/sensor/$DEVICE_ID/parameters/config"
    const val DIAGNOSTIC_STATE = "$ROOT/diagnostic/state"
    const val DIAGNOSTIC_ATTRIBUTES = "$ROOT/diagnostic/attributes"

    data class Publication(val topic: String, val payload: String, val retained: Boolean = true)
    data class DiagnosticRuntime(
        val captureActive: Boolean,
        val captureStatus: String,
        val detail: String,
        val appVersion: String = "unknown",
        val deviceName: String = "unknown",
    )
    sealed interface Command { data object On : Command; data object Off : Command; data class SetEffect(val value: Effect) : Command }

    fun validBroker(uri: String) = uri == BROKER_URI
    fun isAllowedTopic(topic: String) = topic == CAPTURE_COMMAND || topic == EFFECT_COMMAND
    fun parseCommand(topic: String, payload: String, retained: Boolean): Command? {
        if (retained || !isAllowedTopic(topic) || payload.length !in 1..32) return null
        return when (topic) {
            CAPTURE_COMMAND -> when (payload.trim()) { "ON" -> Command.On; "OFF" -> Command.Off; else -> null }
            EFFECT_COMMAND -> Effect.entries.firstOrNull { it.name == payload.trim() }?.let(Command::SetEffect)
            else -> null
        }
    }

    fun snapshot(settings: AudioSettings, runtime: DiagnosticRuntime): List<Publication> = listOf(
        Publication(AVAILABILITY, "online"),
        Publication(CAPTURE_DISCOVERY, captureDiscovery()),
        Publication(EFFECT_DISCOVERY, effectDiscovery()),
        Publication(DIAGNOSTIC_DISCOVERY, diagnosticDiscovery()),
        Publication(CAPTURE_STATE, if (runtime.captureActive) "ON" else "OFF"),
        Publication(EFFECT_STATE, settings.effect.name),
        Publication(STATUS, runtime.detail.take(160)),
        Publication(DIAGNOSTIC_STATE, runtime.captureStatus),
        Publication(DIAGNOSTIC_ATTRIBUTES, diagnosticAttributes(settings, runtime)),
    )

    /** Compatibility seam for callers that have not yet collected the lifecycle enum. */
    fun snapshot(settings: AudioSettings, captureActive: Boolean, detail: String) =
        snapshot(settings, DiagnosticRuntime(captureActive, if (captureActive) "CAPTURE_ACTIVE" else "NEEDS_MEDIA_PROJECTION_CONSENT", detail))

    fun offline() = Publication(AVAILABILITY, "offline")

    private fun captureDiscovery() = """{"name":"Audio reactive capture","unique_id":"${DEVICE_ID}_capture","state_topic":"$CAPTURE_STATE","command_topic":"$CAPTURE_COMMAND","availability_topic":"$AVAILABILITY","payload_on":"ON","payload_off":"OFF","device":{"identifiers":["$DEVICE_ID"],"name":"Audio Reactive TV","manufacturer":"Local"}}"""
    private fun effectDiscovery() = """{"name":"Audio reactive effect","unique_id":"${DEVICE_ID}_effect","state_topic":"$EFFECT_STATE","command_topic":"$EFFECT_COMMAND","availability_topic":"$AVAILABILITY","options":[${Effect.entries.joinToString(",") { "\"${it.name}\"" }}],"device":{"identifiers":["$DEVICE_ID"]}}"""
    private fun diagnosticDiscovery() = """{"name":"Audio reactive parameters","unique_id":"${DEVICE_ID}_parameters","state_topic":"$DIAGNOSTIC_STATE","json_attributes_topic":"$DIAGNOSTIC_ATTRIBUTES","availability_topic":"$AVAILABILITY","entity_category":"diagnostic","icon":"mdi:tune-variant","device":{"identifiers":["$DEVICE_ID"]}}"""

    private fun diagnosticAttributes(settings: AudioSettings, runtime: DiagnosticRuntime): String {
        val selectedWled = settings.selectedWledIdentities
        val selectedHyperion = settings.selectedHyperionIdentity
        return jsonObject(
            "capture_active" to runtime.captureActive.toString(),
            "capture_status" to jsonString(runtime.captureStatus),
            "capture_detail" to jsonString(runtime.detail.take(160)),
            "app_version" to jsonString(runtime.appVersion),
            "device_name" to jsonString(runtime.deviceName),
            "render_mode" to jsonString(settings.renderMode.name),
            "output_mode" to jsonString(settings.outputMode.name),
            "effect" to jsonString(settings.effect.name),
            "video_effect" to jsonString(settings.videoEffect.name),
            "video_audio_effect" to jsonString(settings.videoAudioEffect.name),
            "brightness" to settings.brightness.toString(),
            "sensitivity" to settings.sensitivity.toString(),
            "fps" to settings.fps.toString(),
            "audio_boost" to settings.audioBoost.toString(),
            "video_quality" to jsonString(settings.videoQuality.name),
            "video_saturation_percent" to settings.videoSaturationPercent.toString(),
            "wled_source_zones" to settings.wledSourceZones.toString(),
            "capture_frame" to jsonObject("width" to settings.captureFrame().width.toString(), "height" to settings.captureFrame().height.toString(), "fps" to settings.captureFrame().fps.toString()),
            "effect_parameters" to jsonObject("speed" to settings.effectParameters.speed.toString(), "trail" to settings.effectParameters.trail.toString(), "beat_threshold" to settings.effectParameters.beatThreshold.toString(), "hue_shift" to settings.effectParameters.hueShift.toString()),
            "wled_devices" to jsonArray(settings.wledDevices.map { device ->
                val calibration = settings.calibrationFor(device)
                jsonObject(
                    "identity" to jsonString(device.identity), "name" to jsonString(device.name),
                    "selected" to (device.identity in selectedWled).toString(), "led_count" to device.leds.toString(),
                    "calibration_status" to jsonString(if (calibration?.validFor(device) == true) "valid" else if (calibration == null) "missing" else "invalid"),
                    "calibration" to (calibration?.let(::calibrationJson) ?: "null"),
                )
            }),
            "selected_hyperion_identity" to (selectedHyperion?.let(::jsonString) ?: "null"),
            "hyperion_devices" to jsonArray(settings.hyperionDevices.map { device ->
                jsonObject("identity" to jsonString(device.identity), "name" to jsonString(device.name), "selected" to (device.identity == selectedHyperion).toString())
            }),
        )
    }

    private fun calibrationJson(value: WledScreenCalibration) = jsonObject(
        "direction" to jsonString(value.direction.name), "physical_led_count" to value.physicalLedCount.toString(), "start_pixel" to value.startPixel.toString(),
        "bottom" to value.bottom.toString(), "right" to value.right.toString(), "top" to value.top.toString(), "left" to value.left.toString(),
        "bottom_inset_percent" to value.bottomInsetPercent.toString(), "right_inset_percent" to value.rightInsetPercent.toString(), "top_inset_percent" to value.topInsetPercent.toString(), "left_inset_percent" to value.leftInsetPercent.toString(),
        "depth_percent" to value.depthPercent.toString(), "samples_per_edge" to value.samplesPerEdge.toString(), "gamma" to value.gamma.toString(), "brightness_limit" to value.brightnessLimit.toString(),
    )
    private fun jsonObject(vararg fields: Pair<String, String>) = fields.joinToString(prefix = "{", postfix = "}") { "\"${it.first}\":${it.second}" }
    private fun jsonArray(values: List<String>) = values.joinToString(prefix = "[", postfix = "]")
    private fun jsonString(value: String) = "\"" + buildString(value.length) { value.forEach { char -> when (char) { '\\' -> append("\\\\"); '"' -> append("\\\""); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t"); else -> if (char.code < 32) append("\\u%04x".format(char.code)) else append(char) } } } + "\""
}

/** Command mutation policy; MQTT ON is informational and never opens Android consent/capture. */
object MqttCommandPolicy {
    sealed interface Action { data object ReportConsentRequired : Action; data object StopOwnedCapture : Action; data class ChangeEffect(val effect: Effect) : Action; data object Ignore : Action }
    fun decide(command: MqttContract.Command?, captureActive: Boolean): Action = when (command) {
        MqttContract.Command.On -> Action.ReportConsentRequired
        MqttContract.Command.Off -> if (captureActive) Action.StopOwnedCapture else Action.Ignore
        is MqttContract.Command.SetEffect -> MqttCommandPolicy.Action.ChangeEffect(command.value)
        null -> Action.Ignore
    }
}
