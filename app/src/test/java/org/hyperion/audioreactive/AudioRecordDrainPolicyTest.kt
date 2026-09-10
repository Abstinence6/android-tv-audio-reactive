package org.hyperion.audioreactive

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRecordDrainPolicyTest {
    @Test fun staleFullBlocksAreDroppedAndOnlyTheCurrentFullBlockIsRetained() {
        val readBuffer = ShortArray(4)
        val latest = ShortArray(4)
        val reads = ArrayDeque(listOf(4 to 11, 4 to 22, 0 to 0))

        val found = AudioRecordDrainPolicy.drainLatestFullBlock(readBuffer, latest) { buffer ->
            val (count, value) = reads.removeFirst()
            buffer.fill(value.toShort())
            count
        }

        assertTrue(found)
        assertArrayEquals(ShortArray(4) { 22 }, latest)
        assertEquals(0, reads.size)
    }

    @Test fun partialTailIsConsumedButNeverReplacesTheLatestCompleteBlock() {
        val readBuffer = ShortArray(4)
        val latest = ShortArray(4)
        val reads = ArrayDeque(listOf(4 to 7, 2 to 99, 0 to 0))

        val found = AudioRecordDrainPolicy.drainLatestFullBlock(readBuffer, latest) { buffer ->
            val (count, value) = reads.removeFirst()
            buffer.fill(value.toShort())
            count
        }

        assertTrue(found)
        assertArrayEquals(ShortArray(4) { 7 }, latest)
        assertEquals(0, reads.size)
    }

    @Test fun noDataDoesNotProduceAnAnalysisBlock() {
        val readBuffer = ShortArray(4)
        val latest = ShortArray(4) { 3 }

        val found = AudioRecordDrainPolicy.drainLatestFullBlock(readBuffer, latest) { 0 }

        assertFalse(found)
        assertArrayEquals(ShortArray(4) { 3 }, latest)
    }
}
