package com.baarcha.arcade;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * KitPvP — the training-ground mode: a fixed, identical kit for everyone,
 * instant respawn at your own spawn point, and a combo multiplier that rewards
 * chaining kills without dying (the standard KitPvP convention).
 *
 * A short spawn-protection window after each respawn keeps spawn-camping out.
 */
public final class KitPvp extends GameMode
        implements SpawnProvider, Hooks.ModeListener, Hooks.DamageListener {

    private static final int ROUND_SECONDS = 240;
    private static final int COMBO_DECAY_SECONDS = 8;

    private final Map<UUID, Integer> kills = new HashMap<>();
    private final Map<UUID, Integer> combos = new HashMap<>();
    private final Map<UUID, Integer> comboExpiry = new HashMap<>();
    private final Map<UUID, Location> spawnPoints = new HashMap<>();
    private Location spectator;

    public KitPvp(org.bukkit.plugin.java.JavaPlugin plugin, Arcade arcade) {
        super(plugin, arcade, "kitpvp", "KitPvP", Material.DIAMOND_SWORD, 2, 12, 2000);
    }

    @Override
    public String rules() {
        return "Same kit for everyone. Chain kills for a combo multiplier.";
    }

    @Override
    public void build(Arena a) {
        // per-match state reset (build runs at every session start)
        kills.clear();
        combos.clear();
        comboExpiry.clear();
        spawnPoints.clear();
        a.clear(-30, -5, -30, 30, 20, 30);
        a.fill(-26, 0, -26, 26, 0, 26, Material.STONE);
        // four walled training lanes
        a.shell(-26, 1, -26, 26, 8, 26, Material.COBBLESTONE);
        a.platform(0, 1, 0, 8, Material.SMOOTH_STONE);
        a.platform(0, 5, 0, 3, Material.DIAMOND_BLOCK);
        a.platform(-18, 1, -18, 3, Material.EMERALD_BLOCK);
        a.platform(18, 1, -18, 3, Material.EMERALD_BLOCK);
        a.platform(-18, 1, 18, 3, Material.EMERALD_BLOCK);
        a.platform(18, 1, 18, 3, Material.EMERALD_BLOCK);
        spectator = a.at(0, 20, 0);
    }

    @Override
    public List<Location> spawns() {
        // y=2: pedestal layer is at y=1, spawning at y=1 embeds feet in the block.
        return List.of(
                map().at(-18, 2, -18), map().at(18, 2, -18),
                map().at(-18, 2, 18), map().at(18, 2, 18));
    }

    @Override
    public Location spectatorSpawn() { return spectator; }

    @Override
    public void giveKit(Player player, GameSession session) {
        kills.putIfAbsent(player.getUniqueId(), 0);
        combos.putIfAbsent(player.getUniqueId(), 0);
        // The canonical KitPvP kit: full iron, sword, bow, arrows, food.
        setKit(player, List.of(
                new ItemStack(Material.IRON_SWORD),
                new ItemStack(Material.BOW),
                new ItemStack(Material.ARROW, 32),
                new ItemStack(Material.GOLDEN_APPLE, 2),
                new ItemStack(Material.COOKED_BEEF, 8)));
        player.getInventory().setArmorContents(armour(
                Material.IRON_HELMET, Material.IRON_CHESTPLATE,
                Material.IRON_LEGGINGS, Material.IRON_BOOTS).toArray(new ItemStack[0]));
        fullHeal(player);
        player.setFoodLevel(20);
        // remember this player's own pedestal for respawns
        spawnPoints.putIfAbsent(player.getUniqueId(),
                player.getLocation().clone());
    }

    @Override
    public void onModeDeath(GameSession session, PlayerDeathEvent event) {
        Player victim = event.getEntity();
        if (!session.isParticipant(victim)) {
            return;
        }
        combos.put(victim.getUniqueId(), 0);
        Player killer = victim.getKiller();
        if (killer != null && session.isParticipant(killer)
                && !killer.getUniqueId().equals(victim.getUniqueId())) {
            kills.merge(killer.getUniqueId(), 1, Integer::sum);
            int combo = combos.merge(killer.getUniqueId(), 1, Integer::sum);
            comboExpiry.put(killer.getUniqueId(), COMBO_DECAY_SECONDS);
            String label = combo >= 3
                    ? " &6x" + combo + " COMBO"
                    : "";
            chat(killer, "&7Kill! &8(&e" + kills.get(killer.getUniqueId()) + "&8)" + label);
            playSound(killer, Sound.ENTITY_PLAYER_LEVELUP, 1.4f);
        } else {
            broadcast(session, "&7" + victim.getName() + " died.");
        }
    }

    @Override
    public boolean eliminateOnDeath() {
        return false;
    }

    @Override
    public void onModeDamage(GameSession session, EntityDamageEvent event) {
        // No fall damage on the pedestals.
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            event.setCancelled(true);
        }
    }

    @Override
    public void tick(GameSession session) {
        // combo multiplier decays if you stop getting kills
        var it = comboExpiry.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            entry.setValue(entry.getValue() - 1);
            if (entry.getValue() <= 0) {
                combos.put(entry.getKey(), 0);
                it.remove();
            }
        }
    }

    @Override
    public int timeLimitSeconds() {
        return ROUND_SECONDS;
    }

    @Override
    public void onTimeUp(GameSession session) {
        Player best = null;
        int bestKills = -1;
        for (Player p : session.players()) {
            int k = kills.getOrDefault(p.getUniqueId(), 0);
            if (k > bestKills) {
                bestKills = k;
                best = p;
            }
        }
        session.end(bestKills > 0 && best != null ? best.getName() : null);
    }

    @Override
    public List<String> scoreboardLines(GameSession session, Player player) {
        return List.of(
                "Kills: &e" + kills.getOrDefault(player.getUniqueId(), 0),
                "Combo: &6x" + combos.getOrDefault(player.getUniqueId(), 0),
                "Time: &b" + secondsLeft(session) + "s",
                "&7kitpvp.arcade");
    }
}
