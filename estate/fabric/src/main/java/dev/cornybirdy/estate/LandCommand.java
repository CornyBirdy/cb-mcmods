package dev.cornybirdy.estate;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.cornybirdy.estate.core.Action;
import dev.cornybirdy.estate.core.Claim;
import dev.cornybirdy.estate.core.ClaimManager;
import dev.cornybirdy.estate.core.Community;
import dev.cornybirdy.estate.core.Denial;
import dev.cornybirdy.estate.core.EstateConfig;
import dev.cornybirdy.estate.core.Flag;
import dev.cornybirdy.estate.core.Permission;
import dev.cornybirdy.estate.core.PlacementStore;
import dev.cornybirdy.estate.core.Problem;
import dev.cornybirdy.estate.core.TownZone;
import dev.cornybirdy.estate.core.TrustTier;
import dev.cornybirdy.estate.core.storage.SqliteStorage;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * {@code /land} (and its alias {@code /estate}). Without arguments it opens the menu; every menu action also has a
 * command. Admin commands that don't need a position or a menu work from the server console too.
 */
final class LandCommand {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private static final SuggestionProvider<CommandSourceStack> PLAYERS =
            (context, builder) -> SharedSuggestionProvider.suggest(context.getSource().getOnlinePlayerNames(), builder);
    private static final SuggestionProvider<CommandSourceStack> TIERS =
            (context, builder) -> SharedSuggestionProvider.suggest(Arrays.stream(TrustTier.values()).map(TrustTier::key), builder);
    private static final SuggestionProvider<CommandSourceStack> FLAGS =
            (context, builder) -> SharedSuggestionProvider.suggest(Arrays.stream(Flag.values()).map(f -> f.key), builder);
    private static final SuggestionProvider<CommandSourceStack> SETTINGS = (context, builder) -> {
        List<String> names = new java.util.ArrayList<>(EstateConfig.settingNames());
        for (Flag flag : Flag.values()) {
            names.add("flagDefaults." + flag.key);
        }
        for (TrustTier tier : TrustTier.values()) {
            names.add("trustTiers." + tier.key());
        }
        return SharedSuggestionProvider.suggest(names, builder);
    };
    private static final SuggestionProvider<CommandSourceStack> CLAIM_IDS = (context, builder) -> {
        EstateRuntime rt = Estate.runtime();
        return SharedSuggestionProvider.suggest(rt == null ? List.of()
                : rt.manager.claims().stream().map(c -> String.valueOf(c.id())).toList(), builder);
    };
    private static final SuggestionProvider<CommandSourceStack> OWN_CLAIMS = (context, builder) -> {
        EstateRuntime rt = Estate.runtime();
        net.minecraft.server.level.ServerPlayer player = context.getSource().getPlayer();
        return SharedSuggestionProvider.suggest(rt == null || player == null ? List.of()
                : rt.manager.claimsAccessibleBy(player.getUUID()).stream().map(Claim::name).toList(), builder);
    };
    private static final SuggestionProvider<CommandSourceStack> MY_COMMUNITIES = (context, builder) -> {
        EstateRuntime rt = Estate.runtime();
        net.minecraft.server.level.ServerPlayer player = context.getSource().getPlayer();
        return SharedSuggestionProvider.suggest(rt == null || player == null ? List.of()
                : rt.manager.communitiesOf(player.getUUID()).stream().map(Community::name).toList(), builder);
    };
    private static final SuggestionProvider<CommandSourceStack> INVITED_COMMUNITIES = (context, builder) -> {
        EstateRuntime rt = Estate.runtime();
        net.minecraft.server.level.ServerPlayer player = context.getSource().getPlayer();
        return SharedSuggestionProvider.suggest(rt == null || player == null ? List.of()
                : rt.manager.invitesFor(player.getUUID()).stream().map(Community::name).toList(), builder);
    };
    private static final SuggestionProvider<CommandSourceStack> DEFAULT_TIERS = (context, builder) ->
            SharedSuggestionProvider.suggest(List.of("access", "containers", "build", "none"), builder);
    private static final SuggestionProvider<CommandSourceStack> PARTNERS = (context, builder) -> {
        EstateRuntime rt = Estate.runtime();
        net.minecraft.server.level.ServerPlayer player = context.getSource().getPlayer();
        return SharedSuggestionProvider.suggest(rt == null || player == null ? List.of()
                : rt.manager.buildPartners(player.getUUID()).stream().map(rt::name).toList(), builder);
    };
    private static final SuggestionProvider<CommandSourceStack> TOWNS = (context, builder) -> {
        EstateRuntime rt = Estate.runtime();
        return SharedSuggestionProvider.suggest(rt == null ? List.of() : rt.manager.towns().stream().map(TownZone::name).toList(), builder);
    };

    private LandCommand() {
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralCommandNode<CommandSourceStack> land = dispatcher.register(Commands.literal("land")
                .executes(c -> run(c, LandCommand::open))
                .then(Commands.literal("help").executes(c -> run(c, (rt, src) -> help(rt, src))))
                .then(Commands.literal("menu").executes(c -> run(c, LandCommand::open)))
                .then(Commands.literal("claim")
                        .executes(c -> player(c, (rt, p) -> {
                            rt.claimTool.start(p);
                            return 1;
                        }))
                        .then(Commands.literal("confirm").executes(c -> player(c, (rt, p) -> rt.claimTool.confirm(p) ? 1 : 0)))
                        .then(Commands.literal("cancel").executes(c -> player(c, (rt, p) -> {
                            rt.claimTool.end(p);
                            rt.send(p, "draft-cancelled");
                            return 1;
                        })))
                        .then(Commands.literal("edit").executes(c -> player(c, (rt, p) -> {
                            Menus.editor(rt, p);
                            return 1;
                        })))
                        .then(Commands.literal("name")
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(c -> player(c, (rt, p) -> draftName(rt, p, StringArgumentType.getString(c, "name")))))))
                .then(corner("pos1", 1))
                .then(corner("pos2", 2))
                .then(Commands.literal("unclaim")
                        .executes(c -> player(c, (rt, p) -> unclaim(rt, p, false)))
                        .then(Commands.literal("confirm").executes(c -> player(c, (rt, p) -> unclaim(rt, p, true)))))
                .then(autoClaimBranch(Commands.literal("autoclaim")))
                .then(Commands.literal("trust")
                        .executes(c -> player(c, (rt, p) -> trustMenu(rt, p)))
                        .then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS)
                                .executes(c -> player(c, (rt, p) -> trust(rt, p, StringArgumentType.getString(c, "player"), "build")))
                                .then(Commands.argument("tier", StringArgumentType.word()).suggests(TIERS)
                                        .executes(c -> player(c, (rt, p) -> trust(rt, p, StringArgumentType.getString(c, "player"),
                                                StringArgumentType.getString(c, "tier")))))))
                .then(Commands.literal("untrust")
                        .then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS)
                                .executes(c -> player(c, (rt, p) -> untrust(rt, p, StringArgumentType.getString(c, "player"))))))
                .then(Commands.literal("trustlist").executes(c -> player(c, (rt, p) -> trustMenu(rt, p))))
                .then(Commands.literal("info").executes(c -> player(c, LandCommand::info))
                        .then(Commands.argument("claim", StringArgumentType.greedyString()).suggests(OWN_CLAIMS)
                                .executes(c -> player(c, (rt, p) -> {
                                    Claim claim = Menus.lookup(rt, p, StringArgumentType.getString(c, "claim"));
                                    if (claim != null) {
                                        sendClaimInfo(rt, p.createCommandSourceStack(), claim);
                                    }
                                    return claim == null ? 0 : 1;
                                }))))
                .then(Commands.literal("manage")
                        .executes(c -> player(c, (rt, p) -> {
                            Menus.withClaim(rt, p, null, claim -> Menus.claimMenu(rt, p, claim));
                            return 1;
                        }))
                        .then(Commands.argument("claim", StringArgumentType.greedyString()).suggests(OWN_CLAIMS)
                                .executes(c -> player(c, (rt, p) -> {
                                    Claim claim = Menus.lookup(rt, p, StringArgumentType.getString(c, "claim"));
                                    if (claim != null) {
                                        Menus.claimMenu(rt, p, claim);
                                    }
                                    return claim == null ? 0 : 1;
                                }))))
                .then(Commands.literal("select")
                        .then(Commands.literal("none").executes(c -> player(c, (rt, p) -> {
                            rt.session(p).selectedClaim = 0;
                            rt.send(p, "selected-none");
                            return 1;
                        })))
                        .then(Commands.argument("claim", StringArgumentType.greedyString()).suggests(OWN_CLAIMS)
                                .executes(c -> player(c, (rt, p) -> {
                                    Claim claim = Menus.lookup(rt, p, StringArgumentType.getString(c, "claim"));
                                    if (claim != null) {
                                        rt.session(p).selectedClaim = claim.id();
                                        rt.send(p, "selected", "claim", claim.name());
                                    }
                                    return claim == null ? 0 : 1;
                                }))))
                .then(Commands.literal("defaulttier")
                        .then(Commands.argument("tier", StringArgumentType.word()).suggests(DEFAULT_TIERS)
                                .executes(c -> player(c, (rt, p) -> defaultTier(rt, p, StringArgumentType.getString(c, "tier"))))))
                .then(community())
                .then(Commands.literal("partner")
                        .executes(c -> player(c, (rt, p) -> partnerList(rt, p)))
                        .then(Commands.literal("list").executes(c -> player(c, (rt, p) -> partnerList(rt, p))))
                        .then(Commands.literal("add")
                                .then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS)
                                        .executes(c -> player(c, (rt, p) -> partner(rt, p, StringArgumentType.getString(c, "player"), true)))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("player", StringArgumentType.word()).suggests(PARTNERS)
                                        .executes(c -> player(c, (rt, p) -> partner(rt, p, StringArgumentType.getString(c, "player"), false))))))
                .then(Commands.literal("list").executes(c -> player(c, (rt, p) -> {
                    Menus.claimList(rt, p, p.getUUID());
                    return 1;
                })))
                .then(Commands.literal("borders").executes(c -> player(c, (rt, p) -> {
                    rt.borders.show(p, 0);
                    rt.send(p, "borders-shown", "seconds", rt.config.borderParticleSeconds);
                    return 1;
                })))
                .then(Commands.literal("resize").executes(c -> player(c, (rt, p) -> {
                    Claim claim = Menus.target(rt, p, Permission.RESIZE);
                    if (claim != null) {
                        Menus.resize(rt, p, claim);
                    }
                    return claim == null ? 0 : 1;
                })))
                .then(Commands.literal("rename")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(c -> player(c, (rt, p) -> rename(rt, p, StringArgumentType.getString(c, "name"))))))
                .then(Commands.literal("flags").executes(c -> player(c, (rt, p) -> {
                    Claim claim = Menus.target(rt, p, null);
                    if (claim != null) {
                        Menus.flags(rt, p, claim);
                    }
                    return claim == null ? 0 : 1;
                })))
                .then(Commands.literal("flag")
                        .then(Commands.argument("flag", StringArgumentType.word()).suggests(FLAGS)
                                .then(Commands.argument("value", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("on", "off", "default"), b))
                                        .executes(c -> player(c, (rt, p) -> flag(rt, p, StringArgumentType.getString(c, "flag"),
                                                StringArgumentType.getString(c, "value")))))))
                .then(Commands.literal("donate")
                        .then(Commands.argument("blocks", IntegerArgumentType.integer(1))
                                .executes(c -> player(c, (rt, p) -> donate(rt, p, IntegerArgumentType.getInteger(c, "blocks"), true)))))
                .then(Commands.literal("withdraw")
                        .then(Commands.argument("blocks", IntegerArgumentType.integer(1))
                                .executes(c -> player(c, (rt, p) -> donate(rt, p, IntegerArgumentType.getInteger(c, "blocks"), false)))))
                .then(admin()));
        dispatcher.register(Commands.literal("estate").executes(c -> run(c, LandCommand::open)).redirect(land));
        dispatcher.register(autoClaimBranch(Commands.literal("autoclaim")));
    }

    /** {@code /land pos1|pos2 [x y z]}: a corner where you stand, or at exact coordinates ({@code ~} works). */
    private static LiteralArgumentBuilder<CommandSourceStack> corner(String name, int which) {
        return Commands.literal(name)
                .executes(c -> player(c, (rt, p) -> {
                    rt.claimTool.pick(p, which, p.blockPosition().below());
                    return 1;
                }))
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(c -> player(c, (rt, p) -> {
                            BlockPos pos;
                            try {
                                pos = BlockPosArgument.getBlockPos(c, "pos");
                            } catch (Exception e) {
                                rt.send(p, "draft-bad-position");
                                return 0;
                            }
                            rt.claimTool.setExact(p, which, pos);
                            return 1;
                        })));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> autoClaimBranch(LiteralArgumentBuilder<CommandSourceStack> node) {
        return node.executes(c -> player(c, (rt, p) -> autoClaim(rt, p, !rt.manager.autoClaim(p.getUUID()))))
                .then(Commands.literal("on").executes(c -> player(c, (rt, p) -> autoClaim(rt, p, true))))
                .then(Commands.literal("off").executes(c -> player(c, (rt, p) -> autoClaim(rt, p, false))));
    }

    // ------------------------------------------------------------------ plumbing

    private static int run(CommandContext<CommandSourceStack> context, BiFunction<EstateRuntime, CommandSourceStack, Integer> action) {
        EstateRuntime rt = Estate.runtime();
        if (rt == null) {
            return 0;
        }
        return action.apply(rt, context.getSource());
    }

    private static int player(CommandContext<CommandSourceStack> context, BiFunction<EstateRuntime, ServerPlayer, Integer> action) {
        EstateRuntime rt = Estate.runtime();
        if (rt == null) {
            return 0;
        }
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendSystemMessage(rt.msg.get("players-only"));
            return 0;
        }
        return action.apply(rt, player);
    }

    private static boolean isAdmin(CommandSourceStack source) {
        return source.checkPermission(Estate.ADMIN_PERMISSION, PermissionLevel.GAMEMASTERS);
    }

    private static int open(EstateRuntime rt, CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return help(rt, source);
        }
        Menus.main(rt, player);
        return 1;
    }

    static int help(EstateRuntime rt, CommandSourceStack source) {
        source.sendSystemMessage(rt.msg.get("help"));
        if (isAdmin(source)) {
            source.sendSystemMessage(rt.msg.get("help-admin"));
        }
        return 1;
    }

    // ------------------------------------------------------------------ player commands

    private static int autoClaim(EstateRuntime rt, ServerPlayer player, boolean on) {
        rt.manager.setAutoClaim(player.getUUID(), on);
        rt.send(player, on ? "autoclaim-on" : "autoclaim-off", "threshold", rt.config.autoClaimThreshold);
        return 1;
    }

    private static int unclaim(EstateRuntime rt, ServerPlayer player, boolean confirmed) {
        Claim claim = Menus.target(rt, player, null);
        if (claim == null) {
            return 0;
        }
        if (!confirmed) {
            Menus.unclaim(rt, player, claim);
            return 1;
        }
        doUnclaim(rt, player, claim);
        return 1;
    }

    static void doUnclaim(EstateRuntime rt, ServerPlayer player, Claim claim) {
        if (rt.manager.claim(claim.id()) == null) {
            rt.send(player, "claim-gone");
            return;
        }
        boolean admin = !claim.owner().equals(player.getUUID());
        if (admin && !rt.bypassing(player)) {
            rt.send(player, "unclaim-owner-only");
            return;
        }
        rt.manager.delete(claim, player.getUUID(), admin ? "admin-delete" : "unclaim");
        rt.session(player).noBudgetShown = false;
        rt.send(player, "unclaimed", "claim", claim.name(), "left", rt.manager.budgetLeft(player.getUUID()));
        if (admin) {
            rt.notifyOwner(claim.owner(), rt.msg.get("admin-deleted-your-claim", "claim", claim.name()));
        }
    }

    private static int trustMenu(EstateRuntime rt, ServerPlayer player) {
        Claim claim = Menus.target(rt, player, Permission.TRUST);
        if (claim != null) {
            Menus.trust(rt, player, claim, 0);
        }
        return claim == null ? 0 : 1;
    }

    private static int trust(EstateRuntime rt, ServerPlayer player, String name, String tierName) {
        TrustTier tier = TrustTier.byKey(tierName);
        if (tier == null) {
            rt.send(player, "unknown-tier", "tier", tierName);
            return 0;
        }
        Claim claim = Menus.target(rt, player, Permission.TRUST);
        if (claim == null) {
            return 0;
        }
        if (tier == TrustTier.MANAGE && !rt.manager.isOwnerLike(claim, player.getUUID()) && !rt.bypassing(player)) {
            rt.send(player, "trust-owner-only");
            return 0;
        }
        NameAndId target = rt.findPlayer(name);
        if (target == null) {
            rt.send(player, "unknown-player", "player", name);
            return 0;
        }
        if (target.id().equals(claim.owner())) {
            rt.send(player, "trust-self");
            return 0;
        }
        rt.manager.ensurePlayer(target.id(), target.name());
        rt.manager.trust(claim, target.id(), tier, player.getUUID());
        rt.send(player, "trusted", "player", target.name(), "tier", Menus.tierName(rt, tier), "claim", claim.name());
        rt.notifyOwner(target.id(), rt.msg.get("trusted-you", "player", player.getGameProfile().name(),
                "claim", claim.name(), "tier", Menus.tierName(rt, tier)));
        return 1;
    }

    private static int untrust(EstateRuntime rt, ServerPlayer player, String name) {
        Claim claim = Menus.target(rt, player, Permission.TRUST);
        if (claim == null) {
            return 0;
        }
        NameAndId target = rt.findPlayer(name);
        if (target == null || !claim.trust().containsKey(target.id())) {
            rt.send(player, "not-trusted", "player", name);
            return 0;
        }
        if (claim.trust().get(target.id()) == TrustTier.MANAGE && !rt.manager.isOwnerLike(claim, player.getUUID()) && !rt.bypassing(player)) {
            rt.send(player, "trust-owner-only");
            return 0;
        }
        rt.manager.trust(claim, target.id(), null, player.getUUID());
        rt.send(player, "untrusted", "player", target.name(), "claim", claim.name());
        return 1;
    }

    private static int info(EstateRuntime rt, ServerPlayer player) {
        BlockPos pos = player.blockPosition();
        String world = EstateRuntime.world(player.level());
        Claim claim = rt.claimAt(player.level(), pos);
        if (claim == null) {
            Denial denial = rt.manager.checkBuild(world, pos.getX(), pos.getY(), pos.getZ(), player.getUUID());
            if (denial != null && denial.kind() == Denial.Kind.BUFFER) {
                rt.send(player, "info-buffer", "owner", rt.name(denial.claim().owner()));
            } else if (rt.manager.inTown(world, pos.getX(), pos.getZ())) {
                rt.send(player, "info-town");
            } else {
                rt.send(player, "info-wilderness");
            }
            return 1;
        }
        sendClaimInfo(rt, player.createCommandSourceStack(), claim);
        return 1;
    }

    private static void sendClaimInfo(EstateRuntime rt, CommandSourceStack source, Claim claim) {
        ClaimManager manager = rt.manager;
        StringBuilder trusted = new StringBuilder();
        for (Map.Entry<UUID, TrustTier> entry : claim.trust().entrySet()) {
            if (!trusted.isEmpty()) {
                trusted.append(", ");
            }
            trusted.append(rt.name(entry.getKey())).append(" (").append(Menus.tierName(rt, entry.getValue())).append(')');
        }
        StringBuilder flags = new StringBuilder();
        for (Flag flag : Flag.values()) {
            boolean value = manager.flag(claim, flag);
            if (!value) {
                if (!flags.isEmpty()) {
                    flags.append(", ");
                }
                flags.append(flag.key);
            }
        }
        int[] center = claim.region().middle();
        long days = manager.daysUntilExpiry(claim.owner());
        source.sendSystemMessage(rt.msg.get("info-claim", "claim", claim.name(), "id", claim.id(),
                "owner", rt.name(claim.owner()), "blocks", claim.size(), "miny", claim.minY(), "maxy", claim.maxY(),
                "x", center[0], "z", center[1],
                "trusted", trusted.isEmpty() ? rt.msg.raw("nobody") : trusted.toString(),
                "flags", flags.isEmpty() ? rt.msg.raw("all-protected") : flags.toString(),
                "expiry", claim.expiryExempt() ? rt.msg.raw("expiry-exempt")
                        : days < 0 ? rt.msg.raw("expiry-never") : rt.msg.raw("expiry-days").replace("<days>", String.valueOf(days)),
                "pool", claim.donated() > 0 ? rt.msg.raw("info-pool").replace("<blocks>", String.valueOf(claim.donated())) : ""));
    }

    private static int rename(EstateRuntime rt, ServerPlayer player, String name) {
        Claim claim = Menus.target(rt, player, Permission.RESIZE);
        if (claim == null) {
            return 0;
        }
        String clean = cleanName(rt, player, name, claim);
        if (clean == null) {
            return 0;
        }
        rt.manager.rename(claim, clean, player.getUUID());
        rt.send(player, "renamed", "claim", clean);
        return 1;
    }

    /** A claim name with the formatting characters taken out, or null (with a message) if it's empty, long or in use. */
    private static String cleanName(EstateRuntime rt, ServerPlayer player, String name, Claim except) {
        String clean = name.replaceAll("[<>]", "").trim();
        if (clean.isEmpty() || clean.length() > 32) {
            rt.send(player, "rename-invalid");
            return null;
        }
        if (rt.manager.nameTaken(except == null ? player.getUUID() : except.owner(), clean, except)) {
            rt.send(player, "name-taken", "claim", clean);
            return null;
        }
        return clean;
    }

    /** {@code /land claim name <name>}: what the claim being drawn will be called. */
    private static int draftName(EstateRuntime rt, ServerPlayer player, String name) {
        if (rt.session(player).draft == null && rt.session(player).selection == null) {
            rt.send(player, "draft-name-none");
            return 0;
        }
        String clean = cleanName(rt, player, name, null);
        if (clean == null) {
            return 0;
        }
        rt.session(player).draftName = clean;
        rt.send(player, "draft-named", "claim", clean);
        return 1;
    }

    private static int defaultTier(EstateRuntime rt, ServerPlayer player, String tierName) {
        TrustTier tier = tierName.equalsIgnoreCase("none") ? null : TrustTier.byKey(tierName);
        if (tier == null && !tierName.equalsIgnoreCase("none")) {
            rt.send(player, "unknown-tier", "tier", tierName);
            return 0;
        }
        Claim claim = Menus.target(rt, player, Permission.TRUST);
        return claim == null ? 0 : Communities.defaultTier(rt, player, claim, tier);
    }

    /** {@code /land community ...}: groups that share claims. Community names are one word. */
    private static LiteralArgumentBuilder<CommandSourceStack> community() {
        return Commands.literal("community")
                .executes(c -> player(c, (rt, p) -> {
                    Menus.communities(rt, p);
                    return 1;
                }))
                .then(Commands.literal("list").executes(c -> player(c, (rt, p) -> {
                    Menus.communities(rt, p);
                    return 1;
                })))
                .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(c -> player(c, (rt, p) -> Communities.create(rt, p, StringArgumentType.getString(c, "name"))))))
                .then(Commands.literal("invite")
                        .then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS)
                                .executes(c -> player(c, (rt, p) -> {
                                    var owned = rt.manager.communitiesOf(p.getUUID()).stream()
                                            .filter(x -> x.owner().equals(p.getUUID())).toList();
                                    if (owned.size() != 1) {
                                        rt.send(p, "community-which");
                                        return 0;
                                    }
                                    return Communities.invite(rt, p, owned.get(0), StringArgumentType.getString(c, "player"));
                                }))
                                .then(Commands.argument("community", StringArgumentType.word()).suggests(MY_COMMUNITIES)
                                        .executes(c -> player(c, (rt, p) -> {
                                            Community community = Communities.lookup(rt, p, StringArgumentType.getString(c, "community"));
                                            return community == null ? 0 : Communities.invite(rt, p, community, StringArgumentType.getString(c, "player"));
                                        })))))
                .then(Commands.literal("accept")
                        .then(Commands.argument("community", StringArgumentType.word()).suggests(INVITED_COMMUNITIES)
                                .executes(c -> player(c, (rt, p) -> {
                                    Community community = rt.manager.findCommunity(StringArgumentType.getString(c, "community"));
                                    if (community == null) {
                                        rt.send(p, "unknown-community", "community", StringArgumentType.getString(c, "community"));
                                        return 0;
                                    }
                                    return Communities.accept(rt, p, community);
                                }))))
                .then(Commands.literal("decline")
                        .then(Commands.argument("community", StringArgumentType.word()).suggests(INVITED_COMMUNITIES)
                                .executes(c -> player(c, (rt, p) -> {
                                    Community community = rt.manager.findCommunity(StringArgumentType.getString(c, "community"));
                                    if (community == null) {
                                        rt.send(p, "unknown-community", "community", StringArgumentType.getString(c, "community"));
                                        return 0;
                                    }
                                    return Communities.decline(rt, p, community);
                                }))))
                .then(Commands.literal("leave")
                        .then(Commands.argument("community", StringArgumentType.word()).suggests(MY_COMMUNITIES)
                                .executes(c -> player(c, (rt, p) -> {
                                    Community community = Communities.lookup(rt, p, StringArgumentType.getString(c, "community"));
                                    return community == null ? 0 : Communities.leave(rt, p, community);
                                }))))
                .then(Commands.literal("kick")
                        .then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS)
                                .then(Commands.argument("community", StringArgumentType.word()).suggests(MY_COMMUNITIES)
                                        .executes(c -> player(c, (rt, p) -> {
                                            Community community = Communities.lookup(rt, p, StringArgumentType.getString(c, "community"));
                                            if (community == null) {
                                                return 0;
                                            }
                                            String name = StringArgumentType.getString(c, "player");
                                            NameAndId target = rt.findPlayer(name);
                                            if (target == null) {
                                                rt.send(p, "unknown-player", "player", name);
                                                return 0;
                                            }
                                            return Communities.kick(rt, p, community, target.id(), target.name());
                                        })))))
                .then(Commands.literal("rename")
                        .then(Commands.argument("community", StringArgumentType.word()).suggests(MY_COMMUNITIES)
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(c -> player(c, (rt, p) -> {
                                            Community community = Communities.lookup(rt, p, StringArgumentType.getString(c, "community"));
                                            return community == null ? 0 : Communities.rename(rt, p, community, StringArgumentType.getString(c, "name"));
                                        })))))
                .then(Commands.literal("disband")
                        .then(Commands.argument("community", StringArgumentType.word()).suggests(MY_COMMUNITIES)
                                .executes(c -> player(c, (rt, p) -> {
                                    Community community = Communities.lookup(rt, p, StringArgumentType.getString(c, "community"));
                                    if (community == null) {
                                        return 0;
                                    }
                                    rt.send(p, "community-disband-confirm", "community", community.name());
                                    return 1;
                                }))
                                .then(Commands.literal("confirm").executes(c -> player(c, (rt, p) -> {
                                    Community community = Communities.lookup(rt, p, StringArgumentType.getString(c, "community"));
                                    return community == null ? 0 : Communities.disband(rt, p, community);
                                })))))
                .then(Commands.literal("info")
                        .then(Commands.argument("community", StringArgumentType.word()).suggests(MY_COMMUNITIES)
                                .executes(c -> player(c, (rt, p) -> {
                                    Community community = Communities.lookup(rt, p, StringArgumentType.getString(c, "community"));
                                    return community == null ? 0 : Communities.info(rt, p, community);
                                }))))
                .then(Commands.literal("assign")
                        .then(Commands.argument("community", StringArgumentType.word()).suggests(MY_COMMUNITIES)
                                .executes(c -> player(c, (rt, p) -> {
                                    String ref = StringArgumentType.getString(c, "community");
                                    Community community = ref.equalsIgnoreCase("none") ? null : Communities.lookup(rt, p, ref);
                                    if (community == null && !ref.equalsIgnoreCase("none")) {
                                        return 0;
                                    }
                                    Claim claim = Menus.target(rt, p, null);
                                    return claim == null ? 0 : Communities.assign(rt, p, claim, community);
                                }))));
    }

    private static int partner(EstateRuntime rt, ServerPlayer player, String name, boolean add) {
        NameAndId target = rt.findPlayer(name);
        if (target == null) {
            rt.send(player, "unknown-player", "player", name);
            return 0;
        }
        if (target.id().equals(player.getUUID())) {
            rt.send(player, "partner-self");
            return 0;
        }
        rt.manager.ensurePlayer(target.id(), target.name());
        boolean changed = add ? rt.manager.addBuildPartner(player.getUUID(), target.id())
                : rt.manager.removeBuildPartner(player.getUUID(), target.id());
        rt.send(player, changed ? (add ? "partner-added" : "partner-removed") : (add ? "partner-already" : "partner-not"),
                "player", target.name());
        return changed ? 1 : 0;
    }

    private static int partnerList(EstateRuntime rt, ServerPlayer player) {
        var partners = rt.manager.buildPartners(player.getUUID());
        if (partners.isEmpty()) {
            rt.send(player, "partner-none");
        } else {
            rt.send(player, "partner-list", "players", String.join(", ", partners.stream().map(rt::name).toList()));
        }
        return 1;
    }

    private static int flag(EstateRuntime rt, ServerPlayer player, String flagName, String value) {
        Flag flag = Flag.byKey(flagName);
        if (flag == null) {
            rt.send(player, "unknown-flag", "flag", flagName);
            return 0;
        }
        Claim claim = Menus.target(rt, player, null);
        if (claim == null) {
            return 0;
        }
        boolean admin = rt.isAdmin(player);
        if (!admin && !rt.config.ownerMayEdit(flag)) {
            rt.send(player, "flag-locked", "flag", flag.key);
            return 0;
        }
        String typed = value.toLowerCase(Locale.ROOT);
        Boolean setting;
        if (List.of("on", "true", "yes").contains(typed)) {
            setting = true;
        } else if (List.of("off", "false", "no").contains(typed)) {
            setting = false;
        } else if (typed.equals("default")) {
            setting = null;
        } else {
            rt.send(player, "flag-value");
            return 0;
        }
        rt.manager.setFlag(claim, flag, setting, player.getUUID());
        rt.send(player, "flag-set", "flag", flag.key, "value", setting == null ? "default" : setting ? "on" : "off");
        return 1;
    }

    private static int donate(EstateRuntime rt, ServerPlayer player, int cells, boolean give) {
        Claim claim = rt.claimAt(player.level(), player.blockPosition());
        if (claim == null) {
            rt.send(player, "stand-in-claim");
            return 0;
        }
        if (claim.communityId() != 0) {
            rt.send(player, "donate-shared");
            return 0;
        }
        if (give && !rt.manager.has(claim, player.getUUID(), Permission.BUILD)) {
            rt.send(player, "donate-trusted-only");
            return 0;
        }
        Problem problem = give ? rt.manager.donate(claim, player.getUUID(), cells) : rt.manager.withdraw(claim, player.getUUID(), cells);
        if (problem != null) {
            rt.send(player, (give ? "donate-" : "withdraw-") + problem.messageKey, "blocks", cells,
                    "left", rt.manager.budgetLeft(player.getUUID()), "owner", rt.name(claim.owner()));
            return 0;
        }
        rt.send(player, give ? "donated" : "withdrawn", "blocks", cells, "claim", claim.name(),
                "share", claim.share(player.getUUID()), "left", rt.manager.budgetLeft(player.getUUID()));
        rt.notifyOwner(claim.owner(), rt.msg.get(give ? "donated-owner" : "withdrawn-owner",
                "player", player.getGameProfile().name(), "blocks", cells, "claim", claim.name()));
        return 1;
    }

    // ------------------------------------------------------------------ admin

    private static LiteralArgumentBuilder<CommandSourceStack> admin() {
        return Commands.literal("admin")
                .requires(LandCommand::isAdmin)
                .executes(c -> run(c, (rt, src) -> help(rt, src)))
                .then(Commands.literal("reload").executes(c -> run(c, (rt, src) -> {
                    String error = rt.reload();
                    src.sendSystemMessage(error == null ? rt.msg.get("reloaded") : rt.msg.get("reload-failed", "error", error));
                    return error == null ? 1 : 0;
                })))
                .then(Commands.literal("settings").executes(c -> run(c, LandCommand::settings)))
                .then(Commands.literal("menu").executes(c -> player(c, (rt, p) -> {
                    Menus.adminSettings(rt, p, 0);
                    return 1;
                })))
                .then(Commands.literal("get")
                        .then(Commands.argument("setting", StringArgumentType.word()).suggests(SETTINGS)
                                .executes(c -> run(c, (rt, src) -> {
                                    String name = StringArgumentType.getString(c, "setting");
                                    String canonical = EstateConfig.canonicalName(name);
                                    String value = canonical == null ? null : rt.config.get(canonical);
                                    src.sendSystemMessage(value == null ? rt.msg.get("unknown-setting", "setting", name)
                                            : rt.msg.get("setting-value", "setting", canonical, "value", value));
                                    return value == null ? 0 : 1;
                                }))))
                .then(Commands.literal("set")
                        .then(Commands.argument("setting", StringArgumentType.word()).suggests(SETTINGS)
                                .then(Commands.argument("value", StringArgumentType.greedyString())
                                        .executes(c -> run(c, (rt, src) -> {
                                            String name = StringArgumentType.getString(c, "setting");
                                            String value = StringArgumentType.getString(c, "value");
                                            String error = Menus.changeSetting(rt, null, name, value);
                                            if (error != null) {
                                                src.sendSystemMessage(Text.parse("<red>" + error, Map.of()));
                                                return 0;
                                            }
                                            String canonical = EstateConfig.canonicalName(name);
                                            src.sendSystemMessage(rt.msg.get("setting-changed", "setting", canonical, "value", value));
                                            return 1;
                                        })))))
                .then(Commands.literal("bypass")
                        .executes(c -> player(c, (rt, p) -> bypass(rt, p, !rt.session(p).bypass)))
                        .then(Commands.literal("on").executes(c -> player(c, (rt, p) -> bypass(rt, p, true))))
                        .then(Commands.literal("off").executes(c -> player(c, (rt, p) -> bypass(rt, p, false)))))
                .then(Commands.literal("budget")
                        .then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS)
                                .executes(c -> run(c, (rt, src) -> budget(rt, src, StringArgumentType.getString(c, "player"), null, false)))
                                .then(Commands.literal("grant").then(Commands.argument("blocks", IntegerArgumentType.integer(0))
                                        .executes(c -> run(c, (rt, src) -> budget(rt, src, StringArgumentType.getString(c, "player"),
                                                IntegerArgumentType.getInteger(c, "blocks"), false)))))
                                .then(Commands.literal("add").then(Commands.argument("blocks", IntegerArgumentType.integer())
                                        .executes(c -> run(c, (rt, src) -> budget(rt, src, StringArgumentType.getString(c, "player"),
                                                IntegerArgumentType.getInteger(c, "blocks"), true)))))))
                .then(Commands.literal("claims")
                        .executes(c -> run(c, (rt, src) -> claims(rt, src, null)))
                        .then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS)
                                .executes(c -> run(c, (rt, src) -> claims(rt, src, StringArgumentType.getString(c, "player"))))))
                .then(Commands.literal("info").then(Commands.argument("id", IntegerArgumentType.integer(1)).suggests(CLAIM_IDS)
                        .executes(c -> run(c, (rt, src) -> withClaim(rt, src, IntegerArgumentType.getInteger(c, "id"), claim -> {
                            sendClaimInfo(rt, src, claim);
                            return 1;
                        })))))
                .then(Commands.literal("delete").then(Commands.argument("id", IntegerArgumentType.integer(1)).suggests(CLAIM_IDS)
                        .executes(c -> run(c, (rt, src) -> withClaim(rt, src, IntegerArgumentType.getInteger(c, "id"), claim -> {
                            rt.manager.delete(claim, actor(src), "admin-delete");
                            rt.notifyOwner(claim.owner(), rt.msg.get("admin-deleted-your-claim", "claim", claim.name()));
                            src.sendSystemMessage(rt.msg.get("admin-deleted", "claim", claim.name(), "id", claim.id(),
                                    "owner", rt.name(claim.owner())));
                            return 1;
                        })))))
                .then(Commands.literal("transfer").then(Commands.argument("id", IntegerArgumentType.integer(1)).suggests(CLAIM_IDS)
                        .then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS)
                                .executes(c -> run(c, (rt, src) -> withClaim(rt, src, IntegerArgumentType.getInteger(c, "id"), claim -> {
                                    NameAndId target = rt.findPlayer(StringArgumentType.getString(c, "player"));
                                    if (target == null) {
                                        src.sendSystemMessage(rt.msg.get("unknown-player", "player", StringArgumentType.getString(c, "player")));
                                        return 0;
                                    }
                                    String old = rt.name(claim.owner());
                                    rt.manager.ensurePlayer(target.id(), target.name());
                                    rt.manager.transfer(claim, target.id(), actor(src));
                                    src.sendSystemMessage(rt.msg.get("admin-transferred", "claim", claim.name(), "from", old, "to", target.name()));
                                    return 1;
                                }))))))
                .then(Commands.literal("exempt").then(Commands.argument("id", IntegerArgumentType.integer(1)).suggests(CLAIM_IDS)
                        .executes(c -> run(c, (rt, src) -> exempt(rt, src, IntegerArgumentType.getInteger(c, "id"), null)))
                        .then(Commands.literal("on").executes(c -> run(c, (rt, src) -> exempt(rt, src, IntegerArgumentType.getInteger(c, "id"), true))))
                        .then(Commands.literal("off").executes(c -> run(c, (rt, src) -> exempt(rt, src, IntegerArgumentType.getInteger(c, "id"), false))))))
                .then(Commands.literal("town")
                        .then(Commands.literal("list").executes(c -> run(c, LandCommand::towns)))
                        .then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 10_000))
                                        .executes(c -> player(c, (rt, p) -> {
                                            int r = IntegerArgumentType.getInteger(c, "radius");
                                            BlockPos pos = p.blockPosition();
                                            return createTown(rt, p.createCommandSourceStack(), StringArgumentType.getString(c, "name"),
                                                    EstateRuntime.world(p.level()), pos.getX() - r, pos.getZ() - r, pos.getX() + r, pos.getZ() + r);
                                        })))
                                .then(Commands.argument("x1", IntegerArgumentType.integer()).then(Commands.argument("z1", IntegerArgumentType.integer())
                                        .then(Commands.argument("x2", IntegerArgumentType.integer()).then(Commands.argument("z2", IntegerArgumentType.integer())
                                                .executes(c -> run(c, (rt, src) -> createTown(rt, src, StringArgumentType.getString(c, "name"),
                                                        EstateRuntime.world(src.getLevel()),
                                                        IntegerArgumentType.getInteger(c, "x1"), IntegerArgumentType.getInteger(c, "z1"),
                                                        IntegerArgumentType.getInteger(c, "x2"), IntegerArgumentType.getInteger(c, "z2"))))))))))
                        .then(Commands.literal("remove").then(Commands.argument("name", StringArgumentType.word()).suggests(TOWNS)
                                .executes(c -> run(c, (rt, src) -> {
                                    String name = StringArgumentType.getString(c, "name");
                                    TownZone town = rt.manager.town(name);
                                    if (town == null) {
                                        src.sendSystemMessage(rt.msg.get("town-unknown", "town", name));
                                        return 0;
                                    }
                                    rt.manager.removeTown(town, actor(src));
                                    src.sendSystemMessage(rt.msg.get("town-removed", "town", town.name()));
                                    return 1;
                                })))))
                .then(Commands.literal("lookup")
                        .executes(c -> player(c, (rt, p) -> lookupHere(rt, p)))
                        .then(Commands.literal("at").then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("y", IntegerArgumentType.integer()).then(Commands.argument("z", IntegerArgumentType.integer())
                                        .executes(c -> run(c, (rt, src) -> lookupAt(rt, src, src.getLevel(),
                                                new BlockPos(IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "y"),
                                                        IntegerArgumentType.getInteger(c, "z")))))))))
                        .then(Commands.literal("player").then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS)
                                .executes(c -> run(c, (rt, src) -> lookupPlayer(rt, src, StringArgumentType.getString(c, "player")))))))
                .then(Commands.literal("log")
                        .executes(c -> run(c, (rt, src) -> log(rt, src, 15)))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 200))
                                .executes(c -> run(c, (rt, src) -> log(rt, src, IntegerArgumentType.getInteger(c, "count"))))))
                .then(Commands.literal("expire").executes(c -> run(c, (rt, src) -> {
                    int removed = rt.expire();
                    src.sendSystemMessage(rt.msg.get("expire-ran", "count", removed, "days", rt.config.inactivityDays));
                    return 1;
                })));
    }

    private static UUID actor(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player == null ? null : player.getUUID();
    }

    private static int withClaim(EstateRuntime rt, CommandSourceStack source, int id, java.util.function.Function<Claim, Integer> action) {
        Claim claim = rt.manager.claim(id);
        if (claim == null) {
            source.sendSystemMessage(rt.msg.get("claim-unknown", "id", id));
            return 0;
        }
        return action.apply(claim);
    }

    private static int settings(EstateRuntime rt, CommandSourceStack source) {
        StringBuilder text = new StringBuilder();
        for (String name : EstateConfig.settingNames()) {
            text.append("\n<gray>").append(name).append(": <white>").append(rt.config.get(name).replace("<", ""));
        }
        source.sendSystemMessage(rt.msg.get("settings-header"));
        source.sendSystemMessage(Text.parse(text.toString().substring(1), Map.of()));
        return 1;
    }

    private static int bypass(EstateRuntime rt, ServerPlayer player, boolean on) {
        rt.session(player).bypass = on;
        rt.send(player, on ? "bypass-on" : "bypass-off");
        rt.manager.log(new Action(rt.manager.now(), player.getUUID(), "admin-bypass", null, null, null, null, null, on ? "on" : "off"));
        return 1;
    }

    private static int budget(EstateRuntime rt, CommandSourceStack source, String name, Integer cells, boolean add) {
        NameAndId target = rt.findPlayer(name);
        if (target == null) {
            source.sendSystemMessage(rt.msg.get("unknown-player", "player", name));
            return 0;
        }
        ClaimManager manager = rt.manager;
        if (cells != null) {
            manager.ensurePlayer(target.id(), target.name());
            int bonus = add ? manager.player(target.id()).bonusBudget + cells : cells;
            manager.setBonusBudget(target.id(), bonus, actor(source));
            rt.notifyOwner(target.id(), rt.msg.get("budget-changed-you", "limit", manager.budgetLimit(target.id())));
        }
        var record = manager.player(target.id());
        source.sendSystemMessage(rt.msg.get("budget-info", "player", target.name(), "used", manager.budgetUsed(target.id()),
                "limit", manager.budgetLimit(target.id()), "bonus", record == null ? 0 : record.bonusBudget,
                "claims", manager.claimsOf(target.id()).size(), "cap", rt.config.maxGrantedBudget));
        return 1;
    }

    private static int claims(EstateRuntime rt, CommandSourceStack source, String name) {
        List<Claim> claims;
        if (name == null) {
            claims = List.copyOf(rt.manager.claims());
        } else {
            NameAndId target = rt.findPlayer(name);
            if (target == null) {
                source.sendSystemMessage(rt.msg.get("unknown-player", "player", name));
                return 0;
            }
            claims = rt.manager.claimsOf(target.id());
        }
        source.sendSystemMessage(rt.msg.get("claims-header", "count", claims.size()));
        for (Claim claim : claims) {
            int[] center = claim.region().middle();
            source.sendSystemMessage(rt.msg.get("claims-entry", "id", claim.id(), "claim", claim.name(), "owner", rt.name(claim.owner()),
                    "blocks", claim.size(), "x", center[0], "z", center[1], "world", claim.world().replace("minecraft:", "")));
        }
        return claims.size();
    }

    private static int exempt(EstateRuntime rt, CommandSourceStack source, int id, Boolean on) {
        return withClaim(rt, source, id, claim -> {
            boolean value = on == null ? !claim.expiryExempt() : on;
            rt.manager.setExpiryExempt(claim, value, actor(source));
            source.sendSystemMessage(rt.msg.get(value ? "exempt-on" : "exempt-off", "claim", claim.name(), "id", claim.id()));
            return 1;
        });
    }

    private static int towns(EstateRuntime rt, CommandSourceStack source) {
        source.sendSystemMessage(rt.msg.get("towns-header", "count", rt.manager.towns().size()));
        for (TownZone town : rt.manager.towns()) {
            source.sendSystemMessage(rt.msg.get("towns-entry", "town", town.name(), "world", town.world().replace("minecraft:", ""),
                    "x1", town.minX(), "z1", town.minZ(), "x2", town.maxX(), "z2", town.maxZ()));
        }
        return 1;
    }

    private static int createTown(EstateRuntime rt, CommandSourceStack source, String name, String world, int x1, int z1, int x2, int z2) {
        if (rt.manager.town(name) != null) {
            source.sendSystemMessage(rt.msg.get("town-exists", "town", name));
            return 0;
        }
        TownZone town = rt.manager.createTown(world, name, x1, z1, x2, z2, actor(source));
        source.sendSystemMessage(rt.msg.get("town-created", "town", town.name(), "x1", town.minX(), "z1", town.minZ(),
                "x2", town.maxX(), "z2", town.maxZ()));
        return 1;
    }

    private static int lookupHere(EstateRuntime rt, ServerPlayer player) {
        HitResult hit = player.pick(8, 0, false);
        BlockPos pos = hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK
                ? blockHit.getBlockPos() : player.blockPosition().below();
        return lookupAt(rt, player.createCommandSourceStack(), player.level(), pos);
    }

    private static int lookupAt(EstateRuntime rt, CommandSourceStack source, ServerLevel level, BlockPos pos) {
        String world = EstateRuntime.world(level);
        PlacementStore.Placed placed = rt.placements.at(world, pos.getX(), pos.getY(), pos.getZ());
        String block = BlockKinds.id(level.getBlockState(pos).getBlock()).replace("minecraft:", "");
        if (placed == null) {
            source.sendSystemMessage(rt.msg.get("lookup-natural", "x", pos.getX(), "y", pos.getY(), "z", pos.getZ(), "block", block));
        } else {
            source.sendSystemMessage(rt.msg.get("lookup-placed", "x", pos.getX(), "y", pos.getY(), "z", pos.getZ(),
                    "block", placed.block().replace("minecraft:", ""), "player", rt.name(placed.player()),
                    "time", TIME.format(Instant.ofEpochSecond(placed.time()))));
        }
        Claim claim = rt.claimAt(level, pos);
        if (claim == null) {
            return 1;
        }
        sendClaimInfo(rt, source, claim);
        rt.storage.actionsForClaim(claim.id(), 10).whenComplete((actions, error) -> rt.server.execute(() ->
                sendActions(rt, source, actions, error, rt.msg.get("lookup-claim-log", "claim", claim.name()))));
        return 1;
    }

    private static int lookupPlayer(EstateRuntime rt, CommandSourceStack source, String name) {
        NameAndId target = rt.findPlayer(name);
        if (target == null) {
            source.sendSystemMessage(rt.msg.get("unknown-player", "player", name));
            return 0;
        }
        SqliteStorage storage = rt.storage;
        storage.placementCount(target.id()).thenCombine(storage.placementsBy(target.id(), 10), (count, rows) -> {
            rt.server.execute(() -> {
                source.sendSystemMessage(rt.msg.get("lookup-player", "player", target.name(), "count", count));
                for (SqliteStorage.PlacementRow row : rows) {
                    source.sendSystemMessage(rt.msg.get("lookup-player-entry", "block", row.block().replace("minecraft:", ""),
                            "x", row.x(), "y", row.y(), "z", row.z(), "world", row.world().replace("minecraft:", ""),
                            "time", TIME.format(Instant.ofEpochSecond(row.time()))));
                }
            });
            return null;
        });
        storage.actionsBy(target.id(), 10).whenComplete((actions, error) -> rt.server.execute(() ->
                sendActions(rt, source, actions, error, rt.msg.get("lookup-player-log", "player", target.name()))));
        return 1;
    }

    private static int log(EstateRuntime rt, CommandSourceStack source, int count) {
        rt.storage.recentActions(count).whenComplete((actions, error) -> rt.server.execute(() ->
                sendActions(rt, source, actions, error, rt.msg.get("log-header", "count", count))));
        return 1;
    }

    private static void sendActions(EstateRuntime rt, CommandSourceStack source, List<Action> actions, Throwable error, Component header) {
        if (error != null) {
            EstateRuntime.LOGGER.error("Could not read the Estate log", error);
            source.sendSystemMessage(rt.msg.get("log-failed"));
            return;
        }
        source.sendSystemMessage(header);
        if (actions.isEmpty()) {
            source.sendSystemMessage(rt.msg.get("log-empty"));
        }
        for (Action action : actions) {
            source.sendSystemMessage(rt.msg.get("log-entry", "time", TIME.format(Instant.ofEpochSecond(action.time())),
                    "actor", action.actor() == null ? rt.msg.raw("server") : rt.name(action.actor()),
                    "action", action.type(), "claim", action.claimId() == null ? "-" : "#" + action.claimId(),
                    "details", action.details().replace("<", "")));
        }
    }
}
