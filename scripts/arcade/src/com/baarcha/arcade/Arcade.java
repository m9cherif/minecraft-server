package com.baarcha.arcade;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
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

    /** Legacy {@code §}-code chat lines -> Adventure components. */
    public static Component mm(String legacy) {
        return legacy(legacy);
    }

    public static Component legacy(String legacy) {
        return LegacyComponentSerializer.legacySection().deserialize(legacy);
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

        getServer().getPluginManager().registerEvents(new ArcadeListener(this), this);
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
            player.sendMessage(mm("&e" + active.mode().display()
                    + " is running. Join after it finishes."));
            return;
        }
        if (mode.minPlayers() > 1 && !player.hasPermission("arcade.admin")) {
            // Still queue the player; an admin can start with fewer.
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
