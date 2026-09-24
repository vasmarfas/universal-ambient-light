package com.vasmarfas.UniversalAmbientLight.common.remote

import android.util.Log
import org.json.JSONObject
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** ТВ отказал в команде; [message] уже на языке ТВ и показывается как есть. */
class RemoteCallException(val code: String, message: String) : IOException(message)

/**
 * Одно соединение телефона с телевизором: рукопожатие, запросы с ожиданием ответа и
 * события от ТВ. Переподключениями занимается [RemoteSession]; этот класс после обрыва
 * больше не используется.
 */
class RemoteClient(private val listener: Listener) {

    interface Listener {
        fun onEvent(event: JSONObject)

        fun onClosed(error: Exception?)
    }

    private val mPending = ConcurrentHashMap<Int, CompletableFuture<JSONObject>>()
    private val mNextId = AtomicInteger(1)
    private val mClosed = AtomicBoolean(false)
    private var mPinger: ScheduledExecutorService? = null

    @Volatile
    private var mChannel: RemoteChannel? = null

    /** Блокирует до ответа на hello; возвращает ответ ТВ (id, имя, модель). */
    fun connect(host: String, port: Int, secret: ByteArray, tvId: UUID?, clientName: String): JSONObject {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            val hello = JSONObject()
                .put("id", 0)
                .put("op", RemoteProtocol.OP_HELLO)
                .put("proto", RemoteProtocol.VERSION)
                .put("name", clientName)
            val channel = RemoteChannel.connect(socket, secret, tvId, hello)
            val reply = try {
                channel.receive()
            } catch (e: EOFException) {
                throw PairingRejectedException()
            }
            if (!reply.optBoolean("ok")) {
                throw RemoteCallException(reply.optString("err"), reply.optString("msg"))
            }
            channel.setReadTimeout(RemoteProtocol.IDLE_TIMEOUT_MS)
            mChannel = channel
            Thread({ readLoop(channel) }, "remote-client-read").apply { isDaemon = true }.start()
            startPinger()
            return reply
        } catch (e: Exception) {
            try {
                socket.close()
            } catch (_: IOException) {
                // Соединение и так не состоялось.
            }
            throw e
        }
    }

    /**
     * Команда с ожиданием ответа. Бросает [RemoteCallException], если ТВ отказал, и
     * [IOException] при обрыве или таймауте.
     */
    fun call(op: String, args: JSONObject = JSONObject(), timeoutMs: Long = DEFAULT_TIMEOUT_MS): JSONObject {
        val channel = mChannel ?: throw IOException("Not connected")
        val id = mNextId.getAndIncrement()
        val future = CompletableFuture<JSONObject>()
        mPending[id] = future
        try {
            channel.send(args.put("id", id).put("op", op))
            val reply = future.get(timeoutMs, TimeUnit.MILLISECONDS)
            if (!reply.optBoolean("ok")) {
                throw RemoteCallException(reply.optString("err"), reply.optString("msg"))
            }
            return reply
        } catch (e: TimeoutException) {
            throw IOException("TV did not answer '$op' in time")
        } catch (e: ExecutionException) {
            throw e.cause as? IOException ?: IOException(e.cause)
        } finally {
            mPending.remove(id)
        }
    }

    fun close() {
        closeWith(null)
    }

    private fun readLoop(channel: RemoteChannel) {
        try {
            while (!mClosed.get()) {
                val message = channel.receive()
                if (message.has("re")) {
                    mPending[message.optInt("re")]?.complete(message)
                } else if (message.has("ev")) {
                    listener.onEvent(message)
                }
            }
        } catch (e: Exception) {
            closeWith(e)
        }
    }

    private fun startPinger() {
        val pinger = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "remote-client-ping").apply { isDaemon = true }
        }
        mPinger = pinger
        pinger.scheduleWithFixedDelay({
            try {
                call(RemoteProtocol.OP_PING, timeoutMs = PING_TIMEOUT_MS)
            } catch (e: Exception) {
                // ТВ не отвечает на пинг — соединение мёртвое, даже если сокет этого не знает
                // (Wi-Fi телефона ушёл в сон, роутер потерял NAT-запись)
                Log.w(TAG, "Ping failed: ${e.message}")
                closeWith(e as? IOException ?: IOException(e))
            }
        }, RemoteProtocol.PING_INTERVAL_MS, RemoteProtocol.PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun closeWith(error: Exception?) {
        if (!mClosed.compareAndSet(false, true)) return
        mPinger?.shutdownNow()
        mChannel?.close()
        val failure = IOException("Connection closed", error)
        for (future in mPending.values) future.completeExceptionally(failure)
        mPending.clear()
        listener.onClosed(error)
    }

    companion object {
        private const val TAG = "RemoteClient"
        private const val CONNECT_TIMEOUT_MS = 3000
        private const val DEFAULT_TIMEOUT_MS = 15_000L
        private const val PING_TIMEOUT_MS = 8_000L

        /** Сопряжение ADB и запуск захвата ждут пользователя у ТВ — даём им время. */
        const val LONG_TIMEOUT_MS = 120_000L
    }
}
