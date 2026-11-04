package dev.cornybirdy.estate.core;

import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Decides what a newly placed block means for its builder's claims: nothing, grow a claim, make a new claim, or show
 * the build tip. The caller carries out the plan (through {@link ClaimManager}, which still checks budget, buffers and
 * other players' blocks) and tells the player what happened.
 */
public final class AutoClaimPlanner {

    public sealed interface Plan permits Nothing, Grow, Create, Prompt {
    }

    public enum Nothing implements Plan {
        INSTANCE
    }

    /** Grow an own claim to this area and heights. {@code verticalOnly} when only the top or bottom moves. */
    public record Grow(Claim claim, Region region, int minY, int maxY, boolean verticalOnly) implements Plan {
    }

    /** Make a new claim around a build of {@code blocks} blocks. */
    public record Create(Region region, int minY, int maxY, int blocks) implements Plan {
    }

    /** The player has auto-claim off and has just built something worth protecting. */
    public record Prompt(int blocks) implements Plan {
    }

    private AutoClaimPlanner() {
    }

    /**
     * @param countsBlock whether a block id counts towards a build (torches, rails and the like don't)
     */
    public static Plan plan(ClaimManager manager, PlacementIndex placements, String world, UUID player,
                            int x, int y, int z, Predicate<String> countsBlock) {
        EstateConfig config = manager.config();
        int padding = config.verticalPadding;
        int margin = config.autoClaimMargin;
        int radius = config.autoClaimClusterRadius;
        if (!config.isClaimable(world) || manager.claimAt(world, x, y, z) != null) {
            return Nothing.INSTANCE;
        }
        boolean auto = manager.autoClaim(player);

        // Building above or below an own claim: move its top or bottom.
        Claim column = null;
        for (Claim claim : manager.claimsInColumn(world, x, z)) {
            if (claim.owner().equals(player) && (column == null || verticalGap(claim, y) < verticalGap(column, y))) {
                column = claim;
            }
        }
        if (column != null && (auto || config.autoExtendVertical)) {
            return new Grow(column, column.region(), Math.min(column.minY(), y - padding),
                    Math.max(column.maxY(), y + padding), true);
        }

        if (auto) {
            Claim nearest = null;
            int best = Integer.MAX_VALUE;
            for (Claim claim : manager.claimsNear(world, x, z, radius)) {
                if (!claim.owner().equals(player) || verticalGap(claim, y) > radius) {
                    continue;
                }
                int distance = claim.region().distance(x, z);
                if (distance <= radius && distance < best) {
                    best = distance;
                    nearest = claim;
                }
            }
            if (nearest != null) {
                // The block plus a margin around it, stretched to reach the claim so it stays in one piece.
                int[] edge = nearest.region().nearest(x, z);
                Region.Box joined = Region.Box.of(Math.min(x - margin, edge[0]), Math.min(z - margin, edge[1]),
                        Math.max(x + margin, edge[0]), Math.max(z + margin, edge[1]));
                Region grown = nearest.region().union(Region.of(joined));
                return new Grow(nearest, grown, Math.min(nearest.minY(), y - padding),
                        Math.max(nearest.maxY(), y + padding), false);
            }
        }

        // Helping a friend build next to their claim: that's their build, not a new one.
        for (Claim claim : manager.claimsNear(world, x, z, radius)) {
            if (!claim.owner().equals(player) && manager.has(claim, player, Permission.BUILD)) {
                return Nothing.INSTANCE;
            }
        }

        List<PlacementIndex.Hit> hits = placements.nearby(world, x, y, z, radius, player);
        hits.removeIf(hit -> !countsBlock.test(hit.block()) || manager.claimAt(world, hit.x(), hit.y(), hit.z()) != null);
        if (hits.size() < config.autoClaimThreshold) {
            return Nothing.INSTANCE;
        }
        if (!auto) {
            PlayerRecord record = manager.player(player);
            return config.buildPrompt && (record == null || !record.promptDone)
                    ? new Prompt(hits.size()) : Nothing.INSTANCE;
        }
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (PlacementIndex.Hit hit : hits) {
            minX = Math.min(minX, hit.x());
            minZ = Math.min(minZ, hit.z());
            maxX = Math.max(maxX, hit.x());
            maxZ = Math.max(maxZ, hit.z());
            minY = Math.min(minY, hit.y());
            maxY = Math.max(maxY, hit.y());
        }
        // A box around the whole build, with a margin of land around it.
        Region region = Region.box(minX - margin, minZ - margin, maxX + margin, maxZ + margin);
        return new Create(region, minY - padding, maxY + padding, hits.size());
    }

    /** What an auto-made claim shrinks to after part of its build was removed. */
    public record Fit(Region region, int minY, int maxY) {
    }

    /**
     * Fits an auto-made claim to what's left of its build: the margin around each remaining block of the owner (or of
     * players trusted to build there), never more than the claim already has.
     *
     * @return the smaller claim, or null if nothing of the build is left
     */
    public static Fit refit(ClaimManager manager, PlacementIndex placements, Claim claim, Predicate<String> countsBlock) {
        EstateConfig config = manager.config();
        int margin = config.autoClaimMargin;
        List<PlacementIndex.Hit> hits = placements.within(claim.world(), claim.region(), claim.minY(), claim.maxY(),
                id -> id.equals(claim.owner()) || manager.has(claim, id, Permission.BUILD));
        hits.removeIf(hit -> !countsBlock.test(hit.block()));
        if (hits.isEmpty()) {
            return null;
        }
        List<Region.Box> boxes = new java.util.ArrayList<>(hits.size());
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (PlacementIndex.Hit hit : hits) {
            boxes.add(Region.Box.of(hit.x() - margin, hit.z() - margin, hit.x() + margin, hit.z() + margin));
            minY = Math.min(minY, hit.y());
            maxY = Math.max(maxY, hit.y());
        }
        Region region = Region.of(boxes).intersect(claim.region());
        return new Fit(region, Math.max(claim.minY(), minY - config.verticalPadding),
                Math.min(claim.maxY(), maxY + config.verticalPadding));
    }

    private static int verticalGap(Claim claim, int y) {
        return y < claim.minY() ? claim.minY() - y : Math.max(0, y - claim.maxY());
    }
}
