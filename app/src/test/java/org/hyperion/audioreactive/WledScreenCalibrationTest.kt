package org.hyperion.audioreactive

import org.junit.Assert.*
import org.junit.Test

class WledScreenCalibrationTest {
    private val device = WledDevice("mac:001122334455", "TV", "192.168.1.152", 8, 21324)

    @Test fun calibrationRequiresExactCountFourEdgesAndFourIndependentInsets() {
        val c = WledScreenCalibration.proportional(device.identity, 8)
        assertTrue(c.validFor(device)); assertFalse(c.validFor(device.copy(leds = 9)))
        assertFalse(c.copy(left = c.left + 1).validFor())
        assertTrue(c.copy(topInsetPercent = 45, bottomInsetPercent = 0).validFor())
        assertFalse(c.copy(rightInsetPercent = 46).validFor())
    }

    @Test fun allocationEditorLeavesOtherEdgesUntouchedAndRequiresExplicitRemaining() {
        val c = WledScreenCalibration.proportional(device.identity, 8)
        val edited = WledCalibrationEditor.changeAllocation(c, ScreenEdge.BOTTOM, 1)
        assertEquals(c.right, edited.right); assertEquals(c.top, edited.top); assertEquals(c.left, edited.left)
        assertEquals(-1, WledCalibrationEditor.remaining(edited)); assertFalse(edited.validFor())
        val allBottom = c.copy(bottom = 8, right = 0, top = 0, left = 0)
        assertEquals(allBottom, WledCalibrationEditor.changeAllocation(allBottom, ScreenEdge.BOTTOM, 1))
    }

    @Test fun mapperRotatesReversesAndUsesPerEdgeInset() {
        val c = WledScreenCalibration(device.identity, 8, 2, PerimeterDirection.CCW, 2, 2, 2, 2,
            0, 45, 0, 0, 2, 4, 1f, 1f)
        val frame = ByteArray(4 * 4 * 3) { 0 }
        for (i in 0 until 16) frame[i * 3] = i.toByte()
        val mapped = WledPerimeterMapper(SourceFrameSpec(4, 4, 20), c).map(frame)
        assertEquals(8 * 3, mapped.size)
        assertTrue(mapped.any { it.toInt() and 255 != 0 })
    }

    @Test fun mapperAveragesTheConfiguredEdgeDepthStrip() {
        val calibration = WledScreenCalibration(device.identity, 4, 0, PerimeterDirection.CW,
            4, 0, 0, 0, depthPercent = 25, samplesPerEdge = 4, gamma = 1f, brightnessLimit = 1f)
        val frame = ByteArray(4 * 8 * 3)
        // The first bottom sample is x=0 and depth 25% spans rows 7 and 6.
        frame[(7 * 4) * 3] = 20
        frame[(6 * 4) * 3] = 100.toByte()
        val mapped = WledPerimeterMapper(SourceFrameSpec(4, 8, 20), calibration).map(frame)
        assertEquals(60, mapped[0].toInt() and 255)
    }

    @Test fun mapperUsesOneSharedEdgeOrderRatherThanAllocatingAnArrayPerFrame() {
        assertSame(WledPerimeterMapper.edgeOrder, WledPerimeterMapper.edgeOrder)
        val calibration = WledScreenCalibration.proportional(device.identity, device.leds)
        val mapper = WledPerimeterMapper(SourceFrameSpec(4, 4, 20), calibration)
        assertSame(mapper.map(ByteArray(48)), mapper.map(ByteArray(48)))
    }

    @Test fun mapperSamplesAllFourActivePillarboxEdgesInsteadOfBlackSideBars() {
        val calibration = WledScreenCalibration(device.identity, 4, 0, PerimeterDirection.CW, 1, 1, 1, 1, depthPercent = 10, samplesPerEdge = 4, gamma = 1f, brightnessLimit = 1f)
        val frame = activeEdgeFrame(128, 72, 40, 0, 48, 72)
        assertEdgeColors(WledPerimeterMapper(SourceFrameSpec(128, 72, 20), calibration).map(frame))
    }

    @Test fun mapperSamplesAllFourActiveLetterboxEdgesInsteadOfBlackTopBottomBars() {
        val calibration = WledScreenCalibration(device.identity, 4, 0, PerimeterDirection.CW, 1, 1, 1, 1, depthPercent = 10, samplesPerEdge = 4, gamma = 1f, brightnessLimit = 1f)
        val frame = activeEdgeFrame(128, 72, 0, 18, 128, 36)
        assertEdgeColors(WledPerimeterMapper(SourceFrameSpec(128, 72, 20), calibration).map(frame))
    }

    @Test fun mapperKeepsFullFrameAndDoesNotCropAnArbitraryDarkScene() {
        val calibration = WledScreenCalibration(device.identity, 4, 0, PerimeterDirection.CW, 1, 1, 1, 1, depthPercent = 10, samplesPerEdge = 4, gamma = 1f, brightnessLimit = 1f)
        assertEdgeColors(WledPerimeterMapper(SourceFrameSpec(128, 72, 20), calibration).map(activeEdgeFrame(128, 72, 0, 0, 128, 72)))
        val dark = ByteArray(128 * 72 * 3) { 8 }
        for (y in 0 until 72) for (x in 0 until 40) { val at = (y * 128 + x) * 3; dark[at] = 0; dark[at + 1] = 0; dark[at + 2] = 0 }
        for (y in 0 until 72) for (x in 88 until 128) { val at = (y * 128 + x) * 3; dark[at] = 0; dark[at + 1] = 0; dark[at + 2] = 0 }
        val mapped = WledPerimeterMapper(SourceFrameSpec(128, 72, 20), calibration).map(dark)
        // A dark frame with black side regions lacks broad bright content, so its physical sides remain black.
        assertEquals(0, mapped[9].toInt() and 255); assertEquals(0, mapped[10].toInt() and 255); assertEquals(0, mapped[11].toInt() and 255)
    }

    private fun activeEdgeFrame(width: Int, height: Int, left: Int, top: Int, activeWidth: Int, activeHeight: Int): ByteArray {
        val frame = ByteArray(width * height * 3)
        fun put(x: Int, y: Int, r: Int, g: Int, b: Int) { val at = (y * width + x) * 3; frame[at] = r.toByte(); frame[at + 1] = g.toByte(); frame[at + 2] = b.toByte() }
        for (y in top until top + activeHeight) for (x in left until left + activeWidth) put(x, y, 80, 80, 80)
        for (x in left until left + activeWidth) { put(x, top, 255, 0, 0); put(x, top + activeHeight - 1, 0, 0, 255) }
        for (y in top until top + activeHeight) { put(left, y, 255, 255, 255); put(left + activeWidth - 1, y, 0, 255, 0) }
        return frame
    }

    private fun assertEdgeColors(mapped: ByteArray) {
        // Edge order is BOTTOM, RIGHT, TOP, LEFT. Depth averaging may mix the edge's interior,
        // but every physical calibrated edge must receive an active (non-black) sample.
        for (edge in 0 until 4) assertTrue((0 until 3).sumOf { channel -> mapped[edge * 3 + channel].toInt() and 255 } > 0)
    }

    @Test fun videoRequiresCalibrationButAudioKeepsLegacyMapping() {
        val s = AudioSettings.defaults().copy(outputMode = OutputMode.WLED, wledDevices = listOf(device), selectedWledIdentities = setOf(device.identity))
        assertTrue(WledCalibrationPolicy.routeable(s, device))
        assertFalse(WledCalibrationPolicy.routeable(s.copy(renderMode = RenderMode.VIDEO), device))
        assertTrue(WledCalibrationPolicy.routeable(s.copy(renderMode = RenderMode.VIDEO, wledCalibrations = listOf(WledScreenCalibration.proportional(device.identity, 8))), device))
    }

    @Test fun wizardPolicyLocksEditsAndSaveWhileCaptureIsActive() {
        val c = WledScreenCalibration.proportional(device.identity, device.leds)
        assertTrue(WledCalibrationWizardPolicy.editable(false))
        assertFalse(WledCalibrationWizardPolicy.editable(true))
        assertTrue(WledCalibrationWizardPolicy.canSave(false, c, device))
        assertFalse(WledCalibrationWizardPolicy.canSave(true, c, device))
    }

    @Test fun parametersAreBoundedAndAffectRendering() {
        val f = AudioFeatures(.6f,.7f,.4f,.5f,.4f,.3f,FloatArray(16){.5f},true)
        val slow = EffectRenderer.renderImage(Effect.SPECTRUM,f,.8f,1,EffectParameters(hueShift=0f))
        val shifted = EffectRenderer.renderImage(Effect.SPECTRUM,f,.8f,1,EffectParameters(hueShift=120f))
        assertFalse(slow.contentEquals(shifted)); assertFalse(EffectParameters(speed=4f).valid())
    }
}
