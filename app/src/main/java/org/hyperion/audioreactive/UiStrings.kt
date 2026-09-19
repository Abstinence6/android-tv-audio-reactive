package org.hyperion.audioreactive

import android.content.Context

/** Android-facing localized labels. Enum names remain stable persistence and MQTT identifiers. */
object UiStrings {
    private fun Context.array(id: Int): Array<String> = resources.getStringArray(id)

    fun effect(context: Context, value: Effect) = context.array(R.array.effect_labels)[value.ordinal]
    fun videoEffect(context: Context, value: VideoEffect) = context.array(R.array.video_effect_labels)[value.ordinal]
    fun videoAudioEffect(context: Context, value: VideoAudioEffect) = context.array(R.array.video_audio_effect_labels)[VideoAudioEffectCatalogue.visible.indexOf(VideoAudioEffectCatalogue.pickerEffect(value))]
    fun animationEffect(context: Context, value: AnimationEffect) = context.array(R.array.animation_effect_labels)[value.ordinal]
    fun animationColour(context: Context, value: AnimationColour) = context.array(R.array.animation_colour_labels)[value.ordinal]
    fun renderMode(context: Context, value: RenderMode) = context.array(R.array.render_mode_labels)[value.ordinal]
    fun outputMode(context: Context, value: OutputMode) = context.array(R.array.output_mode_labels)[value.ordinal]
    fun videoQuality(context: Context, value: VideoQuality) = context.array(R.array.video_quality_labels)[value.ordinal]
    fun localVisualPattern(context: Context, value: LocalVisualPattern) = context.array(R.array.local_visual_pattern_labels)[value.ordinal]
    fun diagnosticPattern(context: Context, value: WledDiagnosticPattern) = context.array(R.array.wled_diagnostic_labels)[value.ordinal]
    fun screenEdge(context: Context, value: ScreenEdge) = context.array(R.array.screen_edge_labels)[value.ordinal]
    fun perimeterDirection(context: Context, value: PerimeterDirection) = context.array(R.array.perimeter_direction_labels)[value.ordinal]
    fun captureStatus(context: Context, value: CaptureStatus) = context.array(R.array.capture_status_texts)[value.ordinal]

    fun effectLabels(context: Context, settings: AudioSettings): List<String> = when (settings.renderMode) {
        RenderMode.AUDIO -> EffectCatalogue.visible.map { effect(context, it) }
        RenderMode.VIDEO -> VideoEffect.entries.map { videoEffect(context, it) }
        RenderMode.VIDEO_AUDIO -> VideoAudioEffectCatalogue.visible.map { videoAudioEffect(context, it) }
        RenderMode.ANIMATION -> AnimationEffect.entries.map { animationEffect(context, it) }
    }
}
