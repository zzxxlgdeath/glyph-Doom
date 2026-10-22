package com.example.doom

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Unified input controller for 25x25 Glyph DOOM:
 * - Supports hardware Gamepad (sticks, triggers, D-pad, buttons) and Keyboard (WASD, arrows, Space, etc.).
 * - Supports Gyroscope camera turning (`Sensor.TYPE_GYROSCOPE`), which is automatically disabled
 *   whenever Gamepad, Keyboard, or Touch controls are used and only resumes after [GYRO_RESUME_DELAY_MS]
 *   (4 seconds) of complete inactivity from other controls.
 * - Supports rear Glyph Button long-press (`GlyphToy.EVENT_CHANGE`) for simultaneous Walk Forward + Fire
 *   (short press is completely ignored because Nothing OS uses it to switch Glyph Toys).
 */
class DoomInputController(
    private val clockProvider: () -> Long = { SystemClock.elapsedRealtime() }
) : SensorEventListener {

    companion object {
        /** 4-second delay (within the requested 3-5s window) before gyroscope turning resumes. */
        const val GYRO_RESUME_DELAY_MS = 4000L

        /** Minimum duration of forward+fire action when `GlyphToy.EVENT_CHANGE` (long press) is triggered. */
        private const val GLYPH_LONG_PRESS_BURST_MS = 1400L
    }

    private val pressedKeys = HashSet<Int>()

    // Gamepad analog axes
    private var stickForward = 0f
    private var stickStrafe = 0f
    private var stickTurn = 0f
    private var triggerFire = false

    // Touch zone analog/button states
    private var touchForward = 0f
    private var touchStrafe = 0f
    private var touchTurn = 0f
    private var touchFire = false
    private var touchUse = false
    private var touchWeaponCycle = false
    private var touchToggleMap = false

    // Gyroscope angular velocity turn rate (-1f..1f)
    @Volatile
    private var gyroTurnRate = 0f
    private var sensorManager: SensorManager? = null
    private var gyroSensor: Sensor? = null

    // Rear Glyph Button long-press state (Walk Forward + Fire simultaneously)
    @Volatile
    private var glyphLongPressActiveUntilMs: Long = 0L
    @Volatile
    private var glyphLongPressHeld: Boolean = false

    // Timestamp of last input from Gamepad, Keyboard, or Touch
    @Volatile
    private var lastExternalControlTimeMs: Long = -GYRO_RESUME_DELAY_MS * 2

    private val _touchOverlayVisible = MutableStateFlow(false)
    val touchOverlayVisible: StateFlow<Boolean> = _touchOverlayVisible.asStateFlow()

    fun startGyroscope(context: Context) {
        try {
            val sm = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
            sensorManager = sm
            val sensor = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            gyroSensor = sensor
            if (sensor != null) {
                sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
            }
        } catch (_: Throwable) {
        }
    }

    fun stopGyroscope() {
        try {
            sensorManager?.unregisterListener(this)
        } catch (_: Throwable) {
        }
        gyroTurnRate = 0f
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_GYROSCOPE) return
        // Combine yaw (Y axis in portrait) and Z axis rotation for natural wrist-turn aiming
        val rawYaw = -event.values[1] - (event.values[2] * 0.45f)
        val deadzone = 0.12f
        gyroTurnRate = if (abs(rawYaw) < deadzone) {
            0f
        } else {
            (rawYaw * 0.55f).coerceIn(-1f, 1f)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * Triggered ONLY on `GlyphToy.EVENT_CHANGE` (rear Glyph Button long press).
     * Short press (`EVENT_ACTION_DOWN` alone) is intentionally ignored because it switches Glyph Toys.
     */
    fun onGlyphLongPressChange() {
        glyphLongPressHeld = true
        glyphLongPressActiveUntilMs = clockProvider() + GLYPH_LONG_PRESS_BURST_MS
    }

    /**
     * Triggered when the user releases the rear Glyph Button (`GlyphToy.EVENT_ACTION_UP`).
     */
    fun onGlyphButtonReleased() {
        glyphLongPressHeld = false
    }

    fun setGyroTurnForTesting(turnRate: Float) {
        gyroTurnRate = turnRate.coerceIn(-1f, 1f)
    }

    private fun markExternalControlUsed() {
        lastExternalControlTimeMs = clockProvider()
    }

    fun isGyroscopeActive(): Boolean {
        val now = clockProvider()
        val anyExternalHeld = pressedKeys.isNotEmpty() ||
            abs(stickForward) > 0.01f ||
            abs(stickStrafe) > 0.01f ||
            abs(stickTurn) > 0.01f ||
            triggerFire ||
            abs(touchForward) > 0.01f ||
            abs(touchStrafe) > 0.01f ||
            abs(touchTurn) > 0.01f ||
            touchFire ||
            touchUse ||
            touchWeaponCycle ||
            touchToggleMap

        if (anyExternalHeld) {
            lastExternalControlTimeMs = now
            return false
        }
        return (now - lastExternalControlTimeMs) >= GYRO_RESUME_DELAY_MS
    }

    fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isHandledKey(keyCode)) {
            pressedKeys.add(keyCode)
            markExternalControlUsed()
            _touchOverlayVisible.value = false
            return true
        }
        return false
    }

    fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (isHandledKey(keyCode)) {
            pressedKeys.remove(keyCode)
            markExternalControlUsed()
            return true
        }
        return false
    }

    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val source = event.source
        val isJoystickOrGamepad = (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
            (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
        if (!isJoystickOrGamepad || event.action != MotionEvent.ACTION_MOVE) return false

        val lx = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_X))
        val ly = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_Y))
        val hatX = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_HAT_X))
        val hatY = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_HAT_Y))

        val rxZ = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_Z))
        val rxRX = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_RX))
        val rx = if (abs(rxZ) > abs(rxRX)) rxZ else rxRX

        val rTrigger = maxOf(
            event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
            event.getAxisValue(MotionEvent.AXIS_GAS),
            event.getAxisValue(MotionEvent.AXIS_LTRIGGER)
        )

        stickForward = (-ly - hatY).coerceIn(-1f, 1f)
        stickStrafe = (lx + hatX).coerceIn(-1f, 1f)
        stickTurn = rx.coerceIn(-1f, 1f)
        triggerFire = rTrigger > 0.25f

        if (abs(stickForward) > 0.01f || abs(stickStrafe) > 0.01f || abs(stickTurn) > 0.01f || triggerFire) {
            markExternalControlUsed()
            _touchOverlayVisible.value = false
        }
        return true
    }

    fun updateTouchMove(forward: Float, strafe: Float, active: Boolean) {
        touchForward = if (active) forward.coerceIn(-1f, 1f) else 0f
        touchStrafe = if (active) strafe.coerceIn(-1f, 1f) else 0f
        if (active) {
            markExternalControlUsed()
            _touchOverlayVisible.value = true
        }
    }

    fun updateTouchLookAndFire(turn: Float, firing: Boolean, active: Boolean) {
        touchTurn = if (active) turn.coerceIn(-1f, 1f) else 0f
        touchFire = active && firing
        if (active) {
            markExternalControlUsed()
            _touchOverlayVisible.value = true
        }
    }

    fun updateTouchAction(use: Boolean, cycleWeapon: Boolean, toggleMap: Boolean = false) {
        touchUse = use
        touchWeaponCycle = cycleWeapon
        touchToggleMap = toggleMap
        if (use || cycleWeapon || toggleMap) {
            markExternalControlUsed()
            _touchOverlayVisible.value = true
        }
    }

    fun sampleInputFrame(): DoomInputFrame {
        val now = clockProvider()
        var kbForward = 0f
        var kbStrafe = 0f
        var kbTurn = 0f

        if (pressedKeys.contains(KeyEvent.KEYCODE_W) ||
            pressedKeys.contains(KeyEvent.KEYCODE_DPAD_UP)
        ) {
            kbForward += 1f
        }
        if (pressedKeys.contains(KeyEvent.KEYCODE_S) ||
            pressedKeys.contains(KeyEvent.KEYCODE_DPAD_DOWN)
        ) {
            kbForward -= 1f
        }

        if (pressedKeys.contains(KeyEvent.KEYCODE_A)) {
            kbStrafe -= 1f
        }
        if (pressedKeys.contains(KeyEvent.KEYCODE_D)) {
            kbStrafe += 1f
        }

        if (pressedKeys.contains(KeyEvent.KEYCODE_DPAD_LEFT) ||
            pressedKeys.contains(KeyEvent.KEYCODE_Q) ||
            pressedKeys.contains(KeyEvent.KEYCODE_BUTTON_L1)
        ) {
            kbTurn -= 1f
        }
        if (pressedKeys.contains(KeyEvent.KEYCODE_DPAD_RIGHT) ||
            pressedKeys.contains(KeyEvent.KEYCODE_BUTTON_R1)
        ) {
            kbTurn += 1f
        }

        // Rear Glyph Button long-press: simultaneously Walk Forward + Fire (and open doors in front)
        val isGlyphLongPressActive = glyphLongPressHeld || (now < glyphLongPressActiveUntilMs)
        val glyphForward = if (isGlyphLongPressActive) 0.9f else 0f

        val firePressed = isGlyphLongPressActive ||
            triggerFire ||
            touchFire ||
            pressedKeys.contains(KeyEvent.KEYCODE_SPACE) ||
            pressedKeys.contains(KeyEvent.KEYCODE_CTRL_LEFT) ||
            pressedKeys.contains(KeyEvent.KEYCODE_CTRL_RIGHT) ||
            pressedKeys.contains(KeyEvent.KEYCODE_F) ||
            pressedKeys.contains(KeyEvent.KEYCODE_BUTTON_A) ||
            pressedKeys.contains(KeyEvent.KEYCODE_BUTTON_R2) ||
            pressedKeys.contains(KeyEvent.KEYCODE_BUTTON_X) ||
            pressedKeys.contains(KeyEvent.KEYCODE_DPAD_CENTER)

        val usePressed = isGlyphLongPressActive ||
            touchUse ||
            pressedKeys.contains(KeyEvent.KEYCODE_E) ||
            pressedKeys.contains(KeyEvent.KEYCODE_ENTER) ||
            pressedKeys.contains(KeyEvent.KEYCODE_BUTTON_B) ||
            pressedKeys.contains(KeyEvent.KEYCODE_BUTTON_START)

        val weaponSelect = when {
            pressedKeys.contains(KeyEvent.KEYCODE_1) -> 1
            pressedKeys.contains(KeyEvent.KEYCODE_2) -> 2
            pressedKeys.contains(KeyEvent.KEYCODE_3) -> 3
            touchWeaponCycle ||
                pressedKeys.contains(KeyEvent.KEYCODE_SHIFT_LEFT) ||
                pressedKeys.contains(KeyEvent.KEYCODE_BUTTON_Y) -> -1
            else -> 0
        }

        val mapToggle = touchToggleMap ||
            pressedKeys.contains(KeyEvent.KEYCODE_TAB) ||
            pressedKeys.contains(KeyEvent.KEYCODE_M) ||
            pressedKeys.contains(KeyEvent.KEYCODE_BUTTON_SELECT)

        // Gyroscope camera turning is ONLY applied when no gamepad/keyboard/touch input
        // has occurred within the last 4 seconds (GYRO_RESUME_DELAY_MS).
        val effectiveGyroTurn = if (isGyroscopeActive()) gyroTurnRate else 0f

        return DoomInputFrame(
            moveForward = (kbForward + stickForward + touchForward + glyphForward).coerceIn(-1f, 1f),
            strafe = (kbStrafe + stickStrafe + touchStrafe).coerceIn(-1f, 1f),
            turn = (kbTurn + stickTurn + touchTurn + effectiveGyroTurn).coerceIn(-1f, 1f),
            fire = firePressed,
            use = usePressed,
            weaponSelect = weaponSelect,
            toggleMap = mapToggle
        )
    }

    private fun applyDeadzone(value: Float, threshold: Float = 0.14f): Float {
        return if (abs(value) < threshold) 0f else value
    }

    private fun isHandledKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_W,
            KeyEvent.KEYCODE_A,
            KeyEvent.KEYCODE_S,
            KeyEvent.KEYCODE_D,
            KeyEvent.KEYCODE_Q,
            KeyEvent.KEYCODE_E,
            KeyEvent.KEYCODE_F,
            KeyEvent.KEYCODE_M,
            KeyEvent.KEYCODE_R,
            KeyEvent.KEYCODE_1,
            KeyEvent.KEYCODE_2,
            KeyEvent.KEYCODE_3,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_CTRL_LEFT,
            KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L2,
            KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_SELECT -> true
            else -> false
        }
    }
}
