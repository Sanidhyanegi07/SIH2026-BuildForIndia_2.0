package com.example.georescux.domain.ble

/**
 * Newline-delimited frame codec for GATT transport.
 *
 * A "frame" is one UTF-8 JSON [GeoRescueBlePacket] serialized followed by a
 * 0x0A terminator. Because a GATT write/notification carries at most
 * (MTU - 3) bytes, a frame is split into ordered chunks by the sender and
 * reassembled here by the receiver. Chunks to the SAME peer over the SAME
 * characteristic are delivered in order by the Bluetooth stack, so no chunk
 * numbering is required; a malformed/partial frame is dropped whole rather
 * than half-processed.
 *
 * Pure Kotlin — exercised by unit tests with synthetic MTU sizes.
 */
class GeoRescueBleFramer(private val maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES) {

    private val buffer = java.io.ByteArrayOutputStream()

    /** Number of buffered bytes awaiting the frame terminator. */
    val pendingBytes: Int get() = buffer.size()

    /**
     * Splits [payload] (already UTF-8 encoded) plus the terminator into
     * ordered chunks no larger than [maxChunkBytes] (the per-GATT-write
     * payload limit, typically MTU - 3).
     */
    fun chunksFor(payloadBytes: ByteArray, maxChunkBytes: Int): List<ByteArray> {
        val frame = payloadBytes + FRAME_TERMINATOR
        if (frame.size <= maxChunkBytes) return listOf(frame)
        return frame.toList().chunked(maxChunkBytes).map { it.toByteArray() }
    }

    /**
     * Feeds one received chunk; returns the FIRST complete frame's JSON
     * when its terminator is in the buffer, otherwise null. Later complete
     * frames stay buffered and are emitted by subsequent feeds, in order.
     *
     * Guard: if garbage without a terminator exceeds [maxFrameBytes], the
     * buffer is discarded (bounded memory, attacker-safe).
     */
    fun feed(chunk: ByteArray): String? {
        if (chunk.isEmpty()) return null
        buffer.write(chunk)
        if (buffer.size() > maxFrameBytes) {
            reset()
            return null
        }
        val bytes = buffer.toByteArray()
        for (i in bytes.indices) {
            if (bytes[i] == FRAME_TERMINATOR) {
                val json = String(bytes, 0, i, Charsets.UTF_8)
                // Keep any bytes after the terminator (next frame's prefix).
                buffer.reset()
                if (i + 1 < bytes.size) buffer.write(bytes, i + 1, bytes.size - i - 1)
                return json.ifBlank { null }
            }
        }
        return null
    }

    /** Clears partial state (connection closed mid-frame). */
    fun reset() = buffer.reset()

    companion object {
        const val FRAME_TERMINATOR: Byte = 0x0A
        const val DEFAULT_MAX_FRAME_BYTES = GeoRescueBlePacket.MAX_PACKET_BYTES
    }
}
