package dev.cornybirdy.estate.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Every tunable value. Loaded from and saved to {@code config/estate.json} by {@link ConfigFile}; admins change single
 * values live with {@link #set}. Field names are the setting names.
 */
public final class EstateConfig {

    /** Bump when a setting is renamed or its meaning changes, and add a step to {@link ConfigFile#migrate}. */
    public static final int CURRENT_VERSION = 3;

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface Range {
        int min();

        int max();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface Hidden {
    }

    @Hidden
    public int configVersion = CURRENT_VERSION;

    /** Blocks of land (counted from above, so height is free) every player can claim. The same for everyone;
     * playtime never changes it. 4096 is a 64x64 area. */
    @Range(min = 0, max = 100_000_000)
    public int baseBudget = 4096;
    /** Most blocks a player can claim in total across all claims, without an admin grant. */
    @Range(min = 0, max = 100_000_000)
    public int maxTotalBudget = 4096;
    /** Most blocks a player with an admin grant can claim in total. */
    @Range(min = 0, max = 100_000_000)
    public int maxGrantedBudget = 16384;
    @Range(min = 1, max = 1000)
    public int maxClaimsPerPlayer = 5;
    /** Blocks added above the highest and below the lowest block of a claim. */
    @Range(min = 0, max = 256)
    public int verticalPadding = 10;
    /** Blocks around every claim where only trusted players may build or claim. 0 turns buffers off. */
    @Range(min = 0, max = 256)
    public int bufferDistance = 8;
    /** If above 0: in a buffer they're allowed to build in, players can't build higher than this many blocks above
     * the claim's ground level. 0 = off. */
    @Range(min = 0, max = 512)
    public int bufferHeightCap = 0;
    /** Placed blocks in one cluster before auto-claim makes a claim (or the build tip is shown). */
    @Range(min = 1, max = 100_000)
    public int autoClaimThreshold = 50;
    /** Blocks within this distance of the newest block count as one build. */
    @Range(min = 2, max = 128)
    public int autoClaimClusterRadius = 16;
    /** Blocks of land auto-claim adds around a build on every side. */
    @Range(min = 0, max = 64)
    public int autoClaimMargin = 4;
    /** Whether auto-claim starts on for new players. */
    public boolean autoClaimDefault = false;
    /** Tell players with auto-claim off about claims once they've built something (once per session). */
    public boolean buildPrompt = true;
    /** Grow an owner's claim up or down when they build above or below it, even with auto-claim off. */
    public boolean autoExtendVertical = true;
    /** Blocks of room above the ground in a claim drawn with the claim tool. */
    @Range(min = 0, max = 512)
    public int newClaimHeadroom = 24;
    /** Claims are removed when the owner hasn't logged in for this many days. 0 = never. */
    @Range(min = 0, max = 100_000)
    public int inactivityDays = 30;
    /** Remove a player's claims when they're banned permanently. */
    public boolean unclaimOnPermanentBan = true;
    /** How long "View borders" and previews stay visible. */
    @Range(min = 1, max = 300)
    public int borderParticleSeconds = 10;
    /** How borders are drawn: "blocks" (glass walls with glowing edges) or "particles". */
    public String borderStyle = "blocks";
    /** How long placed-block records are kept. 0 = forever. */
    @Range(min = 0, max = 100_000)
    public int placedBlockRetentionDays = 90;
    /** Action bar message when walking into or out of a claim. */
    public boolean enterLeaveMessages = true;
    /** Worlds (dimension ids) where claims can be made. */
    public List<String> claimableWorlds = new ArrayList<>(List.of("minecraft:overworld"));
    /** Flag defaults for every claim. true = protected. */
    public Map<String, Boolean> flagDefaults = defaultFlags();
    /** Flags claim owners may change for their own claims. */
    public List<String> ownerEditableFlags = defaultEditableFlags();
    /** What each trust tier allows: interact, containers, build, buffer, trust, resize. */
    public Map<String, List<String>> trustTiers = defaultTiers();
    /** Blocks that need only the "interact" permission (ids or #tags). */
    public List<String> accessBlocks = new ArrayList<>(List.of(
            "#minecraft:doors", "#minecraft:trapdoors", "#minecraft:fence_gates", "#minecraft:buttons",
            "minecraft:lever", "#minecraft:beds", "minecraft:bell", "minecraft:crafting_table",
            "minecraft:enchanting_table", "#minecraft:anvil", "minecraft:grindstone", "minecraft:stonecutter",
            "minecraft:loom", "minecraft:cartography_table", "minecraft:smithing_table", "minecraft:ender_chest"));
    /** Extra blocks treated as containers, on top of every block that holds items. */
    public List<String> containerBlocks = new ArrayList<>();
    /** Blocks that never count towards auto-claim (torches, rails, scaffolding and other things used to get around). */
    public List<String> autoClaimIgnoredBlocks = new ArrayList<>(List.of(
            "minecraft:torch", "minecraft:wall_torch", "minecraft:soul_torch", "minecraft:soul_wall_torch",
            "minecraft:redstone_torch", "minecraft:redstone_wall_torch", "minecraft:copper_torch",
            "minecraft:copper_wall_torch", "minecraft:scaffolding", "minecraft:ladder", "#minecraft:rails",
            "minecraft:tnt", "minecraft:fire", "minecraft:soul_fire"));

    private static Map<String, Boolean> defaultFlags() {
        Map<String, Boolean> flags = new LinkedHashMap<>();
        for (Flag flag : Flag.values()) {
            flags.put(flag.key, true);
        }
        return flags;
    }

    static List<String> defaultEditableFlags() {
        return new ArrayList<>(List.of("explosions", "fireSpread", "fluidFlowIn", "pistonCross", "entityDamage"));
    }

    private static Map<String, List<String>> defaultTiers() {
        Map<String, List<String>> tiers = new LinkedHashMap<>();
        tiers.put("access", new ArrayList<>(List.of("interact")));
        tiers.put("containers", new ArrayList<>(List.of("interact", "containers")));
        tiers.put("build", new ArrayList<>(List.of("interact", "containers", "build", "buffer")));
        tiers.put("manage", new ArrayList<>(List.of("interact", "containers", "build", "buffer", "trust", "resize")));
        return tiers;
    }

    // Derived lookups, rebuilt by refresh() after loading or changing a value.

    private transient Map<Flag, Boolean> flagCache;
    private transient Set<Flag> editableCache;
    private transient Map<TrustTier, Set<Permission>> tierCache;

    /** Rebuilds the lookups. Call after loading or changing values. */
    public void refresh() {
        Map<Flag, Boolean> flags = new EnumMap<>(Flag.class);
        Map<String, Boolean> builtIn = defaultFlags();
        for (Flag flag : Flag.values()) {
            Boolean value = flagDefaults == null ? null : flagDefaults.get(flag.key);
            flags.put(flag, value != null ? value : builtIn.get(flag.key));
        }
        Set<Flag> editable = EnumSet.noneOf(Flag.class);
        if (ownerEditableFlags != null) {
            for (String key : ownerEditableFlags) {
                Flag flag = Flag.byKey(key);
                if (flag != null) {
                    editable.add(flag);
                }
            }
        }
        Map<TrustTier, Set<Permission>> tiers = new EnumMap<>(TrustTier.class);
        Map<String, List<String>> builtInTiers = defaultTiers();
        for (TrustTier tier : TrustTier.values()) {
            List<String> keys = trustTiers == null ? null : trustTiers.get(tier.key());
            if (keys == null) {
                keys = builtInTiers.get(tier.key());
            }
            Set<Permission> permissions = EnumSet.noneOf(Permission.class);
            for (String key : keys) {
                Permission permission = Permission.byKey(key);
                if (permission != null) {
                    permissions.add(permission);
                }
            }
            tiers.put(tier, permissions);
        }
        flagCache = flags;
        editableCache = editable;
        tierCache = tiers;
    }

    public boolean flagDefault(Flag flag) {
        if (flagCache == null) {
            refresh();
        }
        return flagCache.get(flag);
    }

    public boolean ownerMayEdit(Flag flag) {
        if (editableCache == null) {
            refresh();
        }
        return editableCache.contains(flag);
    }

    public boolean tierAllows(TrustTier tier, Permission permission) {
        if (tierCache == null) {
            refresh();
        }
        return tierCache.get(tier).contains(permission);
    }

    public boolean isClaimable(String world) {
        return claimableWorlds != null && claimableWorlds.contains(world);
    }

    // Live changes from /land admin set.

    /** Setting names admins can change, in file order. */
    public static List<String> settingNames() {
        List<String> names = new ArrayList<>();
        for (Field field : EstateConfig.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && !field.isAnnotationPresent(Hidden.class)) {
                names.add(field.getName());
            }
        }
        return names;
    }

    /** A setting's current value as text, or null if there's no such setting. */
    public String get(String name) {
        Field field = field(name);
        if (field == null) {
            return null;
        }
        try {
            Object value = field.get(this);
            if (value instanceof List<?> list) {
                return String.join(",", list.stream().map(String::valueOf).toList());
            }
            return String.valueOf(value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The exact field name for a setting typed in any case, or null. Map entries use a dot, like {@code flagDefaults.explosions}. */
    public static String canonicalName(String name) {
        String base = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
        for (String setting : settingNames()) {
            if (setting.equalsIgnoreCase(base)) {
                return name.contains(".") ? setting + name.substring(name.indexOf('.')) : setting;
            }
        }
        return null;
    }

    /**
     * Changes one setting from text. Numbers are checked against their allowed range, lists are comma-separated.
     * {@code flagDefaults.<flag>} and {@code trustTiers.<tier>} change one entry of those maps.
     *
     * @return null on success, otherwise why the value was refused
     */
    public String set(String name, String value) {
        String canonical = canonicalName(name);
        if (canonical == null) {
            return "Unknown setting '" + name + "'.";
        }
        value = value.trim();
        try {
            if (canonical.startsWith("flagDefaults.")) {
                Flag flag = Flag.byKey(canonical.substring("flagDefaults.".length()));
                if (flag == null) {
                    return "Unknown flag. Flags: " + flagKeys();
                }
                Boolean on = parseBoolean(value);
                if (on == null) {
                    return "Use true or false.";
                }
                flagDefaults.put(flag.key, on);
            } else if (canonical.startsWith("trustTiers.")) {
                TrustTier tier = TrustTier.byKey(canonical.substring("trustTiers.".length()));
                if (tier == null) {
                    return "Unknown tier. Tiers: access, containers, build, manage.";
                }
                List<String> permissions = splitList(value);
                for (String permission : permissions) {
                    if (Permission.byKey(permission) == null) {
                        return "Unknown permission '" + permission + "'. Use interact, containers, build, buffer, trust, resize.";
                    }
                }
                trustTiers.put(tier.key(), permissions);
            } else {
                Field field = field(canonical);
                if (field == null || canonical.contains(".")) {
                    return "That setting can't be changed this way.";
                }
                Class<?> type = field.getType();
                if (type == int.class) {
                    int number = Integer.parseInt(value);
                    Range range = field.getAnnotation(Range.class);
                    if (range != null && (number < range.min() || number > range.max())) {
                        return canonical + " must be between " + range.min() + " and " + range.max() + ".";
                    }
                    field.setInt(this, number);
                } else if (type == boolean.class) {
                    Boolean on = parseBoolean(value);
                    if (on == null) {
                        return "Use true or false.";
                    }
                    field.setBoolean(this, on);
                } else if (type == List.class) {
                    field.set(this, splitList(value));
                } else if (type == String.class) {
                    if (canonical.equals("borderStyle") && !value.equals("blocks") && !value.equals("particles")) {
                        return "borderStyle is either blocks or particles.";
                    }
                    field.set(this, value);
                } else {
                    return canonical + " can only be changed in the config file (use dotted names for single entries).";
                }
            }
        } catch (NumberFormatException e) {
            return "'" + value + "' is not a whole number.";
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        refresh();
        return null;
    }

    /** Whether a setting holds a whole number, for menus. */
    public static boolean isNumber(String name) {
        Field field = field(name);
        return field != null && field.getType() == int.class;
    }

    public static boolean isBoolean(String name) {
        Field field = field(name);
        return field != null && field.getType() == boolean.class;
    }

    public static Range range(String name) {
        Field field = field(name);
        return field == null ? null : field.getAnnotation(Range.class);
    }

    private static Field field(String name) {
        try {
            Field field = EstateConfig.class.getField(name);
            return Modifier.isStatic(field.getModifiers()) || field.isAnnotationPresent(Hidden.class) ? null : field;
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    private static Boolean parseBoolean(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true", "on", "yes", "1" -> true;
            case "false", "off", "no", "0" -> false;
            default -> null;
        };
    }

    private static List<String> splitList(String value) {
        List<String> list = new ArrayList<>();
        for (String part : value.split(",")) {
            if (!part.isBlank()) {
                list.add(part.trim());
            }
        }
        return list;
    }

    private static String flagKeys() {
        return String.join(", ", Arrays.stream(Flag.values()).map(f -> f.key).toList());
    }
}
