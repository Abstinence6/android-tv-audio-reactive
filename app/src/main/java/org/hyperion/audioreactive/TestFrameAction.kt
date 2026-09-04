package org.hyperion.audioreactive

/**
 * Explicit diagnostic output only. This code has no Android permission, service, or projection
 * dependency: a caller must freshly preflight a selected direct route before one frame is sent.
 */
internal object TestFrameAction {
    internal interface HyperionDiagnosticOutput { fun register(); fun send(frame: ByteArray); fun clear(); fun close() }
    /** A small spatial RGB test pattern, deliberately independent of capture/source settings. */
    val frame: ByteArray = ByteArray(16 * 3).also { bytes ->
        val colors = arrayOf(
            intArrayOf(255, 0, 0), intArrayOf(255, 128, 0), intArrayOf(255, 255, 0), intArrayOf(0, 255, 0),
            intArrayOf(0, 255, 255), intArrayOf(0, 96, 255), intArrayOf(0, 0, 255), intArrayOf(128, 0, 255),
            intArrayOf(255, 0, 255), intArrayOf(255, 0, 96), intArrayOf(255, 255, 255), intArrayOf(96, 96, 96),
            intArrayOf(255, 64, 64), intArrayOf(64, 255, 64), intArrayOf(64, 64, 255), intArrayOf(255, 255, 255),
        )
        colors.forEachIndexed { index, color ->
            val offset = index * 3
            bytes[offset] = color[0].toByte()
            bytes[offset + 1] = color[1].toByte()
            bytes[offset + 2] = color[2].toByte()
        }
    }

    /** Returns false unless the current selected route gets a newly-created, one-shot binding. */
    fun execute(
        settings: AudioSettings,
        wledPreflight: (AudioSettings) -> String? = WledCapturePreflight::bind,
        hyperionPreflight: (AudioSettings) -> String? = HyperionCapturePreflight::bind,
        wledSender: (List<WledDevice>, ByteArray) -> Unit = ::sendWled,
        hyperionSender: (HyperionDevice, ByteArray) -> Unit = ::sendHyperion,
    ): Boolean {
        return when (settings.outputMode) {
            OutputMode.WLED -> {
                val binding = wledPreflight(settings) ?: return false
                val targets = WledRouteBindings.consume(binding, settings) ?: return false
                if (targets.isEmpty()) return false
                wledSender(targets, frame)
                true
            }
            OutputMode.HYPERION -> {
                val binding = hyperionPreflight(settings) ?: return false
                val target = HyperionRouteBindings.consume(binding, settings) ?: return false
                hyperionSender(target, frame)
                true
            }
        }
    }

    private fun sendWled(targets: List<WledDevice>, frame: ByteArray) {
        targets.forEach { device ->
            WledRealtimeOutput(device).also { output ->
                try {
                    output.send(frame)
                } finally {
                    output.close()
                }
            }
        }
    }

    private fun sendHyperion(target: HyperionDevice, frame: ByteArray) = sendHyperionForTest(target, frame) { device ->
        HyperionClient(device.host, device.dataPort, 16, 1, HyperionFlatbuffer.DIAGNOSTIC_ORIGIN).let { client ->
            object : HyperionDiagnosticOutput {
                override fun register() = client.register()
                override fun send(frame: ByteArray) = client.sendImage(frame)
                override fun clear() = client.clearBestEffort().let { Unit }
                override fun close() = client.close()
            }
        }
    }
    /** Diagnostic protocol sequence: distinct app origin at app priority, image, then same-priority clear. */
    internal fun sendHyperionForTest(target: HyperionDevice, frame: ByteArray, create: (HyperionDevice) -> HyperionDiagnosticOutput) {
        create(target).also { output ->
            var registered = false
            try { output.register(); registered = true; output.send(frame) } finally { try { if (registered) output.clear() } finally { output.close() } }
        }
    }
}

/** A diagnostic frame opens a route and shares the output priority/socket, so it cannot overlap capture. */
internal object TestFrameActionPolicy {
    const val ACTIVE_CAPTURE_REASON = "Тестове зображення виходу недоступне під час захоплення: воно створює окремий маршрут із тим самим пріоритетом/сокетом."
    fun mayExecute(captureActive: Boolean) = !captureActive
}
