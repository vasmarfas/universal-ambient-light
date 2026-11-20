package com.vasmarfas.UniversalAmbientLight.common

/**
 * Источник уровней полос для цветомузыки: [MicrophoneSpectrum] или [OutputSpectrum].
 * [EffectEncoder] забирает уровни на каждом кадре и останавливает источник вместе с эффектом.
 */
interface SoundSpectrum {
    /** 0..100, см. AudioSpectrum.sensitivity. */
    var sensitivity: Int

    /** False, если звука из этого источника на устройстве не получить. */
    fun start(): Boolean

    fun stop()

    fun levels(out: FloatArray)
}
