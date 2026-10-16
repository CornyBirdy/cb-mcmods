package dev.cornybirdy.papercorpes;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * <pre>
 * /corpse history [player]       list recent deaths (other players need papercorpes.admin)
 * /corpse view (player) (number) open a copy of an old death inventory (papercorpes.admin)
 * /corpse reload                 reload config.yml (papercorpes.admin)
 * /corpse config [option] [value]  show or change an option (papercorpes.config)
 * </pre>
 */
final class CorpseCommand implements BasicCommand {

    private static final String ADMIN = "papercorpes.admin";
    private static final String CONFIG = "papercorpes.config";

    private final PaperCorpes plugin;
    private final CorpseStorage storage;

    CorpseCommand(PaperCorpes plugin, CorpseStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "history" -> history(sender, args);
            case "view" -> view(sender, args);
            case "reload" -> reload(sender);
            case "config" -> config(sender, args);
            default -> sender.sendMessage(plugin.message("usage"));
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        boolean admin = sender.hasPermission(ADMIN);
        if (args.length <= 1) {
            List<String> options = new ArrayList<>(List.of("history"));
            if (admin) {
                options.addAll(List.of("view", "reload"));
            }
            if (sender.hasPermission(CONFIG)) {
                options.add("config");
            }
            return startingWith(options, args.length == 0 ? "" : args[0]);
        }
        if (args[0].equalsIgnoreCase("config") && sender.hasPermission(CONFIG)) {
            if (args.length == 2) {
                return startingWith(ConfigOption.ALL.stream().map(ConfigOption::key).toList(), args[1]);
            }
            ConfigOption option = ConfigOption.find(args[1]);
            if (args.length == 3 && option != null) {
                return startingWith(option.kind() == ConfigOption.Kind.BOOLEAN
                        ? List.of("true", "false") : List.of(String.valueOf(plugin.getConfig().get(option.key()))), args[2]);
            }
            return List.of();
        }
        if (args.length == 2 && admin && (args[0].equalsIgnoreCase("history") || args[0].equalsIgnoreCase("view"))) {
            List<String> names = Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
            return startingWith(names, args[1]);
        }
        return List.of();
    }

    @Override
    public String permission() {
        return "papercorpes.use";
    }

    private void history(CommandSender sender, String[] args) {
        OfflinePlayer target;
        if (args.length >= 2) {
            if (!sender.hasPermission(ADMIN) && !sender.getName().equalsIgnoreCase(args[1])) {
                sender.sendMessage(plugin.message("no-permission"));
                return;
            }
            target = findPlayer(sender, args[1]);
            if (target == null) {
                return;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage(plugin.message("players-only"));
            return;
        }

        String name = nameOf(target, args.length >= 2 ? args[1] : sender.getName());
        List<HistoryEntry> entries = storage.history(target.getUniqueId());
        if (entries.isEmpty()) {
            sender.sendMessage(plugin.message("no-history", Placeholder.unparsed("player", name)));
            return;
        }

        boolean admin = sender.hasPermission(ADMIN);
        sender.sendMessage(plugin.message("history-header", Placeholder.unparsed("player", name)));
        for (int i = 0; i < entries.size(); i++) {
            HistoryEntry entry = entries.get(i);
            int index = i + 1;
            Component line = plugin.message("history-entry",
                    Placeholder.unparsed("index", String.valueOf(index)),
                    Placeholder.unparsed("date", plugin.settings().formatDate(entry.time())),
                    Placeholder.unparsed("world", entry.world()),
                    Placeholder.unparsed("x", String.valueOf((int) Math.floor(entry.x()))),
                    Placeholder.unparsed("y", String.valueOf((int) Math.floor(entry.y()))),
                    Placeholder.unparsed("z", String.valueOf((int) Math.floor(entry.z()))),
                    Placeholder.unparsed("cause", entry.cause()));
            if (admin) {
                line = line.append(plugin.message("history-view")
                        .clickEvent(ClickEvent.runCommand("/corpse view " + name + " " + index))
                        .hoverEvent(HoverEvent.showText(plugin.message("history-view-hover"))));
            }
            sender.sendMessage(line);
        }
    }

    private void view(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN)) {
            sender.sendMessage(plugin.message("no-permission"));
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.message("players-only"));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(plugin.message("usage"));
            return;
        }
        OfflinePlayer target = findPlayer(sender, args[1]);
        if (target == null) {
            return;
        }
        String name = nameOf(target, args[1]);
        List<HistoryEntry> entries = storage.history(target.getUniqueId());
        int index;
        try {
            index = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            index = -1;
        }
        if (index < 1 || index > entries.size()) {
            sender.sendMessage(plugin.message("invalid-number",
                    Placeholder.unparsed("index", args[2]), Placeholder.unparsed("player", name)));
            return;
        }
        player.openInventory(new HistoryMenu(plugin, target.getUniqueId(), name, index, entries.get(index - 1)).getInventory());
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission(ADMIN)) {
            sender.sendMessage(plugin.message("no-permission"));
            return;
        }
        plugin.reload();
        sender.sendMessage(plugin.message("reloaded"));
    }

    /** /corpse config lists the options, /corpse config (option) shows one, /corpse config (option) (value) changes it. */
    private void config(CommandSender sender, String[] args) {
        if (!sender.hasPermission(CONFIG)) {
            sender.sendMessage(plugin.message("no-permission"));
            return;
        }
        if (args.length == 1) {
            sender.sendMessage(plugin.message("config-header"));
            for (ConfigOption option : ConfigOption.ALL) {
                sender.sendMessage(configEntry(option));
            }
            return;
        }
        ConfigOption option = ConfigOption.find(args[1]);
        if (option == null) {
            sender.sendMessage(plugin.message("config-unknown", Placeholder.unparsed("key", args[1])));
            return;
        }
        if (args.length == 2) {
            sender.sendMessage(configEntry(option));
            return;
        }

        // The value is the rest of the line, because the date format contains spaces.
        String raw = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        Object value;
        try {
            value = option.parse(raw);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(plugin.message("config-invalid", Placeholder.unparsed("key", option.key()),
                    Placeholder.unparsed("value", raw), Placeholder.unparsed("reason", e.getMessage())));
            return;
        }
        plugin.getConfig().set(option.key(), value);
        plugin.saveConfig();
        plugin.reload();
        sender.sendMessage(plugin.message("config-set", Placeholder.unparsed("key", option.key()),
                Placeholder.unparsed("value", String.valueOf(value))));
    }

    private Component configEntry(ConfigOption option) {
        return plugin.message("config-entry", Placeholder.unparsed("key", option.key()),
                Placeholder.unparsed("value", String.valueOf(plugin.getConfig().get(option.key()))));
    }

    private OfflinePlayer findPlayer(CommandSender sender, String name) {
        Player online = Bukkit.getPlayerExact(name);
        OfflinePlayer target = online != null ? online : Bukkit.getOfflinePlayerIfCached(name);
        if (target == null) {
            sender.sendMessage(plugin.message("unknown-player", Placeholder.unparsed("player", name)));
        }
        return target;
    }

    private static String nameOf(OfflinePlayer player, String fallback) {
        String name = player.getName();
        return name != null ? name : fallback;
    }

    private static List<String> startingWith(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }
}
