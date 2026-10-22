package dev.cornybirdy.estate;

import dev.cornybirdy.estate.core.Check;

/** Shared placeholder sets for messages. */
final class Messages {

    private Messages() {
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
