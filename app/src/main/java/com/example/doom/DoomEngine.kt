package com.example.doom

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

data class DoomInputFrame(
    val moveForward: Float = 0f, // -1f..1f
    val strafe: Float = 0f,      // -1f..1f
    val turn: Float = 0f,        // -1f..1f
    val fire: Boolean = false,
    val use: Boolean = false,
    val weaponSelect: Int = 0,   // 0 = none, 1..3 = specific, -1 = cycle next
    val toggleMap: Boolean = false
)

enum class HapticEvent {
    NONE,
    SHOT_PISTOL,
    SHOT_SHOTGUN,
    EXPLOSION,
    DAMAGE,
    DOOR_OR_PICKUP
}

class Entity(
    var x: Float,
    var y: Float,
    val type: EntityType,
    var hp: Int,
    var state: EnemyState = EnemyState.IDLE,
    var stateTimer: Int = 0,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var active: Boolean = true
)

/**
 * Self-contained 1993 DOOM (E1M1: Hangar) 3D engine & high-clarity 25x25 software renderer:
 * - Pitch-black ceiling & floor so corridor geometry stands out with zero background clutter.
 * - Wireframe-accented wall top/bottom edges & depth-discontinuity corner outlines.
 * - 1-pixel black silhouette halo around every monster/barrel/item so enemies never blend into walls.
 * - Classic DOOM Top-Down Automap mode (`Tab` / `M`) + Top-row directional enemy radar blips.
 */
class DoomEngine {

    companion object {
        const val MATRIX_SIZE = 25
        const val VIEWPORT_HEIGHT = 22 // Rows 0..21 for 3D scene; Rows 22..24 for micro-HUD
        private const val FOV = (PI / 2.85).toFloat()
        private const val HALF_FOV = FOV / 2f

        // Subtle Bayer 2x2 dithering for wall depth shading
        private val BAYER_2X2 = arrayOf(
            floatArrayOf(-0.025f, 0.015f),
            floatArrayOf(0.025f, -0.015f)
        )
    }

    private var mapGrid = E1M1Map.createGrid()
    private val doorOpenProgress = FloatArray(E1M1Map.MAP_WIDTH * E1M1Map.MAP_HEIGHT)
    private val doorOpening = BooleanArray(E1M1Map.MAP_WIDTH * E1M1Map.MAP_HEIGHT)
    private val entities = ArrayList<Entity>(32)

    // Player state
    var playerX = 5.5f
        private set
    var playerY = 1.8f
        private set
    var playerAngle = (PI / 2.0).toFloat() // Facing south down the starting Hangar aisle
        private set
    var health = 100
        private set
    var ammo = 50
        private set
    var hasShotgun = false
        private set
    var currentWeapon = WeaponType.PISTOL
        private set
    var isAutomapActive = false
        private set

    private var weaponCooldown = 0
    private var muzzleFlashTicks = 0
    private var damageFlashTicks = 0
    private var pickupFlashTicks = 0
    private var bobPhase = 0f
    private var tickCount = 0
    private var totalMonsters = 1
    private var killedMonsters = 0

    // Level transition / DOOM Screen Melt state
    private var isLevelComplete = false
    private var levelCompleteTimer = 0
    private var deathTimer = 0

    private val currentFrame = FloatArray(MATRIX_SIZE * MATRIX_SIZE)
    private val previousMeltFrame = FloatArray(MATRIX_SIZE * MATRIX_SIZE)
    private val outputFrame = FloatArray(MATRIX_SIZE * MATRIX_SIZE)
    private val meltColumnOffsets = IntArray(MATRIX_SIZE)
    private var meltActive = false

    private val zBuffer = FloatArray(MATRIX_SIZE)
    private val wallTopRow = IntArray(MATRIX_SIZE)
    private val wallBottomRow = IntArray(MATRIX_SIZE)
    private val rng = Random(19931210)

    private var usePressedLastTick = false
    private var weaponSwitchPressedLastTick = false
    private var mapTogglePressedLastTick = false

    init {
        resetLevel(triggerMelt = false)
    }

    fun toggleAutomap() {
        isAutomapActive = !isAutomapActive
    }

    fun resetLevel(triggerMelt: Boolean = true) {
        if (triggerMelt) {
            System.arraycopy(outputFrame, 0, previousMeltFrame, 0, outputFrame.size)
            startScreenMelt()
        } else {
            startScreenMelt(initialBoot = true)
        }

        mapGrid = E1M1Map.createGrid()
        doorOpenProgress.fill(0f)
        doorOpening.fill(false)
        entities.clear()

        var monsterCount = 0
        for (spawn in E1M1Map.createInitialEntities()) {
            val hp = when (spawn.type) {
                EntityType.ZOMBIEMAN -> 20
                EntityType.SERGEANT -> 30
                EntityType.IMP -> 60
                EntityType.BARREL -> 20
                else -> 1
            }
            if (spawn.type == EntityType.ZOMBIEMAN ||
                spawn.type == EntityType.SERGEANT ||
                spawn.type == EntityType.IMP
            ) {
                monsterCount++
            }
            entities.add(Entity(spawn.x, spawn.y, spawn.type, hp))
        }
        totalMonsters = max(1, monsterCount)
        killedMonsters = 0

        playerX = 5.5f
        playerY = 1.8f
        playerAngle = (PI / 2.0).toFloat()
        health = 100
        ammo = 50
        hasShotgun = false
        currentWeapon = WeaponType.PISTOL
        isAutomapActive = false
        weaponCooldown = 0
        muzzleFlashTicks = 0
        damageFlashTicks = 0
        pickupFlashTicks = 0
        bobPhase = 0f
        isLevelComplete = false
        levelCompleteTimer = 0
        deathTimer = 0
    }

    private fun startScreenMelt(initialBoot: Boolean = false) {
        if (initialBoot) {
            for (y in 0 until MATRIX_SIZE) {
                for (x in 0 until MATRIX_SIZE) {
                    val checker = ((x + y) and 1) == 0
                    previousMeltFrame[y * MATRIX_SIZE + x] = if (checker) 0.72f else 0.16f
                }
            }
            DoomSprites.drawText3x5(previousMeltFrame, "DOOM", 5, 10, 1.0f)
        }
        meltActive = true
        meltColumnOffsets[0] = -(rng.nextInt(0, 4))
        for (x in 1 until MATRIX_SIZE) {
            val delta = rng.nextInt(-1, 2)
            meltColumnOffsets[x] = (meltColumnOffsets[x - 1] + delta).coerceIn(-6, 0)
        }
    }

    /**
     * Advances the DOOM engine by 1 tick (~35 Hz classic DOOM rate) and renders
     * the resulting 25x25 frame into [outBuffer].
     */
    fun stepAndRender(input: DoomInputFrame, outBuffer: FloatArray): HapticEvent {
        tickCount++
        var haptic = HapticEvent.NONE

        val mapJustPressed = input.toggleMap && !mapTogglePressedLastTick
        mapTogglePressedLastTick = input.toggleMap
        if (mapJustPressed) {
            isAutomapActive = !isAutomapActive
        }

        if (isLevelComplete) {
            levelCompleteTimer++
            renderIntermissionScreen()
            if (levelCompleteTimer > 105 || (levelCompleteTimer > 25 && (input.fire || input.use))) {
                resetLevel(triggerMelt = true)
            }
        } else if (health <= 0) {
            deathTimer++
            render3DScene()
            for (i in currentFrame.indices) {
                currentFrame[i] = (currentFrame[i] * 0.45f).coerceIn(0f, 1f)
            }
            if (deathTimer > 28) {
                resetLevel(triggerMelt = true)
            }
        } else {
            haptic = updateGameplay(input)
            if (isAutomapActive) {
                renderAutomap25x25()
            } else {
                render3DScene()
            }
        }

        // Apply DOOM Screen Melt wipe if active
        if (meltActive) {
            var anyRemaining = false
            for (x in 0 until MATRIX_SIZE) {
                val offset = meltColumnOffsets[x]
                if (offset < MATRIX_SIZE) {
                    anyRemaining = true
                    for (y in 0 until MATRIX_SIZE) {
                        val idx = y * MATRIX_SIZE + x
                        if (y < offset) {
                            outputFrame[idx] = currentFrame[idx]
                        } else {
                            val srcY = (y - offset).coerceIn(0, MATRIX_SIZE - 1)
                            outputFrame[idx] = previousMeltFrame[srcY * MATRIX_SIZE + x]
                        }
                    }
                    meltColumnOffsets[x] += if (offset < 0) 1 else 2
                } else {
                    for (y in 0 until MATRIX_SIZE) {
                        val idx = y * MATRIX_SIZE + x
                        outputFrame[idx] = currentFrame[idx]
                    }
                }
            }
            if (!anyRemaining) {
                meltActive = false
            }
        } else {
            System.arraycopy(currentFrame, 0, outputFrame, 0, MATRIX_SIZE * MATRIX_SIZE)
        }

        System.arraycopy(outputFrame, 0, outBuffer, 0, MATRIX_SIZE * MATRIX_SIZE)
        return haptic
    }

    private fun updateGameplay(input: DoomInputFrame): HapticEvent {
        var haptic = HapticEvent.NONE

        // 1. Turning
        if (abs(input.turn) > 0.01f) {
            playerAngle += input.turn * 0.095f
            while (playerAngle < 0f) playerAngle += (PI * 2.0).toFloat()
            while (playerAngle >= (PI * 2.0).toFloat()) playerAngle -= (PI * 2.0).toFloat()
        }

        // 2. Movement & Strafing with wall-sliding collision
        val moveSpeed = 0.115f
        val forwardX = cos(playerAngle) * input.moveForward * moveSpeed
        val forwardY = sin(playerAngle) * input.moveForward * moveSpeed
        val strafeAngle = playerAngle + (PI / 2.0).toFloat()
        val strafeX = cos(strafeAngle) * input.strafe * moveSpeed
        val strafeY = sin(strafeAngle) * input.strafe * moveSpeed

        val dx = forwardX + strafeX
        val dy = forwardY + strafeY
        if (abs(dx) > 0.001f || abs(dy) > 0.001f) {
            bobPhase += 0.36f
            tryMovePlayer(dx, dy)
        }

        // Auto-open doors or trigger exit switch when pressing USE or walking directly up to a door
        val useJustPressed = input.use && !usePressedLastTick
        usePressedLastTick = input.use
        if (useJustPressed || input.moveForward > 0.55f) {
            if (tryInteractInFront(onlyDoors = !useJustPressed)) {
                haptic = HapticEvent.DOOR_OR_PICKUP
            }
        }

        // Update sliding doors
        for (i in doorOpenProgress.indices) {
            if (doorOpening[i] && doorOpenProgress[i] < 1f) {
                doorOpenProgress[i] = min(1f, doorOpenProgress[i] + 0.07f)
            }
        }

        // Weapon switching
        val switchTriggered = input.weaponSelect != 0
        if (switchTriggered && !weaponSwitchPressedLastTick) {
            when (input.weaponSelect) {
                1 -> currentWeapon = WeaponType.FIST
                2 -> currentWeapon = WeaponType.PISTOL
                3 -> if (hasShotgun) currentWeapon = WeaponType.SHOTGUN
                -1 -> {
                    currentWeapon = when (currentWeapon) {
                        WeaponType.FIST -> WeaponType.PISTOL
                        WeaponType.PISTOL -> if (hasShotgun) WeaponType.SHOTGUN else WeaponType.FIST
                        WeaponType.SHOTGUN -> WeaponType.FIST
                    }
                }
            }
        }
        weaponSwitchPressedLastTick = switchTriggered

        if (ammo <= 0 && currentWeapon != WeaponType.FIST) {
            currentWeapon = WeaponType.FIST
        }

        // Weapon firing
        if (weaponCooldown > 0) weaponCooldown--
        if (muzzleFlashTicks > 0) muzzleFlashTicks--
        if (damageFlashTicks > 0) damageFlashTicks--
        if (pickupFlashTicks > 0) pickupFlashTicks--

        if (input.fire && weaponCooldown == 0) {
            if (currentWeapon.ammoPerShot == 0 || ammo >= currentWeapon.ammoPerShot) {
                ammo = max(0, ammo - currentWeapon.ammoPerShot)
                weaponCooldown = currentWeapon.cooldownTicks
                muzzleFlashTicks = if (currentWeapon == WeaponType.FIST) 1 else 3
                val causedExplosion = fireHitscanWeapon(currentWeapon)
                haptic = when {
                    causedExplosion -> HapticEvent.EXPLOSION
                    currentWeapon == WeaponType.SHOTGUN -> HapticEvent.SHOT_SHOTGUN
                    else -> HapticEvent.SHOT_PISTOL
                }
            } else {
                currentWeapon = WeaponType.FIST
            }
        }

        // Acid pool floor hazard
        val pTileX = playerX.toInt().coerceIn(0, E1M1Map.MAP_WIDTH - 1)
        val pTileY = playerY.toInt().coerceIn(0, E1M1Map.MAP_HEIGHT - 1)
        if (mapGrid[pTileY * E1M1Map.MAP_WIDTH + pTileX] == E1M1Map.TILE_ACID && tickCount % 28 == 0) {
            applyPlayerDamage(4)
            haptic = HapticEvent.DAMAGE
        }

        val entityHaptic = updateEntities()
        if (entityHaptic != HapticEvent.NONE) {
            haptic = entityHaptic
        }

        return haptic
    }

    private fun tryMovePlayer(dx: Float, dy: Float) {
        val radius = 0.28f
        val nextX = playerX + dx
        if (isPassable(nextX + if (dx > 0) radius else -radius, playerY)) {
            playerX = nextX
        }
        val nextY = playerY + dy
        if (isPassable(playerX, nextY + if (dy > 0) radius else -radius)) {
            playerY = nextY
        }
    }

    private fun isPassable(wx: Float, wy: Float): Boolean {
        val tx = wx.toInt()
        val ty = wy.toInt()
        if (tx !in 0 until E1M1Map.MAP_WIDTH || ty !in 0 until E1M1Map.MAP_HEIGHT) return false
        val idx = ty * E1M1Map.MAP_WIDTH + tx
        return when (mapGrid[idx]) {
            E1M1Map.TILE_EMPTY, E1M1Map.TILE_ACID -> true
            E1M1Map.TILE_DOOR -> doorOpenProgress[idx] > 0.72f
            else -> false
        }
    }

    private fun tryInteractInFront(onlyDoors: Boolean): Boolean {
        val reach = 1.15f
        val checkX = (playerX + cos(playerAngle) * reach).toInt()
        val checkY = (playerY + sin(playerAngle) * reach).toInt()
        if (checkX !in 0 until E1M1Map.MAP_WIDTH || checkY !in 0 until E1M1Map.MAP_HEIGHT) return false
        val idx = checkY * E1M1Map.MAP_WIDTH + checkX
        val tile = mapGrid[idx]
        if (tile == E1M1Map.TILE_DOOR && !doorOpening[idx]) {
            doorOpening[idx] = true
            return true
        }
        if (!onlyDoors && tile == E1M1Map.TILE_EXIT_SWITCH) {
            isLevelComplete = true
            levelCompleteTimer = 0
            startScreenMelt()
            return true
        }
        return false
    }

    private fun fireHitscanWeapon(weapon: WeaponType): Boolean {
        var triggeredExplosion = false
        for (p in 0 until weapon.pellets) {
            val spread = if (weapon.pellets > 1) (p - 2) * 0.055f else 0f
            val rayAngle = playerAngle + spread
            val wallDist = castDistanceOnly(rayAngle)

            var closestTarget: Entity? = null
            var bestDist = min(weapon.maxRange, wallDist)

            for (entity in entities) {
                if (!entity.active) continue
                val isShootable = entity.type == EntityType.ZOMBIEMAN ||
                    entity.type == EntityType.SERGEANT ||
                    entity.type == EntityType.IMP ||
                    entity.type == EntityType.BARREL
                if (!isShootable || entity.state == EnemyState.DYING || entity.state == EnemyState.DEAD) continue

                val ex = entity.x - playerX
                val ey = entity.y - playerY
                val dist = hypot(ex, ey)
                if (dist >= bestDist || dist < 0.2f) continue

                val angleToEntity = atan2(ey, ex)
                val angleDiff = abs(normalizeAngleDiff(angleToEntity, rayAngle))
                val hitCone = (0.45f / dist).coerceIn(0.07f, 0.40f)
                if (angleDiff <= hitCone) {
                    bestDist = dist
                    closestTarget = entity
                }
            }

            if (closestTarget != null) {
                if (damageEntity(closestTarget, weapon.damagePerPellet)) {
                    triggeredExplosion = true
                }
            }
        }
        return triggeredExplosion
    }

    private fun damageEntity(target: Entity, amount: Int): Boolean {
        target.hp -= amount
        if (target.hp <= 0) {
            target.state = EnemyState.DYING
            target.stateTimer = 8
            if (target.type == EntityType.BARREL) {
                for (other in entities) {
                    if (other !== target && other.active && other.state != EnemyState.DEAD && other.state != EnemyState.DYING) {
                        val d = hypot(other.x - target.x, other.y - target.y)
                        if (d < 2.8f) {
                            other.hp -= 45
                            if (other.hp <= 0) {
                                other.state = EnemyState.DYING
                                other.stateTimer = 8
                                if (other.type == EntityType.ZOMBIEMAN || other.type == EntityType.SERGEANT || other.type == EntityType.IMP) {
                                    killedMonsters++
                                }
                            } else {
                                other.state = EnemyState.PAIN
                                other.stateTimer = 4
                            }
                        }
                    }
                }
                val playerDist = hypot(playerX - target.x, playerY - target.y)
                if (playerDist < 2.4f) {
                    applyPlayerDamage(((2.4f - playerDist) * 16f).toInt())
                }
                return true
            } else {
                killedMonsters++
            }
        } else if (target.type != EntityType.BARREL) {
            target.state = EnemyState.PAIN
            target.stateTimer = 4
        }
        return false
    }

    private fun updateEntities(): HapticEvent {
        var haptic = HapticEvent.NONE
        val spawnedFireballs = ArrayList<Entity>(2)

        for (entity in entities) {
            if (!entity.active) continue

            val distToPlayer = hypot(entity.x - playerX, entity.y - playerY)

            when (entity.type) {
                EntityType.PICKUP_SHOTGUN -> {
                    if (distToPlayer < 0.75f) {
                        entity.active = false
                        hasShotgun = true
                        ammo = min(99, ammo + 12)
                        currentWeapon = WeaponType.SHOTGUN
                        pickupFlashTicks = 3
                        haptic = HapticEvent.DOOR_OR_PICKUP
                    }
                    continue
                }
                EntityType.PICKUP_MEDKIT -> {
                    if (distToPlayer < 0.75f && health < 100) {
                        entity.active = false
                        health = min(100, health + 25)
                        pickupFlashTicks = 3
                        haptic = HapticEvent.DOOR_OR_PICKUP
                    }
                    continue
                }
                EntityType.PICKUP_AMMO -> {
                    if (distToPlayer < 0.75f && ammo < 99) {
                        entity.active = false
                        ammo = min(99, ammo + 15)
                        if (currentWeapon == WeaponType.FIST) {
                            currentWeapon = if (hasShotgun) WeaponType.SHOTGUN else WeaponType.PISTOL
                        }
                        pickupFlashTicks = 3
                        haptic = HapticEvent.DOOR_OR_PICKUP
                    }
                    continue
                }
                EntityType.FIREBALL -> {
                    entity.x += entity.vx
                    entity.y += entity.vy
                    if (!isPassable(entity.x, entity.y)) {
                        entity.active = false
                    } else if (hypot(entity.x - playerX, entity.y - playerY) < 0.45f) {
                        entity.active = false
                        applyPlayerDamage(12)
                        haptic = HapticEvent.DAMAGE
                    }
                    continue
                }
                EntityType.BARREL -> {
                    if (entity.state == EnemyState.DYING) {
                        entity.stateTimer--
                        if (entity.stateTimer <= 0) {
                            entity.state = EnemyState.DEAD
                            entity.active = false
                        }
                    }
                    continue
                }
                else -> {}
            }

            when (entity.state) {
                EnemyState.DEAD -> continue
                EnemyState.DYING -> {
                    entity.stateTimer--
                    if (entity.stateTimer <= 0) {
                        entity.state = EnemyState.DEAD
                    }
                }
                EnemyState.PAIN -> {
                    entity.stateTimer--
                    if (entity.stateTimer <= 0) {
                        entity.state = EnemyState.CHASE
                    }
                }
                EnemyState.ATTACK -> {
                    entity.stateTimer--
                    if (entity.stateTimer == 3) {
                        if (hasLineOfSight(entity.x, entity.y, playerX, playerY, distToPlayer)) {
                            if (entity.type == EntityType.IMP && distToPlayer > 1.6f) {
                                val ang = atan2(playerY - entity.y, playerX - entity.x)
                                spawnedFireballs.add(
                                    Entity(
                                        x = entity.x + cos(ang) * 0.4f,
                                        y = entity.y + sin(ang) * 0.4f,
                                        type = EntityType.FIREBALL,
                                        hp = 1,
                                        vx = cos(ang) * 0.22f,
                                        vy = sin(ang) * 0.22f
                                    )
                                )
                            } else {
                                val hitChance = (1.0f - (distToPlayer / 11.0f)).coerceIn(0.25f, 0.85f)
                                if (rng.nextFloat() < hitChance) {
                                    val dmg = if (entity.type == EntityType.SERGEANT) 11 else 7
                                    applyPlayerDamage(dmg)
                                    haptic = HapticEvent.DAMAGE
                                }
                            }
                        }
                    }
                    if (entity.stateTimer <= 0) {
                        entity.state = EnemyState.CHASE
                        entity.stateTimer = 14
                    }
                }
                EnemyState.IDLE -> {
                    if (distToPlayer < 9.5f && hasLineOfSight(entity.x, entity.y, playerX, playerY, distToPlayer)) {
                        entity.state = EnemyState.CHASE
                    }
                }
                EnemyState.CHASE -> {
                    if (entity.stateTimer > 0) entity.stateTimer--
                    val canSee = distToPlayer < 10.5f && hasLineOfSight(entity.x, entity.y, playerX, playerY, distToPlayer)
                    if (canSee && entity.stateTimer <= 0 && distToPlayer < 7.5f && rng.nextFloat() < 0.085f) {
                        entity.state = EnemyState.ATTACK
                        entity.stateTimer = 7
                    } else if (distToPlayer > 0.85f) {
                        val ang = atan2(playerY - entity.y, playerX - entity.x)
                        val speed = if (entity.type == EntityType.IMP) 0.052f else 0.042f
                        val nx = entity.x + cos(ang) * speed
                        val ny = entity.y + sin(ang) * speed
                        if (isPassable(nx, entity.y)) entity.x = nx
                        if (isPassable(entity.x, ny)) entity.y = ny
                    }
                }
            }
        }

        if (spawnedFireballs.isNotEmpty()) {
            entities.addAll(spawnedFireballs)
        }
        return haptic
    }

    private fun applyPlayerDamage(amount: Int) {
        health = max(0, health - amount)
        damageFlashTicks = 4
    }

    private fun hasLineOfSight(x0: Float, y0: Float, x1: Float, y1: Float, dist: Float): Boolean {
        val steps = (dist * 4f).toInt().coerceAtLeast(1)
        val dx = (x1 - x0) / steps
        val dy = (y1 - y0) / steps
        var cx = x0
        var cy = y0
        for (i in 0 until steps) {
            cx += dx
            cy += dy
            if (!isPassable(cx, cy)) return false
        }
        return true
    }

    private fun castDistanceOnly(rayAngle: Float): Float {
        val cosA = cos(rayAngle)
        val sinA = sin(rayAngle)
        var dist = 0.1f
        while (dist < 16f) {
            val wx = playerX + cosA * dist
            val wy = playerY + sinA * dist
            if (!isPassable(wx, wy)) return dist
            dist += 0.08f
        }
        return 16f
    }

    /**
     * Renders a high-contrast 3D Hangar scene (rows 0..21) + Micro-HUD (rows 22..24):
     * - Ceiling and standard floor are kept completely off (0.0f) so walls and enemies pop out.
     * - Walls have medium interior brightness (0.14..0.46) with bright structural top/bottom/corner edges (0.58..0.78).
     * - Enemies & items have a 1-pixel dark silhouette halo (0.0f) and peak LED brightness (0.85..1.00).
     */
    private fun render3DScene() {
        currentFrame.fill(0f)

        val horizonOffset = (sin(bobPhase) * 0.55f).toInt()
        val horizonRow = (VIEWPORT_HEIGHT / 2) + horizonOffset

        // 1. Cast 25 vertical columns across the 25x22 3D viewport
        for (x in 0 until MATRIX_SIZE) {
            val cameraX = (2f * x / (MATRIX_SIZE - 1).toFloat()) - 1f
            val rayAngle = playerAngle + cameraX * HALF_FOV
            val cosR = cos(rayAngle)
            val sinR = sin(rayAngle)

            var mapX = floor(playerX).toInt()
            var mapY = floor(playerY).toInt()

            val deltaDistX = if (abs(cosR) < 1e-5f) 1e5f else abs(1f / cosR)
            val deltaDistY = if (abs(sinR) < 1e-5f) 1e5f else abs(1f / sinR)

            val stepX: Int
            var sideDistX: Float
            if (cosR < 0) {
                stepX = -1
                sideDistX = (playerX - mapX) * deltaDistX
            } else {
                stepX = 1
                sideDistX = (mapX + 1.0f - playerX) * deltaDistX
            }

            val stepY: Int
            var sideDistY: Float
            if (sinR < 0) {
                stepY = -1
                sideDistY = (playerY - mapY) * deltaDistY
            } else {
                stepY = 1
                sideDistY = (mapY + 1.0f - playerY) * deltaDistY
            }

            var hitTile = E1M1Map.TILE_WALL_HANGAR
            var side = 0
            var steps = 0

            while (steps < 36) {
                if (sideDistX < sideDistY) {
                    sideDistX += deltaDistX
                    mapX += stepX
                    side = 0
                } else {
                    sideDistY += deltaDistY
                    mapY += stepY
                    side = 1
                }
                steps++

                if (mapX !in 0 until E1M1Map.MAP_WIDTH || mapY !in 0 until E1M1Map.MAP_HEIGHT) {
                    hitTile = E1M1Map.TILE_WALL_HANGAR
                    break
                }
                val idx = mapY * E1M1Map.MAP_WIDTH + mapX
                val tile = mapGrid[idx]
                if (tile != E1M1Map.TILE_EMPTY && tile != E1M1Map.TILE_ACID) {
                    if (tile == E1M1Map.TILE_DOOR && doorOpenProgress[idx] > 0.85f) {
                        continue
                    }
                    hitTile = tile
                    break
                }
            }

            val perpWallDist = max(
                0.15f,
                if (side == 0) (sideDistX - deltaDistX) else (sideDistY - deltaDistY)
            )
            zBuffer[x] = perpWallDist

            val lineHeight = (VIEWPORT_HEIGHT / perpWallDist).toInt().coerceAtLeast(2)
            val drawStart = (horizonRow - lineHeight / 2).coerceIn(0, VIEWPORT_HEIGHT - 1)
            val drawEnd = (horizonRow + lineHeight / 2).coerceIn(0, VIEWPORT_HEIGHT - 1)
            wallTopRow[x] = drawStart
            wallBottomRow[x] = drawEnd

            val wallX = if (side == 0) {
                playerY + perpWallDist * sinR
            } else {
                playerX + perpWallDist * cosR
            }.let { it - floor(it) }

            val distAttenuation = (1.0f / (1.0f + perpWallDist * 0.22f)).coerceIn(0.18f, 1.0f)
            val sideShade = if (side == 1) 0.72f else 1.0f

            for (y in 0 until VIEWPORT_HEIGHT) {
                val bayer = BAYER_2X2[y and 1][x and 1]
                val rawBrightness = when {
                    y < drawStart -> {
                        // Pitch-black ceiling so top wall silhouette is 100% crisp
                        0.0f
                    }
                    y <= drawEnd -> {
                        val isTopOrBottomEdge = (y == drawStart || y == drawEnd)
                        val texY = ((y - drawStart).toFloat() / max(1, drawEnd - drawStart)).coerceIn(0f, 1f)
                        when (hitTile) {
                            E1M1Map.TILE_PILLAR -> {
                                if (isTopOrBottomEdge) 0.78f else 0.52f * distAttenuation
                            }
                            E1M1Map.TILE_DOOR -> {
                                // Unmistakable DOOM door: bright outer frame + horizontal center lock line
                                val isDoorFrame = isTopOrBottomEdge || wallX < 0.14f || wallX > 0.86f || abs(texY - 0.5f) < 0.08f
                                if (isDoorFrame) 0.76f * distAttenuation else 0.22f * distAttenuation
                            }
                            E1M1Map.TILE_EXIT_SWITCH -> {
                                val inSwitchBox = wallX in 0.28f..0.72f && texY in 0.28f..0.72f
                                if (inSwitchBox) {
                                    if ((tickCount / 4) % 2 == 0) 1.0f else 0.65f
                                } else if (isTopOrBottomEdge) {
                                    0.68f
                                } else {
                                    0.32f * distAttenuation
                                }
                            }
                            E1M1Map.TILE_WALL_TECH -> {
                                if (isTopOrBottomEdge) {
                                    0.66f * distAttenuation
                                } else {
                                    0.38f * sideShade * distAttenuation + bayer
                                }
                            }
                            else -> {
                                // Architectural wireframe-accented Hangar wall:
                                // Bright top & bottom perspective lines + calm interior fill (capped at <=0.44f so enemies at 0.90..1.0f pop out!)
                                if (isTopOrBottomEdge) {
                                    (0.64f * distAttenuation).coerceIn(0.24f, 0.66f)
                                } else {
                                    (0.36f * sideShade * distAttenuation + bayer).coerceIn(0.12f, 0.42f)
                                }
                            }
                        }
                    }
                    else -> {
                        // Floor: completely dark except for pulsing Nukage Acid hazard tiles
                        val rowDelta = max(1, y - horizonRow)
                        val floorDist = (VIEWPORT_HEIGHT * 0.5f) / rowDelta
                        val fx = (playerX + cosR * floorDist).toInt()
                        val fy = (playerY + sinR * floorDist).toInt()
                        val isAcidFloor = fx in 0 until E1M1Map.MAP_WIDTH &&
                            fy in 0 until E1M1Map.MAP_HEIGHT &&
                            mapGrid[fy * E1M1Map.MAP_WIDTH + fx] == E1M1Map.TILE_ACID

                        if (isAcidFloor) {
                            val shimmer = ((x + y + (tickCount / 5)) and 1) == 0
                            if (shimmer) 0.32f else 0.14f
                        } else {
                            0.0f
                        }
                    }
                }

                currentFrame[y * MATRIX_SIZE + x] = quantizeLedBrightness(rawBrightness)
            }
        }

        // 1b. Highlight vertical depth-discontinuity corners between adjacent columns so corridors & doorways are ultra-readable
        for (x in 1 until MATRIX_SIZE) {
            if (abs(zBuffer[x] - zBuffer[x - 1]) > 0.65f) {
                val nearerCol = if (zBuffer[x] < zBuffer[x - 1]) x else x - 1
                val edgeBrightness = (0.62f / (1f + zBuffer[nearerCol] * 0.18f)).coerceIn(0.25f, 0.65f)
                for (y in wallTopRow[nearerCol]..wallBottomRow[nearerCol]) {
                    if (y in 0 until VIEWPORT_HEIGHT) {
                        currentFrame[y * MATRIX_SIZE + nearerCol] =
                            max(currentFrame[y * MATRIX_SIZE + nearerCol], quantizeLedBrightness(edgeBrightness))
                    }
                }
            }
        }

        // 2. Render Z-buffered sprites with a 1-pixel Black Silhouette Halo so enemies never blend into walls
        val sortedEntities = entities
            .filter { it.active }
            .sortedByDescending { (it.x - playerX) * (it.x - playerX) + (it.y - playerY) * (it.y - playerY) }

        var enemyInCrosshair = false

        for (entity in sortedEntities) {
            val relX = entity.x - playerX
            val relY = entity.y - playerY
            val dist = sqrt(relX * relX + relY * relY)
            if (dist < 0.22f || dist > 14.0f) continue

            val angleToSprite = normalizeAngleDiff(atan2(relY, relX), playerAngle)
            val isLiveEnemy = (entity.type == EntityType.ZOMBIEMAN ||
                entity.type == EntityType.SERGEANT ||
                entity.type == EntityType.IMP) &&
                entity.state != EnemyState.DEAD &&
                entity.state != EnemyState.DYING

            // Top-row directional threat radar blip for visible live enemies
            if (isLiveEnemy && abs(angleToSprite) <= HALF_FOV * 1.15f && dist < 11.0f) {
                val radarX = (((angleToSprite / HALF_FOV) * 0.5f + 0.5f) * (MATRIX_SIZE - 1))
                    .roundToInt()
                    .coerceIn(1, MATRIX_SIZE - 2)
                if (dist < zBuffer[radarX] + 0.5f) {
                    currentFrame[radarX] = 0.95f
                }
            }

            if (abs(angleToSprite) > HALF_FOV + 0.35f) continue

            val screenX = (((angleToSprite / HALF_FOV) * 0.5f + 0.5f) * (MATRIX_SIZE - 1)).roundToInt()
            val sprite = selectEntitySprite(entity)
            val scale = (VIEWPORT_HEIGHT / dist).coerceIn(3.0f, 18.0f)
            val drawH = max(3, (scale * (sprite.height / 8.5f)).roundToInt())
            val drawW = max(3, (scale * (sprite.width / 8.5f)).roundToInt())

            val startY = horizonRow - drawH / 2 + if (entity.state == EnemyState.DEAD) drawH / 3 else 0
            val startX = screenX - drawW / 2
            // Keep enemies & items at high contrast (0.78..1.00) well above wall interior brightness (<=0.42)
            val spriteShade = (1.0f / (1.0f + dist * 0.06f)).coerceIn(0.78f, 1.0f)

            if (isLiveEnemy && screenX in 10..14 && dist < zBuffer[MATRIX_SIZE / 2]) {
                enemyInCrosshair = true
            }

            // Pass A: Carve a 1-pixel dark silhouette halo (0.0f) around opaque sprite pixels
            for (sx in 0 until drawW) {
                val px = startX + sx
                if (px !in 0 until MATRIX_SIZE || dist >= zBuffer[px]) continue
                val texX = (sx * sprite.width / drawW).coerceIn(0, sprite.width - 1)
                for (sy in 0 until drawH) {
                    val py = startY + sy
                    if (py !in 0 until VIEWPORT_HEIGHT) continue
                    val texY = (sy * sprite.height / drawH).coerceIn(0, sprite.height - 1)
                    if (sprite.pixels[texY * sprite.width + texX] > 0.05f) {
                        for (oy in -1..1) {
                            val hy = py + oy
                            if (hy !in 0 until VIEWPORT_HEIGHT) continue
                            for (ox in -1..1) {
                                val hx = px + ox
                                if (hx in 0 until MATRIX_SIZE) {
                                    currentFrame[hy * MATRIX_SIZE + hx] = 0f
                                }
                            }
                        }
                    }
                }
            }

            // Pass B: Draw bright high-contrast sprite pixels inside the dark silhouette moat
            for (sx in 0 until drawW) {
                val px = startX + sx
                if (px !in 0 until MATRIX_SIZE || dist >= zBuffer[px]) continue
                val texX = (sx * sprite.width / drawW).coerceIn(0, sprite.width - 1)
                for (sy in 0 until drawH) {
                    val py = startY + sy
                    if (py !in 0 until VIEWPORT_HEIGHT) continue
                    val texY = (sy * sprite.height / drawH).coerceIn(0, sprite.height - 1)
                    val pixelVal = sprite.pixels[texY * sprite.width + texX]
                    if (pixelVal > 0.05f) {
                        currentFrame[py * MATRIX_SIZE + px] =
                            quantizeLedBrightness((pixelVal * spriteShade).coerceIn(0.68f, 1.0f))
                    }
                }
            }
        }

        // 3. Render compact First-Person DOOM Weapon at bottom center of 3D viewport
        renderFirstPersonWeapon()

        // 4. Center crosshair dot (pulses at peak 1.0f when an enemy is in the line of fire)
        val centerIdx = (VIEWPORT_HEIGHT / 2) * MATRIX_SIZE + (MATRIX_SIZE / 2)
        currentFrame[centerIdx] = if (enemyInCrosshair) 1.0f else max(currentFrame[centerIdx], 0.72f)

        // 5. Render Micro-HUD on bottom 3 rows (rows 22..24)
        renderMicroHud()

        // 6. Global flash effects
        if (muzzleFlashTicks > 0) {
            for (i in 0 until MATRIX_SIZE * VIEWPORT_HEIGHT) {
                if (currentFrame[i] > 0f) {
                    currentFrame[i] = quantizeLedBrightness(currentFrame[i] + 0.14f)
                }
            }
        }
        if (damageFlashTicks > 0) {
            for (y in 0 until MATRIX_SIZE) {
                for (x in 0 until MATRIX_SIZE) {
                    if (x == 0 || x == MATRIX_SIZE - 1 || y == 0 || y == MATRIX_SIZE - 1) {
                        currentFrame[y * MATRIX_SIZE + x] = 1.0f
                    }
                }
            }
        } else if (pickupFlashTicks > 0) {
            for (x in 0 until MATRIX_SIZE) {
                currentFrame[(VIEWPORT_HEIGHT - 1) * MATRIX_SIZE + x] = 0.9f
            }
        }
    }

    /**
     * Classic DOOM Top-Down Automap view rendered directly on the 25x25 Glyph Matrix:
     * - Shows the entire 24x24 E1M1 Hangar layout with clear walls, doors, exit switch,
     *   pulsing enemy positions, and the player's position & facing direction vector!
     */
    private fun renderAutomap25x25() {
        currentFrame.fill(0f)

        // Render 24x22 map area on rows 0..21
        val camOffsetY = (playerY.roundToInt() - 11).coerceIn(0, E1M1Map.MAP_HEIGHT - VIEWPORT_HEIGHT)

        for (ry in 0 until VIEWPORT_HEIGHT) {
            val my = (ry + camOffsetY).coerceIn(0, E1M1Map.MAP_HEIGHT - 1)
            for (mx in 0 until E1M1Map.MAP_WIDTH) {
                val idx = my * E1M1Map.MAP_WIDTH + mx
                val tile = mapGrid[idx]
                val brightness = when (tile) {
                    E1M1Map.TILE_WALL_HANGAR, E1M1Map.TILE_WALL_TECH -> 0.34f
                    E1M1Map.TILE_PILLAR -> 0.55f
                    E1M1Map.TILE_DOOR -> if (doorOpenProgress[idx] > 0.7f) 0.18f else 0.72f
                    E1M1Map.TILE_EXIT_SWITCH -> if ((tickCount / 3) % 2 == 0) 1.0f else 0.45f
                    E1M1Map.TILE_ACID -> 0.12f
                    else -> 0.0f
                }
                currentFrame[ry * MATRIX_SIZE + mx] = brightness
            }
        }

        // Draw live enemies & barrels on Automap
        val blinkEnemy = (tickCount / 4) % 2 == 0
        for (entity in entities) {
            if (!entity.active || entity.state == EnemyState.DEAD || entity.state == EnemyState.DYING) continue
            val ex = entity.x.toInt().coerceIn(0, E1M1Map.MAP_WIDTH - 1)
            val ey = (entity.y.toInt() - camOffsetY)
            if (ey in 0 until VIEWPORT_HEIGHT) {
                val isMonster = entity.type == EntityType.ZOMBIEMAN ||
                    entity.type == EntityType.SERGEANT ||
                    entity.type == EntityType.IMP
                if (isMonster) {
                    currentFrame[ey * MATRIX_SIZE + ex] = if (blinkEnemy) 0.95f else 0.62f
                } else if (entity.type == EntityType.PICKUP_SHOTGUN) {
                    currentFrame[ey * MATRIX_SIZE + ex] = 0.78f
                }
            }
        }

        // Draw player position (peak 1.0f) and facing direction pointer
        val px = playerX.toInt().coerceIn(0, E1M1Map.MAP_WIDTH - 1)
        val py = (playerY.toInt() - camOffsetY).coerceIn(0, VIEWPORT_HEIGHT - 1)
        currentFrame[py * MATRIX_SIZE + px] = 1.0f

        val lookX = (playerX + cos(playerAngle) * 1.15f).roundToInt().coerceIn(0, MATRIX_SIZE - 1)
        val lookY = ((playerY + sin(playerAngle) * 1.15f).roundToInt() - camOffsetY).coerceIn(0, VIEWPORT_HEIGHT - 1)
        currentFrame[lookY * MATRIX_SIZE + lookX] = 0.85f

        renderMicroHud()
    }

    private fun selectEntitySprite(entity: Entity): DoomSprites.SpriteBitmap {
        val walkFrameA = ((tickCount / 6) and 1) == 0
        return when (entity.type) {
            EntityType.ZOMBIEMAN -> when (entity.state) {
                EnemyState.PAIN -> DoomSprites.ENEMY_PAIN
                EnemyState.DYING -> DoomSprites.ENEMY_DYING
                EnemyState.DEAD -> DoomSprites.ENEMY_DEAD
                EnemyState.ATTACK -> DoomSprites.ZOMBIE_ATTACK
                else -> if (walkFrameA) DoomSprites.ZOMBIE_WALK_A else DoomSprites.ZOMBIE_WALK_B
            }
            EntityType.SERGEANT -> when (entity.state) {
                EnemyState.PAIN -> DoomSprites.ENEMY_PAIN
                EnemyState.DYING -> DoomSprites.ENEMY_DYING
                EnemyState.DEAD -> DoomSprites.ENEMY_DEAD
                EnemyState.ATTACK -> DoomSprites.SERGEANT_ATTACK
                else -> DoomSprites.SERGEANT_WALK_A
            }
            EntityType.IMP -> when (entity.state) {
                EnemyState.PAIN -> DoomSprites.ENEMY_PAIN
                EnemyState.DYING -> DoomSprites.ENEMY_DYING
                EnemyState.DEAD -> DoomSprites.ENEMY_DEAD
                EnemyState.ATTACK -> DoomSprites.IMP_ATTACK
                else -> if (walkFrameA) DoomSprites.IMP_WALK_A else DoomSprites.IMP_WALK_B
            }
            EntityType.BARREL -> when (entity.state) {
                EnemyState.DYING -> DoomSprites.BARREL_EXPLODE
                else -> if (walkFrameA) DoomSprites.BARREL_IDLE_A else DoomSprites.BARREL_IDLE_B
            }
            EntityType.FIREBALL -> DoomSprites.FIREBALL_SPRITE
            EntityType.PICKUP_SHOTGUN -> DoomSprites.PICKUP_SHOTGUN_SPRITE
            EntityType.PICKUP_MEDKIT -> DoomSprites.PICKUP_MEDKIT_SPRITE
            EntityType.PICKUP_AMMO -> DoomSprites.PICKUP_AMMO_SPRITE
        }
    }

    private fun renderFirstPersonWeapon() {
        val weaponSprite = when (currentWeapon) {
            WeaponType.FIST -> if (weaponCooldown > 4) DoomSprites.WEAPON_FIST_PUNCH else DoomSprites.WEAPON_FIST_IDLE
            WeaponType.PISTOL -> if (weaponCooldown > 5) DoomSprites.WEAPON_PISTOL_FIRE else DoomSprites.WEAPON_PISTOL_IDLE
            WeaponType.SHOTGUN -> when {
                weaponCooldown > 13 -> DoomSprites.WEAPON_SHOTGUN_FIRE
                weaponCooldown in 6..12 -> DoomSprites.WEAPON_SHOTGUN_PUMP
                else -> DoomSprites.WEAPON_SHOTGUN_IDLE
            }
        }

        val bobX = (cos(bobPhase * 0.5f) * 1.0f).toInt()
        val bobY = abs(sin(bobPhase) * 0.9f).toInt()
        val startX = (MATRIX_SIZE - weaponSprite.width) / 2 + bobX
        val startY = VIEWPORT_HEIGHT - weaponSprite.height + bobY

        for (sy in 0 until weaponSprite.height) {
            val py = startY + sy
            if (py !in 0 until VIEWPORT_HEIGHT) continue
            for (sx in 0 until weaponSprite.width) {
                val px = startX + sx
                if (px !in 0 until MATRIX_SIZE) continue
                val v = weaponSprite.pixels[sy * weaponSprite.width + sx]
                if (v > 0.01f) {
                    currentFrame[py * MATRIX_SIZE + px] = quantizeLedBrightness(v)
                }
            }
        }
    }

    private fun renderMicroHud() {
        for (x in 0 until MATRIX_SIZE) {
            currentFrame[22 * MATRIX_SIZE + x] = 0.22f
        }
        currentFrame[22 * MATRIX_SIZE + 10] = if (currentWeapon == WeaponType.FIST) 1.0f else 0.35f
        currentFrame[22 * MATRIX_SIZE + 12] = if (currentWeapon == WeaponType.PISTOL) 1.0f else 0.35f
        currentFrame[22 * MATRIX_SIZE + 14] = if (currentWeapon == WeaponType.SHOTGUN) 1.0f else if (hasShotgun) 0.45f else 0.15f

        val healthCols = ((health / 100f) * 10f).toInt().coerceIn(0, 10)
        for (x in 0 until 10) {
            val lit = x < healthCols
            val lowHpBlink = health <= 25 && (tickCount / 4) % 2 == 0
            val val23 = if (lit) (if (lowHpBlink) 0.4f else 0.92f) else 0.12f
            val val24 = if (lit) (if (lowHpBlink) 0.4f else 0.76f) else 0.08f
            currentFrame[23 * MATRIX_SIZE + x] = val23
            currentFrame[24 * MATRIX_SIZE + x] = val24
        }

        val faceBrightness = when {
            damageFlashTicks > 0 -> 1.0f
            muzzleFlashTicks > 0 -> 0.95f
            isAutomapActive -> 0.90f
            else -> 0.65f
        }
        currentFrame[23 * MATRIX_SIZE + 11] = faceBrightness
        currentFrame[23 * MATRIX_SIZE + 13] = faceBrightness
        currentFrame[24 * MATRIX_SIZE + 12] = faceBrightness

        val ammoCols = ((ammo / 50f).coerceAtMost(1f) * 10f).toInt().coerceIn(0, 10)
        for (i in 0 until 10) {
            val x = 24 - i
            val lit = i < ammoCols
            currentFrame[23 * MATRIX_SIZE + x] = if (lit) 0.88f else 0.12f
            currentFrame[24 * MATRIX_SIZE + x] = if (lit) 0.68f else 0.08f
        }
    }

    private fun renderIntermissionScreen() {
        currentFrame.fill(0.0f)
        for (i in 0 until MATRIX_SIZE) {
            currentFrame[i] = 0.55f
            currentFrame[(MATRIX_SIZE - 1) * MATRIX_SIZE + i] = 0.55f
            currentFrame[i * MATRIX_SIZE] = 0.55f
            currentFrame[i * MATRIX_SIZE + (MATRIX_SIZE - 1)] = 0.55f
        }
        DoomSprites.drawText3x5(currentFrame, "E1M1", 5, 4, 1.0f)
        val killPct = ((killedMonsters * 100) / max(1, totalMonsters)).coerceIn(0, 100)
        val pctStr = "$killPct%"
        val startX = ((MATRIX_SIZE - pctStr.length * 4) / 2).coerceAtLeast(2)
        DoomSprites.drawText3x5(currentFrame, pctStr, startX, 13, 0.92f)
    }

    private fun quantizeLedBrightness(value: Float): Float {
        val clamped = value.coerceIn(0f, 1f)
        if (clamped < 0.07f) return 0f
        return ((clamped * 15f).toInt() / 15f).coerceIn(0.08f, 1.0f)
    }

    private fun normalizeAngleDiff(a: Float, b: Float): Float {
        var d = (a - b) % (PI * 2.0).toFloat()
        if (d > PI) d -= (PI * 2.0).toFloat()
        if (d < -PI) d += (PI * 2.0).toFloat()
        return d
    }
}
