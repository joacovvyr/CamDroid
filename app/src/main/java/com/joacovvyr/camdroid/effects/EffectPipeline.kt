package com.joacovvyr.camdroid.effects

/** Local processing boundary. GPU shaders and LiteRT models can be added without changing camera UI. */
class EffectPipeline {
    var backgroundBlurEnabled = false
    var blurIntensity = 0.5f
        private set
    fun setBlurIntensity(value: Float) { blurIntensity = value.coerceIn(0f, 1f) }
}
