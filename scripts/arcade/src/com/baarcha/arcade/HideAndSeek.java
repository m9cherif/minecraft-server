package com.baarcha.arcade;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Hide and Seek — hiders get blocks to disguise themselves as scenery, the
 * seeker hunts them down. Classic rules:
 *  - hiders can right-click a block while sneaking to wear it as a prop
 *  - the seeker is told when someone is found, and converts to a hider on being
 *    caught (a "prop hunt" twist so a single finder never ends the round)
 *  - the round ends when every hider is found or the timer expires
 */
public final class HideAndSeek extends GameMode
        implements SpawnProvider, Hooks.InteractListener, Hooks.ModeListener,
        Hooks.DamageListener, Hooks.HungerFree {

    private static final int ROUND_SECONDS = 300;
    private static final int PROP_TYPES = 5;

    private static final Material[] PROPS = {
            Material.OAK_LOG, Material.STONE, Material.SAND, Material.WHITE_WOOL,
            Material.BOOKSHELF, Material.CHEST, Material.HAY_BLOCK,
    };

    private final Map<UUID, Boolean> hider = new HashMap<>();
    private final Map<UUID, Material> disguise = new HashMap<>();
    private final List<Location> propSpawns = new ArrayList<>();
    private Location spectator;

    public HideAndSeek(org.bukkit.plugin.java.JavaPlugin plugin, Arcade arcade) {
        super(plugin, arcade, "hideandseek", "Hide and Seek",
                Material.ENDER_PEARL, 3, 12, 10000);
    }

    @Override
    public String rules() {
        return "Hiders wear scenery. The seeker hunts. Sneak + right-click a block to wear it.";
    }

    @Override
    public void build(Arena a) {
        a.clear(-50, -10, -50, 50, 30, 50);
        hider.clear();
        disguise.clear();
        propSpawns.clear();

        // an open field with scattered scenery to hide behind and as
        a.fill(-45, 0, -45, 45, 0, 45, Material.GRASS_BLOCK);
        for (int i = 0; i < 40; i++) {
            int x = (i * 37 % 88) - 44;
            int z = (i * 53 % 88) - 44;
            Material m = PROPS[i % PROPS.length];
            a.set(x, 1, z, m);
            a.set(x, 2, z, m);
            propSpawns.add(a.at(x, 1, z));
        }
        // a small house to break sightlines
        a.shell(-10, 1, -10, 10, 6, 10, Material.OAK_PLANKS);
        a.clear(-10, 1, -10, 10, 3, -10);
        a.clear(-10, 1, 10, 10, 3, 10);
        a.clear(-10, 1, -10, -10, 3, 10);
        a.clear(10, 1, -10, 10, 3, 10);
        a.fill(-9, 6, -9, 9, 6, 9, Material.OAK_PLANKS);

        spectator = a.at(0, 28, 0);
    }

    @Override
    public List<Location> spawns() {
        return List.of(map().at(-20, 1, 0), map().at(20, 1, 0),
                map().at(0, 1, -20), map().at(0, 1, 20));
    }

    @Override
    public Location spectatorSpawn() { return spectator; }

    @Override
    public void giveKit(Player player, GameSession session) {
        hider.putIfAbsent(player.getUniqueId(), true);
        player.getInventory().clear();
        fullHeal(player);
        if (Boolean.TRUE.equals(hider.get(player.getUniqueId()))) {
            tag(player, "[Hider] ", "&a");
            setKit(player, List.of(
                    named(Material.STONE, "&7Sneak + right-click to wear"),
                    named(Material.OAK_LOG, "&7Sneak + right-click to wear"),
                    named(Material.SAND, "&7Sneak + right-click to wear")));
        } else {
            tag(player, "[Seeker] ", "&c");
            setKit(player, List.of(new ItemStack(Material.IRON_SWORD)));
        }
    }

    @Override
    public void onRoundStart(GameSession session) {
        assignRoles(session);
    }

    /** Assigns one seeker, everyone else hides. */
    public void assignRoles(GameSession session) {
        List<Player> players = session.players();
        if (players.isEmpty()) {
            return;
        }
        Player seeker = players.get(0);
        for (Player p : players) {
            boolean isSeeker = p.getUniqueId().equals(seeker.getUniqueId());
            hider.put(p.getUniqueId(), !isSeeker);
            giveKit(p, session);
        }
        broadcast(session, "&c" + seeker.getName() + " &7is the seeker. Hide!");
    }

    @Override
    public void onModeInteract(GameSession session, PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!session.isParticipant(player) || !player.isSneaking()) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || !PROP_SET.contains(item.getType())) {
            return;
        }
        event.setCancelled(true);
        Material worn = item.getType();
        disguise.put(player.getUniqueId(), worn);
        player.getInventory().clear();
        // the worn prop IS the player model: a block on the head is the
        // classic trick, plus the item in hand for clarity
        player.getInventory().addItem(new ItemStack(worn));
        chat(player, "&7Now wearing &f" + worn.name().toLowerCase() + "&7.");
    }

    private static final java.util.Set<Material> PROP_SET = java.util.Set.of(
            Material.OAK_LOG, Material.STONE, Material.SAND, Material.WHITE_WOOL,
            Material.BOOKSHELF, Material.CHEST, Material.HAY_BLOCK);

    @Override
    public void onModeDamage(GameSession session, EntityDamageEvent event) {
        if (!(event instanceof org.bukkit.event.entity.EntityDamageByEntityEvent byEntity)) {
            return;
        }
        if (!(byEntity.getEntity() instanceof Player victim)) {
            return;
        }
        if (!(byEntity.getDamager() instanceof Player seeker)) {
            return;
        }
        if (Boolean.TRUE.equals(hider.get(victim.getUniqueId()))
                && !Boolean.TRUE.equals(hider.get(seeker.getUniqueId()))) {
            // caught: the finder becomes a hider, the caught one is out
            event.setCancelled(true);
            hider.put(seeker.getUniqueId(), true);
            hider.put(victim.getUniqueId(), false);
            broadcast(session, "&e" + seeker.getName() + " &7found &e"
                    + victim.getName() + "&7! You are now a hider.");
            playSound(session, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.4f);
        }
    }

    @Override
    public boolean eliminateOnDeath() {
        return false;
    }

    @Override
    public void onModeDeath(GameSession session, PlayerDeathEvent event) {
        Player victim = event.getEntity();
        if (Boolean.TRUE.equals(hider.get(victim.getUniqueId()))) {
            hider.put(victim.getUniqueId(), false);
            broadcast(session, "&7" + victim.getName() + " was found.");
        }
    }

    private int hidersLeft(GameSession session) {
        int count = 0;
        for (Player p : session.players()) {
            if (Boolean.TRUE.equals(hider.get(p.getUniqueId()))) {
                count++;
            }
        }
        return count;
    }

    @Override
    public int timeLimitSeconds() {
        return ROUND_SECONDS;
    }

    @Override
    public void onTimeUp(GameSession session) {
        broadcast(session, "&aTime is up! The hiders survived.");
        session.end(null);
    }

    @Override
    public String winner(GameSession session) {
        return hidersLeft(session) == 0 ? "The seeker" : null;
    }

    @Override
    public void announceWinner(GameSession session, String winnerName) {
        if (winnerName == null) {
            broadcast(session, "&aThe hiders win by hiding!");
        } else {
            broadcast(session, "&cThe seeker &6found everyone!");
        }
    }

    @Override
    public List<String> scoreboardLines(GameSession session, Player player) {
        boolean hiding = Boolean.TRUE.equals(hider.get(player.getUniqueId()));
        Material worn = disguise.get(player.getUniqueId());
        List<String> lines = new ArrayList<>();
        lines.add("You are: " + (hiding ? "&aHIDER" : "&cSEEKER"));
        if (worn != null) {
            lines.add("Wearing: &f" + worn.name().toLowerCase());
        }
        lines.add("Hiders left: &e" + hidersLeft(session));
        lines.add("Time: &b" + secondsLeft(session) + "s");
        return lines;
    }
}
