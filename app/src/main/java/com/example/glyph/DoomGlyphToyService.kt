package com.example.glyph

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.example.doom.DoomEngine
import com.example.doom.DoomInputController
import com.nothing.ketchum.GlyphToy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Official Nothing Phone (3) Glyph Toy Service (`com.nothing.glyph.TOY`).
 *
 * Control scheme for rear Glyph Matrix gameplay:
 * - **Gyroscope (`Sensor.TYPE_GYROSCOPE`)**: Smoothly turns the camera left/right by tilting/rotating the phone.
 *   Automatically pauses if a connected Gamepad or Keyboard sends commands, and resumes after 4 seconds of inactivity.
 * - **Long press on rear Glyph Button (`GlyphToy.EVENT_CHANGE`)**: Simultaneously walks forward and shoots
 *   (and opens doors / switches directly ahead).
 * - **Short press**: Completely ignored by the game because Nothing OS uses short press to switch Glyph Toys.
 */
class DoomGlyphToyService : Service() {

    private var glyphBridge: NothingGlyphBridge? = null
    private val engine = DoomEngine()
    val inputController = DoomInputController()
    private val frameBuffer = FloatArray(DoomEngine.MATRIX_SIZE * DoomEngine.MATRIX_SIZE)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null

    private val serviceHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                GlyphToy.MSG_GLYPH_TOY -> {
                    val bundle = msg.data
                    val event = bundle?.getString(GlyphToy.MSG_GLYPH_TOY_DATA)
                    when (event) {
                        GlyphToy.EVENT_CHANGE -> {
                            // Long press on rear Glyph Button: simultaneously Walk Forward + Fire
                            inputController.onGlyphLongPressChange()
                        }
                        GlyphToy.EVENT_ACTION_UP -> {
                            // Release held state if user was holding after a long press
                            inputController.onGlyphButtonReleased()
                        }
                        GlyphToy.EVENT_AOD -> {
                            val input = inputController.sampleInputFrame()
                            engine.stepAndRender(input, frameBuffer)
                            glyphBridge?.pushFrame25x25(frameBuffer)
                        }
                        // Note: EVENT_ACTION_DOWN (short press) is intentionally not handled
                        // because short press switches Glyph Toys on Nothing Phone (3).
                    }
                }
                else -> super.handleMessage(msg)
            }
        }
    }

    private val serviceMessenger = Messenger(serviceHandler)

    override fun onBind(intent: Intent?): IBinder {
        val bridge = NothingGlyphBridge(applicationContext, isToyServiceMode = true)
        glyphBridge = bridge
        inputController.startGyroscope(applicationContext)
        startToyGameLoop(bridge)
        return serviceMessenger.binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        loopJob?.cancel()
        loopJob = null
        inputController.onGlyphButtonReleased()
        inputController.stopGyroscope()
        glyphBridge?.clearAndCloseMatrix()
        glyphBridge?.release()
        glyphBridge = null
        return false
    }

    override fun onDestroy() {
        loopJob?.cancel()
        inputController.onGlyphButtonReleased()
        inputController.stopGyroscope()
        glyphBridge?.clearAndCloseMatrix()
        glyphBridge?.release()
        glyphBridge = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startToyGameLoop(bridge: NothingGlyphBridge) {
        loopJob?.cancel()
        loopJob = serviceScope.launch {
            while (isActive) {
                val input = inputController.sampleInputFrame()
                engine.stepAndRender(input, frameBuffer)
                bridge.pushFrame25x25(frameBuffer)
                delay(28L)
            }
        }
    }
}
