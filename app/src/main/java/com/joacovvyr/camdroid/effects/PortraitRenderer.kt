package com.joacovvyr.camdroid.effects

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.view.MotionEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Frame and mask travel together so motion cannot pair a new frame with an old mask. */
class PortraitRenderer(context: Context) : GLSurfaceView(context), GLSurfaceView.Renderer {
    @Volatile var intensity = 0.65f
    @Volatile var maskOnly = false
    @Volatile var lensProfile = VirtualLensProfile.NATURAL
    @Volatile var qualityEnhancementEnabled = true
    @Volatile var qualityEnhancement = 0.25f
    @Volatile private var focusX = 0.5f
    @Volatile private var focusY = 0.5f
    @Volatile private var focusEnabled = false
    @Volatile var onFocusTargetChanged: ((Float, Float) -> Unit)? = null
    @Volatile var onPhysicalFocusPointChanged: ((Float, Float) -> Unit)? = null
    private var pendingFrame: Bitmap? = null
    private var pendingMask: Bitmap? = null
    private var program = 0
    private val textures = IntArray(2)
    private var frameWidth = 0
    private var frameHeight = 0
    private var screenWidth = 1
    private var screenHeight = 1
    private var maskAvailable = false
    private var positionLocation = -1
    private var cameraFrameLocation = -1
    private var personMaskLocation = -1
    private var texelLocation = -1
    private var strengthLocation = -1
    private var cropScaleLocation = -1
    private var blurScaleLocation = -1
    private var focusPointLocation = -1
    private var focusEnabledLocation = -1
    private var debugMaskLocation = -1
    private var qualityBoostLocation = -1
    private var qualityEnabledLocation = -1
    private var maskAvailableLocation = -1
    private var shot: ((Bitmap) -> Unit)? = null
    private val vertices = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, 1f,1f)); position(0) }

    init {
        setEGLContextClientVersion(2)
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
        preserveEGLContextOnPause = true
    }
    @Synchronized fun submit(frame: Bitmap, mask: Bitmap) {
        pendingMask?.recycle()
        pendingMask = mask
        if (pendingFrame == null) {
            pendingFrame = frame
        } else {
            frame.recycle()
        }
        requestRender()
    }
    @Synchronized fun submitFrame(frame: Bitmap) {
        pendingFrame?.recycle()
        pendingFrame = frame
        requestRender()
    }
    @Synchronized private fun takeFrame(): Pair<Bitmap?, Bitmap?>? {
        if (pendingFrame == null) return null
        val value = pendingFrame to pendingMask
        pendingFrame = null
        pendingMask = null
        return value
    }
    @Synchronized fun discardPending() {
        pendingFrame?.recycle()
        pendingMask?.recycle()
        pendingFrame = null
        pendingMask = null
    }
    fun capture(callback: (Bitmap) -> Unit) {
        queueEvent { if (frameWidth > 0) shot = callback }
        requestRender()
    }
    fun cancelCapture() { queueEvent { shot = null } }
    fun clearFocusTarget() {
        focusEnabled = false
        requestRender()
    }

    private fun setFocusTarget(x: Float, y: Float) {
        focusX = x.coerceIn(0f, 1f)
        focusY = y.coerceIn(0f, 1f)
        focusEnabled = true
        onFocusTargetChanged?.invoke(focusX, focusY)
        requestRender()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP && frameWidth > 0 && width > 0 && height > 0) {
            val scale = minOf(width.toFloat() / frameWidth, height.toFloat() / frameHeight)
            val imageWidth = frameWidth * scale
            val imageHeight = frameHeight * scale
            val left = (width - imageWidth) * 0.5f
            val top = (height - imageHeight) * 0.5f
            if (event.x in left..(left + imageWidth) && event.y in top..(top + imageHeight)) {
                setFocusTarget(
                    (event.x - left) / imageWidth,
                    (event.y - top) / imageHeight
                )
                onPhysicalFocusPointChanged?.invoke(
                    (event.x / width.toFloat()).coerceIn(0f, 1f),
                    (event.y / height.toFloat()).coerceIn(0f, 1f)
                )
            }
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        frameWidth = 0
        maskAvailable = false
        program = glCreateProgram()
        fun shader(type: Int, source: String): Int {
            val id = glCreateShader(type)
            glShaderSource(id, source); glCompileShader(id)
            val status = IntArray(1)
            glGetShaderiv(id, GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { glGetShaderInfoLog(id) }
            return id
        }
        val vertex = shader(GL_VERTEX_SHADER, """
            attribute vec2 position;
            varying vec2 uv;
            void main() {
                gl_Position = vec4(position, 0.0, 1.0);
                uv = vec2((position.x + 1.0) * 0.5, (1.0 - position.y) * 0.5);
            }
        """)
        val fragment = shader(GL_FRAGMENT_SHADER, """
            precision mediump float;
            varying vec2 uv;
            uniform sampler2D cameraFrame;
            uniform sampler2D personMask;
            uniform vec2 texel;
            uniform float strength;
            uniform float cropScale;
            uniform float blurScale;
            uniform vec2 focusPoint;
            uniform float focusEnabled;
            uniform float debugMask;
            uniform float qualityBoost;
            uniform float qualityEnabled;
            uniform float maskAvailable;
            float refinedPerson(vec2 p) {
                float centerMask = texture2D(personMask, p).r;
                if (centerMask < 0.08 || centerMask > 0.92) return smoothstep(0.20, 0.80, centerMask);
                vec3 centerColor = texture2D(cameraFrame, p).rgb;
                float sum = centerMask;
                float weightSum = 1.0;
                for (int i = 0; i < 4; i++) {
                    vec2 delta = i == 0 ? vec2(3.0, 0.0)
                        : i == 1 ? vec2(-3.0, 0.0)
                        : i == 2 ? vec2(0.0, 3.0) : vec2(0.0, -3.0);
                    vec2 q = clamp(p + delta * texel, 0.0, 1.0);
                    vec3 difference = texture2D(cameraFrame, q).rgb - centerColor;
                    float weight = 1.0 / (1.0 + dot(difference, difference) * 36.0);
                    sum += texture2D(personMask, q).r * weight;
                    weightSum += weight;
                }
                return smoothstep(0.20, 0.80, sum / weightSum);
            }
            vec3 enhanceColor(vec2 sampleUv, vec3 center) {
                vec2 stepUv = texel * 1.25;
                vec3 north = texture2D(cameraFrame, clamp(sampleUv + vec2(0.0, stepUv.y), 0.0, 1.0)).rgb;
                vec3 south = texture2D(cameraFrame, clamp(sampleUv - vec2(0.0, stepUv.y), 0.0, 1.0)).rgb;
                vec3 east = texture2D(cameraFrame, clamp(sampleUv + vec2(stepUv.x, 0.0), 0.0, 1.0)).rgb;
                vec3 west = texture2D(cameraFrame, clamp(sampleUv - vec2(stepUv.x, 0.0), 0.0, 1.0)).rgb;
                vec3 soft = (center * 2.0 + north + south + east + west) / 6.0;
                vec3 detail = center - soft;
                vec3 crisp = mix(center, soft, 0.025 * qualityBoost);
                crisp += detail * (0.12 * qualityBoost);
                float luminance = dot(crisp, vec3(0.2126, 0.7152, 0.0722));
                vec3 chroma = crisp - vec3(luminance);
                crisp = vec3(luminance) + chroma * (1.0 + 0.035 * qualityBoost);
                crisp = (crisp - 0.5) * (1.0 + 0.025 * qualityBoost) + 0.5;
                return clamp(crisp, 0.0, 1.0);
            }
            void main() {
                vec2 lensUv = clamp((uv - 0.5) / cropScale + 0.5, 0.0, 1.0);
                vec2 focusSourceUv = clamp((focusPoint - 0.5) * cropScale + 0.5, 0.0, 1.0);
                vec4 original = texture2D(cameraFrame, lensUv);
                float person = maskAvailable > 0.5 ? refinedPerson(lensUv) : 1.0;
                float keep = person;
                vec4 blurred = vec4(0.0);
                float weights = 0.0;
                for (int x = -1; x <= 1; x++) {
                    for (int y = -1; y <= 1; y++) {
                        vec2 delta = vec2(float(x), float(y));
                        vec2 sampleUv = clamp(lensUv + delta * texel * strength * blurScale * 7.0, 0.0, 1.0);
                        float samplePerson = smoothstep(0.20, 0.80, texture2D(personMask, sampleUv).r);
                        float background = 1.0 - samplePerson;
                        float weight = exp(-dot(delta, delta) / 6.0) * background;
                        blurred += texture2D(cameraFrame, sampleUv) * weight;
                        weights += weight;
                    }
                }
                vec4 backgroundColor = weights > 0.001 ? blurred / weights : original;
                vec4 result = mix(backgroundColor, original, keep);
                if (strength < 0.001) result = original;
                if (qualityEnabled > 0.5 && qualityBoost > 0.001) {
                    result.rgb = mix(result.rgb, enhanceColor(lensUv, result.rgb), qualityBoost);
                }
                float ring = focusEnabled > 0.5
                    ? 1.0 - smoothstep(0.0, 0.014, abs(distance(uv, focusPoint) - 0.16))
                    : 0.0;
                vec3 outputColor = mix(result.rgb, vec3(1.0, 0.78, 0.12), ring * 0.85);
                gl_FragColor = debugMask > 0.5 ? vec4(vec3(person), 1.0) : vec4(outputColor, 1.0);
            }
        """)
        glAttachShader(program, vertex); glAttachShader(program, fragment); glLinkProgram(program)
        val linked = IntArray(1); glGetProgramiv(program, GL_LINK_STATUS, linked, 0)
        check(linked[0] != 0) { glGetProgramInfoLog(program) }
        glDeleteShader(vertex); glDeleteShader(fragment)
        positionLocation = glGetAttribLocation(program, "position")
        cameraFrameLocation = glGetUniformLocation(program, "cameraFrame")
        personMaskLocation = glGetUniformLocation(program, "personMask")
        texelLocation = glGetUniformLocation(program, "texel")
        strengthLocation = glGetUniformLocation(program, "strength")
        cropScaleLocation = glGetUniformLocation(program, "cropScale")
        blurScaleLocation = glGetUniformLocation(program, "blurScale")
        focusPointLocation = glGetUniformLocation(program, "focusPoint")
        focusEnabledLocation = glGetUniformLocation(program, "focusEnabled")
        debugMaskLocation = glGetUniformLocation(program, "debugMask")
        qualityBoostLocation = glGetUniformLocation(program, "qualityBoost")
        qualityEnabledLocation = glGetUniformLocation(program, "qualityEnabled")
        maskAvailableLocation = glGetUniformLocation(program, "maskAvailable")
        glGenTextures(2, textures, 0)
        textures.forEach {
            glBindTexture(GL_TEXTURE_2D, it)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        }
    }
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        screenWidth = width; screenHeight = height
    }
    override fun onDrawFrame(gl: GL10?) {
        takeFrame()?.let { (frame, mask) ->
            frame?.let {
                frameWidth = it.width; frameHeight = it.height
                glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, textures[0])
                GLUtils.texImage2D(GL_TEXTURE_2D, 0, it, 0)
                it.recycle()
            }
            mask?.let {
                glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, textures[1])
                GLUtils.texImage2D(GL_TEXTURE_2D, 0, it, 0)
                maskAvailable = true
                it.recycle()
            }
        }
        glViewport(0, 0, screenWidth, screenHeight)
        glClearColor(0f,0f,0f,1f); glClear(GL_COLOR_BUFFER_BIT)
        if (frameWidth == 0) return
        val scale = minOf(screenWidth.toFloat()/frameWidth, screenHeight.toFloat()/frameHeight)
        val width = (frameWidth * scale).toInt().coerceAtLeast(1)
        val height = (frameHeight * scale).toInt().coerceAtLeast(1)
        val left = (screenWidth-width)/2
        val bottom = (screenHeight-height)/2
        glViewport(left,bottom,width,height)
        glUseProgram(program)
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, textures[0])
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, textures[1])
        glUniform1i(cameraFrameLocation,0)
        glUniform1i(personMaskLocation,1)
        glUniform2f(texelLocation,1f/frameWidth,1f/frameHeight)
        glUniform1f(strengthLocation,intensity)
        glUniform1f(cropScaleLocation,lensProfile.cropScale)
        glUniform1f(blurScaleLocation,lensProfile.blurScale)
        glUniform2f(focusPointLocation,focusX,focusY)
        glUniform1f(focusEnabledLocation,if (focusEnabled) 1f else 0f)
        glUniform1f(debugMaskLocation,if (maskOnly && shot == null) 1f else 0f)
        glUniform1f(qualityBoostLocation, qualityEnhancement.coerceIn(0f, 1f))
        glUniform1f(qualityEnabledLocation, if (qualityEnhancementEnabled) 1f else 0f)
        glUniform1f(maskAvailableLocation, if (maskAvailable) 1f else 0f)
        glEnableVertexAttribArray(positionLocation)
        glVertexAttribPointer(positionLocation,2,GL_FLOAT,false,0,vertices)
        glDrawArrays(GL_TRIANGLE_STRIP,0,4)
        shot?.let { callback ->
            shot = null
            val bytes = ByteBuffer.allocateDirect(width*height*4)
            glReadPixels(left,bottom,width,height,GL_RGBA,GL_UNSIGNED_BYTE,bytes)
            val pixels = IntArray(width*height)
            for (y in 0 until height) for (x in 0 until width) {
                val i = (y*width+x)*4
                val r = bytes.get(i).toInt() and 255
                val g = bytes.get(i+1).toInt() and 255
                val b = bytes.get(i+2).toInt() and 255
                pixels[(height-1-y)*width+x] = android.graphics.Color.rgb(r,g,b)
            }
            callback(Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888))
        }
    }
}
