package dev.cornybirdy.papercorpes;

import org.bukkit.configuration.file.FileConfiguration;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

record Settings(
        boolean allowAnyoneLoot,
        boolean storeXp,
        int skeletonAfterMinutes,
        int historySize,
        boolean showName,
        float hitboxWidth,
        float hitboxHeight,
        DateTimeFormatter dateFormat
) {

    static Settings load(FileConfiguration config) {
        DateTimeFormatter format;
        try {
            format = DateTimeFormatter.ofPattern(config.getString("date-format", "dd MMM HH:mm"), Locale.ENGLISH);
        } catch (IllegalArgumentException e) {
            format = DateTimeFormatter.ofPattern("dd MMM HH:mm", Locale.ENGLISH);
        }
        return new Settings(
                config.getBoolean("allow-anyone-loot", true),
                config.getBoolean("store-xp", false),
                Math.max(0, config.getInt("skeleton-after-minutes", 60)),
                Math.max(1, config.getInt("history-size", 10)),
                config.getBoolean("show-name", true),
                (float) Math.max(0.2, config.getDouble("hitbox-width", 2.0)),
                (float) Math.max(0.1, config.getDouble("hitbox-height", 0.6)),
                format.withZone(ZoneId.systemDefault())
        );
    }

    String formatDate(long epochMillis) {
        return dateFormat.format(java.time.Instant.ofEpochMilli(epochMillis));
    }
}
