package org.hyperion.audioreactive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import java.util.UUID

/** App-launch-started, non-sticky foreground owner for one anonymous pinned MQTT client. */
class MqttControlService : Service(), MqttCallbackExtended {
    companion object {
        const val ACTION_START = "org.hyperion.audioreactive.MQTT_START"
        const val ACTION_STOP = "org.hyperion.audioreactive.MQTT_STOP"
        private const val CHANNEL = "mqtt_control"
        private const val ID = 8
        @Volatile private var alive = false
        @Volatile private var instance: MqttControlService? = null
        fun exists() = alive
        /** Safe from capture/background threads; coalesces state churn on the service main loop. */
        fun notifyDiagnosticChanged() = instance?.requestSnapshot()
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, MqttControlService::class.java).setAction(ACTION_START))
        fun stop(context: Context) = context.startService(Intent(context, MqttControlService::class.java).setAction(ACTION_STOP))
    }

    private var client: MqttAsyncClient? = null
    private var connectedBroker: MqttBrokerSettings? = null
    @Volatile private var connected = false
    private val snapshotHandler = Handler(Looper.getMainLooper())
    private var snapshotScheduled = false
    private val settingsListener: (AudioSettings) -> Unit = { settings ->
        if (settings.mqttBroker != connectedBroker) reconnectFor(settings.mqttBroker) else requestSnapshot()
    }


    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopControl(); return START_NOT_STICKY }
        if (client == null) {
            createChannel()
            startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            alive = true
            instance = this
            RuntimeSettings.addListener(settingsListener)
            connect()
        }
        return START_NOT_STICKY
    }

    private fun connect() {
        val broker = RuntimeSettings.snapshot().mqttBroker
        if (!broker.valid()) return
        connectedBroker = broker
        val instance = MqttAsyncClient(MqttClientPolicy.uri(broker), "${MqttContract.DEVICE_ID}-${UUID.randomUUID().toString().take(8)}", null)
        client = instance
        instance.setCallback(this)
        val policyOptions = MqttClientPolicy.options(broker)
        val options = MqttConnectOptions().apply {
            isAutomaticReconnect = true
            isCleanSession = true
            connectionTimeout = 10
            keepAliveInterval = 30
            setWill(MqttContract.AVAILABILITY, "offline".toByteArray(), 1, true)
            policyOptions.username?.let(::setUserName)
            policyOptions.password?.let(::setPassword)
        }
        runCatching {
            instance.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) = onConnected(instance)
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) = onConnectionFailure(instance)
            })
        }.onFailure { onConnectionFailure(instance) }
    }

    private fun onConnected(instance: MqttAsyncClient) {
        if (client !== instance || connected) return
        connected = true

        runCatching { instance.subscribe(arrayOf(MqttContract.CAPTURE_COMMAND, MqttContract.EFFECT_COMMAND, "${MqttContract.ROOT}/settings/#", "${MqttContract.ROOT}/routes/#", "${MqttContract.ROOT}/calibration/#"), intArrayOf(1, 1, 1, 1, 1)) }
        // Always publish a complete retained snapshot after initial connect and every reconnect.
        publishSnapshot()
    }

    private fun onConnectionFailure(instance: MqttAsyncClient) {
        if (client !== instance) return
        connected = false
        client = null
        alive = false
        runCatching { instance.close() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun connectComplete(reconnect: Boolean, serverURI: String?) { client?.let(::onConnected) }
    override fun connectionLost(cause: Throwable?) { connected = false }
    override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
    override fun messageArrived(topic: String?, message: MqttMessage?) {
        val command = MqttContract.parseCommand(topic.orEmpty(), message?.payload?.decodeToString().orEmpty(), message?.isRetained == true)
        val action = MqttCommandPolicy.decide(command, AudioReactiveService.exists())
        var detail: String? = null
        when (action) {
            MqttCommandPolicy.Action.ReportConsentRequired -> publishSnapshot("needs_media_projection_consent")
            MqttCommandPolicy.Action.StopOwnedCapture -> AudioReactiveService.stopExisting(this)
            is MqttCommandPolicy.Action.ChangeEffect -> {
                val persisted = RuntimeSettings.snapshot()
                if (AudioReactiveService.exists()) LiveRendererSettings.setActiveEffect(action.effect)
                else EffectSelectorPolicy.withActiveName(persisted, action.effect)?.let(RuntimeSettings::apply)
            }
            is MqttCommandPolicy.Action.ChangeSetting -> {
                if (AudioReactiveService.exists()) {
                    if (!LiveMqttSettingsPolicy.apply(RuntimeSettings.snapshot(), action.update)) detail = if (action.update.field == "render_mode") LiveMqttSettingsPolicy.BLOCKED_DETAIL else "setting blocked while capture is active"
                } else MqttSettingsPolicy.apply(RuntimeSettings.snapshot(), action.update)?.let(RuntimeSettings::apply)
            }
            MqttCommandPolicy.Action.Ignore -> Unit
        }
        if (command != null && action !is MqttCommandPolicy.Action.ReportConsentRequired) publishSnapshot(detail ?: if (AudioReactiveService.exists()) "capture_active" else "needs_media_projection_consent")
    }

    private fun publishSnapshot(detail: String = if (AudioReactiveService.exists()) "capture_active" else "needs_media_projection_consent") {
        val c = client ?: return
        val local = LocalStatusStore.snapshot()
        val runtime = MqttContract.DiagnosticRuntime(
            captureActive = AudioReactiveService.exists(),
            captureStatus = AudioReactiveService.captureStatus().name,
            detail = detail,
            appVersion = BuildConfig.VERSION_NAME,
            deviceName = listOf(Build.MANUFACTURER, Build.MODEL).filter(String::isNotBlank).joinToString(" ").ifBlank { "unknown" },
            frameTimeMs = local.frameTimeMs,
            worstFrameTimeMs = local.worstFrameTimeMs,
            missedFrameDeadlines = local.missedFrameDeadlines,
        )
        val effective = EffectiveRenderSettings.snapshot(RuntimeSettings.snapshot(), runtime.captureActive)
        MqttContract.snapshot(effective, runtime).forEach { p ->
            runCatching { c.publish(p.topic, p.payload.toByteArray(), 1, p.retained) }
        }
    }
    private fun requestSnapshot() {
        if (!connected || snapshotScheduled) return
        snapshotScheduled = true
        snapshotHandler.postDelayed({
            snapshotScheduled = false
            if (connected) publishSnapshot()
        }, 100L)
    }
    /** Persisted broker settings changed: discard the old client before creating the new one. */
    private fun reconnectFor(broker: MqttBrokerSettings) {
        if (client == null || !broker.valid()) return
        val old = client
        connected = false
        client = null
        runCatching { old?.disconnect(2_000) }
        runCatching { old?.close() }
        connect()
    }
    private fun stopControl() {
        val c = client
        if (connected && c != null) runCatching { c.publish(MqttContract.offline().topic, "offline".toByteArray(), 1, true).waitForCompletion(2_000) }
        connected = false
        runCatching { c?.disconnect(2_000) }
        runCatching { c?.close() }
        client = null
        alive = false
        if (instance === this) instance = null
        RuntimeSettings.removeListener(settingsListener)
        snapshotHandler.removeCallbacksAndMessages(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() { stopControl(); super.onDestroy() }
    private fun createChannel() = (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel(CHANNEL, "Home Assistant control", NotificationManager.IMPORTANCE_LOW))
    private fun notification() = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("Home Assistant MQTT").setContentText("Control available; capture requires local consent.").setOngoing(true).build()
}
