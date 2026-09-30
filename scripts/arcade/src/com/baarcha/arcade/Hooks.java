package com.baarcha.arcade;

import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;

/** Optional hooks a gamemode implements to receive events from ArcadeListener. */
public final class Hooks {

    private Hooks() {
    }

    /** Modes that care about a participant dying inside their arena. */
    public interface ModeListener {
        void onModeDeath(GameSession session, PlayerDeathEvent event);
    }

    /** Modes with custom damage rules (Sumo knockback, Build Fights protection). */
    public interface DamageListener {
        void onModeDamage(GameSession session, EntityDamageEvent event);
    }

    /** Modes that watch for movement (Hide and Seek hiding, Parkour falls). */
    public interface MoveListener {
        void onModeMove(GameSession session, PlayerMoveEvent event);
    }

    /** Modes driven by right/left clicks (Murder Mystery knife, Parkour plates). */
    public interface InteractListener {
        void onModeInteract(GameSession session, PlayerInteractEvent event);
    }

    /** Modes that let players drop items (CTF flags). */
    public interface DropListener {
    }

    /** Modes where hunger must not tick (Build Fights, Skyblock timers). */
    public interface HungerFree {
    }
}
