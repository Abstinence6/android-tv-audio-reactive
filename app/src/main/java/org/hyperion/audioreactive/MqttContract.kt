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
    /** Legacy bounded JSON command channel; HA entities use their own strict command topics. */
    const val SETTINGS_COMMAND = "$ROOT/settings/set"
    const val SETTINGS_STATE = "$ROOT/settings/state"

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
    sealed interface Command { data object On : Command; data object Off : Command; data class SetEffect(val value: Effect) : Command; data class SetSetting(val field: String, val value: String) : Command }

    fun validBroker(uri: String) = uri == BROKER_URI
    private val settingFields = listOf(
        "effect", "brightness", "sensitivity", "fps", "output_mode", "render_mode", "video_quality", "audio_boost", "wled_source_zones", "video_effect", "video_audio_effect", "video_saturation_percent", "speed", "trail", "beat_threshold", "hue_shift",
    )
    fun settingStateTopic(field: String) = "$ROOT/settings/$field/state"
    fun settingCommandTopic(field: String) = "$ROOT/settings/$field/set"
    fun settingDiscoveryTopic(field: String) = "$DISCOVERY_ROOT/${if (field in numericSettings) "number" else "select"}/$DEVICE_ID/$field/config"
    private fun wledId(identity: String) = identity.removePrefix("mac:").lowercase()
    fun wledRouteStateTopic(identity: String) = "$ROOT/routes/wled/${wledId(identity)}/state"
    fun wledRouteCommandTopic(identity: String) = "$ROOT/routes/wled/${wledId(identity)}/set"
    fun wledRouteDiscoveryTopic(identity: String) = "$DISCOVERY_ROOT/switch/$DEVICE_ID/wled_${wledId(identity)}/config"
    fun hyperionRouteStateTopic() = "$ROOT/routes/hyperion/state"
    fun hyperionRouteCommandTopic() = "$ROOT/routes/hyperion/set"
    fun calibrationStateTopic(identity: String, field: String) = "$ROOT/calibration/wled/${wledId(identity)}/$field/state"
    fun calibrationCommandTopic(identity: String, field: String) = "$ROOT/calibration/wled/${wledId(identity)}/$field/set"
    fun calibrationDiscoveryTopic(identity: String, field: String) = "$DISCOVERY_ROOT/${if (field in calibrationNumericFields) "number" else "select"}/$DEVICE_ID/wled_${wledId(identity)}_$field/config"
    fun isAllowedTopic(topic: String) = topic == CAPTURE_COMMAND || topic == EFFECT_COMMAND || topic == SETTINGS_COMMAND ||
        settingFields.any { topic == settingCommandTopic(it) } || topic == hyperionRouteCommandTopic() ||
        WLED_ROUTE_TOPIC.matches(topic) || CALIBRATION_TOPIC.matches(topic)
    fun parseCommand(topic: String, payload: String, retained: Boolean): Command? {
        if (retained || !isAllowedTopic(topic) || payload.length !in 1..256) return null
        return when (topic) {
            CAPTURE_COMMAND -> when (payload.trim()) { "ON" -> Command.On; "OFF" -> Command.Off; else -> null }
            EFFECT_COMMAND -> Effect.entries.firstOrNull { it.name == payload.trim() }?.let(Command::SetEffect)
            SETTINGS_COMMAND -> parseSetting(payload)
            hyperionRouteCommandTopic() -> payload.trim().takeIf { it == "none" || it.matches(HYPERION_IDENTITY) }?.let { Command.SetSetting("selected_hyperion_identity", it) }
            else -> parseEntityCommand(topic, payload.trim())
        }
    }
    private fun parseEntityCommand(topic: String, value: String): Command.SetSetting? {
        if (!value.matches(SETTING_VALUE)) return null
        settingFields.firstOrNull { topic == settingCommandTopic(it) }?.let { return Command.SetSetting(it, value) }
        WLED_ROUTE_TOPIC.matchEntire(topic)?.let { match ->
            return value.takeIf { it == "ON" || it == "OFF" }?.let { Command.SetSetting("selected_wled_identities", "mac:${match.groupValues[1].uppercase()},$it") }
        }
        CALIBRATION_TOPIC.matchEntire(topic)?.let { match ->
            val field = "calibration_${match.groupValues[2]}"
            return field.takeIf { it in MqttSettingsPolicy.fields }?.let { Command.SetSetting(it, "mac:${match.groupValues[1].uppercase()},$value") }
        }
        return null
    }
    /** Strict two-string JSON shape, with a closed field whitelist; malformed JSON never reaches settings. */
    private fun parseSetting(payload: String): Command.SetSetting? {
        val match = Regex("""^\{\s*"field"\s*:\s*"([a-z_]+)"\s*,\s*"value"\s*:\s*"([A-Za-z0-9:,.+_-]{1,96})"\s*}$""").matchEntire(payload) ?: return null
        val (field, value) = match.destructured
        return field.takeIf { it in MqttSettingsPolicy.fields }?.let { Command.SetSetting(it, value) }
    }

    fun snapshot(settings: AudioSettings, runtime: DiagnosticRuntime): List<Publication> = listOf(
        Publication(AVAILABILITY, "online"),
        Publication(CAPTURE_DISCOVERY, captureDiscovery()),
        Publication(EFFECT_DISCOVERY, effectDiscovery()),
        Publication(DIAGNOSTIC_DISCOVERY, diagnosticDiscovery()),
        *settingsPublications(settings).toTypedArray(),
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
    /** Per-setting HA entities are controllable; aggregate JSON remains diagnostic-only. */
    private fun settingsPublications(settings: AudioSettings): List<Publication> = buildList {
        settingFields.forEach { field ->
            add(Publication(settingDiscoveryTopic(field), settingDiscovery(field)))
            add(Publication(settingStateTopic(field), settingValue(settings, field)))
        }
        add(Publication("$DISCOVERY_ROOT/select/$DEVICE_ID/hyperion_route/config", selectDiscovery("Hyperion route", "hyperion_route", hyperionRouteStateTopic(), hyperionRouteCommandTopic(), listOf("none") + settings.hyperionDevices.map { it.identity })))
        add(Publication(hyperionRouteStateTopic(), settings.selectedHyperionIdentity ?: "none"))
        settings.wledDevices.forEach { device ->
            add(Publication(wledRouteDiscoveryTopic(device.identity), switchDiscovery("WLED route ${device.name}", "wled_${wledId(device.identity)}", wledRouteStateTopic(device.identity), wledRouteCommandTopic(device.identity))))
            add(Publication(wledRouteStateTopic(device.identity), if (device.identity in settings.selectedWledIdentities) "ON" else "OFF"))
            val calibration = settings.calibrationFor(device) ?: WledScreenCalibration.proportional(device.identity, device.leds)
            calibrationFields.forEach { field ->
                add(Publication(calibrationDiscoveryTopic(device.identity, field), calibrationDiscovery(device, field)))
                add(Publication(calibrationStateTopic(device.identity, field), calibrationValue(calibration, field)))
            }
        }
    }
    private fun settingDiscovery(field: String): String = if (field in numericSettings) numberDiscovery(field.replace('_', ' '), field, settingStateTopic(field), settingCommandTopic(field), numericSettings.getValue(field)) else selectDiscovery(field.replace('_', ' '), field, settingStateTopic(field), settingCommandTopic(field), settingOptions(field))
    private fun settingValue(settings: AudioSettings, field: String) = when (field) {
        "effect" -> settings.effect.name; "brightness" -> settings.brightness.toString(); "sensitivity" -> settings.sensitivity.toString(); "fps" -> settings.fps.toString(); "output_mode" -> settings.outputMode.name; "render_mode" -> settings.renderMode.name; "video_quality" -> settings.videoQuality.name; "audio_boost" -> settings.audioBoost.toString(); "wled_source_zones" -> settings.wledSourceZones.toString(); "video_effect" -> settings.videoEffect.name; "video_audio_effect" -> settings.videoAudioEffect.name; "video_saturation_percent" -> settings.videoSaturationPercent.toString(); "speed" -> settings.effectParameters.speed.toString(); "trail" -> settings.effectParameters.trail.toString(); "beat_threshold" -> settings.effectParameters.beatThreshold.toString(); else -> settings.effectParameters.hueShift.toString()
    }
    private fun settingsState(settings: AudioSettings) = jsonObject(
        "effect" to jsonString(settings.effect.name), "brightness" to settings.brightness.toString(), "sensitivity" to settings.sensitivity.toString(), "fps" to settings.fps.toString(),
        "output_mode" to jsonString(settings.outputMode.name), "render_mode" to jsonString(settings.renderMode.name), "video_quality" to jsonString(settings.videoQuality.name),
        "audio_boost" to settings.audioBoost.toString(), "wled_source_zones" to settings.wledSourceZones.toString(), "video_effect" to jsonString(settings.videoEffect.name),
        "video_audio_effect" to jsonString(settings.videoAudioEffect.name), "video_saturation_percent" to settings.videoSaturationPercent.toString(),
        "effect_parameters" to jsonObject("speed" to settings.effectParameters.speed.toString(), "trail" to settings.effectParameters.trail.toString(), "beat_threshold" to settings.effectParameters.beatThreshold.toString(), "hue_shift" to settings.effectParameters.hueShift.toString()),
        "selected_wled_identities" to jsonArray(settings.selectedWledIdentities.sorted().map(::jsonString)), "selected_hyperion_identity" to (settings.selectedHyperionIdentity?.let(::jsonString) ?: "null"),
        "wled_calibrations" to jsonArray(settings.wledCalibrations.map(::calibrationJson)), "known_wled_identities" to jsonArray(settings.wledDevices.map { jsonString(it.identity) }), "known_hyperion_identities" to jsonArray(settings.hyperionDevices.map { jsonString(it.identity) }),
        "command_topic" to jsonString(SETTINGS_COMMAND), "command_format" to jsonString("{\\\"field\\\":\\\"<whitelisted field>\\\",\\\"value\\\":\\\"<bounded value>\\\"}"),
    )
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
    private fun calibrationDiscovery(device: WledDevice, field: String): String = if (field in calibrationNumericFields) numberDiscovery("WLED ${device.name} ${field.replace('_', ' ')}", "wled_${wledId(device.identity)}_$field", calibrationStateTopic(device.identity, field), calibrationCommandTopic(device.identity, field), calibrationNumericFields.getValue(field)) else selectDiscovery("WLED ${device.name} direction", "wled_${wledId(device.identity)}_$field", calibrationStateTopic(device.identity, field), calibrationCommandTopic(device.identity, field), PerimeterDirection.entries.map { it.name })
    private fun calibrationValue(value: WledScreenCalibration, field: String) = when (field) {
        "direction" -> value.direction.name; "start_pixel" -> value.startPixel.toString(); "bottom" -> value.bottom.toString(); "right" -> value.right.toString(); "top" -> value.top.toString(); "left" -> value.left.toString(); "bottom_inset" -> value.bottomInsetPercent.toString(); "right_inset" -> value.rightInsetPercent.toString(); "top_inset" -> value.topInsetPercent.toString(); "left_inset" -> value.leftInsetPercent.toString(); "depth_percent" -> value.depthPercent.toString(); "samples_per_edge" -> value.samplesPerEdge.toString(); "gamma" -> value.gamma.toString(); else -> value.brightnessLimit.toString()
    }
    private fun selectDiscovery(name: String, id: String, state: String, command: String, options: List<String>) = """{"name":"$name","unique_id":"${DEVICE_ID}_$id","state_topic":"$state","command_topic":"$command","options":[${options.joinToString(",") { jsonString(it) } }],"availability_topic":"$AVAILABILITY","entity_category":"config","device":{"identifiers":["$DEVICE_ID"]}}"""
    private fun switchDiscovery(name: String, id: String, state: String, command: String) = """{"name":"$name","unique_id":"${DEVICE_ID}_$id","state_topic":"$state","command_topic":"$command","payload_on":"ON","payload_off":"OFF","availability_topic":"$AVAILABILITY","entity_category":"config","device":{"identifiers":["$DEVICE_ID"]}}"""
    private fun numberDiscovery(name: String, id: String, state: String, command: String, bounds: NumericBounds) = """{"name":"$name","unique_id":"${DEVICE_ID}_$id","state_topic":"$state","command_topic":"$command","min":${bounds.min},"max":${bounds.max},"step":${bounds.step},"mode":"box","availability_topic":"$AVAILABILITY","entity_category":"config","device":{"identifiers":["$DEVICE_ID"]}}"""
    private fun jsonObject(vararg fields: Pair<String, String>) = fields.joinToString(prefix = "{", postfix = "}") { "\"${it.first}\":${it.second}" }
    private fun jsonArray(values: List<String>) = values.joinToString(prefix = "[", postfix = "]")
    private fun jsonString(value: String) = "\"" + buildString(value.length) { value.forEach { char -> when (char) { '\\' -> append("\\\\"); '"' -> append("\\\""); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t"); else -> if (char.code < 32) append("\\u%04x".format(char.code)) else append(char) } } } + "\""

    private data class NumericBounds(val min: String, val max: String, val step: String)
    private fun settingOptions(field: String): List<String> = when (field) {
        "effect" -> Effect.entries.map { it.name }; "fps" -> VideoCapturePolicy.fpsOptions.map(Int::toString); "output_mode" -> OutputMode.entries.map { it.name }; "render_mode" -> RenderMode.entries.map { it.name }; "video_quality" -> VideoQuality.entries.map { it.name }; "video_effect" -> VideoEffect.entries.map { it.name }; "video_audio_effect" -> VideoAudioEffect.entries.map { it.name }; else -> emptyList()
    }
    private val numericSettings = mapOf("brightness" to NumericBounds("0", "1", "0.05"), "sensitivity" to NumericBounds("0.25", "3.25", "0.05"), "audio_boost" to NumericBounds("0", "0.75", "0.05"), "wled_source_zones" to NumericBounds("16", "512", "16"), "video_saturation_percent" to NumericBounds("0", "200", "1"), "speed" to NumericBounds("0.25", "3", "0.25"), "trail" to NumericBounds("0", "1", "0.1"), "beat_threshold" to NumericBounds("0.05", "0.95", "0.05"), "hue_shift" to NumericBounds("-180", "180", "15"))
    private val calibrationFields = listOf("start_pixel", "direction", "bottom", "right", "top", "left", "bottom_inset", "right_inset", "top_inset", "left_inset", "depth_percent", "samples_per_edge", "gamma", "brightness_limit")
    private val calibrationNumericFields = mapOf("start_pixel" to NumericBounds("0", "4095", "1"), "bottom" to NumericBounds("0", "4096", "1"), "right" to NumericBounds("0", "4096", "1"), "top" to NumericBounds("0", "4096", "1"), "left" to NumericBounds("0", "4096", "1"), "bottom_inset" to NumericBounds("0", "45", "1"), "right_inset" to NumericBounds("0", "45", "1"), "top_inset" to NumericBounds("0", "45", "1"), "left_inset" to NumericBounds("0", "45", "1"), "depth_percent" to NumericBounds("2", "25", "1"), "samples_per_edge" to NumericBounds("4", "64", "4"), "gamma" to NumericBounds("1", "3.5", "0.1"), "brightness_limit" to NumericBounds("0.05", "1", "0.05"))
    private val SETTING_VALUE = Regex("[A-Za-z0-9:,.+_-]{1,96}")
    private val HYPERION_IDENTITY = Regex("uuid:[0-9a-fA-F-]{36}")
    private val WLED_ROUTE_TOPIC = Regex("$ROOT/routes/wled/([0-9a-f]{12})/set")
    private val CALIBRATION_TOPIC = Regex("$ROOT/calibration/wled/([0-9a-f]{12})/([a-z_]+)/set")
}

/** Closed, range-checked settings mutation. It cannot create endpoints or start capture/consent. */
object MqttSettingsPolicy {
    val fields = setOf("effect", "brightness", "sensitivity", "fps", "output_mode", "render_mode", "video_quality", "audio_boost", "wled_source_zones", "video_effect", "video_audio_effect", "video_saturation_percent", "speed", "trail", "beat_threshold", "hue_shift", "selected_wled_identities", "selected_hyperion_identity", "calibration_start_pixel", "calibration_direction", "calibration_bottom", "calibration_right", "calibration_top", "calibration_left", "calibration_bottom_inset", "calibration_right_inset", "calibration_top_inset", "calibration_left_inset", "calibration_depth_percent", "calibration_samples_per_edge", "calibration_gamma", "calibration_brightness_limit")
    fun apply(settings: AudioSettings, update: MqttContract.Command.SetSetting): AudioSettings? = runCatching {
        val value = update.value
        fun <T : Enum<T>> enum(entries: Iterable<T>) = entries.firstOrNull { it.name == value } ?: error("enum")
        fun number() = value.toFloatOrNull() ?: error("number")
        val next = when (update.field) {
            "effect" -> settings.copy(effect = enum(Effect.entries))
            "brightness" -> settings.copy(brightness = number())
            "sensitivity" -> settings.copy(sensitivity = number())
            "fps" -> settings.copy(fps = value.toIntOrNull() ?: error("fps"))
            "output_mode" -> settings.copy(outputMode = enum(OutputMode.entries))
            "render_mode" -> settings.copy(renderMode = enum(RenderMode.entries))
            "video_quality" -> settings.copy(videoQuality = enum(VideoQuality.entries))
            "audio_boost" -> settings.copy(audioBoost = number())
            "wled_source_zones" -> settings.copy(wledSourceZones = value.toIntOrNull() ?: error("zones"))
            "video_effect" -> settings.copy(videoEffect = enum(VideoEffect.entries))
            "video_audio_effect" -> settings.copy(videoAudioEffect = enum(VideoAudioEffect.entries))
            "video_saturation_percent" -> settings.copy(videoSaturationPercent = value.toIntOrNull() ?: error("saturation"))
            "speed" -> settings.copy(effectParameters = settings.effectParameters.copy(speed = number()))
            "trail" -> settings.copy(effectParameters = settings.effectParameters.copy(trail = number()))
            "beat_threshold" -> settings.copy(effectParameters = settings.effectParameters.copy(beatThreshold = number()))
            "hue_shift" -> settings.copy(effectParameters = settings.effectParameters.copy(hueShift = number()))
            "selected_wled_identities" -> {
                val route = value.split(',', limit = 2)
                val identities = if (route.size == 2 && route[1] in setOf("ON", "OFF")) {
                    val identity = route[0]
                    if (route[1] == "ON") settings.selectedWledIdentities + identity else settings.selectedWledIdentities - identity
                } else value.split(',').filter(String::isNotBlank).toSet()
                settings.copy(selectedWledIdentities = identities)
            }
            "selected_hyperion_identity" -> settings.copy(selectedHyperionIdentity = value.takeIf { it != "none" })
            else -> updateCalibration(settings, update.field, value)
        }
        next.takeIf { it.valid() } ?: error("invalid")
    }.getOrNull()
    private fun updateCalibration(settings: AudioSettings, field: String, encoded: String): AudioSettings {
        val (identity, raw) = encoded.split(',', limit = 2).takeIf { it.size == 2 } ?: error("calibration")
        val device = settings.wledDevices.firstOrNull { it.identity == identity } ?: error("unknown device")
        val current = settings.calibrationFor(device) ?: WledScreenCalibration.proportional(identity, device.leds)
        val n = raw.toIntOrNull()
        val updated = when (field) {
            "calibration_start_pixel" -> current.copy(startPixel = n ?: error("number"))
            "calibration_direction" -> current.copy(direction = PerimeterDirection.entries.firstOrNull { it.name == raw } ?: error("direction"))
            "calibration_bottom" -> current.copy(bottom = n ?: error("number")); "calibration_right" -> current.copy(right = n ?: error("number")); "calibration_top" -> current.copy(top = n ?: error("number")); "calibration_left" -> current.copy(left = n ?: error("number"))
            "calibration_bottom_inset" -> current.copy(bottomInsetPercent = n ?: error("number")); "calibration_right_inset" -> current.copy(rightInsetPercent = n ?: error("number")); "calibration_top_inset" -> current.copy(topInsetPercent = n ?: error("number")); "calibration_left_inset" -> current.copy(leftInsetPercent = n ?: error("number"))
            "calibration_depth_percent" -> current.copy(depthPercent = n ?: error("number")); "calibration_samples_per_edge" -> current.copy(samplesPerEdge = n ?: error("number")); "calibration_gamma" -> current.copy(gamma = raw.toFloatOrNull() ?: error("number")); "calibration_brightness_limit" -> current.copy(brightnessLimit = raw.toFloatOrNull() ?: error("number"))
            else -> error("field")
        }
        return settings.copy(wledCalibrations = settings.wledCalibrations.filterNot { it.identity == identity } + updated)
    }
}

/** Command mutation policy; MQTT ON is informational and never opens Android consent/capture. */
object MqttCommandPolicy {
    sealed interface Action { data object ReportConsentRequired : Action; data object StopOwnedCapture : Action; data class ChangeEffect(val effect: Effect) : Action; data class ChangeSetting(val update: MqttContract.Command.SetSetting) : Action; data object Ignore : Action }
    fun decide(command: MqttContract.Command?, captureActive: Boolean): Action = when (command) {
        MqttContract.Command.On -> Action.ReportConsentRequired
        MqttContract.Command.Off -> if (captureActive) Action.StopOwnedCapture else Action.Ignore
        is MqttContract.Command.SetEffect -> Action.ChangeEffect(command.value)
        is MqttContract.Command.SetSetting -> Action.ChangeSetting(command)
        null -> Action.Ignore
    }
}
