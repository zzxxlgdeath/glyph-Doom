package com.example

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.doom.DoomEngine
import com.example.doom.DoomInputController
import com.example.glyph.NothingGlyphBridge
import com.example.ui.DoomGlyphScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    val doomEngine = DoomEngine()
    val inputController = DoomInputController()
    private lateinit var glyphBridge: NothingGlyphBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        glyphBridge = NothingGlyphBridge(this)

        setContent {
            MyApplicationTheme {
                DoomGlyphScreen(
                    engine = doomEngine,
                    inputController = inputController,
                    glyphBridge = glyphBridge
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        inputController.startGyroscope(this)
        if (::glyphBridge.isInitialized) {
            glyphBridge.resumeSession()
        }
    }

    override fun onPause() {
        inputController.stopGyroscope()
        if (::glyphBridge.isInitialized) {
            glyphBridge.clearAndCloseMatrix()
        }
        super.onPause()
    }

    override fun onStop() {
        inputController.stopGyroscope()
        if (::glyphBridge.isInitialized) {
            glyphBridge.clearAndCloseMatrix()
        }
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_R) {
                doomEngine.resetLevel(triggerMelt = true)
                return true
            }
            if (inputController.onKeyDown(event.keyCode, event)) {
                return true
            }
        } else if (event.action == KeyEvent.ACTION_UP) {
            if (inputController.onKeyUp(event.keyCode, event)) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (inputController.onGenericMotionEvent(ev)) {
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun onDestroy() {
        inputController.stopGyroscope()
        if (::glyphBridge.isInitialized) {
            glyphBridge.release()
        }
        super.onDestroy()
    }
}
