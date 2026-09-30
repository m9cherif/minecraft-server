package com.baarcha.arcade;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Build Fights — head-to-head building on a random theme.
 *
 * Rules of the mode: everyone gets an identical plot, a random theme is drawn
 * (a castle, a tree house, a pixel art, ...), a build window ticks down, and
 * then the players vote for the best build. The theme item in slot 0 tells you
 * what to build; the winner is whoever the votes pick.
 */
public final class BuildFights extends GameMode
        implements SpawnProvider, Hooks.DamageListener, Hooks.HungerFree {

    private static final int PLOT_SPACING = 20;
    private static final int BUILD_SECONDS = 240;
    private static final int VOTE_SECONDS = 30;

    private static final List<String> THEMES = Arrays.asList(
            "a medieval castle", "a tall tree house", "a pixel-art logo",
            "a underwater base", "a giant robot", "a cosy village house",
            "a wizard's tower", "a snowy mountain");

    private final Map<UUID, Integer> votes = new HashMap<>();
    private final Map<UUID, UUID> votedFor = new HashMap<>();
    private final Map<Integer, Integer> tally = new LinkedHashMap<>();
    private final List<Location> plotSpawns = new ArrayList<>();
    private final AtomicBoolean votingOpen = new AtomicBoolean(false);
    private String theme = "a medieval castle";
    private Location spectator;
    private boolean votePhase;

    public BuildFights(org.bukkit.plugin.java.JavaPlugin plugin, Arcade arcade) {
        super(plugin, arcade, "buildfights", "Build Fights", Material.BRICKS, 2, 8, 11000);
    }

    @Override
    public String rules() {
        return "Random theme, timed build, then vote. Build the best!";
    }

    @Override
    public void build(Arena a) {
        a.clear(-60, -10, -40, 60, 40, 40);
        plotSpawns.clear();
        votes.clear();
        votedFor.clear();
        tally.clear();
        votePhase = false;
        votingOpen.set(false);

        for (int i = 0; i < 8; i++) {
            int x = (i % 4) * PLOT_SPACING - 30;
            int z = (i / 4) * PLOT_SPACING - 10;
            // 15x15 plot with a low border
            a.fill(x - 7, 0, z - 7, x + 7, 0, z + 7, Material.GRASS_BLOCK);
            a.fill(x - 7, 1, z - 7, x + 7, 1, z - 7, Material.STONE_BRICKS);
            a.fill(x - 7, 1, z - 7, x - 7, 1, z + 7, Material.STONE_BRICKS);
            a.fill(x + 7, 1, z - 7, x + 7, 1, z + 7, Material.STONE_BRICKS);
            a.fill(x - 7, 1, z + 7, x + 7, 1, z + 7, Material.STONE_BRICKS);
            plotSpawns.add(a.at(x, 1, z));
        }
        spectator = a.at(0, 30, 0);
    }

    @Override
    public List<Location> spawns() { return plotSpawns; }

    @Override
    public Location spectatorSpawn() { return spectator; }

    @Override
    public void giveKit(Player player, GameSession session) {
        player.getInventory().clear();
        player.getInventory().addItem(new ItemStack(Material.GRASS_BLOCK, 256));
        player.getInventory().addItem(new ItemStack(Material.STONE_BRICKS, 256));
        player.getInventory().addItem(new ItemStack(Material.OAK_PLANKS, 256));
        player.getInventory().addItem(new ItemStack(Material.GLASS, 128));
        player.getInventory().addItem(new ItemStack(Material.SAND, 128));
        player.getInventory().addItem(new ItemStack(Material.RED_WOOL, 64));
        player.getInventory().addItem(new ItemStack(Material.WHITE_WOOL, 64));
        fullHeal(player);
        chat(player, "&6Theme: &fbuild " + theme + "&6!");
        chat(player, "&7Use the blocks in your inventory. Right-click a plot to vote later.");
    }

    @Override
    public int timeLimitSeconds() {
        return BUILD_SECONDS;
    }

    @Override
    public void onTimeUp(GameSession session) {
        if (!votePhase) {
            votePhase = true;
            votingOpen.set(true);
            theme = THEMES.get(new Random().nextInt(THEMES.size()));
            for (Player p : session.players()) {
                chat(p, "&6Build time is up! &7Now vote with &f/bfgame vote <1-" 
                        + session.players().size() + ">");
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1f);
            }
            // give the vote phase its own timer
            session.extendTimer(VOTE_SECONDS);
        }
    }

    @Override
    public void tick(GameSession session) {
        if (votePhase && tally.size() >= session.players().size()
                && session.players().size() > 1) {
            finishVote(session);
        }
    }

    private void finishVote(GameSession session) {
        if (!votingOpen.compareAndSet(true, false)) {
            return;
        }
        int best = 0;
        int bestVotes = -1;
        for (Map.Entry<Integer, Integer> e : tally.entrySet()) {
            if (e.getValue() > bestVotes) {
                bestVotes = e.getValue();
                best = e.getKey();
            }
        }
        List<Player> players = session.players();
        String winner = best >= 0 && best < players.size()
                ? players.get(best).getName() : null;
        broadcast(session, "&6Votes: &e" + bestVotes + " &6for the winner.");
        session.end(winner);
    }

    /** Called by /bfgame vote <n>. */
    public boolean vote(GameSession session, Player voter, int choice) {
        if (!votingOpen.get()) {
            voter.sendMessage(Arcade.mm("&cVoting is not open right now."));
            return false;
        }
        List<Player> players = session.players();
        if (choice < 1 || choice > players.size()) {
            voter.sendMessage(Arcade.mm("&cPick a number between 1 and " + players.size() + "."));
            return false;
        }
        UUID prev = votedFor.put(voter.getUniqueId(), players.get(choice - 1).getUniqueId());
        if (prev != null) {
            tally.computeIfPresent(slotOf(session, prev), (k, v) -> v - 1);
        }
        tally.merge(choice - 1, 1, Integer::sum);
        voter.sendMessage(Arcade.mm("&aVoted for &f" + players.get(choice - 1).getName() + "&a."));
        return true;
    }

    private Integer slotOf(GameSession session, UUID id) {
        List<Player> players = session.players();
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i).getUniqueId().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void onModeDamage(GameSession session, EntityDamageEvent event) {
        // nobody can grief a build in progress
        event.setCancelled(true);
    }

    @Override
    public List<String> scoreboardLines(GameSession session, Player player) {
        List<String> lines = new ArrayList<>();
        lines.add("Theme: &f" + theme);
        if (votePhase) {
            lines.add("&7Vote with &f/bfgame vote <n>");
        } else {
            lines.add("Build time: &b" + secondsLeft(session) + "s");
        }
        lines.add("Players: &a" + session.players().size());
        return lines;
    }
}
