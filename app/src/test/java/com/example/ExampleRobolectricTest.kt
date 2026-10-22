package com.example

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import com.example.doom.DoomEngine
import com.example.doom.DoomInputController
import com.example.doom.DoomInputFrame
import com.example.doom.HapticEvent
import com.example.glyph.DoomGlyphToyService
import com.example.glyph.NothingGlyphBridge
import com.nothing.ketchum.Glyph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Glyph DOOM", appName)
    }

    @Test
    fun `doom engine renders high-contrast 25x25 LED frame, handles movement, firing, and automap`() {
        val engine = DoomEngine()
        val frame = FloatArray(25 * 25)

        repeat(20) {
            engine.stepAndRender(DoomInputFrame(), frame)
        }

        val activePixels = frame.count { it > 0.05f }
        assertTrue("Expected active LEDs on 25x25 matrix", activePixels > 60)

        val startY = engine.playerY
        val startAmmo = engine.ammo
        val haptic = engine.stepAndRender(
            DoomInputFrame(moveForward = 1f, fire = true),
            frame
        )
        assertTrue("Player should move forward in E1M1 Hangar", engine.playerY > startY)
        assertEquals(startAmmo - 1, engine.ammo)
        assertEquals(HapticEvent.SHOT_PISTOL, haptic)

        assertFalse(engine.isAutomapActive)
        engine.stepAndRender(DoomInputFrame(toggleMap = true), frame)
        assertTrue(engine.isAutomapActive)
        assertTrue("Automap frame should have lit wall and player LEDs", frame.count { it > 0.1f } > 40)
    }

    @Test
    fun `gyroscope camera turn is suppressed during keyboard or gamepad use and resumes after 4s delay`() {
        var fakeTimeMs = 10_000L
        val controller = DoomInputController(clockProvider = { fakeTimeMs })
        controller.setGyroTurnForTesting(0.65f)

        // Initially no external controls used -> Gyroscope turn is active
        assertTrue(controller.isGyroscopeActive())
        assertEquals(0.65f, controller.sampleInputFrame().turn, 0.01f)

        // Press keyboard W key -> Gyroscope turn must immediately turn off
        controller.onKeyDown(KeyEvent.KEYCODE_W, null)
        assertFalse(controller.isGyroscopeActive())
        assertEquals(0f, controller.sampleInputFrame().turn, 0.01f)

        // Release W key -> Still within 4-second (4000ms) cooldown window
        fakeTimeMs += 500L
        controller.onKeyUp(KeyEvent.KEYCODE_W, null)
        fakeTimeMs += 2500L // 2.5s after release (< 4s)
        assertFalse("Gyro should stay disabled at 2.5s after keyboard release", controller.isGyroscopeActive())
        assertEquals(0f, controller.sampleInputFrame().turn, 0.01f)

        // Advance past 4.0s of inactivity -> Gyroscope camera turning automatically resumes
        fakeTimeMs += 1600L // 4.1s total since release
        assertTrue("Gyro should resume after 4s of inactivity", controller.isGyroscopeActive())
        assertEquals(0.65f, controller.sampleInputFrame().turn, 0.01f)
    }

    @Test
    fun `rear glyph button long press triggers simultaneous walk forward and fire`() {
        var fakeTimeMs = 20_000L
        val controller = DoomInputController(clockProvider = { fakeTimeMs })

        controller.onGlyphLongPressChange()
        val frame = controller.sampleInputFrame()
        assertTrue("Long press should move forward", frame.moveForward > 0.8f)
        assertTrue("Long press should fire weapon", frame.fire)

        controller.onGlyphButtonReleased()
        fakeTimeMs += 2000L
        val afterRelease = controller.sampleInputFrame()
        assertEquals(0f, afterRelease.moveForward, 0.01f)
        assertFalse(afterRelease.fire)
    }

    @Test
    fun `nothing glyph sdk bridge clears matrix on exit and DoomGlyphToyService binds cleanly`() {
        assertTrue(Glyph.DEVICE_23112.isNotEmpty())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bridge = NothingGlyphBridge(context)
        assertTrue(bridge.isSessionActive)

        bridge.clearAndCloseMatrix()
        assertFalse("Matrix session must be inactive after clearAndCloseMatrix on app exit", bridge.isSessionActive)
        bridge.resumeSession()
        assertTrue("Matrix session should reactivate on resumeSession", bridge.isSessionActive)
        bridge.release()

        val serviceController = Robolectric.buildService(DoomGlyphToyService::class.java)
        val service = serviceController.create().get()
        val binder = service.onBind(Intent("com.nothing.glyph.TOY"))
        assertNotNull("DoomGlyphToyService should return Messenger IBinder for GlyphToy events", binder)
        service.onUnbind(Intent("com.nothing.glyph.TOY"))
        serviceController.destroy()
    }
}
