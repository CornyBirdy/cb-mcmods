package dev.cornybirdy.estate.core;

import java.util.UUID;

/**
 * The outcome of checking a new or changed claim.
 *
 * @param problem    null if the change is allowed
 * @param cost       cells the payer's budget changes by (negative when cells are given back)
 * @param budgetLeft cells the payer has left before the change
 * @param other      the claim that's in the way, for {@link Problem#OVERLAP} and {@link Problem#BUFFER}
 * @param blocker    whose blocks are in the way, for {@link Problem#OTHERS_BLOCKS}
 */
public record Check(Problem problem, int cost, int budgetLeft, Claim other, UUID blocker) {

    public static Check ok(int cost, int budgetLeft) {
        return new Check(null, cost, budgetLeft, null, null);
    }

    public static Check fail(Problem problem, int cost, int budgetLeft) {
        return new Check(problem, cost, budgetLeft, null, null);
    }

    public boolean allowed() {
        return problem == null;
    }

    Check with(Claim other) {
        return new Check(problem, cost, budgetLeft, other, blocker);
    }

    Check withBlocker(UUID blocker) {
        return new Check(problem, cost, budgetLeft, other, blocker);
    }
}
