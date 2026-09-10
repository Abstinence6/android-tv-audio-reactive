package org.hyperion.audioreactive

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioReactiveServiceAudioDrainSourceTest {
    private val source by lazy {
        sequenceOf(
            File("src/main/java/org/hyperion/audioreactive/AudioReactiveService.kt"),
            File("app/src/main/java/org/hyperion/audioreactive/AudioReactiveService.kt"),
        ).first(File::isFile).readText()
    }

    @Test fun videoLoopDrainsToTheLatestCompletePcmBlockBeforeAnalysis() {
        assertTrue(source.contains("AudioRecordDrainPolicy.drainLatestFullBlock(samples,latestSamples)"))
        assertTrue(source.contains("analyzer.analyze(latestSamples,latestSamples.size,s.sensitivity)"))
        assertTrue(source.contains("AudioRecord.READ_NON_BLOCKING"))
        assertFalse(source.contains("analyzer.analyze(samples,n,s.sensitivity)"))
    }
}
