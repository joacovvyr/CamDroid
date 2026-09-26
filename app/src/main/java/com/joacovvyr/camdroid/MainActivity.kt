package com.joacovvyr.camdroid

import android.Manifest
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import com.joacovvyr.camdroid.camera.CameraController
import com.joacovvyr.camdroid.effects.EffectPipeline
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var controller: CameraController
    private val pipeline = EffectPipeline()
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startCamera() }

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); buildUi(); permission.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }

    private fun buildUi() {
        val preview = PreviewView(this).apply { id = View.generateViewId(); scaleType = PreviewView.ScaleType.FILL_CENTER }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 20); setBackgroundColor(0x99000000.toInt()) }
        val intensity = SeekBar(this).apply { max = 100; progress = 50; visibility = SeekBar.GONE; setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { pipeline.setBlurIntensity(p / 100f) }; override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) {} }) }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
        val photo = Button(this).apply { text = "FOTO"; setOnClickListener { controller.takePhoto({ Toast.makeText(context, "Foto guardada", Toast.LENGTH_SHORT).show() }, { Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show() }) } }
        val video = Button(this).apply { text = "VIDEO"; setOnClickListener { controller.toggleVideo { text = if (it) "DETENER" else "VIDEO" } } }
        val blur = Button(this).apply { text = "BLUR IA"; setOnClickListener { pipeline.backgroundBlurEnabled = !pipeline.backgroundBlurEnabled; intensity.visibility = if (pipeline.backgroundBlurEnabled) SeekBar.VISIBLE else SeekBar.GONE } }
        row.addView(photo); row.addView(video); row.addView(blur); controls.addView(intensity); controls.addView(row)
        val root = FrameLayout(this); root.addView(preview, FrameLayout.LayoutParams(-1, -1)); root.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)); setContentView(root)
        previewId = preview.id
    }

    private var previewId: Int = View.NO_ID
    private fun startCamera() { val preview = findViewById<PreviewView>(previewId); if (preview == null) return; controller = CameraController(this, executor); controller.bind(Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }, CameraSelector.DEFAULT_BACK_CAMERA) {} }
    override fun onDestroy() { executor.shutdown(); super.onDestroy() }
}
