package com.joacovvyr.camdroid

import android.hardware.camera2.CaptureRequest

/** User-editable controls for the pro camera surface. Null means automatic. */
data class CameraProState(
    var manualIso: Int? = null,
    var manualExposureTimeNs: Long? = null,
    var manualFocusDistance: Float? = null,
    var whiteBalanceMode: Int = CaptureRequest.CONTROL_AWB_MODE_AUTO,
    var targetFps: Int? = null,
    var zoomRatio: Float = 1f,
    var exposureCompensation: Int = 0,
    var torchEnabled: Boolean = false
)

enum class WhiteBalancePreset(val label: String, val cameraMode: Int) {
    AUTO("Auto", CaptureRequest.CONTROL_AWB_MODE_AUTO),
    DAYLIGHT("Día", CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT),
    CLOUDY("Nublado", CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
    TUNGSTEN("Tungsteno", CaptureRequest.CONTROL_AWB_MODE_TUNGSTEN),
    FLUORESCENT("Fluorescente", CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT),
    SHADE("Sombra", CaptureRequest.CONTROL_AWB_MODE_SHADE)
}
