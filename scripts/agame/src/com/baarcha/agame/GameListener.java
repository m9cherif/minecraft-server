package com.baarcha.agame;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * Event wiring for AGame: menu clicks, death -> spectator, quit -> leave,
 * and lobby/during-game guards.
 */
public final class GameListener implements Listener {

    private final AGamePlugin plugin;

    public GameListener(AGamePlugin plugin) {
        this.plugin = plugin;
    }

    // --------------------------------------------------------- map menu

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!MapsMenu.title().equals(event.getView().title())) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ArenaMap map = plugin.mapsMenu().mapAtSlot(event.getRawSlot());
        if (map != null) {
            player.closeInventory();
            plugin.gameManager().join(player, map);
        }
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent event) {
        if (MapsMenu.title().equals(event.getView().title())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------ deaths

    /**
     * Death while playing: mark eliminated, make them a spectator.
     * MONITOR priority so we only react when the death actually happens.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (plugin.gameManager().state() != GameManager.State.PLAYING
                || !plugin.gameManager().isParticipant(player)) {
            return;
        }
        // silent-ish death: keep vanilla message but no drops on the arena
        event.getDrops().clear();
        event.setDroppedExp(0);
        plugin.gameManager().onDeath(player);
    }

    /** Respawn after elimination -> spectator at the arena's sky view. */
    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        GameManager gm = plugin.gameManager();
        if (gm.state() == GameManager.State.PLAYING && gm.isParticipant(player)) {
            event.setRespawnLocation(gm.activeMap().spectatorSpawn());
            // SPECTATOR mode is applied right after respawn (1 tick later)
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    player.setGameMode(GameMode.SPECTATOR);
                }
            });
        }
    }

    // ------------------------------------------------------------- quits

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // relog mid-game = leave (design doc §8, OnJoinSpawn covers the hub tp)
        plugin.gameManager().leave(event.getPlayer());
    }

    // ------------------------------------------------------------ guards

    /** No damage before the fight starts (countdown freeze). */
    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        GameManager gm = plugin.gameManager();
        if (gm.isParticipant(player)
                && (gm.state() == GameManager.State.COUNTDOWN
                    || gm.state() == GameManager.State.GAMEOVER)) {
            event.setCancelled(true);
        }
    }

    /** Participants can't throw items away mid-game (inventory hygiene). */
    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (plugin.gameManager().isParticipant(event.getPlayer())
                && plugin.gameManager().state() == GameManager.State.PLAYING) {
            event.setCancelled(true);
        }
    }
}
