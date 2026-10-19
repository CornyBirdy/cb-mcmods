package dev.cornybirdy.gatekeeper;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;

/**
 * Works in the server console as well as in game (needs gatekeeper:admin, op level 2 without a permission mod).
 * {@code /gk} is short for {@code /gatekeeper}.
 * <pre>
 * /gatekeeper [status]                     show what's on and off
 * /gatekeeper enderchests [on|off|toggle]  ender chests on or off
 * /gatekeeper end [on|off|toggle]          the End on or off
 * /gatekeeper nether [on|off|toggle]       the Nether on or off
 * /gatekeeper reload                       reload config/gatekeeper.json
 * </pre>
 */
final class GatekeeperCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger("Gatekeeper");

    private GatekeeperCommand() {
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralCommandNode<CommandSourceStack> gatekeeper = dispatcher.register(Commands.literal("gatekeeper")
                .requires(source -> source.checkPermission(Gatekeeper.ADMIN_PERMISSION, PermissionLevel.GAMEMASTERS))
                .executes(context -> status(context.getSource()))
                .then(Commands.literal("status")
                        .executes(context -> status(context.getSource())))
                .then(feature(Feature.ENDER_CHESTS))
                .then(feature(Feature.END))
                .then(feature(Feature.NETHER))
                .then(Commands.literal("reload")
                        .executes(context -> reload(context.getSource()))));
        dispatcher.register(Commands.literal("gk")
                .requires(source -> source.checkPermission(Gatekeeper.ADMIN_PERMISSION, PermissionLevel.GAMEMASTERS))
                .executes(context -> status(context.getSource()))
                .redirect(gatekeeper));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> feature(Feature feature) {
        return Commands.literal(feature.command)
                .executes(context -> show(context.getSource(), feature))
                .then(Commands.literal("on")
                        .executes(context -> set(context.getSource(), feature, true)))
                .then(Commands.literal("off")
                        .executes(context -> set(context.getSource(), feature, false)))
                .then(Commands.literal("toggle")
                        .executes(context -> set(context.getSource(), feature,
                                !Gatekeeper.settings().isEnabled(feature))));
    }

    private static int status(CommandSourceStack source) {
        for (Feature feature : Feature.values()) {
            show(source, feature);
        }
        source.sendSystemMessage(Gatekeeper.settings().message("usage"));
        return 1;
    }

    private static int show(CommandSourceStack source, Feature feature) {
        Settings settings = Gatekeeper.settings();
        source.sendSystemMessage(settings.message(feature.stateMessage, settings.isEnabled(feature)));
        return 1;
    }

    private static int set(CommandSourceStack source, Feature feature, boolean enabled) {
        if (!Gatekeeper.setEnabled(source.getServer(), feature, enabled)) {
            source.sendSystemMessage(Text.parse("<red>Could not save config/gatekeeper.json, see the server log. "
                    + "The change works until the server restarts.", Map.of()));
        }
        Settings settings = Gatekeeper.settings();
        source.sendSystemMessage(settings.message(feature.stateMessage, enabled));
        if (source.getPlayer() != null) {
            LOGGER.info("{} turned {} {}", source.getTextName(), feature.name().toLowerCase(Locale.ROOT).replace('_', ' '),
                    enabled ? "on" : "off");
        }
        if (settings.announces(feature)) {
            Component announcement = settings.message(feature.announceMessage, enabled);
            for (ServerPlayer player : source.getServer().getPlayerList().getPlayers()) {
                if (player != source.getPlayer()) {
                    player.sendSystemMessage(announcement);
                }
            }
        }
        return 1;
    }

    private static int reload(CommandSourceStack source) {
        if (Gatekeeper.reload()) {
            source.sendSystemMessage(Gatekeeper.settings().message("reloaded"));
            return 1;
        }
        source.sendSystemMessage(Text.parse("<red>Could not read config/gatekeeper.json, see the server log.", Map.of()));
        return 0;
    }
}
