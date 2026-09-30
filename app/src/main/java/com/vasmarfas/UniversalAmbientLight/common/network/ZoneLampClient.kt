package com.vasmarfas.UniversalAmbientLight.common.network

import android.util.Log
import com.vasmarfas.UniversalAmbientLight.common.util.ZoneColorExtractor
import java.io.IOException

/**
 * Основа для умных ламп, привязанных к зонам экрана. Лампы - не адресная лента, поток
 * кадров им не по силам: цвет зоны уходит редко и только при заметной смене, ритм задаёт
 * та же политика, что у Home Assistant. Наследник только отправляет цвет или выключение
 * лампам своего протокола.
 */
abstract class ZoneLampClient(
    lampsSpec: String?,
    private val mSettings: LampSettings,
) : HyperionClient, StreamingClient {

    /** Поведение ламп из настроек; у всех экосистем одно и то же. */
    data class LampSettings(
        val updateIntervalMs: Long,
        val changeThreshold: Int,
        val transitionMs: Int,
        val brightnessMode: String,
        /** 1..255 - потолок яркости, когда она следует за картинкой. */
        val brightnessMax: Int,
        val darkOffEnabled: Boolean,
        val darkThreshold: Int,
        val turnOffLights: Boolean,
    )

    protected val mLampsByZone: Map<HomeAssistantZone, List<HomeAssistantLamp>> =
        HomeAssistantLamp.parseList(lampsSpec).groupBy { it.zone }

    private val mPolicy = HomeAssistantUpdatePolicy(
        mSettings.updateIntervalMs,
        mSettings.changeThreshold,
        mSettings.darkOffEnabled,
        mSettings.darkThreshold
    )
    private val mZoneColors = IntArray(ZoneColorExtractor.ZONE_COUNT * 3)

    @Volatile
    private var mConnected = false

    @Volatile
    private var mPaused = false

    // Лампы уже погашены нами: гашение идёт серией кадров, а лампам хватает одной команды
    @Volatile
    private var mLightsOff = false

    private var mFailures = 0

    protected val transitionMs: Int get() = mSettings.transitionMs

    /** Проверяет связь с лампами; зовёт наследник в конце своего конструктора. */
    @Throws(IOException::class)
    protected fun start() {
        if (mLampsByZone.isEmpty()) throw IOException("No lights are assigned to zones")
        open()
        mConnected = true
    }

    @Throws(IOException::class)
    protected abstract fun open()

    /**
     * Цвет лампам зоны. [brightness] 1..255 - яркость, когда она следует за картинкой,
     * иначе null: лампа держит свою, а цвет уходит как есть.
     */
    @Throws(IOException::class)
    protected abstract fun sendColor(lamps: List<HomeAssistantLamp>, r: Int, g: Int, b: Int, brightness: Int?)

    @Throws(IOException::class)
    protected abstract fun sendOff(lamps: List<HomeAssistantLamp>)

    protected open fun close() {}

    override fun isConnected(): Boolean = mConnected

    override fun pauseSending() {
        mPaused = true
    }

    override fun resumeSending() {
        mPaused = false
        // После паузы лампы могли переключать руками - первый кадр уходит заново
        mPolicy.reset()
    }

    @Throws(IOException::class)
    override fun disconnect() {
        if (mConnected && mSettings.turnOffLights && !mLightsOff) turnOffAll()
        mConnected = false
        close()
    }

    @Throws(IOException::class)
    override fun clear(priority: Int) {
        if (mSettings.turnOffLights && !mLightsOff) {
            turnOffAll()
            mLightsOff = true
            mPolicy.reset()
        }
    }

    @Throws(IOException::class)
    override fun clearAll() {
        clear(0)
    }

    @Throws(IOException::class)
    override fun setColor(color: Int, priority: Int) {
        setColor(color, priority, -1)
    }

    @Throws(IOException::class)
    override fun setColor(color: Int, priority: Int, duration_ms: Int) {
        if (!mConnected || mPaused) return
        send(mLampsByZone.values.flatten(), (color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF)
    }

    @Throws(IOException::class)
    override fun setImage(data: ByteArray, width: Int, height: Int, priority: Int) {
        setImage(data, width, height, priority, -1)
    }

    @Throws(IOException::class)
    override fun setImage(data: ByteArray, width: Int, height: Int, priority: Int, duration_ms: Int) {
        if (!mConnected || mPaused) return
        if (!ZoneColorExtractor.extract(data, width, height, mZoneColors)) return
        val updates = mPolicy.plan(System.currentTimeMillis(), mZoneColors, mLampsByZone.keys)
        for (update in updates) {
            val lamps = mLampsByZone[update.zone] ?: continue
            if (update.turnOff) {
                guarded { sendOff(lamps) }
            } else {
                send(lamps, update.red, update.green, update.blue)
            }
        }
    }

    private fun send(lamps: List<HomeAssistantLamp>, r: Int, g: Int, b: Int) {
        mLightsOff = false
        if (mSettings.brightnessMode != HomeAssistantClient.BRIGHTNESS_MODE_SCREEN) {
            guarded { sendColor(lamps, r, g, b, null) }
            return
        }
        // Яркость лампы следует за зоной, а цвет вытягивается до чистого тона: тёмно-красный
        // - это красный на малой яркости, а не тусклый цвет на полной
        val luma = maxOf(r, g, b)
        val brightness = (luma * mSettings.brightnessMax / 255).coerceAtLeast(1)
        if (luma == 0) {
            guarded { sendColor(lamps, 0, 0, 0, brightness) }
        } else {
            guarded { sendColor(lamps, r * 255 / luma, g * 255 / luma, b * 255 / luma, brightness) }
        }
    }

    private fun turnOffAll() {
        guarded { sendOff(mLampsByZone.values.flatten()) }
    }

    /**
     * Один сбой - не повод хоронить соединение: лампа могла на миг выпасть из Wi-Fi.
     * После нескольких подряд клиент объявляется отключённым, и поток вывода пересоздаёт его.
     */
    private fun guarded(block: () -> Unit) {
        try {
            block()
            mFailures = 0
        } catch (e: IOException) {
            mFailures++
            Log.w(TAG, "${javaClass.simpleName} command failed ($mFailures/$MAX_FAILURES): ${e.message}")
            if (mFailures >= MAX_FAILURES) mConnected = false
        }
    }

    companion object {
        private const val TAG = "ZoneLampClient"
        private const val MAX_FAILURES = 5
    }
}
