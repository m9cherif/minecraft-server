package com.baarcha.arcade;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import com.baarcha.arcade.Hooks.DamageListener;
import com.baarcha.arcade.Hooks.DropListener;
import com.baarcha.arcade.Hooks.HungerFree;
import com.baarcha.arcade.Hooks.InteractListener;
import com.baarcha.arcade.Hooks.ModeListener;
import com.baarcha.arcade.Hooks.MoveListener;

/**
 * Routes vanilla events into the active mode. Keeps modes free of boilerplate
 * listener code: a mode that needs a hook simply implements the matching
 * interface below.
 */
public final class ArcadeListener implements Listener {

    private final Arcade arcade;

    public ArcadeListener(Arcade arcade) {
        this.arcade = arcade;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        GameSession session = arcade.sessionOf(event.getPlayer());
        if (session != null) {
            session.onQuit(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        GameSession session = arcade.sessionOf(event.getEntity());
        if (session == null) {
            return;
        }
        // Hooks live on the MODE, not on the player: this used to test the
        // Player, which never implements them, so every mode silently missed
        // its own death handler (no kills, no round wins, no role reactions).
        // Only while PLAYING: deaths during GAMEOVER (after the winner was
        // announced) must not keep scoring kills into the mode's maps.
        if (session.state() == GameSession.State.PLAYING
                && session.mode() instanceof ModeListener listener) {
            listener.onModeDeath(session, event);
        }
        // Arenas are self-contained: never drop loot into the void world.
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setKeepInventory(false);
        session.onDeath(event.getEntity());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        GameSession session = arcade.sessionOf(player);
        if (session == null) {
            return;
        }
        if (session.mode() instanceof DamageListener listener) {
            listener.onModeDamage(session, event);
        }
        // No starvation/fall drowning in an arena.
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL
                || event.getCause() == EntityDamageEvent.DamageCause.STARVATION
                || event.getCause() == EntityDamageEvent.DamageCause.SUFFOCATION) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player
                && arcade.inGame(player)
                && sessionModeAllowsHunger(arcade.sessionOf(player))) {
            event.setCancelled(true);
        }
    }

    private boolean sessionModeAllowsHunger(GameSession session) {
        return !(session.mode() instanceof HungerFree);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        GameSession session = arcade.sessionOf(event.getPlayer());
        if (session == null) {
            return;
        }
        if (session.mode() instanceof MoveListener listener) {
            listener.onModeMove(session, event);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        GameSession session = arcade.sessionOf(event.getPlayer());
        if (session != null && session.state() == GameSession.State.PLAYING) {
            // Keep arenas clean; modes that need dropping (CTF flags) opt in.
            if (!(session.mode() instanceof DropListener)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        GameSession session = arcade.sessionOf(event.getPlayer());
        if (session != null && session.mode() instanceof InteractListener listener) {
            listener.onModeInteract(session, event);
        }
    }

    /**
     * Respawning inside a match must land the player back in the ARENA, not on
     * the hub. This runs at HIGHEST so it wins over OnJoinSpawn's
     * exact-hub-spawn handler, which would otherwise send a dead PvP/Sumo
     * player to the lobby while their match is still running.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        GameSession session = arcade.sessionOf(event.getPlayer());
        if (session != null) {
            event.setRespawnLocation(session.spawnFor(event.getPlayer()));
            session.handleRespawn(event.getPlayer());
        }
        // not in a match: leave the hub behaviour to OnJoinSpawn untouched
    }
}
