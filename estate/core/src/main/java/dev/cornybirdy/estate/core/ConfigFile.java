package dev.cornybirdy.estate.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads and writes {@code estate.json} and {@code estate-messages.json}. Values the admin set are never changed by an
 * update: settings that are new in this version are added with their defaults, and if a setting is ever renamed the
 * old file is backed up and the value carried over by {@link #migrate}.
 */
public final class ConfigFile {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private ConfigFile() {
    }

    /** Loads the config, writing it first if it's missing. Throws if the file exists but can't be read. */
    public static EstateConfig load(Path file) throws IOException {
        if (!Files.exists(file)) {
            EstateConfig config = new EstateConfig();
            config.refresh();
            save(file, config);
            return config;
        }
        JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        int version = json.has("configVersion") ? json.get("configVersion").getAsInt() : 1;
        boolean changed = false;
        if (version > EstateConfig.CURRENT_VERSION) {
            throw new IOException(file + " was written by a newer version of Estate (config version " + version
                    + "). Update the mod or restore an older config.");
        }
        if (version < EstateConfig.CURRENT_VERSION) {
            Files.copy(file, file.resolveSibling(file.getFileName() + ".v" + version + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            migrate(json, version);
            changed = true;
        }
        EstateConfig config = GSON.fromJson(json, EstateConfig.class);
        config.configVersion = EstateConfig.CURRENT_VERSION;
        config.refresh();
        // Write new settings into the file so admins can see them; existing values are kept as they are.
        JsonObject full = GSON.toJsonTree(config).getAsJsonObject();
        for (String key : full.keySet()) {
            if (!json.has(key)) {
                changed = true;
            }
        }
        if (changed) {
            save(file, config);
        }
        return config;
    }

    /** Upgrades an old config in place. Add a step here whenever {@link EstateConfig#CURRENT_VERSION} goes up. */
    static void migrate(JsonObject json, int fromVersion) {
        if (fromVersion < 2) {
            // Version 2: claims are exact to the block, so budgets are counted in blocks instead of cells.
            int cellSize = json.has("cellSize") ? json.get("cellSize").getAsInt() : 8;
            json.remove("cellSize");
            json.remove("newClaimSize");
            json.remove("resizeVerticalStep");
            for (String key : new String[]{"baseBudget", "maxTotalBudget", "maxGrantedBudget"}) {
                if (json.has(key)) {
                    long blocks = json.get(key).getAsLong() * cellSize * cellSize;
                    json.addProperty(key, (int) Math.min(100_000_000L, blocks));
                }
            }
        }
        if (fromVersion < 3) {
            // Version 3: PvP is left to a PvP mod and mobs behave as in vanilla, so those flags are gone.
            if (json.has("flagDefaults") && json.get("flagDefaults").isJsonObject()) {
                json.getAsJsonObject("flagDefaults").remove("pvp");
                json.getAsJsonObject("flagDefaults").remove("mobGriefing");
            }
            if (json.has("ownerEditableFlags") && json.get("ownerEditableFlags").isJsonArray()) {
                com.google.gson.JsonArray old = json.getAsJsonArray("ownerEditableFlags");
                com.google.gson.JsonArray kept = new com.google.gson.JsonArray();
                for (JsonElement flag : old) {
                    String key = flag.getAsString();
                    if (!key.equalsIgnoreCase("pvp") && !key.equalsIgnoreCase("mobGriefing")) {
                        kept.add(key);
                    }
                }
                if (kept.isEmpty()) {
                    // It only held the removed flags (the old default): use the new default.
                    EstateConfig.defaultEditableFlags().forEach(kept::add);
                }
                json.add("ownerEditableFlags", kept);
            }
        }
        json.addProperty("configVersion", EstateConfig.CURRENT_VERSION);
    }

    public static synchronized void save(Path file, EstateConfig config) throws IOException {
        writeAtomically(file, GSON.toJson(config));
    }

    /** Bump when the bundled messages change so much that old files can't be kept (they're backed up and replaced). */
    static final int MESSAGES_VERSION = 2;
    private static final String MESSAGES_VERSION_KEY = "_version";

    /**
     * Loads messages: the bundled defaults with the admin's file on top. Missing keys are added to the file. A copy
     * of the defaults the file was last written with is kept next to it, so messages the admin never changed follow
     * the new defaults after an update while their own wording is kept.
     */
    public static Map<String, String> loadMessages(Path file) throws IOException {
        Map<String, String> defaults = bundledMessages();
        Path snapshot = file.resolveSibling("." + file.getFileName() + ".defaults");
        Map<String, String> messages = new LinkedHashMap<>(defaults);
        boolean changed = true;
        if (Files.exists(file)) {
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            int version = json.has(MESSAGES_VERSION_KEY) ? json.get(MESSAGES_VERSION_KEY).getAsInt() : 1;
            if (version < MESSAGES_VERSION) {
                Path backup = file.resolveSibling(file.getFileName() + ".v" + version + ".bak");
                Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
                org.slf4j.LoggerFactory.getLogger("Estate").warn("The messages changed a lot in this version, so {} was"
                        + " reset to the new defaults. Your old file is at {}.", file.getFileName(), backup.getFileName());
            } else {
                Map<String, String> oldDefaults = Files.exists(snapshot) ? readFlat(snapshot) : Map.of();
                changed = false;
                for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                    String key = entry.getKey();
                    if (key.startsWith("_") || !entry.getValue().isJsonPrimitive()) {
                        continue;
                    }
                    String value = entry.getValue().getAsString();
                    String newDefault = defaults.get(key);
                    if (value.equals(oldDefaults.get(key)) && !value.equals(newDefault)) {
                        // Never changed by the admin: use the new default (or drop it if the message is gone).
                        changed = true;
                        continue;
                    }
                    messages.put(key, value);
                }
                for (String key : defaults.keySet()) {
                    if (!json.has(key)) {
                        changed = true;
                    }
                }
            }
        }
        if (changed) {
            JsonObject out = new JsonObject();
            out.addProperty(MESSAGES_VERSION_KEY, MESSAGES_VERSION);
            messages.forEach(out::addProperty);
            writeAtomically(file, GSON.toJson(out));
        }
        JsonObject copy = new JsonObject();
        defaults.forEach(copy::addProperty);
        String copyText = GSON.toJson(copy);
        if (!Files.exists(snapshot) || !Files.readString(snapshot, StandardCharsets.UTF_8).equals(copyText)) {
            writeAtomically(snapshot, copyText);
        }
        return messages;
    }

    private static Map<String, String> readFlat(Path file) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            if (entry.getValue().isJsonPrimitive()) {
                values.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        return values;
    }

    public static Map<String, String> bundledMessages() throws IOException {
        try (InputStream in = ConfigFile.class.getResourceAsStream("/estate/messages.json")) {
            if (in == null) {
                throw new IOException("estate/messages.json is missing from the mod jar");
            }
            JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, String> messages = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                messages.put(entry.getKey(), entry.getValue().getAsString());
            }
            return messages;
        }
    }

    /** Writes to a temporary file first and then swaps it in, so a crash never leaves half a file behind. */
    public static void writeAtomically(Path file, String content) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
