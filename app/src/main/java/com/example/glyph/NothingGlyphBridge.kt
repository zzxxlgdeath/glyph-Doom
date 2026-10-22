package com.example.glyph

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphException
import com.nothing.ketchum.GlyphMatrixFrame
import com.nothing.ketchum.GlyphMatrixManager
import kotlin.math.roundToInt

/**
 * Direct integration with the official Nothing Glyph Matrix Developer Kit (`glyph-matrix-sdk-1.0.aar`)
 * for Nothing Phone (3) (`Glyph.DEVICE_23112`).
 *
 * Ensures that whenever the app is paused, stopped, or closed, the 25x25 LED matrix is immediately
 * zeroed out (`turnOff()` + `closeAppMatrix()`) so no residual image remains on the rear panel.
 */
class NothingGlyphBridge(
    context: Context,
    private val isToyServiceMode: Boolean = false
) {

    private val appContext: Context = context.applicationContext
    private var matrixManager: GlyphMatrixManager? = null
    private val rawMatrixData = IntArray(625)
    private val emptyMatrixData = IntArray(625)

    @Volatile
    var isHardwareConnected: Boolean = false
        private set

    @Volatile
    var isSessionActive: Boolean = true
        private set

    private val callback = object : GlyphMatrixManager.Callback {
        override fun onServiceConnected(componentName: ComponentName?) {
            try {
                matrixManager?.register(Glyph.DEVICE_23112)
                isHardwareConnected = true
                if (!isSessionActive) {
                    clearHardwareMatrixInternal()
                }
            } catch (_: Throwable) {
                isHardwareConnected = false
            }
        }

        override fun onServiceDisconnected(componentName: ComponentName?) {
            isHardwareConnected = false
        }
    }

    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (_: Throwable) {
        null
    }

    init {
        initGlyphSdk()
    }

    @Synchronized
    private fun initGlyphSdk() {
        try {
            if (matrixManager == null) {
                val mgr = GlyphMatrixManager.getInstance(appContext)
                matrixManager = mgr
                mgr?.init(callback)
            } else if (isHardwareConnected) {
                matrixManager?.register(Glyph.DEVICE_23112)
            }
        } catch (_: Throwable) {
            isHardwareConnected = false
        }
    }

    /**
     * Resumes matrix output when the Activity returns to the foreground.
     */
    @Synchronized
    fun resumeSession() {
        isSessionActive = true
        initGlyphSdk()
    }

    /**
     * Immediately blocks any further frames from being sent and clears/closes
     * the physical Nothing Glyph Matrix so no image remains after leaving the app.
     */
    @Synchronized
    fun clearAndCloseMatrix() {
        isSessionActive = false
        clearHardwareMatrixInternal()
    }

    private fun clearHardwareMatrixInternal() {
        val mgr = matrixManager ?: return
        try {
            if (isToyServiceMode) {
                try {
                    mgr.setMatrixFrame(emptyMatrixData)
                } catch (_: Throwable) {
                }
                try {
                    mgr.turnOff()
                } catch (_: Throwable) {
                }
            } else {
                try {
                    mgr.setAppMatrixFrame(emptyMatrixData)
                } catch (_: Throwable) {
                }
                try {
                    mgr.turnOff()
                } catch (_: Throwable) {
                }
                try {
                    mgr.closeAppMatrix()
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Converts the 25x25 normalized float brightness buffer (0.0f..1.0f) into
     * a 25x25 `GlyphMatrixFrame` (0..255 LED brightness) and transmits it to the
     * Nothing Phone (3) Glyph Matrix via the official SDK.
     */
    @Synchronized
    fun pushFrame25x25(ledBuffer: FloatArray) {
        if (!isSessionActive || !isHardwareConnected) return
        val mgr = matrixManager ?: return

        for (i in 0 until 625) {
            val v = ledBuffer[i].coerceIn(0f, 1f)
            rawMatrixData[i] = (v * 255f).roundToInt().coerceIn(0, 255)
        }

        try {
            val frame = GlyphMatrixFrame.Builder()
                .addTop(rawMatrixData)
                .build(appContext)
            val rendered = frame.render()

            if (!isSessionActive) return
            if (isToyServiceMode) {
                mgr.setMatrixFrame(rendered)
            } else {
                try {
                    mgr.setAppMatrixFrame(rendered)
                } catch (_: GlyphException) {
                    mgr.setMatrixFrame(rendered)
                }
            }
        } catch (_: Throwable) {
            if (!isSessionActive) return
            try {
                if (isToyServiceMode) {
                    mgr.setMatrixFrame(rawMatrixData)
                } else {
                    mgr.setAppMatrixFrame(rawMatrixData)
                }
            } catch (_: Throwable) {
            }
        }
    }

    fun triggerHapticPulse(durationMs: Long = 18L, amplitude: Int = 140) {
        if (!isSessionActive) return
        try {
            val vib = vibrator ?: return
            if (!vib.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(
                    VibrationEffect.createOneShot(
                        durationMs,
                        amplitude.coerceIn(1, 255)
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(durationMs)
            }
        } catch (_: Throwable) {
        }
    }

    @Synchronized
    fun release() {
        isSessionActive = false
        clearHardwareMatrixInternal()
        val mgr = matrixManager
        matrixManager = null
        isHardwareConnected = false
        if (mgr != null) {
            try {
                mgr.unInit()
            } catch (_: Throwable) {
            }
        }
    }
}
