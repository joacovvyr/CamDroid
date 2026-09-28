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
