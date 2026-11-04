package dev.cornybirdy.estate.core;

/** Things a trusted player may do in a claim. Which tier gives which permissions is set in the config. */
public enum Permission {
    /** Doors, trapdoors, gates, buttons, levers, beds, workstations, riding, trading. */
    INTERACT("interact"),
    /** Chests, barrels, furnaces and every other block that holds items, item frames, armour stands. */
    CONTAINERS("containers"),
    /** Placing and breaking blocks, using items on blocks, animals. */
    BUILD("build"),
    /** Building in the claim's buffer and claiming next to it. */
    BUFFER("buffer"),
    /** Changing the trust list. */
    TRUST("trust"),
    /** Resizing the claim. */
    RESIZE("resize");

    public final String key;

    Permission(String key) {
        this.key = key;
    }

    public static Permission byKey(String key) {
        for (Permission permission : values()) {
            if (permission.key.equalsIgnoreCase(key)) {
                return permission;
            }
        }
        return null;
    }
}
