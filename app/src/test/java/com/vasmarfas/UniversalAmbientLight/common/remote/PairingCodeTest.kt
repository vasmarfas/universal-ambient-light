package com.vasmarfas.UniversalAmbientLight.common.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.SecureRandom

class PairingCodeTest {

    private val secret = byteArrayOf(
        0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte(), 0xFF.toByte()
    )

    @Test
    fun `a secret survives the round trip through the code`() {
        assertArrayEquals(secret, PairingCode.decode(PairingCode.encode(secret)))
    }

    @Test
    fun `the code has sixteen characters`() {
        assertEquals(16, PairingCode.encode(secret).length)
    }

    @Test
    fun `a generated code is a valid code`() {
        // Фиксированное зерно: тест не должен зависеть от случайности
        val random = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(42L) }
        val code = PairingCode.generate(random)
        assertEquals(code, PairingCode.normalize(code))
    }

    @Test
    fun `dashes spaces and lower case are ignored`() {
        val code = PairingCode.encode(secret)
        val typed = PairingCode.format(code).lowercase().replace("-", " - ")
        assertEquals(code, PairingCode.normalize(typed))
    }

    @Test
    fun `letters confused with digits are read as digits`() {
        // В алфавите нет O, I и L: их вводят вместо 0 и 1
        assertEquals("0111222233334444", PairingCode.normalize("OIL1-2222-3333-4444"))
    }

    @Test
    fun `a code of the wrong length is rejected`() {
        assertNull(PairingCode.normalize("ABCD-EFGH-JKMN"))
    }

    @Test
    fun `a code with a character outside the alphabet is rejected`() {
        assertNull(PairingCode.decode("ABCD-EFGH-JKMN-PQRU"))
    }

    @Test
    fun `the formatted code is split into groups of four`() {
        assertEquals("ABCD-EFGH-JKMN-PQRS", PairingCode.format("ABCDEFGHJKMNPQRS"))
    }
}
