package dev.cornybirdy.gatekeeper;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Works in the server console as well as in game (needs gatekeeper.admin, ops have it). {@code /gk} is short for
 * {@code /gatekeeper}.
 * <pre>
 * /gatekeeper [status]                        show what's on and off
 * /gatekeeper enderchests [on|off|toggle]     ender chests on or off
 * /gatekeeper end [on|off|toggle]             the End on or off
 * /gatekeeper nether [on|off|toggle]          the Nether on or off
 * /gatekeeper netheritearmor [on|off|toggle]  netherite armor on or off
 * /gatekeeper netheritetools [on|off|toggle]  netherite tools on or off
 * /gatekeeper reload                          reload config.yml
 * </pre>
 */
final class GatekeeperCommand implements BasicCommand {

    private final Gatekeeper plugin;

    GatekeeperCommand(Gatekeeper plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        Feature feature = feature(sub);
        if (feature != null) {
            feature(sender, feature, args);
            return;
        }
        switch (sub) {
            case "status" -> {
                for (Feature each : Feature.values()) {
                    show(sender, each);
                }
                sender.sendMessage(plugin.message("usage"));
            }
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
            List<String> options = new ArrayList<>(List.of("status", "reload"));
            for (Feature feature : Feature.values()) {
                options.add(feature.command);
            }
            return startingWith(options, args.length == 0 ? "" : args[0]);
        }
        if (args.length == 2 && feature(args[0].toLowerCase(Locale.ROOT)) != null) {
            return startingWith(List.of("on", "off", "toggle"), args[1]);
        }
        return List.of();
    }

    @Override
    public String permission() {
        return Gatekeeper.ADMIN;
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
        if (plugin.getConfig().getBoolean(feature.announceMessage, feature.announceByDefault)) {
            Component announcement = plugin.message(feature.announceMessage, enabled);
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                if (player != sender) {
                    player.sendMessage(announcement);
                }
            }
        }
    }

    private static Feature feature(String command) {
        for (Feature feature : Feature.values()) {
            if (feature.command.equals(command)) {
                return feature;
            }
        }
        return null;
    }

    private void show(CommandSender sender, Feature feature) {
        sender.sendMessage(plugin.message(feature.stateMessage, plugin.isEnabled(feature)));
    }

    private static List<String> startingWith(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.startsWith(lower)).toList();
    }
}
