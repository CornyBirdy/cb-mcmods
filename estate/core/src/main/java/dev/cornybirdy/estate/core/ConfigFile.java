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
        // Version 1 is the first release; nothing to migrate yet.
        json.addProperty("configVersion", EstateConfig.CURRENT_VERSION);
    }

    public static synchronized void save(Path file, EstateConfig config) throws IOException {
        writeAtomically(file, GSON.toJson(config));
    }

    /** Loads messages: the bundled defaults with the admin's file on top. Missing keys are added to the file. */
    public static Map<String, String> loadMessages(Path file) throws IOException {
        Map<String, String> defaults = bundledMessages();
        Map<String, String> messages = new LinkedHashMap<>(defaults);
        boolean changed = !Files.exists(file);
        if (!changed) {
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    messages.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
            for (String key : defaults.keySet()) {
                if (!json.has(key)) {
                    changed = true;
                }
            }
        }
        if (changed) {
            JsonObject out = new JsonObject();
            messages.forEach(out::addProperty);
            writeAtomically(file, GSON.toJson(out));
        }
        return messages;
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
