package dev.cornybirdy.estate.core;

import java.util.Collection;
import java.util.UUID;
import java.util.function.Predicate;

/** Answers whether other players have built somewhere, so nobody can claim over someone else's build. */
@FunctionalInterface
public interface PlacementOracle {

    PlacementOracle NONE = (world, cells, minY, maxY, counts) -> null;

    /**
     * @param counts whose blocks count; blocks of everyone else are ignored
     * @return a player whose placed block is in the area, or null if there's none
     */
    UUID findBuilder(String world, Collection<Long> cells, int minY, int maxY, Predicate<UUID> counts);
}
