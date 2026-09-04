package org.hyperion.audioreactive

import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Narrow writer for the retained official Hyperion FlatBuffers schema. RawImage data is packed
 * RGB24, one byte each for red, green, and blue, and sent in documented 4-byte BE frames.
 */
object HyperionFlatbuffer {
    const val PRIORITY = 101
    const val CAPTURE_ORIGIN = "android-tv-audio-reactive-video"
    const val DIAGNOSTIC_ORIGIN = "android-tv-audio-reactive-diagnostic"
    private const val REGISTER = 4
    private const val COLOR = 1
    private const val IMAGE = 2
    private const val CLEAR = 3
    private const val RAW_IMAGE = 1
    /** Audio remains 16×1; video is bounded by the native 320×180 capture producer. */
    const val MAX_IMAGE_WIDTH = 320
    const val MAX_IMAGE_HEIGHT = 180
    const val MAX_IMAGE_BYTES = MAX_IMAGE_WIDTH * MAX_IMAGE_HEIGHT * 3
    const val AUDIO_WIDTH = 16
    const val AUDIO_HEIGHT = 1

    fun register(origin: String, priority: Int = PRIORITY): ByteArray =
        request(REGISTER, origin.toByteArray(), priority)

    fun color(rgb: Rgb): ByteArray =
        request(COLOR, null, (rgb.r shl 16) or (rgb.g shl 8) or rgb.b)

    /** Image { data:RawImage, duration:-1 }, with RawImage { RGB24 data, width, height }. */
    fun rawImage(rgb24: ByteArray, width: Int, height: Int): ByteArray {
        require(width in 1..MAX_IMAGE_WIDTH && height in 1..MAX_IMAGE_HEIGHT) { "Image dimensions out of bounds" }
        require(rgb24.size == width * height * 3 && rgb24.size <= MAX_IMAGE_BYTES) { "Raw RGB byte count mismatch" }
        // Root/request, Image vtable/table, RawImage vtable/table, vector length/data/padding.
        val vectorEnd = 84 + rgb24.size
        val b = ByteBuffer.allocate((vectorEnd + 3) and -4).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0, 12)
        vtable(b, 4, 8, 12, 4, 8) // Request { command_type, command }
        b.putInt(12, 8)
        b.put(16, IMAGE.toByte())
        b.putInt(20, 16) // Request.command -> Image at 36
        threeFieldVtable(b, 24, 10, 16, 4, 8, 12)
        b.putInt(36, 12)
        b.put(40, RAW_IMAGE.toByte())
        b.putInt(44, 20) // Image.data -> RawImage at 64
        b.putInt(48, -1)
        threeFieldVtable(b, 52, 10, 16, 4, 8, 12)
        b.putInt(64, 12)
        b.putInt(68, 12) // RawImage.data -> vector at 80
        b.putInt(72, width)
        b.putInt(76, height)
        b.putInt(80, rgb24.size)
        b.position(84)
        b.put(rgb24)
        return b.array()
    }

    /** Reusable framed RGB24 request. Allocation occurs once when the source format is selected. */
    class RawImageFrame(private val width: Int = AUDIO_WIDTH, private val height: Int = AUDIO_HEIGHT) {
        private val bytes = width * height * 3
        private val frame: ByteArray
        private val dataOffset = FRAME_HEADER_BYTES + 84
        init {
            require(width in 1..MAX_IMAGE_WIDTH && height in 1..MAX_IMAGE_HEIGHT)
            val payload = rawImage(ByteArray(bytes), width, height)
            frame = ByteArray(FRAME_HEADER_BYTES + payload.size)
            frame[0] = (payload.size ushr 24).toByte(); frame[1] = (payload.size ushr 16).toByte(); frame[2] = (payload.size ushr 8).toByte(); frame[3] = payload.size.toByte()
            payload.copyInto(frame, FRAME_HEADER_BYTES)
        }
        fun write(rgb24: ByteArray): ByteArray { require(rgb24.size == bytes) { "Raw RGB byte count mismatch" }; rgb24.copyInto(frame, dataOffset); return frame }
    }

    /** Clear has exactly one schema field: Clear { priority:int; }. */
    fun clear(priority: Int = PRIORITY): ByteArray {
        val b = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0, 12)
        vtable(b, 4, 8, 12, 4, 8)
        b.putInt(12, 8)
        b.put(16, CLEAR.toByte())
        b.putInt(20, 12)
        oneFieldVtable(b, 24, 8, 4)
        b.putInt(32, 8)
        b.putInt(36, priority)
        return b.array()
    }

    /** Documented FlatBufferClient network envelope: a four-byte big-endian payload length. */
    fun frame(payload: ByteArray): ByteArray = ByteBuffer.allocate(payload.size + 4).order(ByteOrder.BIG_ENDIAN)
        .putInt(payload.size).put(payload).array()

    private fun request(type: Int, text: ByteArray?, value: Int): ByteArray {
        val size = if (text == null) 44 else 48 + ((text.size + 4) and -4)
        val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0, 12)
        vtable(b, 4, 8, 12, 4, 8)
        b.putInt(12, 8)
        b.put(16, type.toByte())
        b.putInt(20, 12)
        vtable(b, 24, 8, 12, 4, 8)
        b.putInt(32, 8)
        if (text != null) {
            b.putInt(36, 8)
            b.putInt(40, value)
            b.putInt(44, text.size)
            b.position(48)
            b.put(text)
            b.put(48 + text.size, 0)
        } else {
            b.putInt(36, value)
            b.putInt(40, -1)
        }
        return b.array()
    }

    private fun oneFieldVtable(b: ByteBuffer, at: Int, objectSize: Int, f0: Int) {
        b.putShort(at, 6); b.putShort(at + 2, objectSize.toShort()); b.putShort(at + 4, f0.toShort())
    }
    private fun vtable(b: ByteBuffer, at: Int, length: Int, objectSize: Int, f0: Int, f1: Int) {
        b.putShort(at, length.toShort()); b.putShort(at + 2, objectSize.toShort()); b.putShort(at + 4, f0.toShort()); b.putShort(at + 6, f1.toShort())
    }
    private fun threeFieldVtable(b: ByteBuffer, at: Int, length: Int, objectSize: Int, f0: Int, f1: Int, f2: Int) {
        b.putShort(at, length.toShort()); b.putShort(at + 2, objectSize.toShort()); b.putShort(at + 4, f0.toShort()); b.putShort(at + 6, f1.toShort()); b.putShort(at + 8, f2.toShort())
    }

    private const val FRAME_HEADER_BYTES = 4
    private const val RAW_IMAGE_PAYLOAD_BYTES = 84 + MAX_IMAGE_BYTES
    private const val FRAMED_RAW_IMAGE_BYTES = FRAME_HEADER_BYTES + ((RAW_IMAGE_PAYLOAD_BYTES + 3) and -4)
    private const val RAW_IMAGE_DATA_OFFSET = FRAME_HEADER_BYTES + 84
}

/** FlatBuffers evidence specifies client-to-server requests only; socket success is not server ack. */
class HyperionClient(private val host: String, private val port: Int, width: Int = HyperionFlatbuffer.AUDIO_WIDTH, height: Int = HyperionFlatbuffer.AUDIO_HEIGHT, private val origin: String = HyperionFlatbuffer.CAPTURE_ORIGIN) {
    private var socket: Socket? = null
    private var output: BufferedOutputStream? = null
    private val rawImageFrame = HyperionFlatbuffer.RawImageFrame(width, height)
    private val width = width; private val height = height
    @Synchronized fun register() = send(HyperionFlatbuffer.register(origin))
    @Synchronized fun sendImage(rgb24: ByteArray) = sendFramed(rawImageFrame.write(rgb24))

    @Synchronized fun clearBestEffort(): Boolean {
        close()
        repeat(2) {
            try { send(HyperionFlatbuffer.clear()); close(); return true } catch (_: Exception) { close() }
        }
        return false
    }
    @Synchronized fun close() { runCatching { socket?.close() }; socket = null; output = null }
    private fun send(message: ByteArray) {
        sendFramed(HyperionFlatbuffer.frame(message))
    }
    private fun sendFramed(message: ByteArray) {
        val out = output ?: Socket().also { it.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS); socket = it }
            .getOutputStream().let { BufferedOutputStream(it).also { output = it } }
        out.write(message)
        out.flush()
    }
    private companion object { const val CONNECT_TIMEOUT_MS = 1_500 }
}
