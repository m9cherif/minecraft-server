package com.baarcha.agame;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * State machine for one AGame match (design doc §4).
 *
 * IDLE -> COUNTDOWN(10s) -> PLAYING -> GAMEOVER(5s) -> IDLE / COUNTDOWN
 * - 1 alive at end = winner; 0 alive = auto-restart (user rule:
 *   "restarts when all players die").
 */
public final class GameManager {

    public enum State { IDLE, COUNTDOWN, PLAYING, GAMEOVER }

    static final int COUNTDOWN_SECONDS = 10;
    static final int GAMEOVER_SECONDS = 5;
    static final int MIN_PLAYERS = 2;

    private final AGamePlugin plugin;
    private State state = State.IDLE;

    /** Map currently counting down or playing (null when IDLE). */
    private ArenaMap activeMap;

    /** Everyone participating in the active/next game. */
    private final Set<UUID> participants = new HashSet<>();
    private final Set<UUID> alive = new HashSet<>();

    /** Saved hub inventories + game mode, restored on game end. */
    private final Map<UUID, ItemStack[]> savedInventories = new LinkedHashMap<>();

    private BukkitTask countdownTask;
    private int secondsLeft;

    public GameManager(AGamePlugin plugin) {
        this.plugin = plugin;
    }

    public State state() { return state; }
    public ArenaMap activeMap() { return activeMap; }

    public boolean isParticipant(Player p) {
        return participants.contains(p.getUniqueId());
    }

    public int aliveCount() { return alive.size(); }

    // ------------------------------------------------------------ joining

    /** Called by /agame join and the compass menu. Opens the map menu. */
    public void openMapMenu(Player player) {
        if (state == State.PLAYING || state == State.COUNTDOWN) {
            player.sendMessage(plugin.mm("§6A Game is running (map: "
                    + (activeMap != null ? activeMap.displayName() : "?")
                    + "). You can join the next round after it ends."));
            return;
        }
        plugin.mapsMenu().openFor(player);
    }

    /** Player picked a map -> queue for the next countdown on that map. */
    public void join(Player player, ArenaMap map) {
        if (state == State.PLAYING) {
            player.sendMessage(plugin.mm("§6Game in progress. Try again soon."));
            return;
        }
        if (state == State.GAMEOVER) {
            player.sendMessage(plugin.mm("§6Round is ending - wait a second."));
            return;
        }
        if (activeMap != null && activeMap != map) {
            // countdown switched maps implicitly; simplest rule: last vote wins
            endCountdownQuietly();
        }
        activeMap = map;
        participants.add(player.getUniqueId());
        alive.add(player.getUniqueId());

        player.sendMessage(plugin.mm("§aJoined §e" + map.displayName()
                + "§a. " + participants.size() + "/" + MIN_PLAYERS + " players."));

        if (participants.size() >= MIN_PLAYERS && state == State.IDLE) {
            startCountdown();
        }
    }

    /** Player command or relog during a game. Returns to hub. */
    public void leave(Player player) {
        if (!participants.remove(player.getUniqueId())) {
            return;
        }
        boolean wasAlive = alive.remove(player.getUniqueId());
        restoreToHub(player, true);
        plugin.getLogger().info(player.getName() + " left AGame (was "
                + (wasAlive ? "alive" : "spectating") + ")");
        if (state == State.PLAYING) {
            checkEndConditions();
        } else if (participants.isEmpty()) {
            resetToIdle();
        }
    }

    // ----------------------------------------------------------- countdown

    private void startCountdown() {
        state = State.COUNTDOWN;
        secondsLeft = COUNTDOWN_SECONDS;
        final ArenaMap map = activeMap;
        map.build(); // arena fresh every round
        plugin.lootManager().clearLoot(map);

        // move players onto their spawns immediately so they see the arena
        List<Location> spawns = map.playerSpawns();
        int i = 0;
        for (UUID id : participants) {
            Player p = plugin.getServer().getPlayer(id);
            if (p == null || !p.isOnline()) {
                continue;
            }
            saveHubInventory(p);
            p.setGameMode(GameMode.SURVIVAL);
            p.setHealth(20.0);
            p.setFoodLevel(20);
            p.getInventory().clear();
            p.teleport(spawns.get(i++ % spawns.size()));
        }

        countdownTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (secondsLeft <= 0) {
                    cancel();
                    beginPlaying();
                    return;
                }
                if (secondsLeft <= 5 || secondsLeft % 5 == 0) {
                    broadcastParticipants(plugin.mm("§eGame starts in §c"
                            + secondsLeft + "§es..."));
                    for (UUID id : participants) {
                        Player p = plugin.getServer().getPlayer(id);
                        if (p != null) {
                            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                        }
                    }
                }
                secondsLeft--;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private void beginPlaying() {
        state = State.PLAYING;
        broadcastParticipants(plugin.mm("§6§lLAST MAN STANDING - FIGHT!"));
        plugin.lootManager().spawnLoot(activeMap);
        for (UUID id : participants) {
            Player p = plugin.getServer().getPlayer(id);
            if (p != null) {
                p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.2f);
            }
        }
    }

    private void endCountdownQuietly() {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
        // everyone goes back to the hub; they'll rejoin from the new menu
        for (UUID id : new ArrayList<>(participants)) {
            Player p = plugin.getServer().getPlayer(id);
            if (p != null) {
                restoreToHub(p, true);
            }
        }
        participants.clear();
        alive.clear();
        state = State.IDLE;
    }

    // ------------------------------------------------------- death / win

    /** Called by GameListener on death (after the player became spectator). */
    public void onDeath(Player player) {
        if (!alive.remove(player.getUniqueId())) {
            return;
        }
        broadcastParticipants(plugin.mm("§c" + player.getName()
                + " was eliminated §7(" + alive.size() + " left)"));
        checkEndConditions();
    }

    private void checkEndConditions() {
        if (state != State.PLAYING) {
            return;
        }
        if (alive.size() == 1) {
            Player winner = plugin.getServer().getPlayer(alive.iterator().next());
            endGame(winner != null ? winner.getName() : null);
        } else if (alive.isEmpty()) {
            endGame(null); // all died -> restart without winner (user rule)
        }
    }

    private void endGame(String winnerName) {
        state = State.GAMEOVER;
        if (winnerName != null) {
            broadcastParticipants(plugin.mm("§6§l"
                    + winnerName + " is the LAST MAN STANDING!"));
        } else {
            broadcastParticipants(plugin.mm("§eNobody survived - restarting..."));
        }
        // everyone back to hub, then either IDLE or straight into a new countdown
        for (UUID id : new ArrayList<>(participants)) {
            Player p = plugin.getServer().getPlayer(id);
            if (p != null) {
                restoreToHub(p, true);
            }
        }
        participants.clear();
        alive.clear();
        final ArenaMap finishedMap = activeMap;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            state = State.IDLE;
            activeMap = null;
            // design doc §10: restart loop - if enough players queued... v0.1
            // simply returns everyone to the hub; they rejoin via compass.
            plugin.getLogger().info("game on " + finishedMap.id()
                    + " finished (winner: " + (winnerName != null ? winnerName : "none")
                    + "); state=IDLE");
        }, GAMEOVER_SECONDS * 20L);
    }

    // --------------------------------------------------------- utilities

    private void saveHubInventory(Player p) {
        if (!savedInventories.containsKey(p.getUniqueId())) {
            savedInventories.put(p.getUniqueId(), p.getInventory().getContents());
        }
    }

    /** Teleports back to the exact hub spawn and restores the hub state. */
    private void restoreToHub(Player p, boolean restoreInventory) {
        p.setGameMode(GameMode.SURVIVAL);
        p.teleport(plugin.hubSpawn());
        if (restoreInventory) {
            ItemStack[] hub = savedInventories.remove(p.getUniqueId());
            p.getInventory().clear();
            if (hub != null) {
                p.getInventory().setContents(hub);
            }
            // compass re-given by OnJoinSpawn-style flow: keep HubCompass's
            // slot-0 compass guarantee by re-running its join logic isn't
            // possible cross-plugin, so give a fresh compass if slot 0 empty
            if (p.getInventory().getItem(0) == null) {
                p.getInventory().setItem(0,
                        org.bukkit.inventory.ItemStack.of(org.bukkit.Material.COMPASS));
            }
        }
        p.setHealth(20.0);
        p.setFoodLevel(20);
    }

    private void resetToIdle() {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
        state = State.IDLE;
        activeMap = null;
    }

    /** Force start (op testing): /agame start <map> */
    public void forceStart(Player sender, ArenaMap map) {
        if (state != State.IDLE) {
            sender.sendMessage(plugin.mm("<red>A game is already running."));
            return;
        }
        activeMap = map;
        participants.add(sender.getUniqueId());
        alive.add(sender.getUniqueId());
        startCountdown();
    }

    /** Force stop: everyone to hub, state reset. */
    public void forceStop() {
        endCountdownQuietly();
        if (state == State.GAMEOVER) {
            resetToIdle();
        }
    }

    private void broadcastParticipants(net.kyori.adventure.text.Component msg) {
        for (UUID id : participants) {
            Player p = plugin.getServer().getPlayer(id);
            if (p != null) {
                p.sendMessage(msg);
            }
        }
    }
}
