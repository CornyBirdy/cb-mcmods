package dev.cornybirdy.gatekeeper;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.OfflinePlayer;
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
 * /gatekeeper pvp                             show the PvP settings
 * /gatekeeper pvpdefault [on|off]             PvP for players who never used /pvp
 * /gatekeeper pvpcooldown [seconds]           wait between /pvp changes
 * /gatekeeper pvpcombat [seconds]             how long after a hit players can't turn PvP off
 * /gatekeeper combatlog [on|off|toggle]       kill players who log out in combat
 * /gatekeeper pvpforce (player) [on|off|clear] lock a player's PvP on or off, or let them choose again
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
                pvpSettings(sender);
                sender.sendMessage(plugin.message("usage"));
            }
            case "pvp" -> pvpSettings(sender);
            case "pvpdefault" -> option(sender, Pvp.DEFAULT, args);
            case "combatlog" -> option(sender, Pvp.COMBAT_LOG, args);
            case "pvpcooldown" -> seconds(sender, Pvp.COOLDOWN, args);
            case "pvpcombat" -> seconds(sender, Pvp.COMBAT, args);
            case "pvpforce" -> force(sender, args);
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
            List<String> options = new ArrayList<>(List.of("status", "reload", "pvp", "pvpdefault", "pvpcooldown", "pvpcombat", "combatlog", "pvpforce"));
            for (Feature feature : Feature.values()) {
                options.add(feature.command);
            }
            return startingWith(options, args.length == 0 ? "" : args[0]);
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && (feature(sub) != null || sub.equals("combatlog") || sub.equals("pvpdefault"))) {
            return startingWith(List.of("on", "off", "toggle"), args[1]);
        }
        if (args.length == 2 && sub.equals("pvpforce")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return plugin.getServer().getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        if (args.length == 3 && sub.equals("pvpforce")) {
            return startingWith(List.of("on", "off", "clear"), args[2]);
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

    private void pvpSettings(CommandSender sender) {
        var config = plugin.getConfig();
        sender.sendMessage(plugin.message("pvp-default-state", config.getBoolean(Pvp.DEFAULT)));
        sender.sendMessage(plugin.message("pvp-cooldown-state", Placeholder.unparsed("seconds", String.valueOf(config.getInt(Pvp.COOLDOWN)))));
        sender.sendMessage(plugin.message("pvp-combat-state", Placeholder.unparsed("seconds", String.valueOf(config.getInt(Pvp.COMBAT)))));
        sender.sendMessage(plugin.message("combat-log-state", config.getBoolean(Pvp.COMBAT_LOG)));
    }

    /** {@code /gatekeeper pvpdefault|combatlog [on|off|toggle]} */
    private void option(CommandSender sender, String key, String[] args) {
        if (args.length < 2) {
            pvpSettings(sender);
            return;
        }
        boolean value;
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "on", "enable", "true" -> value = true;
            case "off", "disable", "false" -> value = false;
            case "toggle" -> value = !plugin.getConfig().getBoolean(key);
            default -> {
                sender.sendMessage(plugin.message("usage"));
                return;
            }
        }
        save(sender, key, value);
    }

    /** {@code /gatekeeper pvpcooldown|pvpcombat [seconds]} */
    private void seconds(CommandSender sender, String key, String[] args) {
        if (args.length < 2) {
            pvpSettings(sender);
            return;
        }
        int seconds;
        try {
            seconds = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            seconds = -1;
        }
        if (seconds < 0 || seconds > 86_400) {
            sender.sendMessage(plugin.message("usage"));
            return;
        }
        save(sender, key, seconds);
    }

    private void save(CommandSender sender, String key, Object value) {
        plugin.getConfig().set(key, value);
        plugin.saveConfig();
        if (sender instanceof Player) {
            plugin.getLogger().info(sender.getName() + " set " + key + " to " + value);
        }
        pvpSettings(sender);
    }

    /** {@code /gatekeeper pvpforce <player> [on|off|clear]} */
    private void force(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(plugin.message("usage"));
            return;
        }
        OfflinePlayer target = PvpCommand.find(plugin, args[1]);
        if (target == null) {
            sender.sendMessage(plugin.message("pvp-unknown-player", Placeholder.unparsed("player", args[1])));
            return;
        }
        String name = target.getName() != null ? target.getName() : args[1];
        if (args.length >= 3) {
            Boolean on;
            switch (args[2].toLowerCase(Locale.ROOT)) {
                case "on", "true" -> on = true;
                case "off", "false" -> on = false;
                case "clear", "none" -> on = null;
                default -> {
                    sender.sendMessage(plugin.message("usage"));
                    return;
                }
            }
            plugin.pvp().force(target.getUniqueId(), on);
            plugin.getLogger().info(sender.getName() + " set " + name + "'s PvP to "
                    + (on == null ? "their own choice" : on ? "locked on" : "locked off"));
            Player online = target.getPlayer();
            if (online != null) {
                online.sendMessage(plugin.message(on == null ? "pvp-forced-you-none" : on ? "pvp-forced-you-on" : "pvp-forced-you-off"));
            }
        }
        Boolean lock = plugin.pvp().forced(target.getUniqueId());
        String key = lock == null ? "pvp-force-none" : lock ? "pvp-force-on" : "pvp-force-off";
        sender.sendMessage(plugin.message(key, plugin.pvp().enabled(target), Placeholder.unparsed("player", name)));
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
