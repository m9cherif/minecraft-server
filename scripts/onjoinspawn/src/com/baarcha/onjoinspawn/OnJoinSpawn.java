package com.baarcha.onjoinspawn;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Teleports every player to the default world's spawn point on every join,
 * including relogs — regardless of where in the world they logged out.
 */
public final class OnJoinSpawn extends JavaPlugin implements Listener {

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
        final Location spawn = getServer().getWorlds().get(0).getSpawnLocation();
        getServer().getScheduler().runTask(this, () -> {
            if (!player.isOnline()) {
                return;
            }
            player.teleport(spawn);
            getLogger().info(player.getName() + " joined -> teleported to world spawn "
                    + spawn.getBlockX() + " " + spawn.getBlockY() + " " + spawn.getBlockZ());
        });
    }
}
