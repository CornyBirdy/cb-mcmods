package dev.cornybirdy.corpes;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.players.NameAndId;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * <pre>
 * /corpse history [player]       open your recent deaths (other players need corpes:admin)
 * /corpse view (player) (number) open one death's inventory (corpes:admin)
 * /corpse reload                 reload config/corpes.json (corpes:admin)
 * </pre>
 */
final class CorpseCommand {

    private static final SuggestionProvider<CommandSourceStack> ONLINE_PLAYERS =
            (context, builder) -> SharedSuggestionProvider.suggest(context.getSource().getOnlinePlayerNames(), builder);

    private final Supplier<Settings> settings;
    private final Supplier<CorpseManager> manager;
    private final HistoryGui gui;

    private CorpseCommand(Supplier<Settings> settings, Supplier<CorpseManager> manager) {
        this.settings = settings;
        this.manager = manager;
        this.gui = new HistoryGui(settings, manager);
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher, Supplier<Settings> settings,
                         Supplier<CorpseManager> manager) {
        CorpseCommand command = new CorpseCommand(settings, manager);
        dispatcher.register(Commands.literal("corpse")
                .requires(source -> source.checkPermission(Corpes.USE_PERMISSION, true))
                .executes(context -> command.usage(context.getSource()))
                .then(Commands.literal("history")
                        .executes(context -> command.history(context.getSource(), null))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(ONLINE_PLAYERS)
                                .executes(context -> command.history(context.getSource(),
                                        StringArgumentType.getString(context, "player")))))
                .then(Commands.literal("view")
                        .requires(CorpseCommand::isAdmin)
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(ONLINE_PLAYERS)
                                .then(Commands.argument("number", IntegerArgumentType.integer(1))
                                        .executes(context -> command.view(context.getSource(),
                                                StringArgumentType.getString(context, "player"),
                                                IntegerArgumentType.getInteger(context, "number"))))))
                .then(Commands.literal("reload")
                        .requires(CorpseCommand::isAdmin)
                        .executes(context -> command.reload(context.getSource()))));
    }

    private static boolean isAdmin(CommandSourceStack source) {
        return source.checkPermission(Corpes.ADMIN_PERMISSION, PermissionLevel.GAMEMASTERS);
    }

    private int usage(CommandSourceStack source) {
        source.sendSystemMessage(Text.parse("<gray>/corpse history [player]"
                + (isAdmin(source) ? " <dark_gray>|</dark_gray> /corpse view (player) (number) <dark_gray>|</dark_gray> /corpse reload" : ""),
                Map.of()));
        return 1;
    }

    private int history(CommandSourceStack source, String targetName) {
        Settings s = settings.get();
        CorpseManager m = manager.get();
        ServerPlayer player = source.getPlayer();
        if (m == null) {
            return 0;
        }
        if (player == null) {
            source.sendSystemMessage(s.message("players-only"));
            return 0;
        }
        NameAndId target = player.nameAndId();
        if (targetName != null && !player.getGameProfile().name().equalsIgnoreCase(targetName)) {
            if (!gui.canBrowseAll(player)) {
                source.sendSystemMessage(s.message("no-permission"));
                return 0;
            }
            target = findPlayer(source, targetName);
            if (target == null) {
                return 0;
            }
        }
        // Admins who can browse everyone still get the screen, since it leads to the other players.
        if (m.storage().history(target.id()).isEmpty() && !gui.canBrowseAll(player)) {
            source.sendSystemMessage(s.message("no-history", Map.of("player", target.name())));
            return 0;
        }
        gui.openDeaths(player, target.id(), target.name());
        return 1;
    }

    private int view(CommandSourceStack source, String targetName, int index) {
        Settings s = settings.get();
        CorpseManager m = manager.get();
        ServerPlayer player = source.getPlayer();
        if (m == null) {
            return 0;
        }
        if (player == null) {
            source.sendSystemMessage(s.message("players-only"));
            return 0;
        }
        NameAndId target = findPlayer(source, targetName);
        if (target == null) {
            return 0;
        }
        if (!gui.canOpen(player, target.id())) {
            source.sendSystemMessage(s.message("no-permission"));
            return 0;
        }
        List<HistoryEntry> entries = m.storage().history(target.id());
        if (index > entries.size()) {
            source.sendSystemMessage(s.message("invalid-number",
                    Map.of("index", String.valueOf(index), "player", target.name())));
            return 0;
        }
        gui.openView(player, target.id(), target.name(), index, entries.get(index - 1));
        return 1;
    }

    private int reload(CommandSourceStack source) {
        if (Corpes.reload()) {
            source.sendSystemMessage(settings.get().message("reloaded"));
            return 1;
        }
        source.sendSystemMessage(Text.parse("<red>Could not read config/corpes.json, see the server log.", Map.of()));
        return 0;
    }

    private NameAndId findPlayer(CommandSourceStack source, String name) {
        MinecraftServer server = source.getServer();
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) {
            return online.nameAndId();
        }
        NameAndId cached = server.services().nameToIdCache().get(name).orElse(null);
        if (cached == null) {
            source.sendSystemMessage(settings.get().message("unknown-player", Map.of("player", name)));
        }
        return cached;
    }
}
