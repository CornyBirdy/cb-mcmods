package dev.cornybirdy.endlock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns ender chests and the End on or off.
 * <ul>
 *     <li>Ender chests off: they can't be opened (also not through other plugins), placed or crafted.</li>
 *     <li>The End off: end portals outside the End go dark (eyes can still be put in the frames) and light up
 *     again when it's turned back on. Anyone in the End is sent to the overworld spawn.</li>
 * </ul>
 */
public final class EndLock extends JavaPlugin {

    static final String ADMIN = "endlock.admin";
    static final String BYPASS_ENDER_CHESTS = "endlock.bypass.enderchests";
    static final String BYPASS_END = "endlock.bypass.end";

    private static final long WARNING_COOLDOWN_MILLIS = 3000;

    private final Map<UUID, Long> lastWarning = new ConcurrentHashMap<>();
    private Portals portals;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        portals = new Portals(this);
        getServer().getPluginManager().registerEvents(new LockListener(this, portals), this);
        registerCommand("endlock", new EndLockCommand(this));
        applyEndState(false);
    }

    boolean isEnabled(Feature feature) {
        return getConfig().getBoolean(feature.configKey, true);
    }

    /** Turns a feature on or off and saves it to config.yml, so it stays that way after a restart. */
    void setEnabled(Feature feature, boolean enabled) {
        getConfig().set(feature.configKey, enabled);
        saveConfig();
        if (feature == Feature.END) {
            applyEndState(true);
        }
    }

    void reload() {
        reloadConfig();
        applyEndState(false);
    }

    /** Closes or reopens the portals and sends players out of the End, to match the current setting. */
    private void applyEndState(boolean announce) {
        if (isEnabled(Feature.END)) {
            portals.reopen(announce);
        } else {
            portals.closeAll();
            sendPlayersOutOfEnd();
        }
    }

    boolean enderChestsBanned(Player player) {
        return !isEnabled(Feature.ENDER_CHESTS) && !player.hasPermission(BYPASS_ENDER_CHESTS);
    }

    boolean endBanned(Player player) {
        return !isEnabled(Feature.END) && !player.hasPermission(BYPASS_END);
    }

    void sendPlayersOutOfEnd() {
        for (Player player : getServer().getOnlinePlayers()) {
            if (player.getWorld().getEnvironment() == World.Environment.THE_END && endBanned(player)) {
                sendToSpawn(player);
            }
        }
    }

    void sendToSpawn(Player player) {
        Location spawn = getServer().getWorlds().getFirst().getSpawnLocation();
        player.teleportAsync(spawn);
        player.sendMessage(message("sent-out-of-end"));
        getLogger().info("Sent " + player.getName() + " out of the End because it is disabled");
    }

    /** Tells a player why something didn't work, at most once every few seconds (portals ask every tick). */
    void warn(Player player, String messageKey) {
        long now = System.currentTimeMillis();
        Long last = lastWarning.get(player.getUniqueId());
        if (last == null || now - last >= WARNING_COOLDOWN_MILLIS) {
            lastWarning.put(player.getUniqueId(), now);
            player.sendMessage(message(messageKey));
        }
    }

    void forget(Player player) {
        lastWarning.remove(player.getUniqueId());
    }

    /** Formats a MiniMessage string from the {@code messages} section of the config. */
    Component message(String key) {
        return MiniMessage.miniMessage().deserialize(getConfig().getString("messages." + key, key));
    }

    /** Same as {@link #message(String)}, with {@code <state>} replaced by the enabled or disabled message. */
    Component message(String key, boolean state) {
        String stateText = getConfig().getString("messages." + (state ? "enabled" : "disabled"), state ? "enabled" : "disabled");
        return MiniMessage.miniMessage().deserialize(getConfig().getString("messages." + key, key),
                Placeholder.parsed("state", stateText));
    }
}
