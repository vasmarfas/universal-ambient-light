package com.vasmarfas.UniversalAmbientLight.common.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingPayloadTest {

    private val payload = PairingPayload(
        tvId = "3f1c2a9e-0000-4000-8000-000000000001",
        name = "Гостиная TV & co",
        hosts = listOf("192.168.1.50", "10.0.0.7"),
        port = 38911,
        code = "ABCDEFGHJKMNPQRS"
    )

    @Test
    fun `a payload survives the round trip through the uri`() {
        assertEquals(payload, PairingPayload.parse(payload.toUri()))
    }

    @Test
    fun `the uri uses the app scheme`() {
        assertTrue(payload.toUri().startsWith("uamblight://pair?"))
    }

    @Test
    fun `a foreign link is not a payload`() {
        assertNull(PairingPayload.parse("https://example.com/pair?v=1&id=x&h=1.2.3.4&p=1&k=ABCDEFGHJKMNPQRS"))
    }

    @Test
    fun `a payload without an address is rejected`() {
        assertNull(PairingPayload.parse("uamblight://pair?v=1&id=tv&n=x&h=&p=38911&k=ABCDEFGHJKMNPQRS"))
    }

    @Test
    fun `a port out of range is rejected`() {
        assertNull(PairingPayload.parse("uamblight://pair?v=1&id=tv&h=1.2.3.4&p=70000&k=ABCDEFGHJKMNPQRS"))
    }

    @Test
    fun `a broken code is rejected`() {
        assertNull(PairingPayload.parse("uamblight://pair?v=1&id=tv&h=1.2.3.4&p=38911&k=SHORT"))
    }

    @Test
    fun `the code from the uri is normalized`() {
        val parsed = PairingPayload.parse("uamblight://pair?v=1&id=tv&h=1.2.3.4&p=38911&k=abcd-efgh-jkmn-pqrs")
        assertEquals("ABCDEFGHJKMNPQRS", parsed?.code)
    }

    @Test
    fun `a missing name is read as empty`() {
        val parsed = PairingPayload.parse("uamblight://pair?v=1&id=tv&h=1.2.3.4&p=38911&k=ABCDEFGHJKMNPQRS")
        assertEquals("", parsed?.name)
    }
}
