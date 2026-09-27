package com.baarcha.agame;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.List;

/**
 * One playable arena map inside the shared agame_world void world.
 *
 * Maps are laid out 1000 blocks apart on X so they never interact:
 * region x-center = index * 1000. Platforms are generated procedurally
 * (design decision: no schematic pipeline for v0.1).
 */
public final class ArenaMap {

    /** Shared void world holding all three arenas. */
    static final String WORLD_NAME = "agame_world";

    /** Offsets (relative to map center at y=100) of the four player spawns. */
    private static final int[][] SPAWN_OFFSETS = {
            {-10, -10}, {10, -10}, {-10, 10}, {10, 10},
    };

    /** Offsets of chest spawn points (each gets a loot chest at ground level). */
    private static final int[][] CHEST_OFFSETS = {
            {0, 0}, {-16, 0}, {16, 0}, {0, -16}, {0, 16}, {-8, -8}, {8, 8},
    };

    private final String id;
    private final String displayName;
    private final Material icon;
    private final int regionIndex;

    public ArenaMap(String id, String displayName, Material icon, int regionIndex) {
        this.id = id;
        this.displayName = displayName;
        this.icon = icon;
        this.regionIndex = regionIndex;
    }

    public String id() { return id; }
    public String displayName() { return displayName; }
    public Material icon() { return icon; }

    public World world() {
        return Bukkit.getWorld(WORLD_NAME);
    }

    private int centerX() { return regionIndex * 1000; }
    private int centerY() { return 100; }
    private int centerZ() { return 0; }

    public Location spectatorSpawn() {
        return new Location(world(), centerX() + 0.5, centerY() + 12, centerZ() + 0.5);
    }

    public List<Location> playerSpawns() {
        List<Location> spawns = new ArrayList<>();
        for (int[] off : SPAWN_OFFSETS) {
            spawns.add(new Location(world(), centerX() + off[0] + 0.5,
                    centerY() + 1, centerZ() + off[1] + 0.5));
        }
        return spawns;
    }

    public Location chestLocation(int index) {
        int[] off = CHEST_OFFSETS[index % CHEST_OFFSETS.length];
        return new Location(world(), centerX() + off[0], centerY() + 1, centerZ() + off[1]);
    }

    public int chestCount() {
        return CHEST_OFFSETS.length;
    }

    // -------------------------------------------------- procedural building

    /** Builds (or rebuilds) the arena platform + obstacles. Idempotent. */
    public void build() {
        World w = world();
        if (w == null) {
            throw new IllegalStateException(WORLD_NAME + " not loaded");
        }
        int cx = centerX(), cy = centerY(), cz = centerZ();

        switch (id) {
            case "plains" -> buildPlains(w, cx, cy, cz);
            case "nether" -> buildNether(w, cx, cy, cz);
            case "sky" -> buildSky(w, cx, cy, cz);
            default -> buildFlat(w, cx, cy, cz, Material.STONE);
        }
    }

    private void buildFlat(World w, int cx, int cy, int cz, Material floor) {
        fill(w, cx - 32, cy, cz - 32, cx + 32, cy, cz + 32, floor);
    }

    private void buildPlains(World w, int cx, int cy, int cz) {
        buildFlat(w, cx, cy, cz, Material.GRASS_BLOCK);
        // scattered trees: trunk + leaf blob
        int[][] trees = {{-20, -20}, {18, -22}, {-24, 16}, {22, 20}, {0, -24}};
        for (int[] t : trees) {
            int tx = cx + t[0], tz = cz + t[1];
            for (int dy = 1; dy <= 4; dy++) {
                set(w, tx, cy + dy, tz, Material.OAK_LOG);
            }
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    for (int dy = 4; dy <= 5; dy++) {
                        if (Math.abs(dx) + Math.abs(dz) + (dy - 4) <= 3) {
                            set(w, tx + dx, cy + dy, tz + dz, Material.OAK_LEAVES);
                        }
                    }
                }
            }
        }
        border(w, cx, cy, cz, Material.OAK_FENCE);
    }

    private void buildNether(World w, int cx, int cy, int cz) {
        buildFlat(w, cx, cy, cz, Material.NETHERRACK);
        // maze-ish pillars for cover
        for (int i = -3; i <= 3; i++) {
            for (int j = -3; j <= 3; j++) {
                if ((i + j) % 2 == 0 && (i != 0 || j != 0)) {
                    int px = cx + i * 8, pz = cz + j * 8;
                    for (int dy = 1; dy <= 5; dy++) {
                        set(w, px, cy + dy, pz, Material.NETHER_BRICKS);
                    }
                }
            }
        }
        border(w, cx, cy, cz, Material.NETHER_BRICKS);
    }

    private void buildSky(World w, int cx, int cy, int cz) {
        // main island + 3 satellites; the gaps are void = death
        fill(w, cx - 12, cy, cz - 12, cx + 12, cy, cz + 12, Material.END_STONE);
        int[][] sats = {{-28, 0}, {28, 0}, {0, 28}, {0, -28}};
        for (int[] s : sats) {
            int sx = cx + s[0], sz = cz + s[1];
            fill(w, sx - 8, cy, sz - 8, sx + 8, cy, sz + 8, Material.END_STONE);
            // bridge planks connecting satellite to main island
            int step = s[0] != 0 ? Integer.signum(s[0]) : 0;
            int stepZ = s[1] != 0 ? Integer.signum(s[1]) : 0;
            for (int d = 13; d < Math.abs(s[0] != 0 ? s[0] : s[1]) - 8; d++) {
                int bx = cx + step * d, bz = cz + stepZ * d;
                set(w, bx, cy, bz, Material.OAK_PLANKS);
                set(w, bx + (stepZ != 0 ? 1 : 0), cy, bz + (step != 0 ? 1 : 0),
                        Material.OAK_PLANKS);
            }
        }
        border(w, cx, cy, cz, Material.END_STONE_BRICKS);
    }

    /** 1-block wall rim so players don't walk off the flat maps. */
    private void border(World w, int cx, int cy, int cz, Material rim) {
        for (int d = -32; d <= 32; d++) {
            set(w, cx + d, cy + 1, cz - 32, rim);
            set(w, cx + d, cy + 1, cz + 32, rim);
            set(w, cx - 32, cy + 1, cz + d, rim);
            set(w, cx + 32, cy + 1, cz + d, rim);
        }
    }

    private void fill(World w, int x1, int y1, int z1, int x2, int y2, int z2,
                      Material m) {
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    set(w, x, y, z, m);
                }
            }
        }
    }

    private void set(World w, int x, int y, int z, Material m) {
        Block b = w.getBlockAt(x, y, z);
        if (b.getType() != m) {
            b.setType(m, false);
        }
    }
}
