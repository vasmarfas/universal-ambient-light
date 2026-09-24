package com.vasmarfas.UniversalAmbientLight.common.remote

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.net.nsd.NsdManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Телефон в роли пульта: какой телевизор выбран, есть ли с ним связь, что с подсветкой.
 *
 * Настройки ТВ живут на телефоне в зеркале — отдельном файле SharedPreferences. Экраны
 * настроек работают с ним через обычный [Preferences] и не знают, что правят чужой
 * телевизор; каждая правка зеркала уходит на ТВ, а правки на самом ТВ приходят событием.
 *
 * Всё состояние меняется на главном потоке; сеть — в своих потоках.
 */
object RemoteSession {
    private const val TAG = "RemoteSession"
    private const val MIRROR_FILE = "remote_mirror"
    private const val FLUSH_DELAY_MS = 120L
    private const val DISCOVERY_TIMEOUT_MS = 3000L
    private const val BACKGROUND_GRACE_MS = 30_000L
    private val RETRY_DELAYS_MS = longArrayOf(1000, 2000, 4000, 8000, 15_000)

    enum class Connection { IDLE, CONNECTING, CONNECTED, OFFLINE }

    data class TvCaps(
        val sdk: Int,
        val appVersion: String,
        val isTv: Boolean,
        val hasAccessibility: Boolean,
        val accessibilityOn: Boolean,
        val methods: Set<String>,
        /** Запись экрана без диалога подтверждения (выдаётся через ADB). */
        val projectMedia: Boolean,
        /** Окна поверх других приложений — без них Android 10+ не покажет окно из фона. */
        val overlay: Boolean,
    )

    data class Snapshot(
        val tv: PairedTv? = null,
        val connection: Connection = Connection.IDLE,
        val running: Boolean = false,
        val alive: Boolean = false,
        val error: String? = null,
        val source: String? = null,
        val caps: TvCaps? = null,
        val problem: String? = null,
        /** Растёт с каждым полным снимком настроек — экранам пора перечитать зеркало. */
        val revision: Int = 0,
    )

    fun interface Listener {
        fun onRemoteChanged(snapshot: Snapshot)
    }

    @Volatile
    var snapshot = Snapshot()
        private set

    private val mListeners = CopyOnWriteArrayList<Listener>()
    private val mMain = Handler(Looper.getMainLooper())
    private val mConnector: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "remote-session").apply { isDaemon = true }
    }
    private val mSender: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "remote-send").apply { isDaemon = true }
    }

    // Контекст не храним: синглтон живёт дольше любой активити, а нужно ему немногое
    private var mMirror: SharedPreferences? = null
    private var mResources: Resources? = null
    private var mNsd: NsdManager? = null
    private var mDeviceName = Build.MODEL
    private var mStore: PairedTvStore? = null

    @Volatile
    private var mClient: RemoteClient? = null

    @Volatile
    private var mGeneration = 0

    @Volatile
    private var mForeground = false

    // Текущая попытка подключения: её ожидание между повторами прерывается кнопкой
    // «Повторить» и сменой телевизора, иначе новая попытка ждала бы до 15 секунд
    @Volatile
    private var mConnectTask: Future<*>? = null

    private var mApplyingRemote = false
    private val mPendingKeys = LinkedHashSet<String>()
    private val mFlush = Runnable { flushChanges() }
    private val mGoIdle = Runnable {
        mGeneration++
        disconnect()
    }

    private val mMirrorListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (mApplyingRemote || key == null || !PrefsCodec.isSynced(key)) return@OnSharedPreferenceChangeListener
        mPendingKeys += key
        // Ползунок пишет настройку на каждом шаге — на ТВ уходит пачка раз в FLUSH_DELAY_MS
        mMain.removeCallbacks(mFlush)
        mMain.postDelayed(mFlush, FLUSH_DELAY_MS)
    }

    /** Поднимает сохранённое состояние: телефон снова пульт того ТВ, что был выбран. */
    fun init(context: Context) {
        if (mMirror != null) return
        val app = context.applicationContext
        val mirror = mirror(app)
        mMirror = mirror
        mResources = app.resources
        mNsd = app.getSystemService(Context.NSD_SERVICE) as? NsdManager
        mDeviceName = deviceName(app)
        val store = PairedTvStore(app)
        mStore = store
        mirror.registerOnSharedPreferenceChangeListener(mMirrorListener)
        store.activeId?.let { id -> store.get(id) }?.let { tv -> update { Snapshot(tv = tv) } }
    }

    fun addListener(listener: Listener) {
        mListeners += listener
    }

    fun removeListener(listener: Listener) {
        mListeners -= listener
    }

    fun mirror(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(MIRROR_FILE, Context.MODE_PRIVATE)

    /** [Preferences] поверх зеркала: ключи и умолчания из ресурсов, хранилище — ТВ. */
    fun preferences(context: Context): Preferences = Preferences(context, mirror(context))

    val isActive: Boolean
        get() = snapshot.tv != null

    /** Телефон становится пультом [tv]; зеркало заполнится после подключения. */
    fun activate(tv: PairedTv) {
        val store = mStore ?: return
        store.save(tv)
        store.activeId = tv.id
        disconnect()
        mGeneration++
        update { Snapshot(tv = tv) }
        if (mForeground) scheduleConnect(0)
    }

    /** Назад к управлению самим телефоном. */
    fun deactivate() {
        mStore?.activeId = null
        mGeneration++
        disconnect()
        update { Snapshot() }
    }

    fun onForeground() {
        mForeground = true
        mMain.removeCallbacks(mGoIdle)
        val current = snapshot
        if (current.tv != null && current.connection != Connection.CONNECTED && mClient == null) {
            scheduleConnect(0)
        }
    }

    /** Короткий уход в фон (поворот, шторка) соединение не рвёт. */
    fun onBackground() {
        mForeground = false
        mMain.removeCallbacks(mGoIdle)
        mMain.postDelayed(mGoIdle, BACKGROUND_GRACE_MS)
    }

    fun reconnectNow() {
        if (snapshot.tv == null) return
        mGeneration++
        disconnect()
        scheduleConnect(0)
    }

    /**
     * Проверяет код и адреса новым соединением и возвращает ТВ с его id и именем. Блокирует;
     * бросает [PairingRejectedException], если код не подошёл, и [IOException], если ТВ не
     * найден ни по одному адресу.
     */
    fun verify(context: Context, hosts: List<String>, port: Int, code: String, tvId: String?): PairedTv {
        val secret = PairingCode.decode(code) ?: throw PairingRejectedException()
        val expected = tvId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        var lastError: Exception = IOException("No address")
        for (host in hosts) {
            val probe = RemoteClient(object : RemoteClient.Listener {
                override fun onEvent(event: JSONObject) {}
                override fun onClosed(error: Exception?) {}
            })
            try {
                val hello = probe.connect(host, port, secret, expected, deviceName(context))
                probe.close()
                return PairedTv(
                    id = hello.optString("id"),
                    name = hello.optString("name").ifBlank { host },
                    code = checkNotNull(PairingCode.normalize(code)) { "decoded above" },
                    hosts = listOf(host) + hosts.filter { it != host },
                    port = port
                )
            } catch (e: PairingRejectedException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError as? IOException ?: IOException(lastError)
    }

    /** Команда телевизору с ожиданием ответа. Блокирует, из главного потока не звать. */
    fun call(op: String, args: JSONObject = JSONObject(), timeoutMs: Long = 15_000L): JSONObject {
        val client = mClient ?: throw IOException(string(R.string.remote_error_offline))
        return client.call(op, args, timeoutMs)
    }

    fun adb(action: String, extra: JSONObject = JSONObject()): JSONObject =
        call(RemoteProtocol.OP_ADB, extra.put("action", action), RemoteClient.LONG_TIMEOUT_MS)

    private fun scheduleConnect(delayMs: Long) {
        val generation = mGeneration
        mConnectTask = mConnector.submit {
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    return@submit
                }
            }
            if (generation == mGeneration && mForeground && mClient == null) connectLoop(generation)
        }
    }

    private fun connectLoop(generation: Int) {
        var attempt = 0
        while (generation == mGeneration && mForeground) {
            val tv = snapshot.tv ?: return
            mMain.post { if (generation == mGeneration) update { copy(connection = Connection.CONNECTING) } }
            val error = tryConnect(tv, generation) ?: return
            val fatal = error is PairingRejectedException || error is ProtocolMismatchException
            mMain.post {
                if (generation == mGeneration) {
                    update { copy(connection = Connection.OFFLINE, problem = describe(error)) }
                }
            }
            if (fatal) return
            try {
                Thread.sleep(RETRY_DELAYS_MS[attempt.coerceAtMost(RETRY_DELAYS_MS.size - 1)])
            } catch (_: InterruptedException) {
                return
            }
            attempt++
        }
    }

    /** null — подключились; иначе причина неудачи. */
    private fun tryConnect(tv: PairedTv, generation: Int): Exception? {
        val secret = PairingCode.decode(tv.code) ?: return PairingRejectedException()
        val tvId = runCatching { UUID.fromString(tv.id) }.getOrNull()

        var lastError: Exception = IOException(string(R.string.remote_error_not_found))
        val candidates = tv.hosts.map { it to tv.port }.toMutableList()
        var discovered = false
        while (true) {
            for ((host, port) in candidates) {
                if (generation != mGeneration) return IOException("Cancelled")
                val client = newClient(generation)
                try {
                    client.connect(host, port, secret, tvId, mDeviceName)
                    if (generation != mGeneration) {
                        client.close()
                        return IOException("Cancelled")
                    }
                    mClient = client
                    if (host != tv.hosts.firstOrNull() || port != tv.port) {
                        // ТВ сменил адрес — запоминаем новый, в следующий раз он первый
                        val hosts = listOf(host) + tv.hosts.filter { it != host }
                        mStore?.save(tv.copy(hosts = hosts, port = port))
                    }
                    try {
                        onConnected(client, generation)
                    } catch (e: Exception) {
                        // Рукопожатие прошло, а снимок настроек не пришёл — соединение
                        // негодное, закрываем его, а не оставляем висеть рядом с новым
                        mClient = null
                        client.close()
                        throw e
                    }
                    return null
                } catch (e: PairingRejectedException) {
                    return e
                } catch (e: ProtocolMismatchException) {
                    return e
                } catch (e: Exception) {
                    Log.i(TAG, "Connect to $host:$port failed: ${e.message}")
                    lastError = e
                }
            }
            if (discovered) return lastError
            discovered = true
            val found = RemoteDiscovery.discover(mNsd, DISCOVERY_TIMEOUT_MS, tv.id)
                .firstOrNull { it.id == tv.id } ?: return lastError
            candidates.clear()
            candidates += found.host to found.port
        }
    }

    private fun newClient(generation: Int) = RemoteClient(object : RemoteClient.Listener {
        override fun onEvent(event: JSONObject) {
            mMain.post { if (generation == mGeneration) applyEvent(event) }
        }

        override fun onClosed(error: Exception?) {
            mMain.post {
                if (generation != mGeneration) return@post
                mClient = null
                update {
                    copy(connection = Connection.OFFLINE, problem = string(R.string.remote_error_offline))
                }
                if (mForeground) scheduleConnect(RETRY_DELAYS_MS[0])
            }
        }
    })

    private fun onConnected(client: RemoteClient, generation: Int) {
        // Правки, сделанные без связи, уходят раньше снимка, иначе снимок их затёр бы
        val pending = mainSync { collectPending() }
        if (pending.length() > 0) {
            client.call(RemoteProtocol.OP_SET_PREFS, JSONObject().put("set", pending))
        }
        val snapshotReply = client.call(RemoteProtocol.OP_SNAPSHOT)
        mMain.post {
            if (generation != mGeneration) return@post
            replaceMirror(snapshotReply.optJSONArray("prefs") ?: JSONArray())
            val status = snapshotReply.optJSONObject("status") ?: JSONObject()
            val caps = parseCaps(snapshotReply.optJSONObject("caps"))
            update {
                copy(
                    connection = Connection.CONNECTED,
                    problem = null,
                    caps = caps,
                    revision = revision + 1
                ).withStatus(status)
            }
        }
    }

    private fun applyEvent(event: JSONObject) {
        when (event.optString("ev")) {
            RemoteProtocol.EVENT_STATUS -> {
                val status = event.optJSONObject("status") ?: return
                update { withStatus(status) }
            }

            RemoteProtocol.EVENT_PREFS -> {
                val entries = event.optJSONArray("set") ?: return
                applyToMirror { editor ->
                    for (i in 0 until entries.length()) {
                        val entry = entries.optJSONObject(i) ?: continue
                        val key = entry.optString("k")
                        // Пока правка телефона не ушла, свежее значение — у телефона
                        if (key in mPendingKeys) continue
                        PrefsCodec.put(editor, key, PrefsCodec.decodeValue(entry))
                    }
                }
            }
        }
    }

    private fun Snapshot.withStatus(status: JSONObject) = copy(
        running = status.optBoolean("running"),
        alive = status.optBoolean("alive"),
        error = status.optString("error").takeIf { status.has("error") && !status.isNull("error") },
        source = status.optString("source").ifEmpty { null }
    )

    private fun replaceMirror(entries: JSONArray) {
        applyToMirror { editor ->
            editor.clear()
            for (i in 0 until entries.length()) {
                val entry = entries.optJSONObject(i) ?: continue
                PrefsCodec.put(editor, entry.optString("k"), PrefsCodec.decodeValue(entry))
            }
        }
    }

    /** Запись в зеркало без отправки обратно на ТВ; только на главном потоке. */
    private fun applyToMirror(block: (SharedPreferences.Editor) -> Unit) {
        val mirror = mMirror ?: return
        mApplyingRemote = true
        try {
            // commit, а не apply: слушатель должен отработать синхронно, пока флаг поднят
            mirror.edit(commit = true) { block(this) }
        } finally {
            mApplyingRemote = false
        }
    }

    private fun collectPending(): JSONArray {
        val values = mMirror?.all ?: return JSONArray()
        val entries = JSONArray()
        for (key in mPendingKeys) PrefsCodec.encode(key, values[key])?.let { entries.put(it) }
        mPendingKeys.clear()
        return entries
    }

    private fun flushChanges() {
        val client = mClient ?: return
        val entries = collectPending()
        if (entries.length() == 0) return
        mSender.execute {
            try {
                client.call(RemoteProtocol.OP_SET_PREFS, JSONObject().put("set", entries))
            } catch (e: Exception) {
                // Правка не дошла: связь рвётся, и при переподключении зеркало перезапишет
                // снимок ТВ — экран покажет то, что на ТВ на самом деле
                Log.w(TAG, "set_prefs failed: ${e.message}")
            }
        }
    }

    private fun disconnect() {
        mMain.removeCallbacks(mGoIdle)
        mConnectTask?.cancel(true)
        mConnectTask = null
        val client = mClient
        mClient = null
        client?.close()
        if (snapshot.connection == Connection.CONNECTED || snapshot.connection == Connection.CONNECTING) {
            update { copy(connection = Connection.IDLE) }
        }
    }

    private fun parseCaps(caps: JSONObject?): TvCaps? {
        caps ?: return null
        val methods = caps.optJSONArray("methods") ?: JSONArray()
        return TvCaps(
            sdk = caps.optInt("sdk"),
            appVersion = caps.optString("app"),
            isTv = caps.optBoolean("tv"),
            hasAccessibility = caps.optBoolean("accessibility"),
            accessibilityOn = caps.optBoolean("accessibilityOn"),
            methods = (0 until methods.length()).map { methods.optString(it) }.toSet(),
            projectMedia = caps.optBoolean("projectMedia"),
            overlay = caps.optBoolean("overlay")
        )
    }

    private fun describe(error: Exception): String = when (error) {
        is PairingRejectedException -> string(R.string.remote_error_code_rejected)
        is ProtocolMismatchException -> string(R.string.remote_error_version)
        is WrongTvException -> string(R.string.remote_error_not_found)
        else -> string(R.string.remote_error_not_found)
    }

    private fun update(transform: Snapshot.() -> Snapshot) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mMain.post { update(transform) }
            return
        }
        val next = snapshot.transform()
        if (next == snapshot) return
        snapshot = next
        for (listener in mListeners) listener.onRemoteChanged(next)
    }

    private fun <T> mainSync(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: T? = null
        val latch = CountDownLatch(1)
        mMain.post {
            result = block()
            latch.countDown()
        }
        latch.await()
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun string(resId: Int): String = mResources?.getString(resId).orEmpty()

    private fun deviceName(context: Context): String {
        val name = runCatching {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull()
        return name?.takeIf { it.isNotBlank() } ?: Build.MODEL
    }
}
