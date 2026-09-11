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
import android.graphics.drawable.StateListDrawable
import android.graphics.drawable.Drawable
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Labels and tab contract shared with pure UI-policy tests. */
object OutputUiPolicy {
    const val ENABLE = "Увімкнути"
    const val DISABLE = "Вимкнути"
    val sections = listOf("toggle", "capture-mode", "effects", "outputs", "discovery", "settings")
    fun modeAfterToggle(current: OutputMode, clicked: OutputMode, checked: Boolean) = if (checked) clicked else current
    fun handlesCheckboxChange(synchronizing: Boolean) = !synchronizing
}

/** Short, stable TV-facing summary; detailed diagnostics stay behind the local status action. */
object CaptureUiPresentation {
    fun indicator(status: CaptureStatus, mode: RenderMode): String =
        "Захоплення: ${stateLabel(status)} · Режим: ${modeLabel(mode)}"

    private fun stateLabel(status: CaptureStatus) = when (status) {
        CaptureStatus.NEEDS_MEDIA_PROJECTION_CONSENT -> "Готово"
        CaptureStatus.PREPARING_PROJECTION, CaptureStatus.PREPARING_AUDIO_RECORD -> "Підготовка"
        CaptureStatus.ROUTE_LOST -> "Вихід втрачено"
        CaptureStatus.ROUTER_INIT_FAILED, CaptureStatus.AUDIO_RECORD_INIT_FAILED,
        CaptureStatus.AUDIO_RECORD_START_FAILED, CaptureStatus.AUDIO_RECORD_INIT_TIMEOUT -> "Помилка запуску"
        CaptureStatus.VIDEO_UNAVAILABLE_OR_PROTECTED -> "Відео недоступне"
        CaptureStatus.CAPTURE_ACTIVE, CaptureStatus.CAPTURE_ACTIVE_AUDIO,
        CaptureStatus.CAPTURE_ACTIVE_VIDEO, CaptureStatus.CAPTURE_ACTIVE_VIDEO_AUDIO -> "Активне"
    }

    private fun modeLabel(mode: RenderMode) = when (mode) {
        RenderMode.AUDIO -> "Аудіо"
        RenderMode.VIDEO -> "Відео"
        RenderMode.VIDEO_AUDIO -> "Аудіо + відео"
    }
}

/** Local-only visual patterns. The existing main-screen button cycles these without an output route. */
enum class LocalVisualPattern(
    val label: String,
    val colors: IntArray? = null,
    val style: LocalVisualStyle = LocalVisualStyle.SOLID_OR_GRADIENT,
) {
    RAINBOW("Райдужна анімація", style = LocalVisualStyle.RAINBOW),
    BLACK("Чорний", intArrayOf(Color.BLACK)),
    WHITE("Білий", intArrayOf(Color.WHITE)),
    RED("Червоний", intArrayOf(Color.RED)),
    GREEN("Зелений", intArrayOf(Color.GREEN)),
    BLUE("Синій", intArrayOf(Color.BLUE)),
    RGB("RGB", intArrayOf(Color.RED, Color.GREEN, Color.BLUE)),
    HORIZONTAL_BARS("Горизонтальні кольорові смуги", style = LocalVisualStyle.HORIZONTAL_BARS),
    VERTICAL_BARS("Вертикальні кольорові смуги", style = LocalVisualStyle.VERTICAL_BARS),
    CORNER_COLOURS("Кольори сторін і кутів", style = LocalVisualStyle.CORNER_COLOURS),
    COLOUR_WHEEL("Кольорове коло", style = LocalVisualStyle.COLOUR_WHEEL),
    CHECKERBOARD("Шахівниця", style = LocalVisualStyle.CHECKERBOARD),
    GRAYSCALE_RAMP("Градація сірого", style = LocalVisualStyle.GRAYSCALE_RAMP),
    MOVING_BARS("Рухомі кольорові смуги", style = LocalVisualStyle.MOVING_BARS),
}

enum class LocalVisualStyle {
    SOLID_OR_GRADIENT, RAINBOW, HORIZONTAL_BARS, VERTICAL_BARS, CORNER_COLOURS,
    COLOUR_WHEEL, CHECKERBOARD, GRAYSCALE_RAMP, MOVING_BARS;

    val animated get() = this == RAINBOW || this == MOVING_BARS
}

/** Pure cycle contract so every visible pattern remains reachable from the one local button. */
object LocalVisualPatternPolicy {
    val patterns: List<LocalVisualPattern> = LocalVisualPattern.entries
    fun next(index: Int): Int = (index + 1) % patterns.size
}

/** Procedural display-only drawable; it owns no capture, route, socket, or output state. */
private class LocalVisualPatternDrawable(
    private val style: LocalVisualStyle,
    private var phase: Float = 0f,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bars = intArrayOf(Color.WHITE, Color.YELLOW, Color.CYAN, Color.GREEN, Color.MAGENTA, Color.RED, Color.BLUE)

    override fun draw(canvas: Canvas) {
        val width = bounds.width().toFloat().coerceAtLeast(1f)
        val height = bounds.height().toFloat().coerceAtLeast(1f)
        when (style) {
            LocalVisualStyle.HORIZONTAL_BARS -> drawBars(canvas, width, height, horizontal = true)
            LocalVisualStyle.VERTICAL_BARS -> drawBars(canvas, width, height, horizontal = false)
            LocalVisualStyle.CORNER_COLOURS -> drawCorners(canvas, width, height)
            LocalVisualStyle.COLOUR_WHEEL -> {
                paint.shader = SweepGradient(width / 2f, height / 2f, intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED), null)
                canvas.drawRect(0f, 0f, width, height, paint)
                paint.shader = null
            }
            LocalVisualStyle.CHECKERBOARD -> drawCheckerboard(canvas, width, height)
            LocalVisualStyle.GRAYSCALE_RAMP -> {
                paint.shader = LinearGradient(0f, 0f, width, 0f, Color.BLACK, Color.WHITE, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, width, height, paint)
                paint.shader = null
            }
            LocalVisualStyle.MOVING_BARS -> drawMovingBars(canvas, width, height)
            else -> error("Drawable is only used for procedural local patterns")
        }
    }

    private fun drawBars(canvas: Canvas, width: Float, height: Float, horizontal: Boolean) {
        bars.forEachIndexed { index, color ->
            paint.color = color
            if (horizontal) canvas.drawRect(0f, height * index / bars.size, width, height * (index + 1) / bars.size, paint)
            else canvas.drawRect(width * index / bars.size, 0f, width * (index + 1) / bars.size, height, paint)
        }
    }

    private fun drawCorners(canvas: Canvas, width: Float, height: Float) {
        val cells = arrayOf(
            intArrayOf(Color.YELLOW, Color.RED, Color.MAGENTA),
            intArrayOf(Color.WHITE, Color.BLACK, Color.GREEN),
            intArrayOf(Color.CYAN, Color.BLUE, Color.WHITE),
        )
        cells.forEachIndexed { row, colours -> colours.forEachIndexed { column, color ->
            paint.color = color
            canvas.drawRect(width * column / 3f, height * row / 3f, width * (column + 1) / 3f, height * (row + 1) / 3f, paint)
        } }
    }

    private fun drawCheckerboard(canvas: Canvas, width: Float, height: Float) {
        val cell = (minOf(width, height) / 8f).coerceAtLeast(1f)
        var y = 0f
        var row = 0
        while (y < height) {
            var x = 0f
            var column = 0
            while (x < width) {
                paint.color = if ((row + column) % 2 == 0) Color.WHITE else Color.BLACK
                canvas.drawRect(x, y, x + cell, y + cell, paint)
                x += cell; column++
            }
            y += cell; row++
        }
    }

    private fun drawMovingBars(canvas: Canvas, width: Float, height: Float) {
        val barWidth = width / bars.size
        bars.forEachIndexed { index, color ->
            paint.color = color
            val left = ((index * barWidth + phase * width) % (width + barWidth)) - barWidth
            canvas.drawRect(left, 0f, left + barWidth, height, paint)
            canvas.drawRect(left - (width + barWidth), 0f, left - (width + barWidth) + barWidth, height, paint)
        }
    }

    /** Redraw the existing local-only moving-bars surface without allocating a new Drawable. */
    fun updatePhase(value: Float) {
        phase = value
        invalidateSelf()
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
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
    private lateinit var recoveryButton: Button
    private lateinit var testButton: Button
    private lateinit var updateButton: Button
    private lateinit var audioBox: CheckBox
    private lateinit var videoBox: CheckBox
    private lateinit var effectSpinner: Spinner
    private lateinit var hyperionMode: CheckBox
    private lateinit var wledMode: CheckBox
    private lateinit var outputRows: LinearLayout
    private lateinit var qualityRow: LinearLayout
    private lateinit var videoFpsRow: LinearLayout
    private lateinit var videoColourTreatmentRow: LinearLayout
    private lateinit var videoColourTreatmentSpinner: Spinner
    private lateinit var videoSaturationRow: LinearLayout
    private lateinit var zonesRow: LinearLayout
    private lateinit var discoverButton: Button
    private lateinit var controlTab: TextView
    private lateinit var additionalTab: TextView
    private val tabPanels = mutableListOf<View>()
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
                    status.text = "Не вдалося прийняти вихід для захоплення; нічого не розпочато."
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
        root.addView(TextView(this).apply { text = "Audio Reactive TV"; textSize = 26f })
        captureIndicator = TextView(this).apply { textSize = 19f }
        root.addView(captureIndicator)
        status = TextView(this).apply { textSize = 15f; maxLines = 2 }
        root.addView(status)
        recoveryButton = Button(this).apply {
            id = View.generateViewId()
            text = "Повторно перевірити й увімкнути"
            contentDescription = "Локально повторно перевірити вихід, потім запросити новий дозвіл захоплення"
            setOnClickListener { handleCaptureToggle() }
            visibility = View.GONE
        }
        root.addView(recoveryButton)

        val tabLayoutContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controlTab = tabView(LeanbackTabPolicy.tabs[0]) { showTab(0) }
        additionalTab = tabView(LeanbackTabPolicy.tabs[1]) { showTab(1) }
        tabBar.addView(controlTab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tabBar.addView(additionalTab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tabLayoutContainer.addView(tabBar)
        val controlPanel = LinearLayout(this).apply { id = View.generateViewId(); orientation = LinearLayout.VERTICAL }
        val configurationPanel = LinearLayout(this).apply { id = View.generateViewId(); orientation = LinearLayout.VERTICAL }
        fun scrollablePanel(panel: LinearLayout) = ScrollView(this).apply {
            id = View.generateViewId()
            isFillViewport = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            addView(panel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val controlScroll = scrollablePanel(controlPanel)
        val configurationScroll = scrollablePanel(configurationPanel)
        tabPanels += listOf(controlScroll, configurationScroll)
        tabPanels.forEach { panel ->
            tabLayoutContainer.addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        root.addView(tabLayoutContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        buildControlTab(controlPanel)
        buildModesTab(configurationPanel)
        buildOutputsTab(configurationPanel)
        buildAdditionalTools(configurationPanel)
        setContentView(root)
        controlTab.nextFocusDownId = captureButton.id
        additionalTab.nextFocusDownId = qualityRow.getChildAt(1).id
        controlTab.nextFocusLeftId = controlTab.id
        controlTab.nextFocusRightId = additionalTab.id
        additionalTab.nextFocusLeftId = controlTab.id
        additionalTab.nextFocusRightId = additionalTab.id
        showTab(0)
        refreshCaptureUi()
        captureButton.requestFocus()
        releaseUpdater = GitHubReleaseUpdater(
            applicationContext,
            onStatus = { message, busy ->
                if (!isFinishing && !isDestroyed) {
                    status.text = message
                    updateButton.isEnabled = !busy
                }
            },
            onUpdateAvailable = {
                if (!isFinishing && !isDestroyed) {
                    updateButton.text = "Оновити"
                    updateButton.contentDescription = "Завантажити, перевірити й відкрити системне підтвердження оновлення"
                    updateButton.setOnClickListener { releaseUpdater.updateSelectedRelease() }
                }
            },
        )
        // Launch check fetches release metadata only; update remains an explicit visible D-pad action.
        releaseUpdater.checkForUpdate()
        handleRemoteAction(intent)
    }

    /** Uses two ordinary focusable text tabs instead of theme-dependent tab indicators. */
    private fun showTab(requested: Int) {
        val selected = TvTabSelectionPolicy.selectedIndex(requested)
        tabPanels.forEachIndexed { index, panel -> panel.visibility = if (index == selected) View.VISIBLE else View.GONE }
        controlTab.isSelected = selected == 0
        additionalTab.isSelected = selected == 1
    }

    private fun tabView(label: String, onClick: () -> Unit) = TextView(this).apply {
        id = View.generateViewId()
        text = label
        textSize = 18f
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        setPadding(24, 16, 24, 16)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_selected, android.R.attr.state_focused), tabBackground(Color.rgb(42, 105, 170), Color.WHITE))
            addState(intArrayOf(android.R.attr.state_focused), tabBackground(Color.rgb(55, 55, 55), Color.WHITE))
            addState(intArrayOf(android.R.attr.state_selected), tabBackground(Color.rgb(42, 105, 170), Color.rgb(150, 210, 255)))
            addState(intArrayOf(), tabBackground(Color.TRANSPARENT, Color.DKGRAY))
        }
        setOnClickListener { onClick() }
    }

    private fun tabBackground(fill: Int, stroke: Int) = GradientDrawable().apply {
        setColor(fill)
        setStroke(2, stroke)
        cornerRadius = 10f
    }

    /** The first interactive control in the first tab remains the capture toggle. */
    private fun buildControlTab(panel: LinearLayout) {
        captureButton = Button(this).apply { id = View.generateViewId(); setOnClickListener { handleCaptureToggle() } }
        panel.addView(captureButton)
        panel.addView(TextView(this).apply { text = "Режим захоплення" })
        val initial = RuntimeSettings.snapshot().renderMode
        audioBox = CheckBox(this).apply { id = View.generateViewId(); text = "Аудіо"; isChecked = initial != RenderMode.VIDEO }
        videoBox = CheckBox(this).apply { id = View.generateViewId(); text = "Відео"; isChecked = initial != RenderMode.AUDIO }
        val listener = CompoundButton.OnCheckedChangeListener { _, _ -> resolveCaptureMode() }
        audioBox.setOnCheckedChangeListener(listener); videoBox.setOnCheckedChangeListener(listener)
        panel.addView(audioBox); panel.addView(videoBox)
        modeMutableRows += audioBox; modeMutableRows += videoBox
        addEffectSelector(panel)
        testButton = Button(this).apply {
            text = "Тест екрана: ${LocalVisualPattern.entries[localVisualPatternIndex].label}"
            contentDescription = "Повноекранне локальне тестове зображення; без захоплення, маршруту, сокета або виходу"
            setOnClickListener { cycleLocalVisualPattern() }
        }
        panel.addView(testButton)
    }

    /** Technical and maintenance actions live in the Additional tab, away from primary capture controls. */
    private fun buildAdditionalTools(panel: LinearLayout) {
        panel.addView(TextView(this).apply { text = "Додатково"; textSize = 18f })
        panel.addView(Button(this).apply { text = "Детальний локальний стан"; setOnClickListener { showDetailedStatus() } })
        updateButton = Button(this).apply {
            text = "Перевірити оновлення"
            contentDescription = "Перевірити GitHub Releases лише за метаданими; завантаження і встановлення потребують окремих дій"
            setOnClickListener { releaseUpdater.checkForUpdate() }
        }
        panel.addView(updateButton)
    }

    /** Compatible selector stays enabled: while active it mutates renderer-local state only. */
    private fun addEffectSelector(panel: LinearLayout) {
        panel.addView(TextView(this).apply { text = "Ефект" })
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
            effectSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, EffectSelectorPolicy.labels(settings))
            effectSpinner.setSelection(EffectSelectorPolicy.selectedIndex(settings), false)
        } finally {
            suppressEffectSelection = false
        }
    }

    private fun buildModesTab(panel: LinearLayout) {
        qualityRow = LinearLayout(this).apply { id = View.generateViewId(); orientation = LinearLayout.VERTICAL }
        qualityRow.addView(TextView(this).apply { text = "Якість відео Hyperion" })
        qualityRow.addView(Spinner(this).apply {
            id = View.generateViewId()
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, VideoQuality.entries.map { it.label })
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
        videoFpsRow = sliderRow("FPS (Аудіо / Відео / Аудіо+відео)", VideoCapturePolicy.fpsOptions.indexOf(RuntimeSettings.snapshot().fps).coerceAtLeast(0), VideoCapturePolicy.fpsOptions.lastIndex, { "${VideoCapturePolicy.fpsOptions[it]} fps" }) {
            RuntimeSettings.update { settings -> settings.copy(fps = VideoCapturePolicy.fpsOptions[it]) }
        }
        panel.addView(videoFpsRow)
        modeMutableRows += videoFpsRow

        panel.addView(TextView(this).apply { text = "Звук" })
        sliderRow("Чутливість", ((RuntimeSettings.snapshot().sensitivity - .25f) / .05f).toInt(), 60, { SliderFormatters.sensitivity(.25f + it * .05f) }) {
            updateSensitivity(.25f + it * .05f)
        }.also(panel::addView)
        sliderRow("Яскравість", (RuntimeSettings.snapshot().brightness / .05f).toInt(), 20, { SliderFormatters.brightness(it * .05f) }) {
            updateBrightness(it * .05f)
        }.also(panel::addView)
        sliderRow("Мінімальна яскравість без звуку", (RuntimeSettings.snapshot().videoAudioSilenceBrightnessFloor / .05f).toInt(), 20, { SliderFormatters.brightness(it * .05f) }) {
            updateVideoAudioSilenceBrightnessFloor(it * .05f)
        }.also(panel::addView)
        sliderRow("Затримка тиші", RuntimeSettings.snapshot().silenceHoldMillis / 100, 30, { "${it * 100} мс" }) { updateSilenceHoldMillis(it * 100) }.also(panel::addView)
        sliderRow("Плавність тиші", (RuntimeSettings.snapshot().silenceFadeMillis - 100) / 100, 19, { "${100 + it * 100} мс" }) { updateSilenceFadeMillis(100 + it * 100) }.also(panel::addView)
        panel.addView(TextView(this).apply { text = "Параметри ефекту" })
        videoColourTreatmentRow = LinearLayout(this).apply { id = View.generateViewId(); orientation = LinearLayout.VERTICAL }
        videoColourTreatmentRow.addView(TextView(this).apply { text = "Базова обробка кольору відео" })
        videoColourTreatmentSpinner = Spinner(this).apply {
            id = View.generateViewId()
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, VideoColourTreatmentPolicy.labels())
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
        videoSaturationRow = sliderRow("Насиченість відео", RuntimeSettings.snapshot().videoSaturationPercent, VideoSaturationPolicy.MAX_PERCENT, { "$it%" }) { value ->
            if (AudioReactiveService.exists()) LiveRendererSettings.setVideoSaturationPercent(value)
            else RuntimeSettings.update { it.copy(videoSaturationPercent = value) }
        }
        panel.addView(videoSaturationRow)
        sliderRow("Швидкість", ((RuntimeSettings.snapshot().effectParameters.speed - .25f) / .25f).toInt(), 11, { "${.25f + it * .25f}×" }) { v -> updateEffectParameters { it.copy(speed = .25f + v * .25f) } }.also { panel.addView(it) }
        sliderRow("Слід", (RuntimeSettings.snapshot().effectParameters.trail * 10).toInt(), 10, { "${it * 10}%" }) { v -> updateEffectParameters { it.copy(trail = v / 10f) } }.also { panel.addView(it) }
        sliderRow("Поріг біту", ((RuntimeSettings.snapshot().effectParameters.beatThreshold - .05f) / .05f).toInt(), 18, { "${5 + it * 5}%" }) { v -> updateEffectParameters { it.copy(beatThreshold = .05f + v * .05f) } }.also { panel.addView(it) }
        sliderRow("Зсув палітри", ((RuntimeSettings.snapshot().effectParameters.hueShift + 180f) / 15f).toInt(), 24, { "${-180 + it * 15}°" }) { v -> updateEffectParameters { it.copy(hueShift = -180f + v * 15f) } }.also { panel.addView(it) }
    }

    private fun buildOutputsTab(panel: LinearLayout) {
        panel.addView(TextView(this).apply { text = "Вихід (Hyperion або WLED)" })
        hyperionMode = CheckBox(this).apply { id = View.generateViewId(); text = "Hyperion" }
        wledMode = CheckBox(this).apply { id = View.generateViewId(); text = "WLED" }
        hyperionMode.setOnCheckedChangeListener { _, checked -> if (OutputUiPolicy.handlesCheckboxChange(suppressOutputCallbacks)) selectOutput(OutputMode.HYPERION, checked) }
        wledMode.setOnCheckedChangeListener { _, checked -> if (OutputUiPolicy.handlesCheckboxChange(suppressOutputCallbacks)) selectOutput(OutputMode.WLED, checked) }
        panel.addView(hyperionMode)
        panel.addView(wledMode)
        discoverButton = Button(this).apply { text = "Знайти вибрані виходи"; setOnClickListener { discover(RuntimeSettings.snapshot().outputMode) } }
        panel.addView(discoverButton)
        outputRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(outputRows)
        zonesRow = sliderRow("Зони джерела WLED", RuntimeSettings.snapshot().wledSourceZones / 16 - 1, 31, { "${(it + 1) * 16}" }) {
            RuntimeSettings.update { settings -> settings.copy(wledSourceZones = (it + 1) * 16) }
        }
        panel.addView(zonesRow)
        panel.addView(Button(this).apply { text = "Налаштування MQTT"; setOnClickListener { showMqttSettingsDialog() } })
        panel.addView(TextView(this).apply { text = "Home Assistant MQTT: приватний LAN broker. Адреса, порт і облікові дані редагуються локально; пароль не публікується." })
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


    private fun sliderRow(label: String, initial: Int, max: Int, format: (Int) -> String, apply: (Int) -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val value = TextView(this@MainActivity)
            fun show(progress: Int) { value.text = "$label: ${format(progress)}" }
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
                        if (fromUser && (!AudioReactiveService.exists() || LiveRendererControlPolicy.sliderMutable(label))) apply(progress)
                    }
                })
            })
        }


    private fun resolveCaptureMode() {
        if (suppressModeCallbacks) return
        val persisted = RuntimeSettings.snapshot()
        val previous = effectiveRenderSettings().renderMode
        val result = CaptureModeCheckboxPolicy.resolve(audioBox.isChecked, videoBox.isChecked, previous)
        val accepted = !AudioReactiveService.exists() || LiveRendererSettings.setRenderMode(result.mode)
        val visible = LiveRenderModeUiPolicy.checkboxes(
            if (accepted) {
                if (AudioReactiveService.exists()) effectiveRenderSettings().renderMode else result.mode
            } else previous
        )
        suppressModeCallbacks = true
        audioBox.isChecked = visible.audioChecked
        videoBox.isChecked = visible.videoChecked
        suppressModeCallbacks = false
        if (!AudioReactiveService.exists()) RuntimeSettings.update { it.copy(renderMode = result.mode) }
        if (!accepted) status.text = "Перехід до відео відхилено: WLED потребує зупинки, перевірки та перезапуску."
        else if (result.rejected) status.text = "Потрібен щонайменше один режим."
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
        status.text = "Шукаю локальні ${mode.label}…"
        discoverButton.isEnabled = false
        work.execute {
            when (mode) {
                OutputMode.WLED -> {
                    val found = WledDiscovery.scan(this@MainActivity)
                    runOnUiThread {
                        if (canMergeDiscovery(discoveryGeneration, mode)) {
                            latestWled = found.map { it.identity }.toSet()
                            RuntimeSettings.update { it.copy(wledDevices = WledInventory.merge(it.wledDevices, found)) }
                            status.text = "Знайдено WLED: ${found.size}"
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
                            status.text = "Знайдено Hyperion: ${found.size}"
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
        testButton.text = "Тест екрана: ${pattern.label}"
        status.text = "Локальний шаблон: ${pattern.label}. Мережевий вихід не використовується."
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
        val ip = field("IPv4 broker", current.ip)
        val port = field("Порт", current.port.toString(), InputType.TYPE_CLASS_NUMBER)
        val username = field("Логін (необов’язково)", current.username)
        val password = field("Пароль (необов’язково)", current.password, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 8); addView(ip); addView(port); addView(username); addView(password) }
        AlertDialog.Builder(this).setTitle("Home Assistant MQTT")
            .setMessage("Дозволено лише literal private RFC1918 IPv4 та порт 1–65535. Порожній логін використовує anonymous MQTT.")
            .setView(form).setNegativeButton("Скасувати", null)
            .setPositiveButton("Зберегти", null).create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val result = MqttBrokerSettings.fromInput(ip.text.toString(), port.text.toString(), username.text.toString(), password.text.toString())
                        val broker = result.settings
                        if (broker == null) { ip.error = result.error; return@setOnClickListener }
                        RuntimeSettings.update { it.copy(mqttBroker = broker) }
                        status.text = "MQTT broker збережено; перепідключення виконується."
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
                text = "${device.name} (${device.host}, ${device.leds} LED)" + if (stale) " — недоступний, потрібна перевірка" else ""
                isChecked = device.identity in settings.selectedWledIdentities
                isEnabled = !active
                setOnCheckedChangeListener { _, on ->
                    if (!AudioReactiveService.exists()) {
                        RuntimeSettings.update { current -> current.copy(selectedWledIdentities = if (on) current.selectedWledIdentities + device.identity else current.selectedWledIdentities - device.identity) }
                    }
                }
            })
            outputRows.addView(Button(this).apply { text = if (settings.calibrationFor(device)?.validFor(device)==true) "Калібрування: ${device.name}" else "Калібрувати ${device.name}"; isEnabled = !active; setOnClickListener { openCalibrationWizard(device) } })
        } else settings.hyperionDevices.forEach { device ->
            outputRows.addView(CheckBox(this).apply {
                val stale = device.identity !in latestHyperion
                text = "${device.name} (${device.host})" + if (stale) " — недоступний, потрібна перевірка" else ""
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
        qualityRow.visibility = if (video) View.VISIBLE else View.GONE
        videoColourTreatmentRow.visibility = if (TvUiStatePolicy.showVideoColourTreatment(settings.renderMode)) View.VISIBLE else View.GONE
        suppressVideoColourTreatmentSelection = true
        try {
            videoColourTreatmentSpinner.setSelection(VideoColourTreatmentPolicy.selectedIndex(settings), false)
        } finally {
            suppressVideoColourTreatmentSelection = false
        }
        videoSaturationRow.visibility = if (TvUiStatePolicy.showVideoSaturation(settings.renderMode)) View.VISIBLE else View.GONE
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
                    status.text = "Вихід втрачено. Повторіть спробу локально кнопкою захоплення."
                    captureButton.requestFocus()
                }
                RouteRecoveryPolicy.Decision.START_NEW_LOCAL_ADMISSION,
                RouteRecoveryPolicy.Decision.IGNORE -> Unit
            }
            MainActivityActionPolicy.Decision.NONE -> Unit
        }
    }

    override fun onStart() { super.onStart(); ContextCompat.registerReceiver(this, receiver, IntentFilter(AudioReactiveService.ACTION_CAPTURE_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED) }
    override fun onResume() { super.onResume(); refreshCaptureUi(true) }
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
        captureIndicator.text = CaptureUiPresentation.indicator(captureStatus, effectiveRenderSettings().renderMode)
        captureButton.text = if (active) OutputUiPolicy.DISABLE else OutputUiPolicy.ENABLE
        val locked = active || captureAdmissionLocked
        captureButton.isEnabled = !captureAdmissionLocked
        val mayRecover = RouteRecoveryPolicy.decide(
            RouteRecoveryPolicy.Origin.LOCAL_CAPTURE_BUTTON,
            captureStatus,
            active,
            captureAdmissionLocked,
        ) == RouteRecoveryPolicy.Decision.START_NEW_LOCAL_ADMISSION
        recoveryButton.visibility = if (mayRecover) View.VISIBLE else View.GONE
        recoveryButton.isEnabled = mayRecover
        // The local visual source is safe while capture owns a route.
        testButton.isEnabled = !captureAdmissionLocked
        modeMutableRows.forEach { control ->
            // Capture inputs are renderer-local and intentionally remain live; all admission/route settings stay locked.
            val enabled = if (control === audioBox || control === videoBox) !captureAdmissionLocked else !locked
            if (control is LinearLayout) setChildrenEnabled(control, enabled) else control.isEnabled = enabled
        }
        discoverButton.isEnabled = !locked
        setChildrenEnabled(zonesRow, !locked)
        renderOutputUi()
        refreshConditionalControls()
        if (updateStatus) status.text = captureStatus.uiText
    }

    private fun setChildrenEnabled(row: LinearLayout, enabled: Boolean) {
        row.isEnabled = enabled
        for (i in 0 until row.childCount) row.getChildAt(i).isEnabled = enabled
    }

    private fun showDetailedStatus() { val s=LocalStatusStore.snapshot(); AlertDialog.Builder(this).setTitle("Локальний стан").setMessage("Стадія: ${s.stage}\nВиходи: ${s.outputs.joinToString().ifBlank{"—"}}\nКалібровані: ${s.calibrated.joinToString().ifBlank{"—"}}\nПропущені: ${s.skipped.joinToString().ifBlank{"—"}}\nКадри: ${s.frames}; FPS: ${"%.1f".format(s.fps)}\nОстання відправка: ${s.lastSend}\nRMS: ${"%.3f".format(s.rms)}; peak: ${"%.3f".format(s.peak)}").setPositiveButton("Закрити",null).show() }
    /** Modal D-pad calibration for exactly one stable MAC. It only edits app preferences on Save. */
    private fun openCalibrationWizard(device: WledDevice) {
        if (AudioReactiveService.exists()) return
        var draft = RuntimeSettings.snapshot().calibrationFor(device) ?: WledScreenCalibration.proportional(device.identity, device.leds)
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 8, 32, 8) }
        var remainingText: TextView? = null
        var directionButton: Button? = null
        val dialog = AlertDialog.Builder(this).setTitle("Калібрування ${device.name}").setView(ScrollView(this).apply { addView(rows) })
            .setNegativeButton("Відкинути", null)
            .setNeutralButton("Скинути") { _, _ ->
                if (!AudioReactiveService.exists()) RuntimeSettings.update { current -> current.copy(wledCalibrations = current.wledCalibrations.filterNot { it.identity == device.identity }) }
                renderOutputUi()
            }
            .setPositiveButton("Зберегти") { _, _ ->
                if (WledCalibrationWizardPolicy.canSave(AudioReactiveService.exists(), draft, device)) RuntimeSettings.update { current -> current.copy(wledCalibrations = current.wledCalibrations.filterNot { it.identity == device.identity } + draft) }
                renderOutputUi()
            }.create()
        fun render() {
            remainingText?.text = "Залишилось LED: ${WledCalibrationEditor.remaining(draft)} (Save тільки при 0)"
            directionButton?.text = "Напрямок: ${draft.direction}"
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
        val physicalCount = TextView(this).apply { text = "MAC: ${device.identity}\nФізичні LED: перевіряються лише для читання…\nПочаток 0 = логічний нижній-лівий." }
        rows.addView(physicalCount)
        remainingText = TextView(this).apply { textSize = 17f }
        rows.addView(requireNotNull(remainingText))
        addStepper("Стартовий піксель", { draft.startPixel.toString() }) { d -> draft = draft.copy(startPixel = Math.floorMod(draft.startPixel + d, device.leds)) }
        directionButton = Button(this).apply { isAllCaps = false; setOnClickListener { if (!AudioReactiveService.exists()) { draft = draft.copy(direction = if (draft.direction == PerimeterDirection.CW) PerimeterDirection.CCW else PerimeterDirection.CW); render() } } }
        rows.addView(requireNotNull(directionButton))
        ScreenEdge.entries.forEach { edge -> addStepper("${edge.name.lowercase().replaceFirstChar { it.uppercase() }} LED", { draft.allocation(edge).toString() }) { d -> draft = WledCalibrationEditor.changeAllocation(draft, edge, d) } }
        ScreenEdge.entries.forEach { edge -> addStepper("Inset ${edge.name.lowercase()}", { "${draft.inset(edge)}%" }) { d -> draft = WledCalibrationEditor.changeInset(draft, edge, d) } }
        addStepper("Глибина вибірки", { "${draft.depthPercent}%" }) { d -> draft = draft.copy(depthPercent = (draft.depthPercent + d).coerceIn(2, 25)) }
        addStepper("Зразків на край", { draft.samplesPerEdge.toString() }) { d -> draft = draft.copy(samplesPerEdge = (draft.samplesPerEdge + d * 4).coerceIn(4, 64) / 4 * 4) }
        addStepper("Gamma", { String.format(java.util.Locale.US, "%.1f", draft.gamma) }) { d -> draft = draft.copy(gamma = (draft.gamma + d * .1f).coerceIn(1f, 3.5f)) }
        addStepper("Ліміт яскравості", { "${(draft.brightnessLimit * 100).toInt()}%" }) { d -> draft = draft.copy(brightnessLimit = (draft.brightnessLimit + d * .05f).coerceIn(.05f, 1f)) }
        addAction("Пропорційний пресет") { draft = WledScreenCalibration.proportional(device.identity, device.leds); render() }
        rows.addView(TextView(this).apply { text = "Діагностика: читає WLED заново, надсилає обмежені realtime-кадри лише до цієї MAC, потім blackout і закриває сокет." })
        WledDiagnosticPattern.entries.forEach { pattern -> addAction(pattern.label) {
            status.text = "Повторно перевіряю ${device.name} перед діагностикою…"
            work.execute {
                val sent = runCatching { WledDiagnosticAction.execute(RuntimeSettings.snapshot(), device, draft, pattern) }.getOrDefault(false)
                runOnUiThread { if (!isFinishing) { status.text = if (sent) "Діагностика ${pattern.label} завершена; blackout надіслано." else "WLED змінився або недоступний; діагностику не надіслано."; render() } }
            }
        } }
        dialog.setOnShowListener { render() }
        dialog.show()
        // Read-only one-shot count display; selection/calibration is never rewritten from this result.
        work.execute {
            val fresh = runCatching { WledDiscovery.revalidate(listOf(device)).singleOrNull() }.getOrNull()
            runOnUiThread {
                if (!isFinishing && dialog.isShowing) physicalCount.text = if (fresh == device)
                    "MAC: ${device.identity}\nФізичні LED: ${fresh.leds} (щойно перевірено, лише читання)\nПочаток 0 = логічний нижній-лівий."
                else "MAC: ${device.identity}\nФізичні LED: недоступні або змінилися; тест і захоплення будуть відхилені.\nПочаток 0 = логічний нижній-лівий."
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
        status.text = "Перевіряю вибраний ${settings.outputMode.label} перед підтвердженням…"
        work.execute {
            val progress: (Int) -> Unit = { attempt -> runOnUiThread { if (!cancelled()) status.text = "Перевіряю ${settings.outputMode.label}: спроба $attempt/${CapturePreflightRetry.MAX_ATTEMPTS}…" } }
            val wledResult = if (settings.outputMode == OutputMode.WLED) CapturePreflightRetry.bind(cancelled, progress) { WledCapturePreflight.bind(settings) } else null
            val hyperionResult = if (settings.outputMode == OutputMode.HYPERION) CapturePreflightRetry.bind(cancelled, progress) { HyperionCapturePreflight.bind(settings) } else null
            val wled = wledResult?.binding
            val hyperion = hyperionResult?.binding
            val attempts = wledResult?.attempts ?: hyperionResult?.attempts ?: 0
            runOnUiThread {
                if (cancelled()) { WledRouteBindings.discard(wled); HyperionRouteBindings.discard(hyperion); return@runOnUiThread }
                if (wled == null && hyperion == null) {
                    captureAdmissionLocked = false
                    status.text = "Вибраний вихід недоступний або змінився після $attempts спроб; захоплення не розпочато."
                    onDenied()
                } else {
                    pendingWled = wled
                    pendingHyperion = hyperion
                    onReady()
                }
            }
        }
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
