package com.vasmarfas.UniversalAmbientLight.common.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.graphics.withScale
import com.vasmarfas.UniversalAmbientLight.common.util.PermissionHelper

/**
 * Стрелка мыши поверх любых приложений ТВ. Системный указатель от внедрённых событий не
 * появляется, Android рисует его только для настоящих устройств, поэтому курсор свой.
 * Окно не берёт ни касаний, ни фокуса: клики уходят сквозь него в приложение под ним.
 *
 * Все методы вызываются с главного потока.
 */
class CursorOverlay(private val mContext: Context) {

    private var mView: View? = null
    private var mWindowManager: WindowManager? = null
    private var mParams: WindowManager.LayoutParams? = null

    val isShown: Boolean
        get() = mView != null

    /** false - ни разрешения на наложение, ни службы доступности, показать нечем. */
    fun show(x: Int, y: Int): Boolean {
        if (mView != null) {
            moveTo(x, y)
            return true
        }
        // Без разрешения на наложение окно можно открыть от имени службы доступности
        val overlayAllowed = PermissionHelper.canDrawOverlays(mContext)
        val context = if (overlayAllowed) mContext else AccessibilityInput.overlayContext() ?: return false
        val type = if (overlayAllowed) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        }
        val density = context.resources.displayMetrics.density
        val view = PointerView(context, density)
        val params = WindowManager.LayoutParams(
            view.widthPx,
            view.heightPx,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val windowManager = context.getSystemService(WindowManager::class.java) ?: return false
        return try {
            windowManager.addView(view, params)
            mView = view
            mWindowManager = windowManager
            mParams = params
            true
        } catch (e: RuntimeException) {
            // Разрешение отозвали, пока шла проверка, или прошивка режет окна наложения
            Log.w(TAG, "Cannot show the cursor: ${e.message}")
            false
        }
    }

    fun moveTo(x: Int, y: Int) {
        val view = mView ?: return
        val params = mParams ?: return
        if (params.x == x && params.y == y) return
        params.x = x
        params.y = y
        try {
            mWindowManager?.updateViewLayout(view, params)
        } catch (e: IllegalArgumentException) {
            // Окно уже снято системой (служба доступности отключилась)
            mView = null
        }
    }

    fun hide() {
        val view = mView ?: return
        mView = null
        try {
            mWindowManager?.removeView(view)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Cursor window is already gone: ${e.message}")
        }
    }

    /** Классическая стрелка: белая с тёмной обводкой, видна и на светлом, и на тёмном. */
    private class PointerView(context: Context, density: Float) : View(context) {
        private val mScale = density * 1.3f
        val widthPx = (14 * mScale).toInt()
        val heightPx = (21 * mScale).toInt()
        private val mPath = Path().apply {
            moveTo(1f, 1f)
            lineTo(1f, 16f)
            lineTo(4.5f, 12.5f)
            lineTo(7f, 18.5f)
            lineTo(9.5f, 17.5f)
            lineTo(7f, 11.5f)
            lineTo(12f, 11.5f)
            close()
        }
        private val mFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val mStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(230, 20, 20, 20)
            style = Paint.Style.STROKE
            strokeWidth = 1.2f
            strokeJoin = Paint.Join.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            canvas.withScale(mScale, mScale) {
                drawPath(mPath, mFill)
                drawPath(mPath, mStroke)
            }
        }
    }

    companion object {
        private const val TAG = "CursorOverlay"
    }
}
