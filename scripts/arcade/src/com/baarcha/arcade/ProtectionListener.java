package com.baarcha.arcade;

import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * Server-wide protection rules, requested by the hub owner:
 *
 * <ul>
 *   <li>only operators may destroy blocks (any world) - the hub and the arcade
 *       arenas are hand-built and must not be griefed;</li>
 *   <li>only operators may hit players while they are in the hub lobby - the
 *       games themselves are meant to be fought in, so this is limited to the
 *       hub world.</li>
 * </ul>
 *
 * "Operator" is checked with {@link Player#isOp()} because that is exactly the
 * rule that was asked for; the three hub operators keep OP level 4 (see
 * {@code server/ops.json}) and so are unaffected.
 */
public final class ProtectionListener implements Listener {

    private static final String NO_BREAK = "&cOnly operators can break blocks.";
    private static final String NO_PVP = "&cOnly operators can hit players in the hub.";

    private final Arcade arcade;

    public ProtectionListener(Arcade arcade) {
        this.arcade = arcade;
    }

    /** True for the lobby world: the first loaded world, as GameMode#hub does. */
    private boolean isHub(org.bukkit.World world) {
        return world.getName().equals(arcade.getServer().getWorlds().get(0).getName());
    }

    /**
     * Runs at LOWEST without {@code ignoreCancelled} on purpose: Paper's own
     * spawn protection cancels breaks near the world spawn first, and with a
     * HIGH + ignoreCancelled handler the player would only ever see the
     * vanilla message and never learn the server's rule.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.isOp()) {
            return;
        }
        boolean wasCancelled = event.isCancelled();
        event.setCancelled(true);
        if (!wasCancelled || player.getWorld().getName()
                .equals(arcade.getServer().getWorlds().get(0).getName())) {
            player.sendMessage(Arcade.legacy(NO_BREAK));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHubDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        if (!isHub(victim.getWorld())) {
            return;
        }
        Player attacker = attackerOf(event);
        if (attacker == null || attacker.isOp()) {
            return;
        }
        event.setCancelled(true);
        attacker.sendMessage(Arcade.legacy(NO_PVP));
    }

    /** The player behind the damage, whether they swung or shot. */
    private static Player attackerOf(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player direct) {
            return direct;
        }
        if (event.getDamager() instanceof Projectile shot
                && shot.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }
}