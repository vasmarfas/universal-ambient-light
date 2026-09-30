package com.vasmarfas.UniversalAmbientLight.common.network

/**
 * Клиент, который сам держит поток на контроллер. Его приглушают на время сна ТВ, а у тех,
 * что сглаживают кадры сами, задержку и сглаживание меняют на ходу, без переподключения.
 */
interface StreamingClient {
    fun pauseSending()

    fun resumeSending()

    fun setOutputDelay(ms: Long) {}

    fun setSmoothingEnabled(enabled: Boolean) {}

    /** Клиент задерживает вывод сам, в [setOutputDelay]; остальным кадры задерживает HyperionThread. */
    val delaysOutput: Boolean
        get() = false
}
