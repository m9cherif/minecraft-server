package com.baarcha.agame;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Spawns arena loot after the countdown (design doc §6): loot chests filled
 * from tiered tables at PLAYING transition; cleared again on round start.
 */
public final class LootManager {

    private static final Material[] COMMON = {
            Material.STONE_SWORD, Material.WOODEN_SWORD, Material.LEATHER_HELMET,
            Material.LEATHER_CHESTPLATE, Material.BREAD, Material.COOKED_BEEF,
            Material.COBBLESTONE, Material.ARROW,
    };
    private static final Material[] RARE = {
            Material.IRON_SWORD, Material.BOW, Material.CHAINMAIL_CHESTPLATE,
            Material.IRON_HELMET, Material.GOLDEN_APPLE, Material.SHIELD,
            Material.COOKED_PORKCHOP,
    };

    private final AGamePlugin plugin;

    public LootManager(AGamePlugin plugin) {
        this.plugin = plugin;
    }

    /** Removes leftover chests/items from the previous round. */
    public void clearLoot(ArenaMap map) {
        for (int i = 0; i < map.chestCount(); i++) {
            Location loc = map.chestLocation(i);
            Block b = loc.getBlock();
            if (b.getType() == Material.CHEST) {
                b.setType(Material.AIR);
            }
        }
    }

    /** Places loot chests and fills them. Called once at PLAYING start. */
    public void spawnLoot(ArenaMap map) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 0; i < map.chestCount(); i++) {
            Location loc = map.chestLocation(i);
            Block b = loc.getBlock();
            b.setType(Material.CHEST);
            if (b.getState() instanceof Chest chest) {
                Inventory inv = chest.getBlockInventory();
                inv.clear();
                int common = 2 + rng.nextInt(3);   // 2-4
                int rare = rng.nextInt(3);         // 0-2
                for (int c = 0; c < common; c++) {
                    inv.setItem(inv.firstEmpty(), randomStack(rng, COMMON));
                }
                for (int r = 0; r < rare; r++) {
                    inv.setItem(inv.firstEmpty(), randomStack(rng, RARE));
                }
                chest.update();
            }
        }
        plugin.getLogger().info("loot spawned for map " + map.id()
                + " (" + map.chestCount() + " chests)");
    }

    private ItemStack randomStack(ThreadLocalRandom rng, Material[] pool) {
        Material m = pool[rng.nextInt(pool.length)];
        int amount = switch (m) {
            case BREAD, COOKED_BEEF, COOKED_PORKCHOP, COBBLESTONE, ARROW -> 3 + rng.nextInt(6);
            default -> 1;
        };
        return ItemStack.of(m, amount);
    }
}
