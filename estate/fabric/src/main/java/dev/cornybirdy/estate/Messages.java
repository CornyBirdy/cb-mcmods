package dev.cornybirdy.estate;

import dev.cornybirdy.estate.core.Check;
import dev.cornybirdy.estate.core.Problem;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;

/** Shared placeholder sets for messages. */
final class Messages {

    private Messages() {
    }

    /**
     * Tells the player why a claim failed. When it's someone's blocks in the way, adds a button to let that player
     * build with them, since they can't trust them in a claim that doesn't exist yet.
     *
     * @param prefix put in front of the message key ("auto-" for the auto-claim wording)
     */
    static void sendProblem(EstateRuntime rt, ServerPlayer player, String prefix, Check check) {
        player.sendSystemMessage(rt.msg.get(prefix + check.problem().messageKey, problemPlaceholders(rt, check)));
        if (check.problem() == Problem.OTHERS_BLOCKS && check.blocker() != null) {
            String command = "/land partner add " + rt.name(check.blocker());
            player.sendSystemMessage(rt.msg.get("partner-button", "builder", rt.name(check.blocker()))
                    .withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(command))
                            .withHoverEvent(new HoverEvent.ShowText(rt.msg.get("partner-button-hover",
                                    "builder", rt.name(check.blocker()))))));
        }
    }

    /** {@code <cost>}, {@code <left>}, {@code <owner>} (whose claim is in the way) and {@code <builder>}. */
    static Object[] problemPlaceholders(EstateRuntime rt, Check check) {
        return new Object[]{
                "cost", Math.max(0, check.cost()),
                "left", Math.max(0, check.budgetLeft()),
                "owner", check.other() == null ? "?" : rt.name(check.other().owner()),
                "builder", check.blocker() == null ? "?" : rt.name(check.blocker()),
                "max", rt.config.maxClaimsPerPlayer
        };
    }
}
