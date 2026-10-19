package dev.cornybirdy.gatekeeper;

/** Something {@code /gatekeeper} can turn on or off. */
enum Feature {
    ENDER_CHESTS("enderchests", "ender-chests-enabled", "ender-chests-state", "announce-ender-chests", false),
    END("end", "end-enabled", "end-state", "announce-end", true),
    NETHER("nether", "nether-enabled", "nether-state", "announce-nether", true);

    /** The {@code /gatekeeper <command>} that switches it. */
    final String command;
    final String configKey;
    final String stateMessage;
    /** Both the message key and the config option that turns the announcement on or off. */
    final String announceMessage;
    final boolean announceByDefault;

    Feature(String command, String configKey, String stateMessage, String announceMessage, boolean announceByDefault) {
        this.command = command;
        this.configKey = configKey;
        this.stateMessage = stateMessage;
        this.announceMessage = announceMessage;
        this.announceByDefault = announceByDefault;
    }
}
