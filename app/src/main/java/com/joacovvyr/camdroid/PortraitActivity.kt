package com.joacovvyr.camdroid

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.joacovvyr.camdroid.effects.LocalPortraitEngine
import com.joacovvyr.camdroid.effects.PortraitRenderer
import com.joacovvyr.camdroid.effects.VirtualLensProfile
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit

class PortraitActivity : AppCompatActivity() {
    private lateinit var renderer: PortraitRenderer
    private lateinit var status: TextView
    private lateinit var capture: Button
    private val worker = Executors.newSingleThreadExecutor()
    private val storage = Executors.newSingleThreadExecutor()
    private var engine: LocalPortraitEngine? = null
    private var engineSession = -1
    private var analyzer: ImageAnalysis? = null
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

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) bindCamera() else status.text = "Se necesita permiso de cámara para el retrato IA."
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
                val factory = SurfaceOrientedMeteringPointFactory(
                    renderer.width.toFloat(), renderer.height.toFloat()
                )
                val point = factory.createPoint(x * renderer.width, y * renderer.height)
                val action = FocusMeteringAction.Builder(
                    point,
                    FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                ).setAutoCancelDuration(4, TimeUnit.SECONDS).build()
                currentCamera.cameraControl.startFocusAndMetering(action)
            }
        }
        status = TextView(this).apply {
            setTextColor(-1); text = "Preparando IA local…"; textSize = 15f
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 36); setBackgroundColor(0xff15191f.toInt())
        }
        val title = TextView(this).apply {
            text = "RETRATO IA · Sin conexión"; textSize = 20f; setTextColor(-1)
        }
        val label = TextView(this).apply { text = "Desenfoque: 65%"; setTextColor(-1) }
        val lensLabel = TextView(this).apply {
            text = "Objetivo virtual"
            setTextColor(-1)
        }
        val lensSelector = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@PortraitActivity,
                android.R.layout.simple_spinner_item,
                VirtualLensProfile.values().map { it.label }
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    renderer.lensProfile = VirtualLensProfile.values()[position]
                    renderer.requestRender()
                }
            }
        }
        val strength = SeekBar(this).apply {
            max = 100; progress = 65
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, user: Boolean) {
                    renderer.intensity = value / 100f
                    label.text = "Desenfoque: \${value}%"
                    renderer.requestRender()
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }
        val mask = Switch(this).apply {
            text = "Ver máscara de persona"; setTextColor(-1)
            setOnCheckedChangeListener { _, checked ->
                renderer.maskOnly = checked; renderer.requestRender()
                capture.isEnabled = frameReady && !checked && !saving
            }
        }
        capture = Button(this).apply {
            text = "Guardar foto IA"; isEnabled = false
            setOnClickListener {
                if (!frameReady || renderer.maskOnly) return@setOnClickListener
                saving = true
                isEnabled = false
                renderer.capture { bitmap -> save(bitmap) }
            }
        }
        val flip = Button(this).apply {
            text = "Cambiar cámara"
            setOnClickListener {
                front = !front
                focusTargetActive = false
                renderer.clearFocusTarget()
                bindCamera()
            }
        }
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(capture, LinearLayout.LayoutParams(0,-2,1f))
            addView(flip, LinearLayout.LayoutParams(0,-2,1f))
        }
        controls.addView(title); controls.addView(status); controls.addView(lensLabel); controls.addView(lensSelector); controls.addView(label)
        controls.addView(strength); controls.addView(mask); controls.addView(row)
        controls.addView(TextView(this).apply {
            text = "Tocá el objeto que querés priorizar · Video IA todavía no disponible"
            setTextColor(0xffb7c1cc.toInt()); textSize = 12f
        })
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(renderer, LinearLayout.LayoutParams(-1,0,1f)); addView(controls)
        })
    }
    override fun onResume() {
        super.onResume()
        active = true; renderer.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            bindCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }
    private fun bindCamera() {
        if (!active) return
        val session = ++generation
        frameReady = false; capture.isEnabled = false
        status.text = "Iniciando segmentación local…"
        analyzer?.clearAnalyzer()
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!active || session != generation) return@addListener
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                var selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                if (!cameraProvider.hasCamera(selector)) {
                    front = !front
                    selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                }
                cameraProvider.unbindAll()
                camera = null
                val mirror = front
                // A single analysis use case avoids unsupported preview/photo/video/analysis combinations.
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640,480))
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analyzer = analysis
                analysis.setAnalyzer(worker) { proxy ->
                    if (!active || session != generation || !busy.compareAndSet(false,true)) {
                        proxy.close(); return@setAnalyzer
                    }
                    val started = SystemClock.elapsedRealtime()
                    try {
                        val source = proxy.toBitmap()
                        val transform = Matrix().apply {
                            postRotate(proxy.imageInfo.rotationDegrees.toFloat())
                            if (mirror) postScale(-1f,1f)
                        }
                        val upright = Bitmap.createBitmap(source,0,0,source.width,source.height,transform,true)
                        if (upright !== source) source.recycle()
                        // Camera buffer can be returned immediately: inference owns a bitmap copy.
                        proxy.close()
                        if (engineSession != session) {
                            engine?.close()
                            engine = LocalPortraitEngine()
                            engineSession = session
                        }
                        val localEngine = checkNotNull(engine)
                        localEngine.process(upright, ContextCompat.getMainExecutor(this)) { mask, error ->
                            busy.set(false)
                            if (!active || session != generation || error != null || mask == null) {
                                upright.recycle(); mask?.recycle()
                                if (active && session == generation && error != null) {
                                    status.text = "Falló la IA local: \${error.localizedMessage}"
                                }
                            } else {
                                val dimensions = "\${upright.width}×\${upright.height}"
                                renderer.submit(upright,mask)
                                val now = SystemClock.elapsedRealtime()
                                val fps = if (lastFrame == 0L) 0 else (1000L/(now-lastFrame).coerceAtLeast(1)).toInt()
                                lastFrame = now
                                frameReady = true
                                capture.isEnabled = !renderer.maskOnly && !saving
                                val focusLabel = if (focusTargetActive) " · objetivo fijado" else ""
                                status.text = "IA local · \${now-started} ms · \${fps} FPS · \${dimensions}\${focusLabel}"
                            }
                        }
                    } catch (error: Exception) {
                        proxy.close(); busy.set(false)
                        runOnUiThread { if (active) status.text = "Error de procesamiento: \${error.localizedMessage}" }
                    }
                }
                camera = cameraProvider.bindToLifecycle(this,selector,analysis)
            } catch (error: Exception) { status.text = "No se pudo abrir la cámara: \${error.localizedMessage}" }
        }, ContextCompat.getMainExecutor(this))
    }
    private fun save(bitmap: Bitmap) {
        if (storage.isShutdown) { bitmap.recycle(); return }
        storage.execute {
            try {
                val name = "CamDroid_IA_\${System.currentTimeMillis()}.jpg"
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME,name)
                        put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/CamDroid")
                        put(MediaStore.Images.Media.IS_PENDING,1)
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)
                        ?: error("No se pudo crear la foto")
                    try {
                        contentResolver.openOutputStream(uri)?.use {
                            check(bitmap.compress(Bitmap.CompressFormat.JPEG,95,it))
                        } ?: error("No se pudo abrir la foto")
                        contentResolver.update(uri, ContentValues().apply {
                            put(MediaStore.Images.Media.IS_PENDING,0)
                        },null,null)
                    } catch (error: Exception) {
                        contentResolver.delete(uri,null,null); throw error
                    }
                    notifySaved("Foto IA guardada en Pictures/CamDroid")
                } else {
                    val dir = getExternalFilesDir("pictures") ?: filesDir
                    File(dir,name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG,95,it)) }
                    notifySaved("Foto IA guardada en la carpeta privada de CamDroid")
                }
            } catch (error: Exception) { notifySaved("No se pudo guardar: \${error.localizedMessage}") }
            finally { bitmap.recycle() }
        }
    }
    private fun notifySaved(message: String) = runOnUiThread {
        saving = false
        if (active) {
            Toast.makeText(this,message,Toast.LENGTH_LONG).show()
            capture.isEnabled = frameReady && !renderer.maskOnly
        }
    }
    override fun onPause() {
        active = false; generation++
        saving = false
        analyzer?.clearAnalyzer(); provider?.unbindAll()
        camera = null
        renderer.cancelCapture(); renderer.onPause(); renderer.discardPending()
        super.onPause()
    }
    override fun onDestroy() {
        // Shutdown runs after the last analyzer invocation; its callback uses the main executor.
        worker.execute { engine?.close() }
        worker.shutdown(); storage.shutdown()
        super.onDestroy()
    }
}
