package com.baarcha.arcade;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Arcade — the shared engine behind every hub gamemode.
 *
 * Holds the single void world all arenas are stamped into, the mode registry,
 * and the one active {@link GameSession} at a time (the box has 1 vCPU, so
 * rounds are serialised rather than concurrent). The Gamemodes compass in
 * HubCompass dispatches {@code /arcade join <id>}.
 */
public final class Arcade extends JavaPlugin {

    public static final String WORLD_NAME = "arcade_world";

    private final Map<String, GameMode> modes = new LinkedHashMap<>();
    private final Scoreboards scoreboards = new Scoreboards(this);
    private GameSession active;
    private World arenaWorld;

    public Scoreboards scoreboards() { return scoreboards; }

    public JavaPlugin plugin() { return this; }

    public World arenaWorld() { return arenaWorld; }

    public GameSession active() { return active; }

    public Collection<GameMode> modes() { return modes.values(); }

    public GameMode mode(String id) {
        return modes.get(id.toLowerCase(Locale.ROOT));
    }

    /** Legacy {@code &}-coded chat lines -> Adventure components. */
    public static Component mm(String legacy) {
        return legacy(legacy);
    }

    public static Component legacy(String legacy) {
        // Modes author their text with '&' colour codes, but Adventure only
        // understands the section sign. Without this translation players saw
        // the raw text ("&6&lGO!") instead of coloured output.
        return LegacyComponentSerializer.legacySection()
                .deserialize(ChatColor.translateAlternateColorCodes('&', legacy));
    }

    /**
     * Folder name of the imported Parkour Panic map under server/world/.
     * When present, the Parkour mode runs that map instead of the built-in
     * generated course.
     */
    public static final String PARKOUR_WORLD = "parkour_panic";

    private org.bukkit.World parkourWorld;

    /** The imported map, or null when it has not been installed. */
    public org.bukkit.World parkourWorld() {
        return parkourWorld;
    }

    /**
     * Loads the imported Parkour Panic map if it is installed. It is a normal
     * Java world save dropped into server/world, so Paper can load it as-is;
     * a missing folder is not an error and the mode falls back to its
     * generated course.
     */
    private void loadParkourWorld() {
        if (getServer().getWorld(PARKOUR_WORLD) != null) {
            parkourWorld = getServer().getWorld(PARKOUR_WORLD);
            return;
        }
        java.io.File dir = findParkourFolder();
        if (dir == null) {
            getLogger().info("Parkour Panic map not installed (looked for '" + PARKOUR_WORLD
                    + "' in the world container and beside the main world)"
                    + " - Parkour uses its generated course");
            return;
        }
        try {
            // Name-based: Paper resolves it inside the world container (which
            // is "." here, i.e. server/), and a path-style creator is rejected
            // as an illegal namespaced identifier.
            parkourWorld = Bukkit.createWorld(new WorldCreator(PARKOUR_WORLD));
        } catch (RuntimeException ex) {
            getLogger().warning("Parkour Panic map failed to load from " + dir + ": "
                    + ex.getMessage());
            return;
        }
        if (parkourWorld != null) {
            getLogger().info("Parkour Panic map loaded from " + dir.getAbsolutePath()
                    + " spawn=" + parkourWorld.getSpawnLocation());
        }
    }

    /**
     * The map folder can sit in the world container or beside the main world
     * depending on how the server was provisioned, so both are checked.
     */
    /**
     * The map folder can live in the world container, or - after Paper's own
     * legacy-world migration - under the main world's dimensions/minecraft
     * namespace. Both layouts are checked.
     */
    private java.io.File findParkourFolder() {
        java.io.File container = getServer().getWorldContainer();
        if (container == null) {
            container = new java.io.File(".");
        }
        java.util.List<java.io.File> candidates = new ArrayList<>();
        candidates.add(new java.io.File(container, PARKOUR_WORLD));
        candidates.add(new java.io.File(container,
                "world/dimensions/minecraft/" + PARKOUR_WORLD));
        candidates.add(new java.io.File(container, "world/" + PARKOUR_WORLD));
        for (org.bukkit.World w : getServer().getWorlds()) {
            candidates.add(new java.io.File(w.getWorldFolder(), PARKOUR_WORLD));
            java.io.File dims = new java.io.File(w.getWorldFolder(), "../../dimensions/minecraft/"
                    + PARKOUR_WORLD);
            candidates.add(dims.getAbsoluteFile());
        }
        for (java.io.File dir : candidates) {
            if (new java.io.File(dir, "level.dat").isFile()
                    || new java.io.File(dir, "region").isDirectory()
                    || new java.io.File(dir, "dimensions").isDirectory()) {
                return dir;
            }
        }
        getLogger().info("parkour map lookup: cwd=" + System.getProperty("user.dir")
                + " container=" + getServer().getWorldContainer()
                + " worlds=" + getServer().getWorlds().stream()
                        .map(org.bukkit.World::getName).toList()
                + " tried=" + candidates);
        return null;
    }

    @Override
    public void onEnable() {
        arenaWorld = Bukkit.getWorld(WORLD_NAME);
        if (arenaWorld == null) {
            arenaWorld = Bukkit.createWorld(new WorldCreator(WORLD_NAME)
                    .generator(new VoidGenerator())
                    .environment(World.Environment.NORMAL));
        }
        arenaWorld.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        arenaWorld.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        arenaWorld.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        arenaWorld.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
        arenaWorld.setTime(6000L);
        arenaWorld.setStorm(false);

        registerModes();
        loadParkourWorld();

        getServer().getPluginManager().registerEvents(new ArcadeListener(this), this);
        getServer().getPluginManager().registerEvents(new ProtectionListener(this), this);
        getCommand("arcade").setExecutor(this);
        getCommand("bfgame").setExecutor(new BuildFightsCommand(this));

        // Build every arena once at boot and log a self-check, because console
        // `execute if block ... run say` is silent on this Paper build.
        List<String> checks = new ArrayList<>();
        for (GameMode mode : modes.values()) {
            mode.build(mode.map());
            Arena a = mode.map();
            checks.add(mode.id() + "=" + a.get(0, 0, 0));
        }
        getLogger().info("arena self-check: " + String.join(", ", checks));
        getLogger().info("modes registered: " + modes.keySet());
    }

    private void registerModes() {
        add(new SkyWars(this, this));
        add(new Pvp(this, this));
        add(new KitPvp(this, this));
        add(new Duels(this, this));
        add(new Sumo(this, this));
        add(new Parkour(this, this));
        add(new Skyblock(this, this));
        add(new MurderMystery(this, this));
        add(new CaptureTheFlag(this, this));
        add(new HideAndSeek(this, this));
        add(new BuildFights(this, this));
    }

    private void add(GameMode mode) {
        modes.put(mode.id(), mode);
    }

    // ------------------------------------------------------------- sessions

    public GameSession sessionFor(GameMode mode) {
        return active != null && active.mode() == mode ? active : null;
    }

    public GameSession sessionOf(Player player) {
        if (active != null && active.isParticipant(player)) {
            return active;
        }
        return null;
    }

    public boolean inGame(Player player) {
        return sessionOf(player) != null;
    }

    public void startSession(GameSession session) {
        if (active != null) {
            active.finish();
        }
        active = session;
        session.start();
    }

    public void onSessionFinished(GameSession session) {
        if (active == session) {
            active = null;
        }
    }

    /** After leaving, the player's hub state is restored by the session. */
    public void untrack(Player player) {
        // nothing global to clean: sessions own their own bookkeeping
    }

    // ------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label,
                             String[] args) {
        if (command.getName().equalsIgnoreCase("bfgame")) {
            return new BuildFightsCommand(this).onCommand(sender, command, label, args);
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Arcade commands are player-only.");
            return true;
        }
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "join" -> {
                if (args.length < 2) {
                    player.sendMessage(mm("&cUsage: /arcade join <mode> — see /arcade list"));
                    return true;
                }
                joinMode(player, args[1]);
            }
            case "list" -> listModes(player);
            case "leave" -> leave(player);
            case "stop" -> {
                if (!player.hasPermission("arcade.admin")) {
                    player.sendMessage(mm("&cNo permission."));
                    return true;
                }
                if (active == null) {
                    player.sendMessage(mm("&eNo game is running."));
                } else {
                    player.sendMessage(mm("&eStopping " + active.mode().display() + "..."));
                    active.finish();
                }
            }
            default -> player.sendMessage(mm(
                    "&cUsage: /arcade [join <mode>|leave|list|stop]"));
        }
        return true;
    }

    public void joinMode(Player player, String modeId) {
        GameMode mode = mode(modeId);
        if (mode == null) {
            player.sendMessage(mm("&cUnknown mode: &f" + modeId));
            return;
        }
        GameSession existing = sessionOf(player);
        if (existing != null) {
            player.sendMessage(mm("&cYou are already in &f" + existing.mode().display()
                    + "&c. Use &f/arcade leave&c."));
            return;
        }
        if (active != null) {
            // Latecomers may still join the SAME mode while it counts down;
            // without this every 2+ player mode is unplayable, because the
            // first joiner starts the countdown immediately.
            boolean sameMode = active.mode() == mode;
            boolean stillJoining = active.state() == GameSession.State.COUNTDOWN;
            if (sameMode && stillJoining && active.hasRoom()) {
                active.addLateJoiner(player);
                player.sendMessage(mm("&aJoined &f" + mode.display() + "&a. Starting soon..."));
                return;
            }
            player.sendMessage(mm("&e" + active.mode().display()
                    + " is running. Join after it finishes."));
            return;
        }
        if (mode.minPlayers() > 1 && !player.hasPermission("arcade.admin")) {
            // Still start the round; an admin can force it with fewer players.
            player.sendMessage(mm("&7" + mode.display() + " &8| &f"
                    + mode.minPlayers() + " &7players needed. Waiting..."));
        }
        GameSession session = new GameSession(this, mode);
        session.join(player);
        startSession(session);
    }

    private void listModes(Player player) {
        player.sendMessage(mm("&6&lArcade &7— pick one with &f/arcade join <id>"));
        for (GameMode mode : modes.values()) {
            player.sendMessage(mm("&e" + mode.id() + " &8- &f" + mode.display()
                    + " &7(" + mode.minPlayers() + "+ players)"));
        }
    }

    public void leave(Player player) {
        GameSession session = sessionOf(player);
        if (session == null) {
            player.sendMessage(mm("&cYou are not in a game."));
            return;
        }
        session.restoreToHub(player);
        if (session.state() == GameSession.State.PLAYING) {
            String winner = session.mode().winner(session);
            session.onQuit(player);
            if (winner != null) {
                session.end(winner);
            }
        } else {
            session.onQuit(player);
        }
        player.sendMessage(mm("&7You left &f" + session.mode().display() + "&7."));
    }
}
