package com.baarcha.arcade;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sumo — the classic one-block-fall duel. A tiny platform, no building blocks,
 * one hit sends you off. Extra knockback as the duel goes on so it always
 * ends, exactly like the well-known sumo rules.
 */
public final class Sumo extends GameMode
        implements SpawnProvider, Hooks.ModeListener, Hooks.DamageListener {

    private final Map<UUID, Integer> wins = new HashMap<>();
    private Location spectator;

    public Sumo(org.bukkit.plugin.java.JavaPlugin plugin, Arcade arcade) {
        super(plugin, arcade, "sumo", "Sumo", Material.LEATHER_BOOTS, 2, 2, 4000);
    }

    @Override
    public String rules() {
        return "One small platform, no blocks. Knock your rival off. Best of 3.";
    }

    @Override
    public void build(Arena a) {
        a.clear(-15, -10, -15, 15, 15, 15);
        // 7x7 platform, one block of margin: any real hit ends it
        a.platform(0, 0, 0, 3, Material.WHITE_WOOL);
        a.platform(0, 1, 0, 3, Material.WHITE_WOOL);
        // a low lip so the platform reads clearly from a distance
        a.fill(-3, 2, -3, -3, 2, 3, Material.WHITE_WOOL);
        a.fill(3, 2, -3, 3, 2, 3, Material.WHITE_WOOL);
        a.fill(-3, 2, -3, 3, 2, -3, Material.WHITE_WOOL);
        a.fill(-3, 2, 3, 3, 2, 3, Material.WHITE_WOOL);
        spectator = a.at(0, 12, 0);
    }

    @Override
    public List<Location> spawns() {
        return List.of(map().at(-2, 2, 0, 90f, 0f), map().at(2, 2, 0, -90f, 0f));
    }

    @Override
    public Location spectatorSpawn() { return spectator; }

    @Override
    public void giveKit(Player player, GameSession session) {
        wins.putIfAbsent(player.getUniqueId(), 0);
        // leather kit, no blocks, one sword
        setKit(player, List.of(
                new ItemStack(Material.LEATHER_BOOTS),
                named(Material.COOKED_BEEF, "&6Food")));
        fullHeal(player);
    }

    @Override
    public void onModeDamage(GameSession session, EntityDamageEvent event) {
        if (!(event instanceof org.bukkit.event.entity.EntityDamageByEntityEvent byEntity)) {
            return;
        }
        if (!(byEntity.getEntity() instanceof Player victim)) {
            return;
        }
        if (!(byEntity.getDamager() instanceof Player attacker)) {
            return;
        }
        // push the victim away from the attacker, scaled by how lopsided the
        // duel has become so it can never stall
        int lead = wins.getOrDefault(attacker.getUniqueId(), 0)
                - wins.getOrDefault(victim.getUniqueId(), 0);
        double power = 0.55 + Math.max(lead, 0) * 0.25;
        Vector push = victim.getLocation().toVector()
                .subtract(attacker.getLocation().toVector())
                .setY(0);
        if (push.lengthSquared() < 0.0001) {
            push = new Vector(1, 0, 0);
        }
        push.normalize().multiply(power).setY(0.42);
        victim.setVelocity(push);
        victim.setFallDistance(0f);
        playSound(victim, Sound.ENTITY_GENERIC_EXPLODE, 1.6f);
    }

    @Override
    public boolean eliminateOnDeath() {
        return false;
    }

    @Override
    public void onModeDeath(GameSession session, PlayerDeathEvent event) {
        Player loser = event.getEntity();
        Player winner = loser.getKiller();
        if (winner == null || !session.isParticipant(winner)) {
            return;
        }
        int score = wins.merge(winner.getUniqueId(), 1, Integer::sum);
        broadcast(session, "&e" + winner.getName() + " &7wins &8(&e" + score + "/2&8)");
        if (score >= 2) {
            session.end(winner.getName());
            return;
        }
        for (Player p : session.players()) {
            p.setGameMode(org.bukkit.GameMode.SURVIVAL);
            fullHeal(p);
            p.getInventory().clear();
            p.getInventory().setArmorContents(null);
            p.teleport(session.spawnFor(p));
            giveKit(p, session);
        }
    }
}
