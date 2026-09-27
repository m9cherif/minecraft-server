package com.baarcha.agame;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * "A Game" — last-man-standing gamemode (DESIGN-a-game.md).
 *
 * One shared void world (agame_world) holds three procedural arenas 1000
 * blocks apart. Flow per user spec: pick 1 of 3 maps -> countdown -> loot
 * spawns -> last man standing; dead become spectators; everyone-dies
 * restarts; end returns everyone to the exact hub spawn.
 */
public final class AGamePlugin extends JavaPlugin {

    private GameManager gameManager;
    private LootManager lootManager;
    private MapsMenu mapsMenu;
    private final List<ArenaMap> maps = new ArrayList<>();

    @Override
    public void onEnable() {
        // hub spawn = the exact spot OnJoinSpawn enforces (BRAIN.md)
        World hub = getServer().getWorlds().get(0);

        // arena world: flat void, no mob spawning, no daylight/ weather churn
        World arenaWorld = getServer().getWorld(ArenaMap.WORLD_NAME);
        if (arenaWorld == null) {
            arenaWorld = getServer().createWorld(new WorldCreator(ArenaMap.WORLD_NAME)
                    .generator(new VoidGenerator())
                    .environment(World.Environment.NORMAL));
        }
        arenaWorld.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        arenaWorld.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        arenaWorld.setTime(6000L); // fixed noon
        arenaWorld.setStorm(false);

        lootManager = new LootManager(this);
        gameManager = new GameManager(this);
        mapsMenu = new MapsMenu(this);

        maps.add(new ArenaMap("plains", "Plains Pit", Material.GRASS_BLOCK, 0));
        maps.add(new ArenaMap("nether", "Nether Ruins", Material.NETHERRACK, 1));
        maps.add(new ArenaMap("sky", "Sky Islands", Material.END_STONE, 2));

        getServer().getPluginManager().registerEvents(new GameListener(this), this);
        getCommand("agame").setExecutor(this);

        // build arenas once at boot; then read blocks back and log the
        // result (console `execute if block` is silent on this build, so
        // the boot log is our verification channel).
        for (ArenaMap map : maps) {
            map.build();
        }
        getLogger().info("arena self-check: "
                + arenaWorld.getBlockAt(0, 100, 0).getType() + " @plains, "
                + arenaWorld.getBlockAt(1008, 103, 8).getType() + " @nether, "
                + arenaWorld.getBlockAt(2000, 100, 0).getType() + " @sky, "
                + "hub world=" + hub.getName());
    }

    // ------------------------------------------------------------- accessors

    GameManager gameManager() { return gameManager; }
    LootManager lootManager() { return lootManager; }
    MapsMenu mapsMenu() { return mapsMenu; }
    List<ArenaMap> maps() { return maps; }

    Location hubSpawn() {
        return new Location(getServer().getWorlds().get(0), 0.514, 76.0, 0.4, 0f, 0f);
    }

    /** Legacy `§`-code chat lines -> Adventure components (no extra deps). */
    Component mm(String legacy) {
        return LegacyComponentSerializer.legacySection().deserialize(legacy);
    }

    ArenaMap mapById(String id) {
        for (ArenaMap m : maps) {
            if (m.id().equalsIgnoreCase(id)) {
                return m;
            }
        }
        return null;
    }

    // -------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label,
                             String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("A Game commands are player-only.");
            return true;
        }
        String sub = args.length == 0 ? "join" : args[0].toLowerCase();
        switch (sub) {
            case "join" -> gameManager.openMapMenu(player);
            case "leave" -> gameManager.leave(player);
            case "start" -> {
                if (!player.hasPermission("agame.admin")) {
                    player.sendMessage(mm("§cNo permission."));
                    return true;
                }
                ArenaMap map = args.length >= 2 ? mapById(args[1]) : maps.get(0);
                if (map == null) {
                    player.sendMessage(mm("§cUnknown map. Try: plains, nether, sky"));
                    return true;
                }
                gameManager.forceStart(player, map);
            }
            case "stop" -> {
                if (!player.hasPermission("agame.admin")) {
                    player.sendMessage(mm("§cNo permission."));
                    return true;
                }
                gameManager.forceStop();
                player.sendMessage(mm("§eGame stopped, everyone back to hub."));
            }
            case "status" -> {
                player.sendMessage(mm("§6A Game: §e" + gameManager.state()
                        + "§6 | map: §e" + (gameManager.activeMap() != null
                        ? gameManager.activeMap().displayName() : "-")
                        + "§6 | alive: §e" + gameManager.aliveCount()));
            }
            default -> player.sendMessage(mm("§cUsage: /agame [join|leave|start|stop|status]"));
        }
        return true;
    }
}
