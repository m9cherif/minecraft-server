package com.baarcha.onjoinspawn;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Teleports every player to an EXACT fixed location on every join, including
 * relogs — regardless of where in the world they logged out.
 *
 * The coordinates are hardcoded (not world spawn) on purpose: vanilla spawn
 * logic scatters/adjusts players around the spawn block, which made joins land
 * "sometimes in the floor, sometimes elsewhere". A precise Location bypasses
 * that entirely.
 */
public final class OnJoinSpawn extends JavaPlugin implements Listener {

    /** Exact hub spawn, per user spec: 0.514 / 76.0 / 0.4, facing yaw 0. */
    private static final double SPAWN_X = 0.514;
    private static final double SPAWN_Y = 76.0;
    private static final double SPAWN_Z = 0.4;
    private static final float SPAWN_YAW = 0.0f;
    private static final float SPAWN_PITCH = 0.0f;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("enforcing world spawn on every join/relog");
    }

    /**
     * Deferred one tick so the teleport lands after the join sequence finishes
     * writing its own position packets, otherwise the client may snap back.
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        final Location spawn = new Location(
                getServer().getWorlds().get(0), SPAWN_X, SPAWN_Y, SPAWN_Z,
                SPAWN_YAW, SPAWN_PITCH);
        getServer().getScheduler().runTask(this, () -> {
            if (!player.isOnline()) {
                return;
            }
            player.teleport(spawn);
            getLogger().info(player.getName() + " joined -> teleported to "
                    + SPAWN_X + " " + SPAWN_Y + " " + SPAWN_Z);
        });
    }
}
