package dev.cornybirdy.estate.core;

import java.util.Locale;

/**
 * Per-claim protection switches. For every flag except {@link #PVP}, {@code true} means protected. For PvP,
 * {@code true} means players may fight inside the claim.
 */
public enum Flag {
    BLOCK_BREAK_PLACE("blockBreakPlace"),
    CONTAINER_ACCESS("containerAccess"),
    EXPLOSIONS("explosions"),
    FIRE_SPREAD("fireSpread"),
    FLUID_FLOW_IN("fluidFlowIn"),
    PISTON_CROSS("pistonCross"),
    MOB_GRIEFING("mobGriefing"),
    ENTITY_DAMAGE("entityDamage"),
    PVP("pvp");

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
