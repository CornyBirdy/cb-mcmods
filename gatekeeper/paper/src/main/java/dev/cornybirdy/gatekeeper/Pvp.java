package dev.cornybirdy.gatekeeper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.NamespacedKey;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Opt-in PvP: players can only hurt each other when both have turned it on with {@code /pvp on}. Changing it has a
 * cooldown, and after a hit both players are "in combat" for a while and can't turn it off. Each player's choice is
 * saved with their player data; players who never chose get {@code pvp-default}.
 */
final class Pvp implements Listener {

    static final String DEFAULT = "pvp-default";
    static final String COOLDOWN = "pvp-toggle-cooldown-seconds";
    static final String COMBAT = "pvp-combat-seconds";
    static final String COMBAT_LOG = "combat-log-kill";

    enum Outcome {
        CHANGED, UNCHANGED, COOLDOWN, IN_COMBAT
    }

    /** What {@link #set} did, and for a cooldown or combat, how many seconds are left. */
    record Result(Outcome outcome, long secondsLeft) {
    }

    private final Gatekeeper plugin;
    private final NamespacedKey key;
    private final Map<UUID, Long> lastChange = new HashMap<>();
    private final Map<UUID, Long> combatUntil = new HashMap<>();

    Pvp(Gatekeeper plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "pvp");
    }

    boolean enabled(Player player) {
        Boolean choice = player.getPersistentDataContainer().get(key, PersistentDataType.BOOLEAN);
        return choice != null ? choice : plugin.getConfig().getBoolean(DEFAULT);
    }

    /** Turns a player's PvP on or off, unless they changed it too recently or are in a fight (for turning it off). */
    Result set(Player player, boolean on) {
        UUID id = player.getUniqueId();
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

    /** Players only hurt each other when both have PvP on. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Player attacker = attacker(event.getDamager());
        if (!(event.getEntity() instanceof Player victim) || attacker == null || attacker == victim) {
            return;
        }
        if (enabled(attacker) && enabled(victim)) {
            return;
        }
        event.setCancelled(true);
        if (!enabled(attacker)) {
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
}
