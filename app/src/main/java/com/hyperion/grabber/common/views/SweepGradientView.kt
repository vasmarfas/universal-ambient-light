package com.vasmarfas.UniversalAmbientLight.common.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View

/**
 * Created by nino on 26-5-18.
 */

class SweepGradientView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint: Paint
    private val colors = intArrayOf(
        Color.RED,
        Color.MAGENTA,
        Color.BLUE,
        Color.CYAN,
        Color.GREEN,
        Color.YELLOW,
        Color.RED
    )
    private val positions = floatArrayOf(0f, 1 / 6f, 2 / 6f, 3 / 6f, 4 / 6f, 5 / 6f, 6 / 6f)

    private var gradient: SweepGradient? = null

    private val viewBounds = Rect()

    private val gradientMatrix = Matrix()

    private var rotate = 0f

    init {

        paint = Paint()
        paint.shader = gradient
    }


    override fun onDraw(canvas: Canvas) {

        gradientMatrix.postRotate(rotate++, (viewBounds.width() / 2).toFloat(), (viewBounds.height() / 2).toFloat())
        gradient!!.setLocalMatrix(gradientMatrix)

        paint.shader = gradient
        canvas.drawRect(viewBounds, paint)
        gradientMatrix.reset()
        invalidate()

    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        viewBounds.set(0, 0, measuredWidth, measuredHeight)

        gradient = SweepGradient(viewBounds.centerX().toFloat(), viewBounds.centerY().toFloat(), colors, positions)
    }
}
