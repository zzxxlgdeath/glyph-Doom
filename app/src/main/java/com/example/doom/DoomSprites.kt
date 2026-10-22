package com.example.doom

enum class EntityType {
    ZOMBIEMAN,
    SERGEANT,
    IMP,
    BARREL,
    FIREBALL,
    PICKUP_SHOTGUN,
    PICKUP_MEDKIT,
    PICKUP_AMMO
}

enum class EnemyState {
    IDLE,
    CHASE,
    ATTACK,
    PAIN,
    DYING,
    DEAD
}

enum class WeaponType(
    val id: Int,
    val cooldownTicks: Int,
    val ammoPerShot: Int,
    val pellets: Int,
    val damagePerPellet: Int,
    val maxRange: Float
) {
    FIST(1, cooldownTicks = 10, ammoPerShot = 0, pellets = 1, damagePerPellet = 22, maxRange = 1.65f),
    PISTOL(2, cooldownTicks = 9, ammoPerShot = 1, pellets = 1, damagePerPellet = 20, maxRange = 14.0f),
    SHOTGUN(3, cooldownTicks = 18, ammoPerShot = 1, pellets = 5, damagePerPellet = 16, maxRange = 11.5f)
}

/**
 * High-contrast multi-level LED sprite definitions tuned specifically for
 * readability on a 25x25 matrix:
 * '#' = 1.00f (Peak LED white — eyes, weapons, core highlights)
 * '@' = 0.88f (Bright enemy body silhouette)
 * '+' = 0.68f (Secondary armor/limb shading)
 * '-' = 0.45f (Subtle detail)
 */
object DoomSprites {

    data class SpriteBitmap(
        val width: Int,
        val height: Int,
        val pixels: FloatArray
    )

    private fun parseSprite(rows: Array<String>): SpriteBitmap {
        val h = rows.size
        val w = rows[0].length
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val row = rows[y]
            for (x in 0 until w) {
                out[y * w + x] = when (row[x]) {
                    '#' -> 1.00f
                    '@' -> 0.88f
                    '+' -> 0.68f
                    '-' -> 0.45f
                    else -> 0.0f
                }
            }
        }
        return SpriteBitmap(w, h, out)
    }

    // Zombieman (7x9) - High-contrast helmet, glowing visor eyes, rifle across chest, distinct legs
    val ZOMBIE_WALK_A = parseSprite(
        arrayOf(
            "..@@@..",
            ".@#.#@.",
            "..@@@..",
            ".@@@@@.",
            "@#####@",
            ".@@@@@.",
            "..@@@..",
            ".@@.@@.",
            "@@...@@"
        )
    )

    val ZOMBIE_WALK_B = parseSprite(
        arrayOf(
            "..@@@..",
            ".@#.#@.",
            "..@@@..",
            ".@@@@@.",
            "@#####@",
            ".@@@@@.",
            "..@@@..",
            "..@@@..",
            ".@@.@@."
        )
    )

    val ZOMBIE_ATTACK = parseSprite(
        arrayOf(
            "..@@@..",
            ".@#.#@.",
            "..#####",
            ".@#####",
            ".@#####",
            ".@@@@@.",
            "..@@@..",
            ".@@.@@.",
            "@@...@@"
        )
    )

    // Shotgun Sergeant (7x9) - Wide armored shoulders + bright central shotgun flash
    val SERGEANT_WALK_A = parseSprite(
        arrayOf(
            ".@@@@@.",
            "@#.@.#@",
            ".@@@@@.",
            "@@@@@@@",
            "###@###",
            "@@@@@@@",
            ".@@@@@.",
            ".@@.@@.",
            "@@...@@"
        )
    )

    val SERGEANT_ATTACK = parseSprite(
        arrayOf(
            ".@@@@@.",
            "@#.@.#@",
            ".######",
            "#######",
            "#######",
            "@@@@@@@",
            ".@@@@@.",
            ".@@.@@.",
            "@@...@@"
        )
    )

    // Imp (7x9) - Unmistakable horned silhouette, glowing eyes, raised claws
    val IMP_WALK_A = parseSprite(
        arrayOf(
            "#..@..#",
            ".##.##.",
            ".@@@@@.",
            "#@###@#",
            "@@@@@@@",
            ".@@@@@.",
            "..@@@..",
            ".@@.@@.",
            "@.....@"
        )
    )

    val IMP_WALK_B = parseSprite(
        arrayOf(
            "#..@..#",
            ".##.##.",
            ".@@@@@.",
            "@@@@@@@",
            ".@###@.",
            ".@@@@@.",
            "..@@@..",
            "..@.@..",
            ".@...@."
        )
    )

    val IMP_ATTACK = parseSprite(
        arrayOf(
            "#..#..#",
            ".#####.",
            "#######",
            "###@###",
            "#######",
            ".@@@@@.",
            "..@@@..",
            ".@@.@@.",
            "@.....@"
        )
    )

    val ENEMY_PAIN = parseSprite(
        arrayOf(
            ".#####.",
            "#######",
            "#######",
            "#######",
            "#######",
            ".#####.",
            "..###..",
            ".##.##.",
            "##...##"
        )
    )

    val ENEMY_DYING = parseSprite(
        arrayOf(
            ".......",
            ".......",
            "..###..",
            ".#####.",
            "#######",
            ".@@@@@.",
            "@@@@@@@",
            "@@@@@@@",
            ".+++++."
        )
    )

    val ENEMY_DEAD = parseSprite(
        arrayOf(
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            ".+@@@+.",
            "++@@@++"
        )
    )

    // Explosive Nukage Barrel (5x7) - Distinct striped cylinder
    val BARREL_IDLE_A = parseSprite(
        arrayOf(
            ".###.",
            "@###@",
            "@+.+@",
            "@###@",
            "@+.+@",
            "@###@",
            ".@@@."
        )
    )

    val BARREL_IDLE_B = parseSprite(
        arrayOf(
            ".###.",
            "@+.+@",
            "@###@",
            "@+.+@",
            "@###@",
            "@###@",
            ".@@@."
        )
    )

    val BARREL_EXPLODE = parseSprite(
        arrayOf(
            "#.#.#.#",
            ".#####.",
            "#######",
            "#######",
            "#######",
            ".#####.",
            "#.#.#.#"
        )
    )

    // Imp Fireball Projectile (5x5)
    val FIREBALL_SPRITE = parseSprite(
        arrayOf(
            ".###.",
            "#####",
            "#####",
            "#####",
            ".###."
        )
    )

    // Pickups (5x5)
    val PICKUP_SHOTGUN_SPRITE = parseSprite(
        arrayOf(
            ".....",
            "..###",
            "#####",
            "@@...",
            "....."
        )
    )

    val PICKUP_MEDKIT_SPRITE = parseSprite(
        arrayOf(
            ".@@@.",
            "@.#.@",
            "@###@",
            "@.#.@",
            ".@@@."
        )
    )

    val PICKUP_AMMO_SPRITE = parseSprite(
        arrayOf(
            ".....",
            ".###.",
            "@@@@@",
            "@@@@@",
            "....."
        )
    )

    // Compact First-Person Weapons (7x5 so they never obstruct the center 3D view)
    val WEAPON_FIST_IDLE = parseSprite(
        arrayOf(
            ".......",
            ".......",
            "...@@..",
            "..@@@@.",
            "..@@@.."
        )
    )

    val WEAPON_FIST_PUNCH = parseSprite(
        arrayOf(
            "..###..",
            ".#####.",
            "..@@@@.",
            "...@@@.",
            "...@@.."
        )
    )

    val WEAPON_PISTOL_IDLE = parseSprite(
        arrayOf(
            "...#...",
            "..###..",
            "..@@@..",
            "..@@@..",
            ".+++++."
        )
    )

    val WEAPON_PISTOL_FIRE = parseSprite(
        arrayOf(
            ".#.#.#.",
            "..###..",
            ".#####.",
            "..@@@..",
            ".+++++."
        )
    )

    val WEAPON_SHOTGUN_IDLE = parseSprite(
        arrayOf(
            "...#...",
            "..###..",
            "..#@#..",
            ".@@@@@.",
            ".+++++."
        )
    )

    val WEAPON_SHOTGUN_FIRE = parseSprite(
        arrayOf(
            "#.##.#.",
            ".#####.",
            "#######",
            ".@@@@@.",
            ".+++++."
        )
    )

    val WEAPON_SHOTGUN_PUMP = parseSprite(
        arrayOf(
            ".......",
            ".###...",
            ".#####.",
            "..@@@@@",
            ".+++++."
        )
    )

    // 3x5 micro pixel font
    private val FONT_3X5 = mapOf(
        '0' to intArrayOf(7, 5, 5, 5, 7),
        '1' to intArrayOf(2, 6, 2, 2, 7),
        '2' to intArrayOf(7, 1, 7, 4, 7),
        '3' to intArrayOf(7, 1, 7, 1, 7),
        '4' to intArrayOf(5, 5, 7, 1, 1),
        '5' to intArrayOf(7, 4, 7, 1, 7),
        '6' to intArrayOf(7, 4, 7, 5, 7),
        '7' to intArrayOf(7, 1, 1, 2, 2),
        '8' to intArrayOf(7, 5, 7, 5, 7),
        '9' to intArrayOf(7, 5, 7, 1, 7),
        'E' to intArrayOf(7, 4, 7, 4, 7),
        'M' to intArrayOf(5, 7, 5, 5, 5),
        'K' to intArrayOf(5, 5, 6, 5, 5),
        'O' to intArrayOf(7, 5, 5, 5, 7),
        'D' to intArrayOf(6, 5, 5, 5, 6),
        '%' to intArrayOf(5, 1, 2, 4, 5)
    )

    fun drawText3x5(
        buffer: FloatArray,
        text: String,
        startX: Int,
        startY: Int,
        brightness: Float = 1.0f
    ) {
        var cursorX = startX
        for (ch in text) {
            val rows = FONT_3X5[ch]
            if (rows != null) {
                for (ry in 0 until 5) {
                    val py = startY + ry
                    if (py in 0 until 25) {
                        val mask = rows[ry]
                        for (rx in 0 until 3) {
                            val px = cursorX + rx
                            if (px in 0 until 25 && ((mask shr (2 - rx)) and 1) == 1) {
                                buffer[py * 25 + px] = brightness
                            }
                        }
                    }
                }
            }
            cursorX += 4
        }
    }
}
