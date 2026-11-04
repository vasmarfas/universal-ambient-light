package com.vasmarfas.UniversalAmbientLight.common.network

/**
 * Отдаёт кадры всем контроллерам сразу. Энкодер знает только про один listener, поэтому
 * композиция подменяет его. Статус захвата сервис ждёт только от основного вывода.
 */
internal class OutputFanOut(
    private val mPrimary: HyperionThread.HyperionThreadListener,
    extras: List<HyperionThread.HyperionThreadListener>,
) : HyperionThread.HyperionThreadListener {

    private val mOutputs = listOf(mPrimary) + extras

    override fun sendFrame(data: ByteArray, width: Int, height: Int) {
        mOutputs.forEach { it.sendFrame(data, width, height) }
    }

    override fun clear() {
        mOutputs.forEach { it.clear() }
    }

    override fun disconnect() {
        mOutputs.forEach { it.disconnect() }
    }

    override fun sendStatus(isGrabbing: Boolean) {
        mPrimary.sendStatus(isGrabbing)
    }
}
