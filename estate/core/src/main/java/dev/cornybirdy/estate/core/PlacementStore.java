package dev.cornybirdy.estate.core;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Where placed-block records are kept. {@link PlacementIndex} caches them per chunk. */
public interface PlacementStore {

    /** Who placed a block, which block it was, and when (epoch seconds). */
    record Placed(UUID player, String block, long time) {
    }

    /** Everything recorded in one chunk, keyed by {@link PlacementIndex#pack packed position}. Blocks until loaded. */
    Map<Long, Placed> loadChunk(String world, long chunk);

    /** Records a block. May be written a moment later, but before any later load of the same chunk. */
    void put(String world, long pos, long chunk, Placed placed);

    void remove(String world, long pos, long chunk);

    /** Keeps everything in memory; for tests. */
    final class Memory implements PlacementStore {
        private final Map<String, Map<Long, Map<Long, Placed>>> data = new HashMap<>();

        @Override
        public Map<Long, Placed> loadChunk(String world, long chunk) {
            Map<Long, Placed> stored = data.getOrDefault(world, Map.of()).get(chunk);
            return stored == null ? new HashMap<>() : new HashMap<>(stored);
        }

        @Override
        public void put(String world, long pos, long chunk, Placed placed) {
            data.computeIfAbsent(world, w -> new HashMap<>()).computeIfAbsent(chunk, c -> new HashMap<>()).put(pos, placed);
        }

        @Override
        public void remove(String world, long pos, long chunk) {
            Map<Long, Placed> stored = data.getOrDefault(world, Map.of()).get(chunk);
            if (stored != null) {
                stored.remove(pos);
            }
        }
    }
}
