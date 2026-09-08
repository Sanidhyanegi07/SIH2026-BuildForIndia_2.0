package com.example.georescux.domain.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the structured emergency packet: serialization,
 * deserialization, invalid-packet handling, packetId generation and
 * structural validation.
 */
class GeoRescueBlePacketTest {

    private fun validPacket(
        packetId: String = "GRX-test-1",
        ttl: Int = 5,
        type: GeoRescueBlePacketType = GeoRescueBlePacketType.SOS,
    ) = GeoRescueBlePacket(
        packetId = packetId,
        originDeviceId = "DEVICE_A",
        originEmergencyId = "SOS-123",
        timestampMs = 1_700_000_000_000,
        ttl = ttl,
        type = type,
        latitude = 30.7333,
        longitude = 76.7794,
        status = "ACTIVE",
        payload = mapOf("message" to "hello"),
    )

    @Test
    fun `packetId generation is unique`() {
        val a = GeoRescueBlePacket.newPacketId()
        val b = GeoRescueBlePacket.newPacketId()
        assertNotEquals(a, b)
        assertTrue(a.startsWith("GRX-"))
        assertTrue(b.startsWith("GRX-"))
    }

    @Test
    fun `serialization round-trip preserves every field`() {
        val original = validPacket()
        val restored = GeoRescueBlePacketCodec.deserialize(original.serialize())
        assertNotNull(restored)
        assertEquals(original, restored)
    }

    @Test
    fun `round-trip preserves optional-field absence`() {
        val original = GeoRescueBlePacket(
            packetId = "GRX-min",
            originDeviceId = "DEVICE_A",
            timestampMs = 123L,
            type = GeoRescueBlePacketType.TEST,
        )
        val restored = GeoRescueBlePacketCodec.deserialize(original.serialize())
        assertNotNull(restored)
        assertEquals(original, restored)
        assertNull(restored!!.originEmergencyId)
        assertNull(restored.latitude)
        assertNull(restored.longitude)
        assertNull(restored.status)
    }

    @Test
    fun `test packet wire name is GEORESCUEX_BLE_TEST`() {
        assertEquals("GEORESCUEX_BLE_TEST", GeoRescueBlePacketType.TEST.wireName)
        assertEquals("SOS", GeoRescueBlePacketType.SOS.wireName)
        val serialized = validPacket(type = GeoRescueBlePacketType.TEST).serialize()
        assertTrue(serialized.contains(GeoRescueBlePacketType.TEST_PACKET_MARKER))
    }

    @Test
    fun `deserialization returns null for garbage input`() {
        assertNull(GeoRescueBlePacketCodec.deserialize("not json at all"))
        assertNull(GeoRescueBlePacketCodec.deserialize(""))
        assertNull(GeoRescueBlePacketCodec.deserialize("[]"))
        assertNull(GeoRescueBlePacketCodec.deserialize("{\"foo\":1}"))
    }

    @Test
    fun `deserialization returns null when required fields are missing`() {
        assertNull(GeoRescueBlePacketCodec.deserialize("""{"packetId":"GRX-1"}""")) // no origin/timestamp/type
        assertNull(GeoRescueBlePacketCodec.deserialize("""{"originDeviceId":"A"}"""))
        assertNull(GeoRescueBlePacketCodec.deserialize("""{"timestamp":123}"""))
        assertNull(GeoRescueBlePacketCodec.deserialize("""{"type":"SOS"}"""))
    }

    @Test
    fun `deserialization returns null for unknown type`() {
        val json = """
            {"packetId":"GRX-x","originDeviceId":"A","timestamp":1,
             "type":"NOT_A_TYPE","ttl":3}
        """.trimIndent()
        assertNull(GeoRescueBlePacketCodec.deserialize(json))
    }

    @Test
    fun `validation accepts a well-formed packet`() {
        assertEquals(GeoRescueBlePacketVerdict.VALID, GeoRescueBlePacketValidator.validate(validPacket()))
    }

    @Test
    fun `validation rejects wrong protocol version`() {
        val packet = validPacket().copy(protocolVersion = 99)
        assertEquals(
            GeoRescueBlePacketVerdict.MALFORMED_PROTOCOL_VERSION,
            GeoRescueBlePacketValidator.validate(packet)
        )
    }

    @Test
    fun `validation rejects blank packetId and blank origin`() {
        assertEquals(
            GeoRescueBlePacketVerdict.MALFORMED_PACKET_ID,
            GeoRescueBlePacketValidator.validate(validPacket().copy(packetId = " "))
        )
        assertEquals(
            GeoRescueBlePacketVerdict.MALFORMED_ORIGIN_DEVICE,
            GeoRescueBlePacketValidator.validate(validPacket().copy(originDeviceId = ""))
        )
    }

    @Test
    fun `validation rejects non-positive timestamp`() {
        assertEquals(
            GeoRescueBlePacketVerdict.MALFORMED_TIMESTAMP,
            GeoRescueBlePacketValidator.validate(validPacket().copy(timestampMs = 0))
        )
        assertEquals(
            GeoRescueBlePacketVerdict.MALFORMED_TIMESTAMP,
            GeoRescueBlePacketValidator.validate(validPacket().copy(timestampMs = -5))
        )
    }

    @Test
    fun `validation rejects out-of-range ttl`() {
        assertEquals(
            GeoRescueBlePacketVerdict.MALFORMED_TTL,
            GeoRescueBlePacketValidator.validate(validPacket().copy(ttl = -1))
        )
        assertEquals(
            GeoRescueBlePacketVerdict.MALFORMED_TTL,
            GeoRescueBlePacketValidator.validate(validPacket().copy(ttl = GeoRescueBlePacket.MAX_TTL_HOPS + 1))
        )
    }

    @Test
    fun `validation rejects one-sided or out-of-range location`() {
        val base = validPacket()
        assertEquals(
            GeoRescueBlePacketVerdict.MALFORMED_LOCATION,
            GeoRescueBlePacketValidator.validate(base.copy(latitude = 10.0, longitude = null))
        )
        assertEquals(
            GeoRescueBlePacketVerdict.MALFORMED_LOCATION,
            GeoRescueBlePacketValidator.validate(base.copy(latitude = 999.0, longitude = 10.0))
        )
        assertEquals(
            GeoRescueBlePacketVerdict.MALFORMED_LOCATION,
            GeoRescueBlePacketValidator.validate(base.copy(latitude = 10.0, longitude = -999.0))
        )
    }

    @Test
    fun `validation rejects oversized payload values`() {
        val oversized = "x".repeat(GeoRescueBlePacket.MAX_PAYLOAD_VALUE_CHARS + 1)
        assertEquals(
            GeoRescueBlePacketVerdict.MALFORMED_PAYLOAD,
            GeoRescueBlePacketValidator.validate(validPacket().copy(payload = mapOf("k" to oversized)))
        )
    }

    @Test
    fun `ttl semantics - zero hops is not forwardable, next hop decrements`() {
        val packet = validPacket(ttl = 1)
        assertTrue(packet.hasHopsRemaining())
        val next = packet.forNextHop()
        assertEquals(0, next.ttl)
        assertFalse(next.hasHopsRemaining())
        // Forwarding twice below zero is prevented by the mesh node, not the packet.
        assertEquals(-1, next.forNextHop().ttl)
    }

    @Test
    fun `serialized packet stays within the wire size budget`() {
        val packet = validPacket()
        assertTrue(packet.serialize().toByteArray(Charsets.UTF_8).size <= GeoRescueBlePacket.MAX_PACKET_BYTES)
    }

    @Test
    fun `deserialization tolerates unknown extra fields`() {
        val json = """
            {"packetId":"GRX-x","originDeviceId":"A","timestamp":1,"type":"SOS",
             "ttl":4,"futureField":{"nested":true}}
        """.trimIndent()
        val packet = GeoRescueBlePacketCodec.deserialize(json)
        assertNotNull(packet)
        assertEquals("GRX-x", packet!!.packetId)
    }
}
