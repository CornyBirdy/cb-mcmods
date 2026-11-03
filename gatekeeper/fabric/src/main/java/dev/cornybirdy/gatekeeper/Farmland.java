package dev.cornybirdy.gatekeeper;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;

/**
 * Farmland that survives being trampled a few times. Vanilla turns it to dirt the first time something heavy lands
 * on it; with {@code farmland-trample-hits} above 1 it takes that many tramples first.
 */
final class Farmland {

    static final String HITS = "farmland-trample-hits";

    /** Tramples are forgotten after this many ticks without another one, so the counts can't pile up forever. */
    private static final long FORGET_AFTER_TICKS = 6000;
    private static final int PRUNE_ABOVE = 2048;

    private record Spot(ResourceKey<Level> dimension, long pos) {
    }

    private record Hits(int count, long lastTick) {
    }

    private static final Map<Spot, Hits> HIT_COUNTS = new HashMap<>();

    private Farmland() {
    }

    /** Called when something would trample the farmland at {@code pos}. True lets it turn to dirt, false counts the hit and spares it. */
    static boolean shouldTrample(Level level, BlockPos pos) {
        int needed = Gatekeeper.settings().integer(HITS);
        if (needed <= 1) {
            return true;
        }
        long now = level.getGameTime();
        Spot spot = new Spot(level.dimension(), pos.asLong());
        Hits previous = HIT_COUNTS.get(spot);
        int count = previous == null || now - previous.lastTick() > FORGET_AFTER_TICKS ? 1 : previous.count() + 1;
        if (count >= needed) {
            HIT_COUNTS.remove(spot);
            return true;
        }
        HIT_COUNTS.put(spot, new Hits(count, now));
        if (HIT_COUNTS.size() > PRUNE_ABOVE) {
            HIT_COUNTS.values().removeIf(hits -> now - hits.lastTick() > FORGET_AFTER_TICKS);
        }
        return false;
    }
}
