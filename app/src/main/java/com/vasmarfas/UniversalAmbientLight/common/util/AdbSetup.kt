package com.vasmarfas.UniversalAmbientLight.common.util

import android.content.Context
import android.os.Build
import android.util.Log
import com.vasmarfas.UniversalAmbientLight.R
import dadb.Dadb
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.android.AndroidUtils
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Подключение приложения к собственному ADB устройства: проверка, сопряжение, выдача
 * разрешений. Одни и те же операции нужны диалогу на самом ТВ и телефону, который
 * управляет ТВ удалённо, поэтому они вынесены сюда.
 *
 * Все вызовы, кроме [status], блокирующие — из главного потока не звать.
 */
object AdbSetup {
    private const val TAG = "AdbSetup"
    private const val CONNECT_TIMEOUT_MS = 8000L

    class Status(val developerOptions: Boolean, val adbEnabled: Boolean, val sdkInt: Int)

    class Outcome(val success: Boolean, val message: String)

    /** Долгоживущая команда оболочки: её stdin, объединённые stdout и stderr. */
    interface Shell : Closeable {
        val input: InputStream
        val output: OutputStream
    }

    fun status(context: Context) = Status(
        developerOptions = DevOptionsHelper.isDeveloperOptionsEnabled(context),
        adbEnabled = DevOptionsHelper.isAdbEnabled(context),
        sdkInt = Build.VERSION.SDK_INT
    )

    /** Старый путь с RSA-ключом: Android 10 и ниже либо после `adb tcpip 5555`. */
    fun legacyConnect(context: Context, port: Int): Outcome = try {
        Dadb.create("127.0.0.1", port, AdbKeyHelper.getKeyPair(context)).use { it.shell("echo ok") }
        Outcome(true, context.getString(R.string.adb_test_success))
    } catch (e: Throwable) {
        Outcome(false, context.getString(R.string.adb_test_failed, e.message ?: "?"))
    }

    fun testConnection(context: Context, port: Int): Outcome {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return legacyConnect(context, port)
        return try {
            // Android 11+: TLS; порт беспроводной отладки находится сам, иначе берём введённый
            val mgr = AppAdbConnectionManager.getInstance(context)
            if (!mgr.isConnected) {
                val auto = try {
                    mgr.autoConnect(context, CONNECT_TIMEOUT_MS)
                } catch (e: AdbPairingRequiredException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
                if (!auto && port > 0) mgr.connect("127.0.0.1", port)
            }
            if (mgr.isConnected) {
                Outcome(true, context.getString(R.string.adb_test_success))
            } else {
                Outcome(false, context.getString(R.string.adb_test_failed, "not connected"))
            }
        } catch (e: AdbPairingRequiredException) {
            Outcome(false, context.getString(R.string.error_adb_pairing_required))
        } catch (e: Throwable) {
            Outcome(false, context.getString(R.string.adb_test_failed, e.message ?: "unknown"))
        }
    }

    /**
     * Сопряжение по коду с экрана «Подключение с помощью кода». Без порта он ищется через
     * mDNS — с телефона так вводится только шестизначный код.
     */
    fun pair(context: Context, port: Int?, code: String): Outcome {
        return try {
            var host: String? = null
            var pairPort = port ?: -1
            if (pairPort <= 0) {
                val service = AdbMdns.discover(context, AdbMdns.TLS_PAIRING, 5000)
                    ?: return Outcome(false, context.getString(R.string.adb_pair_port_not_found))
                host = service.host.hostAddress
                pairPort = service.port
            }
            // Сервер сопряжения adbd слушает на адресе локальной сети, а не на loopback
            val pairHost = host ?: AndroidUtils.getHostIpAddress(context)
            val mgr = AppAdbConnectionManager.getInstance(context)
            if (!mgr.pair(pairHost, pairPort, code)) {
                return Outcome(false, context.getString(R.string.adb_pair_failed, "?"))
            }
            try {
                mgr.autoConnect(context, CONNECT_TIMEOUT_MS)
            } catch (_: Throwable) {
                // Сопряжение уже удалось; рабочее соединение поднимется при первом захвате.
            }
            Outcome(true, context.getString(R.string.adb_pair_success))
        } catch (e: Throwable) {
            Outcome(false, context.getString(R.string.adb_pair_failed, e.message ?: "?"))
        }
    }

    /**
     * Выдаёт приложению через ADB то, что сам пользователь с пульта выдать обычно не может:
     * запись экрана без диалога подтверждения, запуск окон поверх других приложений (без него
     * Android 10+ не показывает окно подтверждения при автозапуске) и работу в фоне без
     * ограничений энергосбережения. Именно это держит автозапуск после сна и включения ТВ.
     */
    fun grantPermissions(context: Context): Outcome {
        val pkg = context.packageName
        val commands = buildList {
            add("appops set $pkg PROJECT_MEDIA allow")
            add("appops set $pkg SYSTEM_ALERT_WINDOW allow")
            add("appops set $pkg RUN_ANY_IN_BACKGROUND allow")
            // Статистика использования нужна только задержке по приложениям - в проверку
            // успеха ниже не входит
            add("appops set $pkg GET_USAGE_STATS allow")
            add("cmd deviceidle whitelist +$pkg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add("pm grant $pkg android.permission.POST_NOTIFICATIONS")
            }
        }
        var lastError: Throwable? = null
        for (command in commands) {
            try {
                val output = shell(context, command).trim()
                if (output.isNotEmpty()) Log.i(TAG, "$command -> $output")
            } catch (e: AdbPairingRequiredException) {
                return Outcome(false, context.getString(R.string.error_adb_pairing_required))
            } catch (e: Throwable) {
                // Команды независимы: без deviceidle на урезанной прошивке остальные всё
                // равно нужны. Что в итоге выдано, решает проверка ниже, а не вывод команд
                Log.w(TAG, "$command failed: ${e.message}")
                lastError = e
            }
        }
        val missing = buildList {
            if (!PermissionHelper.hasProjectMediaPermission(context)) add("PROJECT_MEDIA")
            if (!PermissionHelper.canDrawOverlays(context)) add("SYSTEM_ALERT_WINDOW")
            if (!PermissionHelper.isIgnoringBatteryOptimizations(context)) add("deviceidle")
        }
        val error = lastError
        return when {
            missing.isEmpty() -> Outcome(true, context.getString(R.string.adb_grant_success))
            // Не выдано ничего и была ошибка — дело в самом ADB, а не в прошивке
            missing.size == 3 && error != null ->
                Outcome(false, context.getString(R.string.adb_grant_failed, error.message ?: "?"))

            else -> Outcome(false, context.getString(R.string.adb_grant_partial, missing.joinToString()))
        }
    }

    /** Команда оболочки от имени shell через собственный ADB устройства. Блокирует. */
    fun shell(context: Context, command: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val mgr = AppAdbConnectionManager.getInstance(context)
            if (mgr.isConnected || mgr.autoConnect(context, CONNECT_TIMEOUT_MS)) {
                val stream = mgr.openStream("shell:$command")
                return try {
                    readToEnd(stream)
                } finally {
                    try {
                        stream.close()
                    } catch (_: Exception) {
                        // Поток команды закрывается сам после её завершения.
                    }
                }
            }
        }
        // Android 10 и ниже, а также Android 11+ после `adb tcpip`: RSA-путь через dadb
        val port = Preferences(context).getString(R.string.pref_key_adb_port, "5555")
            ?.toIntOrNull() ?: 5555
        return Dadb.create("127.0.0.1", port, AdbKeyHelper.getKeyPair(context)).use {
            it.shell(command).allOutput
        }
    }

    /**
     * Запускает команду и оставляет её работать: пишут в [Shell.output], читают из
     * [Shell.input]. Путь подключения тот же, что у [shell]. Бросает
     * [AdbPairingRequiredException], если ADB ещё не сопряжён.
     */
    fun openShell(context: Context, command: String): Shell {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val mgr = AppAdbConnectionManager.getInstance(context)
            if (mgr.isConnected || mgr.autoConnect(context, CONNECT_TIMEOUT_MS)) {
                val stream = mgr.openStream("shell:$command")
                return object : Shell {
                    override val input: InputStream = stream.openInputStream()
                    override val output: OutputStream = stream.openOutputStream()
                    override fun close() = stream.close()
                }
            }
        }
        val port = Preferences(context).getString(R.string.pref_key_adb_port, "5555")
            ?.toIntOrNull() ?: 5555
        val dadb = Dadb.create("127.0.0.1", port, AdbKeyHelper.getKeyPair(context))
        val stream = try {
            dadb.open("shell:$command")
        } catch (e: Exception) {
            // Соединение с adbd уже поднято, без закрытия оно осталось бы висеть
            dadb.close()
            throw e
        }
        return object : Shell {
            override val input: InputStream = stream.source.inputStream()
            override val output: OutputStream = stream.sink.outputStream()
            override fun close() {
                stream.close()
                dadb.close()
            }
        }
    }

    /**
     * Весь вывод команды. Конец вывода libadb отдаёт не -1, а исключением «Stream closed.»,
     * если adbd закрыл поток, пока чтение ждало данных: так заканчивается любая команда без
     * вывода, например `appops set`. Закрытый поток здесь — нормальный конец.
     */
    private fun readToEnd(stream: AdbStream): String {
        val input = stream.openInputStream()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = try {
                input.read(buffer)
            } catch (e: IOException) {
                if (stream.isClosed) break else throw e
            }
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }
}
