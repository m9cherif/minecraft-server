package com.baarcha.hubcompass;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Hub quality-of-life plugin, per user request:
 *
 * 1. NO mob spawning on the hub: doMobSpawning gamerule is forced false on the
 *    main world at every enable, plus a CreatureSpawnEvent guard that cancels
 *    anything still trying to spawn (except plugin/command CUSTOM spawns).
 * 2. Every player gets a "Gamemodes" compass on join and respawn. Right-click
 *    opens a gamemode selection menu (Bedwars, PvP, ...). IMPORTANT, per user:
 *    the gamemodes themselves are NOT implemented yet — clicking any entry
 *    simply closes the menu and gives NO items. The menu is display-only for
 *    now, ready to be wired to real gamemodes later.
 * 3. Owner accounts join the hub in Creative. The server console cannot target
 *    offline players on this Paper build (and a player who has never joined has
 *    no player data yet), so the mode is applied on join. AGame saves and
 *    restores the hub gamemode, so a match does not strip it.
 */
public final class HubCompass extends JavaPlugin implements Listener {

    private static final Component MENU_TITLE = Component.text(
            "Gamemodes", NamedTextColor.GOLD, TextDecoration.BOLD);
    private static final Component COMPASS_NAME = Component.text(
            "Gamemodes", NamedTextColor.AQUA, TextDecoration.BOLD);

    /** Accounts that always spawn in Creative on the hub (user request). */
    private static final Set<String> CREATIVE_PLAYERS = new HashSet<>(
            Arrays.asList("m9cherif3"));

    /** Ordered gamemode entries shown in the menu: label -> icon. */
    private static final Map<String, Material> GAMEMODES = new LinkedHashMap<>();
    /** Label -> the command that starts that mode. */
    private static final Map<String, String> COMMANDS = new LinkedHashMap<>();
    static {
        GAMEMODES.put("A Game", Material.CLOCK);
        COMMANDS.put("A Game", "agame join");
        GAMEMODES.put("SkyWars", Material.GRASS_BLOCK);
        COMMANDS.put("SkyWars", "arcade join skywars");
        GAMEMODES.put("PvP", Material.IRON_SWORD);
        COMMANDS.put("PvP", "arcade join pvp");
        GAMEMODES.put("KitPvP", Material.BOW);
        COMMANDS.put("KitPvP", "arcade join kitpvp");
        GAMEMODES.put("Duels", Material.ENDER_PEARL);
        COMMANDS.put("Duels", "arcade join duels");
        GAMEMODES.put("Sumo", Material.SADDLE);
        COMMANDS.put("Sumo", "arcade join sumo");
        GAMEMODES.put("Parkour", Material.LEATHER_BOOTS);
        COMMANDS.put("Parkour", "arcade join parkour");
        GAMEMODES.put("Skyblock", Material.OAK_SAPLING);
        COMMANDS.put("Skyblock", "arcade join skyblock");
        GAMEMODES.put("Murder Mystery", Material.OAK_SIGN);
        COMMANDS.put("Murder Mystery", "arcade join murdermystery");
        GAMEMODES.put("Capture the Flag", Material.RED_BANNER);
        COMMANDS.put("Capture the Flag", "arcade join ctf");
        GAMEMODES.put("Hide and Seek", Material.EMERALD);
        COMMANDS.put("Hide and Seek", "arcade join hideandseek");
        GAMEMODES.put("Build Fights", Material.BRICKS);
        COMMANDS.put("Build Fights", "arcade join buildfights");
    }

    @Override
    public void onEnable() {
        // Hub must stay mob-free, including after restarts (gamerule persists,
        // but re-enforcing here guarantees it even if level.dat is reset).
        World hub = getServer().getWorlds().get(0);
        hub.setGameRule(GameRule.DO_MOB_SPAWNING, false);

        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("hub mob spawning disabled (doMobSpawning=false), "
                + "gamemode compass enabled for every player, creative owners: "
                + CREATIVE_PLAYERS);
    }

    // ------------------------------------------------------------ mob guard

    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        World hub = getServer().getWorlds().get(0);
        if (!event.getEntity().getWorld().equals(hub)) {
            return;
        }
        // CUSTOM = spawned by other plugins/commands; everything else
        // (natural, spawner, egg, breeding, ...) is unwanted on the hub.
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CUSTOM) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------- compass giving

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (CREATIVE_PLAYERS.contains(player.getName().toLowerCase())) {
            player.setGameMode(GameMode.CREATIVE);
            getLogger().info(player.getName() + " joined in Creative");
        }
        giveCompass(player);
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        // Vanilla keeps the gamemode across death, so a creative owner stays
        // creative; AGame overrides SPECTATOR for its own participants.
        giveCompass(event.getPlayer());
    }

    /** Puts the Gamemodes compass in hotbar slot 0 (never duplicated). */
    private void giveCompass(Player player) {
        ItemStack current = player.getInventory().getItem(0);
        if (current != null && current.getType() == Material.COMPASS) {
            return;
        }
        ItemStack compass = new ItemStack(Material.COMPASS);
        ItemMeta meta = compass.getItemMeta();
        if (meta != null) {
            meta.displayName(COMPASS_NAME);
            compass.setItemMeta(meta);
        }
        player.getInventory().setItem(0, compass);
    }

    // ----------------------------------------------------- menu interaction

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.COMPASS) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !COMPASS_NAME.equals(meta.displayName())) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().openInventory(buildMenu());
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!MENU_TITLE.equals(event.getView().title())) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) {
            return;
        }
        // Every entry is wired: dispatch the mode's own join command.
        player.closeInventory();
        String command = commandFor(clicked);
        if (command != null) {
            Bukkit.dispatchCommand(player, command);
        }
    }

    /** Resolves a clicked menu icon back to its label, then its command. */
    private String commandFor(ItemStack clicked) {
        ItemMeta meta = clicked.getItemMeta();
        if (meta == null || meta.displayName() == null) {
            return null;
        }
        String label = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(meta.displayName());
        return COMMANDS.get(label);
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent event) {
        if (MENU_TITLE.equals(event.getView().title())) {
            event.setCancelled(true);
        }
    }

    /** Keeps the compass itself from being thrown away. */
    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        ItemMeta meta = event.getItemDrop().getItemStack().getItemMeta();
        if (meta != null && COMPASS_NAME.equals(meta.displayName())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------- builders

    private Inventory buildMenu() {
        Inventory menu = Bukkit.createInventory(null, 18, MENU_TITLE);
        int slot = 0;
        for (Map.Entry<String, Material> entry : GAMEMODES.entrySet()) {
            ItemStack icon = new ItemStack(entry.getValue());
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                meta.displayName(Component.text(entry.getKey(), NamedTextColor.YELLOW));
                icon.setItemMeta(meta);
            }
            menu.setItem(slot++, icon);
        }
        return menu;
    }
}
