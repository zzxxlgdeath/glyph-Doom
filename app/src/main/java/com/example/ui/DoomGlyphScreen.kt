package com.example.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.R
import com.example.doom.DoomEngine
import com.example.doom.DoomInputController
import com.example.doom.HapticEvent
import com.example.glyph.NothingGlyphBridge
import com.example.ui.theme.NothingBezelBorder
import com.example.ui.theme.NothingChassisDark
import com.example.ui.theme.NothingInactiveLed
import com.example.ui.theme.NothingPitchBlack
import com.example.ui.theme.NothingTouchZoneOutline
import kotlinx.coroutines.delay
import kotlin.math.min

/**
 * Pure, zero-menu 25x25 Glyph Matrix DOOM screen for Nothing Phone (3).
 *
 * - Renders the centered 25x25 micro-LED array with 16-level dithering and subtle LED bloom.
 * - Simultaneously streams every 25x25 frame to the physical Nothing Phone (3) Glyph Matrix SDK bridge
 *   while in the foreground, and immediately clears/turns off the rear matrix on pause/stop/exit.
 * - Supports Gyroscope camera turning (automatically paused when Gamepad/Keyboard/Touch is used,
 *   resuming after 4 seconds of inactivity).
 */
@Composable
fun DoomGlyphScreen(
    engine: DoomEngine,
    inputController: DoomInputController,
    glyphBridge: NothingGlyphBridge,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    val ledFrameBuffer = remember { FloatArray(DoomEngine.MATRIX_SIZE * DoomEngine.MATRIX_SIZE) }
    var frameTick by remember { mutableLongStateOf(0L) }
    val touchOverlayVisible by inputController.touchOverlayVisible.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    var isForegroundActive by remember { mutableStateOf(true) }

    DisposableEffect(lifecycleOwner, glyphBridge) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    isForegroundActive = true
                    glyphBridge.resumeSession()
                }
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY -> {
                    isForegroundActive = false
                    glyphBridge.clearAndCloseMatrix()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            glyphBridge.clearAndCloseMatrix()
        }
    }

    val overlayAlpha by animateFloatAsState(
        targetValue = if (touchOverlayVisible) 0.34f else 0f,
        animationSpec = tween(durationMillis = 260),
        label = "touchZoneAlpha"
    )

    // Classic ~35 Hz DOOM game loop + hardware Glyph Matrix frame sync (only while in foreground)
    LaunchedEffect(engine, inputController, glyphBridge, isForegroundActive) {
        if (!isForegroundActive) return@LaunchedEffect
        focusRequester.requestFocus()
        while (isForegroundActive) {
            val input = inputController.sampleInputFrame()
            val haptic = engine.stepAndRender(input, ledFrameBuffer)
            if (isForegroundActive) {
                glyphBridge.pushFrame25x25(ledFrameBuffer)
            }

            when (haptic) {
                HapticEvent.SHOT_PISTOL -> glyphBridge.triggerHapticPulse(14L, 120)
                HapticEvent.SHOT_SHOTGUN -> glyphBridge.triggerHapticPulse(28L, 220)
                HapticEvent.EXPLOSION -> glyphBridge.triggerHapticPulse(45L, 255)
                HapticEvent.DAMAGE -> glyphBridge.triggerHapticPulse(32L, 200)
                HapticEvent.DOOR_OR_PICKUP -> glyphBridge.triggerHapticPulse(12L, 90)
                HapticEvent.NONE -> {}
            }

            frameTick++
            delay(28L)
        }
    }

    val matrixDescription = stringResource(id = R.string.cd_glyph_matrix)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(NothingPitchBlack)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { keyEvent ->
                val native = keyEvent.nativeKeyEvent
                if (native.action == android.view.KeyEvent.ACTION_DOWN) {
                    if (native.keyCode == android.view.KeyEvent.KEYCODE_R) {
                        engine.resetLevel(triggerMelt = true)
                        true
                    } else {
                        inputController.onKeyDown(native.keyCode, native)
                    }
                } else if (native.action == android.view.KeyEvent.ACTION_UP) {
                    inputController.onKeyUp(native.keyCode, native)
                } else {
                    false
                }
            }
            .testTag("doom_glyph_root")
    ) {
        val minDimension = min(maxWidth.value, maxHeight.value).dp
        val matrixBoxSize = (minDimension * 0.84f).coerceIn(220.dp, 460.dp)

        // Centered 25x25 Nothing Phone (3) Glyph Matrix Display
        Box(
            modifier = Modifier
                .sizeIn(
                    minWidth = 200.dp,
                    minHeight = 200.dp,
                    maxWidth = matrixBoxSize,
                    maxHeight = matrixBoxSize
                )
                .aspectRatio(1f)
                .align(Alignment.Center)
                .background(NothingChassisDark, RoundedCornerShape(22.dp))
                .border(1.5.dp, NothingBezelBorder, RoundedCornerShape(22.dp))
                .padding(14.dp)
                .semantics { contentDescription = matrixDescription }
                .testTag("glyph_matrix_25x25")
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val tick = frameTick
                if (tick >= 0L) {
                    val gridCount = DoomEngine.MATRIX_SIZE
                    val cellW = size.width / gridCount
                    val cellH = size.height / gridCount
                    val cellSize = min(cellW, cellH)
                    val ledRadius = cellSize * 0.37f
                    val glowRadius = cellSize * 0.54f

                    for (row in 0 until gridCount) {
                        val rowOffset = row * gridCount
                        val cy = row * cellH + cellH * 0.5f
                        for (col in 0 until gridCount) {
                            val cx = col * cellW + cellW * 0.5f
                            val center = Offset(cx, cy)
                            val intensity = ledFrameBuffer[rowOffset + col].coerceIn(0f, 1f)

                            if (intensity <= 0.04f) {
                                drawCircle(
                                    color = NothingInactiveLed,
                                    radius = ledRadius * 0.88f,
                                    center = center
                                )
                            } else {
                                if (intensity > 0.45f) {
                                    drawCircle(
                                        color = Color.White.copy(alpha = (intensity - 0.35f) * 0.24f),
                                        radius = glowRadius,
                                        center = center
                                    )
                                }

                                val r = (0.22f + intensity * 0.78f).coerceIn(0f, 1f)
                                val g = (0.22f + intensity * 0.78f).coerceIn(0f, 1f)
                                val b = (0.25f + intensity * 0.75f).coerceIn(0f, 1f)

                                drawRoundRect(
                                    color = Color(r, g, b, 1f),
                                    topLeft = Offset(cx - ledRadius, cy - ledRadius),
                                    size = Size(ledRadius * 2f, ledRadius * 2f),
                                    cornerRadius = CornerRadius(ledRadius * 0.48f, ledRadius * 0.48f)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Auto-hiding invisible touch zones
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val origin = down.position
                            val maxRadius = (size.width * 0.28f).coerceAtLeast(60f)
                            if (origin.y < size.height * 0.22f) {
                                val isFarCornerMapToggle = origin.x < size.width * 0.35f && origin.y < size.height * 0.14f
                                inputController.updateTouchAction(
                                    use = true,
                                    cycleWeapon = false,
                                    toggleMap = isFarCornerMapToggle
                                )
                            }
                            do {
                                val event = awaitPointerEvent()
                                val current = event.changes.firstOrNull()?.position ?: origin
                                val dx = ((current.x - origin.x) / maxRadius).coerceIn(-1f, 1f)
                                val dy = (-(current.y - origin.y) / maxRadius).coerceIn(-1f, 1f)
                                val forwardVal = if (kotlin.math.abs(dy) < 0.12f && kotlin.math.abs(dx) < 0.12f && origin.y >= size.height * 0.22f) {
                                    0.75f
                                } else {
                                    dy
                                }
                                inputController.updateTouchMove(
                                    forward = forwardVal,
                                    strafe = dx,
                                    active = event.changes.any { it.pressed }
                                )
                            } while (event.changes.any { it.pressed })
                            inputController.updateTouchMove(0f, 0f, false)
                            inputController.updateTouchAction(use = false, cycleWeapon = false)
                        }
                    }
                    .testTag("touch_zone_left")
            ) {
                if (overlayAlpha > 0.01f) {
                    Text(
                        text = stringResource(id = R.string.touch_use_zone),
                        style = MaterialTheme.typography.labelSmall,
                        color = NothingTouchZoneOutline.copy(alpha = overlayAlpha),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(16.dp)
                    )
                    Text(
                        text = stringResource(id = R.string.touch_move_zone),
                        style = MaterialTheme.typography.labelSmall,
                        color = NothingTouchZoneOutline.copy(alpha = overlayAlpha),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val origin = down.position
                            val maxRadius = (size.width * 0.25f).coerceAtLeast(55f)
                            val isWeaponCorner = origin.y < size.height * 0.22f
                            if (isWeaponCorner) {
                                inputController.updateTouchAction(use = false, cycleWeapon = true)
                            }
                            do {
                                val event = awaitPointerEvent()
                                val current = event.changes.firstOrNull()?.position ?: origin
                                val turnVal = ((current.x - origin.x) / maxRadius).coerceIn(-1f, 1f)
                                val pressed = event.changes.any { it.pressed }
                                if (!isWeaponCorner) {
                                    inputController.updateTouchLookAndFire(
                                        turn = turnVal,
                                        firing = pressed,
                                        active = pressed
                                    )
                                }
                            } while (event.changes.any { it.pressed })
                            inputController.updateTouchLookAndFire(0f, firing = false, active = false)
                            inputController.updateTouchAction(use = false, cycleWeapon = false)
                        }
                    }
                    .testTag("touch_zone_right")
            ) {
                if (overlayAlpha > 0.01f) {
                    Text(
                        text = stringResource(id = R.string.touch_weapon_zone),
                        style = MaterialTheme.typography.labelSmall,
                        color = NothingTouchZoneOutline.copy(alpha = overlayAlpha),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                    )
                    Text(
                        text = stringResource(id = R.string.touch_look_fire_zone),
                        style = MaterialTheme.typography.labelSmall,
                        color = NothingTouchZoneOutline.copy(alpha = overlayAlpha),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp)
                    )
                }
            }
        }
    }
}
