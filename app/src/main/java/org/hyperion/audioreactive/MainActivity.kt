package org.hyperion.audioreactive

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.view.ViewGroup
import android.text.InputType
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.graphics.drawable.GradientDrawable
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Labels and tab contract shared with pure UI-policy tests. */
object OutputUiPolicy {
    val sections = listOf("toggle", "capture-mode", "effects", "outputs", "discovery", "settings")
    fun modeAfterToggle(current: OutputMode, clicked: OutputMode, checked: Boolean) = if (checked) clicked else current
    fun handlesCheckboxChange(synchronizing: Boolean) = !synchronizing
}

/** Short, stable TV-facing summary; detailed diagnostics stay behind the local status action. */
object CaptureUiPresentation {
    fun indicator(context: Context, status: CaptureStatus, mode: RenderMode): String =
        context.getString(R.string.capture_indicator, context.getString(stateLabel(status)), UiStrings.renderMode(context, mode))

    internal fun stateLabel(status: CaptureStatus) = when (status) {
        CaptureStatus.NEEDS_MEDIA_PROJECTION_CONSENT -> R.string.status_ready
        CaptureStatus.PREPARING_PROJECTION, CaptureStatus.PREPARING_AUDIO_RECORD, CaptureStatus.PREPARING_ANIMATION -> R.string.status_preparing
        CaptureStatus.ROUTE_LOST -> R.string.status_route_lost
        CaptureStatus.ROUTER_INIT_FAILED, CaptureStatus.AUDIO_RECORD_INIT_FAILED,
        CaptureStatus.AUDIO_RECORD_START_FAILED, CaptureStatus.AUDIO_RECORD_INIT_TIMEOUT -> R.string.status_start_failed
        CaptureStatus.VIDEO_UNAVAILABLE_OR_PROTECTED -> R.string.status_video_unavailable
        CaptureStatus.CAPTURE_ACTIVE, CaptureStatus.CAPTURE_ACTIVE_AUDIO,
        CaptureStatus.CAPTURE_ACTIVE_VIDEO, CaptureStatus.CAPTURE_ACTIVE_VIDEO_AUDIO, CaptureStatus.CAPTURE_ACTIVE_ANIMATION -> R.string.status_active
    }
}

class MainActivity : Activity(), CaptureToggleCoordinator.Host {
    companion object {
        const val EXTRA_IR_TOGGLE = "org.hyperion.audioreactive.IR_TOGGLE"
        const val ACTION_TOGGLE = "org.hyperion.audioreactive.action.TOGGLE"
        const val ACTION_ON = "org.hyperion.audioreactive.action.ON"
        const val ACTION_OFF = "org.hyperion.audioreactive.action.OFF"

    }
    private val captureRequest = 42
    private val captureToggleCoordinator = CaptureToggleCoordinator(this)
    private val work = Executors.newSingleThreadExecutor()
    private val capturePreflightGeneration = AtomicLong()
    private val discoveryAdmissionGeneration = AtomicLong()
    private val rainbowHandler = Handler(Looper.getMainLooper())
    private var rainbowHue = 0f
    private var movingPatternPhase = 0f
    private var movingBarsDrawable: LocalVisualPatternDrawable? = null
    // Starts before the first entry so the first press retains the old rainbow-test behavior.
    private var localVisualPatternIndex = LocalVisualPattern.entries.lastIndex
    private lateinit var contentRoot: LinearLayout
    private val rainbowAnimator = object : Runnable {
        override fun run() {
            if (!RainbowVisualSourcePolicy.running) return
            rainbowHue = (rainbowHue + 3f) % 360f
            contentRoot.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
                Color.HSVToColor(floatArrayOf(rainbowHue, 1f, 1f)),
                Color.HSVToColor(floatArrayOf((rainbowHue + 120f) % 360f, 1f, 1f)),
                Color.HSVToColor(floatArrayOf((rainbowHue + 240f) % 360f, 1f, 1f)),
            ))
            rainbowHandler.postDelayed(this, 33L)
        }
    }
    private val movingBarsAnimator = object : Runnable {
        override fun run() {
            if (!RainbowVisualSourcePolicy.running) return
            movingPatternPhase = (movingPatternPhase + 0.008f) % 1f
            movingBarsDrawable?.updatePhase(movingPatternPhase)
            rainbowHandler.postDelayed(this, 33L)
        }
    }
    private lateinit var captureIndicator: TextView
    private lateinit var status: TextView
    private lateinit var captureButton: Button
    private lateinit var testButton: Button
    private lateinit var audioBox: CheckBox
    private lateinit var videoBox: CheckBox
    private lateinit var animationBox: CheckBox
    private lateinit var effectSpinner: Spinner
    private lateinit var hyperionMode: CheckBox
    private lateinit var wledMode: CheckBox
    private lateinit var outputRows: LinearLayout
    private lateinit var qualityRow: LinearLayout
    private lateinit var videoFpsRow: LinearLayout
    private lateinit var audioSection: TextView
    private lateinit var sensitivityRow: LinearLayout
    private lateinit var silenceBrightnessRow: LinearLayout
    private lateinit var silenceFadeToggle: CheckBox
    private lateinit var silenceHoldRow: LinearLayout
    private lateinit var silenceFadeRow: LinearLayout
    private lateinit var videoColourTreatmentRow: LinearLayout
    private lateinit var videoColourTreatmentSpinner: Spinner
    private lateinit var videoSaturationRow: LinearLayout
    private lateinit var animationColourRow: LinearLayout
    private lateinit var animationColourSpinner: Spinner
    private lateinit var effectParametersTitle: TextView
    private lateinit var speedRow: LinearLayout
    private lateinit var trailRow: LinearLayout
    private lateinit var beatThresholdRow: LinearLayout
    private lateinit var paletteShiftRow: LinearLayout
    private lateinit var zonesRow: LinearLayout
    private lateinit var discoverButton: Button

    private val modeMutableRows = mutableListOf<View>()
    private lateinit var releaseUpdater: GitHubReleaseUpdater

    private var suppressModeCallbacks = false
    private var suppressOutputCallbacks = false
    private var suppressEffectSelection = false
    private var suppressVideoColourTreatmentSelection = false
    private var latestWled = emptySet<String>()
    private var latestHyperion = emptySet<String>()
    private var pendingWled: String? = null
    private var pendingHyperion: String? = null
    private var pendingPermissionGeneration: Long? = null
    private var pendingProjectionGeneration: Long? = null
    private var pendingAdmissionGeneration: Long? = null
    private var captureAdmissionLocked = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val generation = intent.getLongExtra(AudioReactiveService.EXTRA_ADMISSION_GENERATION, Long.MIN_VALUE)
            if (generation != Long.MIN_VALUE && pendingAdmissionGeneration == generation) {
                if (intent.getBooleanExtra(AudioReactiveService.EXTRA_ADMISSION_FAILED, false)) {
                    // The service discarded the unconsumed one-shot route; make this admission retryable.
                    invalidatePendingCaptureAdmission()
                    captureToggleCoordinator.invalidatePending()
                    status.text = getString(R.string.route_admission_failed)
                } else {
                    // Sent only after the service has consumed and installed this exact route binding.
                    pendingWled = null; pendingHyperion = null
                    pendingAdmissionGeneration = null; captureAdmissionLocked = false
                    captureToggleCoordinator.onCaptureServiceOwnershipConfirmed(generation)
                }
            }
            refreshCaptureUi(true)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        contentRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 36, 48, 36)
        }
        val root = contentRoot
        root.addView(TextView(this).apply { text = getString(R.string.app_title); textSize = 26f })
        captureIndicator = TextView(this).apply { textSize = 19f }
        root.addView(captureIndicator)
        status = TextView(this).apply { textSize = 15f; maxLines = 2 }
        root.addView(status)

        val mainPanel = LinearLayout(this).apply { id = View.generateViewId(); orientation = LinearLayout.VERTICAL }
        val mainScroll = ScrollView(this).apply {
            id = View.generateViewId()
            isFillViewport = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            addView(mainPanel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(mainScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        buildControlTab(mainPanel)
        buildModesTab(mainPanel)
        buildOutputsTab(mainPanel)
        buildAdditionalTools(mainPanel)
        setContentView(root)
        refreshCaptureUi()
        captureButton.requestFocus()
        releaseUpdater = GitHubReleaseUpdater(
            applicationContext,
            onStatus = { message, _ ->
                if (!isFinishing && !isDestroyed) {
                    status.text = message
                }
            },
            onUpdateAvailable = {
                if (!isFinishing && !isDestroyed) {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.update_available_title)
                        .setMessage(R.string.update_available_message)
                        .setNegativeButton(R.string.no, null)
                        .setPositiveButton(R.string.yes) { _, _ -> releaseUpdater.updateSelectedRelease() }
                        .show()
                }
            },
        )
        // Launch check fetches release metadata only. Download remains user-confirmed in this Activity.
        releaseUpdater.checkForUpdate()
        handleRemoteAction(intent)
    }

    /** The first interactive control in the single scrollable D-pad screen is capture. */
    private fun buildControlTab(panel: LinearLayout) {
        captureButton = Button(this).apply { id = View.generateViewId(); setOnClickListener { handleCaptureToggle() } }
        panel.addView(captureButton)
        panel.addView(TextView(this).apply { text = getString(R.string.capture_mode) })
        val initial = RuntimeSettings.snapshot().renderMode
        audioBox = CheckBox(this).apply { id = View.generateViewId(); text = getString(R.string.mode_audio); isChecked = initial == RenderMode.AUDIO || initial == RenderMode.VIDEO_AUDIO }
        videoBox = CheckBox(this).apply { id = View.generateViewId(); text = getString(R.string.mode_video); isChecked = initial == RenderMode.VIDEO || initial == RenderMode.VIDEO_AUDIO }
        animationBox = CheckBox(this).apply { id = View.generateViewId(); text = getString(R.string.mode_animation); isChecked = initial == RenderMode.ANIMATION }
        val listener = CompoundButton.OnCheckedChangeListener { changed, checked ->
            if (suppressModeCallbacks) return@OnCheckedChangeListener
            if (checked && changed === animationBox) {
                suppressModeCallbacks = true
                audioBox.isChecked = false; videoBox.isChecked = false
                suppressModeCallbacks = false
            } else if (checked && (changed === audioBox || changed === videoBox)) {
                suppressModeCallbacks = true
                animationBox.isChecked = false
                suppressModeCallbacks = false
            }
            resolveCaptureMode()
        }
        audioBox.setOnCheckedChangeListener(listener); videoBox.setOnCheckedChangeListener(listener); animationBox.setOnCheckedChangeListener(listener)
        panel.addView(audioBox); panel.addView(videoBox); panel.addView(animationBox)
        modeMutableRows += audioBox; modeMutableRows += videoBox; modeMutableRows += animationBox
        addEffectSelector(panel)
        testButton = Button(this).apply {
            text = getString(R.string.screen_test, UiStrings.localVisualPattern(this@MainActivity, LocalVisualPattern.entries[localVisualPatternIndex]))
            contentDescription = getString(R.string.screen_test_description)
            setOnClickListener { cycleLocalVisualPattern() }
        }
        panel.addView(testButton)
    }

    /** Technical and maintenance actions follow capture and output controls on the same scrollable screen. */
    private fun buildAdditionalTools(panel: LinearLayout) {
        panel.addView(TextView(this).apply { text = getString(R.string.additional); textSize = 18f })
        panel.addView(Button(this).apply { text = getString(R.string.detailed_local_status); setOnClickListener { showDetailedStatus() } })
    }

    /** Compatible selector stays enabled: while active it mutates renderer-local state only. */
    private fun addEffectSelector(panel: LinearLayout) {
        panel.addView(TextView(this).apply { text = getString(R.string.effect) })
        effectSpinner = Spinner(this).apply {
            id = View.generateViewId()
            isEnabled = EffectSelectionPolicy.enabledWhileCaptureActive()
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (suppressEffectSelection) return
                    val s = effectiveRenderSettings()
                    when (s.renderMode) {
                        RenderMode.AUDIO -> Effect.entries.getOrNull(position)?.let { value -> if (AudioReactiveService.exists()) LiveRendererSettings.setEffect(value) else RuntimeSettings.update { it.copy(effect = value) } }
                        RenderMode.VIDEO -> VideoEffect.entries.getOrNull(position)?.let { value -> if (AudioReactiveService.exists()) LiveRendererSettings.setVideoEffect(value) else RuntimeSettings.update { it.copy(videoEffect = value) } }
                        RenderMode.VIDEO_AUDIO -> VideoAudioEffect.entries.getOrNull(position)?.let { value -> if (AudioReactiveService.exists()) LiveRendererSettings.setVideoAudioEffect(value) else RuntimeSettings.update { it.copy(videoAudioEffect = value) } }
                        RenderMode.ANIMATION -> AnimationEffect.entries.getOrNull(position)?.let { value -> if (AudioReactiveService.exists()) LiveRendererSettings.setAnimationEffect(value) else RuntimeSettings.update { it.copy(animationEffect = value) } }
                    }
                }
            }
        }
        panel.addView(effectSpinner)
        rebuildEffectSelector()
    }

    /** Rebuild before selecting, so an old catalogue ordinal can never mutate a new mode. */
    private fun rebuildEffectSelector() {
        val settings = effectiveRenderSettings()
        suppressEffectSelection = true
        try {
            effectSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, UiStrings.effectLabels(this, settings))
            effectSpinner.setSelection(EffectSelectorPolicy.selectedIndex(settings), false)
        } finally {
            suppressEffectSelection = false
        }
    }

    private fun buildModesTab(panel: LinearLayout) {
        qualityRow = LinearLayout(this).apply { id = View.generateViewId(); orientation = LinearLayout.VERTICAL }
        qualityRow.addView(TextView(this).apply { text = getString(R.string.video_quality_hyperion) })
        qualityRow.addView(Spinner(this).apply {
            id = View.generateViewId()
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, VideoQuality.entries.map { UiStrings.videoQuality(this@MainActivity, it) })
            setSelection(RuntimeSettings.snapshot().videoQuality.ordinal)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (!AudioReactiveService.exists()) RuntimeSettings.update { it.copy(videoQuality = VideoQuality.entries[position]) }
                }
            }
        })
        panel.addView(qualityRow)
        modeMutableRows += qualityRow
        videoFpsRow = sliderRow(getString(R.string.fps_label), VideoCapturePolicy.fpsOptions.indexOf(RuntimeSettings.snapshot().fps).coerceAtLeast(0), VideoCapturePolicy.fpsOptions.lastIndex, false, { getString(R.string.fps_value, VideoCapturePolicy.fpsOptions[it]) }) {
            RuntimeSettings.update { settings -> settings.copy(fps = VideoCapturePolicy.fpsOptions[it]) }
        }
        panel.addView(videoFpsRow)
        modeMutableRows += videoFpsRow

        audioSection = TextView(this).apply { text = getString(R.string.audio_section) }
        panel.addView(audioSection)
        sensitivityRow = sliderRow(getString(R.string.sensitivity), ((RuntimeSettings.snapshot().sensitivity - .25f) / .05f).toInt(), 60, true, { SliderFormatters.sensitivity(this, .25f + it * .05f) }) {
            updateSensitivity(.25f + it * .05f)
        }
        panel.addView(sensitivityRow)
        panel.addView(sliderRow(getString(R.string.brightness), (RuntimeSettings.snapshot().brightness / .05f).toInt(), 20, true, { SliderFormatters.brightness(this, it * .05f) }) {
            updateBrightness(it * .05f)
        })
        silenceBrightnessRow = sliderRow(getString(R.string.silence_brightness), (RuntimeSettings.snapshot().videoAudioSilenceBrightnessFloor / .05f).toInt(), 20, true, { SliderFormatters.brightness(this, it * .05f) }) {
            updateVideoAudioSilenceBrightnessFloor(it * .05f)
        }
        panel.addView(silenceBrightnessRow)
        silenceFadeToggle = CheckBox(this).apply {
            id = View.generateViewId()
            text = getString(R.string.silence_fade_enabled)
            isChecked = RuntimeSettings.snapshot().silenceFadeEnabled
            setOnCheckedChangeListener { _, enabled -> updateSilenceFadeEnabled(enabled) }
        }
        panel.addView(silenceFadeToggle)
        silenceHoldRow = sliderRow(getString(R.string.silence_hold), RuntimeSettings.snapshot().silenceHoldMillis / 100, 30, true, { getString(R.string.milliseconds, it * 100) }) { updateSilenceHoldMillis(it * 100) }
        panel.addView(silenceHoldRow)
        silenceFadeRow = sliderRow(getString(R.string.silence_fade), (RuntimeSettings.snapshot().silenceFadeMillis - 100) / 100, 19, true, { getString(R.string.milliseconds, 100 + it * 100) }) { updateSilenceFadeMillis(100 + it * 100) }
        panel.addView(silenceFadeRow)
        effectParametersTitle = TextView(this).apply { text = getString(R.string.effect_parameters) }
        panel.addView(effectParametersTitle)
        videoColourTreatmentRow = LinearLayout(this).apply { id = View.generateViewId(); orientation = LinearLayout.VERTICAL }
        videoColourTreatmentRow.addView(TextView(this).apply { text = getString(R.string.video_colour_treatment) })
        videoColourTreatmentSpinner = Spinner(this).apply {
            id = View.generateViewId()
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, VideoEffect.entries.map { UiStrings.videoEffect(this@MainActivity, it) })
            setSelection(VideoColourTreatmentPolicy.selectedIndex(RuntimeSettings.snapshot()), false)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (suppressVideoColourTreatmentSelection) return
                    VideoColourTreatmentPolicy.selection(position)?.let { treatment ->
                        if (AudioReactiveService.exists()) LiveRendererSettings.setVideoEffect(treatment)
                        else RuntimeSettings.update { it.copy(videoEffect = treatment) }
                    }
                }
            }
        }
        videoColourTreatmentRow.addView(videoColourTreatmentSpinner)
        panel.addView(videoColourTreatmentRow)
        videoSaturationRow = sliderRow(getString(R.string.video_saturation), RuntimeSettings.snapshot().videoSaturationPercent, VideoSaturationPolicy.MAX_PERCENT, true, { getString(R.string.format_percent, it) }) { value ->
            if (AudioReactiveService.exists()) LiveRendererSettings.setVideoSaturationPercent(value)
            else RuntimeSettings.update { it.copy(videoSaturationPercent = value) }
        }
        panel.addView(videoSaturationRow)
        animationColourRow = LinearLayout(this).apply { id = View.generateViewId(); orientation = LinearLayout.VERTICAL }
        animationColourRow.addView(TextView(this).apply { text = getString(R.string.animation_palette) })
        animationColourSpinner = Spinner(this).apply {
            id = View.generateViewId()
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, AnimationColour.entries.map { UiStrings.animationColour(this@MainActivity, it) })
            setSelection(RuntimeSettings.snapshot().animationColour.ordinal, false)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    AnimationColour.entries.getOrNull(position)?.let { colour -> if (AudioReactiveService.exists()) LiveRendererSettings.setAnimationColour(colour) else RuntimeSettings.update { it.copy(animationColour = colour) } }
                }
            }
        }
        animationColourRow.addView(animationColourSpinner)
        panel.addView(animationColourRow)
        speedRow = sliderRow(getString(R.string.speed), ((RuntimeSettings.snapshot().effectParameters.speed - .25f) / .25f).toInt(), 11, true, { getString(R.string.format_multiplier, .25f + it * .25f) }) { v -> updateEffectParameters { it.copy(speed = .25f + v * .25f) } }
        panel.addView(speedRow)
        trailRow = sliderRow(getString(R.string.trail), (RuntimeSettings.snapshot().effectParameters.trail * 10).toInt(), 10, true, { getString(R.string.format_percent, it * 10) }) { v -> updateEffectParameters { it.copy(trail = v / 10f) } }
        panel.addView(trailRow)
        beatThresholdRow = sliderRow(getString(R.string.beat_threshold), ((RuntimeSettings.snapshot().effectParameters.beatThreshold - .05f) / .05f).toInt(), 18, true, { getString(R.string.format_percent, 5 + it * 5) }) { v -> updateEffectParameters { it.copy(beatThreshold = .05f + v * .05f) } }
        panel.addView(beatThresholdRow)
        paletteShiftRow = sliderRow(getString(R.string.palette_shift), ((RuntimeSettings.snapshot().effectParameters.hueShift + 180f) / 15f).toInt(), 24, true, { getString(R.string.format_degrees, -180 + it * 15) }) { v -> updateEffectParameters { it.copy(hueShift = -180f + v * 15f) } }
        panel.addView(paletteShiftRow)
    }

    private fun buildOutputsTab(panel: LinearLayout) {
        panel.addView(TextView(this).apply { text = getString(R.string.output) })
        hyperionMode = CheckBox(this).apply { id = View.generateViewId(); text = getString(R.string.hyperion) }
        wledMode = CheckBox(this).apply { id = View.generateViewId(); text = getString(R.string.wled) }
        hyperionMode.setOnCheckedChangeListener { _, checked -> if (OutputUiPolicy.handlesCheckboxChange(suppressOutputCallbacks)) selectOutput(OutputMode.HYPERION, checked) }
        wledMode.setOnCheckedChangeListener { _, checked -> if (OutputUiPolicy.handlesCheckboxChange(suppressOutputCallbacks)) selectOutput(OutputMode.WLED, checked) }
        panel.addView(hyperionMode)
        panel.addView(wledMode)
        discoverButton = Button(this).apply { text = getString(R.string.find_selected_outputs); setOnClickListener { discover(RuntimeSettings.snapshot().outputMode) } }
        panel.addView(discoverButton)
        outputRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(outputRows)
        zonesRow = sliderRow(getString(R.string.source_zones), RuntimeSettings.snapshot().wledSourceZones / 16 - 1, 31, false, { "${(it + 1) * 16}" }) {
            RuntimeSettings.update { settings -> settings.copy(wledSourceZones = (it + 1) * 16) }
        }
        panel.addView(zonesRow)
        panel.addView(Button(this).apply { text = getString(R.string.mqtt_settings); setOnClickListener { showMqttSettingsDialog() } })
    }

    private fun updateEffectParameters(transform: (EffectParameters) -> EffectParameters) {
        if (AudioReactiveService.exists()) LiveRendererSettings.updateParameters(RuntimeSettings.snapshot().effectParameters, transform)
        else RuntimeSettings.update { it.copy(effectParameters = transform(it.effectParameters)) }
    }

    private fun updateBrightness(value: Float) {
        if (AudioReactiveService.exists()) LiveRendererSettings.setBrightness(value)
        else RuntimeSettings.update { it.copy(brightness = value, videoAudioSilenceBrightnessFloor = it.videoAudioSilenceBrightnessFloor.coerceAtMost(value)) }
    }

    private fun updateSensitivity(value: Float) {
        if (AudioReactiveService.exists()) LiveRendererSettings.setSensitivity(value)
        else RuntimeSettings.update { it.copy(sensitivity = value) }
    }

    private fun updateVideoAudioSilenceBrightnessFloor(value: Float) {
        if (AudioReactiveService.exists()) {
            val live = LiveRendererSettings.apply(RuntimeSettings.snapshot())
            LiveRendererSettings.setVideoAudioSilenceBrightnessFloor(value.coerceIn(0f, live.brightness), live.brightness)
        } else RuntimeSettings.update { it.copy(videoAudioSilenceBrightnessFloor = value.coerceIn(0f, it.brightness)) }
    }
    private fun updateSilenceHoldMillis(value: Int) { if (AudioReactiveService.exists()) LiveRendererSettings.setSilenceHoldMillis(value) else RuntimeSettings.update { it.copy(silenceHoldMillis = value) } }
    private fun updateSilenceFadeMillis(value: Int) { if (AudioReactiveService.exists()) LiveRendererSettings.setSilenceFadeMillis(value) else RuntimeSettings.update { it.copy(silenceFadeMillis = value) } }
    private fun updateSilenceFadeEnabled(value: Boolean) { if (AudioReactiveService.exists()) LiveRendererSettings.setSilenceFadeEnabled(value) else RuntimeSettings.update { it.copy(silenceFadeEnabled = value) } }


    private fun sliderRow(label: String, initial: Int, max: Int, liveMutable: Boolean = false, format: (Int) -> String, apply: (Int) -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val value = TextView(this@MainActivity)
            fun show(progress: Int) { value.text = getString(R.string.slider_value, label, format(progress)) }
            show(initial)
            addView(value)
            addView(SeekBar(this@MainActivity).apply {
                this.max = max
                progress = initial
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        show(progress)
                        if (fromUser && (!AudioReactiveService.exists() || liveMutable)) apply(progress)
                    }
                })
            })
        }


    private fun resolveCaptureMode() {
        if (suppressModeCallbacks) return
        val persisted = RuntimeSettings.snapshot()
        val previous = effectiveRenderSettings().renderMode
        val result = CaptureModeCheckboxPolicy.resolve(audioBox.isChecked, videoBox.isChecked, animationBox.isChecked, previous)
        // ANIMATION owns neither projection nor AudioRecord. Crossing that boundary must stop the
        // current service so the next capture press performs fresh permission and resource admission.
        val restartForInputOwnership = AnimationModeTransitionPolicy.requiresRestart(AudioReactiveService.exists(), previous, result.mode)
        if (restartForInputOwnership) AudioReactiveService.stopExisting(this)
        val accepted = restartForInputOwnership || !AudioReactiveService.exists() || LiveRendererSettings.setRenderMode(result.mode)
        val visible = LiveRenderModeUiPolicy.checkboxes(
            if (accepted) {
                if (AudioReactiveService.exists() && !restartForInputOwnership) effectiveRenderSettings().renderMode else result.mode
            } else previous
        )
        suppressModeCallbacks = true
        audioBox.isChecked = visible.audioChecked
        videoBox.isChecked = visible.videoChecked
        animationBox.isChecked = visible.animationChecked
        suppressModeCallbacks = false
        if (restartForInputOwnership || !AudioReactiveService.exists()) RuntimeSettings.update { it.copy(renderMode = result.mode) }
        if (!accepted) status.text = getString(R.string.video_change_rejected)
        else if (result.rejected) status.text = getString(R.string.no_mode_selected)
        rebuildEffectSelector()
        refreshConditionalControls()
        MqttControlService.notifyDiagnosticChanged()
    }

    private fun selectOutput(clicked: OutputMode, checked: Boolean) {
        if (AudioReactiveService.exists()) { renderOutputUi(); return }
        val current = RuntimeSettings.snapshot().outputMode
        val next = OutputUiPolicy.modeAfterToggle(current, clicked, checked)
        if (next == current && !checked) { renderOutputUi(); return }
        RuntimeSettings.update { it.copy(outputMode = next) }
        renderOutputUi()
        refreshConditionalControls()
        // Hyperion remains discoverable through a read-only, pinned serverinfo/data-port probe.
        if (next == OutputMode.HYPERION) discover(OutputMode.HYPERION)
    }

    /** Discovery is read-only and is triggered explicitly or after the user selects Hyperion. */
    private fun discover(mode: OutputMode) {
        if (AudioReactiveService.exists()) return
        val discoveryGeneration = discoveryAdmissionGeneration.get()
        status.text = getString(R.string.discovery_searching, UiStrings.outputMode(this, mode))
        discoverButton.isEnabled = false
        work.execute {
            when (mode) {
                OutputMode.WLED -> {
                    val found = WledDiscovery.scan(this@MainActivity)
                    runOnUiThread {
                        if (canMergeDiscovery(discoveryGeneration, mode)) {
                            latestWled = found.map { it.identity }.toSet()
                            RuntimeSettings.update { it.copy(wledDevices = WledInventory.merge(it.wledDevices, found)) }
                            status.text = getString(R.string.discovery_wled_found, found.size)
                            renderOutputUi()
                        }
                        refreshCaptureUi()
                    }
                }
                OutputMode.HYPERION -> {
                    val found = HyperionDiscovery.scan()
                    runOnUiThread {
                        if (canMergeDiscovery(discoveryGeneration, mode)) {
                            latestHyperion = found.map { it.identity }.toSet()
                            RuntimeSettings.update { it.copy(hyperionDevices = HyperionInventory.merge(it.hyperionDevices, found)) }
                            status.text = getString(R.string.discovery_hyperion_found, found.size)
                            renderOutputUi()
                        }
                        refreshCaptureUi()
                    }
                }
            }
        }
    }

    /** Recheck the idle admission epoch on the UI thread immediately before any inventory write. */
    private fun canMergeDiscovery(queuedGeneration: Long, mode: OutputMode): Boolean =
        !isFinishing && RuntimeSettings.snapshot().outputMode == mode && DiscoveryCompletionPolicy.mayMerge(
            queuedGeneration,
            discoveryAdmissionGeneration.get(),
            AudioReactiveService.exists() || captureAdmissionLocked,
        )

    /** Existing main-screen control cycles patterns; it changes this Activity only. */
    private fun cycleLocalVisualPattern() {
        localVisualPatternIndex = LocalVisualPatternPolicy.next(localVisualPatternIndex)
        showLocalVisualPattern(LocalVisualPatternPolicy.patterns[localVisualPatternIndex])
    }

    /** Visual source only: it changes this Activity background and starts no capture/output path. */
    private fun showLocalVisualPattern(pattern: LocalVisualPattern) {
        RainbowVisualSourcePolicy.start()
        rainbowHandler.removeCallbacks(rainbowAnimator)
        rainbowHandler.removeCallbacks(movingBarsAnimator)
        val label = UiStrings.localVisualPattern(this, pattern)
        testButton.text = getString(R.string.screen_test, label)
        status.text = getString(R.string.local_pattern_status, label)
        if (pattern == LocalVisualPattern.RAINBOW) {
            rainbowAnimator.run()
        } else if (pattern.style == LocalVisualStyle.MOVING_BARS) {
            movingBarsDrawable = LocalVisualPatternDrawable(LocalVisualStyle.MOVING_BARS, movingPatternPhase)
            contentRoot.background = movingBarsDrawable
            movingBarsAnimator.run()
        } else if (pattern.style != LocalVisualStyle.SOLID_OR_GRADIENT) {
            contentRoot.background = LocalVisualPatternDrawable(pattern.style)
        } else {
            val colors = requireNotNull(pattern.colors)
            contentRoot.background = if (colors.size == 1) GradientDrawable().apply { setColor(colors.single()) }
            else GradientDrawable(GradientDrawable.Orientation.TL_BR, colors)
        }
    }

    /** Local-only broker editor; save persists first and the MQTT service reconnects via its listener. */
    private fun showMqttSettingsDialog() {
        val current = RuntimeSettings.snapshot().mqttBroker
        fun field(label: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText = EditText(this).apply { hint = label; setText(value); inputType = type }
        val ip = field(getString(R.string.mqtt_ip_hint), current.ip)
        val port = field(getString(R.string.mqtt_port_hint), current.port.toString(), InputType.TYPE_CLASS_NUMBER)
        val username = field(getString(R.string.mqtt_username_hint), current.username)
        val password = field(getString(R.string.mqtt_password_hint), current.password, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 8); addView(ip); addView(port); addView(username); addView(password) }
        AlertDialog.Builder(this).setTitle(R.string.mqtt_dialog_title)
            .setMessage(R.string.mqtt_dialog_message)
            .setView(form).setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null).create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val result = MqttBrokerSettings.fromInput(ip.text.toString(), port.text.toString(), username.text.toString(), password.text.toString())
                        val broker = result.settings
                        if (broker == null) {
                            ip.error = getString(when (result.error) {
                                MqttBrokerSettings.ValidationError.INVALID_PORT -> R.string.invalid_port
                                MqttBrokerSettings.ValidationError.INVALID_BROKER, null -> R.string.invalid_broker
                            })
                            return@setOnClickListener
                        }
                        RuntimeSettings.update { it.copy(mqttBroker = broker) }
                        status.text = getString(R.string.mqtt_saved)
                        dialog.dismiss()
                    }
                }
                dialog.show()
            }
    }

    private fun renderOutputUi() {
        val active = AudioReactiveService.exists()
        val settings = RuntimeSettings.snapshot()
        suppressOutputCallbacks = true
        try {
            hyperionMode.isChecked = settings.outputMode == OutputMode.HYPERION
            wledMode.isChecked = settings.outputMode == OutputMode.WLED
        } finally {
            suppressOutputCallbacks = false
        }
        hyperionMode.isEnabled = !active
        wledMode.isEnabled = !active
        outputRows.removeAllViews()
        if (settings.outputMode == OutputMode.WLED) settings.wledDevices.forEach { device ->
            outputRows.addView(CheckBox(this).apply {
                val stale = device.identity !in latestWled
                text = getString(R.string.device_wled, device.name, device.host, device.leds, if (stale) getString(R.string.device_stale) else "")
                isChecked = device.identity in settings.selectedWledIdentities
                isEnabled = !active
                setOnCheckedChangeListener { _, on ->
                    if (!AudioReactiveService.exists()) {
                        RuntimeSettings.update { current -> current.copy(selectedWledIdentities = if (on) current.selectedWledIdentities + device.identity else current.selectedWledIdentities - device.identity) }
                    }
                }
            })
            outputRows.addView(Button(this).apply { text = if (settings.calibrationFor(device)?.validFor(device)==true) getString(R.string.calibration_existing, device.name) else getString(R.string.calibrate, device.name); isEnabled = !active; setOnClickListener { openCalibrationWizard(device) } })
        } else settings.hyperionDevices.forEach { device ->
            outputRows.addView(CheckBox(this).apply {
                val stale = device.identity !in latestHyperion
                text = getString(R.string.device_hyperion, device.name, device.host, if (stale) getString(R.string.device_stale) else "")
                isChecked = device.identity == settings.selectedHyperionIdentity
                isEnabled = !active
                setOnCheckedChangeListener { _, on ->
                    if (!AudioReactiveService.exists()) {
                        RuntimeSettings.update { current -> current.copy(selectedHyperionIdentity = if (on) device.identity else null) }
                        renderOutputUi()
                    }
                }
            })
        }
    }

    private fun refreshConditionalControls() {
        val settings = effectiveRenderSettings()
        val video = TvUiStatePolicy.showVideoControls(settings.renderMode)
        val audio = TvUiStatePolicy.showAudioControls(settings.renderMode)
        val mixed = TvUiStatePolicy.showVideoAudioControls(settings.renderMode)
        val animation = TvUiStatePolicy.showAnimationControls(settings.renderMode)
        qualityRow.visibility = if (video) View.VISIBLE else View.GONE
        audioSection.visibility = if (audio) View.VISIBLE else View.GONE
        sensitivityRow.visibility = if (audio) View.VISIBLE else View.GONE
        silenceFadeToggle.visibility = if (mixed) View.VISIBLE else View.GONE
        if (silenceFadeToggle.isChecked != settings.silenceFadeEnabled) silenceFadeToggle.isChecked = settings.silenceFadeEnabled
        silenceBrightnessRow.visibility = if (mixed && settings.silenceFadeEnabled) View.VISIBLE else View.GONE
        silenceHoldRow.visibility = if (mixed && settings.silenceFadeEnabled) View.VISIBLE else View.GONE
        silenceFadeRow.visibility = if (mixed && settings.silenceFadeEnabled) View.VISIBLE else View.GONE
        videoColourTreatmentRow.visibility = if (TvUiStatePolicy.showVideoColourTreatment(settings.renderMode)) View.VISIBLE else View.GONE
        suppressVideoColourTreatmentSelection = true
        try {
            videoColourTreatmentSpinner.setSelection(VideoColourTreatmentPolicy.selectedIndex(settings), false)
        } finally {
            suppressVideoColourTreatmentSelection = false
        }
        videoSaturationRow.visibility = if (TvUiStatePolicy.showVideoSaturation(settings.renderMode)) View.VISIBLE else View.GONE
        animationColourRow.visibility = if (animation) View.VISIBLE else View.GONE
        // Animation uses only its palette and speed; audio-only shaping controls are never shown there.
        speedRow.visibility = if (settings.renderMode != RenderMode.VIDEO) View.VISIBLE else View.GONE
        trailRow.visibility = if (audio) View.VISIBLE else View.GONE
        beatThresholdRow.visibility = if (audio) View.VISIBLE else View.GONE
        paletteShiftRow.visibility = if (audio) View.VISIBLE else View.GONE
        effectParametersTitle.visibility = if (speedRow.visibility == View.VISIBLE || trailRow.visibility == View.VISIBLE || beatThresholdRow.visibility == View.VISIBLE || paletteShiftRow.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        videoFpsRow.visibility = View.VISIBLE
        zonesRow.visibility = if (TvUiStatePolicy.showWledZones(settings.outputMode)) View.VISIBLE else View.GONE
    }

    private fun effectiveRenderSettings(): AudioSettings =
        EffectiveRenderSettings.snapshot(RuntimeSettings.snapshot(), AudioReactiveService.exists())

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRemoteAction(intent)
        refreshCaptureUi()
    }

    /** Remote actions only enter the ordinary visible consent flow; route-loss recovery stays local. */
    private fun handleRemoteAction(intent: Intent) {
        val request = MainActivityActionPolicy.parse(intent.action)
        when (MainActivityActionPolicy.decide(request, AudioReactiveService.exists())) {
            MainActivityActionPolicy.Decision.STOP_APP_OWNED_SERVICE -> stopExistingService()
            MainActivityActionPolicy.Decision.REQUEST_VISIBLE_CAPTURE_FLOW -> when (
                RouteRecoveryPolicy.decide(
                    RouteRecoveryPolicy.Origin.REMOTE_ACTION,
                    AudioReactiveService.captureStatus(),
                    AudioReactiveService.exists(),
                    captureAdmissionLocked,
                )
            ) {
                RouteRecoveryPolicy.Decision.ORDINARY_FLOW -> captureToggleCoordinator.toggle()
                RouteRecoveryPolicy.Decision.REQUIRE_LOCAL_BUTTON -> {
                    status.text = getString(R.string.route_lost_local)
                    captureButton.requestFocus()
                }
                RouteRecoveryPolicy.Decision.START_NEW_LOCAL_ADMISSION,
                RouteRecoveryPolicy.Decision.IGNORE -> Unit
            }
            MainActivityActionPolicy.Decision.NONE -> Unit
        }
    }

    override fun onStart() { super.onStart(); ContextCompat.registerReceiver(this, receiver, IntentFilter(AudioReactiveService.ACTION_CAPTURE_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED) }
    override fun onResume() { super.onResume(); refreshCaptureUi(true); releaseUpdater.resumePendingInstall() }
    override fun onStop() { unregisterReceiver(receiver); super.onStop() }
    override fun onDestroy() { invalidatePendingCaptureAdmission(); captureToggleCoordinator.invalidatePending(); rainbowHandler.removeCallbacks(rainbowAnimator); rainbowHandler.removeCallbacks(movingBarsAnimator); RainbowVisualSourcePolicy.stop(); releaseUpdater.close(); work.shutdownNow(); super.onDestroy() }
    private fun handleCaptureToggle() {
        val active = AudioReactiveService.exists()
        if (active) {
            captureToggleCoordinator.toggle()
        } else when (
            RouteRecoveryPolicy.decide(
                RouteRecoveryPolicy.Origin.LOCAL_CAPTURE_BUTTON,
                AudioReactiveService.captureStatus(),
                serviceActive = false,
                admissionPending = captureAdmissionLocked,
            )
        ) {
            RouteRecoveryPolicy.Decision.ORDINARY_FLOW,
            RouteRecoveryPolicy.Decision.START_NEW_LOCAL_ADMISSION -> captureToggleCoordinator.toggle()
            RouteRecoveryPolicy.Decision.REQUIRE_LOCAL_BUTTON,
            RouteRecoveryPolicy.Decision.IGNORE -> Unit
        }
        refreshCaptureUi()
    }

    private fun refreshCaptureUi(updateStatus: Boolean = false) {
        val active = AudioReactiveService.exists()
        val captureStatus = AudioReactiveService.captureStatus()
        captureIndicator.text = CaptureUiPresentation.indicator(this, captureStatus, effectiveRenderSettings().renderMode)
        val mayRecover = RouteRecoveryPolicy.decide(
            RouteRecoveryPolicy.Origin.LOCAL_CAPTURE_BUTTON,
            captureStatus,
            active,
            captureAdmissionLocked,
        ) == RouteRecoveryPolicy.Decision.START_NEW_LOCAL_ADMISSION
        captureButton.text = when {
            active -> getString(R.string.capture_disable)
            mayRecover -> getString(R.string.capture_retry)
            else -> getString(R.string.capture_enable)
        }
        val locked = active || captureAdmissionLocked
        captureButton.isEnabled = !captureAdmissionLocked
        // The local visual source is safe while capture owns a route.
        testButton.isEnabled = !captureAdmissionLocked
        modeMutableRows.forEach { control ->
            // Capture inputs are renderer-local and intentionally remain live; all admission/route settings stay locked.
            val enabled = if (control === audioBox || control === videoBox || control === animationBox) !captureAdmissionLocked else !locked
            if (control is LinearLayout) setChildrenEnabled(control, enabled) else control.isEnabled = enabled
        }
        discoverButton.isEnabled = !locked
        setChildrenEnabled(zonesRow, !locked)
        renderOutputUi()
        refreshConditionalControls()
        if (updateStatus) status.text = UiStrings.captureStatus(this, captureStatus)
    }

    private fun setChildrenEnabled(row: LinearLayout, enabled: Boolean) {
        row.isEnabled = enabled
        for (i in 0 until row.childCount) row.getChildAt(i).isEnabled = enabled
    }

    private fun showDetailedStatus() {
        val s = LocalStatusStore.snapshot()
        val lastSend = when (s.lastSendSucceeded) {
            true -> getString(R.string.status_send_ok)
            false -> getString(R.string.status_send_failed)
            null -> getString(R.string.status_send_none)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.local_status_title)
            .setMessage(getString(R.string.status_detail, UiStrings.captureStatus(this, s.captureStatus), s.outputs.joinToString().ifBlank { getString(R.string.none) }, s.calibrated.joinToString().ifBlank { getString(R.string.none) }, s.skipped.joinToString().ifBlank { getString(R.string.none) }, s.frames, s.fps, lastSend, s.rms, s.peak))
            .setPositiveButton(R.string.close, null)
            .show()
    }
    /** Modal D-pad calibration for exactly one stable MAC. It only edits app preferences on Save. */
    private fun openCalibrationWizard(device: WledDevice) {
        if (AudioReactiveService.exists()) return
        var draft = RuntimeSettings.snapshot().calibrationFor(device) ?: WledScreenCalibration.proportional(device.identity, device.leds)
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 8, 32, 8) }
        var remainingText: TextView? = null
        var directionButton: Button? = null
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.calibration_title, device.name)).setView(ScrollView(this).apply { addView(rows) })
            .setNegativeButton(R.string.discard, null)
            .setNeutralButton(R.string.reset) { _, _ ->
                if (!AudioReactiveService.exists()) RuntimeSettings.update { current -> current.copy(wledCalibrations = current.wledCalibrations.filterNot { it.identity == device.identity }) }
                renderOutputUi()
            }
            .setPositiveButton(R.string.save) { _, _ ->
                if (WledCalibrationWizardPolicy.canSave(AudioReactiveService.exists(), draft, device)) RuntimeSettings.update { current -> current.copy(wledCalibrations = current.wledCalibrations.filterNot { it.identity == device.identity } + draft) }
                renderOutputUi()
            }.create()
        fun render() {
            remainingText?.text = getString(R.string.remaining_leds, WledCalibrationEditor.remaining(draft))
            directionButton?.text = getString(R.string.direction, UiStrings.perimeterDirection(this, draft.direction))
            for (i in 0 until rows.childCount) {
                val row = rows.getChildAt(i)
                if (row is LinearLayout && row.childCount > 0 && row.getChildAt(0) is TextView) {
                    val text = row.getChildAt(0) as TextView
                    @Suppress("UNCHECKED_CAST") val item = text.tag as? Pair<String, () -> String>
                    if (item != null) text.text = "${item.first}: ${item.second()}"
                }
                row.isEnabled = !AudioReactiveService.exists()
                if (row is ViewGroup) for (j in 0 until row.childCount) row.getChildAt(j).isEnabled = !AudioReactiveService.exists()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = !AudioReactiveService.exists() && draft.validFor(device)
        }
        fun addStepper(label: String, value: () -> String, change: (Int) -> Unit) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val valueText = TextView(this).apply { textSize = 17f }
            row.addView(valueText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            fun button(symbol: String, delta: Int) = Button(this).apply { this.text = symbol; isAllCaps = false; setOnClickListener { if (!AudioReactiveService.exists()) { change(delta); render() } } }
            row.addView(button("−", -1)); row.addView(button("+", 1)); rows.addView(row)
            valueText.tag = Pair(label, value)
        }
        fun addAction(label: String, action: () -> Unit): Button = Button(this).apply { this.text = label; isAllCaps = false; setOnClickListener { if (!AudioReactiveService.exists()) action() } }.also(rows::addView)
        val physicalCount = TextView(this).apply { text = getString(R.string.physical_leds_pending, device.identity) }
        rows.addView(physicalCount)
        remainingText = TextView(this).apply { textSize = 17f }
        rows.addView(requireNotNull(remainingText))
        addStepper(getString(R.string.start_pixel), { draft.startPixel.toString() }) { d -> draft = draft.copy(startPixel = Math.floorMod(draft.startPixel + d, device.leds)) }
        directionButton = Button(this).apply { isAllCaps = false; setOnClickListener { if (!AudioReactiveService.exists()) { draft = draft.copy(direction = if (draft.direction == PerimeterDirection.CW) PerimeterDirection.CCW else PerimeterDirection.CW); render() } } }
        rows.addView(requireNotNull(directionButton))
        ScreenEdge.entries.forEach { edge -> addStepper(getString(R.string.edge_leds, UiStrings.screenEdge(this, edge)), { draft.allocation(edge).toString() }) { d -> draft = WledCalibrationEditor.changeAllocation(draft, edge, d) } }
        ScreenEdge.entries.forEach { edge -> addStepper(getString(R.string.edge_inset, UiStrings.screenEdge(this, edge)), { getString(R.string.format_percent, draft.inset(edge)) }) { d -> draft = WledCalibrationEditor.changeInset(draft, edge, d) } }
        addStepper(getString(R.string.sampling_depth), { getString(R.string.format_percent, draft.depthPercent) }) { d -> draft = draft.copy(depthPercent = (draft.depthPercent + d).coerceIn(2, 25)) }
        addStepper(getString(R.string.samples_per_edge), { draft.samplesPerEdge.toString() }) { d -> draft = draft.copy(samplesPerEdge = (draft.samplesPerEdge + d * 4).coerceIn(4, 64) / 4 * 4) }
        addStepper(getString(R.string.gamma), { getString(R.string.format_decimal, draft.gamma) }) { d -> draft = draft.copy(gamma = (draft.gamma + d * .1f).coerceIn(1f, 3.5f)) }
        addStepper(getString(R.string.brightness_limit), { getString(R.string.format_percent, (draft.brightnessLimit * 100).toInt()) }) { d -> draft = draft.copy(brightnessLimit = (draft.brightnessLimit + d * .05f).coerceIn(.05f, 1f)) }
        addAction(getString(R.string.proportional_preset)) { draft = WledScreenCalibration.proportional(device.identity, device.leds); render() }
        rows.addView(TextView(this).apply { text = getString(R.string.calibration_diagnostic_description) })
        WledDiagnosticPattern.entries.forEach { pattern -> addAction(UiStrings.diagnosticPattern(this, pattern)) {
            status.text = getString(R.string.diagnostic_revalidating, device.name)
            work.execute {
                val sent = runCatching { WledDiagnosticAction.execute(RuntimeSettings.snapshot(), device, draft, pattern) }.getOrDefault(false)
                runOnUiThread { if (!isFinishing) { status.text = if (sent) getString(R.string.diagnostic_complete, UiStrings.diagnosticPattern(this, pattern)) else getString(R.string.diagnostic_unavailable); render() } }
            }
        } }
        dialog.setOnShowListener { render() }
        dialog.show()
        // Read-only one-shot count display; selection/calibration is never rewritten from this result.
        work.execute {
            val fresh = runCatching { WledDiscovery.revalidate(listOf(device)).singleOrNull() }.getOrNull()
            runOnUiThread {
                if (!isFinishing && dialog.isShowing) physicalCount.text = if (fresh == device)
                    getString(R.string.physical_leds_verified, device.identity, fresh.leds)
                else getString(R.string.physical_leds_unavailable, device.identity)
            }
        }
    }

    override fun serviceExists() = AudioReactiveService.exists()
    override fun hasRecordAudioPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    override fun stopExistingService() = AudioReactiveService.stopExisting(this)
    override fun requestRecordAudioPermission(generation: Long) {
        pendingPermissionGeneration = generation
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }
    override fun requestMediaProjectionConsent(generation: Long) {
        pendingProjectionGeneration = generation
        startActivityForResult((getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).createScreenCaptureIntent(), captureRequest)
    }
    /** Cancel every one-shot route before a replacement admission can be created. */
    private fun invalidatePendingCaptureAdmission() {
        capturePreflightGeneration.incrementAndGet()
        WledRouteBindings.discard(pendingWled); HyperionRouteBindings.discard(pendingHyperion)
        pendingWled = null; pendingHyperion = null
        pendingPermissionGeneration = null; pendingProjectionGeneration = null; pendingAdmissionGeneration = null
        captureAdmissionLocked = false
    }
    override fun prepareOutputForCapture(admissionGeneration: Long, onReady: () -> Unit, onDenied: () -> Unit) {
        invalidatePendingCaptureAdmission()
        discoveryAdmissionGeneration.incrementAndGet()
        pendingAdmissionGeneration = admissionGeneration
        val settings = RuntimeSettings.snapshot()
        val generation = capturePreflightGeneration.incrementAndGet()
        val cancelled = { generation != capturePreflightGeneration.get() || isFinishing || isDestroyed || AudioReactiveService.exists() }
        captureAdmissionLocked = true
        refreshCaptureUi()
        status.text = getString(R.string.preflight_selected, UiStrings.outputMode(this, settings.outputMode))
        work.execute {
            val progress: (Int) -> Unit = { attempt -> runOnUiThread { if (!cancelled()) status.text = getString(R.string.preflight_attempt, UiStrings.outputMode(this, settings.outputMode), attempt, CapturePreflightRetry.MAX_ATTEMPTS) } }
            val wledResult = if (settings.outputMode == OutputMode.WLED) CapturePreflightRetry.bind(cancelled, progress) { WledCapturePreflight.bind(settings) } else null
            val hyperionResult = if (settings.outputMode == OutputMode.HYPERION) CapturePreflightRetry.bind(cancelled, progress) { HyperionCapturePreflight.bind(settings) } else null
            val wled = wledResult?.binding
            val hyperion = hyperionResult?.binding
            val attempts = wledResult?.attempts ?: hyperionResult?.attempts ?: 0
            runOnUiThread {
                if (cancelled()) { WledRouteBindings.discard(wled); HyperionRouteBindings.discard(hyperion); return@runOnUiThread }
                if (wled == null && hyperion == null) {
                    captureAdmissionLocked = false
                    status.text = getString(R.string.preflight_failed, attempts)
                    onDenied()
                } else {
                    pendingWled = wled
                    pendingHyperion = hyperion
                    onReady()
                }
            }
        }
    }
    override fun startsWithoutCaptureInputs() = RuntimeSettings.snapshot().isNoInputAnimation()
    override fun startNoInputAnimation(admissionGeneration: Long) {
        if (pendingAdmissionGeneration != admissionGeneration) { invalidatePendingCaptureAdmission(); return }
        capturePreflightGeneration.incrementAndGet()
        ContextCompat.startForegroundService(this, Intent(this, AudioReactiveService::class.java)
            .putExtra(AudioReactiveService.EXTRA_NO_INPUT_ANIMATION, true)
            .putExtra(AudioReactiveService.EXTRA_WLED_ROUTE_BINDING, pendingWled)
            .putExtra(AudioReactiveService.EXTRA_HYPERION_ROUTE_BINDING, pendingHyperion)
            .putExtra(AudioReactiveService.EXTRA_ADMISSION_GENERATION, admissionGeneration))
    }
    override fun startCapture(admissionGeneration: Long, resultCode: Int, data: Intent) {
        if (pendingAdmissionGeneration != admissionGeneration) {
            invalidatePendingCaptureAdmission()
            return
        }
        capturePreflightGeneration.incrementAndGet()
        val wled = pendingWled
        val hyperion = pendingHyperion
        pendingPermissionGeneration = null; pendingProjectionGeneration = null
        ContextCompat.startForegroundService(this, Intent(this, AudioReactiveService::class.java)
            .putExtra(AudioReactiveService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(AudioReactiveService.EXTRA_RESULT_DATA, data)
            .putExtra(AudioReactiveService.EXTRA_WLED_ROUTE_BINDING, wled)
            .putExtra(AudioReactiveService.EXTRA_HYPERION_ROUTE_BINDING, hyperion)
            .putExtra(AudioReactiveService.EXTRA_ADMISSION_GENERATION, admissionGeneration))
    }
    override fun onStoppedExistingService() { refreshCaptureUi() }
    override fun onCaptureStartDenied() { invalidatePendingCaptureAdmission(); refreshCaptureUi() }
    override fun onCaptureStartApproved() { refreshCaptureUi() }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, permissions, grants)
        if (code == 1) pendingPermissionGeneration?.let { generation ->
            pendingPermissionGeneration = null
            captureToggleCoordinator.onRecordAudioPermissionResult(generation, grants.firstOrNull() == PackageManager.PERMISSION_GRANTED)
        }
    }
    @Deprecated("API callback")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        if (code == captureRequest) pendingProjectionGeneration?.let { generation ->
            pendingProjectionGeneration = null
            captureToggleCoordinator.onMediaProjectionConsentResult(generation, result, data)
        }
    }
}
