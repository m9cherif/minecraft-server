package com.baarcha.arcade;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * SkyWars — floating islands over the void, loot chests, and last-man-standing.
 *
 * Mechanics follow the mode of the same name: one island per player, a
 * starting chest each, a richer mid island in the centre, and mid chests that
 * refill on a timer so nobody snowballs out of the round early. Blocks from
 * chests make bridging to the middle possible.
 */
public final class SkyWars extends GameMode
        implements SpawnProvider, Hooks.HungerFree {

    private static final int ISLAND_SPACING = 24;
    private static final int MAX_ISLANDS = 12;
    private static final int REFILL_SECONDS = 45;

    private final Random random = new Random();
    private final List<Location> islandSpawns = new ArrayList<>();
    private Location spectator;
    private int secondsToRefill = REFILL_SECONDS;

    public SkyWars(org.bukkit.plugin.java.JavaPlugin plugin, Arcade arcade) {
        super(plugin, arcade, "skywars", "SkyWars", Material.GRASS_BLOCK, 2, 12, 5000);
    }

    @Override
    public String rules() {
        return "Floating islands, chest loot, mid refills. Last one standing wins.";
    }

    @Override
    public void build(Arena a) {
        a.clear(-60, -20, -60, 60, 40, 60);
        islandSpawns.clear();
        secondsToRefill = REFILL_SECONDS;

        // ring of player islands
        for (int i = 0; i < MAX_ISLANDS; i++) {
            double angle = (2 * Math.PI * i) / MAX_ISLANDS;
            int x = (int) Math.round(Math.cos(angle) * 40);
            int z = (int) Math.round(Math.sin(angle) * 40);
            buildIsland(a, x, z, Material.GRASS_BLOCK);
            islandSpawns.add(a.at(x, 2, z));
        }

        // richer mid island
        buildIsland(a, 0, 0, Material.SANDSTONE);
        refillChest(a, 0, 2, 0, true);
        a.platform(0, 3, 0, 1, Material.CHEST);

        // four corner islands, lower loot
        int[][] corners = {{-18, -18}, {18, -18}, {-18, 18}, {18, 18}};
        for (int[] c : corners) {
            buildIsland(a, c[0], c[1], Material.GRASS_BLOCK);
            refillChest(a, c[0], 2, c[1], false);
        }

        spectator = a.at(0, 30, 0);
    }

    /** A 5x5 grass island with a tree and a loot chest. */
    private void buildIsland(Arena a, int x, int z, Material top) {
        a.platform(x, 0, z, 2, top);
        a.fill(x - 2, -1, z - 2, x + 2, -1, z + 2, Material.DIRT);
        // a small tree so islands are not bare
        a.set(x, 1, z, Material.OAK_LOG);
        a.set(x, 2, z, Material.OAK_LOG);
        a.fill(x - 1, 3, z - 1, x + 1, 3, z + 1, Material.OAK_LEAVES);
        refillChest(a, x, 1, z + 2, false);
    }

    /** Fills (or refills) a chest with tier-appropriate loot. */
    private void refillChest(Arena a, int x, int y, int z, boolean mid) {
        Block block = a.world().getBlockAt(
                a.originX() + x, 100 + y, z);
        if (block.getType() != Material.AIR) {
            block.setType(Material.CHEST, false);
        }
        if (!(block.getState() instanceof Chest chest)) {
            return;
        }
        chest.getBlockInventory().clear();
        List<ItemStack> loot = new ArrayList<>(mid ? midLoot() : commonLoot());
        Collections.shuffle(loot, random);
        int slot = 0;
        for (ItemStack item : loot) {
            if (slot < chest.getBlockInventory().getSize()) {
                chest.getBlockInventory().setItem(slot++, item);
            }
        }
    }

    private List<ItemStack> commonLoot() {
        return List.of(
                new ItemStack(Material.IRON_SWORD),
                new ItemStack(Material.BOW),
                new ItemStack(Material.ARROW, 16),
                new ItemStack(Material.OAK_PLANKS, 32),
                new ItemStack(Material.COOKED_BEEF, 6),
                new ItemStack(Material.LEATHER_CHESTPLATE),
                new ItemStack(Material.IRON_HELMET),
                new ItemStack(Material.COBBLESTONE, 48),
                new ItemStack(Material.GOLDEN_APPLE, 2));
    }

    private List<ItemStack> midLoot() {
        return List.of(
                new ItemStack(Material.DIAMOND_SWORD),
                new ItemStack(Material.DIAMOND_CHESTPLATE),
                new ItemStack(Material.DIAMOND_HELMET),
                new ItemStack(Material.BOW),
                new ItemStack(Material.ARROW, 32),
                new ItemStack(Material.GOLDEN_APPLE, 4),
                new ItemStack(Material.COOKED_BEEF, 12),
                new ItemStack(Material.ENDER_PEARL, 2),
                new ItemStack(Material.COBBLESTONE, 64),
                named(Material.DIAMOND_SWORD, "&b&lMID LOOT &7- &fbe careful"));
    }

    @Override
    public List<Location> spawns() {
        return islandSpawns;
    }

    @Override
    public Location spectatorSpawn() { return spectator; }

    @Override
    public void giveKit(Player player, GameSession session) {
        // SkyWars players start empty-handed: the chest is the kit.
        player.getInventory().clear();
        fullHeal(player);
        chat(player, "&7Loot your island chest, then fight for the middle.");
    }

    @Override
    public void tick(GameSession session) {
        secondsToRefill--;
        if (secondsToRefill <= 0) {
            secondsToRefill = REFILL_SECONDS;
            Arena a = map();
            refillChest(a, 0, 3, 0, true);
            for (int[] c : new int[][]{{-18, -18}, {18, -18}, {-18, 18}, {18, 18}}) {
                refillChest(a, c[0], 2, c[1], false);
            }
            broadcast(session, "&eMid chests &7have been restocked!");
            playSound(session, Sound.BLOCK_CHEST_OPEN, 1f);
        }
    }

    @Override
    public String winner(GameSession session) {
        List<Player> living = session.alivePlayers();
        return living.size() == 1 ? living.get(0).getName() : null;
    }

    @Override
    public List<String> scoreboardLines(GameSession session, Player player) {
        return List.of(
                "Alive: &a" + session.aliveCount() + "&7/" + session.players().size(),
                "Refill: &e" + Math.max(secondsToRefill, 0) + "s",
                "&7skywars.arcade");
    }
}
