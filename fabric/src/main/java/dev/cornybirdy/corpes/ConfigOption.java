package dev.cornybirdy.corpes;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** A top-level option in config.yml that {@code /corpse config} can read and change. Messages are edited in the file. */
record ConfigOption(String key, Kind kind, double min) {

    enum Kind { BOOLEAN, INT, DECIMAL, TEXT }

    static final List<ConfigOption> ALL = List.of(
            new ConfigOption("allow-anyone-loot", Kind.BOOLEAN, 0),
            new ConfigOption("store-xp", Kind.BOOLEAN, 0),
            new ConfigOption("skeleton-after-minutes", Kind.INT, 0),
            new ConfigOption("history-size", Kind.INT, 1),
            new ConfigOption("show-name", Kind.BOOLEAN, 0),
            new ConfigOption("label-view-distance", Kind.INT, 8),
            new ConfigOption("label-see-through", Kind.BOOLEAN, 0),
            new ConfigOption("hitbox-width", Kind.DECIMAL, 0.2),
            new ConfigOption("hitbox-height", Kind.DECIMAL, 0.1),
            new ConfigOption("float-on-lava", Kind.BOOLEAN, 0),
            new ConfigOption("fall-to-ground", Kind.BOOLEAN, 0),
            new ConfigOption("date-format", Kind.TEXT, 0)
    );

    static ConfigOption find(String key) {
        for (ConfigOption option : ALL) {
            if (option.key.equalsIgnoreCase(key)) {
                return option;
            }
        }
        return null;
    }

    /**
     * Turns what the admin typed into the value to store.
     *
     * @throws IllegalArgumentException with a short reason if the text isn't valid for this option
     */
    Object parse(String raw) {
        switch (kind) {
            case BOOLEAN -> {
                String lower = raw.toLowerCase(Locale.ROOT);
                if (!lower.equals("true") && !lower.equals("false")) {
                    throw new IllegalArgumentException("use true or false");
                }
                return Boolean.parseBoolean(lower);
            }
            case INT -> {
                int value;
                try {
                    value = Integer.parseInt(raw);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("not a whole number");
                }
                requireAtLeastMin(value);
                return value;
            }
            case DECIMAL -> {
                double value;
                try {
                    value = Double.parseDouble(raw);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("not a number");
                }
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException("not a number");
                }
                requireAtLeastMin(value);
                return value;
            }
            default -> {
                try {
                    DateTimeFormatter.ofPattern(raw, Locale.ENGLISH);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("not a valid date pattern");
                }
                return raw;
            }
        }
    }

    private void requireAtLeastMin(double value) {
        if (value < min) {
            throw new IllegalArgumentException("must be at least " + (min == Math.rint(min) ? String.valueOf((long) min) : String.valueOf(min)));
        }
    }
}
