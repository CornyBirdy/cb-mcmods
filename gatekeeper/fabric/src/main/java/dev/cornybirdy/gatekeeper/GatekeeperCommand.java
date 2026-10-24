package dev.cornybirdy.gatekeeper;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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
 * /gatekeeper reload                          reload config/gatekeeper.json
 *
 * /pvp [on|off|toggle]                        everyone (gatekeeper:pvp): turn your own PvP on or off
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
                .then(feature(Feature.NETHERITE_ARMOR))
                .then(feature(Feature.NETHERITE_TOOLS))
                .then(Commands.literal("pvp")
                        .executes(context -> pvpSettings(context.getSource())))
                .then(Commands.literal("pvpdefault")
                        .executes(context -> pvpSettings(context.getSource()))
                        .then(Commands.literal("on").executes(context -> setOption(context.getSource(), Pvp.DEFAULT, true)))
                        .then(Commands.literal("off").executes(context -> setOption(context.getSource(), Pvp.DEFAULT, false))))
                .then(seconds("pvpcooldown", Pvp.COOLDOWN))
                .then(seconds("pvpcombat", Pvp.COMBAT))
                .then(Commands.literal("combatlog")
                        .executes(context -> pvpSettings(context.getSource()))
                        .then(Commands.literal("on").executes(context -> setOption(context.getSource(), Pvp.COMBAT_LOG, true)))
                        .then(Commands.literal("off").executes(context -> setOption(context.getSource(), Pvp.COMBAT_LOG, false)))
                        .then(Commands.literal("toggle").executes(context -> setOption(context.getSource(), Pvp.COMBAT_LOG,
                                !Gatekeeper.settings().option(Pvp.COMBAT_LOG)))))
                .then(Commands.literal("reload")
                        .executes(context -> reload(context.getSource()))));
        dispatcher.register(Commands.literal("pvp")
                .requires(source -> source.checkPermission(Gatekeeper.PVP_PERMISSION, true))
                .executes(context -> pvpShow(context.getSource()))
                .then(Commands.literal("on").executes(context -> pvpSet(context.getSource(), true)))
                .then(Commands.literal("off").executes(context -> pvpSet(context.getSource(), false)))
                .then(Commands.literal("toggle").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayer();
                    Pvp pvp = Gatekeeper.pvp();
                    return player == null || pvp == null ? pvpShow(context.getSource())
                            : pvpSet(context.getSource(), !pvp.enabled(player.getUUID()));
                })));
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

    private static LiteralArgumentBuilder<CommandSourceStack> seconds(String command, String key) {
        return Commands.literal(command)
                .executes(context -> pvpSettings(context.getSource()))
                .then(Commands.argument("seconds", IntegerArgumentType.integer(0, 86_400))
                        .executes(context -> setOption(context.getSource(), key, IntegerArgumentType.getInteger(context, "seconds"))));
    }

    private static int pvpSettings(CommandSourceStack source) {
        Settings settings = Gatekeeper.settings();
        source.sendSystemMessage(settings.message("pvp-default-state", settings.option(Pvp.DEFAULT)));
        source.sendSystemMessage(settings.message("pvp-cooldown-state", null,
                Map.of("seconds", String.valueOf(settings.integer(Pvp.COOLDOWN)))));
        source.sendSystemMessage(settings.message("pvp-combat-state", null,
                Map.of("seconds", String.valueOf(settings.integer(Pvp.COMBAT)))));
        source.sendSystemMessage(settings.message("combat-log-state", settings.option(Pvp.COMBAT_LOG)));
        return 1;
    }

    private static int setOption(CommandSourceStack source, String key, Object value) {
        try {
            Gatekeeper.settings().set(key, value);
        } catch (Exception e) {
            LOGGER.error("Could not save config/gatekeeper.json, the change is lost on restart", e);
            source.sendSystemMessage(Text.parse("<red>Could not save config/gatekeeper.json, see the server log. "
                    + "The change works until the server restarts.", Map.of()));
        }
        if (source.getPlayer() != null) {
            LOGGER.info("{} set {} to {}", source.getTextName(), key, value);
        }
        return pvpSettings(source);
    }

    private static int pvpShow(CommandSourceStack source) {
        Settings settings = Gatekeeper.settings();
        ServerPlayer player = source.getPlayer();
        Pvp pvp = Gatekeeper.pvp();
        if (player == null || pvp == null) {
            return pvpSettings(source);
        }
        source.sendSystemMessage(settings.message("pvp-status", pvp.enabled(player.getUUID())));
        return 1;
    }

    private static int pvpSet(CommandSourceStack source, boolean on) {
        Settings settings = Gatekeeper.settings();
        ServerPlayer player = source.getPlayer();
        Pvp pvp = Gatekeeper.pvp();
        if (player == null || pvp == null) {
            source.sendSystemMessage(settings.message("pvp-players-only"));
            return 0;
        }
        Pvp.Result result = pvp.set(player.getUUID(), on);
        Map<String, String> seconds = Map.of("seconds", String.valueOf(result.secondsLeft()));
        switch (result.outcome()) {
            case CHANGED -> source.sendSystemMessage(settings.message(on ? "pvp-now-on" : "pvp-now-off"));
            case UNCHANGED -> source.sendSystemMessage(settings.message("pvp-already", on));
            case COOLDOWN -> source.sendSystemMessage(settings.message("pvp-cooldown", null, seconds));
            case IN_COMBAT -> source.sendSystemMessage(settings.message("pvp-in-combat", null, seconds));
        }
        return result.outcome() == Pvp.Outcome.CHANGED ? 1 : 0;
    }

    private static int status(CommandSourceStack source) {
        for (Feature feature : Feature.values()) {
            show(source, feature);
        }
        pvpSettings(source);
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
