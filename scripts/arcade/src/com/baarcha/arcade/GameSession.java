package com.baarcha.arcade;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One running round of one {@link GameMode}.
 *
 * Shared state machine, exactly like the proven AGame flow:
 * COUNTDOWN -> PLAYING -> GAMEOVER -> (back to the hub).
 *
 * Responsibilities kept here so the 11 modes stay small:
 *  - participant tracking, hub inventory/gamemode save + restore
 *  - the countdown, the per-second tick, the game-over pause
 *  - death -> spectator, and asking the mode who won
 */
public final class GameSession {

    public enum State { COUNTDOWN, PLAYING, GAMEOVER }

    static final int COUNTDOWN_SECONDS = 10;
    static final int GAMEOVER_SECONDS = 5;

    private final Arcade arcade;
    private final GameMode mode;

    private State state = State.COUNTDOWN;
    private final Set<UUID> participants = new LinkedHashSet<>();
    private final Set<UUID> alive = new LinkedHashSet<>();
    /** Died in a respawning mode; put back in the fight when they respawn. */
    private final Set<UUID> awaitingRespawn = new LinkedHashSet<>();

    private final Map<UUID, ItemStack[]> savedInventories = new LinkedHashMap<>();
    private final Map<UUID, org.bukkit.GameMode> savedGameModes = new LinkedHashMap<>();
    private final Map<UUID, Location> savedLocations = new LinkedHashMap<>();

    private BukkitTask task;
    private int secondsLeft = COUNTDOWN_SECONDS;
    private String winnerName;

    public GameSession(Arcade arcade, GameMode mode) {
        this.arcade = arcade;
        this.mode = mode;
    }

    public GameMode mode() { return mode; }

    public State state() { return state; }

    public boolean isRunning() { return state != State.GAMEOVER; }

    public int secondsRemaining() { return secondsLeft; }

    public String winnerName() { return winnerName; }

    /** Extends the running timer (Build Fights adds a voting phase). */
    public void extendTimer(int seconds) {
        secondsLeft = seconds;
    }

    public boolean isAlive(Player player) { return alive.contains(player.getUniqueId()); }

    public boolean isParticipant(Player player) {
        return participants.contains(player.getUniqueId());
    }

    public List<Player> players() {
        List<Player> out = new ArrayList<>();
        for (UUID id : participants) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                out.add(p);
            }
        }
        return out;
    }

    public int aliveCount() { return alive.size(); }

    /**
     * The mode-defined spawn for a participant: their slot in the join order,
     * cycled when the mode provides fewer spawns than players.
     */
    public Location spawnFor(Player player) {
        List<Location> spawns = mode instanceof SpawnProvider provider
                ? provider.spawns() : List.of(mode.hubSpawn());
        if (spawns.isEmpty()) {
            return mode.hubSpawn();
        }
        int index = 0;
        for (UUID id : participants) {
            if (id.equals(player.getUniqueId())) {
                break;
            }
            index++;
        }
        return spawns.get(index % spawns.size());
    }

    // ------------------------------------------------------------- joining

    public boolean hasRoom() { return participants.size() < mode.maxPlayers(); }

    public void join(Player player) {
        UUID id = player.getUniqueId();
        saveHubState(player);
        participants.add(id);
        alive.add(id);
    }

    /**
     * Called from the respawn event for modes where death is not elimination:
     * restores the kit and counts the player as alive again.
     */
    public void handleRespawn(Player player) {
        if (!awaitingRespawn.remove(player.getUniqueId())) {
            return;
        }
        if (state != State.PLAYING || !participants.contains(player.getUniqueId())) {
            return;
        }
        // 1 tick: the respawn is not fully applied until the next tick
        player.getServer().getScheduler().runTaskLater(arcade.plugin(), () -> {
            if (!player.isOnline() || !participants.contains(player.getUniqueId())) {
                return;
            }
            player.setGameMode(org.bukkit.GameMode.SURVIVAL);
            player.teleport(spawnFor(player));
            fullReset(player);
            alive.add(player.getUniqueId());
            mode.giveKit(player, this);
        }, 1L);
    }

    /**
     * Seats a player who joined after the round already teleported everyone
     * (allowed while still counting down). Without this they would be a
     * participant standing on the hub while the match plays out around them.
     */
    public void addLateJoiner(Player player) {
        join(player);
        player.setGameMode(org.bukkit.GameMode.SURVIVAL);
        fullReset(player);
        player.teleport(spawnFor(player));
        mode.giveKit(player, this);
    }

    private void saveHubState(Player player) {
        UUID id = player.getUniqueId();
        if (!savedInventories.containsKey(id)) {
            savedInventories.put(id, player.getInventory().getContents());
            savedGameModes.put(id, player.getGameMode());

            savedLocations.put(id, player.getLocation().clone());
        }
    }

    // ------------------------------------------------------------- start

    public void start() {
        // fresh arena every round
        mode.build(mode.map());

        for (UUID id : new ArrayList<>(participants)) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) {
                participants.remove(id);
                alive.remove(id);
                continue;
            }
            p.setGameMode(org.bukkit.GameMode.SURVIVAL);
            fullReset(p);
            p.teleport(spawnFor(p));
            mode.giveKit(p, this);
        }

        state = State.COUNTDOWN;
        secondsLeft = COUNTDOWN_SECONDS;
        winnerName = null;
        mode.broadcast(this, "&7" + mode.display() + " &8| &f" + participants.size()
                + "/" + mode.minPlayers() + " &7players. Starting in &e"
                + COUNTDOWN_SECONDS + "s");

        task = new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                switch (state) {
                    case COUNTDOWN -> tickCountdown();
                    case PLAYING -> tickPlaying();
                    case GAMEOVER -> tickGameOver();
                    default -> cancel();
                }
            }
        }.runTaskTimer(arcade.plugin(), 0L, 20L);
    }

    private void fullReset(Player p) {
        p.setHealth(20.0);
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setFireTicks(0);
        p.setFallDistance(0f);
        p.setLevel(0);
        p.setExp(0f);
        p.getInventory().clear();
        p.getInventory().setArmorContents(null);
    }

    private void refreshScoreboards() {
        for (Player p : players()) {
            arcade.scoreboards().show(this, p);
        }
    }

    private void tickCountdown() {
        if (secondsLeft <= 0) {
            beginPlaying();
            return;
        }
        if (secondsLeft <= 5 || secondsLeft % 5 == 0) {
            mode.broadcast(this, "&eStarts in &c" + secondsLeft + "&es...");
            for (Player p : players()) {
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                p.showTitle(net.kyori.adventure.title.Title.title(
                        Arcade.legacy("&e" + secondsLeft),
                        Arcade.legacy("&7" + mode.display())));
            }
        }
        secondsLeft--;
    }

    private void beginPlaying() {
        state = State.PLAYING;
        secondsLeft = mode.timeLimitSeconds() > 0 ? mode.timeLimitSeconds() : 0;
        // roles can only be handed out once everyone is on the map
        mode.onRoundStart(this);
        mode.broadcast(this, "&6&lGO!");
        for (Player p : players()) {
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.2f);
        }
        refreshScoreboards();
    }

    private void tickPlaying() {
        mode.tick(this);
        refreshScoreboards();
        if (secondsLeft > 0) {
            secondsLeft--;
            if (secondsLeft == 0) {
                mode.onTimeUp(this);
            }
        }
        String winner = mode.winner(this);
        if (winner != null) {
            end(winner);
        }
    }

    private void tickGameOver() {
        if (secondsLeft > 0) {
            secondsLeft--;
        } else {
            finish();
        }
    }

    // ------------------------------------------------------------- outcomes

    /** Ends the round now (mode-driven win, or admin stop). */
    public void end(String winner) {
        if (state == State.GAMEOVER) {
            return;
        }
        winnerName = winner;
        state = State.GAMEOVER;
        secondsLeft = GAMEOVER_SECONDS;
        mode.announceWinner(this, winner);
        for (Player p : players()) {
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
    }

    /** Called by the listener when a participating player dies. */
    public void onDeath(Player player) {
        if (state != State.PLAYING || !isParticipant(player)) {
            return;
        }
        alive.remove(player.getUniqueId());
        if (mode.eliminateOnDeath()) {
            // Dead players become spectators at the arena's sky view.
            player.setGameMode(org.bukkit.GameMode.SPECTATOR);
            Location view = mode instanceof SpawnProvider provider
                    ? provider.spectatorSpawn()
                    : mode.hubSpawn();
            player.teleport(view);
        } else {
            // PvP-style modes: the respawn EVENT puts them back in the arena
            // (see ArcadeListener#onRespawn). Teleporting from a death handler
            // fights the respawn and strands them on the hub mid-match.
            awaitingRespawn.add(player.getUniqueId());
        }
        String winner = mode.winner(this);
        if (winner != null) {
            end(winner);
        }
    }

    /** Sends everyone home and clears the session. */
    public void finish() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (UUID id : new ArrayList<>(participants)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                restoreToHub(p);
            }
        }
        participants.clear();
        alive.clear();
        awaitingRespawn.clear();
        savedInventories.clear();
        savedGameModes.clear();
        savedLocations.clear();
        arcade.onSessionFinished(this);
    }

    /** Teleports one player back to the hub with their saved state. */
    public void restoreToHub(Player player) {
        UUID id = player.getUniqueId();
        ItemStack[] saved = savedInventories.remove(id);
        org.bukkit.GameMode savedMode = savedGameModes.remove(id);
        savedLocations.remove(id);

        player.setGameMode(savedMode != null ? savedMode
                : org.bukkit.GameMode.SURVIVAL);
        player.teleport(mode.hubSpawn());
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        if (saved != null) {
            player.getInventory().setContents(saved);
        }
        player.setHealth(20.0);
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setFireTicks(0);
        player.setFallDistance(0f);
        arcade.scoreboards().hide(player);
        arcade.untrack(player);
    }

    /** A player quit mid-round: drop them, re-check the win condition. */
    // (see onQuit)
    public void onQuit(Player player) {
        UUID id = player.getUniqueId();
        if (!participants.remove(id)) {
            return;
        }
        alive.remove(id);
        awaitingRespawn.remove(id);
        savedInventories.remove(id);
        savedGameModes.remove(id);
        savedLocations.remove(id);
        arcade.scoreboards().hide(player);
        arcade.untrack(player);
        // Everyone leaving must ALWAYS release the mode. Previously a round
        // that emptied while PLAYING (winner() still null, e.g. a duel where
        // both players quit) left the session active forever and the mode
        // refused every later join with "is running".
        if (participants.isEmpty()) {
            if (task != null) {
                task.cancel();
                task = null;
            }
            arcade.onSessionFinished(this);
            return;
        }
        if (state == State.PLAYING) {
            String winner = mode.winner(this);
            if (winner != null) {
                end(winner);
            }
        }
    }

    /** Convenience for modes: all currently alive players. */
    public List<Player> alivePlayers() {
        List<Player> out = new ArrayList<>();
        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                out.add(p);
            }
        }
        return out;
    }

    public List<UUID> aliveIds() { return Collections.unmodifiableList(new ArrayList<>(alive)); }
}
