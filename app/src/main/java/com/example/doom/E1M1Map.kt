package com.example.doom

/**
 * Recreates the iconic 1993 DOOM E1M1 ("Hangar") layout optimized for
 * fast raycasting and recognizable landmarks on the 25x25 Glyph Matrix:
 * - Room 1: Starting Hangar with twin pillars, side window view, and armor alcove
 * - Door 1: Sliding tech door into the Computer Room
 * - Room 2: Zig-zag Nukage/Acid Bridge room with explosive barrels and Imps
 * - Door 2: Passage to the dark Imp corridor & secret Shotgun alcove
 * - Door 3 & Exit Chamber: Final room with guarding Sergeant/Imp and the EXIT switch
 */
object E1M1Map {
    const val MAP_WIDTH = 24
    const val MAP_HEIGHT = 24

    const val TILE_EMPTY = 0
    const val TILE_WALL_HANGAR = 1
    const val TILE_WALL_TECH = 2
    const val TILE_PILLAR = 3
    const val TILE_DOOR = 4
    const val TILE_ACID = 5 // Walkable floor with pulsing nukage hazard
    const val TILE_EXIT_SWITCH = 6
    const val TILE_WALL_SECRET = 7

    // Legend for initial ASCII map construction:
    // '#' = Hangar Wall (1)
    // '=' = Tech Panel Wall (2)
    // 'P' = High-contrast Pillar (3)
    // 'D' = Sliding Door (4)
    // '~' = Nukage Acid Pool (5 - walkable, glowing floor)
    // 'X' = Exit Switch Wall (6)
    // '.' = Empty Floor (0)
    private val RAW_ROWS = arrayOf(
        "########################", // 0
        "#..........#===X===#...#", // 1  Exit room top (Switch at x=15, y=1)
        "#..P....P..#.......#...#", // 2
        "#..........#...I...#...#", // 3  Exit room Imp
        "#....Z.....#.......#...#", // 4
        "#..........####D####...#", // 5  Door into Exit chamber (x=15, y=5)
        "#..P....P..#.......#...#", // 6
        "#..........#..B.S..#...#", // 7  Pre-exit hallway (Barrel + Sergeant)
        "#####D######.......#...#", // 8  Door from Hangar to Computer Hall
        "#..........#.......#...#", // 9
        "#..==..==..D.......D..W#", // 10 Doors to Computer Hall & Secret Shotgun Room ('W')
        "#..==..==..#.......#...#", // 11
        "#..........#####D###...#", // 12 Door to Zig-Zag Acid Room
        "#####D######~~~~...~~~~#", // 13 Zig-Zag Nukage Room starts
        "#..........#~~~~...~~~~#", // 14
        "#..M....A..#~~~...B.~~~#", // 15
        "############~~~..I..~~~#", // 16
        "#..........#~~~~...~~~~#", // 17
        "#..S....B..D...~~~~~...#", // 18 Side room with Sergeant & Barrel
        "#..........#...~~~~~...#", // 19
        "#..........#~~~..Z..~~~#", // 20
        "#..M....A..#~~~~...~~~~#", // 21
        "#..........#~~~~...~~~~#", // 22
        "########################"  // 23
    )

    fun createGrid(): IntArray {
        val grid = IntArray(MAP_WIDTH * MAP_HEIGHT)
        for (y in 0 until MAP_HEIGHT) {
            val row = RAW_ROWS[y]
            for (x in 0 until MAP_WIDTH) {
                grid[y * MAP_WIDTH + x] = when (row[x]) {
                    '#' -> TILE_WALL_HANGAR
                    '=' -> TILE_WALL_TECH
                    'P' -> TILE_PILLAR
                    'D' -> TILE_DOOR
                    '~' -> TILE_ACID
                    'X' -> TILE_EXIT_SWITCH
                    else -> TILE_EMPTY
                }
            }
        }
        return grid
    }

    data class SpawnDef(
        val x: Float,
        val y: Float,
        val type: EntityType
    )

    fun createInitialEntities(): List<SpawnDef> = listOf(
        // Starting Hangar enemies & barrels
        SpawnDef(4.2f, 5.5f, EntityType.ZOMBIEMAN),
        SpawnDef(8.5f, 3.5f, EntityType.ZOMBIEMAN),
        SpawnDef(2.5f, 6.5f, EntityType.BARREL),
        SpawnDef(8.5f, 6.5f, EntityType.PICKUP_AMMO),

        // Computer Server Hallway (south of Hangar)
        SpawnDef(5.5f, 10.5f, EntityType.ZOMBIEMAN),
        SpawnDef(9.5f, 11.5f, EntityType.SERGEANT),
        SpawnDef(2.5f, 9.5f, EntityType.BARREL),
        SpawnDef(3.5f, 15.5f, EntityType.PICKUP_MEDKIT),
        SpawnDef(8.5f, 15.5f, EntityType.PICKUP_AMMO),

        // Central hub & Secret Shotgun alcove (east)
        SpawnDef(15.5f, 10.5f, EntityType.IMP),
        SpawnDef(21.5f, 10.5f, EntityType.PICKUP_SHOTGUN),
        SpawnDef(21.5f, 8.5f, EntityType.PICKUP_MEDKIT),
        SpawnDef(21.5f, 12.5f, EntityType.PICKUP_AMMO),

        // Zig-Zag Nukage Bridge Room (southeast)
        SpawnDef(16.5f, 15.5f, EntityType.BARREL),
        SpawnDef(16.5f, 16.5f, EntityType.IMP),
        SpawnDef(15.5f, 20.5f, EntityType.ZOMBIEMAN),
        SpawnDef(18.5f, 21.5f, EntityType.IMP),
        SpawnDef(13.5f, 18.5f, EntityType.PICKUP_MEDKIT),

        // Southwest Ambush Room
        SpawnDef(4.5f, 18.5f, EntityType.SERGEANT),
        SpawnDef(8.5f, 18.5f, EntityType.BARREL),
        SpawnDef(3.5f, 21.5f, EntityType.PICKUP_MEDKIT),
        SpawnDef(8.5f, 21.5f, EntityType.PICKUP_AMMO),

        // North Exit Corridor & Exit Chamber
        SpawnDef(14.5f, 7.5f, EntityType.BARREL),
        SpawnDef(16.5f, 7.5f, EntityType.SERGEANT),
        SpawnDef(15.5f, 3.5f, EntityType.IMP)
    )
}
