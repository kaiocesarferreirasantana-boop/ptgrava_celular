package com.personaltech.gravador

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Recebe a tela do celular (proporção nativa, ex. 20:9) numa SurfaceTexture e
 * desenha no encoder em 1080x1920 cortando o excesso (modo "Preencher").
 * Roda numa thread própria com OpenGL ES 2.
 */
class GlCropper(
    private val outSurface: Surface,
    private val outW: Int,
    private val outH: Int,
    private val inW: Int,
    private val inH: Int,
) {
    private val thread = HandlerThread("PTGravador-GL").apply { start() }
    private val handler = Handler(thread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var texId = 0
    private var aPos = 0
    private var aTex = 0
    private var uTexMatrix = 0
    private var uCrop = 0
    private val texMatrix = FloatArray(16)
    private val crop = FloatArray(4)

    private var surfaceTexture: SurfaceTexture? = null
    private var _inputSurface: Surface? = null
    val inputSurface: Surface get() = _inputSurface!!

    @Volatile private var released = false

    private val quadPos: FloatBuffer = floatBuffer(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val quadTex: FloatBuffer = floatBuffer(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

    init {
        computeCrop()
        val latch = CountDownLatch(1)
        var error: Throwable? = null
        handler.post {
            try { setup() } catch (t: Throwable) { error = t } finally { latch.countDown() }
        }
        latch.await(3, TimeUnit.SECONDS)
        error?.let { release(); throw RuntimeException("Falha ao iniciar OpenGL: ${it.message}", it) }
    }

    /** Recorte centralizado em coordenadas de textura (x, y, largura, altura). */
    private fun computeCrop() {
        val inAspect = inW.toFloat() / inH
        val outAspect = outW.toFloat() / outH
        if (inAspect < outAspect) {
            // Tela mais "alta" que 9:16 -> corta topo e base
            val f = inAspect / outAspect
            crop[0] = 0f; crop[1] = (1f - f) / 2f; crop[2] = 1f; crop[3] = f
        } else {
            // Tela mais "larga" que 9:16 (tablet) -> corta laterais
            val f = outAspect / inAspect
            crop[0] = (1f - f) / 2f; crop[1] = 0f; crop[2] = f; crop[3] = 1f
        }
    }

    private fun setup() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "sem EGL display" }
        val ver = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) { "eglInitialize" }

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) {
            "eglChooseConfig"
        }
        val config = configs[0]!!

        eglContext = EGL14.eglCreateContext(
            eglDisplay, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }

        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, config, outSurface, intArrayOf(EGL14.EGL_NONE), 0
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface" }
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent" }

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uCrop = GLES20.glGetUniformLocation(program, "uCrop")

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(texId)
        st.setDefaultBufferSize(inW, inH)
        st.setOnFrameAvailableListener({ drawFrame() }, handler)
        surfaceTexture = st
        _inputSurface = Surface(st)
    }

    private fun drawFrame() {
        if (released) return
        val st = surfaceTexture ?: return
        try {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)

            GLES20.glViewport(0, 0, outW, outH)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
            GLES20.glUniform4f(uCrop, crop[0], crop[1], crop[2], crop[3])

            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quadPos)
            GLES20.glEnableVertexAttribArray(aTex)
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, quadTex)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPos)
            GLES20.glDisableVertexAttribArray(aTex)

            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, st.timestamp)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        } catch (_: Throwable) {
            // frame perdido não derruba a gravação
        }
    }

    /** Libera tudo e espera terminar (chamar ANTES de MediaRecorder.stop()). */
    fun release() {
        if (released) return
        released = true
        val latch = CountDownLatch(1)
        val posted = handler.post {
            try {
                surfaceTexture?.setOnFrameAvailableListener(null)
                _inputSurface?.release()
                surfaceTexture?.release()
                if (program != 0) GLES20.glDeleteProgram(program)
                if (texId != 0) GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                    EGL14.eglReleaseThread()
                    EGL14.eglTerminate(eglDisplay)
                }
            } catch (_: Throwable) {
            } finally {
                latch.countDown()
            }
        }
        if (posted) latch.await(2, TimeUnit.SECONDS)
        thread.quitSafely()
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            throw RuntimeException("link: $log")
        }
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw RuntimeException("shader: $log")
        }
        return s
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private val VERTEX_SHADER = """
            attribute vec4 aPos;
            attribute vec2 aTex;
            uniform mat4 uTexMatrix;
            uniform vec4 uCrop;
            varying vec2 vTex;
            void main() {
                gl_Position = aPos;
                vec2 t = uCrop.xy + aTex * uCrop.zw;
                vTex = (uTexMatrix * vec4(t, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        private val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() {
                gl_FragColor = texture2D(sTex, vTex);
            }
        """.trimIndent()

        private fun floatBuffer(vararg v: Float): FloatBuffer =
            ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply { put(v); position(0) }
    }
}
