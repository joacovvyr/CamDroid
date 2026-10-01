package com.joacovvyr.camdroid

import android.annotation.SuppressLint
import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.joacovvyr.camdroid.effects.LocalPortraitEngine
import com.joacovvyr.camdroid.effects.HighResolutionPortraitProcessor
import com.joacovvyr.camdroid.effects.PortraitRenderer
import com.joacovvyr.camdroid.effects.VirtualLensProfile
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCamera2Interop::class)
@SuppressLint("UnsafeOptInUsageError")
class PortraitActivity : AppCompatActivity() {
    private lateinit var renderer: PortraitRenderer
    private lateinit var status: TextView
    private lateinit var capture: Button
    private val worker = Executors.newSingleThreadExecutor()
    private val storage = Executors.newSingleThreadExecutor()
    private var engine: LocalPortraitEngine? = null
    private var engineSession = -1
    private var analyzer: ImageAnalysis? = null
    private var stillCapture: ImageCapture? = null
    private val photoProcessor = HighResolutionPortraitProcessor()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private val busy = AtomicBoolean(false)
    @Volatile private var active = false
    private var generation = 0
    private var front = true
    private var frameReady = false
    private var saving = false
    private var lastFrame = 0L
    private var focusTargetActive = false
    private var camera2Control: Camera2CameraControl? = null
    private var isoRange: Range<Int> = Range(50, 3200)
    private var exposureRange: Range<Long> = Range(250_000L, 1_000_000_000L)
    private var focusDistanceMax = 10f
    private var zoomMax = 10f
    private var analysisSize = Size(1280, 720)
    private val proState = CameraProState()
    private var proControls: LinearLayout? = null
    private lateinit var proToggle: Button
    private lateinit var zoomValue: TextView
    private lateinit var isoValue: TextView
    private lateinit var shutterValue: TextView
    private lateinit var focusValue: TextView
    private lateinit var exposureValue: TextView
    private lateinit var whiteBalance: Spinner
    private val shutterSpeeds = listOf(
        "1/4000" to 250_000L, "1/2000" to 500_000L, "1/1000" to 1_000_000L,
        "1/500" to 2_000_000L, "1/250" to 4_000_000L, "1/125" to 8_000_000L,
        "1/60" to 16_666_667L, "1/30" to 33_333_333L, "1/15" to 66_666_667L,
        "1/8" to 125_000_000L, "1/4" to 250_000_000L, "1/2" to 500_000_000L
    )

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int = 18): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun caption(value: String, size: Float = 12f) = TextView(this).apply {
        text = value; setTextColor(0xffaeb8c4.toInt()); textSize = size
    }
    private fun proButton(value: String, onClick: () -> Unit) = Button(this).apply {
        text = value; setTextColor(Color.WHITE); textSize = 12f
        background = rounded(0xff27313d.toInt(), 14); setPadding(dp(10), 0, dp(10), 0)
        setOnClickListener { onClick() }
    }
    private fun sliderRow(parent: LinearLayout, title: String, value: TextView, max: Int, progress: Int, onChange: (Int) -> Unit): SeekBar {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(4)) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(caption(title), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(value)
        row.addView(header)
        val slider = SeekBar(this).apply {
            this.max = max; this.progress = progress
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) onChange(progress) }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        row.addView(slider)
        parent.addView(row)
        return slider
    }

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) bindCamera() else status.text = "Se necesita permiso de cámara para el retrato IA."
    }

    private fun refreshCameraCapabilities(currentCamera: Camera) {
        try {
            val info = Camera2CameraInfo.from(currentCamera.cameraInfo)
            isoRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: isoRange
            exposureRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: exposureRange
            focusDistanceMax = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: focusDistanceMax
            zoomMax = info.getCameraCharacteristic(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: zoomMax
            camera2Control = Camera2CameraControl.from(currentCamera.cameraControl)
            runOnUiThread {
                if (::isoValue.isInitialized) isoValue.text = "AUTO · ISO ${isoRange.lower}–${isoRange.upper}"
                if (::zoomValue.isInitialized) zoomValue.text = "1.0× · máx ${"%.1f".format(zoomMax)}×"
            }
        } catch (_: Exception) {
            camera2Control = null
        }
    }

    private fun applyProCameraState() {
        val currentCamera = camera ?: return
        val interop = camera2Control ?: return
        val options = CaptureRequestOptions.Builder()
        if (proState.manualIso != null && proState.manualExposureTimeNs != null) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            options.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, proState.manualIso!!)
            options.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, proState.manualExposureTimeNs!!)
        } else {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }
        if (proState.manualFocusDistance != null && focusDistanceMax > 0f) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            options.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, proState.manualFocusDistance!!)
        } else {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }
        options.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, proState.whiteBalanceMode)
        proState.targetFps?.let { fps ->
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range<Int>(fps, fps))
        }
        interop.setCaptureRequestOptions(options.build())
        currentCamera.cameraControl.setZoomRatio(proState.zoomRatio.coerceIn(1f, zoomMax))
        currentCamera.cameraControl.setExposureCompensationIndex(proState.exposureCompensation)
        currentCamera.cameraControl.enableTorch(proState.torchEnabled)
    }

    private fun setAutoExposure() {
        proState.manualIso = null
        proState.manualExposureTimeNs = null
        isoValue.text = "AUTO"
        shutterValue.text = "AUTO"
        applyProCameraState()
    }

    private fun setAutoFocus() {
        proState.manualFocusDistance = null
        focusValue.text = "AF CONTINUO"
        applyProCameraState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        renderer = PortraitRenderer(this)
        renderer.onFocusTargetChanged = { _, _ ->
            focusTargetActive = true
            status.text = "Objetivo IA fijado · enfoque físico activo"
        }
        renderer.onPhysicalFocusPointChanged = { x, y ->
            val currentCamera = camera
            if (currentCamera != null && renderer.width > 0 && renderer.height > 0) {
                val factory = SurfaceOrientedMeteringPointFactory(renderer.width.toFloat(), renderer.height.toFloat())
                val point = factory.createPoint(x * renderer.width, y * renderer.height)
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(4, TimeUnit.SECONDS).build()
                currentCamera.cameraControl.startFocusAndMetering(action)
            }
        }
        status = TextView(this).apply { setTextColor(-1); text = "Preparando IA local…"; textSize = 15f }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(24)); setBackgroundColor(0xff15191f.toInt()) }
        val title = TextView(this).apply { text = "RETRATO IA · PRO · Sin conexión"; textSize = 20f; setTextColor(-1) }
        val label = TextView(this).apply { text = "Desenfoque: 65%"; setTextColor(-1) }
        val lensLabel = caption("Objetivo virtual", 13f)
        val lensSelector = Spinner(this).apply {
            adapter = ArrayAdapter(this@PortraitActivity, android.R.layout.simple_spinner_item, VirtualLensProfile.values().map { it.label }).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { renderer.lensProfile = VirtualLensProfile.values()[position]; renderer.requestRender() }
            }
        }
        val strength = SeekBar(this).apply {
            max = 100; progress = 65
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, user: Boolean) { renderer.intensity = value / 100f; label.text = "Desenfoque: ${value}%"; renderer.requestRender() }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        val qualityPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = rounded(0xff1a222c.toInt(), 18)
        }
        lateinit var qualityToggle: Button
        qualityToggle = proButton("MEJORA IA · SUAVE") {
            renderer.qualityEnhancementEnabled = !renderer.qualityEnhancementEnabled
            qualityToggle.text = if (renderer.qualityEnhancementEnabled) "MEJORA IA · SUAVE" else "MEJORA IA · DESACTIVADA"
            renderer.requestRender()
        }
        qualityPanel.addView(qualityToggle, LinearLayout.LayoutParams(-1, dp(42)))
        val qualityValue = caption("25%")
        sliderRow(qualityPanel, "Ajuste de vista · no modifica foto", qualityValue, 100, 25) { value ->
            renderer.qualityEnhancement = value / 100f
            qualityValue.text = "${value}%"
            renderer.requestRender()
        }
        val mask = Switch(this).apply {
            text = "Ver máscara IA real"; setTextColor(-1)
            setOnCheckedChangeListener { _, checked -> renderer.maskOnly = checked; renderer.requestRender(); capture.isEnabled = frameReady && stillCapture != null && !checked && !saving }
        }
        capture = Button(this).apply {
            text = "GUARDAR FOTO IA"; isEnabled = false
            setOnClickListener { if (!frameReady || renderer.maskOnly || stillCapture == null) return@setOnClickListener; saving = true; isEnabled = false; takeHighResolutionPhoto() }
        }
        val flip = Button(this).apply {
            text = "CAMBIAR CÁMARA"
            setOnClickListener { front = !front; focusTargetActive = false; renderer.clearFocusTarget(); bindCamera() }
        }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER; addView(capture, LinearLayout.LayoutParams(0,-2,1f)); addView(flip, LinearLayout.LayoutParams(0,-2,1f)) }
        proToggle = proButton("CONTROLES PRO · MOSTRAR") {
            proControls?.let { panel -> panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE; proToggle.text = if (panel.visibility == View.VISIBLE) "CONTROLES PRO · OCULTAR" else "CONTROLES PRO · MOSTRAR" }
        }.apply { minimumHeight = dp(46) }
        val proPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(12)); background = rounded(0xff1a222c.toInt(), 18); visibility = View.GONE }
        proControls = proPanel
        val proActions = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(proButton("AE AUTO") { setAutoExposure() }, LinearLayout.LayoutParams(0, dp(42), 1f))
            addView(proButton("AF AUTO") { setAutoFocus() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(6) })
            lateinit var torch: Button
            torch = proButton("LINTERNA") { proState.torchEnabled = !proState.torchEnabled; torch.text = if (proState.torchEnabled) "LINTERNA ON" else "LINTERNA"; applyProCameraState() }
            addView(torch, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(6) })
        }
        proPanel.addView(caption("CONTROL MANUAL · Camera2", 13f)); proPanel.addView(proActions)
        zoomValue = caption("1.0×")
        sliderRow(proPanel, "Zoom óptico/digital", zoomValue, 100, 0) { progress -> proState.zoomRatio = 1f + (zoomMax - 1f) * progress / 100f; zoomValue.text = "%.1f×".format(proState.zoomRatio); applyProCameraState() }
        isoValue = caption("AUTO")
        sliderRow(proPanel, "ISO", isoValue, 100, 0) { progress -> val value = isoRange.lower + ((isoRange.upper - isoRange.lower) * progress / 100f).toInt(); proState.manualIso = value; if (proState.manualExposureTimeNs == null) proState.manualExposureTimeNs = 16_666_667L; isoValue.text = "ISO ${value}"; applyProCameraState() }
        shutterValue = caption("AUTO")
        sliderRow(proPanel, "Obturación", shutterValue, shutterSpeeds.lastIndex, 6) { progress -> val selected = shutterSpeeds[progress.coerceIn(0, shutterSpeeds.lastIndex)]; proState.manualExposureTimeNs = selected.second.coerceIn(exposureRange.lower, exposureRange.upper); if (proState.manualIso == null) proState.manualIso = 100; shutterValue.text = selected.first; applyProCameraState() }
        focusValue = caption("AF CONTINUO")
        sliderRow(proPanel, "Enfoque manual · infinito → cerca", focusValue, 100, 0) { progress -> proState.manualFocusDistance = focusDistanceMax * progress / 100f; focusValue.text = "%.2f D".format(proState.manualFocusDistance); applyProCameraState() }
        exposureValue = caption("0 EV")
        sliderRow(proPanel, "Compensación de exposición", exposureValue, 60, 30) { progress -> proState.exposureCompensation = (progress - 30) / 10; exposureValue.text = if (proState.exposureCompensation == 0) "0 EV" else "%+.1f EV".format(proState.exposureCompensation / 10f); applyProCameraState() }
        val wbRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        wbRow.addView(caption("Balance de blancos"), LinearLayout.LayoutParams(0, -2, 1f))
        whiteBalance = Spinner(this).apply {
            adapter = ArrayAdapter(this@PortraitActivity, android.R.layout.simple_spinner_dropdown_item, WhiteBalancePreset.values().map { it.label })
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { proState.whiteBalanceMode = WhiteBalancePreset.values()[position].cameraMode; applyProCameraState() }
            }
        }
        wbRow.addView(whiteBalance, LinearLayout.LayoutParams(dp(150), dp(44))); proPanel.addView(wbRow)
        val fpsRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
        fpsRow.addView(caption("FPS de captura"), LinearLayout.LayoutParams(0, -2, 1f))
        val fpsSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@PortraitActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Auto", "24 FPS", "30 FPS", "60 FPS"))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { proState.targetFps = listOf(null, 24, 30, 60)[position]; applyProCameraState() }
            }
        }
        fpsRow.addView(fpsSpinner, LinearLayout.LayoutParams(dp(150), dp(44))); proPanel.addView(fpsRow)
        val resolutionRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(8)) }
        resolutionRow.addView(caption("Resolución IA"), LinearLayout.LayoutParams(0, -2, 1f))
        val resolutions = listOf(Size(640, 480), Size(1280, 720), Size(1920, 1080))
        val resolutionSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@PortraitActivity, android.R.layout.simple_spinner_dropdown_item, resolutions.map { "${it.width}×${it.height}" })
            setSelection(1)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { if (analysisSize != resolutions[position]) { analysisSize = resolutions[position]; if (active) bindCamera() } }
            }
        }
        resolutionRow.addView(resolutionSpinner, LinearLayout.LayoutParams(dp(150), dp(44))); proPanel.addView(resolutionRow)
        controls.addView(title); controls.addView(status); controls.addView(lensLabel); controls.addView(lensSelector); controls.addView(label); controls.addView(strength)
        controls.addView(qualityPanel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        controls.addView(mask); controls.addView(row)
        controls.addView(proToggle, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) }); controls.addView(proPanel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        controls.addView(TextView(this).apply { text = "Toque: objetivo IA · deslizar: controles pro · S22 optimizado"; setTextColor(0xffb7c1cc.toInt()); textSize = 12f })
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0xff0d1117.toInt()); addView(renderer, LinearLayout.LayoutParams(-1,0,1f))
            addView(ScrollView(this@PortraitActivity).apply { setBackgroundColor(0xff121820.toInt()); addView(controls) }, LinearLayout.LayoutParams(-1, dp(340)))
        })
    }
    override fun onResume() {
        super.onResume(); active = true; renderer.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) bindCamera() else permission.launch(Manifest.permission.CAMERA)
    }
    private fun bindCamera() {
        if (!active) return
        val session = ++generation; frameReady = false; capture.isEnabled = false; stillCapture = null; status.text = "Iniciando segmentación local…"; analyzer?.clearAnalyzer()
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!active || session != generation) return@addListener
            try {
                val cameraProvider = future.get(); provider = cameraProvider
                var selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                if (!cameraProvider.hasCamera(selector)) { front = !front; selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA }
                cameraProvider.unbindAll(); camera = null; val mirror = front
                val analysis = ImageAnalysis.Builder().setTargetResolution(analysisSize).setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analyzer = analysis
                analysis.setAnalyzer(worker) { proxy ->
                    val shouldInfer = busy.compareAndSet(false,true)
                    if (!active || session != generation) { if (shouldInfer) busy.set(false); proxy.close(); return@setAnalyzer }
                    if (!shouldInfer) { proxy.close(); return@setAnalyzer }
                    val started = SystemClock.elapsedRealtime()
                    try {
                        val source = proxy.toBitmap(); val transform = Matrix().apply { postRotate(proxy.imageInfo.rotationDegrees.toFloat()); if (mirror) postScale(-1f,1f) }
                        val upright = Bitmap.createBitmap(source,0,0,source.width,source.height,transform,true); if (upright !== source) source.recycle(); proxy.close()
                        if (engineSession != session) { engine?.close(); engine = LocalPortraitEngine(); engineSession = session }
                        val localEngine = checkNotNull(engine)
                        localEngine.process(upright, ContextCompat.getMainExecutor(this)) { mask, error ->
                            busy.set(false)
                            if (!active || session != generation || error != null || mask == null) {
                                upright.recycle(); mask?.recycle(); if (active && session == generation && error != null) status.text = "Falló la IA local: ${error.localizedMessage}"
                            } else {
                                val dimensions = "${upright.width}×${upright.height}"; renderer.submit(upright,mask); val now = SystemClock.elapsedRealtime(); val fps = if (lastFrame == 0L) 0 else (1000L/(now-lastFrame).coerceAtLeast(1)).toInt(); lastFrame = now; frameReady = true; capture.isEnabled = stillCapture != null && !renderer.maskOnly && !saving; val focusLabel = if (focusTargetActive) " · objetivo fijado" else ""; status.text = "IA local · ${now-started} ms · ${fps} FPS · ${dimensions}${focusLabel}"
                            }
                        }
                    } catch (error: Exception) { proxy.close(); if (shouldInfer) busy.set(false); runOnUiThread { if (active) status.text = "Error de procesamiento: ${error.localizedMessage}" } }
                }
                val still = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setJpegQuality(98)
                    .build()
                camera = cameraProvider.bindToLifecycle(this,selector,analysis,still)
                stillCapture = still
                refreshCameraCapabilities(checkNotNull(camera)); applyProCameraState()
            } catch (error: Exception) { status.text = "No se pudo abrir la cámara: ${error.localizedMessage}" }
        }, ContextCompat.getMainExecutor(this))
    }
    private fun takeHighResolutionPhoto() {
        val still = stillCapture ?: return
        val mirror = front
        val blur = renderer.intensity
        status.text = "Capturando foto de alta resolución…"
        still.takePicture(storage, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val plane = image.planes.firstOrNull() ?: error("Foto vacía")
                    val bytes = ByteArray(plane.buffer.remaining())
                    plane.buffer.get(bytes)
                    val rotation = image.imageInfo.rotationDegrees
                    image.close()
                    photoProcessor.process(bytes, rotation, mirror, blur, storage) { photo, error ->
                        if (photo == null || error != null) {
                            notifySaved("No se pudo procesar la foto: ${error?.localizedMessage}")
                        } else save(photo)
                    }
                } catch (error: Exception) {
                    image.close()
                    notifySaved("No se pudo leer la foto: ${error.localizedMessage}")
                }
            }
            override fun onError(exception: ImageCaptureException) {
                notifySaved("No se pudo capturar la foto: ${exception.localizedMessage}")
            }
        })
    }
    private fun save(bitmap: Bitmap) {
        if (storage.isShutdown) { bitmap.recycle(); return }
        storage.execute {
            try {
                val name = "CamDroid_IA_${System.currentTimeMillis()}.jpg"
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME,name); put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg"); put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/CamDroid"); put(MediaStore.Images.Media.IS_PENDING,1) }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values) ?: error("No se pudo crear la foto")
                    try { contentResolver.openOutputStream(uri)?.use { check(bitmap.compress(Bitmap.CompressFormat.JPEG,95,it)) } ?: error("No se pudo abrir la foto"); contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING,0) },null,null) } catch (error: Exception) { contentResolver.delete(uri,null,null); throw error }
                    notifySaved("Foto IA guardada en Pictures/CamDroid")
                } else { val dir = getExternalFilesDir("pictures") ?: filesDir; File(dir,name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG,95,it)) }; notifySaved("Foto IA guardada en la carpeta privada de CamDroid") }
            } catch (error: Exception) { notifySaved("No se pudo guardar: ${error.localizedMessage}") } finally { bitmap.recycle() }
        }
    }
    private fun notifySaved(message: String) = runOnUiThread { saving = false; if (active) { Toast.makeText(this,message,Toast.LENGTH_LONG).show(); capture.isEnabled = frameReady && stillCapture != null && !renderer.maskOnly } }
    override fun onPause() { active = false; generation++; saving = false; stillCapture = null; analyzer?.clearAnalyzer(); provider?.unbindAll(); camera = null; renderer.cancelCapture(); renderer.onPause(); renderer.discardPending(); super.onPause() }
    override fun onDestroy() { worker.execute { engine?.close() }; storage.execute { photoProcessor.close() }; worker.shutdown(); storage.shutdown(); super.onDestroy() }
}
