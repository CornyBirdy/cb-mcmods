package dev.cornybirdy.estate.core;

/** Why a claim couldn't be made or changed. Each one has a message with the same key in lowercase-dashes. */
public enum Problem {
    WORLD_NOT_CLAIMABLE("world-not-claimable"),
    EMPTY("claim-empty"),
    BAD_HEIGHT("bad-height"),
    TOO_MANY_CLAIMS("too-many-claims"),
    OVERLAP("overlap"),
    BUFFER("in-buffer"),
    NO_BUDGET("no-budget"),
    OTHERS_BLOCKS("others-blocks"),
    NOT_ALLOWED("not-allowed");

    public final String messageKey;

    Problem(String messageKey) {
        this.messageKey = messageKey;
    }
}
