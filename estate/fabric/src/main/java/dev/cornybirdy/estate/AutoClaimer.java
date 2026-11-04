package dev.cornybirdy.estate;

import dev.cornybirdy.estate.core.AutoClaimPlanner;
import dev.cornybirdy.estate.core.Check;
import dev.cornybirdy.estate.core.Claim;
import dev.cornybirdy.estate.core.ClaimManager;
import dev.cornybirdy.estate.core.Problem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Turns placed blocks into claims: grows a player's claim as they build on, makes a new one once a build is big
 * enough (with auto-claim on), or shows the build tip (with it off). Every outcome is explained, but each problem only
 * once in a while so building doesn't flood the chat.
 */
final class AutoClaimer {

    private static final long PROBLEM_COOLDOWN_MILLIS = 5 * 60 * 1000;

    private final EstateRuntime rt;

    AutoClaimer(EstateRuntime rt) {
        this.rt = rt;
    }

    void placed(ServerPlayer player, ServerLevel level, BlockPos pos, String block) {
        if (!rt.kinds.countsForAutoClaim(block) || rt.bypassing(player)) {
            return;
        }
        ClaimManager manager = rt.manager;
        String world = EstateRuntime.world(level);
        AutoClaimPlanner.Plan plan = AutoClaimPlanner.plan(manager, rt.placements, world, player.getUUID(),
                pos.getX(), pos.getY(), pos.getZ(), rt.kinds::countsForAutoClaim);
        switch (plan) {
            case AutoClaimPlanner.Grow grow -> grow(player, level, grow);
            case AutoClaimPlanner.Create create -> create(player, level, world, create);
            case AutoClaimPlanner.Prompt prompt -> prompt(player, prompt);
            case AutoClaimPlanner.Nothing nothing -> {
            }
        }
    }

    private void grow(ServerPlayer player, ServerLevel level, AutoClaimPlanner.Grow grow) {
        Claim claim = grow.claim();
        int minY = Math.max(level.getMinY(), grow.minY());
        int maxY = Math.min(level.getMaxY(), grow.maxY());
        Check check = rt.manager.resize(claim, grow.region(), minY, maxY, player.getUUID(), rt.oracle(), false);
        if (!check.allowed()) {
            explain(player, check);
            return;
        }
        rt.session(player).noBudgetShown = false;
        if (!grow.verticalOnly()) {
            rt.actionBar(player, "auto-grew", "claim", claim.name(), "left", rt.manager.budgetLeft(player.getUUID()));
        }
    }

    private void create(ServerPlayer player, ServerLevel level, String world, AutoClaimPlanner.Create create) {
        int minY = Math.max(level.getMinY(), create.minY());
        int maxY = Math.min(level.getMaxY(), create.maxY());
        String name = rt.manager.uniqueName(player.getUUID(),
                rt.msg.raw("default-claim-name").replace("<player>", player.getGameProfile().name()));
        ClaimManager.Change change = rt.manager.create(world, player.getUUID(), name, create.region(), minY, maxY,
                player.getUUID(), rt.oracle(), false);
        if (!change.ok()) {
            explain(player, change.check());
            return;
        }
        rt.manager.setAutoClaimed(change.claim(), true);
        rt.session(player).noBudgetShown = false;
        rt.send(player, "auto-created", "blocks", change.claim().size(), "left", rt.manager.budgetLeft(player.getUUID()));
        rt.borders.show(player, 0);
    }

    /**
     * A block was broken. An auto-made claim shrinks to what's left of its build, and is removed once nothing of it is
     * left, so knocking a build down gives the land back. Claims drawn or changed by hand never shrink by themselves.
     */
    void broken(ServerLevel level, BlockPos pos) {
        Claim claim = rt.claimAt(level, pos);
        if (claim == null || !claim.autoClaimed()) {
            return;
        }
        ClaimManager manager = rt.manager;
        AutoClaimPlanner.Fit fit = AutoClaimPlanner.refit(manager, rt.placements, claim, rt.kinds::countsForAutoClaim);
        ServerPlayer owner = rt.server.getPlayerList().getPlayer(claim.owner());
        if (fit == null) {
            manager.delete(claim, null, "auto-remove");
            if (owner != null) {
                rt.send(owner, "auto-removed", "claim", claim.name(), "left", manager.budgetLeft(claim.owner()));
            }
            return;
        }
        if (fit.region().equals(claim.region()) && fit.minY() == claim.minY() && fit.maxY() == claim.maxY()) {
            return;
        }
        if (manager.resize(claim, fit.region(), fit.minY(), fit.maxY(), claim.owner(), rt.oracle(), true).allowed()
                && owner != null) {
            rt.actionBar(owner, "auto-shrank", "claim", claim.name(), "left", manager.budgetLeft(claim.owner()));
        }
    }

    private void prompt(ServerPlayer player, AutoClaimPlanner.Prompt prompt) {
        Session session = rt.session(player);
        if (session.promptShown) {
            return;
        }
        session.promptShown = true;
        player.sendSystemMessage(rt.msg.get("build-tip", "blocks", prompt.blocks()));
        player.sendSystemMessage(rt.msg.get("build-tip-turn-on").withStyle(style -> style
                .withClickEvent(new ClickEvent.RunCommand("/land autoclaim on"))
                .withHoverEvent(new HoverEvent.ShowText(rt.msg.get("build-tip-turn-on-hover")))));
        player.sendSystemMessage(rt.msg.get("build-tip-menu").withStyle(style -> style
                .withClickEvent(new ClickEvent.RunCommand("/land"))
                .withHoverEvent(new HoverEvent.ShowText(rt.msg.get("build-tip-menu-hover")))));
    }

    /** Tells the player why their build couldn't be claimed, without repeating it on every block. */
    private void explain(ServerPlayer player, Check check) {
        Session session = rt.session(player);
        Problem problem = check.problem();
        if (problem == Problem.NO_BUDGET) {
            if (session.noBudgetShown) {
                return;
            }
            session.noBudgetShown = true;
        } else {
            long now = System.currentTimeMillis();
            Long last = session.lastAutoProblem.get(problem);
            if (last != null && now - last < PROBLEM_COOLDOWN_MILLIS) {
                return;
            }
            session.lastAutoProblem.put(problem, now);
        }
        Messages.sendProblem(rt, player, "auto-", check);
    }
}
