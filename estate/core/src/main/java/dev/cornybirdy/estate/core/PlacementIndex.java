package dev.cornybirdy.estate.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Who placed which block, cached per 16x16 chunk column on top of a {@link PlacementStore}. Used for the "can't
 * claim over someone else's build" rule, auto-claim clusters and admin lookups. Server thread only.
 */
public final class PlacementIndex {

    /** Checks the world: is this block still there? Records of blocks that are gone get dropped. */
    @FunctionalInterface
    public interface Verifier {
        /** @return true if it's still there, false if it's gone, null if the chunk isn't loaded */
        Boolean stillThere(String world, int x, int y, int z, String block);
    }

    private record ChunkRef(String world, long chunk) {
    }

    /** A recorded block. */
    public record Hit(int x, int y, int z, String block) {
    }

    private static final int MAX_CACHED_CHUNKS = 4096;

    private final PlacementStore store;
    private final LongSupplier clock;
    private final IntSupplier retentionDays;
    private final Map<ChunkRef, Map<Long, PlacementStore.Placed>> cache =
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<ChunkRef, Map<Long, PlacementStore.Placed>> eldest) {
                    return size() > MAX_CACHED_CHUNKS;
                }
            };

    public PlacementIndex(PlacementStore store, LongSupplier clock, IntSupplier retentionDays) {
        this.store = store;
        this.clock = clock;
        this.retentionDays = retentionDays;
    }

    // Positions are packed like vanilla's BlockPos: 26 bits x, 26 bits z, 12 bits y.

    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }

    public static int x(long pos) {
        return (int) (pos >> 38);
    }

    public static int y(long pos) {
        return (int) (pos << 52 >> 52);
    }

    public static int z(long pos) {
        return (int) (pos << 26 >> 38);
    }

    public static long chunk(int blockX, int blockZ) {
        return Cells.key(blockX >> 4, blockZ >> 4);
    }

    public void placed(String world, int x, int y, int z, UUID player, String block) {
        long pos = pack(x, y, z);
        long chunk = chunk(x, z);
        PlacementStore.Placed placed = new PlacementStore.Placed(player, block, clock.getAsLong());
        chunkMap(world, chunk).put(pos, placed);
        store.put(world, pos, chunk, placed);
    }

    public void removed(String world, int x, int y, int z) {
        long pos = pack(x, y, z);
        long chunk = chunk(x, z);
        if (chunkMap(world, chunk).remove(pos) != null) {
            store.remove(world, pos, chunk);
        }
    }

    /** Who placed the block here, or null if nobody did (or the record is too old). */
    public PlacementStore.Placed at(String world, int x, int y, int z) {
        PlacementStore.Placed placed = chunkMap(world, chunk(x, z)).get(pack(x, y, z));
        return placed == null || expired(placed) ? null : placed;
    }

    /** A player's recorded blocks within {@code radius} blocks (straight-line) of a spot. */
    public List<Hit> nearby(String world, int x, int y, int z, int radius, UUID player) {
        List<Hit> found = new ArrayList<>();
        long radiusSq = (long) radius * radius;
        for (int cx = (x - radius) >> 4; cx <= (x + radius) >> 4; cx++) {
            for (int cz = (z - radius) >> 4; cz <= (z + radius) >> 4; cz++) {
                for (Map.Entry<Long, PlacementStore.Placed> entry : chunkMap(world, Cells.key(cx, cz)).entrySet()) {
                    PlacementStore.Placed placed = entry.getValue();
                    if (!placed.player().equals(player) || expired(placed)) {
                        continue;
                    }
                    long pos = entry.getKey();
                    long dx = x(pos) - x;
                    long dy = y(pos) - y;
                    long dz = z(pos) - z;
                    if (dx * dx + dy * dy + dz * dz <= radiusSq) {
                        found.add(new Hit(x(pos), y(pos), z(pos), placed.block()));
                    }
                }
            }
        }
        return found;
    }

    /**
     * Finds someone whose block is inside this area and heights. Records of blocks that are no longer there are
     * dropped along the way.
     *
     * @param counts whose blocks matter
     * @return a player who built there, or null
     */
    public UUID findBuilder(String world, Region region, int minY, int maxY, Predicate<UUID> counts, Verifier verifier) {
        Set<Long> chunks = new LinkedHashSet<>();
        for (Region.Box box : region.boxes()) {
            for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
                for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                    chunks.add(Cells.key(cx, cz));
                }
            }
        }
        for (long chunk : chunks) {
            Iterator<Map.Entry<Long, PlacementStore.Placed>> it = chunkMap(world, chunk).entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Long, PlacementStore.Placed> entry = it.next();
                long pos = entry.getKey();
                PlacementStore.Placed placed = entry.getValue();
                int y = y(pos);
                if (y < minY || y > maxY || expired(placed) || !counts.test(placed.player())
                        || !region.contains(x(pos), z(pos))) {
                    continue;
                }
                Boolean there = verifier.stillThere(world, x(pos), y, z(pos), placed.block());
                if (Boolean.FALSE.equals(there)) {
                    it.remove();
                    store.remove(world, pos, chunk);
                    continue;
                }
                return placed.player();
            }
        }
        return null;
    }

    /** Every recorded block inside an area and heights whose builder passes {@code whose}. */
    public List<Hit> within(String world, Region region, int minY, int maxY, Predicate<UUID> whose) {
        Set<Long> chunks = new LinkedHashSet<>();
        for (Region.Box box : region.boxes()) {
            for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
                for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                    chunks.add(Cells.key(cx, cz));
                }
            }
        }
        List<Hit> found = new ArrayList<>();
        for (long chunk : chunks) {
            for (Map.Entry<Long, PlacementStore.Placed> entry : chunkMap(world, chunk).entrySet()) {
                long pos = entry.getKey();
                PlacementStore.Placed placed = entry.getValue();
                int y = y(pos);
                if (y >= minY && y <= maxY && !expired(placed) && region.contains(x(pos), z(pos))
                        && whose.test(placed.player())) {
                    found.add(new Hit(x(pos), y, z(pos), placed.block()));
                }
            }
        }
        return found;
    }

    /** Drops a chunk from the cache, e.g. when it unloads. Nothing is lost; it's reloaded when needed. */
    public void forgetChunk(String world, int chunkX, int chunkZ) {
        cache.remove(new ChunkRef(world, Cells.key(chunkX, chunkZ)));
    }

    public void clearCache() {
        cache.clear();
    }

    private boolean expired(PlacementStore.Placed placed) {
        int days = retentionDays.getAsInt();
        return days > 0 && clock.getAsLong() - placed.time() > days * 86_400L;
    }

    private Map<Long, PlacementStore.Placed> chunkMap(String world, long chunk) {
        ChunkRef ref = new ChunkRef(world, chunk);
        Map<Long, PlacementStore.Placed> map = cache.get(ref);
        if (map == null) {
            map = store.loadChunk(world, chunk);
            cache.put(ref, map);
        }
        return map;
    }
}
