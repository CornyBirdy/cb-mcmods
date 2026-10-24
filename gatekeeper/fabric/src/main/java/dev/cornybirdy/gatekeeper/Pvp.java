package dev.cornybirdy.gatekeeper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Opt-in PvP: players can only hurt each other when both have turned it on with {@code /pvp on}. Changing it has a
 * cooldown, and after a hit both players are "in combat" for a while and can't turn it off.
 * <p>
 * Exceptions: two players who have both {@code /pvp allow}ed each other can fight even with PvP off, and an admin can
 * lock a player's PvP on or off ({@code /gatekeeper pvpforce}), which they then can't change. A player locked off can't
 * fight anyone, allowed or not.
 * <p>
 * Everything is saved in {@code <world>/gatekeeper/pvp.json}; players who never chose get {@code pvp-default}.
 */
final class Pvp {

    static final String DEFAULT = "pvp-default";
    static final String COOLDOWN = "pvp-toggle-cooldown-seconds";
    static final String COMBAT = "pvp-combat-seconds";
    static final String COMBAT_LOG = "combat-log-kill";

    enum Outcome {
        CHANGED, UNCHANGED, COOLDOWN, IN_COMBAT, FORCED
    }

    /** What {@link #set} did, and for a cooldown or combat, how many seconds are left. */
    record Result(Outcome outcome, long secondsLeft) {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("Gatekeeper");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Supplier<Settings> settings;
    private final Map<UUID, Boolean> choices = new HashMap<>();
    /** Set by an admin; overrides the player's own choice. */
    private final Map<UUID, Boolean> forced = new HashMap<>();
    /** Who each player has allowed to fight them even with PvP off. Only counts when it goes both ways. */
    private final Map<UUID, Set<UUID>> allowed = new HashMap<>();
    private final Map<UUID, Long> lastChange = new HashMap<>();
    private final Map<UUID, Long> combatUntil = new HashMap<>();

    Pvp(MinecraftServer server, Supplier<Settings> settings) {
        this.file = server.getWorldPath(LevelResource.ROOT).resolve("gatekeeper").resolve("pvp.json");
        this.settings = settings;
        load();
    }

    boolean enabled(UUID player) {
        Boolean lock = forced.get(player);
        if (lock != null) {
            return lock;
        }
        Boolean choice = choices.get(player);
        return choice != null ? choice : settings.get().option(DEFAULT);
    }

    /** The admin lock on a player's PvP, or null if they choose for themselves. */
    Boolean forced(UUID player) {
        return forced.get(player);
    }

    /** @param on null removes the lock */
    void force(UUID player, Boolean on) {
        if (on == null) {
            forced.remove(player);
        } else {
            forced.put(player, on);
        }
        save();
    }

    /** Turns a player's PvP on or off, unless they changed it too recently or are in a fight (for turning it off). */
    Result set(UUID player, boolean on) {
        if (forced.containsKey(player)) {
            return new Result(Outcome.FORCED, 0);
        }
        if (enabled(player) == on) {
            return new Result(Outcome.UNCHANGED, 0);
        }
        long now = System.currentTimeMillis();
        if (!on && inCombat(player)) {
            return new Result(Outcome.IN_COMBAT, combatSecondsLeft(player));
        }
        long cooldown = settings.get().integer(COOLDOWN) * 1000L;
        Long last = lastChange.get(player);
        if (last != null && now - last < cooldown) {
            return new Result(Outcome.COOLDOWN, seconds(last + cooldown - now));
        }
        choices.put(player, on);
        lastChange.put(player, now);
        save();
        return new Result(Outcome.CHANGED, 0);
    }

    /**
     * Whether one player may hurt another: if both have PvP on, or they've allowed each other. Never if an admin
     * locked either of them off. Hurting yourself is always allowed.
     */
    boolean mayHurt(UUID attacker, UUID victim) {
        if (attacker.equals(victim)) {
            return true;
        }
        if (Boolean.FALSE.equals(forced.get(attacker)) || Boolean.FALSE.equals(forced.get(victim))) {
            return false;
        }
        return mutual(attacker, victim) || (enabled(attacker) && enabled(victim));
    }

    /** True if both players have allowed each other. */
    boolean mutual(UUID a, UUID b) {
        return allows(a, b) && allows(b, a);
    }

    /** True if {@code player} has allowed {@code other} to fight them. */
    boolean allows(UUID player, UUID other) {
        return allowed.getOrDefault(player, Set.of()).contains(other);
    }

    /** Adds or removes someone from a player's allow list. Returns false if nothing changed. */
    boolean setAllowed(UUID player, UUID other, boolean allow) {
        Set<UUID> list = allowed.computeIfAbsent(player, id -> new HashSet<>());
        boolean changed = allow ? list.add(other) : list.remove(other);
        if (list.isEmpty()) {
            allowed.remove(player);
        }
        if (changed) {
            save();
        }
        return changed;
    }

    /** Everyone this player has allowed. */
    Set<UUID> allowedBy(UUID player) {
        return Set.copyOf(allowed.getOrDefault(player, Set.of()));
    }

    /** Everyone who has allowed this player. */
    Set<UUID> allowing(UUID player) {
        Set<UUID> found = new HashSet<>();
        allowed.forEach((id, list) -> {
            if (list.contains(player)) {
                found.add(id);
            }
        });
        return found;
    }

    /**
     * Marks a player as in combat after a hit.
     *
     * @return true if the player wasn't in combat before, so they can be told
     */
    boolean tag(UUID player) {
        boolean fresh = !inCombat(player);
        combatUntil.put(player, System.currentTimeMillis() + settings.get().integer(COMBAT) * 1000L);
        return fresh;
    }

    boolean inCombat(UUID player) {
        Long until = combatUntil.get(player);
        return until != null && until > System.currentTimeMillis();
    }

    long combatSecondsLeft(UUID player) {
        Long until = combatUntil.get(player);
        return until == null ? 0 : seconds(until - System.currentTimeMillis());
    }

    void endCombat(UUID player) {
        combatUntil.remove(player);
    }

    private static long seconds(long millis) {
        return Math.max(1, (millis + 999) / 1000);
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!json.has("choices")) {
                // Gatekeeper 1.2.0 saved only the choices, as "uuid": true/false.
                readFlags(json, choices);
                return;
            }
            readFlags(json.getAsJsonObject("choices"), choices);
            if (json.has("forced")) {
                readFlags(json.getAsJsonObject("forced"), forced);
            }
            if (json.has("allowed")) {
                for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("allowed").entrySet()) {
                    UUID player = uuid(entry.getKey());
                    if (player == null || !entry.getValue().isJsonArray()) {
                        continue;
                    }
                    Set<UUID> list = new HashSet<>();
                    for (JsonElement other : entry.getValue().getAsJsonArray()) {
                        UUID id = uuid(other.getAsString());
                        if (id != null) {
                            list.add(id);
                        }
                    }
                    if (!list.isEmpty()) {
                        allowed.put(player, list);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Could not read {}; everyone starts with the default PvP setting", file, e);
        }
    }

    private void readFlags(JsonObject json, Map<UUID, Boolean> into) {
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            UUID id = uuid(entry.getKey());
            if (id != null && entry.getValue().isJsonPrimitive()) {
                into.put(id, entry.getValue().getAsBoolean());
            }
        }
    }

    private UUID uuid(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Ignoring '{}' in {}", text, file);
            return null;
        }
    }

    private void save() {
        JsonObject choiceJson = new JsonObject();
        choices.forEach((id, on) -> choiceJson.addProperty(id.toString(), on));
        JsonObject forcedJson = new JsonObject();
        forced.forEach((id, on) -> forcedJson.addProperty(id.toString(), on));
        JsonObject allowedJson = new JsonObject();
        allowed.forEach((id, list) -> {
            JsonArray array = new JsonArray();
            list.stream().map(UUID::toString).sorted().forEach(array::add);
            allowedJson.add(id.toString(), array);
        });
        JsonObject json = new JsonObject();
        json.add("choices", choiceJson);
        json.add("forced", forcedJson);
        json.add("allowed", allowedJson);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("pvp.json.tmp");
            Files.writeString(tmp, GSON.toJson(json), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.error("Could not save {}; PvP choices are lost on restart", file, e);
        }
    }
}
