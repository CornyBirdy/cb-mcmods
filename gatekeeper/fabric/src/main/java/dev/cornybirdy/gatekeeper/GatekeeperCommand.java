package dev.cornybirdy.gatekeeper;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.players.NameAndId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
 * /gatekeeper pvpforce (player) [on|off|clear] lock a player's PvP on or off, or let them choose again
 * /gatekeeper reload                          reload config/gatekeeper.json
 *
 * /pvp [toggle]                              everyone (gatekeeper:pvp): show or switch your own PvP
 * /pvp allow|unallow (player)                 fight someone even with PvP off, once they allow you too
 * /pvp list                                   who you can fight with PvP off
 * </pre>
 */
final class GatekeeperCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger("Gatekeeper");
    private static final SuggestionProvider<CommandSourceStack> PLAYERS =
            (context, builder) -> SharedSuggestionProvider.suggest(context.getSource().getOnlinePlayerNames(), builder);

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
                .then(Commands.literal("pvpforce")
                        .then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS)
                                .executes(context -> force(context.getSource(), StringArgumentType.getString(context, "player"), null, false))
                                .then(Commands.literal("on").executes(context ->
                                        force(context.getSource(), StringArgumentType.getString(context, "player"), true, true)))
                                .then(Commands.literal("off").executes(context ->
                                        force(context.getSource(), StringArgumentType.getString(context, "player"), false, true)))
                                .then(Commands.literal("clear").executes(context ->
                                        force(context.getSource(), StringArgumentType.getString(context, "player"), null, true)))))
                .then(Commands.literal("reload")
                        .executes(context -> reload(context.getSource()))));
        dispatcher.register(Commands.literal("pvp")
                .requires(source -> source.checkPermission(Gatekeeper.PVP_PERMISSION, true))
                .executes(context -> pvpShow(context.getSource()))
                .then(Commands.literal("toggle").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayer();
                    Pvp pvp = Gatekeeper.pvp();
                    return player == null || pvp == null ? pvpShow(context.getSource())
                            : pvpSet(context.getSource(), !pvp.enabled(player.getUUID()));
                }))
                .then(Commands.literal("allow").then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS)
                        .executes(context -> allow(context.getSource(), StringArgumentType.getString(context, "player"), true))))
                .then(Commands.literal("unallow").then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS)
                        .executes(context -> allow(context.getSource(), StringArgumentType.getString(context, "player"), false))))
                .then(Commands.literal("list").executes(context -> list(context.getSource()))));
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
        if (pvp.forced(player.getUUID()) != null) {
            source.sendSystemMessage(settings.message("pvp-forced", pvp.forced(player.getUUID())));
        }
        return 1;
    }

    /** {@code /pvp allow|unallow <player>}: both players have to allow each other before it counts. */
    private static int allow(CommandSourceStack source, String name, boolean allow) {
        Settings settings = Gatekeeper.settings();
        ServerPlayer player = source.getPlayer();
        Pvp pvp = Gatekeeper.pvp();
        if (player == null || pvp == null) {
            source.sendSystemMessage(settings.message("pvp-players-only"));
            return 0;
        }
        NameAndId other = find(source.getServer(), name);
        Map<String, String> who = Map.of("player", other == null ? name : other.name());
        if (other == null) {
            source.sendSystemMessage(settings.message("pvp-unknown-player", null, who));
            return 0;
        }
        UUID me = player.getUUID();
        if (other.id().equals(me)) {
            source.sendSystemMessage(settings.message("pvp-allow-self"));
            return 0;
        }
        ServerPlayer otherOnline = source.getServer().getPlayerList().getPlayer(other.id());
        Map<String, String> byMe = Map.of("player", player.getGameProfile().name());
        if (allow) {
            if (!pvp.setAllowed(me, other.id(), true)) {
                source.sendSystemMessage(settings.message("pvp-already-allowed", null, who));
                return 0;
            }
            if (pvp.mutual(me, other.id())) {
                source.sendSystemMessage(settings.message("pvp-allowed-mutual", null, who));
                if (otherOnline != null) {
                    otherOnline.sendSystemMessage(settings.message("pvp-allowed-mutual", null, byMe));
                }
            } else {
                source.sendSystemMessage(settings.message("pvp-allowed", null, who));
                if (otherOnline != null) {
                    otherOnline.sendSystemMessage(settings.message("pvp-allow-request", null, byMe));
                }
            }
            return 1;
        }
        boolean wasMutual = pvp.mutual(me, other.id());
        if (wasMutual && pvp.inCombat(me)) {
            source.sendSystemMessage(settings.message("pvp-in-combat", null,
                    Map.of("seconds", String.valueOf(pvp.combatSecondsLeft(me)))));
            return 0;
        }
        if (!pvp.setAllowed(me, other.id(), false)) {
            source.sendSystemMessage(settings.message("pvp-not-allowed", null, who));
            return 0;
        }
        source.sendSystemMessage(settings.message("pvp-unallowed", null, who));
        if (wasMutual && otherOnline != null) {
            otherOnline.sendSystemMessage(settings.message("pvp-unallowed-other", null, byMe));
        }
        return 1;
    }

    /** {@code /pvp list}: who you can fight with PvP off, and who's still waiting. */
    private static int list(CommandSourceStack source) {
        Settings settings = Gatekeeper.settings();
        ServerPlayer player = source.getPlayer();
        Pvp pvp = Gatekeeper.pvp();
        if (player == null || pvp == null) {
            source.sendSystemMessage(settings.message("pvp-players-only"));
            return 0;
        }
        UUID me = player.getUUID();
        Set<UUID> mine = pvp.allowedBy(me);
        Set<UUID> theirs = pvp.allowing(me);
        if (mine.isEmpty() && theirs.isEmpty()) {
            source.sendSystemMessage(settings.message("pvp-list-empty"));
            return 1;
        }
        List<String> mutual = new ArrayList<>();
        List<String> waiting = new ArrayList<>();
        List<String> requests = new ArrayList<>();
        MinecraftServer server = source.getServer();
        for (UUID id : mine) {
            (theirs.contains(id) ? mutual : waiting).add(name(server, id));
        }
        for (UUID id : theirs) {
            if (!mine.contains(id)) {
                requests.add(name(server, id));
            }
        }
        String nobody = settings.message("nobody").getString();
        source.sendSystemMessage(settings.message("pvp-list", null, Map.of(
                "mutual", mutual.isEmpty() ? nobody : String.join(", ", mutual),
                "waiting", waiting.isEmpty() ? nobody : String.join(", ", waiting),
                "requests", requests.isEmpty() ? nobody : String.join(", ", requests))));
        return 1;
    }

    /** {@code /gatekeeper pvpforce <player> [on|off|clear]} */
    private static int force(CommandSourceStack source, String name, Boolean on, boolean change) {
        Settings settings = Gatekeeper.settings();
        Pvp pvp = Gatekeeper.pvp();
        if (pvp == null) {
            return 0;
        }
        NameAndId target = find(source.getServer(), name);
        if (target == null) {
            source.sendSystemMessage(settings.message("pvp-unknown-player", null, Map.of("player", name)));
            return 0;
        }
        Map<String, String> who = Map.of("player", target.name());
        if (change) {
            pvp.force(target.id(), on);
            LOGGER.info("{} set {}'s PvP to {}", source.getTextName(), target.name(), on == null ? "their own choice" : on ? "locked on" : "locked off");
            ServerPlayer online = source.getServer().getPlayerList().getPlayer(target.id());
            if (online != null) {
                online.sendSystemMessage(settings.message(on == null ? "pvp-forced-you-none" : on ? "pvp-forced-you-on" : "pvp-forced-you-off"));
            }
        }
        Boolean lock = pvp.forced(target.id());
        String key = lock == null ? "pvp-force-none" : lock ? "pvp-force-on" : "pvp-force-off";
        source.sendSystemMessage(settings.message(key, pvp.enabled(target.id()), who));
        return 1;
    }

    /** A player by name: online, or anyone the server has seen. */
    private static NameAndId find(MinecraftServer server, String name) {
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) {
            return online.nameAndId();
        }
        return server.services().nameToIdCache().get(name).orElse(null);
    }

    private static String name(MinecraftServer server, UUID id) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) {
            return online.getGameProfile().name();
        }
        return server.services().nameToIdCache().get(id).map(NameAndId::name).orElse(id.toString().substring(0, 8));
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
            case FORCED -> source.sendSystemMessage(settings.message("pvp-forced", pvp.forced(player.getUUID())));
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
