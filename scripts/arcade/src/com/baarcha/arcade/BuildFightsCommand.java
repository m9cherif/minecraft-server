package com.baarcha.arcade;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Handles {@code /bfgame vote <number>} for the Build Fights vote phase. */
public final class BuildFightsCommand implements CommandExecutor {

    private final Arcade arcade;

    public BuildFightsCommand(Arcade arcade) {
        this.arcade = arcade;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label,
                             String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Player-only.");
            return true;
        }
        GameSession session = arcade.active();
        if (session == null || !(session.mode() instanceof BuildFights fights)) {
            player.sendMessage(arcade.mm("&cBuild Fights is not running."));
            return true;
        }
        if (args.length < 2 || !args[0].equalsIgnoreCase("vote")) {
            player.sendMessage(arcade.mm("&cUsage: /bfgame vote <number>"));
            return true;
        }
        int choice;
        try {
            choice = Integer.parseInt(args[1]);
        } catch (NumberFormatException ex) {
            player.sendMessage(arcade.mm("&cThat is not a number."));
            return true;
        }
        fights.vote(session, player, choice);
        return true;
    }
}
