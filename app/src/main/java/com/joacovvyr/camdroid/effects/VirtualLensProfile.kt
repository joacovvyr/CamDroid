package com.joacovvyr.camdroid.effects

/**
 * Optical profiles applied after the local segmentation stage.
 *
 * These are virtual focal-length looks, not a replacement for a physical
 * camera module. The crop is deliberately conservative so the preview does
 * not invent detail before the future temporal upscaler is added.
 */
enum class VirtualLensProfile(
    val label: String,
    val cropScale: Float,
    val blurScale: Float
) {
    NATURAL("Natural · 35 mm", 1.0f, 1.0f),
    PORTRAIT_50("Retrato · 50 mm", 1.12f, 1.08f),
    PORTRAIT_85("Retrato pro · 85 mm", 1.42f, 1.22f),
    TELE_2X("Tele · 2×", 1.90f, 1.35f)
}
