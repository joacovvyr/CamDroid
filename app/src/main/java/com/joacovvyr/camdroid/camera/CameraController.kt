package com.joacovvyr.camdroid.camera

import android.content.Context
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.ExecutorService

class CameraController(private val context: Context, private val executor: ExecutorService) {
    var imageCapture: ImageCapture? = null
    var videoCapture: VideoCapture<Recorder>? = null
    var recording: Recording? = null

    fun bind(preview: Preview, selector: CameraSelector, onReady: () -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
            val provider = future.get()
            val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.FHD)).build()
            val video = VideoCapture.withOutput(recorder)
            provider.unbindAll()
            provider.bindToLifecycle(context as androidx.lifecycle.LifecycleOwner, selector, preview, capture, video)
            imageCapture = capture; videoCapture = video; onReady()
            } catch (error: Exception) {
                android.widget.Toast.makeText(context, "No se pudo iniciar la cámara: " + error.message, android.widget.Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun takePhoto(onSaved: (Uri) -> Unit, onError: (Throwable) -> Unit) {
        val capture = imageCapture ?: return
        val file = File(context.getExternalFilesDir("pictures"), "CamDroid_${System.currentTimeMillis()}.jpg")
        capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) = onSaved(Uri.fromFile(file))
            override fun onError(exception: ImageCaptureException) = onError(exception)
        })
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun toggleVideo(onState: (Boolean) -> Unit) {
        val capture = videoCapture ?: return
        recording?.let { it.stop(); recording = null; onState(false); return }
        val file = File(context.getExternalFilesDir("movies"), "CamDroid_${System.currentTimeMillis()}.mp4")
        val pending = capture.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) pending.withAudioEnabled()
        recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
                if (event is VideoRecordEvent.Start) onState(true)
                if (event is VideoRecordEvent.Finalize) {
                    recording = null
                    onState(false)
                    android.widget.Toast.makeText(context, if (event.hasError()) "Error de video: ${event.error}" else "Video guardado", android.widget.Toast.LENGTH_LONG).show()
                }
            }
    }
}
