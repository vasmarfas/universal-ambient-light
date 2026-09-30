package com.vasmarfas.UniversalAmbientLight.common.input

import android.content.Context

/**
 * Заглушка для Google Play: службы доступности в этой сборке нет, и ввод с телефона идёт
 * только через ADB. API повторяет класс из флейвора full.
 */
@Suppress("UNUSED_PARAMETER")
object AccessibilityInput {
    fun isAvailable(): Boolean = false
    fun key(keyCode: Int): Boolean = false
    fun tap(x: Float, y: Float, durationMs: Long): Boolean = false
    fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long): Boolean = false
    fun editFocusedText(edit: (String) -> String): Boolean = false
    fun overlayContext(): Context? = null
}
