package dev.cornybirdy.gatekeeper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Opt-in PvP: players can only hurt each other when both have turned it on with {@code /pvp on}. Changing it has a
 * cooldown, and after a hit both players are "in combat" for a while and can't turn it off.
 * <p>
 * Exceptions: two players who have both {@code /pvp allow}ed each other can fight even with PvP off, and an admin can
 * lock a player's PvP on or off ({@code /gatekeeper pvpforce}), which they then can't change. A player locked off can't
 * fight anyone, allowed or not.
 * <p>
 * Each player's choice is saved with their player data; players who never chose get {@code pvp-default}. Allow lists
 * and admin locks are in {@code pvp.yml}.
 */
final class Pvp implements Listener {

    static final String DEFAULT = "pvp-default";
    static final String COOLDOWN = "pvp-toggle-cooldown-seconds";
    static final String COMBAT = "pvp-combat-seconds";
    static final String COMBAT_LOG = "combat-log-kill";

    enum Outcome {
        CHANGED, UNCHANGED, COOLDOWN, IN_COMBAT, FORCED
    }

    /** What {@link #set} did, and for a cooldown or combat, how many seconds are left. */
    record Result(Outcome outcome, long secondsLeft) {
    }

    private final Gatekeeper plugin;
    private final NamespacedKey key;
    private final Map<UUID, Long> lastChange = new HashMap<>();
    private final Map<UUID, Long> combatUntil = new HashMap<>();
    private final File file;
    /** Set by an admin; overrides the player's own choice. */
    private final Map<UUID, Boolean> forced = new HashMap<>();
    /** Who each player has allowed to fight them even with PvP off. Only counts when it goes both ways. */
    private final Map<UUID, Set<UUID>> allowed = new HashMap<>();

    Pvp(Gatekeeper plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "pvp");
        this.file = new File(plugin.getDataFolder(), "pvp.yml");
        load();
    }

    boolean enabled(Player player) {
        Boolean lock = forced.get(player.getUniqueId());
        if (lock != null) {
            return lock;
        }
        Boolean choice = player.getPersistentDataContainer().get(key, PersistentDataType.BOOLEAN);
        return choice != null ? choice : plugin.getConfig().getBoolean(DEFAULT);
    }

    /** For players who may be offline: their lock, or their choice if they're online, or the default. */
    boolean enabled(OfflinePlayer player) {
        if (player.getPlayer() != null) {
            return enabled(player.getPlayer());
        }
        Boolean lock = forced.get(player.getUniqueId());
        return lock != null ? lock : plugin.getConfig().getBoolean(DEFAULT);
    }

    /** The admin lock on a player's PvP, or null if they choose for themselves. */
    Boolean forced(UUID player) {
        return forced.get(player);
    }

    /** @param on null removes the lock */
    void force(UUID player, Boolean on) {
        if (on == null) {
            forced.remove(player);
        } else {
            forced.put(player, on);
        }
        save();
    }

    /** True if both players have allowed each other. */
    boolean mutual(UUID a, UUID b) {
        return allowedBy(a).contains(b) && allowedBy(b).contains(a);
    }

    /** Adds or removes someone from a player's allow list. Returns false if nothing changed. */
    boolean setAllowed(UUID player, UUID other, boolean allow) {
        Set<UUID> list = allowed.computeIfAbsent(player, id -> new HashSet<>());
        boolean changed = allow ? list.add(other) : list.remove(other);
        if (list.isEmpty()) {
            allowed.remove(player);
        }
        if (changed) {
            save();
        }
        return changed;
    }

    /** Everyone this player has allowed. */
    Set<UUID> allowedBy(UUID player) {
        return Set.copyOf(allowed.getOrDefault(player, Set.of()));
    }

    /** Everyone who has allowed this player. */
    Set<UUID> allowing(UUID player) {
        Set<UUID> found = new HashSet<>();
        allowed.forEach((id, list) -> {
            if (list.contains(player)) {
                found.add(id);
            }
        });
        return found;
    }

    long combatSecondsLeft(UUID player) {
        Long until = combatUntil.get(player);
        return until == null ? 0 : seconds(until - System.currentTimeMillis());
    }

    /** Turns a player's PvP on or off, unless they changed it too recently or are in a fight (for turning it off). */
    Result set(Player player, boolean on) {
        UUID id = player.getUniqueId();
        if (forced.containsKey(id)) {
            return new Result(Outcome.FORCED, 0);
        }
        if (enabled(player) == on) {
            return new Result(Outcome.UNCHANGED, 0);
        }
        long now = System.currentTimeMillis();
        if (!on && inCombat(id)) {
            return new Result(Outcome.IN_COMBAT, seconds(combatUntil.get(id) - now));
        }
        long cooldown = plugin.getConfig().getInt(COOLDOWN) * 1000L;
        Long last = lastChange.get(id);
        if (last != null && now - last < cooldown) {
            return new Result(Outcome.COOLDOWN, seconds(last + cooldown - now));
        }
        player.getPersistentDataContainer().set(key, PersistentDataType.BOOLEAN, on);
        lastChange.put(id, now);
        return new Result(Outcome.CHANGED, 0);
    }

    boolean inCombat(UUID player) {
        Long until = combatUntil.get(player);
        return until != null && until > System.currentTimeMillis();
    }

    private static long seconds(long millis) {
        return Math.max(1, (millis + 999) / 1000);
    }

    /** The player behind some damage: the one hitting, shooting, lighting the TNT or owning the pet. */
    private static Player attacker(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player player) {
            return player;
        }
        if (damager instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Player player) {
            return player;
        }
        if (damager instanceof Tameable pet && pet.getOwner() instanceof Player player) {
            return player;
        }
        return null;
    }

    /**
     * Whether one player may hurt another: if both have PvP on, or they've allowed each other. Never if an admin
     * locked either of them off.
     */
    boolean mayHurt(Player attacker, Player victim) {
        if (attacker == victim) {
            return true;
        }
        if (Boolean.FALSE.equals(forced.get(attacker.getUniqueId())) || Boolean.FALSE.equals(forced.get(victim.getUniqueId()))) {
            return false;
        }
        return mutual(attacker.getUniqueId(), victim.getUniqueId()) || (enabled(attacker) && enabled(victim));
    }

    /** Players only hurt each other when both have PvP on, or have allowed each other. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Player attacker = attacker(event.getDamager());
        if (!(event.getEntity() instanceof Player victim) || attacker == null || mayHurt(attacker, victim)) {
            return;
        }
        event.setCancelled(true);
        if (Boolean.FALSE.equals(forced.get(attacker.getUniqueId()))) {
            plugin.warn(attacker, plugin.message("pvp-you-locked"));
        } else if (Boolean.FALSE.equals(forced.get(victim.getUniqueId()))) {
            plugin.warn(attacker, plugin.message("pvp-they-locked", Placeholder.unparsed("player", victim.getName())));
        } else if (!enabled(attacker)) {
            plugin.warn(attacker, plugin.message("pvp-you-off"));
        } else {
            plugin.warn(attacker, plugin.message("pvp-they-off", Placeholder.unparsed("player", victim.getName())));
        }
    }

    /** After a hit between players, both are in combat and can't turn PvP off for a while. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamaged(EntityDamageByEntityEvent event) {
        Player attacker = attacker(event.getDamager());
        if (!(event.getEntity() instanceof Player victim) || attacker == null || attacker == victim) {
            return;
        }
        int combatSeconds = plugin.getConfig().getInt(COMBAT);
        long until = System.currentTimeMillis() + combatSeconds * 1000L;
        for (Player player : List.of(attacker, victim)) {
            boolean fresh = !inCombat(player.getUniqueId());
            combatUntil.put(player.getUniqueId(), until);
            if (fresh && plugin.getConfig().getBoolean(COMBAT_LOG)) {
                player.sendMessage(plugin.message("combat-tagged", Placeholder.unparsed("seconds", String.valueOf(combatSeconds))));
            }
        }
    }

    /** With {@code combat-log-kill} on, leaving the game in the middle of a fight kills you. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (!inCombat(player.getUniqueId())) {
            return;
        }
        combatUntil.remove(player.getUniqueId());
        // Not when the whole server shuts down.
        if (!plugin.getConfig().getBoolean(COMBAT_LOG) || plugin.getServer().isStopping()) {
            return;
        }
        plugin.getLogger().info(player.getName() + " logged out during combat and was killed (combat-log-kill is on)");
        player.setHealth(0);
        Component announcement = plugin.message("combat-log-killed", Placeholder.unparsed("player", player.getName()));
        for (Player other : plugin.getServer().getOnlinePlayers()) {
            if (other != player) {
                other.sendMessage(announcement);
            }
        }
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection lockSection = yaml.getConfigurationSection("forced");
        if (lockSection != null) {
            for (String id : lockSection.getKeys(false)) {
                UUID uuid = uuid(id);
                if (uuid != null) {
                    forced.put(uuid, lockSection.getBoolean(id));
                }
            }
        }
        ConfigurationSection allowSection = yaml.getConfigurationSection("allowed");
        if (allowSection != null) {
            for (String id : allowSection.getKeys(false)) {
                UUID uuid = uuid(id);
                Set<UUID> list = new HashSet<>();
                for (String other : allowSection.getStringList(id)) {
                    UUID otherId = uuid(other);
                    if (otherId != null) {
                        list.add(otherId);
                    }
                }
                if (uuid != null && !list.isEmpty()) {
                    allowed.put(uuid, list);
                }
            }
        }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of("PvP allow lists and admin locks. Change them with /pvp allow and /gatekeeper pvpforce."));
        forced.forEach((id, on) -> yaml.set("forced." + id, on));
        allowed.forEach((id, list) -> yaml.set("allowed." + id, list.stream().map(UUID::toString).sorted().toList()));
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save " + file + ": " + e.getMessage());
        }
    }

    private UUID uuid(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Ignoring '" + text + "' in " + file);
            return null;
        }
    }
}
