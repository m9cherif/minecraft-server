package com.baarcha.arcade;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for every Arcade gamemode.
 *
 * A mode owns: an identity (id/display/icon), a slice of the void world to
 * build its arena in, and a set of lifecycle hooks the shared {@link GameSession}
 * calls. The session owns the countdown, the state machine, the hub
 * save/restore and the win detection, so a mode only implements what makes it
 * different: how the map is built, what a player is given, and how it ticks.
 *
 * Mechanics follow the well-known conventions of the modes of the same name
 * (SkyWars chest tiers and mid-chest refills, KitPvP combo multipliers, Sumo
 * knock-back duels, Murder Mystery murderer/detective/innocent roles with a
 * throwable distraction, CTF flag steal-and-return, Hide and Seek prop
 * disguises, Build Fights theme + timed build + voting).
 */
public abstract class GameMode {

    protected final JavaPlugin plugin;
    protected final Arcade arcade;

    private final String id;
    private final String display;
    private final Material icon;
    private final int minPlayers;
    private final int maxPlayers;
    /** X offset of this mode's 1000-block band in the shared void world. */
    private final int originX;

    protected GameMode(JavaPlugin plugin, Arcade arcade, String id, String display,
                       Material icon, int minPlayers, int maxPlayers, int originX) {
        this.plugin = plugin;
        this.arcade = arcade;
        this.id = id;
        this.display = display;
        this.icon = icon;
        this.minPlayers = minPlayers;
        this.maxPlayers = maxPlayers;
        this.originX = originX;
    }

    // ------------------------------------------------------------- identity

    public String id() { return id; }

    public String display() { return display; }

    public Material icon() { return icon; }

    public int minPlayers() { return minPlayers; }

    public int maxPlayers() { return maxPlayers; }

    public int originX() { return originX; }

    /** The hub world, i.e. the first (main) world. */
    public org.bukkit.World hub() {
        return plugin.getServer().getWorlds().get(0);
    }

    /** The shared void world all arenas are built in. */
    public org.bukkit.World arena() {
        return arcade.arenaWorld();
    }

    /** Arena helper bound to this mode's world slice. */
    public Arena map() {
        return new Arena(arena(), originX, 100, 0);
    }

    // ------------------------------------------------------------- lifecycle

    /** Stamps the arena. Called at boot and again before every round. */
    public abstract void build(Arena a);

    /** Items/kit/effects handed to a player when the round starts. */
    public void giveKit(Player player, GameSession session) {
        // default: nothing
    }

    /** Short rules line shown when a player joins the queue. */
    public String rules() {
        return "";
    }

    /**
     * Called once the round goes live, for modes whose roles can only be
     * assigned when everyone is present (Murder Mystery, Hide and Seek).
     */
    public void onRoundStart(GameSession session) {
        // default: nothing
    }

    /** Per-second tick while the round is running. */
    public void tick(GameSession session) {
        // default: nothing
    }

    /** Extra lines for the player's scoreboard, top-down. */
    public List<String> scoreboardLines(GameSession session, Player player) {
        return List.of();
    }

    /** Title of the sidebar shown to this player. */
    public String scoreboardTitle(Player player) {
        return display;
    }

    /**
     * Called when a participating player dies. Return true if the session
     * should treat it as an elimination handled by the default flow
     * (spectate). Modes that keep players alive override this.
     */
    public boolean eliminateOnDeath() {
        return true;
    }

    /** Last player standing / highest score wins. Return null while undecided. */
    public String winner(GameSession session) {
        return null;
    }

    /**
     * Round length in seconds, or 0 for "play until someone wins". Counted
     * down by the session and reported to players in the sidebar.
     */
    public int timeLimitSeconds() {
        return 0;
    }

    /** Called once when {@link #timeLimitSeconds()} reaches zero. */
    public void onTimeUp(GameSession session) {
        endOnTimeUp(session);
    }

    /** Default time-up behaviour: highest score wins, else nobody. */
    protected void endOnTimeUp(GameSession session) {
        session.end(null);
    }

    /** Broadcast shown when the round ends (before everyone is sent home). */
    public void announceWinner(GameSession session, String winnerName) {
        if (winnerName == null) {
            broadcast(session, "&eNobody survived - &7restarting...");
        } else {
            broadcast(session, "&6&l" + winnerName + " &6wins!");
        }
    }

    // ------------------------------------------------------------- utilities

    public void broadcast(GameSession session, String legacy) {
        Component msg = arcade.mm(legacy);
        for (Player p : session.players()) {
            p.sendMessage(msg);
        }
        plugin.getLogger().info("[" + id + "] " + legacy.replace('&', '§'));
    }

    /** Hub spawn, matching the exact OnJoinSpawn location. */
    public Location hubSpawn() {
        return new Location(hub(), 0.514, 76.0, 0.4, 0f, 0f);
    }

    protected static ItemStack named(Material material, String legacyName) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Arcade.legacy(legacyName));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    protected void playSound(GameSession session, Sound sound, float pitch) {
        for (Player p : session.players()) {
            p.playSound(p.getLocation(), sound, 1f, pitch);
        }
    }

    protected void playSound(Player player, Sound sound, float pitch) {
        player.playSound(player.getLocation(), sound, 1f, pitch);
    }

    /** Team-coloured name tag, used by the team modes (CTF, Hide and Seek). */
    protected void tag(Player player, String legacyPrefix, String legacyColour) {
        player.playerListName(Arcade.legacy(legacyColour + legacyPrefix
                + player.getName()));
    }

    protected void clearTag(Player player) {
        player.playerListName(Component.text(player.getName()));
    }

    protected List<ItemStack> armour(Material helmet, Material chest,
                                     Material legs, Material boots) {
        List<ItemStack> kit = new ArrayList<>();
        kit.add(new ItemStack(helmet));
        kit.add(new ItemStack(chest));
        kit.add(new ItemStack(legs));
        kit.add(new ItemStack(boots));
        return kit;
    }

    protected void setKit(Player player, List<ItemStack> items) {
        player.getInventory().clear();
        player.getInventory().addItem(items.toArray(new ItemStack[0]));
    }

    protected void fullHeal(Player player) {
        player.setHealth(20.0);
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setFireTicks(0);
        player.setFallDistance(0f);
    }

    protected void clearArena(GameSession session) {
        for (Player p : session.players()) {
            p.getInventory().clear();
            p.setGameMode(org.bukkit.GameMode.SURVIVAL);
        }
    }

    protected int secondsLeft(GameSession session) {
        return session.secondsRemaining();
    }

    protected void chat(Player player, String legacy) {
        player.sendMessage(arcade.mm(legacy));
    }

    protected void broadcastConsole(String message) {
        Bukkit.getConsoleSender().sendMessage(message);
    }
}
