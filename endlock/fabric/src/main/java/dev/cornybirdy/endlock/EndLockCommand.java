package dev.cornybirdy.endlock;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
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
 * Works in the server console as well as in game (needs endlock:admin, op level 2 without a permission mod).
 * <pre>
 * /endlock [status]                     show what's on and off
 * /endlock enderchests [on|off|toggle]  ender chests on or off
 * /endlock end [on|off|toggle]          the End on or off
 * /endlock reload                       reload config/endlock.json
 * </pre>
 */
final class EndLockCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger("EndLock");

    private EndLockCommand() {
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("endlock")
                .requires(source -> source.checkPermission(EndLock.ADMIN_PERMISSION, PermissionLevel.GAMEMASTERS))
                .executes(context -> status(context.getSource()))
                .then(Commands.literal("status")
                        .executes(context -> status(context.getSource())))
                .then(feature("enderchests", Feature.ENDER_CHESTS))
                .then(feature("end", Feature.END))
                .then(Commands.literal("reload")
                        .executes(context -> reload(context.getSource()))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> feature(String name, Feature feature) {
        return Commands.literal(name)
                .executes(context -> show(context.getSource(), feature))
                .then(Commands.literal("on")
                        .executes(context -> set(context.getSource(), feature, true)))
                .then(Commands.literal("off")
                        .executes(context -> set(context.getSource(), feature, false)))
                .then(Commands.literal("toggle")
                        .executes(context -> set(context.getSource(), feature,
                                !EndLock.settings().isEnabled(feature))));
    }

    private static int status(CommandSourceStack source) {
        show(source, Feature.ENDER_CHESTS);
        show(source, Feature.END);
        source.sendSystemMessage(EndLock.settings().message("usage"));
        return 1;
    }

    private static int show(CommandSourceStack source, Feature feature) {
        Settings settings = EndLock.settings();
        source.sendSystemMessage(settings.message(feature.stateMessage, settings.isEnabled(feature)));
        return 1;
    }

    private static int set(CommandSourceStack source, Feature feature, boolean enabled) {
        if (!EndLock.setEnabled(source.getServer(), feature, enabled)) {
            source.sendSystemMessage(Text.parse("<red>Could not save config/endlock.json, see the server log. "
                    + "The change works until the server restarts.", Map.of()));
        }
        Settings settings = EndLock.settings();
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
        if (EndLock.reload()) {
            source.sendSystemMessage(EndLock.settings().message("reloaded"));
            return 1;
        }
        source.sendSystemMessage(Text.parse("<red>Could not read config/endlock.json, see the server log.", Map.of()));
        return 0;
    }
}
