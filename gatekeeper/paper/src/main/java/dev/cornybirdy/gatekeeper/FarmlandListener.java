package dev.cornybirdy.gatekeeper;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Farmland that survives being trampled a few times. Vanilla turns it to dirt the first time something heavy lands
 * on it; with {@code farmland-trample-hits} above 1 it takes that many tramples first.
 */
final class FarmlandListener implements Listener {

    static final String HITS = "farmland-trample-hits";

    /** Tramples are forgotten after this long without another one, so the counts can't pile up forever. */
    private static final long FORGET_AFTER_MILLIS = 5 * 60 * 1000L;
    private static final int PRUNE_ABOVE = 2048;

    private record Spot(UUID world, int x, int y, int z) {
    }

    private record Hits(int count, long lastMillis) {
    }

    private final Gatekeeper plugin;
    private final Map<Spot, Hits> hitCounts = new HashMap<>();

    FarmlandListener(Gatekeeper plugin) {
        this.plugin = plugin;
    }

    /** Fired when a player or mob would turn farmland into dirt by landing on it (other plugins' cancels are respected). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTrample(EntityChangeBlockEvent event) {
        if (event.getBlock().getType() != Material.FARMLAND || event.getTo() != Material.DIRT) {
            return;
        }
        int needed = plugin.getConfig().getInt(HITS, 3);
        if (needed <= 1) {
            return;
        }
        Location at = event.getBlock().getLocation();
        Spot spot = new Spot(at.getWorld().getUID(), at.getBlockX(), at.getBlockY(), at.getBlockZ());
        long now = System.currentTimeMillis();
        Hits previous = hitCounts.get(spot);
        int count = previous == null || now - previous.lastMillis() > FORGET_AFTER_MILLIS ? 1 : previous.count() + 1;
        if (count >= needed) {
            hitCounts.remove(spot);
            return;
        }
        hitCounts.put(spot, new Hits(count, now));
        if (hitCounts.size() > PRUNE_ABOVE) {
            hitCounts.values().removeIf(hits -> now - hits.lastMillis() > FORGET_AFTER_MILLIS);
        }
        event.setCancelled(true);
    }
}
