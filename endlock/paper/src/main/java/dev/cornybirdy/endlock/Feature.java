package dev.cornybirdy.endlock;

/** Something {@code /endlock} can turn on or off. */
enum Feature {
    ENDER_CHESTS("ender-chests-enabled", "ender-chests-state", "announce-ender-chests", false),
    END("end-enabled", "end-state", "announce-end", true);

    final String configKey;
    final String stateMessage;
    /** Both the message key and the config option that turns the announcement on or off. */
    final String announceMessage;
    final boolean announceByDefault;

    Feature(String configKey, String stateMessage, String announceMessage, boolean announceByDefault) {
        this.configKey = configKey;
        this.stateMessage = stateMessage;
        this.announceMessage = announceMessage;
        this.announceByDefault = announceByDefault;
    }
}
