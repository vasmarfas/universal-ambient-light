package com.vasmarfas.UniversalAmbientLight.common.network

/**
 * Протоколы, принимающие цвета отдельных физических LED без промежуточного изображения.
 */
interface AddressableLedClient {
    fun setLayoutTestEnabled(enabled: Boolean)
}
