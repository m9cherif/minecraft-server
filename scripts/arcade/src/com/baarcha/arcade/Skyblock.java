package com.baarcha.arcade;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Skyblock — one tiny island each over the void, and a resource ladder to
 * climb. You start with a starter kit and must gather your way up the tiers
 * (wood -> stone -> iron -> diamond). First player to finish the ladder wins.
 *
 * The island is wiped and rebuilt between rounds so every run is fair.
 */
public final class Skyblock extends GameMode
        implements SpawnProvider, Hooks.HungerFree {

    private static final int ISLAND_SPACING = 12;

    /** Milestones in order; each needs the listed material at the given count. */
    private static final Object[][] TIERS = {
            {Material.OAK_LOG, 8, "Wood"},
            {Material.COBBLESTONE, 16, "Stone"},
            {Material.IRON_INGOT, 8, "Iron"},
            {Material.DIAMOND, 3, "Diamond"},
    };

    private final Map<UUID, Integer> tierReached = new HashMap<>();
    private final List<Location> islandSpawns = new ArrayList<>();
    private Location spectator;

    public Skyblock(org.bukkit.plugin.java.JavaPlugin plugin, Arcade arcade) {
        super(plugin, arcade, "skyblock", "Skyblock", Material.OAK_SAPLING, 2, 12, 7000);
    }

    @Override
    public String rules() {
        return "Gather wood, stone, iron, diamond. First to the top wins.";
    }

    @Override
    public void build(Arena a) {
        a.clear(-60, -20, -30, 60, 30, 30);
        islandSpawns.clear();
        tierReached.clear();

        int count = 12;
        for (int i = 0; i < count; i++) {
            int x = (i % 6) * ISLAND_SPACING - 30;
            int z = (i / 6) * ISLAND_SPACING - 6;
            // 3x3 grass island with a starter chest and a tree
            a.platform(x, 0, z, 1, Material.GRASS_BLOCK);
            a.set(x, -1, z, Material.DIRT);
            a.set(x + 1, 1, z, Material.OAK_LOG);
            a.set(x + 1, 2, z, Material.OAK_LOG);
            a.fill(x, 3, z, x + 2, 3, z, Material.OAK_LEAVES);
            Block chest = a.world().getBlockAt(
                    a.originX() + x - 1, 100, z);
            chest.setType(Material.CHEST, false);
            if (chest.getState() instanceof org.bukkit.block.Chest c) {
                c.getBlockInventory().clear();
                c.getBlockInventory().addItem(new ItemStack(Material.OAK_SAPLING, 4));
                c.getBlockInventory().addItem(new ItemStack(Material.COOKED_BEEF, 4));
                c.getBlockInventory().addItem(
                        named(Material.IRON_PICKAXE, "&7Skyblock starter"));
            }
            islandSpawns.add(a.at(x, 1, z));
        }
        spectator = a.at(0, 25, -6);
    }

    @Override
    public List<Location> spawns() { return islandSpawns; }

    @Override
    public Location spectatorSpawn() { return spectator; }

    @Override
    public void giveKit(Player player, GameSession session) {
        tierReached.putIfAbsent(player.getUniqueId(), 0);
        player.getInventory().clear();
        fullHeal(player);
        chat(player, "&7Climb the ladder: &fwood > stone > iron > diamond&7.");
    }

    @Override
    public void tick(GameSession session) {
        for (Player player : session.alivePlayers()) {
            int tier = tierReached.getOrDefault(player.getUniqueId(), 0);
            if (tier >= TIERS.length) {
                continue;
            }
            Object[] next = TIERS[tier];
            Material material = (Material) next[0];
            int needed = (Integer) next[1];
            String label = (String) next[2];

            int held = 0;
            for (ItemStack item : player.getInventory().getContents()) {
                if (item != null && item.getType() == material) {
                    held += item.getAmount();
                }
            }
            if (held < needed) {
                continue;
            }
            tierReached.put(player.getUniqueId(), tier + 1);
            broadcast(session, "&e" + player.getName() + " &7reached &b" + label
                    + " &7tier &8(" + (tier + 1) + "/" + TIERS.length + "&8)");
            playSound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f);
        }
    }

    @Override
    public String winner(GameSession session) {
        String best = null;
        int bestTier = 0;
        for (Player p : session.alivePlayers()) {
            int tier = tierReached.getOrDefault(p.getUniqueId(), 0);
            if (tier > bestTier) {
                bestTier = tier;
                best = p.getName();
            }
        }
        return bestTier >= TIERS.length ? best : null;
    }

    @Override
    public int timeLimitSeconds() {
        return 600;
    }

    @Override
    public void onTimeUp(GameSession session) {
        String best = null;
        int bestTier = -1;
        for (Player p : session.players()) {
            int tier = tierReached.getOrDefault(p.getUniqueId(), 0);
            if (tier > bestTier) {
                bestTier = tier;
                best = p.getName();
            }
        }
        session.end(bestTier > 0 ? best : null);
    }

    @Override
    public List<String> scoreboardLines(GameSession session, Player player) {
        int tier = tierReached.getOrDefault(player.getUniqueId(), 0);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < TIERS.length; i++) {
            boolean done = i < tier;
            lines.add((done ? "&a✔ " : "&8○ ") + TIERS[i][2]);
        }
        lines.add("&7Time: &b" + secondsLeft(session) + "s");
        return lines;
    }
}
