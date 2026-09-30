package com.baarcha.arcade;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Murder Mystery — one murderer, one detective, the rest innocents.
 *
 * Classic rules implemented here:
 *  - the murderer gets a throwing-knife and must eliminate everyone else
 *  - the detective gets a bow with a single arrow and wins by shooting them
 *  - innocents get snowballs, purely as distractions to throw at the killer
 *  - the round ends when the murderer dies, when only the murderer is left, or
 *    when the timer runs out (innocents win on a timeout)
 */
public final class MurderMystery extends GameMode
        implements SpawnProvider, Hooks.ModeListener, Hooks.HungerFree {

    private static final int ROUND_SECONDS = 240;

    private enum Role { MURDERER, DETECTIVE, INNOCENT }

    private final Map<UUID, Role> roles = new HashMap<>();
    private final List<Location> lobbySpawns = new ArrayList<>();
    private Location spectator;

    public MurderMystery(org.bukkit.plugin.java.JavaPlugin plugin, Arcade arcade) {
        super(plugin, arcade, "murdermystery", "Murder Mystery",
                Material.OAK_SIGN, 3, 12, 8000);
    }

    @Override
    public String rules() {
        return "One murderer, one detective. Killer must clear the map before time runs out.";
    }

    @Override
    public void build(Arena a) {
        a.clear(-30, -10, -30, 30, 25, 30);
        lobbySpawns.clear();
        // a mansion-ish floor plan of rooms and corridors to hide in
        a.fill(-26, 0, -26, 26, 0, 26, Material.POLISHED_ANDESITE);
        a.shell(-26, 1, -26, 26, 7, 26, Material.OAK_PLANKS);
        // inner partitions with door gaps
        a.fill(-26, 1, -8, 26, 5, -8, Material.OAK_PLANKS);
        a.fill(-26, 1, 8, 26, 5, 8, Material.OAK_PLANKS);
        a.fill(-8, 1, -26, -8, 5, 26, Material.OAK_PLANKS);
        a.fill(8, 1, -26, 8, 5, 26, Material.OAK_PLANKS);
        // punch the doorways back through
        for (int z = -24; z <= 24; z += 8) {
            a.clear(-8, 1, z, -8, 3, z + 1);
            a.clear(8, 1, z, 8, 3, z + 1);
        }
        for (int x = -24; x <= 24; x += 8) {
            a.clear(x, 1, -8, x + 1, 3, -8);
            a.clear(x, 1, 8, x + 1, 3, 8);
        }
        // central lounge
        a.platform(0, 1, 0, 5, Material.OAK_PLANKS);
        a.set(0, 2, 0, Material.LANTERN);

        for (int i = 0; i < 12; i++) {
            double angle = (2 * Math.PI * i) / 12;
            lobbySpawns.add(a.at(
                    Math.round(Math.cos(angle) * 20), 1,
                    Math.round(Math.sin(angle) * 20)));
        }
        spectator = a.at(0, 22, 0);
    }

    @Override
    public List<Location> spawns() { return lobbySpawns; }

    @Override
    public Location spectatorSpawn() { return spectator; }

    @Override
    public void giveKit(Player player, GameSession session) {
        roles.putIfAbsent(player.getUniqueId(), Role.INNOCENT);
        player.getInventory().clear();
        fullHeal(player);
        Role role = roles.get(player.getUniqueId());
        switch (role) {
            case MURDERER -> {
                setKit(player, List.of(named(Material.IRON_SWORD,
                        "&c&lKNIFE &7- throw at a wall to teleport")));
                tag(player, "[Murderer] ", "&c");
                broadcast(session, "&cA murderer walks among you...");
            }
            case DETECTIVE -> {
                setKit(player, List.of(
                        new ItemStack(Material.BOW),
                        new ItemStack(Material.ARROW, 1)));
                tag(player, "[Detective] ", "&9");
                broadcast(session, "&9A detective has been chosen!");
            }
            default -> {
                setKit(player, List.of(
                        named(Material.SNOWBALL, "&7Distraction &8- throw to trick the killer")));
                clearTag(player);
            }
        }
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
        Role deadRole = roles.get(victim.getUniqueId());
        Player killer = victim.getKiller();

        if (deadRole == Role.MURDERER) {
            broadcast(session, "&aThe murderer was killed! &6Innocents win!");
            if (killer != null) {
                broadcast(session, "&e" + killer.getName() + " &7found the killer!");
            }
            session.end(null);
            return;
        }

        broadcast(session, "&7" + victim.getName() + " was murdered.");
        if (killer != null && roles.get(killer.getUniqueId()) == Role.MURDERER) {
            playSound(killer, Sound.ENTITY_WITHER_SHOOT, 1.2f);
        }

        // murderer alone -> murderer wins
        if (deadRole == Role.DETECTIVE) {
            broadcast(session, "&cThe detective is dead...");
        }
    }

    @Override
    public void onRoundStart(GameSession session) {
        assignRoles(session);
    }

    /** Assigns one murderer and one detective after everyone has joined. */
    public void assignRoles(GameSession session) {
        List<UUID> ids = new ArrayList<>();
        for (Player p : session.players()) {
            roles.put(p.getUniqueId(), Role.INNOCENT);
            ids.add(p.getUniqueId());
        }
        Collections.shuffle(ids, new java.util.Random());
        if (!ids.isEmpty()) {
            roles.put(ids.get(0), Role.MURDERER);
        }
        if (ids.size() > 1) {
            roles.put(ids.get(1), Role.DETECTIVE);
        }
        for (Player p : session.players()) {
            giveKit(p, session);
        }
    }

    @Override
    public int timeLimitSeconds() {
        return ROUND_SECONDS;
    }

    @Override
    public void onTimeUp(GameSession session) {
        // time runs out: everyone but the murderer wins
        broadcast(session, "&aTime is up! &6The innocents win.");
        session.end(null);
    }

    @Override
    public String winner(GameSession session) {
        // only the murderer left standing
        List<Player> living = session.alivePlayers();
        if (living.size() == 1
                && roles.get(living.get(0).getUniqueId()) == Role.MURDERER) {
            return living.get(0).getName();
        }
        return null;
    }

    @Override
    public void announceWinner(GameSession session, String winnerName) {
        if (winnerName == null) {
            broadcast(session, "&6The innocents win!");
        } else {
            broadcast(session, "&c" + winnerName + " &6the murderer wins!");
        }
    }

    @Override
    public List<String> scoreboardLines(GameSession session, Player player) {
        Role role = roles.getOrDefault(player.getUniqueId(), Role.INNOCENT);
        return List.of(
                "You are: " + switch (role) {
                    case MURDERER -> "&c&lMURDERER";
                    case DETECTIVE -> "&9&lDETECTIVE";
                    case INNOCENT -> "&a&lINNOCENT";
                },
                "Alive: &e" + session.aliveCount(),
                "Time: &b" + secondsLeft(session) + "s",
                "&7survive &f" + secondsLeft(session) + "s");
    }
}
