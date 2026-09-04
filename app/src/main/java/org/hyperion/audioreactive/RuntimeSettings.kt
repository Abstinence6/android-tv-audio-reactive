package org.hyperion.audioreactive

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64

enum class RenderMode(val label: String) { AUDIO("Аудіо"), VIDEO("Відео"), VIDEO_AUDIO("Аудіо+відео") }
enum class VideoQuality(val width: Int, val height: Int, val label: String) {
    VERY_LOW(64, 36, "Дуже низька — 64×36 RGB24"),
    ECONOMY(80, 45, "Економна — 80×45 RGB24"),
    LOW(96, 54, "Низька — 96×54 RGB24"),
    BALANCED(112, 63, "Збалансована — 112×63 RGB24"),
    STANDARD(128, 72, "Стандартна — 128×72 RGB24"),
    HIGH(160, 90, "Висока — 160×90 RGB24"),
    VERY_HIGH(192, 108, "Дуже висока — 192×108 RGB24"),
    ULTRA(256, 144, "Ультра — 256×144 RGB24"),
    MAXIMUM(320, 180, "Максимальна — 320×180 RGB24");
    companion object { fun safe(value: String?) = entries.firstOrNull { it.name == value } ?: STANDARD }
}
object VideoCapturePolicy {
    /** One shared cadence for AUDIO, VIDEO and VIDEO_AUDIO. */
    val fpsOptions = intArrayOf(5, 10, 12, 15, 20, 24, 30)
    fun validFps(value: Int) = value in fpsOptions && value in CaptureCadence.MIN_FPS..CaptureCadence.MAX_FPS
    fun locked(active: Boolean) = active
    fun sourceFrame(mode: RenderMode, quality: VideoQuality, fps: Int): SourceFrameSpec =
        if (mode == RenderMode.AUDIO) SourceFrameSpec(16, 1, fps) else SourceFrameSpec(quality.width, quality.height, fps)
}
data class SourceFrameSpec(val width: Int, val height: Int, val fps: Int) { val bytes = width * height * 3 }

data class AudioSettings(
    val effect: Effect,
    val brightness: Float,
    val sensitivity: Float,
    val fps: Int,
    val outputMode: OutputMode = OutputMode.HYPERION,
    val wledDevices: List<WledDevice> = emptyList(),
    val selectedWledIdentities: Set<String> = emptySet(),
    val hyperionDevices: List<HyperionDevice> = emptyList(),
    val selectedHyperionIdentity: String? = null,
    val renderMode: RenderMode = RenderMode.AUDIO,
    val videoQuality: VideoQuality = VideoQuality.STANDARD,
    /** Kept only as an on-disk migration input; runtime always uses [fps]. */
    @Deprecated("Use fps") val videoFps: Int = 30,
    val audioBoost: Float = .35f,
    val wledSourceZones: Int = 128,
    val effectParameters: EffectParameters = EffectParameters(),
    val wledCalibrations: List<WledScreenCalibration> = emptyList(),
    val videoEffect: VideoEffect = VideoEffect.NORMAL,
    val videoAudioEffect: VideoAudioEffect = VideoAudioEffect.BRIGHTNESS_PULSE,
    /** Video saturation control: 0..200%, default 125%. */
    val videoSaturationPercent: Int = VideoSaturationPolicy.DEFAULT_PERCENT,
) {
    fun selectedWledDevices() = wledDevices.filter { it.identity in selectedWledIdentities }
    fun selectedHyperionDevice() = hyperionDevices.firstOrNull { it.identity == selectedHyperionIdentity }
    fun hasSelectedWledDestinations() = selectedWledDevices().isNotEmpty()
    fun calibrationFor(device: WledDevice) = wledCalibrations.firstOrNull { it.identity == device.identity }
    fun requiresAudio() = renderMode != RenderMode.VIDEO
    fun requiresVideo() = renderMode != RenderMode.AUDIO
    /** Hyperion receives the native capture frame; WLED is reduced to its bounded source zones. */
    fun sourceFrame() = VideoCapturePolicy.sourceFrame(renderMode, videoQuality, fps)
    fun captureFrame() = if (outputMode == OutputMode.WLED && !requiresVideo()) SourceFrameSpec(wledSourceZones, 1, fps) else sourceFrame()
    fun valid(): Boolean = brightness in 0f..1f && sensitivity in .25f..3.25f && fps in CaptureCadence.MIN_FPS..CaptureCadence.MAX_FPS &&
        VideoCapturePolicy.validFps(fps) && audioBoost in 0f..MAX_AUDIO_BOOST && wledSourceZones in 16..512 && wledSourceZones % 16 == 0 &&
        wledDevices.size <= 64 && wledDevices.all(WledDevice::valid) && wledDevices.map { it.identity }.distinct().size == wledDevices.size &&
        selectedWledIdentities.all { id -> wledDevices.any { it.identity == id } } && hyperionDevices.size <= 64 && hyperionDevices.all(HyperionDevice::valid) &&
        hyperionDevices.map { it.identity }.distinct().size == hyperionDevices.size && (selectedHyperionIdentity == null || hyperionDevices.any { it.identity == selectedHyperionIdentity }) &&
        effectParameters.valid() && VideoSaturationPolicy.valid(videoSaturationPercent) && wledCalibrations.size <= 64 && wledCalibrations.all(WledScreenCalibration::validFor) && wledCalibrations.map { it.identity }.distinct().size == wledCalibrations.size
    companion object { const val MAX_AUDIO_BOOST = .75f; fun defaults() = AudioSettings(Effect.SPECTRUM, .65f, 1f, 20) }
}

object WledInventory { fun merge(previous: List<WledDevice>, discovered: List<WledDevice>): List<WledDevice> { val fresh = discovered.filter(WledDevice::valid).associateBy { it.identity }; val ids = previous.map { it.identity }.toSet(); return (previous.map { fresh[it.identity] ?: it } + discovered.filter { it.identity !in ids }).filter(WledDevice::valid).distinctBy { it.identity }.take(64) } }
/** UI eligibility is the same static predicate used by WLED preflight; fresh discovery remains mandatory. */
object CaptureStartEligibility { fun permits(s: AudioSettings): Boolean = when (s.outputMode) { OutputMode.WLED -> WledCapturePreflight.eligible(s); OutputMode.HYPERION -> s.selectedHyperionDevice() != null } }
object CaptureCadence { const val SAMPLE_RATE_HZ=48_000; const val ANALYSIS_SAMPLES=1_024; const val MIN_FPS=5; const val MAX_FPS=30; fun periodNanos(fps:Int):Long { require(fps in MIN_FPS..MAX_FPS); return 1_000_000_000L/fps }; fun analysisWindowNanos()=ANALYSIS_SAMPLES*1_000_000_000L/SAMPLE_RATE_HZ; fun remainingSleepNanos(start:Long,now:Long,fps:Int)=(periodNanos(fps)-(now-start)).coerceAtLeast(0L) }
interface AudioSettingsStore { fun load(): AudioSettings?; fun save(settings: AudioSettings) }
object OutputModeMigration { fun fromLegacyIds(ids:String?)=if(ids?.split(',')?.any { it=="WLED_TV"||it=="WLED_CEILING" }==true) OutputMode.WLED else OutputMode.HYPERION }

class SharedPreferencesAudioSettingsStore(context: Context): AudioSettingsStore {
 private val preferences=context.applicationContext.getSharedPreferences(PREFERENCES,Context.MODE_PRIVATE)
 override fun load():AudioSettings?=try { val effect=preferences.getString(EFFECT,null)?.let { n->Effect.entries.firstOrNull { it.name==n } }?:return null; val output=OutputMode.entries.firstOrNull { it.name==preferences.getString(MODE,null) }?:OutputModeMigration.fromLegacyIds(preferences.getString(OUTPUTS,null)); val mode=RenderMode.entries.firstOrNull { it.name==preferences.getString(RENDER_MODE,null) }?:RenderMode.AUDIO; val unifiedFps=preferences.getInt(FPS,preferences.getInt(VIDEO_FPS,20)); val wled=decodeWled(preferences.getString(WLED_DEVICES,"")); val hyperion=decodeHyperion(preferences.getString(HYPERION_DEVICES,"")); AudioSettings(effect,preferences.getFloat(BRIGHTNESS,Float.NaN),preferences.getFloat(SENSITIVITY,Float.NaN),unifiedFps,output,wled,preferences.getStringSet(WLED_SELECTED,emptySet())?.toSet()?:emptySet(),hyperion,preferences.getString(HYPERION_SELECTED,null),mode,VideoQuality.safe(preferences.getString(VIDEO_QUALITY,null)),unifiedFps,preferences.getFloat(AUDIO_BOOST,.35f),preferences.getInt(WLED_ZONES,128).coerceIn(16,512).let { it-it%16 },decodeEffectParameters(preferences.getString(EFFECT_PARAMS,null)),decodeCalibrations(preferences.getString(WLED_CALIBRATIONS,"")),VideoEffect.entries.firstOrNull{it.name==preferences.getString(VIDEO_EFFECT,null)}?:VideoEffect.NORMAL,VideoAudioEffect.entries.firstOrNull{it.name==preferences.getString(VIDEO_AUDIO_EFFECT,null)}?:VideoAudioEffect.BRIGHTNESS_PULSE,preferences.getInt(VIDEO_SATURATION,125).coerceIn(0,200)).takeIf { it.valid() } } catch(_:ClassCastException){null}
 override fun save(s:AudioSettings) { require(s.valid()); check(preferences.edit().putString(EFFECT,s.effect.name).putFloat(BRIGHTNESS,s.brightness).putFloat(SENSITIVITY,s.sensitivity).putInt(FPS,s.fps).putString(MODE,s.outputMode.name).putString(RENDER_MODE,s.renderMode.name).putString(VIDEO_QUALITY,s.videoQuality.name).putString(VIDEO_EFFECT,s.videoEffect.name).putString(VIDEO_AUDIO_EFFECT,s.videoAudioEffect.name).putInt(VIDEO_SATURATION,s.videoSaturationPercent).remove(VIDEO_FPS).putFloat(AUDIO_BOOST,s.audioBoost).putInt(WLED_ZONES,s.wledSourceZones).putString(EFFECT_PARAMS,encodeEffectParameters(s.effectParameters)).putString(WLED_CALIBRATIONS,encodeCalibrations(s.wledCalibrations)).putString(WLED_DEVICES,encodeWled(s.wledDevices)).putStringSet(WLED_SELECTED,s.selectedWledIdentities).putString(HYPERION_DEVICES,encodeHyperion(s.hyperionDevices)).apply { if(s.selectedHyperionIdentity==null) remove(HYPERION_SELECTED) else putString(HYPERION_SELECTED,s.selectedHyperionIdentity) }.remove(OUTPUTS).commit()) }
 private fun encoded(fields:List<String>)=fields.joinToString("|"){Base64.encodeToString(it.encodeToByteArray(),Base64.URL_SAFE or Base64.NO_WRAP)}
 private fun decoded(item:String)=item.split('|').mapNotNull { runCatching { Base64.decode(it,Base64.URL_SAFE).decodeToString() }.getOrNull() }
 private fun encodeWled(items:List<WledDevice>)=items.joinToString(";"){encoded(listOf(it.identity,it.name,it.host,it.leds.toString(),it.realtimePort.toString()))}
 private fun decodeWled(raw:String?)=raw.orEmpty().split(';').filter(String::isNotBlank).mapNotNull { val f=decoded(it); if(f.size==5) WledDevice(f[0],f[1],f[2],f[3].toIntOrNull()?:-1,f[4].toIntOrNull()?:-1).takeIf(WledDevice::valid) else null }.distinctBy { it.identity }.take(64)
 private fun encodeHyperion(items:List<HyperionDevice>)=items.joinToString(";"){encoded(listOf(it.identity,it.name,it.host,it.jsonPort.toString(),it.dataPort.toString()))}
 private fun decodeHyperion(raw:String?)=raw.orEmpty().split(';').filter(String::isNotBlank).mapNotNull { val f=decoded(it); if(f.size==5) HyperionDevice(f[0],f[1],f[2],f[3].toIntOrNull()?:-1,f[4].toIntOrNull()?:-1).takeIf(HyperionDevice::valid) else null }.distinctBy { it.identity }.take(64)
 private fun encodeEffectParameters(p:EffectParameters)=encoded(listOf(p.speed.toString(),p.trail.toString(),p.beatThreshold.toString(),p.hueShift.toString()))
 private fun decodeEffectParameters(raw:String?):EffectParameters { val f=decoded(raw.orEmpty()); return if(f.size==4) EffectParameters(f[0].toFloatOrNull()?:1f,f[1].toFloatOrNull()?:.5f,f[2].toFloatOrNull()?:.2f,f[3].toFloatOrNull()?:0f).takeIf(EffectParameters::valid)?:EffectParameters() else EffectParameters() }
 private fun encodeCalibrations(items:List<WledScreenCalibration>)=items.joinToString(";"){ c->encoded(listOf(c.identity,c.physicalLedCount.toString(),c.startPixel.toString(),c.direction.name,c.bottom.toString(),c.right.toString(),c.top.toString(),c.left.toString(),c.bottomInsetPercent.toString(),c.rightInsetPercent.toString(),c.topInsetPercent.toString(),c.leftInsetPercent.toString(),c.depthPercent.toString(),c.samplesPerEdge.toString(),c.gamma.toString(),c.brightnessLimit.toString())) }
 /** v1 used a shared inset; retain it as all four edges on read, then save v2. */
 private fun decodeCalibrations(raw:String?)=raw.orEmpty().split(';').filter(String::isNotBlank).mapNotNull { val f=decoded(it); val sharedInset=f.getOrNull(8)?.toIntOrNull()?:-1; val c=when(f.size) { 16 -> WledScreenCalibration(f[0],f[1].toIntOrNull()?:-1,f[2].toIntOrNull()?:-1,PerimeterDirection.entries.firstOrNull{d->d.name==f[3]}?:PerimeterDirection.CW,f[4].toIntOrNull()?:-1,f[5].toIntOrNull()?:-1,f[6].toIntOrNull()?:-1,f[7].toIntOrNull()?:-1,sharedInset,f[9].toIntOrNull()?:-1,f[10].toIntOrNull()?:-1,f[11].toIntOrNull()?:-1,f[12].toIntOrNull()?:-1,f[13].toIntOrNull()?:-1,f[14].toFloatOrNull()?:Float.NaN,f[15].toFloatOrNull()?:Float.NaN); 13 -> WledScreenCalibration(f[0],f[1].toIntOrNull()?:-1,f[2].toIntOrNull()?:-1,PerimeterDirection.entries.firstOrNull{d->d.name==f[3]}?:PerimeterDirection.CW,f[4].toIntOrNull()?:-1,f[5].toIntOrNull()?:-1,f[6].toIntOrNull()?:-1,f[7].toIntOrNull()?:-1,sharedInset,sharedInset,sharedInset,sharedInset,f[9].toIntOrNull()?:-1,f[10].toIntOrNull()?:-1,f[11].toFloatOrNull()?:Float.NaN,f[12].toFloatOrNull()?:Float.NaN); else -> null }; c?.takeIf(WledScreenCalibration::validFor) }.distinctBy { it.identity }.take(64)
 private companion object { const val PREFERENCES="audio_reactive_preferences"; const val EFFECT="effect"; const val BRIGHTNESS="brightness"; const val SENSITIVITY="sensitivity"; const val FPS="fps"; const val OUTPUTS="output_target_ids"; const val MODE="output_mode"; const val WLED_DEVICES="validated_wled_devices_v1"; const val WLED_SELECTED="selected_wled_identities_v1"; const val HYPERION_DEVICES="validated_hyperion_devices_v1"; const val HYPERION_SELECTED="selected_hyperion_identity_v1"; const val RENDER_MODE="render_mode_v2"; const val VIDEO_QUALITY="video_quality_v2"; const val VIDEO_FPS="video_fps_v2"; const val VIDEO_EFFECT="video_effect_v3"; const val VIDEO_AUDIO_EFFECT="video_audio_effect_v3"; const val VIDEO_SATURATION="video_saturation_v4"; const val AUDIO_BOOST="audio_boost_v2"; const val WLED_ZONES="wled_source_zones_v2"; const val EFFECT_PARAMS="effect_parameters_v1"; const val WLED_CALIBRATIONS="wled_screen_calibration_v1" }
}
object RuntimeSettings {
    private object Defaults: AudioSettingsStore { override fun load() = null; override fun save(settings: AudioSettings) = Unit }
    private var store: AudioSettingsStore = Defaults
    private var current = AudioSettings.defaults()
    private val listeners = mutableSetOf<(AudioSettings) -> Unit>()
    @Synchronized fun initialize(s: AudioSettingsStore) { store = s; current = s.load()?.takeIf { it.valid() } ?: AudioSettings.defaults() }
    @Synchronized fun snapshot() = current
    fun apply(s: AudioSettings) = replaceLocked { s }
    fun update(transform: (AudioSettings) -> AudioSettings): AudioSettings = replaceLocked(transform)
    private fun replaceLocked(transform: (AudioSettings) -> AudioSettings): AudioSettings {
        val next: AudioSettings
        val observers: List<(AudioSettings) -> Unit>
        synchronized(this) {
            next = transform(current)
            require(next.valid())
            store.save(next)
            current = next
            observers = listeners.toList()
        }
        observers.forEach { it(next) }
        return next
    }
    @Synchronized fun addListener(listener: (AudioSettings) -> Unit) { listeners += listener }
    @Synchronized fun removeListener(listener: (AudioSettings) -> Unit) { listeners -= listener }
}
