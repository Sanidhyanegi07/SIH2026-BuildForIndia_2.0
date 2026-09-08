package com.example.georescux.domain.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the newline-delimited GATT frame codec. */
class GeoRescueBleFramerTest {

    @Test
    fun `small payloads are a single chunk`() {
        val framer = GeoRescueBleFramer()
        val payload = "hello".toByteArray()
        val chunks = framer.chunksFor(payload, 20)
        assertEquals(1, chunks.size)
        assertEquals("hello\n", String(chunks[0]))
    }

    @Test
    fun `large payloads split into ordered chunks within the limit`() {
        val framer = GeoRescueBleFramer()
        val payload = "x".repeat(500).toByteArray()
        val chunks = framer.chunksFor(payload, 20)
        assertEquals(26, chunks.size) // 501 bytes (500 + terminator) / 20
        chunks.forEach { chunk -> assertTrue(chunk.size <= 20) }
        // Reassembling yields the original payload + terminator.
        val reassembled = chunks.reduce { acc, chunk -> acc + chunk }
        assertEquals(payload.size + 1, reassembled.size)
    }

    @Test
    fun `feed returns the frame once the terminator arrives`() {
        val framer = GeoRescueBleFramer()
        assertNull(framer.feed("hel".toByteArray()))
        assertNull(framer.feed("lo".toByteArray()))
        assertEquals("hello", framer.feed("\n".toByteArray()))
        assertEquals(0, framer.pendingBytes)
    }

    @Test
    fun `feed reassembles chunked frames exactly like the sender split them`() {
        val sender = GeoRescueBleFramer()
        val receiver = GeoRescueBleFramer()
        val payload = "GRX-packet-json-payload-0123456789".toByteArray()
        val chunks = sender.chunksFor(payload, 7)
        var frame: String? = null
        chunks.forEach { chunk ->
            val result = receiver.feed(chunk)
            if (result != null) frame = result
        }
        assertEquals(String(payload), frame)
    }

    @Test
    fun `multiple frames are delivered in order across feeds`() {
        val receiver = GeoRescueBleFramer()
        // One feed emits at most the FIRST complete frame; later complete
        // frames stay buffered and are emitted by subsequent feeds in order.
        assertEquals("frame1", receiver.feed("frame1\nframe2\nfra".toByteArray()))
        assertEquals("frame2", receiver.feed("\n".toByteArray()))
        // The buffered remainder "fra\n" is itself complete, so the next
        // feed emits "fra" before touching the newly appended data.
        assertEquals("fra", receiver.feed("me3\n".toByteArray()))
        assertEquals("me3", receiver.feed("\n".toByteArray()))
    }

    @Test
    fun `bytes after a terminator start the next frame`() {
        val receiver = GeoRescueBleFramer()
        assertEquals("frame1", receiver.feed("frame1\nabc".toByteArray()))
        assertEquals(3, receiver.pendingBytes)
        assertEquals("abc", receiver.feed("\n".toByteArray()))
    }

    @Test
    fun `oversized garbage without a terminator is discarded (bounded memory)`() {
        val receiver = GeoRescueBleFramer(maxFrameBytes = 64)
        assertNull(receiver.feed("y".repeat(100).toByteArray()))
        assertEquals(0, receiver.pendingBytes)
        // The receiver still works for a fresh frame afterwards.
        assertEquals("ok", receiver.feed("ok\n".toByteArray()))
    }

    @Test
    fun `reset clears partial state`() {
        val receiver = GeoRescueBleFramer()
        receiver.feed("partial".toByteArray())
        receiver.reset()
        assertEquals(0, receiver.pendingBytes)
        assertEquals("new", receiver.feed("new\n".toByteArray()))
    }
}
