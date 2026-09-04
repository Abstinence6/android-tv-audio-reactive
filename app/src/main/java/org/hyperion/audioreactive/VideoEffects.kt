package org.hyperion.audioreactive

/** Separate catalogues keep video colour treatment separate from audio-only strip effects. */
enum class VideoEffect(val label: String) {
    NORMAL("Normal"), SATURATION("Saturation"), CONTRAST("Contrast")
}

enum class VideoAudioEffect(val label: String) {
    BRIGHTNESS_PULSE("Brightness pulse"), BEAT_PULSE("Beat pulse"), EQ("EQ"),
    COMET("Comet"), RIPPLE("Ripple"), BASS_SWEEP("Bass sweep")
}

object VideoEffectCatalog {
    fun labels(mode: RenderMode): List<String> = when (mode) {
        RenderMode.AUDIO -> Effect.entries.map { it.label }
        RenderMode.VIDEO -> VideoEffect.entries.map { it.label }
        RenderMode.VIDEO_AUDIO -> VideoAudioEffect.entries.map { it.label }
    }

    fun compatible(mode: RenderMode, effect: String): Boolean = effect in labels(mode)
}

/** Base image treatment stays available in VIDEO_AUDIO beside its audio modulation list. */
object VideoColourTreatmentPolicy {
    fun labels(): List<String> = VideoEffect.entries.map { it.label }
    fun visible(mode: RenderMode): Boolean = mode != RenderMode.AUDIO
    fun selectedIndex(settings: AudioSettings): Int = settings.videoEffect.ordinal
    fun selection(index: Int): VideoEffect? = VideoEffect.entries.getOrNull(index)
    fun mutable(captureActive: Boolean): Boolean = !captureActive
}

/** Pure selector state prevents a stale enum index from crossing capture-mode catalogues. */
object EffectSelectorPolicy {
    fun selectedIndex(settings: AudioSettings): Int = when (settings.renderMode) {
        RenderMode.AUDIO -> settings.effect.ordinal
        RenderMode.VIDEO -> settings.videoEffect.ordinal
        RenderMode.VIDEO_AUDIO -> settings.videoAudioEffect.ordinal
    }

    fun labels(settings: AudioSettings): List<String> = VideoEffectCatalog.labels(settings.renderMode)
}

/** Runtime-only renderer overrides. During capture no route/capture setting is persisted or changed. */
object LiveRendererSettings {
    private var active = false
    private var effect: Effect? = null
    private var videoEffect: VideoEffect? = null
    private var videoAudioEffect: VideoAudioEffect? = null
    private var parameters: EffectParameters? = null
    private var brightness: Float? = null
    private var sensitivity: Float? = null


    @Synchronized fun begin() { active = true; effect = null; videoEffect = null; videoAudioEffect = null; parameters = null; brightness = null; sensitivity = null }
    @Synchronized fun end() { active = false; effect = null; videoEffect = null; videoAudioEffect = null; parameters = null; brightness = null; sensitivity = null }
    @Synchronized fun setEffect(value: Effect) { if (active) effect = value }
    @Synchronized fun setVideoEffect(value: VideoEffect) { if (active) videoEffect = value }
    @Synchronized fun setVideoAudioEffect(value: VideoAudioEffect) { if (active) videoAudioEffect = value }

    @Synchronized fun setParameters(value: EffectParameters) { if (active && value.valid()) parameters = value }
    /** These are renderer-local scalars; neither alters an admitted route or capture buffers. */
    @Synchronized fun setBrightness(value: Float) { if (active && value in 0f..1f) brightness = value }
    @Synchronized fun setSensitivity(value: Float) { if (active && value in .25f..3.25f) sensitivity = value }
    /** Consecutive live edits use the last live value, not a stale persisted snapshot. */
    @Synchronized fun updateParameters(persisted: EffectParameters, transform: (EffectParameters) -> EffectParameters) {
        transform(parameters ?: persisted).takeIf(EffectParameters::valid)?.let { parameters = it }
    }
    @Synchronized fun apply(settings: AudioSettings): AudioSettings = settings.copy(
        effect = effect ?: settings.effect,
        videoEffect = videoEffect ?: settings.videoEffect,
        videoAudioEffect = videoAudioEffect ?: settings.videoAudioEffect,
        effectParameters = parameters ?: settings.effectParameters,
        brightness = brightness ?: settings.brightness,
        sensitivity = sensitivity ?: settings.sensitivity,
    )
}

/** Only these controls are read atomically by the active renderer. Route/capture controls remain locked. */
object LiveRendererControlPolicy {
    val sliderLabels = setOf("Чутливість", "Яскравість", "Швидкість", "Слід", "Поріг біту", "Зсув палітри")
    fun sliderMutable(label: String) = label in sliderLabels
}
