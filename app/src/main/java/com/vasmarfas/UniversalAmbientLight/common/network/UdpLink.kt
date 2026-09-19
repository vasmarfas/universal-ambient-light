package com.vasmarfas.UniversalAmbientLight.common.network

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * UDP к одному приёмнику. Имя хоста резолвится один раз, при открытии: на каждом кадре DNS
 * в локальной сети стоил бы миллисекунды, а при потере сети ещё и секунды ожидания.
 */
internal class UdpLink(private val host: String, private val port: Int) {

    private var mSocket: DatagramSocket? = null
    private var mAddress: InetAddress? = null

    @Throws(IOException::class)
    fun open() {
        mAddress = InetAddress.getByName(host)
        mSocket = DatagramSocket().apply { broadcast = true }
    }

    @Throws(IOException::class)
    fun send(data: ByteArray, length: Int = data.size) {
        val socket = mSocket ?: throw IOException("Socket is closed")
        socket.send(DatagramPacket(data, length, mAddress, port))
    }

    fun close() {
        mSocket?.close()
        mSocket = null
    }
}
