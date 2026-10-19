package dev.cornybirdy.endlock;

/** Something {@code /endlock} can turn on or off. */
enum Feature {
    ENDER_CHESTS("ender-chests-enabled", "ender-chests-state", "announce-ender-chests"),
    END("end-enabled", "end-state", "announce-end");

    final String configKey;
    final String stateMessage;
    final String announceMessage;

    Feature(String configKey, String stateMessage, String announceMessage) {
        this.configKey = configKey;
        this.stateMessage = stateMessage;
        this.announceMessage = announceMessage;
    }
}
