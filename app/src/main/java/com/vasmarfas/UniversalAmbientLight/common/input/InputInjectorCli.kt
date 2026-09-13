@file:JvmName("InputInjectorCli")

package com.vasmarfas.UniversalAmbientLight.common.input

import android.annotation.SuppressLint
import android.hardware.input.InputManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import java.lang.reflect.Method
import kotlin.system.exitProcess

/** Первая строка процесса: инжектор готов принимать команды. */
internal const val INJECTOR_READY = "ready"

/**
 * Отдельный процесс ввода для телефона-пульта. Запускается через собственный ADB
 * приложения:
 *
 *   CLASSPATH=<apk> app_process /system/bin com.vasmarfas.UniversalAmbientLight.common.input.InputInjectorCli
 *
 * и работает от shell, у которого есть INJECT_EVENTS. Так кнопки, мышь и текст приходят
 * в приложения как от настоящего пульта и клавиатуры, а задержка - миллисекунды: каждая
 * команда `input keyevent` поднимала бы новый app_process на полсекунды.
 *
 * Команды по одной на строку в stdin:
 *   key down|up <keycode> <repeat> [meta]
 *   text <символы виртуальной клавиатуры>
 *   mouse move|down|up <x> <y>
 *   scroll <x> <y> <horizontal> <vertical>
 */
fun main() {
    val injector = try {
        EventInjector()
    } catch (e: ReflectiveOperationException) {
        println("error ${e.javaClass.simpleName}: ${e.message}")
        exitProcess(1)
    }
    println(INJECTOR_READY)
    System.out.flush()
    val input = System.`in`.bufferedReader()
    while (true) {
        val line = input.readLine() ?: break
        try {
            injector.execute(line)
        } catch (e: Exception) {
            // Кривая команда или отказ системы на одном событии не должны ронять процесс:
            // следующая команда может пройти
            System.err.println("${e.javaClass.simpleName}: ${e.message} ($line)")
        }
    }
}

private class EventInjector {

    private val mManager: Any
    private val mInject: Method

    // Кнопку мыши у события BUTTON_PRESS задаёт скрытый setActionButton; без неё система
    // отбрасывает такие события, и мышь остаётся с одними DOWN/UP - их понимает почти всё
    private val mSetActionButton: Method? = try {
        MotionEvent::class.java.getMethod("setActionButton", Int::class.javaPrimitiveType)
    } catch (_: NoSuchMethodException) {
        null
    }

    private val mKeyboard = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
    private var mKeyDownTime = 0L
    private var mMouseDownTime = 0L
    private var mButtons = 0

    // Процесс работает от shell, и скрытый API ввода здесь и есть смысл всего класса
    @SuppressLint("PrivateApi")
    private fun inputManagerGlobal(): Class<*>? = try {
        Class.forName("android.hardware.input.InputManagerGlobal")
    } catch (_: ClassNotFoundException) {
        null
    }

    init {
        // Android 14 перенёс InputManager.getInstance() в InputManagerGlobal
        val global = inputManagerGlobal()
        if (global != null) {
            mManager = checkNotNull(global.getMethod("getInstance").invoke(null)) { "no InputManagerGlobal" }
            mInject = global.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        } else {
            mManager = checkNotNull(InputManager::class.java.getMethod("getInstance").invoke(null)) { "no InputManager" }
            mInject = InputManager::class.java.getMethod(
                "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType
            )
        }
    }

    fun execute(line: String) {
        val parts = line.split(' ')
        when (parts[0]) {
            "key" -> key(
                down = parts[1] == "down",
                code = parts[2].toInt(),
                repeat = parts.getOrNull(3)?.toInt() ?: 0,
                meta = parts.getOrNull(4)?.toInt() ?: 0
            )

            "text" -> text(line.substringAfter(' ', ""))
            "mouse" -> mouse(parts[1], parts[2].toFloat(), parts[3].toFloat())
            "scroll" -> scroll(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat(), parts[4].toFloat())
            else -> throw IllegalArgumentException("unknown command")
        }
    }

    private fun key(down: Boolean, code: Int, repeat: Int, meta: Int) {
        val now = SystemClock.uptimeMillis()
        if (down && repeat == 0) mKeyDownTime = now
        // Первый повтор удерживаемой кнопки помечен долгим нажатием, как у настоящего пульта:
        // по этому флагу приложения открывают контекстные меню
        val flags = if (down && repeat == 1) KeyEvent.FLAG_LONG_PRESS else 0
        val action = if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        inject(
            KeyEvent(
                mKeyDownTime, now, action, code, repeat, meta,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags, InputDevice.SOURCE_KEYBOARD
            )
        )
    }

    private fun text(text: String) {
        for (c in text) {
            val events = mKeyboard.getEvents(charArrayOf(c)) ?: continue
            for (event in events) inject(KeyEvent.changeTimeRepeat(event, SystemClock.uptimeMillis(), 0))
        }
    }

    private fun mouse(action: String, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        when (action) {
            "move" -> motion(
                if (mButtons != 0) MotionEvent.ACTION_MOVE else MotionEvent.ACTION_HOVER_MOVE,
                x, y, now
            )

            "down" -> {
                mMouseDownTime = now
                mButtons = MotionEvent.BUTTON_PRIMARY
                motion(MotionEvent.ACTION_DOWN, x, y, now)
                motion(MotionEvent.ACTION_BUTTON_PRESS, x, y, now, MotionEvent.BUTTON_PRIMARY)
            }

            "up" -> {
                mButtons = 0
                motion(MotionEvent.ACTION_BUTTON_RELEASE, x, y, now, MotionEvent.BUTTON_PRIMARY)
                motion(MotionEvent.ACTION_UP, x, y, now)
            }
        }
    }

    private fun scroll(x: Float, y: Float, horizontal: Float, vertical: Float) {
        motion(MotionEvent.ACTION_SCROLL, x, y, SystemClock.uptimeMillis(), 0, horizontal, vertical)
    }

    private fun motion(
        action: Int,
        x: Float,
        y: Float,
        now: Long,
        actionButton: Int = 0,
        hscroll: Float = 0f,
        vscroll: Float = 0f,
    ) {
        if (actionButton != 0 && mSetActionButton == null) return
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = if (mButtons != 0) 1f else 0f
            setAxisValue(MotionEvent.AXIS_HSCROLL, hscroll)
            setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll)
        }
        val pressed = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE ||
                action == MotionEvent.ACTION_UP || actionButton != 0
        val event = MotionEvent.obtain(
            if (pressed) mMouseDownTime else now, now, action, 1,
            arrayOf(properties), arrayOf(coords), 0, mButtons, 1f, 1f, 0, 0,
            InputDevice.SOURCE_MOUSE, 0
        )
        if (actionButton != 0) mSetActionButton?.invoke(event, actionButton)
        inject(event)
        event.recycle()
    }

    private fun inject(event: InputEvent) {
        mInject.invoke(mManager, event, INJECT_ASYNC)
    }

    companion object {
        /** InputManager.INJECT_INPUT_EVENT_MODE_ASYNC - константа скрыта из SDK. */
        private const val INJECT_ASYNC = 0
    }
}
