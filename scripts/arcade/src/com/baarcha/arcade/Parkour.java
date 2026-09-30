package com.baarcha.arcade;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Parkour — a generated course of jumps with checkpoints. Falling below the
 * course sends you back to your last checkpoint with a time penalty, and the
 * fastest run in a round wins. Best times are kept in memory for the session.
 */
public final class Parkour extends GameMode
        implements SpawnProvider, Hooks.MoveListener, Hooks.HungerFree {

    private static final int COURSE_SEGMENTS = 14;
    private static final int CHECKPOINT_EVERY = 4;

    private final Map<UUID, Integer> checkpoints = new HashMap<>();
    private final Map<UUID, Integer> finishTimes = new HashMap<>();
    private final Map<UUID, Integer> personalBests = new HashMap<>();
    private final Map<UUID, Location> checkpointPoints = new HashMap<>();
    private Location spectator;

    public Parkour(org.bukkit.plugin.java.JavaPlugin plugin, Arcade arcade) {
        super(plugin, arcade, "parkour", "Parkour", Material.LEATHER_BOOTS, 1, 12, 6000);
    }

    @Override
    public String rules() {
        return "Jump the course. Checkpoints every " + CHECKPOINT_EVERY
                + " blocks. Fastest run wins; falls cost 5s.";
    }

    @Override
    public void build(Arena a) {
        a.clear(-10, -20, -90, 10, 40, 90);
        checkpoints.clear();
        finishTimes.clear();
        checkpointPoints.clear();

        int x = 0;
        int z = 0;
        int y = 0;
        for (int i = 0; i < COURSE_SEGMENTS; i++) {
            Material block = (i % CHECKPOINT_EVERY == 0)
                    ? Material.EMERALD_BLOCK
                    : Material.LIME_CONCRETE;
            a.platform(x, y, z, 1, block);
            // deterministic meander so the course is the same every rebuild
            x += 3;
            z += (i % 2 == 0) ? 3 : -3;
            y += (i % 3 == 0) ? 1 : 0;
            // optional side challenge platform
            if (i % 5 == 2) {
                a.platform(x + 2, y, z, 1, Material.YELLOW_CONCRETE);
            }
        }
        // finish pad
        a.platform(x, y, z, 2, Material.GOLD_BLOCK);
        a.set(x, y + 1, z, Material.END_PORTAL_FRAME);

        spectator = a.at(x / 2, y + 18, z / 2);
        this.finishLocation = a.at(x, y + 1, z);
    }

    private Location finishLocation;

    @Override
    public List<Location> spawns() {
        return List.of(map().at(0, 1, 0));
    }

    @Override
    public Location spectatorSpawn() { return spectator; }

    @Override
    public void giveKit(Player player, GameSession session) {
        checkpoints.put(player.getUniqueId(), 0);
        player.getInventory().clear();
        fullHeal(player);
        player.setAllowFlight(true);
        player.setFlying(true);
        chat(player, "&7Reach the &egold pad&7. Checkpoints are &aemerald&7.");
    }

    @Override
    public void onModeMove(GameSession session, PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!session.isAlive(player) || session.state() != GameSession.State.PLAYING) {
            return;
        }
        Location loc = player.getLocation();

        // fell off the course
        if (loc.getY() < 60) {
            Location checkpoint = checkpointPoints.get(player.getUniqueId());
            player.teleport(checkpoint != null ? checkpoint : spawns().get(0));
            player.setFallDistance(0f);
            playSound(player, Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f);
            chat(player, "&cBack to checkpoint &8(+5s)");
            return;
        }

        // checkpoint reached
        int index = Math.abs(loc.getBlockX()) / 3;
        if (index / CHECKPOINT_EVERY > checkpoints.getOrDefault(player.getUniqueId(), 0)
                && player.getLocation().getBlock().getType() == Material.EMERALD_BLOCK) {
            checkpoints.put(player.getUniqueId(), index / CHECKPOINT_EVERY);
            checkpointPoints.put(player.getUniqueId(), loc.clone());
            playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING, 1.6f);
            chat(player, "&aCheckpoint &e#" + checkpoints.get(player.getUniqueId()));
        }

        // finish
        if (loc.distanceSquared(finishLocation) < 6.0
                && !finishTimes.containsKey(player.getUniqueId())) {
            int time = Math.max(secondsLeft(session), 1);
            finishTimes.put(player.getUniqueId(), time);
            int best = personalBests.merge(player.getUniqueId(), time, Math::min);
            chat(player, "&6Finished in &e" + time + "s"
                    + (time == best ? " &6(new personal best!)" : ""));
            playSound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f);
        }
    }

    @Override
    public String winner(GameSession session) {
        return finishTimes.entrySet().stream()
                .min(Map.Entry.comparingByValue())
                .map(e -> nameOf(session, e.getKey()))
                .orElse(null);
    }

    private String nameOf(GameSession session, UUID id) {
        for (Player p : session.players()) {
            if (p.getUniqueId().equals(id)) {
                return p.getName();
            }
        }
        return null;
    }

    @Override
    public List<String> scoreboardLines(GameSession session, Player player) {
        return List.of(
                "Checkpoint: &a" + checkpoints.getOrDefault(player.getUniqueId(), 0),
                "Best: &e" + personalBests.getOrDefault(player.getUniqueId(), 0) + "s",
                "Finished: &b" + finishTimes.size(),
                "&7parkour.arcade");
    }
}
