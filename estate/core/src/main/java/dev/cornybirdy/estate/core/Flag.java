package dev.cornybirdy.estate.core;

import java.util.Locale;

/**
 * Per-claim protection switches; {@code true} means protected. PvP is left to a PvP mod such as Gatekeeper, and mobs
 * behave as in vanilla (the {@code mobGriefing} game rule covers them); both were flags before 1.3 and old values of
 * them are ignored.
 */
public enum Flag {
    BLOCK_BREAK_PLACE("blockBreakPlace"),
    CONTAINER_ACCESS("containerAccess"),
    EXPLOSIONS("explosions"),
    FIRE_SPREAD("fireSpread"),
    FLUID_FLOW_IN("fluidFlowIn"),
    PISTON_CROSS("pistonCross"),
    ENTITY_DAMAGE("entityDamage");

    public final String key;

    Flag(String key) {
        this.key = key;
    }

    public static Flag byKey(String key) {
        for (Flag flag : values()) {
            if (flag.key.equalsIgnoreCase(key) || flag.name().equalsIgnoreCase(key)) {
                return flag;
            }
        }
        return null;
    }

    public String lowerName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
