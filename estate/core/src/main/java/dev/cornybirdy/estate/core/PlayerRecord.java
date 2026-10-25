package dev.cornybirdy.estate.core;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** What Estate remembers about a player. */
public final class PlayerRecord {

    public final UUID id;
    public String name;
    /** Epoch seconds. */
    public long firstSeen;
    /** Epoch seconds of the last login or logout. Inactivity expiry counts from here. */
    public long lastSeen;
    /** Null until the player chooses; then the config default no longer applies. */
    public Boolean autoClaim;
    /** True once the player has claimed or used /land autoclaim, so the build tip is never shown again. */
    public boolean promptDone;
    /** Extra cells granted by an admin. */
    public int bonusBudget;
    /** Players whose placed blocks never stop this player from claiming (they helped build). */
    public final Set<UUID> buildPartners = new LinkedHashSet<>();

    public PlayerRecord(UUID id, String name) {
        this.id = id;
        this.name = name == null ? "" : name;
    }

    public boolean autoClaim(EstateConfig config) {
        return autoClaim != null ? autoClaim : config.autoClaimDefault;
    }

    public String displayName() {
        return name.isEmpty() ? id.toString().substring(0, 8) : name;
    }
}
