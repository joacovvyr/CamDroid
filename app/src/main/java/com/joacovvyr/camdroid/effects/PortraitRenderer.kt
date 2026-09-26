package com.joacovvyr.camdroid.effects

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Frame and mask travel together so motion cannot pair a new frame with an old mask. */
class PortraitRenderer(context: Context) : GLSurfaceView(context), GLSurfaceView.Renderer {
    @Volatile var intensity = 0.65f
    @Volatile var maskOnly = false
    private var pending: Pair<Bitmap, Bitmap>? = null
    private var program = 0
    private val textures = IntArray(2)
    private var frameWidth = 0
    private var frameHeight = 0
    private var screenWidth = 1
    private var screenHeight = 1
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
        pending?.let { it.first.recycle(); it.second.recycle() }
        pending = frame to mask
        requestRender()
    }
    @Synchronized private fun takeFrame(): Pair<Bitmap, Bitmap>? {
        val value = pending
        pending = null
        return value
    }
    @Synchronized fun discardPending() {
        pending?.let { it.first.recycle(); it.second.recycle() }
        pending = null
    }
    fun capture(callback: (Bitmap) -> Unit) {
        queueEvent { if (frameWidth > 0) shot = callback }
        requestRender()
    }
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        frameWidth = 0
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
            uniform float debugMask;
            void main() {
                vec4 original = texture2D(cameraFrame, uv);
                float person = smoothstep(0.2, 0.85, texture2D(personMask, uv).r);
                vec4 blurred = vec4(0.0);
                float weights = 0.0;
                for (int x = -3; x <= 3; x++) {
                    for (int y = -3; y <= 3; y++) {
                        vec2 delta = vec2(float(x), float(y));
                        vec2 sampleUv = clamp(uv + delta * texel * strength * 5.0, 0.0, 1.0);
                        float background = 1.0 - smoothstep(0.2, 0.85, texture2D(personMask, sampleUv).r);
                        float weight = exp(-dot(delta, delta) / 6.0) * background;
                        blurred += texture2D(cameraFrame, sampleUv) * weight;
                        weights += weight;
                    }
                }
                vec4 backgroundColor = weights > 0.001 ? blurred / weights : original;
                vec4 result = mix(backgroundColor, original, person);
                if (strength < 0.001) result = original;
                gl_FragColor = debugMask > 0.5 ? vec4(vec3(person), 1.0) : vec4(result.rgb, 1.0);
            }
        """)
        glAttachShader(program, vertex); glAttachShader(program, fragment); glLinkProgram(program)
        val linked = IntArray(1); glGetProgramiv(program, GL_LINK_STATUS, linked, 0)
        check(linked[0] != 0) { glGetProgramInfoLog(program) }
        glDeleteShader(vertex); glDeleteShader(fragment)
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
            frameWidth = frame.width; frameHeight = frame.height
            glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, textures[0])
            GLUtils.texImage2D(GL_TEXTURE_2D, 0, frame, 0)
            glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, textures[1])
            GLUtils.texImage2D(GL_TEXTURE_2D, 0, mask, 0)
            frame.recycle(); mask.recycle()
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
        glUniform1i(glGetUniformLocation(program,"cameraFrame"),0)
        glUniform1i(glGetUniformLocation(program,"personMask"),1)
        glUniform2f(glGetUniformLocation(program,"texel"),1f/frameWidth,1f/frameHeight)
        glUniform1f(glGetUniformLocation(program,"strength"),intensity)
        glUniform1f(glGetUniformLocation(program,"debugMask"),if (maskOnly && shot == null) 1f else 0f)
        val position = glGetAttribLocation(program,"position")
        glEnableVertexAttribArray(position)
        glVertexAttribPointer(position,2,GL_FLOAT,false,0,vertices)
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
