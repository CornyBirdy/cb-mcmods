package dev.cornybirdy.estate.core;

import java.util.UUID;

/**
 * One line of the claim and admin log.
 *
 * @param actor who did it, or null for the server itself (expiry, bans)
 * @param type  short name like {@code create}, {@code resize}, {@code trust}, {@code admin-delete}
 */
public record Action(long time, UUID actor, String type, Integer claimId, String world, Integer x, Integer y, Integer z,
                     String details) {

    public static Action of(long time, UUID actor, String type, Claim claim, String details) {
        return new Action(time, actor, type, claim == null ? null : claim.id(), claim == null ? null : claim.world(),
                null, null, null, details == null ? "" : details);
    }
}
