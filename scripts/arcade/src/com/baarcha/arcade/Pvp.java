package com.baarcha.arcade;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PvP — free-for-all in a symmetric arena, killstreaks, first to SCORE_LIMIT
 * kills or the highest score when the clock runs out.
 *
 * Follows the classic convention: everyone spawns with the same kit, a kill
 * grants 1 point, and a streak of 3+ announces a bonus message.
 */
public final class Pvp extends GameMode implements SpawnProvider, Hooks.ModeListener {

    private static final int SCORE_LIMIT = 15;
    private static final int MATCH_SECONDS = 300;

    private final Map<UUID, Integer> scores = new HashMap<>();
    private final Map<UUID, Integer> streaks = new HashMap<>();
    private Location spectator;

    public Pvp(org.bukkit.plugin.java.JavaPlugin plugin, Arcade arcade) {
        super(plugin, arcade, "pvp", "PvP", Material.IRON_SWORD, 2, 12, 1000);
    }

    @Override
    public String rules() {
        return "Free-for-all. " + SCORE_LIMIT + " kills wins, or most kills at the end.";
    }

    @Override
    public void build(Arena a) {
        // build() runs at every session start: scores must reset per match,
        // otherwise the previous winner's total carries over and the next
        // match ends on its first tick.
        scores.clear();
        streaks.clear();
        a.clear(-40, -10, -40, 40, 30, 40);
        // Symmetric arena: hollow box with a pillar-and-catwalk middle.
        // The shell MUST stay open - the corner spawns are inside it, so a
        // sealed box would wall every player into their own corner.
        a.shell(-32, 0, -32, 32, 14, 32, Material.SMOOTH_STONE);
        // doorways: punch gaps through each of the four walls
        for (int i = -24; i <= 24; i += 8) {
            a.clear(-32, 1, i, 32, 4, i + 1);        // north/south walls
            a.clear(i, 1, -32, i + 1, 4, 32);        // east/west walls
        }
        a.fill(-32, 0, -32, 32, 0, 32, Material.SMOOTH_STONE);
        a.fill(-4, 1, -4, 4, 12, 4, Material.SMOOTH_STONE);
        a.platform(0, 13, 0, 6, Material.SMOOTH_STONE);
        a.platform(0, 7, 0, 20, Material.SMOOTH_STONE);
        // four corner spawns: platform layer sits at y=1, so the spawn point
        // must be y=2 (standing ON the emerald) — y=1 embeds feet inside the
        // block and the player can never move.
        a.platform(-24, 1, -24, 3, Material.EMERALD_BLOCK);
        a.platform(24, 1, -24, 3, Material.EMERALD_BLOCK);
        a.platform(-24, 1, 24, 3, Material.EMERALD_BLOCK);
        a.platform(24, 1, 24, 3, Material.EMERALD_BLOCK);
        spectator = a.at(0, 26, 0);
    }

    @Override
    public List<Location> spawns() {
        return List.of(
                map().at(-24, 2, -24), map().at(24, 2, -24),
                map().at(-24, 2, 24), map().at(24, 2, 24));
    }

    @Override
    public Location spectatorSpawn() { return spectator; }

    @Override
    public void giveKit(Player player, GameSession session) {
        scores.putIfAbsent(player.getUniqueId(), 0);
        streaks.putIfAbsent(player.getUniqueId(), 0);
        setKit(player, List.of(
                new ItemStack(Material.IRON_SWORD),
                named(Material.COOKED_BEEF, "&6PvP Kit"),
                named(Material.GOLDEN_APPLE, "&6Extra food")));
        player.getInventory().setArmorContents(armour(
                Material.IRON_HELMET, Material.IRON_CHESTPLATE,
                Material.IRON_LEGGINGS, Material.IRON_BOOTS).toArray(new ItemStack[0]));
        fullHeal(player);
    }

    @Override
    public void onModeDeath(GameSession session, PlayerDeathEvent event) {
        Player victim = event.getEntity();
        if (!session.isParticipant(victim)) {
            return;
        }
        // credit the killer
        Player killer = victim.getKiller();
        if (killer != null && session.isParticipant(killer)
                && !killer.getUniqueId().equals(victim.getUniqueId())) {
            scores.merge(killer.getUniqueId(), 1, Integer::sum);
            int streak = streaks.merge(killer.getUniqueId(), 1, Integer::sum);
            int total = scores.get(killer.getUniqueId());
            chat(killer, "&7You killed &f" + victim.getName() + " &8(&e" + total + "&8)");
            if (streak == 3) {
                broadcast(session, "&e" + killer.getName() + " &6is on a &c3 &6kill streak!");
            } else if (streak == 5) {
                broadcast(session, "&e" + killer.getName() + " &6is &cRAGING &6(&c5 &6streak)!");
            }
        } else {
            broadcast(session, "&7" + victim.getName() + " died.");
        }
    }

    @Override
    public boolean eliminateOnDeath() {
        return false;
    }

    @Override
    public String winner(GameSession session) {
        String best = null;
        int bestScore = -1;
        for (Player p : session.players()) {
            int score = scores.getOrDefault(p.getUniqueId(), 0);
            if (score > bestScore) {
                bestScore = score;
                best = p.getName();
            }
        }
        return bestScore >= SCORE_LIMIT ? best : null;
    }

    @Override
    public int timeLimitSeconds() {
        return MATCH_SECONDS;
    }

    @Override
    public void onTimeUp(GameSession session) {
        String best = null;
        int bestScore = -1;
        for (Player p : session.players()) {
            int score = scores.getOrDefault(p.getUniqueId(), 0);
            if (score > bestScore) {
                bestScore = score;
                best = p.getName();
            }
        }
        session.end(bestScore > 0 ? best : null);
    }

    @Override
    public List<String> scoreboardLines(GameSession session, Player player) {
        return List.of(
                "Kills: &e" + scores.getOrDefault(player.getUniqueId(), 0)
                        + "&8/" + SCORE_LIMIT,
                "Streak: &6" + streaks.getOrDefault(player.getUniqueId(), 0),
                "Alive: &a" + session.aliveCount(),
                "Time: &b" + secondsLeft(session) + "s");
    }
}
