package dev.cornybirdy.papercorpes;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * <pre>
 * /corpse history [player]       open your recent deaths (other players need papercorpes.admin)
 * /corpse view (player) (number) open one death's inventory (papercorpes.admin)
 * /corpse reload                 reload config.yml (papercorpes.admin)
 * </pre>
 */
final class CorpseCommand implements BasicCommand {

    private static final String ADMIN = HistoryGui.ADMIN;

    private final PaperCorpes plugin;
    private final CorpseStorage storage;
    private final HistoryGui gui;

    CorpseCommand(PaperCorpes plugin, CorpseStorage storage, HistoryGui gui) {
        this.plugin = plugin;
        this.storage = storage;
        this.gui = gui;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "history" -> history(sender, args);
            case "view" -> view(sender, args);
            case "reload" -> reload(sender);
            default -> sender.sendMessage(plugin.message("usage"));
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        boolean admin = sender.hasPermission(ADMIN);
        if (args.length <= 1) {
            List<String> options = admin ? List.of("history", "view", "reload") : List.of("history");
            return startingWith(options, args.length == 0 ? "" : args[0]);
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
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.message("players-only"));
            return;
        }
        OfflinePlayer target = player;
        if (args.length >= 2) {
            if (!player.getName().equalsIgnoreCase(args[1]) && !gui.canBrowseAll(player)) {
                sender.sendMessage(plugin.message("no-permission"));
                return;
            }
            target = findPlayer(sender, args[1]);
            if (target == null) {
                return;
            }
        }

        String name = nameOf(target, args.length >= 2 ? args[1] : sender.getName());
        // Admins who can browse everyone still get the screen, since it leads to the other players.
        if (storage.history(target.getUniqueId()).isEmpty() && !gui.canBrowseAll(player)) {
            sender.sendMessage(plugin.message("no-history", Placeholder.unparsed("player", name)));
            return;
        }
        gui.openDeaths(player, target.getUniqueId(), name);
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
        if (!gui.canOpen(player, target.getUniqueId())) {
            sender.sendMessage(plugin.message("no-permission"));
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
        gui.openView(player, target.getUniqueId(), name, index, entries.get(index - 1));
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission(ADMIN)) {
            sender.sendMessage(plugin.message("no-permission"));
            return;
        }
        plugin.reload();
        sender.sendMessage(plugin.message("reloaded"));
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
