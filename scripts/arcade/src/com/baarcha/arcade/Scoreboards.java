package com.baarcha.arcade;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-player sidebar showing the active mode's live state.
 *
 * Each player gets their own scoreboard so lines can be player-specific
 * (your kills, your tier, your checkpoint). Refreshed once a second by the
 * session tick; teams are used as the standard per-line carrier because
 * duplicate scores are not allowed on one objective.
 */
public final class Scoreboards {

    private final Arcade arcade;
    private final java.util.Map<java.util.UUID, Scoreboard> boards =
            new java.util.HashMap<>();

    public Scoreboards(Arcade arcade) {
        this.arcade = arcade;
    }

    public void show(GameSession session, Player player) {
        Scoreboard board = boards.computeIfAbsent(player.getUniqueId(),
                id -> Bukkit.getScoreboardManager().getNewScoreboard());
        player.setScoreboard(board);

        Objective objective = board.getObjective("arcade");
        if (objective == null) {
            objective = board.registerNewObjective("arcade", Criteria.DUMMY,
                    Arcade.legacy("&6&lARCA&f&lDE"));
        }
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        objective.displayName(Arcade.legacy(
                "&6" + session.mode().display().toUpperCase(java.util.Locale.ROOT)));

        // clear the previous frame
        for (Team team : board.getTeams()) {
            team.unregister();
        }
        for (String entry : board.getEntries()) {
            board.resetScores(entry);
        }

        List<String> lines = new ArrayList<>(
                session.mode().scoreboardLines(session, player));
        int score = lines.size();
        for (String line : lines) {
            String entry = legacyEntry(score);
            Team team = board.registerNewTeam(legacyKey(line, score));
            team.addEntry(entry);
            for (org.bukkit.scoreboard.Score s : board.getScores(entry)) {
                s.setScore(score);
            }
            team.suffix(Arcade.legacy(line));
            score--;
        }
    }

    public void hide(Player player) {
        boards.remove(player.getUniqueId());
        player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
    }

    /** Unique, legal team name derived from the line. */
    private String legacyKey(String line, int score) {
        String key = "l" + score + "_"
                + Integer.toHexString(line.hashCode() & 0xFFFF);
        return key.length() > 16 ? key.substring(0, 16) : key;
    }

    /** Unique scoreboard entry per line: an invisible colour-code pair. */
    private String legacyEntry(int score) {
        return "§" + String.format("%02x", Math.max(score, 0) & 0x0F);
    }
}
