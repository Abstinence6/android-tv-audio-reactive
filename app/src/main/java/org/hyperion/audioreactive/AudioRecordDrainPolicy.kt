package org.hyperion.audioreactive

/** Retains only the newest complete capture block while consuming queued PCM. */
object AudioRecordDrainPolicy {
    fun drainLatestFullBlock(
        readBuffer: ShortArray,
        latestFullBlock: ShortArray,
        read: (ShortArray) -> Int,
    ): Boolean {
        require(readBuffer.size == latestFullBlock.size)
        var foundFullBlock = false
        while (true) {
            val count = read(readBuffer)
            if (count <= 0) return foundFullBlock
            if (count == readBuffer.size) {
                readBuffer.copyInto(latestFullBlock)
                foundFullBlock = true
            }
        }
    }
}
