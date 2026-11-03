package dev.cornybirdy.estate.core;

import java.util.Locale;

/**
 * Trust levels, lowest first. What each one allows comes from {@code trustTiers} in the config.
 * {@link #NONE} is a block: the player gets nothing in the claim, whatever else would apply to them.
 */
public enum TrustTier {
    ACCESS,
    CONTAINERS,
    BUILD,
    MANAGE,
    NONE;

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static TrustTier byKey(String key) {
        for (TrustTier tier : values()) {
            if (tier.name().equalsIgnoreCase(key)) {
                return tier;
            }
        }
        return null;
    }

    public TrustTier next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
