package com.baarcha.arcade;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

/**
 * Thin block-stamping helper over a region of the shared void world.
 *
 * Every gamemode owns a slice of {@code arcade_world} (a 1000-block X band per
 * mode, so arenas never overlap) and builds its map through this class.
 * Coordinates passed in are RELATIVE to the arena origin.
 */
public final class Arena {

    private final World world;
    private final int ox;
    private final int oy;
    private final int oz;

    public Arena(World world, int ox, int oy, int oz) {
        this.world = world;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
    }

    public World world() { return world; }

    public int originX() { return ox; }

    public void set(int x, int y, int z, Material material) {
        world.getBlockAt(ox + x, oy + y, oz + z).setType(material, false);
    }

    public Material get(int x, int y, int z) {
        return world.getBlockAt(ox + x, oy + y, oz + z).getType();
    }

    /** Solid cuboid, inclusive on both corners. */
    public void fill(int x1, int y1, int z1, int x2, int y2, int z2,
                     Material material) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    set(x, y, z, material);
                }
            }
        }
    }

    /** Floor + roof + four walls, hollow inside. */
    public void shell(int x1, int y1, int z1, int x2, int y2, int z2,
                      Material material) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    boolean edge = x == Math.min(x1, x2) || x == Math.max(x1, x2)
                            || z == Math.min(z1, z2) || z == Math.max(z1, z2)
                            || y == Math.min(y1, y2) || y == Math.max(y1, y2);
                    if (edge) {
                        set(x, y, z, material);
                    }
                }
            }
        }
    }

    /** Wipes a cuboid to air (used between rounds). */
    public void clear(int x1, int y1, int z1, int x2, int y2, int z2) {
        fill(x1, y1, z1, x2, y2, z2, Material.AIR);
    }

    /** Hollow square platform of the given radius (1 = 3x3). */
    public void platform(int cx, int cy, int cz, int radius, Material material) {
        fill(cx - radius, cy, cz - radius, cx + radius, cy, cz + radius, material);
    }

    public Location at(double x, double y, double z) {
        return new Location(world, ox + x, oy + y, oz + z);
    }

    public Location at(double x, double y, double z, float yaw, float pitch) {
        return new Location(world, ox + x, oy + y, oz + z, yaw, pitch);
    }

    /** True when the block at the given relative coords is not air. */
    public boolean solid(int x, int y, int z) {
        return get(x, y, z) != Material.AIR;
    }
}
