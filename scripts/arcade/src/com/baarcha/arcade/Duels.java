package com.baarcha.arcade;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Duels — 1v1 on facing pedestals, best of three rounds. First to 2 round
 * wins takes the match. Each new round refills health and resets the arena
 * centre, so a duel is decided by skill rather than attrition.
 */
public final class Duels extends GameMode
        implements SpawnProvider, Hooks.ModeListener {

    private static final int ROUNDS_TO_WIN = 2;

    private final Map<UUID, Integer> roundWins = new HashMap<>();
    private final Map<UUID, Integer> roundNumber = new HashMap<>();
    private Location spectator;

    public Duels(org.bukkit.plugin.java.JavaPlugin plugin, Arcade arcade) {
        super(plugin, arcade, "duels", "Duels", Material.ENDER_PEARL, 2, 2, 3000);
    }

    @Override
    public String rules() {
        return "1v1, best of " + (ROUNDS_TO_WIN * 2 - 1) + ". First to "
                + ROUNDS_TO_WIN + " round wins takes it.";
    }

    @Override
    public void build(Arena a) {
        // per-match state reset (build runs at every session start)
        roundWins.clear();
        roundNumber.clear();
        a.clear(-20, -5, -20, 20, 20, 20);
        a.fill(-16, 0, -16, 16, 0, 16, Material.POLISHED_ANDESITE);
        // two facing pedestals with a gap between them
        a.platform(-10, 1, 0, 3, Material.EMERALD_BLOCK);
        a.platform(10, 1, 0, 3, Material.EMERALD_BLOCK);
        // decorative side walls so nobody can cheese from outside
        a.shell(-16, 1, -16, 16, 9, 16, Material.POLISHED_ANDESITE);
        a.clear(-16, 1, -16, 16, 9, -16);
        a.clear(-16, 1, 16, 16, 9, 16);
        a.clear(-16, 1, -16, -16, 9, 16);
        a.clear(16, 1, -16, 16, 9, 16);
        a.platform(0, 1, 0, 2, Material.GOLD_BLOCK);
        spectator = a.at(0, 18, 0);
    }

    @Override
    public List<Location> spawns() {
        // y=2: pedestal layer is at y=1, spawning at y=1 embeds feet in the block.
        return List.of(map().at(-10, 2, 0, 90f, 0f), map().at(10, 2, 0, -90f, 0f));
    }

    @Override
    public Location spectatorSpawn() { return spectator; }

    @Override
    public void giveKit(Player player, GameSession session) {
        roundWins.putIfAbsent(player.getUniqueId(), 0);
        roundNumber.putIfAbsent(player.getUniqueId(), 1);
        setKit(player, List.of(
                new ItemStack(Material.IRON_SWORD),
                named(Material.GOLDEN_APPLE, "&6Duel kit"),
                named(Material.COOKED_BEEF, "&6Food")));
        player.getInventory().setArmorContents(armour(
                Material.IRON_HELMET, Material.IRON_CHESTPLATE,
                Material.IRON_LEGGINGS, Material.IRON_BOOTS).toArray(new ItemStack[0]));
        fullHeal(player);
    }

    @Override
    public boolean eliminateOnDeath() {
        return false;
    }

    @Override
    public void onModeDeath(GameSession session, PlayerDeathEvent event) {
        Player loser = event.getEntity();
        if (!session.isParticipant(loser)) {
            return;
        }
        // Fall back to "whoever is still standing": a duel lost to the void
        // has no killer, and the round must still be scored.
        Player winner = loser.getKiller();
        if (winner == null || !session.isParticipant(winner)
                || winner.getUniqueId().equals(loser.getUniqueId())) {
            winner = null;
            for (Player p : session.players()) {
                if (!p.getUniqueId().equals(loser.getUniqueId())) {
                    winner = p;
                    break;
                }
            }
        }
        if (winner == null) {
            return;
        }
        int wins = roundWins.merge(winner.getUniqueId(), 1, Integer::sum);
        int round = roundNumber.merge(loser.getUniqueId(), 1, Integer::sum);
        broadcast(session, "&e" + winner.getName() + " &7wins round &e" + round
                + " &7(&e" + wins + "/" + ROUNDS_TO_WIN + "&7)");

        if (wins >= ROUNDS_TO_WIN) {
            session.end(winner.getName());
            return;
        }
        // next round: heal both, fresh kits, brief breather
        for (Player p : session.players()) {
            p.setGameMode(org.bukkit.GameMode.SURVIVAL);
            fullHeal(p);
            p.getInventory().clear();
            p.getInventory().setArmorContents(null);
            p.teleport(session.spawnFor(p));
            giveKit(p, session);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);
        }
    }

    @Override
    public List<String> scoreboardLines(GameSession session, Player player) {
        StringBuilder sb = new StringBuilder();
        for (Player p : session.players()) {
            if (sb.length() > 0) {
                sb.append("  ");
            }
            sb.append(p.getName()).append(": &e")
                    .append(roundWins.getOrDefault(p.getUniqueId(), 0));
        }
        return List.of(
                sb.toString(),
                "First to &e" + ROUNDS_TO_WIN,
                "&7duels.arcade");
    }
}
