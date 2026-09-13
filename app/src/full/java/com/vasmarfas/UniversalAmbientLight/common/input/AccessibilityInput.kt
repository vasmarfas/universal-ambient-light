package com.vasmarfas.UniversalAmbientLight.common.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.vasmarfas.UniversalAmbientLight.common.AccessibilityCaptureService

/**
 * Ввод через службу доступности: запасной путь пульта, когда ADB на ТВ не настроен.
 * Системные кнопки (стрелки только с Android 13), касания жестами и текст прямо в поле
 * ввода. В сборке для Google Play на месте этого объекта заглушка.
 */
object AccessibilityInput {

    fun isAvailable(): Boolean = AccessibilityCaptureService.getInstance() != null

    fun key(keyCode: Int): Boolean {
        val service = AccessibilityCaptureService.getInstance() ?: return false
        val action = globalAction(keyCode) ?: return false
        return service.performGlobalAction(action)
    }

    fun tap(x: Float, y: Float, durationMs: Long): Boolean =
        gesture(Path().apply { moveTo(x, y) }, durationMs)

    fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long): Boolean =
        gesture(Path().apply { moveTo(fromX, fromY); lineTo(toX, toY) }, durationMs)

    /** Меняет текст поля ввода в фокусе; false - такого поля нет. */
    fun editFocusedText(edit: (String) -> String): Boolean {
        val service = AccessibilityCaptureService.getInstance() ?: return false
        val node = service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (!node.isEditable) return false
        // Пустое поле отдаёт текстом свою подсказку - её дописывать нельзя
        val current = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val updated = edit(current)
        val text = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text)) return false
        val cursor = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, updated.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, updated.length)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, cursor)
        return true
    }

    /** Окно поверх всего без разрешения на наложение можно открыть от имени самой службы. */
    fun overlayContext(): Context? = AccessibilityCaptureService.getInstance()

    private fun gesture(path: Path, durationMs: Long): Boolean {
        val service = AccessibilityCaptureService.getInstance() ?: return false
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1))
        return service.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun globalAction(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_BACK -> AccessibilityService.GLOBAL_ACTION_BACK
        KeyEvent.KEYCODE_HOME -> AccessibilityService.GLOBAL_ACTION_HOME
        KeyEvent.KEYCODE_APP_SWITCH -> AccessibilityService.GLOBAL_ACTION_RECENTS
        KeyEvent.KEYCODE_NOTIFICATION -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
        else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) dpadAction(keyCode) else null
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun dpadAction(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> AccessibilityService.GLOBAL_ACTION_DPAD_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> AccessibilityService.GLOBAL_ACTION_DPAD_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> AccessibilityService.GLOBAL_ACTION_DPAD_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> AccessibilityService.GLOBAL_ACTION_DPAD_RIGHT
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> AccessibilityService.GLOBAL_ACTION_DPAD_CENTER
        else -> null
    }
}
