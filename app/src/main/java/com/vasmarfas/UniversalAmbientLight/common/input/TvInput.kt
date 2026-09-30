package com.vasmarfas.UniversalAmbientLight.common.input

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.util.AdbSetup
import com.vasmarfas.UniversalAmbientLight.common.util.PermissionHelper
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ввод на ТВ по командам телефона: кнопки пульта, мышь, текст и запуск приложений.
 *
 * Путей три. Процесс ввода через ADB ([InputInjector]) умеет всё, и приложения видят его
 * как настоящие пульт, мышь и клавиатуру. Служба доступности (только в сборке full) даёт
 * системные кнопки, касания и текст прямо в поле ввода. Громкость и медиаклавиши работают
 * всегда: AudioManager их принимает без всяких разрешений.
 *
 * Команды одного телефона приходят с одного потока и по порядку.
 */
class TvInput(context: Context) {

    private val mContext = context.applicationContext
    private val mInjector = InputInjector(mContext)
    private val mCursor = CursorOverlay(mContext)
    private val mMain = Handler(Looper.getMainLooper())
    private val mAudio = mContext.getSystemService(AudioManager::class.java)
    private val mRepeater = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "input-repeat") }

    private var mHeldCode = KeyEvent.KEYCODE_UNKNOWN
    private var mHeldRepeat: ScheduledFuture<*>? = null

    // ADB только что не поднялся: не ждём его заново на каждое нажатие
    @Volatile
    private var mInjectorFailedAt = 0L

    @Volatile
    private var mInjectorError: String? = null

    private var mX = -1f
    private var mY = -1f
    private val mHideCursor = Runnable { mCursor.hide() }

    /** Поднимает ввод заранее и рассказывает телефону, что на этом ТВ доступно. */
    fun prepare(): JSONObject {
        mInjectorFailedAt = 0L
        val adb = tryInjector { mInjector.prepare() }
        val accessibility = AccessibilityInput.isAvailable()
        return JSONObject()
            .put("adb", adb)
            .put("accessibility", accessibility)
            .put("cursor", PermissionHelper.canDrawOverlays(mContext) || accessibility)
            .put("error", if (adb) null else mInjectorError)
    }

    @Throws(IOException::class)
    fun key(code: Int, action: String, count: Int) {
        when (action) {
            KEY_DOWN -> keyDown(code)
            KEY_UP -> if (mHeldCode == code) releaseHeld()
            else -> repeat(count.coerceIn(1, MAX_PRESSES)) { press(code) }
        }
    }

    @Throws(IOException::class)
    fun text(text: String) {
        for (chunk in chunks(text)) {
            when {
                chunk == "\n" -> press(KeyEvent.KEYCODE_ENTER)
                isTypable(chunk[0]) -> if (!send("text $chunk")) appendText(chunk)
                else -> pasteText(chunk)
            }
        }
    }

    @Throws(IOException::class)
    fun pointer(action: String, dx: Float, dy: Float) {
        when (action) {
            POINTER_SHOW -> showCursor()
            POINTER_HIDE -> {
                mMain.removeCallbacks(mHideCursor)
                mMain.post(mHideCursor)
            }

            POINTER_MOVE -> move(dx, dy)
            POINTER_CLICK -> click(TAP_MS)
            POINTER_LONG -> click(LONG_CLICK_MS)
            POINTER_SCROLL -> scroll(dx, dy)
        }
    }

    @Throws(IOException::class)
    fun launch(pkg: String) {
        val intent = TvApps.launchIntent(mContext, pkg)
            ?: throw IOException(mContext.getString(R.string.remote_app_not_found))
        val component = intent.component
        // Из фона Android 10+ открывает окна только приложению с правом наложения или со
        // службой доступности; остальным остаётся запуск от имени shell
        val canStart = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                PermissionHelper.canDrawOverlays(mContext) || AccessibilityInput.isAvailable()
        if (canStart || component == null) {
            mMain.post {
                try {
                    mContext.startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    Log.w(TAG, "Cannot launch $pkg: ${e.message}")
                }
            }
            return
        }
        val category = intent.categories?.firstOrNull()?.let { " -c $it" }.orEmpty()
        try {
            AdbSetup.shell(
                mContext,
                "am start -a android.intent.action.MAIN$category -n ${component.flattenToShortString()}"
            )
        } catch (e: Exception) {
            // libadb бросает свои исключения, телефону нужна одна понятная причина
            throw IOException(mContext.getString(R.string.remote_input_needs_adb, e.message ?: "?"), e)
        }
    }

    /** Телефон отключился: отпускаем кнопку и прячем курсор, чтобы ничего не залипло. */
    fun release() {
        releaseHeld()
        mMain.post(mHideCursor)
    }

    fun shutdown() {
        release()
        mRepeater.shutdownNow()
        mInjector.shutdown()
    }

    private fun keyDown(code: Int) {
        releaseHeld()
        if (!send("key down $code 0")) {
            // Без ADB удержание не повторить, хватит одного нажатия
            if (!fallbackKey(code)) throw unavailable()
            return
        }
        val repeat = AtomicInteger()
        val pressedAt = SystemClock.elapsedRealtime()
        synchronized(this) {
            mHeldCode = code
            mHeldRepeat = mRepeater.scheduleWithFixedDelay({
                // Телефон мог пропасть, не отпустив кнопку: дольше MAX_HOLD_MS не держим
                if (SystemClock.elapsedRealtime() - pressedAt > MAX_HOLD_MS) {
                    releaseHeld()
                } else {
                    send("key down $code ${repeat.incrementAndGet()}")
                }
            }, LONG_PRESS_MS, REPEAT_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun releaseHeld() {
        val code: Int
        synchronized(this) {
            code = mHeldCode
            if (code == KeyEvent.KEYCODE_UNKNOWN) return
            mHeldCode = KeyEvent.KEYCODE_UNKNOWN
            mHeldRepeat?.cancel(false)
            mHeldRepeat = null
        }
        send("key up $code 0")
    }

    private fun press(code: Int) {
        if (send("key down $code 0") && send("key up $code 0")) return
        if (!fallbackKey(code)) throw unavailable()
    }

    private fun fallbackKey(code: Int): Boolean {
        val audio = mAudio ?: return AccessibilityInput.key(code)
        when (code) {
            KeyEvent.KEYCODE_VOLUME_UP -> audio.adjustVolume(AudioManager.ADJUST_RAISE)
            KeyEvent.KEYCODE_VOLUME_DOWN -> audio.adjustVolume(AudioManager.ADJUST_LOWER)
            KeyEvent.KEYCODE_VOLUME_MUTE -> audio.adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE)
            in MEDIA_KEYS -> {
                // Медиаклавиши получает активная медиасессия, то есть плеер на экране
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            }

            else -> return AccessibilityInput.key(code)
        }
        return true
    }

    private fun AudioManager.adjustVolume(direction: Int) {
        adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    private fun appendText(chunk: String) {
        if (!AccessibilityInput.editFocusedText { it + chunk }) throw unavailable()
    }

    /**
     * Кириллицы и прочего не-ASCII на виртуальной клавиатуре Android нет, набрать их
     * событиями нельзя. Служба доступности дописывает текст прямо в поле, без неё - вставка
     * через буфер обмена: писать в него фоновому приложению система разрешает.
     */
    private fun pasteText(chunk: String) {
        if (AccessibilityInput.editFocusedText { it + chunk }) return
        val clipboard = mContext.getSystemService(ClipboardManager::class.java) ?: throw unavailable()
        clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, chunk))
        if (!(send("key down ${KeyEvent.KEYCODE_PASTE} 0") && send("key up ${KeyEvent.KEYCODE_PASTE} 0"))) {
            throw unavailable()
        }
    }

    private fun showCursor() {
        if (mX < 0) {
            val (width, height) = screenSize()
            mX = width / 2f
            mY = height / 2f
        }
        val x = mX.toInt()
        val y = mY.toInt()
        mMain.post { mCursor.show(x, y) }
        scheduleCursorHide()
    }

    private fun move(dx: Float, dy: Float) {
        if (mX < 0) showCursor()
        val (width, height) = screenSize()
        // Телефон присылает сдвиг в dp; в пиксели ТВ его переводит плотность экрана ТВ
        val density = mContext.resources.displayMetrics.density
        mX = (mX + dx * density).coerceIn(0f, width - 1f)
        mY = (mY + dy * density).coerceIn(0f, height - 1f)
        val x = mX.toInt()
        val y = mY.toInt()
        mMain.post { if (!mCursor.isShown) mCursor.show(x, y) else mCursor.moveTo(x, y) }
        scheduleCursorHide()
        send("mouse move $x $y")
    }

    private fun click(durationMs: Long) {
        if (mX < 0) showCursor()
        val x = mX.toInt()
        val y = mY.toInt()
        scheduleCursorHide()
        if (send("mouse down $x $y")) {
            if (durationMs > TAP_MS) SystemClock.sleep(durationMs)
            send("mouse up $x $y")
            return
        }
        if (!AccessibilityInput.tap(mX, mY, durationMs)) throw unavailable()
    }

    /** [dx] и [dy] - щелчки колеса по направлению движения пальцев на телефоне. */
    private fun scroll(dx: Float, dy: Float) {
        if (mX < 0) showCursor()
        scheduleCursorHide()
        // Пальцы вверх листают содержимое вниз, как на тачпаде: для колеса это отрицательный
        // VSCROLL, а по горизонтали знак у колеса обратный
        if (send("scroll ${mX.toInt()} ${mY.toInt()} ${-dx} $dy")) return
        val density = mContext.resources.displayMetrics.density
        val swiped = AccessibilityInput.swipe(
            mX, mY,
            mX + dx * SCROLL_STEP_DP * density, mY + dy * SCROLL_STEP_DP * density,
            SWIPE_MS
        )
        if (!swiped) throw unavailable()
    }

    private fun scheduleCursorHide() {
        mMain.removeCallbacks(mHideCursor)
        mMain.postDelayed(mHideCursor, CURSOR_HIDE_MS)
    }

    private fun screenSize(): Pair<Int, Int> {
        val windowManager = mContext.getSystemService(WindowManager::class.java)
        if (windowManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }
        val metrics = mContext.resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun send(command: String): Boolean {
        if (SystemClock.elapsedRealtime() - mInjectorFailedAt < RETRY_AFTER_MS) return false
        return tryInjector { mInjector.send(command) }
    }

    private fun tryInjector(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (e: IOException) {
        Log.w(TAG, "Input helper unavailable: ${e.message}")
        mInjectorFailedAt = SystemClock.elapsedRealtime()
        mInjectorError = e.message
        false
    }

    private fun unavailable() =
        IOException(mContext.getString(R.string.remote_input_needs_adb, mInjectorError ?: "?"))

    companion object {
        private const val TAG = "TvInput"

        const val KEY_DOWN = "down"
        const val KEY_UP = "up"
        const val KEY_PRESS = "press"

        const val POINTER_SHOW = "show"
        const val POINTER_HIDE = "hide"
        const val POINTER_MOVE = "move"
        const val POINTER_CLICK = "click"
        const val POINTER_LONG = "long"
        const val POINTER_SCROLL = "scroll"

        private const val CLIP_LABEL = "remote"

        // Как у настоящего пульта: повтор начинается через полсекунды и идёт 20 раз в секунду
        private const val LONG_PRESS_MS = 500L
        private const val REPEAT_MS = 50L
        private const val MAX_HOLD_MS = 10_000L
        private const val MAX_PRESSES = 50

        private const val TAP_MS = 60L
        private const val LONG_CLICK_MS = 800L
        private const val SWIPE_MS = 200L
        private const val SCROLL_STEP_DP = 60f
        private const val CURSOR_HIDE_MS = 30_000L
        private const val RETRY_AFTER_MS = 20_000L

        private val MEDIA_KEYS = setOf(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_REWIND,
        )

        private fun isTypable(c: Char) = c in ' '..'~'

        /** Текст на куски: ASCII подряд, остальное подряд, перевод строки отдельно. */
        internal fun chunks(text: String): List<String> {
            val result = ArrayList<String>()
            val current = StringBuilder()
            var typable = false
            for (c in text) {
                if (c == '\n' || c == '\r') {
                    if (current.isNotEmpty()) result += current.toString()
                    current.clear()
                    if (c == '\n') result += "\n"
                    continue
                }
                if (current.isNotEmpty() && isTypable(c) != typable) {
                    result += current.toString()
                    current.clear()
                }
                typable = isTypable(c)
                current.append(c)
            }
            if (current.isNotEmpty()) result += current.toString()
            return result
        }
    }
}
