package dev.cornybirdy.corpes;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code config/corpes.json}. Options and messages missing from the file fall back to the defaults bundled in the
 * mod, so old config files keep working after updates.
 */
final class Settings {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final JsonObject config;
    private final JsonObject defaults;
    final boolean allowAnyoneLoot;
    final boolean storeXp;
    final int skeletonAfterMinutes;
    final int historySize;
    final boolean showName;
    final float hitboxWidth;
    final float hitboxHeight;
    final int labelViewDistance;
    final boolean labelSeeThrough;
    final boolean adminSeeAll;
    final boolean adminRestore;
    final int highlightSeconds;
    private final DateTimeFormatter dateFormat;

    private Settings(JsonObject config, JsonObject defaults) {
        this.config = config;
        this.defaults = defaults;
        allowAnyoneLoot = bool("allow-anyone-loot");
        storeXp = bool("store-xp");
        skeletonAfterMinutes = Math.max(0, get("skeleton-after-minutes").getAsInt());
        historySize = Math.max(1, get("history-size").getAsInt());
        showName = bool("show-name");
        hitboxWidth = (float) Math.max(0.2, get("hitbox-width").getAsDouble());
        hitboxHeight = (float) Math.max(0.1, get("hitbox-height").getAsDouble());
        labelViewDistance = Math.max(8, get("label-view-distance").getAsInt());
        labelSeeThrough = bool("label-see-through");
        adminSeeAll = bool("admin-see-all-deaths");
        adminRestore = bool("admin-restore");
        highlightSeconds = Math.max(0, get("highlight-seconds").getAsInt());
        DateTimeFormatter format;
        try {
            format = DateTimeFormatter.ofPattern(get("date-format").getAsString(), Locale.ENGLISH);
        } catch (IllegalArgumentException e) {
            format = DateTimeFormatter.ofPattern("dd MMM HH:mm", Locale.ENGLISH);
        }
        dateFormat = format.withZone(ZoneId.systemDefault());
    }

    /** Loads the config, writing the default file first if there is none. */
    static Settings load(Path file) throws IOException {
        JsonObject defaults;
        try (InputStream in = Settings.class.getResourceAsStream("/assets/corpes/default-config.json")) {
            if (in == null) {
                throw new IOException("default-config.json is missing from the mod jar");
            }
            defaults = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        if (!Files.exists(file)) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(defaults), StandardCharsets.UTF_8);
            return new Settings(defaults, defaults);
        }
        JsonObject config = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        return new Settings(config, defaults);
    }

    /** Settings from the bundled defaults only, used if the config file can't be read. */
    static Settings defaults() {
        try (InputStream in = Settings.class.getResourceAsStream("/assets/corpes/default-config.json")) {
            JsonObject defaults = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            return new Settings(defaults, defaults);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("default-config.json is missing from the mod jar", e);
        }
    }

    String formatDate(long epochMillis) {
        return dateFormat.format(Instant.ofEpochMilli(epochMillis));
    }

    /** A chat message from the {@code messages} section. */
    MutableComponent message(String key, Map<String, String> placeholders) {
        return Text.parse(lines(key).getFirst(), placeholders);
    }

    MutableComponent message(String key) {
        return message(key, Map.of());
    }

    /** Same as {@link #message} but without the default italic style item names get. */
    MutableComponent itemText(String key, Map<String, String> placeholders) {
        return Text.item(lines(key).getFirst(), placeholders);
    }

    /** Item lore. The entry can be a list of lines or a single line. */
    List<Component> itemLore(String key, Map<String, String> placeholders) {
        List<Component> lore = new ArrayList<>();
        for (String line : lines(key)) {
            lore.add(Text.item(line, placeholders));
        }
        return lore;
    }

    private List<String> lines(String key) {
        JsonElement value = messageValue(config, key);
        if (value == null) {
            value = messageValue(defaults, key);
        }
        List<String> lines = new ArrayList<>();
        if (value == null) {
            lines.add(key);
        } else if (value.isJsonArray()) {
            value.getAsJsonArray().forEach(line -> lines.add(line.getAsString()));
        } else {
            lines.add(value.getAsString());
        }
        if (lines.isEmpty()) {
            lines.add("");
        }
        return lines;
    }

    private static JsonElement messageValue(JsonObject root, String key) {
        JsonObject messages = root.getAsJsonObject("messages");
        return messages == null ? null : messages.get(key);
    }

    private JsonElement get(String key) {
        JsonElement value = config.get(key);
        return value != null && value.isJsonPrimitive() ? value : defaults.get(key);
    }

    private boolean bool(String key) {
        return get(key).getAsBoolean();
    }
}
