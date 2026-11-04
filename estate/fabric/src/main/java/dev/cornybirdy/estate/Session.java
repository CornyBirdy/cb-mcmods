package dev.cornybirdy.estate;

import dev.cornybirdy.estate.core.ClaimDraft;
import dev.cornybirdy.estate.core.Problem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

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
    /** The claim picked in the manage menu or with /land select; commands act on it when you aren't standing in your own claim. 0 = none. */
    int selectedClaim;
    /** Only draw this claim (from the claim list); 0 = everything nearby. */
    int bordersClaim;
    /** Only draw claims this close (while holding the claim tool); 0 = the full view distance. */
    int bordersRadius;
    /** A new claim or resize being edited in the menu. */
    ClaimDraft draft;
    /** What the claim being drawn will be called (/land claim name), or null for the default. */
    String draftName;
    /** Whether the draft can be confirmed right now; it's drawn red otherwise. */
    boolean draftOk;
    /** The border displays this player's client is showing, with their entity ids. */
    final Map<Borders.Spec, Integer> displays = new HashMap<>();
    /** Which player object and level those were sent to; a respawn or dimension change clears them on the client. */
    ServerPlayer displaysFor;
    ServerLevel displaysLevel;
    /** The claim tool in use, or null. */
    ClaimTool.Selection selection;
    /** When each kind of "you can't do that" message was last shown, to avoid spam. */
    final Map<String, Long> lastWarning = new HashMap<>();
    /** When each auto-claim problem was last explained. */
    final Map<Problem, Long> lastAutoProblem = new EnumMap<>(Problem.class);
}
