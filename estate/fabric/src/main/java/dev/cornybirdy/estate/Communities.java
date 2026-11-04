package dev.cornybirdy.estate;

import dev.cornybirdy.estate.core.Claim;
import dev.cornybirdy.estate.core.Community;
import dev.cornybirdy.estate.core.Permission;
import dev.cornybirdy.estate.core.Problem;
import dev.cornybirdy.estate.core.TrustTier;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * What communities and shared claims do, for both the commands and the menus. Each method says what happened to the
 * player and returns 1 if it worked, 0 if not.
 */
final class Communities {

    /** One word, so they can be typed in commands. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{2,24}");

    private Communities() {
    }

    /** A community by name or id; it has to be one the player is in (or they're an admin in bypass mode). */
    static Community lookup(EstateRuntime rt, ServerPlayer player, String reference) {
        Community community = rt.manager.findCommunity(reference);
        if (community == null || (!community.isMember(player.getUUID()) && !rt.bypassing(player))) {
            rt.send(player, "unknown-community", "community", reference);
            return null;
        }
        return community;
    }

    static boolean mayAdmin(EstateRuntime rt, ServerPlayer player, Community community) {
        return community.owner().equals(player.getUUID()) || rt.bypassing(player);
    }

    private static boolean requireOwner(EstateRuntime rt, ServerPlayer player, Community community) {
        if (mayAdmin(rt, player, community)) {
            return true;
        }
        rt.send(player, "community-owner-only", "owner", rt.name(community.owner()), "community", community.name());
        return false;
    }

    static int create(EstateRuntime rt, ServerPlayer player, String name) {
        if (!NAME.matcher(name).matches()) {
            rt.send(player, "community-name-invalid");
            return 0;
        }
        Community community = rt.manager.createCommunity(player.getUUID(), name);
        if (community == null) {
            rt.send(player, "community-name-taken", "community", name);
            return 0;
        }
        rt.send(player, "community-created", "community", community.name());
        return 1;
    }

    static int invite(EstateRuntime rt, ServerPlayer player, Community community, String targetName) {
        NameAndId target = rt.findPlayer(targetName);
        if (target == null) {
            rt.send(player, "unknown-player", "player", targetName);
            return 0;
        }
        return invite(rt, player, community, target);
    }

    static int invite(EstateRuntime rt, ServerPlayer player, Community community, NameAndId target) {
        if (!requireOwner(rt, player, community)) {
            return 0;
        }
        rt.manager.ensurePlayer(target.id(), target.name());
        if (!rt.manager.invite(community, target.id(), player.getUUID())) {
            rt.send(player, "community-already", "player", target.name(), "community", community.name());
            return 0;
        }
        rt.send(player, "community-invited", "player", target.name(), "community", community.name());
        ServerPlayer online = rt.server.getPlayerList().getPlayer(target.id());
        if (online != null) {
            online.sendSystemMessage(rt.msg.get("community-invited-you", "player", player.getGameProfile().name(),
                    "community", community.name()));
            MutableComponent buttons = Component.empty();
            buttons.append(button(rt, "community-button-accept", "/land community accept " + community.name()));
            buttons.append(Component.literal("  "));
            buttons.append(button(rt, "community-button-decline", "/land community decline " + community.name()));
            online.sendSystemMessage(buttons);
        }
        return 1;
    }

    private static MutableComponent button(EstateRuntime rt, String key, String command) {
        return rt.msg.get(key).withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
    }

    static int accept(EstateRuntime rt, ServerPlayer player, Community community) {
        if (!rt.manager.acceptInvite(community, player.getUUID())) {
            rt.send(player, "community-no-invite", "community", community.name());
            return 0;
        }
        rt.send(player, "community-joined", "community", community.name());
        rt.notifyOwner(community.owner(), rt.msg.get("community-joined-owner", "player", player.getGameProfile().name(),
                "community", community.name()));
        return 1;
    }

    static int decline(EstateRuntime rt, ServerPlayer player, Community community) {
        if (!rt.manager.declineInvite(community, player.getUUID())) {
            rt.send(player, "community-no-invite", "community", community.name());
            return 0;
        }
        rt.send(player, "community-declined", "community", community.name());
        return 1;
    }

    static int leave(EstateRuntime rt, ServerPlayer player, Community community) {
        if (community.owner().equals(player.getUUID())) {
            rt.send(player, "community-owner-leave", "community", community.name());
            return 0;
        }
        if (!rt.manager.removeMember(community, player.getUUID(), player.getUUID())) {
            rt.send(player, "unknown-community", "community", community.name());
            return 0;
        }
        rt.send(player, "community-left", "community", community.name());
        rt.notifyOwner(community.owner(), rt.msg.get("community-left-owner", "player", player.getGameProfile().name(),
                "community", community.name()));
        return 1;
    }

    static int kick(EstateRuntime rt, ServerPlayer player, Community community, UUID target, String targetName) {
        if (!requireOwner(rt, player, community)) {
            return 0;
        }
        if (!rt.manager.removeMember(community, target, player.getUUID())) {
            rt.send(player, "community-not-member", "player", targetName, "community", community.name());
            return 0;
        }
        rt.send(player, "community-kicked", "player", targetName, "community", community.name());
        rt.notifyOwner(target, rt.msg.get("community-kicked-you", "community", community.name()));
        return 1;
    }

    static int rename(EstateRuntime rt, ServerPlayer player, Community community, String name) {
        if (!requireOwner(rt, player, community)) {
            return 0;
        }
        if (!NAME.matcher(name).matches()) {
            rt.send(player, "community-name-invalid");
            return 0;
        }
        if (!rt.manager.renameCommunity(community, name, player.getUUID())) {
            rt.send(player, "community-name-taken", "community", name);
            return 0;
        }
        rt.send(player, "community-renamed", "community", name);
        return 1;
    }

    static int disband(EstateRuntime rt, ServerPlayer player, Community community) {
        if (!requireOwner(rt, player, community)) {
            return 0;
        }
        var members = community.members().stream().filter(id -> !id.equals(player.getUUID())).toList();
        rt.manager.disband(community, player.getUUID());
        rt.send(player, "community-disbanded", "community", community.name());
        for (UUID member : members) {
            rt.notifyOwner(member, rt.msg.get("community-disbanded-you", "community", community.name()));
        }
        return 1;
    }

    static int info(EstateRuntime rt, ServerPlayer player, Community community) {
        String members = String.join(", ", community.members().stream().map(rt::name).toList());
        player.sendSystemMessage(rt.msg.get("community-info", "community", community.name(),
                "owner", rt.name(community.owner()), "members", members,
                "claims", rt.manager.communityClaimCount(community),
                "used", rt.manager.communityBudgetUsed(community), "limit", rt.manager.communityBudgetLimit(community)));
        return 1;
    }

    /** Shares a claim with a community, or stops sharing it (null). Only the person who made the claim can. */
    static int assign(EstateRuntime rt, ServerPlayer player, Claim claim, Community community) {
        if (!claim.owner().equals(player.getUUID()) && !rt.bypassing(player)) {
            rt.send(player, "community-assign-owner-only");
            return 0;
        }
        Problem problem = rt.manager.assignCommunity(claim, community, player.getUUID());
        if (problem == Problem.NOT_ALLOWED) {
            rt.send(player, "community-assign-not-member");
            return 0;
        }
        if (problem != null) {
            int left = community == null ? rt.manager.budgetLeft(claim.owner()) : rt.manager.communityBudgetLeft(community);
            rt.send(player, community == null ? "community-unassign-no-budget" : "community-assign-no-budget",
                    "blocks", claim.size(), "left", Math.max(0, left), "community", community == null ? "" : community.name());
            return 0;
        }
        if (community == null) {
            rt.send(player, "community-unassigned", "claim", claim.name(), "left", rt.manager.budgetLeft(claim.owner()));
        } else {
            rt.send(player, "community-assigned", "claim", claim.name(), "community", community.name(),
                    "left", rt.manager.communityBudgetLeft(community), "mine", rt.manager.budgetLeft(claim.owner()));
        }
        return 1;
    }

    /** What everyone who isn't on the trust list may do in the claim. Null for nothing. */
    static int defaultTier(EstateRuntime rt, ServerPlayer player, Claim claim, TrustTier tier) {
        if (!Menus.mayManage(rt, player, claim, Permission.TRUST)) {
            rt.send(player, "not-yours", "owner", rt.name(claim.owner()));
            return 0;
        }
        if (!rt.manager.setDefaultTier(claim, tier, player.getUUID())) {
            rt.send(player, "default-tier-manage");
            return 0;
        }
        rt.send(player, "default-tier-set", "claim", claim.name(), "tier", tier == null ? rt.msg.raw("tier-none") : Menus.tierName(rt, tier));
        return 1;
    }
}
