package com.baarcha.agame;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Map-selection menu (design doc §9): 3 map cards; clicking joins that
 * map's queue. Opened from /agame join or the HubCompass "A Game" entry.
 * Click routing happens in GameListener (InventoryClickEvent by title).
 */
public final class MapsMenu {

    public static final String TITLE = "A Game - choose a map";

    private final AGamePlugin plugin;

    public MapsMenu(AGamePlugin plugin) {
        this.plugin = plugin;
    }

    public void openFor(Player player) {
        Inventory menu = org.bukkit.Bukkit.createInventory(null, 9, title());
        int slot = 2; // centered row of 3
        for (ArenaMap map : plugin.maps()) {
            ItemStack icon = new ItemStack(map.icon());
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                meta.displayName(net.kyori.adventure.text.Component.text(
                        map.displayName(),
                        net.kyori.adventure.text.format.NamedTextColor.YELLOW));
                meta.lore(java.util.List.of(
                        net.kyori.adventure.text.Component.text(
                                "Click to join the queue",
                                net.kyori.adventure.text.format.NamedTextColor.GRAY)));
                icon.setItemMeta(meta);
            }
            menu.setItem(slot, icon);
            slot += 2;
        }
        player.openInventory(menu);
    }

    public static String title() {
        return TITLE;
    }

    /** Finds the map whose icon sits in the clicked slot; null otherwise. */
    public ArenaMap mapAtSlot(int slot) {
        if (slot < 2 || slot > 6 || (slot - 2) % 2 != 0) {
            return null;
        }
        int index = (slot - 2) / 2;
        if (index < 0 || index >= plugin.maps().size()) {
            return null;
        }
        return plugin.maps().get(index);
    }

    public Material slotIcon(int slot) {
        ArenaMap m = mapAtSlot(slot);
        return m != null ? m.icon() : null;
    }
}
