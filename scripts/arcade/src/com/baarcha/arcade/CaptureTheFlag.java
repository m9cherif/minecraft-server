package com.baarcha.arcade;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Capture the Flag — two teams, steal the enemy wool and bring it home.
 *
 * Classic rules: pick up the enemy flag by touching it, a carrier is slower
 * and drops the flag when killed, and the flag returns to base after a short
 * delay. First team to CAPS points wins, or the leader at full time.
 */
public final class CaptureTheFlag extends GameMode
        implements SpawnProvider, Hooks.ModeListener, Hooks.DamageListener, Hooks.HungerFree {

    private static final int CAPS_TO_WIN = 3;
    private static final int MATCH_SECONDS = 300;
    private static final int FLAG_RESPAWN_SECONDS = 5;

    private final Map<UUID, Integer> team = new HashMap<>();       // 0 = red, 1 = blue
    private final Map<Integer, Integer> scores = new HashMap<>();  // team id -> caps
    private final Map<UUID, Boolean> carrier = new HashMap<>();
    private final Map<UUID, Integer> flagRespawn = new HashMap<>();

    private Location spectator;
    private Location redFlag;
    private Location blueFlag;
    private Location redBase;
    private Location blueBase;
    private boolean redFlagTaken;
    private boolean blueFlagTaken;

    public CaptureTheFlag(org.bukkit.plugin.java.JavaPlugin plugin, Arcade arcade) {
        super(plugin, arcade, "ctf", "Capture the Flag", Material.RED_BANNER, 2, 12, 9000);
    }

    @Override
    public String rules() {
        return "Steal the enemy wool, bring it home. " + CAPS_TO_WIN + " caps wins.";
    }

    @Override
    public void build(Arena a) {
        a.clear(-50, -10, -30, 50, 30, 30);
        team.clear();
        scores.clear();
        carrier.clear();
        flagRespawn.clear();
        redFlagTaken = false;
        blueFlagTaken = false;

        a.fill(-45, 0, -25, 45, 0, 25, Material.GRASS_BLOCK);
        // divider wall with three gaps
        a.fill(-1, 1, -25, 1, 8, 25, Material.STONE_BRICKS);
        a.clear(-1, 1, -12, 1, 4, -9);
        a.clear(-1, 1, -1, 1, 4, 2);
        a.clear(-1, 1, 10, 1, 4, 13);

        // red base (west) and blue base (east)
        a.platform(-38, 1, 0, 6, Material.RED_CONCRETE);
        a.platform(38, 1, 0, 6, Material.BLUE_CONCRETE);
        a.set(-38, 2, 0, Material.RED_BANNER);
        a.set(38, 2, 0, Material.BLUE_BANNER);

        // a couple of pieceable covers
        for (int z = -18; z <= 18; z += 12) {
            a.fill(-10, 1, z, 10, 3, z + 2, Material.OAK_PLANKS);
        }

        redBase = a.at(-38, 2, 0);
        blueBase = a.at(38, 2, 0);
        redFlag = a.at(-38, 3, 0);
        blueFlag = a.at(38, 3, 0);
        spectator = a.at(0, 26, 0);
    }

    @Override
    public List<Location> spawns() {
        return List.of(map().at(-38, 2, 0), map().at(38, 2, 0),
                map().at(-38, 2, 6), map().at(38, 2, 6));
    }

    @Override
    public Location spectatorSpawn() { return spectator; }

    @Override
    public void giveKit(Player player, GameSession session) {
        // alternate teams by join order
        int index = 0;
        for (Player p : session.players()) {
            if (p.getUniqueId().equals(player.getUniqueId())) {
                break;
            }
            index++;
        }
        int t = index % 2;
        team.put(player.getUniqueId(), t);
        scores.putIfAbsent(t, 0);
        player.setGameMode(org.bukkit.GameMode.SURVIVAL);
        setKit(player, List.of(
                new ItemStack(Material.IRON_SWORD),
                new ItemStack(Material.BOW),
                new ItemStack(Material.ARROW, 16),
                new ItemStack(Material.COOKED_BEEF, 6)));
        player.getInventory().setArmorContents(armour(
                t == 0 ? Material.RED_WOOL : Material.BLUE_WOOL,
                Material.IRON_HELMET, Material.IRON_LEGGINGS,
                Material.IRON_BOOTS).toArray(new ItemStack[0]));
        // dyed chestplate so teams read at a glance
        ItemStack chest = new ItemStack(Material.LEATHER_CHESTPLATE);
        org.bukkit.inventory.meta.LeatherArmorMeta meta =
                (org.bukkit.inventory.meta.LeatherArmorMeta) chest.getItemMeta();
        if (meta != null) {
            meta.displayName(Arcade.legacy(t == 0 ? "&cRed CTF" : "&9Blue CTF"));
            meta.setColor(t == 0
                    ? org.bukkit.Color.fromRGB(0xB0, 0x2E, 0x26)
                    : org.bukkit.Color.fromRGB(0x2E, 0x3B, 0xB0));
            chest.setItemMeta(meta);
        }
        player.getInventory().setChestplate(chest);
        tag(player, t == 0 ? "[Red] " : "[Blue] ", t == 0 ? "&c" : "&9");
        fullHeal(player);
    }

    @Override
    public void tick(GameSession session) {
        // flag return countdown after a drop
        var it = flagRespawn.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            entry.setValue(entry.getValue() - 1);
            if (entry.getValue() <= 0) {
                it.remove();
                redFlagTaken = false;
                blueFlagTaken = false;
                broadcast(session, "&7The flags are back at their pedestals.");
            }
        }

        for (Player p : session.players()) {
            if (!session.isAlive(p)) {
                continue;
            }
            int t = team.getOrDefault(p.getUniqueId(), 0);
            int enemy = 1 - t;

            // pick up the enemy flag by standing on it
            if (enemy == 0 && !redFlagTaken && p.getLocation().distanceSquared(redFlag) < 4) {
                redFlagTaken = true;
                carrier.put(p.getUniqueId(), true);
                giveFlagItem(p);
                broadcast(session, "&c" + p.getName() + " &7stole the &cred flag&7!");
                playSound(session, Sound.ENTITY_ITEM_PICKUP, 1f);
            } else if (enemy == 1 && !blueFlagTaken
                    && p.getLocation().distanceSquared(blueFlag) < 4) {
                blueFlagTaken = true;
                carrier.put(p.getUniqueId(), true);
                giveFlagItem(p);
                broadcast(session, "&9" + p.getName() + " &7stole the &9blue flag&7!");
                playSound(session, Sound.ENTITY_ITEM_PICKUP, 1f);
            }

            // score by reaching your own base
            if (Boolean.TRUE.equals(carrier.get(p.getUniqueId()))) {
                Location home = t == 0 ? redBase : blueBase;
                if (p.getLocation().distanceSquared(home) < 9) {
                    carrier.remove(p.getUniqueId());
                    int capped = scores.merge(t, 1, Integer::sum);
                    removeFlagItem(p);
                    broadcast(session, "&6" + p.getName() + " &7scored! &e(" + capped
                            + "/" + CAPS_TO_WIN + "&e)");
                    playSound(session, Sound.ENTITY_PLAYER_LEVELUP, 1f);
                }
            }
        }
    }

    private void giveFlagItem(Player player) {
        int t = team.getOrDefault(player.getUniqueId(), 0);
        ItemStack flag = new ItemStack(t == 0 ? Material.BLUE_WOOL : Material.RED_WOOL);
        ItemMeta meta = flag.getItemMeta();
        if (meta != null) {
            meta.displayName(Arcade.legacy(t == 0
                    ? "&9&lENEMY BLUE FLAG &7- &frun home"
                    : "&c&lENEMY RED FLAG &7- &frun home"));
            flag.setItemMeta(meta);
        }
        player.getInventory().addItem(flag);
    }

    private void removeFlagItem(Player player) {
        player.getInventory().removeItem(new ItemStack(Material.BLUE_WOOL));
        player.getInventory().removeItem(new ItemStack(Material.RED_WOOL));
    }

    @Override
    public boolean eliminateOnDeath() {
        return false;
    }

    @Override
    public void onModeDeath(GameSession session, PlayerDeathEvent event) {
        Player victim = event.getEntity();
        if (!session.isParticipant(victim)) {
            return;
        }
        if (Boolean.TRUE.equals(carrier.remove(victim.getUniqueId()))) {
            int t = team.getOrDefault(victim.getUniqueId(), 0);
            redFlagTaken = t == 1;
            blueFlagTaken = t == 0;
            flagRespawn.put(victim.getUniqueId(), FLAG_RESPAWN_SECONDS);
            broadcast(session, "&7The " + (t == 0 ? "red" : "blue")
                    + " flag was dropped and will return shortly.");
        }
    }

    @Override
    public void onModeDamage(GameSession session,
                             org.bukkit.event.entity.EntityDamageEvent event) {
        if (event.getCause() == org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL) {
            event.setCancelled(true);
        }
    }

    @Override
    public int timeLimitSeconds() {
        return MATCH_SECONDS;
    }

    @Override
    public String winner(GameSession session) {
        int red = scores.getOrDefault(0, 0);
        int blue = scores.getOrDefault(1, 0);
        if (red >= CAPS_TO_WIN || blue >= CAPS_TO_WIN) {
            return red == blue ? null : (red > blue ? "&cRed" : "&9Blue");
        }
        return null;
    }

    @Override
    public void onTimeUp(GameSession session) {
        int red = scores.getOrDefault(0, 0);
        int blue = scores.getOrDefault(1, 0);
        if (red == blue) {
            broadcast(session, "&eIt's a draw!");
            session.end(null);
        } else {
            session.end(red > blue ? "&cRed" : "&9Blue");
        }
    }

    @Override
    public void announceWinner(GameSession session, String winnerName) {
        broadcast(session, "&6" + (winnerName == null ? "Nobody" : winnerName)
                + " &ewins the flag match!");
    }

    @Override
    public List<String> scoreboardLines(GameSession session, Player player) {
        int t = team.getOrDefault(player.getUniqueId(), 0);
        return List.of(
                "&cRed: &e" + scores.getOrDefault(0, 0),
                "&9Blue: &e" + scores.getOrDefault(1, 0),
                "You are: " + (t == 0 ? "&cRed" : "&9Blue"),
                "Time: &b" + secondsLeft(session) + "s");
    }
}
