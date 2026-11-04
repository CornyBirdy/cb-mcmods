package dev.cornybirdy.estate.core;

/**
 * Why a player can't do something at a spot.
 *
 * @param kind  what kind of area stopped them
 * @param claim the claim that owns the spot or the buffer
 */
public record Denial(Kind kind, Claim claim) {

    public enum Kind {
        /** Inside someone's claim. */
        CLAIM,
        /** In the buffer around someone's claim. */
        BUFFER,
        /** In a buffer they may build in, but above the height cap. */
        HEIGHT_CAP
    }
}
