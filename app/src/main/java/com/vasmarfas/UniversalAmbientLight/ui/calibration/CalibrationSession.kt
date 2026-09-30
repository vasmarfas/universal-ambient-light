package com.vasmarfas.UniversalAmbientLight.ui.calibration

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.vasmarfas.UniversalAmbientLight.common.ScreenGrabberService
import com.vasmarfas.UniversalAmbientLight.common.remote.RemoteProtocol
import com.vasmarfas.UniversalAmbientLight.common.remote.RemoteSession
import com.vasmarfas.UniversalAmbientLight.common.util.CameraFrameDetectionRun
import com.vasmarfas.UniversalAmbientLight.common.util.CameraFrameDetector
import com.vasmarfas.UniversalAmbientLight.common.util.DelayEstimator
import com.vasmarfas.UniversalAmbientLight.common.util.GlowRegions
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Автоподбор задержки на стороне телефона. Камера смотрит на телевизор с фильмом, ТВ по
 * команде гасит ленту и снимает сглаживание, а [DelayEstimator] сравнивает, когда меняются
 * экран и свечение на стене.
 *
 * Порядок такой. Лента гаснет на несколько секунд: за это время детектор находит экран в
 * кадре (своё свечение мешало бы ему, это та же яркая область), а заодно замеряется, сколько
 * света даёт сам экран в зоне свечения. Потом экспозиция камеры фиксируется, и идёт замер,
 * пока не наберётся достаточно смен сцен.
 *
 * Кадры приходят в [onFrame] с потока анализа камеры, команды ТВ уходят со своего потока,
 * состояние для экрана - в [onState] на главном потоке.
 */
class CalibrationSession(
    private val onState: (State) -> Unit,
    private val onLockExposure: (x: Float, y: Float) -> Unit,
) {

    /** Что сейчас с TV: ответ на начало замера. */
    class TvInfo(val app: String?, val label: String?, val delayMs: Int, val usageAccess: Boolean)

    sealed class State {
        object Starting : State()
        object Searching : State()
        class Measuring(
            /** Углы экрана в долях буфера камеры, TL, TR, BR, BL. */
            val corners: FloatArray,
            val events: Int,
            val seconds: Int,
            /** null - ещё рано судить. */
            val glowVisible: Boolean?,
        ) : State()

        class Done(val result: DelayEstimator.Result, val info: TvInfo, val reliable: Boolean) : State()
        class Failed(val reason: Reason, val detail: String? = null) : State()
    }

    enum class Reason { TV_REFUSED, NO_SCREEN, TOO_DARK, TOO_CLOSE, NOT_ENOUGH_CHANGES, CONNECTION }

    private enum class Phase { IDLE, STARTING, DARK, SEARCHING, LOCKING, MEASURING, FINISHED }

    private val mMain = Handler(Looper.getMainLooper())
    private val mRpc = Executors.newSingleThreadExecutor { r -> Thread(r, "calibration-rpc") }

    @Volatile
    private var mPhase = Phase.IDLE

    @Volatile
    private var mPhaseUntil = 0L

    private var mInfo: TvInfo? = null
    private val mDetection = CameraFrameDetectionRun()
    private val mDarkGrids = ArrayList<IntArray>()
    private var mRegions: GlowRegions? = null
    private var mCorners = FloatArray(8)
    private var mSpill = 0f
    private var mDarkGlow = 0f
    private val mEstimator = DelayEstimator()
    private var mMeasureStart = 0L
    private var mLitGlowSum = 0.0
    private var mLitGlowCount = 0
    private var mLastReport = 0L

    val gridCols: Int get() = mDetection.cols
    val gridRows: Int get() = mDetection.rows

    fun start() {
        mPhase = Phase.STARTING
        post(State.Starting)
        mRpc.execute {
            try {
                val reply = call(ScreenGrabberService.CALIBRATION_BEGIN)
                mInfo = TvInfo(
                    app = reply.optString("app").takeIf { !reply.isNull("app") && it.isNotEmpty() },
                    label = reply.optString("label").takeIf { !reply.isNull("label") && it.isNotEmpty() },
                    delayMs = reply.optInt("delay"),
                    usageAccess = reply.optBoolean("usageAccess")
                )
                call(ScreenGrabberService.CALIBRATION_DARK, DARK_MS)
                // Пока лента гаснет и доходит команда, первые кадры ещё со свечением
                mPhaseUntil = SystemClock.elapsedRealtime() + DARK_SETTLE_MS
                mPhase = Phase.DARK
                post(State.Searching)
            } catch (e: Exception) {
                // Телефон и ТВ на разных версиях, сеть или отказ ТВ: причину покажем как есть
                Log.w(TAG, "Calibration start failed: ${e.message}")
                fail(State.Failed(Reason.TV_REFUSED, e.message))
            }
        }
    }

    /** Кадр камеры: сетка яркости [cols] × [rows] в ориентации буфера и время снимка. */
    fun onFrame(grid: IntArray, timeNanos: Long) {
        val now = SystemClock.elapsedRealtime()
        when (mPhase) {
            Phase.DARK -> if (now >= mPhaseUntil) {
                mDetection.start(now)
                mDarkGrids.clear()
                mPhase = Phase.SEARCHING
            }

            Phase.SEARCHING -> search(grid, now)
            Phase.LOCKING -> if (now >= mPhaseUntil) {
                mEstimator.clear()
                mMeasureStart = now
                mPhase = Phase.MEASURING
            }

            Phase.MEASURING -> measure(grid, timeNanos, now)
            else -> {}
        }
    }

    fun stop() {
        val active = mPhase != Phase.IDLE && mPhase != Phase.FINISHED
        mPhase = Phase.FINISHED
        if (active) endOnTv()
        mRpc.shutdown()
    }

    private fun search(grid: IntArray, now: Long) {
        mDarkGrids += grid.copyOf()
        val detection = mDetection.addFrame(grid, now) ?: return
        val corners = detection.corners
        if (corners == null) {
            val reason = if (detection.code == CameraFrameDetector.Code.TOO_DARK) Reason.TOO_DARK else Reason.NO_SCREEN
            fail(State.Failed(reason))
            return
        }
        val regions = GlowRegions(gridCols, gridRows, corners)
        if (!regions.usable) {
            fail(State.Failed(Reason.TOO_CLOSE))
            return
        }
        mRegions = regions
        mCorners = corners
        val screen = FloatArray(mDarkGrids.size) { regions.screenMean(mDarkGrids[it]) }
        val glow = FloatArray(mDarkGrids.size) { regions.glowMean(mDarkGrids[it]) }
        mSpill = DelayEstimator.spill(screen, glow)
        mDarkGlow = glow.average().toFloat()
        mDarkGrids.clear()
        Log.i(TAG, "Screen found, spill=$mSpill, dark glow=$mDarkGlow")

        val cx = (corners[0] + corners[2] + corners[4] + corners[6]) / 4
        val cy = (corners[1] + corners[3] + corners[5] + corners[7]) / 4
        mMain.post { onLockExposure(cx, cy) }
        mPhaseUntil = now + LOCK_SETTLE_MS
        mPhase = Phase.LOCKING
        post(State.Measuring(corners, 0, 0, null))
    }

    private fun measure(grid: IntArray, timeNanos: Long, now: Long) {
        val regions = mRegions ?: return
        val glow = regions.glowMean(grid)
        mEstimator.add(timeNanos, regions.screenMean(grid), glow)
        val elapsed = now - mMeasureStart
        if (elapsed < VISIBILITY_WINDOW_MS) {
            mLitGlowSum += glow
            mLitGlowCount++
        }
        if (now - mLastReport < REPORT_INTERVAL_MS) return
        mLastReport = now
        val events = mEstimator.events()
        val done = (events >= TARGET_EVENTS && elapsed >= MIN_MEASURE_MS) || elapsed >= MAX_MEASURE_MS
        if (!done) {
            post(State.Measuring(mCorners, events, (elapsed / 1000).toInt(), glowVisible(elapsed)))
            return
        }
        finish(events)
    }

    private fun finish(events: Int) {
        val info = mInfo
        val result = mEstimator.estimate(mSpill)
        if (info == null || result == null || events < MIN_EVENTS) {
            fail(State.Failed(Reason.NOT_ENOUGH_CHANGES))
            return
        }
        val reliable = result.correlation >= MIN_CORRELATION && result.spreadMs <= MAX_SPREAD_MS
        Log.i(TAG, "Lag ${result.lagMs} ms, r=${result.correlation}, spread=${result.spreadMs}, events=$events")
        mPhase = Phase.FINISHED
        endOnTv()
        post(State.Done(result, info, reliable))
    }

    private fun glowVisible(elapsed: Long): Boolean? {
        if (elapsed < VISIBILITY_WINDOW_MS || mLitGlowCount == 0) return null
        val lit = (mLitGlowSum / mLitGlowCount).toFloat()
        return lit - mDarkGlow >= MIN_GLOW_LUMA || lit >= mDarkGlow * MIN_GLOW_RATIO
    }

    private fun fail(state: State.Failed) {
        mPhase = Phase.FINISHED
        endOnTv()
        post(state)
    }

    private fun endOnTv() {
        try {
            mRpc.execute {
                try {
                    call(ScreenGrabberService.CALIBRATION_END)
                } catch (e: Exception) {
                    // ТВ снимет режим замера сам: по таймауту или когда телефон отключится
                    Log.w(TAG, "Calibration end failed: ${e.message}")
                }
            }
        } catch (_: RejectedExecutionException) {
            // Экран уже закрыт, и stop() сам отправил завершение.
        }
    }

    private fun call(action: String, darkMs: Long = 0L): JSONObject =
        RemoteSession.call(
            RemoteProtocol.OP_CALIBRATION,
            JSONObject().put("a", action).put("ms", darkMs)
        )

    private fun post(state: State) {
        mMain.post { onState(state) }
    }

    companion object {
        private const val TAG = "CalibrationSession"

        /** Хватает на окно детектора и на то, чтобы лента успела погаснуть. */
        private const val DARK_MS = 5000L
        private const val DARK_SETTLE_MS = 400L
        private const val LOCK_SETTLE_MS = 1200L

        private const val VISIBILITY_WINDOW_MS = 3000L
        private const val REPORT_INTERVAL_MS = 500L
        private const val MIN_MEASURE_MS = 20_000L
        private const val MAX_MEASURE_MS = 60_000L
        private const val TARGET_EVENTS = 10
        private const val MIN_EVENTS = 4

        private const val MIN_CORRELATION = 0.35f
        private const val MAX_SPREAD_MS = 60

        private const val MIN_GLOW_LUMA = 3f
        private const val MIN_GLOW_RATIO = 1.25f
    }
}
