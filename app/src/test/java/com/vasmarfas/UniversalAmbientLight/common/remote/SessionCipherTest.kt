package com.vasmarfas.UniversalAmbientLight.common.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID
import javax.crypto.AEADBadTagException

class SessionCipherTest {

    private val secret = ByteArray(PairingCode.SECRET_BYTES) { it.toByte() }
    private val serverNonce = ByteArray(SessionCipher.NONCE_BYTES) { (it + 1).toByte() }
    private val clientNonce = ByteArray(SessionCipher.NONCE_BYTES) { (it + 100).toByte() }
    private val message = "{\"op\":\"hello\"}".toByteArray()

    @Test
    fun `the server opens what the client sealed`() {
        val client = SessionCipher.forClient(secret, serverNonce, clientNonce)
        val server = SessionCipher.forServer(secret, serverNonce, clientNonce)
        assertArrayEquals(message, server.open(client.seal(message)))
    }

    @Test
    fun `the client opens what the server sealed`() {
        val client = SessionCipher.forClient(secret, serverNonce, clientNonce)
        val server = SessionCipher.forServer(secret, serverNonce, clientNonce)
        assertArrayEquals(message, client.open(server.seal(message)))
    }

    @Test
    fun `consecutive frames are sealed differently`() {
        val client = SessionCipher.forClient(secret, serverNonce, clientNonce)
        assertFalse(client.seal(message).contentEquals(client.seal(message)))
    }

    @Test(expected = AEADBadTagException::class)
    fun `a wrong secret does not open the frame`() {
        val client = SessionCipher.forClient(ByteArray(PairingCode.SECRET_BYTES), serverNonce, clientNonce)
        SessionCipher.forServer(secret, serverNonce, clientNonce).open(client.seal(message))
    }

    @Test(expected = AEADBadTagException::class)
    fun `a replayed frame is rejected`() {
        val client = SessionCipher.forClient(secret, serverNonce, clientNonce)
        val server = SessionCipher.forServer(secret, serverNonce, clientNonce)
        val frame = client.seal(message)
        server.open(frame)
        server.open(frame)
    }

    @Test(expected = AEADBadTagException::class)
    fun `a reflected frame is rejected`() {
        // Ключи направлений разные: свой же кадр, отправленный обратно, не открывается
        val client = SessionCipher.forClient(secret, serverNonce, clientNonce)
        client.open(client.seal(message))
    }

    @Test(expected = AEADBadTagException::class)
    fun `a tampered frame is rejected`() {
        val client = SessionCipher.forClient(secret, serverNonce, clientNonce)
        val frame = client.seal(message)
        frame[0] = (frame[0].toInt() xor 1).toByte()
        SessionCipher.forServer(secret, serverNonce, clientNonce).open(frame)
    }

    @Test(expected = AEADBadTagException::class)
    fun `another connection with fresh nonces cannot open old frames`() {
        val oldFrame = SessionCipher.forClient(secret, serverNonce, clientNonce).seal(message)
        val freshNonce = ByteArray(SessionCipher.NONCE_BYTES) { (it + 7).toByte() }
        SessionCipher.forServer(secret, freshNonce, clientNonce).open(oldFrame)
    }

    @Test
    fun `a frame survives the length prefixed stream`() {
        val buffer = ByteArrayOutputStream()
        FrameIO.write(DataOutputStream(buffer), message)
        assertArrayEquals(message, FrameIO.read(DataInputStream(ByteArrayInputStream(buffer.toByteArray()))))
    }

    @Test(expected = IOException::class)
    fun `an oversized frame length is refused`() {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).writeInt(FrameIO.MAX_FRAME_BYTES + 1)
        FrameIO.read(DataInputStream(ByteArrayInputStream(buffer.toByteArray())))
    }

    @Test
    fun `the server hello carries the tv id`() {
        val tvId = UUID(0x1234L, 0x5678L)
        val hello = Handshake.parseServerHello(Handshake.serverHello(serverNonce, tvId))
        assertEquals(tvId, hello.tvId)
    }

    @Test
    fun `the server hello carries the nonce`() {
        val hello = Handshake.parseServerHello(Handshake.serverHello(serverNonce, UUID(1L, 2L)))
        assertArrayEquals(serverNonce, hello.nonce)
    }

    @Test(expected = IOException::class)
    fun `a foreign greeting is not a server hello`() {
        Handshake.parseServerHello("HTTP/1.1 200 OK\r\n\r\n0123456789012345".toByteArray())
    }

    @Test
    fun `the client hello splits back into nonce and message`() {
        val (nonce, _) = Handshake.splitClientHello(Handshake.clientHello(clientNonce, message))
        assertArrayEquals(clientNonce, nonce)
    }
}
