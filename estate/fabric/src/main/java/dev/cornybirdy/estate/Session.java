package dev.cornybirdy.estate;

import dev.cornybirdy.estate.core.ClaimDraft;
import dev.cornybirdy.estate.core.Problem;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/** What Estate keeps about an online player until they log out. */
final class Session {

    /** The claim the player was in at the last check, for enter/leave messages. 0 = none. */
    int currentClaim;
    /** Admin bypass: build anywhere. */
    boolean bypass;
    /** The build tip was shown this session. */
    boolean promptShown;
    /** "You've used all your claim blocks" was shown since the budget last changed. */
    boolean noBudgetShown;
    /** Server tick until which claim borders are drawn for this player. */
    long bordersUntil;
    /** Only draw this claim (from the claim list); 0 = everything nearby. */
    int bordersClaim;
    /** A new claim or resize being edited in the menu. */
    ClaimDraft draft;
    /** Whether the draft can be confirmed right now; it's drawn red otherwise. */
    boolean draftOk;
    /** Ids of the border displays this player's client is showing. */
    int[] displayIds = new int[0];
    /** The claim tool in use, or null. */
    ClaimTool.Selection selection;
    /** When each kind of "you can't do that" message was last shown, to avoid spam. */
    final Map<String, Long> lastWarning = new HashMap<>();
    /** When each auto-claim problem was last explained. */
    final Map<Problem, Long> lastAutoProblem = new EnumMap<>(Problem.class);
}
