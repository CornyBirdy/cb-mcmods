package dev.cornybirdy.gatekeeper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Opt-in PvP: players can only hurt each other when both have turned it on with {@code /pvp on}. Changing it has a
 * cooldown, and after a hit both players are "in combat" for a while and can't turn it off. Each player's choice is
 * saved in {@code <world>/gatekeeper/pvp.json}; players who never chose get {@code pvp-default}.
 */
final class Pvp {

    static final String DEFAULT = "pvp-default";
    static final String COOLDOWN = "pvp-toggle-cooldown-seconds";
    static final String COMBAT = "pvp-combat-seconds";
    static final String COMBAT_LOG = "combat-log-kill";

    enum Outcome {
        CHANGED, UNCHANGED, COOLDOWN, IN_COMBAT
    }

    /** What {@link #set} did, and for a cooldown or combat, how many seconds are left. */
    record Result(Outcome outcome, long secondsLeft) {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("Gatekeeper");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Supplier<Settings> settings;
    private final Map<UUID, Boolean> choices = new HashMap<>();
    private final Map<UUID, Long> lastChange = new HashMap<>();
    private final Map<UUID, Long> combatUntil = new HashMap<>();

    Pvp(MinecraftServer server, Supplier<Settings> settings) {
        this.file = server.getWorldPath(LevelResource.ROOT).resolve("gatekeeper").resolve("pvp.json");
        this.settings = settings;
        load();
    }

    boolean enabled(UUID player) {
        Boolean choice = choices.get(player);
        return choice != null ? choice : settings.get().option(DEFAULT);
    }

    /** Turns a player's PvP on or off, unless they changed it too recently or are in a fight (for turning it off). */
    Result set(UUID player, boolean on) {
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

    /** Whether one player may hurt another: only if both have PvP on. Hurting yourself is always allowed. */
    boolean mayHurt(UUID attacker, UUID victim) {
        return attacker.equals(victim) || (enabled(attacker) && enabled(victim));
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
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                try {
                    choices.put(UUID.fromString(entry.getKey()), entry.getValue().getAsBoolean());
                } catch (RuntimeException e) {
                    LOGGER.warn("Ignoring '{}' in {}", entry.getKey(), file);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Could not read {}; everyone starts with the default PvP setting", file, e);
        }
    }

    private void save() {
        JsonObject json = new JsonObject();
        choices.forEach((id, on) -> json.addProperty(id.toString(), on));
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
