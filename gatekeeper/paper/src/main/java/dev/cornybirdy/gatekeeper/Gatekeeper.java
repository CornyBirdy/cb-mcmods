package dev.cornybirdy.gatekeeper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns ender chests, the End, the Nether, netherite armor and netherite tools on or off while the server runs.
 * <ul>
 *     <li>Ender chests off: they can't be opened (also not through other plugins), placed or crafted.</li>
 *     <li>The End off: end portals outside the End go dark (eyes can still be put in the frames) and light up
 *     again when it's turned back on. Anyone in the End is sent to the overworld spawn.</li>
 *     <li>The Nether off: the same for nether portals outside the Nether (frames can still be lit) and anyone in
 *     the Nether.</li>
 *     <li>Netherite armor or tools off: they can't be made at a smithing table. Armor can't be worn (anyone wearing
 *     it has it moved into their inventory), tools can't mine, hit or be used. Nothing is deleted.</li>
 * </ul>
 * Also opt-in PvP with {@code /pvp}, see {@link Pvp}.
 */
public final class Gatekeeper extends JavaPlugin {

    static final String ADMIN = "gatekeeper.admin";

    private static final long WARNING_COOLDOWN_MILLIS = 3000;
    private static final long ARMOR_CHECK_TICKS = 10;
    private static final EquipmentSlot[] ARMOR_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private final Map<UUID, Long> lastWarning = new ConcurrentHashMap<>();
    private EndPortals endPortals;
    private NetherPortals netherPortals;
    private Pvp pvp;

    @Override
    public void onEnable() {
        migrateFromEndLock();
        saveDefaultConfig();
        endPortals = new EndPortals(this);
        netherPortals = new NetherPortals(this);
        getServer().getPluginManager().registerEvents(new LockListener(this, endPortals, netherPortals), this);
        pvp = new Pvp(this);
        getServer().getPluginManager().registerEvents(pvp, this);
        registerCommand("gatekeeper", List.of("gk"), new GatekeeperCommand(this));
        registerCommand("pvp", new PvpCommand(this, pvp));
        applyState(Feature.END, false);
        applyState(Feature.NETHER, false);
        // Catches armor put on any way at all: inventory clicks, dispensers, other plugins.
        getServer().getScheduler().runTaskTimer(this, () -> {
            if (!isEnabled(Feature.NETHERITE_ARMOR)) {
                getServer().getOnlinePlayers().forEach(this::takeOffBannedArmor);
            }
        }, ARMOR_CHECK_TICKS, ARMOR_CHECK_TICKS);
    }

    Pvp pvp() {
        return pvp;
    }

    boolean isEnabled(Feature feature) {
        return getConfig().getBoolean(feature.configKey, true);
    }

    /** Turns a feature on or off and saves it to config.yml, so it stays that way after a restart. */
    void setEnabled(Feature feature, boolean enabled) {
        getConfig().set(feature.configKey, enabled);
        saveConfig();
        applyState(feature, true);
    }

    void reload() {
        reloadConfig();
        applyState(Feature.END, false);
        applyState(Feature.NETHER, false);
    }

    /** Closes or reopens a dimension's portals and sends players out of it, to match the current setting. */
    private void applyState(Feature feature, boolean announce) {
        boolean enabled = isEnabled(feature);
        if (feature == Feature.END) {
            if (enabled) {
                endPortals.reopen(announce);
            } else {
                endPortals.closeAll();
            }
        } else if (feature == Feature.NETHER) {
            if (enabled) {
                netherPortals.reopen();
            } else {
                netherPortals.closeAll();
            }
        }
        if (!enabled) {
            sendPlayersOut();
        }
    }

    /** True if the feature is off and the player doesn't have {@code gatekeeper.bypass.<command>}. */
    boolean banned(Player player, Feature feature) {
        return !isEnabled(feature) && !player.hasPermission("gatekeeper.bypass." + feature.command);
    }

    /** Moves banned netherite armor out of the armor slots into the inventory, or drops it if there's no room. */
    void takeOffBannedArmor(Player player) {
        if (!banned(player, Feature.NETHERITE_ARMOR)) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        boolean tookOff = false;
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack worn = inventory.getItem(slot);
            if (Netherite.isArmor(worn)) {
                inventory.setItem(slot, null);
                for (ItemStack left : inventory.addItem(worn).values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), left);
                }
                tookOff = true;
            }
        }
        if (tookOff) {
            warn(player, "netherite-armor-blocked");
        }
    }

    void sendPlayersOut() {
        for (Player player : getServer().getOnlinePlayers()) {
            sendOutIfClosed(player);
        }
    }

    /** Sends the player to the overworld spawn if they're in the End or the Nether while it's off. */
    void sendOutIfClosed(Player player) {
        World.Environment environment = player.getWorld().getEnvironment();
        if (environment == World.Environment.THE_END && banned(player, Feature.END)) {
            sendToSpawn(player, "sent-out-of-end", "the End");
        } else if (environment == World.Environment.NETHER && banned(player, Feature.NETHER)) {
            sendToSpawn(player, "sent-out-of-nether", "the Nether");
        }
    }

    private void sendToSpawn(Player player, String messageKey, String dimensionName) {
        Location spawn = getServer().getWorlds().getFirst().getSpawnLocation();
        player.teleportAsync(spawn);
        player.sendMessage(message(messageKey));
        getLogger().info("Sent " + player.getName() + " out of " + dimensionName + " because it is disabled");
    }

    /** Gatekeeper used to be called EndLock. Its config and saved portals are copied over on first start. */
    private void migrateFromEndLock() {
        File old = new File(getDataFolder().getParentFile(), "EndLock");
        if (!old.isDirectory() || getDataFolder().exists()) {
            return;
        }
        try {
            Files.createDirectories(getDataFolder().toPath());
            copy(new File(old, "config.yml"), new File(getDataFolder(), "config.yml"));
            copy(new File(old, "closed-portals.yml"), new File(getDataFolder(), "closed-end-portals.yml"));
            getLogger().info("Copied the settings from plugins/EndLock (EndLock is now called Gatekeeper). "
                    + "You can delete EndLock's jar and folder.");
        } catch (IOException e) {
            getLogger().severe("Could not copy the settings from plugins/EndLock: " + e.getMessage());
        }
    }

    private static void copy(File from, File to) throws IOException {
        if (from.exists()) {
            Files.copy(from.toPath(), to.toPath());
        }
    }

    /** Tells a player why something didn't work, at most once every few seconds (portals ask every tick). */
    void warn(Player player, String messageKey) {
        warn(player, message(messageKey));
    }

    void warn(Player player, Component message) {
        long now = System.currentTimeMillis();
        Long last = lastWarning.get(player.getUniqueId());
        if (last == null || now - last >= WARNING_COOLDOWN_MILLIS) {
            lastWarning.put(player.getUniqueId(), now);
            player.sendMessage(message);
        }
    }

    void forget(Player player) {
        lastWarning.remove(player.getUniqueId());
    }

    /** Formats a MiniMessage string from the {@code messages} section of the config, with optional placeholders. */
    Component message(String key, TagResolver... placeholders) {
        return MiniMessage.miniMessage().deserialize(raw(key), placeholders);
    }

    /** Same as {@link #message(String, TagResolver...)}, with {@code <state>} replaced by the enabled or disabled message. */
    Component message(String key, boolean state, TagResolver... placeholders) {
        return MiniMessage.miniMessage().deserialize(raw(key), TagResolver.resolver(
                Placeholder.parsed("state", raw(state ? "enabled" : "disabled")), TagResolver.resolver(placeholders)));
    }

    /** A message from config.yml, or the bundled default when an older config file doesn't have it yet. */
    private String raw(String key) {
        String message = getConfig().getString("messages." + key);
        return message != null ? message : key;
    }
}
