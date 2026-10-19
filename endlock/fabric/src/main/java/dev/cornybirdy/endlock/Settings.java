package dev.cornybirdy.endlock;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.MutableComponent;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * {@code config/endlock.json}. {@code /endlock} writes the on/off switches back to the file, so they survive
 * restarts. Options and messages missing from the file fall back to the defaults bundled in the mod.
 */
final class Settings {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path file;
    private final JsonObject config;
    private final JsonObject defaults;
    private volatile boolean enderChestsEnabled;
    private volatile boolean endEnabled;
    final boolean announceChanges;

    private Settings(Path file, JsonObject config, JsonObject defaults) {
        this.file = file;
        this.config = config;
        this.defaults = defaults;
        enderChestsEnabled = bool(Feature.ENDER_CHESTS.configKey);
        endEnabled = bool(Feature.END.configKey);
        announceChanges = bool("announce-changes");
    }

    /** Loads the config, writing the default file first if there is none. */
    static Settings load(Path file) throws IOException {
        JsonObject defaults = readDefaults();
        if (!Files.exists(file)) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(defaults), StandardCharsets.UTF_8);
            return new Settings(file, defaults.deepCopy(), defaults);
        }
        JsonObject config = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        return new Settings(file, config, defaults);
    }

    /** Settings from the bundled defaults only, used if the config file can't be read. */
    static Settings defaults(Path file) {
        try {
            JsonObject defaults = readDefaults();
            return new Settings(file, defaults.deepCopy(), defaults);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("default-config.json is missing from the mod jar", e);
        }
    }

    private static JsonObject readDefaults() throws IOException {
        try (InputStream in = Settings.class.getResourceAsStream("/assets/endlock/default-config.json")) {
            if (in == null) {
                throw new IOException("default-config.json is missing from the mod jar");
            }
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    boolean isEnabled(Feature feature) {
        return feature == Feature.ENDER_CHESTS ? enderChestsEnabled : endEnabled;
    }

    /** Turns a feature on or off and saves it to the config file. The change applies even if saving fails. */
    synchronized void setEnabled(Feature feature, boolean enabled) throws IOException {
        if (feature == Feature.ENDER_CHESTS) {
            enderChestsEnabled = enabled;
        } else {
            endEnabled = enabled;
        }
        config.addProperty(feature.configKey, enabled);
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(config), StandardCharsets.UTF_8);
    }

    /** A chat message from the {@code messages} section. {@code <state>} becomes the enabled/disabled message. */
    MutableComponent message(String key, Boolean state) {
        String template = raw(key);
        if (state != null) {
            template = template.replace("<state>", raw(state ? "enabled" : "disabled"));
        }
        return Text.parse(template, Map.of());
    }

    MutableComponent message(String key) {
        return message(key, null);
    }

    private String raw(String key) {
        JsonElement value = messageValue(config, key);
        if (value == null) {
            value = messageValue(defaults, key);
        }
        return value == null ? key : value.getAsString();
    }

    private static JsonElement messageValue(JsonObject root, String key) {
        JsonObject messages = root.getAsJsonObject("messages");
        JsonElement value = messages == null ? null : messages.get(key);
        return value != null && value.isJsonPrimitive() ? value : null;
    }

    private boolean bool(String key) {
        JsonElement value = config.get(key);
        return (value != null && value.isJsonPrimitive() ? value : defaults.get(key)).getAsBoolean();
    }
}
