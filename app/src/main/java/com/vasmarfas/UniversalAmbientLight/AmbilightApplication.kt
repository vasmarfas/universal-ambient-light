package com.vasmarfas.UniversalAmbientLight

import android.app.Application
import android.content.Context
import android.os.DeadSystemException
import android.util.Log
import com.vasmarfas.UniversalAmbientLight.common.util.AnalyticsHelper
import com.vasmarfas.UniversalAmbientLight.common.util.LocaleHelper
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import com.vasmarfas.UniversalAmbientLight.common.util.UsbRootPermissionHelper

class AmbilightApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.onAttach(base))
        // Раньше здесь вызывался HiddenApiBypass.addHiddenApiExemptions("L"), но Google Play
        // отклоняет SDK org.lsposed.hiddenapibypass (он ломается на новом ART и Android 16),
        // поэтому вызов убран. Возможности, опирающиеся на скрытые API из чёрного списка,
        // на новых Android могут работать хуже; серые API и способы захвата без ADB
        // (MediaProjection, accessibility) это не затрагивает.
    }

    override fun onCreate() {
        super.onCreate()
        installFrameworkBugFilter()
        seedXmlDefaults()
        migratePreferences()
        // Уводим с главного потока: здесь первое обращение к SharedPreferences (чтение с диска)
        // и работа с Firebase — прямо в onCreate это добавляет ANR на холодном старте.
        Thread { AnalyticsHelper.initializeUserProperties(this) }
            .apply { name = "analytics-init"; isDaemon = true }
            .start()
        // Проверка root (запуск `su`, до 2 секунд) иначе случилась бы синхронно на главном
        // потоке первого же BootActivity/ToggleActivity — на старте ТВ это вклад в ANR.
        UsbRootPermissionHelper.warmUpAsync()
    }

    /**
     * `Preferences.getString(key, default)` не смотрит на xml-ресурс `pref_default_*`:
     * вызывающий код передаёт свои значения по умолчанию, поэтому отладочное
     * переопределение в `pref_values.xml` никто бы не увидел. С `getBoolean` то же самое.
     * Эта процедура при первом запуске переносит xml-умолчания в SharedPreferences
     * (идемпотентно — заполняет только отсутствующие ключи), и переопределения начинают работать.
     */
    private fun seedXmlDefaults() {
        val prefs = Preferences(this)

        listOf(
            R.string.pref_key_connection_type to R.string.pref_default_connection_type,
            R.string.pref_key_wled_protocol to R.string.pref_default_wled_protocol
        ).forEach { (keyRes, defRes) ->
            if (!prefs.contains(keyRes)) {
                runCatching { prefs.putString(keyRes, getString(defRes)) }
            }
        }

        listOf(
            R.string.pref_key_color_processing_enabled to R.bool.pref_default_color_processing_enabled
        ).forEach { (keyRes, defRes) ->
            if (!prefs.contains(keyRes)) {
                runCatching { prefs.putBoolean(keyRes, resources.getBoolean(defRes)) }
            }
        }

        // Числовые настройки, для которых вызывающий код передаёт в getInt() свои константы
        // и поэтому никогда не увидит xml-умолчание, если его не посеять. Интервал и переход
        // Home Assistant сеются ещё и ради списков в настройках: ListPreference читает
        // строку и без посева показывал бы первый пункт вместо действующего значения.
        listOf(
            R.string.pref_key_port to R.integer.pref_default_port,
            R.string.pref_key_ha_update_interval to R.integer.pref_default_ha_update_interval,
            R.string.pref_key_ha_transition to R.integer.pref_default_ha_transition,
            R.string.pref_key_ha2_update_interval to R.integer.pref_default_ha2_update_interval,
            R.string.pref_key_ha2_transition to R.integer.pref_default_ha2_transition
        ).forEach { (keyRes, defRes) ->
            if (!prefs.contains(keyRes)) {
                runCatching {
                    // Числа хранятся строками — так же ведёт себя EditTextPreference.
                    prefs.putString(keyRes, resources.getInteger(defRes).toString())
                }
            }
        }
    }

    /**
     * Проглатывает несколько багов платформы и прошивок, которые всплывают необработанными
     * исключениями в потоках, нам не принадлежащих. Каждое условие сужено до предела, чтобы
     * настоящие падения приложения по-прежнему доходили до прежнего обработчика (Crashlytics).
     */
    private fun installFrameworkBugFilter() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val reason = when {
                isMediaCodecDisplayListenerNpe(throwable) -> "MediaCodec.onDisplayChanged NPE"
                isReportSizeConfigurationsBug(throwable) -> "ActivityThread.reportSizeConfigurations race"
                isForegroundServiceTimeout(throwable) -> "ForegroundServiceDidNotStartInTime (OEM blocked FGS)"
                isDeadSystemException(throwable) -> "DeadSystemException (system_server died)"
                isProfileVerifierFirmwareBug(throwable) -> "ProfileVerifier NoSuchMethodError (broken framework.jar)"
                isGoogleCertificatesRejection(throwable) -> "GoogleCertificatesRslt not allowed (uncertified GMS)"
                else -> null
            }
            if (reason != null) {
                Log.w("AmbilightApplication", "Swallowed framework bug ($reason) on ${thread.name}", throwable)
                return@setDefaultUncaughtExceptionHandler
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * NPE из фреймворка Android: внутренний DisplayListener у MediaCodec срабатывает при
     * отключении дисплея (например, вынули HDMI на NVIDIA SHIELD). На затронутых прошивках
     * слушатель переживает release(); кадров нашего приложения в стеке нет.
     */
    private fun isMediaCodecDisplayListenerNpe(t: Throwable): Boolean {
        if (t !is NullPointerException) return false
        val frames = t.stackTrace
        if (frames.isEmpty()) return false
        val top = frames[0]
        if (!top.className.startsWith("android.media.MediaCodec") || top.methodName != "onDisplayChanged") return false
        return frames.any { it.className.startsWith("android.hardware.display.DisplayManagerGlobal") }
    }

    /**
     * Гонка в AOSP: система сообщает размеры конфигурации активити, когда её ActivityRecord
     * уже удалён, и это всплывает как
     * `IllegalArgumentException: reportSizeConfigurations: ActivityRecord not found`.
     * Провоцируется короткоживущими активити-трамплинами (BootActivity); целиком
     * происходит внутри system_server, винить в нём наш код не в чем.
     */
    private fun isReportSizeConfigurationsBug(t: Throwable): Boolean {
        if (t !is IllegalArgumentException) return false
        if (t.message?.contains("reportSizeConfigurations") == true) return true
        return t.stackTrace.any {
            it.className == "android.app.ActivityThread" && it.methodName == "reportSizeConfigurations"
        }
    }

    /**
     * `ForegroundServiceDidNotStartInTimeException` для нашего же сервиса захвата. На
     * некоторых прошивках (например, TCL) startForeground() заблокирован, сервис осознанно
     * продолжает работать без уведомления, и платформа позже принудительно бросает это
     * исключение. Сужено до нашего пакета, чтобы настоящая ошибка «забыли вызвать
     * startForeground» в другом месте всё-таки всплыла.
     */
    private fun isForegroundServiceTimeout(t: Throwable): Boolean {
        if (!t.javaClass.name.contains("ForegroundServiceDidNotStartInTimeException")) return false
        return t.message?.contains(packageName) == true
    }

    /**
     * system_server умер (перезагрузка, падение системы): любой биндер-вызов начинает
     * бросать DeadSystemException, в том числе из колбэков, нам не принадлежащих. Процесс
     * всё равно будет убит вместе с системой — падать с крашем в отчётах незачем.
     */
    private fun isDeadSystemException(t: Throwable): Boolean {
        var cause: Throwable? = t
        while (cause != null) {
            if (cause is DeadSystemException) return true
            cause = cause.cause
        }
        return false
    }

    /**
     * NoSuchMethodError из фонового ProfileVerifier: встречается на прошивках, где
     * framework.jar не содержит API, положенных заявленной версии Android (например,
     * PackageInfoFlags.of на «Android 13»). Это диагностика профилей ART, на работу
     * приложения она не влияет — а починить чужую прошивку мы не можем.
     */
    private fun isProfileVerifierFirmwareBug(t: Throwable): Boolean {
        if (t !is NoSuchMethodError) return false
        return t.stackTrace.any { it.className.startsWith("androidx.profileinstaller.ProfileVerifier") }
    }

    /**
     * Play Services отказывается обслуживать наш пакет: `SecurityException:
     * GoogleCertificatesRslt: not allowed`. Прилетает с потока GoogleApiHandler, когда
     * Firebase Analytics подключается к сервису измерения, и встречается на ТВ-боксах с
     * несертифицированной прошивкой (UGOOS X5M, Android 14 на test-keys), где список
     * разрешённых сертификатов внутри GMS не отрабатывает. Со стороны приложения не
     * чинится, и ронять из-за телеметрии работающую подсветку незачем.
     *
     * Опознаём по тексту: сообщение приходит из процесса Play Services, а вот имена
     * классов GMS в стеке R8 переименовывает, и проверка по ним не сработала бы как раз
     * в release-сборке.
     */
    private fun isGoogleCertificatesRejection(t: Throwable): Boolean {
        if (t !is SecurityException) return false
        return t.message?.contains("GoogleCertificatesRslt") == true
    }

    private fun migratePreferences() {
        val prefs = Preferences(this)
        // Миграция: ключ pref_key_lighting_was_active появился позже. Для тех, у кого
        // автозапуск был включён до его появления, считаем подсветку активной, чтобы старт
        // с загрузки продолжил работать после обновления.
        if (!prefs.contains(R.string.pref_key_lighting_was_active)) {
            if (prefs.getBoolean(R.string.pref_key_boot)) {
                prefs.putBoolean(R.string.pref_key_lighting_was_active, true)
            }
        }
    }
}
