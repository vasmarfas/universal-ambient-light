package com.vasmarfas.UniversalAmbientLight.common.remote

import android.util.Log
import org.json.JSONObject
import java.io.EOFException
import java.io.IOException
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/** Ошибка выполнения команды, которую нужно показать на телефоне как есть. */
class RemoteCommandException(val code: String, message: String) : Exception(message)

/**
 * TCP-сервер управления на стороне ТВ. На каждого телефона — свой поток чтения; команды,
 * кроме пинга, уходят в общий пул: подключение ADB или запуск захвата длятся секундами, и
 * поток чтения за это время пропустил бы пинги и порвал соединение по таймауту.
 */
class RemoteServer(
    private val tvId: UUID,
    private val secretProvider: () -> ByteArray,
    private val handler: Handler,
) {

    interface Handler {
        /** Выполняет команду; бросает [RemoteCommandException], если выполнить нельзя. */
        fun handle(client: Client, op: String, request: JSONObject): JSONObject

        fun onClientsChanged(clients: List<Client>)
    }

    class Client internal constructor(val id: Int, val name: String, val channel: RemoteChannel) {
        /**
         * Последние значения, присланные этим телефоном. Эхо своих же правок ему не шлётся:
         * при быстрой прокрутке ползунка старое эхо догоняло бы новое значение и дёргало его.
         */
        val sentValues = ConcurrentHashMap<String, Any>()
    }

    private val mClients = CopyOnWriteArrayList<Client>()
    private val mNextClientId = AtomicInteger(1)
    private val mWorkers: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "remote-worker").apply { isDaemon = true }
    }

    // Один поток на все события: порядок статусов и правок у телефона не должен путаться
    private val mEvents: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "remote-events").apply { isDaemon = true }
    }

    @Volatile
    private var mServerSocket: ServerSocket? = null

    @Volatile
    private var mStopped = false

    val clients: List<Client>
        get() = mClients.toList()

    /** Слушает [preferredPort], а если он занят — любой свободный. Возвращает порт. */
    fun start(preferredPort: Int): Int {
        val socket = ServerSocket()
        socket.reuseAddress = true
        try {
            socket.bind(InetSocketAddress(preferredPort))
        } catch (e: BindException) {
            Log.w(TAG, "Port $preferredPort busy, taking any free one")
            socket.bind(InetSocketAddress(0))
        }
        mServerSocket = socket
        Thread({ acceptLoop(socket) }, "remote-accept").apply { isDaemon = true }.start()
        Log.i(TAG, "Listening on ${socket.localPort}")
        return socket.localPort
    }

    fun stop() {
        mStopped = true
        try {
            mServerSocket?.close()
        } catch (_: IOException) {
            // Уже закрыт — поток приёма и так завершится.
        }
        for (client in mClients) client.channel.close()
        mClients.clear()
        mWorkers.shutdownNow()
        mEvents.shutdownNow()
    }

    /** Событие всем телефонам (или тем, кого пропустит [filter]); не блокирует вызывающего. */
    fun broadcast(event: JSONObject, filter: (Client) -> JSONObject? = { event }) {
        try {
            mEvents.execute {
                for (client in mClients) {
                    val message = filter(client) ?: continue
                    try {
                        client.channel.send(message)
                    } catch (e: IOException) {
                        // Поток чтения этого клиента сам увидит обрыв и уберёт его из списка
                        Log.w(TAG, "Event to client ${client.id} failed: ${e.message}")
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            // Сервер уже останавливается.
        }
    }

    private fun acceptLoop(serverSocket: ServerSocket) {
        while (!mStopped) {
            val socket = try {
                serverSocket.accept()
            } catch (e: IOException) {
                if (!mStopped) Log.w(TAG, "Accept failed: ${e.message}")
                break
            }
            if (mClients.size >= RemoteProtocol.MAX_CLIENTS) {
                Log.w(TAG, "Too many clients, dropping ${socket.inetAddress}")
                closeQuietly(socket)
                continue
            }
            Thread({ serve(socket) }, "remote-client").apply { isDaemon = true }.start()
        }
    }

    private fun serve(socket: Socket) {
        val (channel, hello) = try {
            RemoteChannel.accept(socket, secretProvider(), tvId)
        } catch (e: Exception) {
            // Чужой код, сканер портов или телефон со сброшенным сопряжением — без подробностей
            // в ответ, просто закрываем
            Log.w(TAG, "Handshake with ${socket.inetAddress} failed: ${e.javaClass.simpleName}")
            closeQuietly(socket)
            return
        }

        val client = Client(
            mNextClientId.getAndIncrement(),
            hello.optString("name").ifBlank { socket.inetAddress?.hostAddress ?: "?" },
            channel
        )
        mClients += client
        notifyClientsChanged()
        Log.i(TAG, "Client ${client.id} (${client.name}) connected")

        try {
            respond(client, hello, RemoteProtocol.OP_HELLO)
            channel.setReadTimeout(RemoteProtocol.IDLE_TIMEOUT_MS)
            while (!mStopped) {
                val request = channel.receive()
                val op = request.optString("op")
                if (op == RemoteProtocol.OP_PING) {
                    respond(client, request, op)
                } else {
                    try {
                        mWorkers.execute { respond(client, request, op) }
                    } catch (_: RejectedExecutionException) {
                        break
                    }
                }
            }
        } catch (e: SocketTimeoutException) {
            Log.i(TAG, "Client ${client.id} went silent, closing")
        } catch (e: EOFException) {
            Log.i(TAG, "Client ${client.id} disconnected")
        } catch (e: SocketException) {
            Log.i(TAG, "Client ${client.id} socket closed: ${e.message}")
        } catch (e: Exception) {
            // Сюда попадают и битые кадры: после ошибки расшифровки канал не восстановить
            Log.w(TAG, "Client ${client.id} dropped", e)
        } finally {
            channel.close()
            mClients.remove(client)
            notifyClientsChanged()
        }
    }

    private fun respond(client: Client, request: JSONObject, op: String) {
        val response = try {
            handler.handle(client, op, request).put("ok", true)
        } catch (e: RemoteCommandException) {
            JSONObject().put("ok", false).put("err", e.code).put("msg", e.message)
        } catch (e: Exception) {
            Log.w(TAG, "Command $op failed", e)
            JSONObject()
                .put("ok", false)
                .put("err", RemoteProtocol.ERR_FAILED)
                .put("msg", e.message ?: e.javaClass.simpleName)
        }
        response.put("re", request.optInt("id"))
        try {
            client.channel.send(response)
        } catch (e: IOException) {
            Log.w(TAG, "Reply to client ${client.id} failed: ${e.message}")
        }
    }

    private fun notifyClientsChanged() {
        handler.onClientsChanged(mClients.toList())
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: IOException) {
            // Закрываем чужое соединение; если оно уже закрыто — тем лучше.
        }
    }

    companion object {
        private const val TAG = "RemoteServer"
    }
}
