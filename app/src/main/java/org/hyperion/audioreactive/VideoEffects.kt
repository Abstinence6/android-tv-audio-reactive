package org.hyperion.audioreactive

/** Separate catalogues keep video colour treatment separate from audio-only strip effects. */
enum class VideoEffect {
    NORMAL, SATURATION, CONTRAST
}

enum class VideoAudioEffect {
    BRIGHTNESS_PULSE, BEAT_PULSE, EQ,
    COMET, RIPPLE, BASS_SWEEP,
    SPECTRAL_BANDS, CENTER_BEAT_BURST,
    EDGE_PULSE, STEREO_BALANCE,
    FREQUENCY_GRADIENT, BEAT_STROBE,
    COMET_TRAILS, BASS_WAVE, VOCAL_FOCUS,
    SILENCE_BREATHING, ADAPTIVE_SHIMMER,
    BEAT_COLOUR_TEMPERATURE
}

/** Effects that intentionally need neither playback audio nor screen capture. */
enum class AnimationEffect {
    WATER, LAKE, OCEAN, COMET_STREAM, DOUBLE_COMET, METEOR_SHOWER,
    CANDLE, FIREPLACE, EMBERS, FIREWORKS, FIREWORK_BURSTS, SPARKLER,
    AURORA, NORTHERN_LIGHTS, LAVA_LAMP, NEON_RAIN, STARFIELD, PLASMA,
    RAINBOW_CHASE, COLOUR_WAVES, TWILIGHT, PULSE_GRID
}

enum class AnimationColour(val hue: Float) {
    AUTO(-1f), OCEAN(205f), AQUA(180f), FOREST(125f), VIOLET(280f),
    SUNSET(18f), GOLD(48f), ROSE(340f), ICE(220f), MONOCHROME(Float.NaN)
}

object VideoEffectCatalog {
    fun compatible(mode: RenderMode, effect: String): Boolean = effect in when (mode) {
        RenderMode.AUDIO -> Effect.entries.map { it.name }
        RenderMode.VIDEO -> VideoEffect.entries.map { it.name }
        RenderMode.VIDEO_AUDIO -> VideoAudioEffect.entries.map { it.name }
        RenderMode.ANIMATION -> AnimationEffect.entries.map { it.name }
    }
}

/** Base image treatment stays available in VIDEO_AUDIO beside its audio modulation list. */
object VideoColourTreatmentPolicy {

    fun visible(mode: RenderMode): Boolean = mode == RenderMode.VIDEO || mode == RenderMode.VIDEO_AUDIO
    fun selectedIndex(settings: AudioSettings): Int = settings.videoEffect.ordinal
    fun selection(index: Int): VideoEffect? = VideoEffect.entries.getOrNull(index)
    fun mutable(captureActive: Boolean): Boolean = true
}

/** Pure selector state prevents a stale enum index from crossing capture-mode catalogues. */
object EffectSelectorPolicy {
    fun selectedIndex(settings: AudioSettings): Int = when (settings.renderMode) {
        RenderMode.AUDIO -> settings.effect.ordinal
        RenderMode.VIDEO -> settings.videoEffect.ordinal
        RenderMode.VIDEO_AUDIO -> settings.videoAudioEffect.ordinal
        RenderMode.ANIMATION -> settings.animationEffect.ordinal
    }

    /** Stable machine identifiers used by MQTT and persistence; Android UI uses [UiStrings]. */
    fun names(settings: AudioSettings): List<String> = when (settings.renderMode) {
        RenderMode.AUDIO -> Effect.entries.map { it.name }
        RenderMode.VIDEO -> VideoEffect.entries.map { it.name }
        RenderMode.VIDEO_AUDIO -> VideoAudioEffect.entries.map { it.name }
        RenderMode.ANIMATION -> AnimationEffect.entries.map { it.name }
    }

    fun activeName(settings: AudioSettings): String = when (settings.renderMode) {
        RenderMode.AUDIO -> settings.effect.name
        RenderMode.VIDEO -> settings.videoEffect.name
        RenderMode.VIDEO_AUDIO -> settings.videoAudioEffect.name
        RenderMode.ANIMATION -> settings.animationEffect.name
    }

    /** A mode-local command cannot accidentally write an effect from a different catalogue. */
    fun withActiveName(settings: AudioSettings, name: String): AudioSettings? = when (settings.renderMode) {
        RenderMode.AUDIO -> Effect.entries.firstOrNull { it.name == name }?.let { settings.copy(effect = it) }
        RenderMode.VIDEO -> VideoEffect.entries.firstOrNull { it.name == name }?.let { settings.copy(videoEffect = it) }
        RenderMode.VIDEO_AUDIO -> VideoAudioEffect.entries.firstOrNull { it.name == name }?.let { settings.copy(videoAudioEffect = it) }
        RenderMode.ANIMATION -> AnimationEffect.entries.firstOrNull { it.name == name }?.let { settings.copy(animationEffect = it) }
    }
}

/** The renderer-local override is the sole effective settings source while capture owns its bindings. */
object EffectiveRenderSettings {
    fun snapshot(persisted: AudioSettings, captureActive: Boolean): AudioSettings =
        if (captureActive) LiveRendererSettings.apply(persisted) else persisted
}

/** Runtime-only renderer overrides. During capture no route/capture setting is persisted or changed. */
object LiveRendererSettings {
    private var active = false
    private var effect: Effect? = null
    private var videoEffect: VideoEffect? = null
    private var videoAudioEffect: VideoAudioEffect? = null
    private var animationEffect: AnimationEffect? = null
    private var animationColour: AnimationColour? = null
    private var parameters: EffectParameters? = null
    private var brightness: Float? = null
    private var sensitivity: Float? = null
    private var videoSaturationPercent: Int? = null
    private var renderMode: RenderMode? = null
    private var videoAudioSilenceBrightnessFloor: Float? = null
    private var silenceHoldMillis: Int? = null
    private var silenceFadeMillis: Int? = null
    private var admitted: AudioSettings? = null


    @Synchronized fun begin(settings: AudioSettings) { active = true; admitted = settings; effect = null; videoEffect = null; videoAudioEffect = null; animationEffect = null; animationColour = null; parameters = null; brightness = null; sensitivity = null; videoSaturationPercent = null; renderMode = null; videoAudioSilenceBrightnessFloor = null; silenceHoldMillis = null; silenceFadeMillis = null }
    @Synchronized fun end() { active = false; admitted = null; effect = null; videoEffect = null; videoAudioEffect = null; animationEffect = null; animationColour = null; parameters = null; brightness = null; sensitivity = null; videoSaturationPercent = null; renderMode = null; videoAudioSilenceBrightnessFloor = null; silenceHoldMillis = null; silenceFadeMillis = null }
    @Synchronized fun setEffect(value: Effect) { if (active) effect = value }
    @Synchronized fun setVideoEffect(value: VideoEffect) { if (active) videoEffect = value }
    @Synchronized fun setVideoAudioEffect(value: VideoAudioEffect) { if (active) videoAudioEffect = value }
    @Synchronized fun setAnimationEffect(value: AnimationEffect) { if (active) animationEffect = value }
    @Synchronized fun setAnimationColour(value: AnimationColour) { if (active) animationColour = value }
    @Synchronized fun setActiveEffect(name: String): Boolean {
        val current = currentRenderMode(admitted ?: return false)
        return when (current) {
            RenderMode.AUDIO -> Effect.entries.firstOrNull { it.name == name }?.let { effect = it } != null
            RenderMode.VIDEO -> VideoEffect.entries.firstOrNull { it.name == name }?.let { videoEffect = it } != null
            RenderMode.VIDEO_AUDIO -> VideoAudioEffect.entries.firstOrNull { it.name == name }?.let { videoAudioEffect = it } != null
            RenderMode.ANIMATION -> AnimationEffect.entries.firstOrNull { it.name == name }?.let { animationEffect = it } != null
        }
    }

    @Synchronized fun setParameters(value: EffectParameters) { if (active && value.valid()) parameters = value }
    /** These are renderer-local scalars; neither alters an admitted route or capture buffers. */
    @Synchronized fun setBrightness(value: Float) { if (active && value in 0f..1f) { brightness = value; videoAudioSilenceBrightnessFloor = videoAudioSilenceBrightnessFloor?.coerceAtMost(value) } }
    @Synchronized fun setSensitivity(value: Float) { if (active && value in .25f..3.25f) sensitivity = value }
    @Synchronized fun setVideoSaturationPercent(value: Int) { if (active && VideoSaturationPolicy.valid(value)) videoSaturationPercent = value }
    /** Never changes a router or capture resource: only an already video-capable admitted route can render video live. */
    @Synchronized fun setRenderMode(value: RenderMode): Boolean {
        if (!active || !LiveRenderModeTransitionPolicy.permits(admitted, value)) return false
        renderMode = value
        return true
    }
    @Synchronized fun currentRenderMode(fallback: AudioSettings): RenderMode = renderMode ?: admitted?.renderMode ?: fallback.renderMode
    @Synchronized fun setVideoAudioSilenceBrightnessFloor(value: Float, ceiling: Float) { if (active && value in 0f..ceiling) videoAudioSilenceBrightnessFloor = value }
    @Synchronized fun setSilenceHoldMillis(value: Int) { if (active && value in 0..3_000) silenceHoldMillis = value }
    @Synchronized fun setSilenceFadeMillis(value: Int) { if (active && value in 100..2_000) silenceFadeMillis = value }
    /** Consecutive live edits use the last live value, not a stale persisted snapshot. */
    @Synchronized fun updateParameters(persisted: EffectParameters, transform: (EffectParameters) -> EffectParameters) {
        transform(parameters ?: persisted).takeIf(EffectParameters::valid)?.let { parameters = it }
    }
    @Synchronized fun apply(settings: AudioSettings): AudioSettings {
        val resolvedBrightness = brightness ?: settings.brightness
        return settings.copy(
        effect = effect ?: settings.effect,
        videoEffect = videoEffect ?: settings.videoEffect,
        videoAudioEffect = videoAudioEffect ?: settings.videoAudioEffect,
        animationEffect = animationEffect ?: settings.animationEffect,
        animationColour = animationColour ?: settings.animationColour,
        effectParameters = parameters ?: settings.effectParameters,
        brightness = resolvedBrightness,
        sensitivity = sensitivity ?: settings.sensitivity,
        videoSaturationPercent = videoSaturationPercent ?: settings.videoSaturationPercent,
        renderMode = renderMode ?: admitted?.renderMode ?: settings.renderMode,
        videoAudioSilenceBrightnessFloor = (videoAudioSilenceBrightnessFloor ?: settings.videoAudioSilenceBrightnessFloor).coerceIn(0f, resolvedBrightness),
        silenceHoldMillis = silenceHoldMillis ?: settings.silenceHoldMillis,
        silenceFadeMillis = silenceFadeMillis ?: settings.silenceFadeMillis,
    )
    }
}

/** WLED mappers are fixed at preflight. AUDIO admission has no mapper and must stop/restart before video. */
object LiveRenderModeTransitionPolicy {
    fun permits(admitted: AudioSettings?, requested: RenderMode): Boolean = admitted != null &&
        (requested == RenderMode.AUDIO || requested == RenderMode.ANIMATION || admitted.outputMode == OutputMode.HYPERION ||
            (admitted.requiresVideo() && admitted.selectedWledDevices().all { WledCalibrationPolicy.routeable(admitted, it) }))
}

object LiveRenderModeUiPolicy {
    fun checkboxes(mode: RenderMode) = CaptureModeCheckboxPolicy.resolve(
        audio = mode == RenderMode.AUDIO || mode == RenderMode.VIDEO_AUDIO,
        video = mode == RenderMode.VIDEO || mode == RenderMode.VIDEO_AUDIO,
        animation = mode == RenderMode.ANIMATION,
        previous = mode,
    )
}

/** Only these controls are read atomically by the active renderer. Route/capture controls remain locked. */
object LiveRendererControlPolicy {
    val sliderLabels = setOf("Чутливість", "Яскравість", "Мінімальна яскравість без звуку", "Затримка тиші", "Плавність тиші", "Насиченість відео", "Швидкість", "Слід", "Поріг біту", "Зсув палітри")
    fun sliderMutable(label: String) = label in sliderLabels
}
