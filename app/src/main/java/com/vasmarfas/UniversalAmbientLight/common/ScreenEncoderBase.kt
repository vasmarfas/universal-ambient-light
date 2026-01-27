package com.vasmarfas.UniversalAmbientLight.common

import android.content.res.Configuration
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import com.vasmarfas.UniversalAmbientLight.common.network.HyperionThread
import com.vasmarfas.UniversalAmbientLight.common.util.BorderProcessor
import com.vasmarfas.UniversalAmbientLight.common.util.AppOptions

abstract class ScreenEncoderBase(
    protected val mListener: HyperionThread.HyperionThreadListener,
    protected val mMediaProjection: MediaProjection,
    width: Int,
    height: Int,
    protected val mDensity: Int,
    options: AppOptions
) {

    // Configuration (immutable after construction)
    protected val mFrameRate: Int = options.frameRate
    protected val mAvgColor: Boolean = options.useAverageColor
    protected val mRemoveBorders = false // Disabled for now
    private val mInitOrientation: Int
    private val mWidthScaled: Int
    private val mHeightScaled: Int
    
    // Store real screen dimensions
    private val mScreenWidth: Int = width
    private val mScreenHeight: Int = height

    // Components
    protected val mBorderProcessor: BorderProcessor
    protected val mHandler: Handler

    // Mutable state
    @Volatile
    protected var mCurrentOrientation: Int = 0
    @Volatile
    private var mIsCapturing: Boolean = false

    init {
        mBorderProcessor = BorderProcessor(options.blackThreshold)

        // Determine orientation
        mInitOrientation = if (width > height) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        mCurrentOrientation = mInitOrientation

        // Calculate scaled dimensions
        val divisor = options.findDivisor(width, height)
        mWidthScaled = width / divisor
        mHeightScaled = height / divisor

        // Handler thread for callbacks
        val thread = HandlerThread(TAG, Process.THREAD_PRIORITY_DISPLAY)
        thread.start()
        mHandler = Handler(thread.looper)

        if (DEBUG) {
            Log.d(TAG, "Init: " + width + "x" + height + " -> " + mWidthScaled + "x" + mHeightScaled)
        }
    }

    fun clearLights() {
        Thread {
            sleep(CLEAR_DELAY_MS.toLong())
            mListener.clear()
        }.start()
    }

    protected fun clearAndDisconnect() {
        Thread {
            sleep(CLEAR_DELAY_MS.toLong())
            mListener.clear()
            mListener.disconnect()
        }.start()
    }

    fun isCapturing(): Boolean {
        return mIsCapturing
    }

    protected fun setCapturing(capturing: Boolean) {
        mIsCapturing = capturing
    }

    fun sendStatus() {
        mListener.sendStatus(mIsCapturing)
    }

    protected fun getGrabberWidth(): Int {
        return if (mInitOrientation != mCurrentOrientation) mHeightScaled else mWidthScaled
    }

    protected fun getGrabberHeight(): Int {
        return if (mInitOrientation != mCurrentOrientation) mWidthScaled else mHeightScaled
    }
    
    // Get real screen dimensions (not scaled down by divisor)
    protected fun getScreenWidth(): Int {
        return if (mInitOrientation != mCurrentOrientation) mScreenHeight else mScreenWidth
    }
    
    protected fun getScreenHeight(): Int {
        return if (mInitOrientation != mCurrentOrientation) mScreenWidth else mScreenHeight
    }

    abstract fun stopRecording()
    abstract fun resumeRecording()
    abstract fun setOrientation(orientation: Int)

    companion object {
        private const val TAG = "ScreenEncoderBase"
        const val DEBUG = false
        private const val CLEAR_DELAY_MS = 100

        private fun sleep(ms: Long) {
            try {
                Thread.sleep(ms)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
