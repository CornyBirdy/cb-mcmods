package dev.cornybirdy.endlock;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Works in the server console as well as in game (needs endlock.admin, ops have it).
 * <pre>
 * /endlock [status]                     show what's on and off
 * /endlock enderchests [on|off|toggle]  ender chests on or off
 * /endlock end [on|off|toggle]          the End on or off
 * /endlock reload                       reload config.yml
 * </pre>
 */
final class EndLockCommand implements BasicCommand {

    private final EndLock plugin;

    EndLockCommand(EndLock plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> {
                show(sender, Feature.ENDER_CHESTS);
                show(sender, Feature.END);
                sender.sendMessage(plugin.message("usage"));
            }
            case "enderchests" -> feature(sender, Feature.ENDER_CHESTS, args);
            case "end" -> feature(sender, Feature.END, args);
            case "reload" -> {
                plugin.reload();
                sender.sendMessage(plugin.message("reloaded"));
            }
            default -> sender.sendMessage(plugin.message("usage"));
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            return startingWith(List.of("status", "enderchests", "end", "reload"), args.length == 0 ? "" : args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("enderchests") || args[0].equalsIgnoreCase("end"))) {
            return startingWith(List.of("on", "off", "toggle"), args[1]);
        }
        return List.of();
    }

    @Override
    public String permission() {
        return EndLock.ADMIN;
    }

    private void feature(CommandSender sender, Feature feature, String[] args) {
        if (args.length < 2) {
            show(sender, feature);
            return;
        }
        boolean enabled;
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "on", "enable", "true" -> enabled = true;
            case "off", "disable", "false" -> enabled = false;
            case "toggle" -> enabled = !plugin.isEnabled(feature);
            default -> {
                sender.sendMessage(plugin.message("usage"));
                return;
            }
        }
        plugin.setEnabled(feature, enabled);
        show(sender, feature);
        if (sender instanceof Player) {
            plugin.getLogger().info(sender.getName() + " turned " + feature.name().toLowerCase(Locale.ROOT).replace('_', ' ')
                    + (enabled ? " on" : " off"));
        }
        if (plugin.getConfig().getBoolean("announce-changes", true)) {
            Component announcement = plugin.message(feature.announceMessage, enabled);
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                if (player != sender) {
                    player.sendMessage(announcement);
                }
            }
        }
    }

    private void show(CommandSender sender, Feature feature) {
        sender.sendMessage(plugin.message(feature.stateMessage, plugin.isEnabled(feature)));
    }

    private static List<String> startingWith(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.startsWith(lower)).toList();
    }
}
