package com.vasmarfas.UniversalAmbientLight.common.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.ScreenGrabberService
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Управление с телефона на стороне ТВ: держит TCP-сервер, объявляет его в локальной сети
 * через mDNS и рассылает телефонам состояние подсветки и правки настроек.
 *
 * Работает foreground-сервисом постоянно, пока доступ включён: телефон должен уметь
 * включить подсветку, когда ни приложение, ни сервис захвата не запущены, — например,
 * посреди фильма в другом приложении.
 */
class RemoteControlService : Service() {

    private var mServer: RemoteServer? = null
    private var mConfig: RemoteHostConfig? = null
    private var mNsdManager: NsdManager? = null
    private var mNsdListener: NsdManager.RegistrationListener? = null
    private val mHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var mRunning = false

    @Volatile
    private var mError: String? = null

    private val mStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            mRunning = intent.getBooleanExtra(ScreenGrabberService.BROADCAST_TAG, false)
            mError = intent.getStringExtra(ScreenGrabberService.BROADCAST_ERROR)
            mServer?.broadcast(JSONObject().put("ev", RemoteProtocol.EVENT_STATUS).put("status", status()))
        }
    }

    private val mPendingPrefKeys = LinkedHashSet<String>()
    private val mFlushPrefs = Runnable { flushPrefChanges() }
    private val mPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || !PrefsCodec.isSynced(key)) return@OnSharedPreferenceChangeListener
        mPendingPrefKeys += key
        // Пачкой: ползунок на ТВ пишет настройку на каждом шаге
        mHandler.removeCallbacks(mFlushPrefs)
        mHandler.postDelayed(mFlushPrefs, PREFS_FLUSH_DELAY_MS)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat(buildNotification(emptyList()))

        val config = RemoteHostConfig(this)
        mConfig = config
        val handler = RemoteHostHandler(this, config, ::status) { clients ->
            mHandler.post { onClientsChanged(clients) }
        }
        val server = RemoteServer(config.tvId, { config.secret }, handler)
        val port = try {
            server.start(config.port)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot start remote control server", e)
            stopSelf()
            return
        }
        mServer = server
        config.port = port
        sPort = port
        registerNsd(config, port)

        ContextCompat.registerReceiver(
            this,
            mStatusReceiver,
            IntentFilter(ScreenGrabberService.BROADCAST_FILTER),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        Preferences.defaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(mPrefsListener)
        sRunning = true
        notifyListeners()
        // Захват мог работать раньше нас — просим сервис повторить своё состояние
        if (ScreenGrabberService.sInstanceRunning) {
            try {
                startService(
                    Intent(this, ScreenGrabberService::class.java)
                        .setAction(ScreenGrabberService.GET_STATUS)
                )
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Cannot ask capture service for status: ${e.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Новый код сопряжения: уже подключённые телефоны знают старый — их отключаем
        if (intent?.action == ACTION_CODE_CHANGED) {
            mServer?.clients?.forEach { it.channel.close() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        sRunning = false
        sClients = emptyList()
        notifyListeners()
        mHandler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(mStatusReceiver)
        } catch (_: IllegalArgumentException) {
            // onCreate мог оборваться раньше регистрации (порт не открылся).
        }
        Preferences.defaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(mPrefsListener)
        unregisterNsd()
        mServer?.stop()
        mServer = null
        super.onDestroy()
    }

    private fun status(): JSONObject {
        val prefs = Preferences(this)
        return JSONObject()
            .put("running", mRunning && ScreenGrabberService.sInstanceRunning)
            .put("alive", ScreenGrabberService.sInstanceRunning)
            .put("error", mError)
            .put("source", prefs.getString(R.string.pref_key_capture_source, "screen"))
            .put("method", prefs.getString(R.string.pref_key_capture_method, "media_projection"))
    }

    private fun flushPrefChanges() {
        val server = mServer ?: return
        val prefs = Preferences.defaultSharedPreferences(this)
        val values = prefs.all
        val changed = mPendingPrefKeys.toList()
        mPendingPrefKeys.clear()

        server.broadcast(JSONObject()) { client ->
            val entries = JSONArray()
            for (key in changed) {
                val value = values[key]
                // Свою же правку телефон уже видит — эхо только сбивало бы ползунок назад
                val sent = client.sentValues[key]
                if (sent != null && (sent == value || (value == null && sent === RemoteHostHandler.REMOVED))) {
                    client.sentValues.remove(key, sent)
                    continue
                }
                PrefsCodec.encode(key, value)?.let { entries.put(it) }
            }
            if (entries.length() == 0) null
            else JSONObject().put("ev", RemoteProtocol.EVENT_PREFS).put("set", entries)
        }
    }

    private fun onClientsChanged(clients: List<RemoteServer.Client>) {
        sClients = clients.map { it.name }
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(sClients))
        notifyListeners()
    }

    private fun registerNsd(config: RemoteHostConfig, port: Int) {
        val nsd = getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val info = NsdServiceInfo().apply {
            serviceName = config.displayName
            serviceType = RemoteProtocol.SERVICE_TYPE
            setPort(port)
            setAttribute("id", config.tvId.toString())
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "Advertised as ${serviceInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // Без mDNS телефон всё равно подключится по адресу из QR-кода
                Log.w(TAG, "mDNS registration failed: $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            mNsdManager = nsd
            mNsdListener = listener
        } catch (e: Exception) {
            Log.w(TAG, "mDNS registration threw: ${e.message}")
        }
    }

    private fun unregisterNsd() {
        val listener = mNsdListener ?: return
        try {
            mNsdManager?.unregisterService(listener)
        } catch (_: Exception) {
            // Регистрация могла и не состояться — снимать нечего.
        }
        mNsdListener = null
    }

    private fun startForegroundCompat(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Прошивки с запретом автозапуска (TCL и похожие) не дают выйти в foreground;
            // сервер при этом работает, пока система не выгрузит процесс
            Log.w(TAG, "startForeground failed: ${e.message}")
        }
    }

    private fun buildNotification(clients: List<String>): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager != null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.remote_notification_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launch?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val text = if (clients.isEmpty()) {
            getString(R.string.remote_notification_waiting)
        } else {
            getString(R.string.remote_notification_connected, clients.joinToString())
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentTitle(getString(R.string.remote_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(contentIntent)
            .build()
    }

    fun interface Listener {
        fun onRemoteHostChanged()
    }

    companion object {
        private const val TAG = "RemoteControlService"
        private const val CHANNEL_ID = "com.vasmarfas.UniversalAmbientLight.remote"
        private const val NOTIFICATION_ID = 3
        private const val PREFS_FLUSH_DELAY_MS = 150L

        private const val ACTION_STOP = "com.vasmarfas.UniversalAmbientLight.remote.STOP"
        private const val ACTION_CODE_CHANGED = "com.vasmarfas.UniversalAmbientLight.remote.CODE_CHANGED"

        @Volatile
        var sRunning = false
            private set

        @Volatile
        var sPort = RemoteProtocol.DEFAULT_PORT
            private set

        /** Имена подключённых телефонов — для экрана сопряжения на ТВ. */
        @Volatile
        var sClients: List<String> = emptyList()
            private set

        private val sListeners = CopyOnWriteArrayList<Listener>()

        fun addListener(listener: Listener) {
            sListeners += listener
        }

        fun removeListener(listener: Listener) {
            sListeners -= listener
        }

        private fun notifyListeners() {
            for (listener in sListeners) listener.onRemoteHostChanged()
        }

        fun isEnabled(context: Context): Boolean =
            Preferences(context).getBoolean(R.string.pref_key_remote_access)

        /** Поднимает сервер, если доступ с телефона включён. Безопасно звать сколько угодно. */
        fun startIfEnabled(context: Context) {
            if (!isEnabled(context)) return
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, RemoteControlService::class.java)
                )
            } catch (e: Exception) {
                // Android 12+ не даёт поднять foreground-сервис из фона без исключения из
                // энергосбережения — сервер поднимется при следующем открытии приложения
                Log.w(TAG, "Cannot start remote control from here: ${e.message}")
            }
        }

        fun setEnabled(context: Context, enabled: Boolean) {
            Preferences(context).putBoolean(R.string.pref_key_remote_access, enabled)
            if (enabled) {
                startIfEnabled(context)
            } else if (sRunning) {
                context.startService(
                    Intent(context, RemoteControlService::class.java).setAction(ACTION_STOP)
                )
            }
        }

        fun onCodeChanged(context: Context) {
            if (!sRunning) return
            context.startService(
                Intent(context, RemoteControlService::class.java).setAction(ACTION_CODE_CHANGED)
            )
        }
    }
}
