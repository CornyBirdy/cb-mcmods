package dev.cornybirdy.estate.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

/** Works out the border lines of a claim (or of its buffer) so they can be drawn. */
public final class Outline {

    /** A straight border line on block edges, from (x1, z1) to (x2, z2). */
    public record Segment(int x1, int z1, int x2, int z2) {
        public int length() {
            return Math.abs(x2 - x1) + Math.abs(z2 - z1);
        }
    }

    private Outline() {
    }

    /**
     * The outline of a region, grown by {@code grow} blocks on every side (0 for the claim itself, the buffer
     * distance for its buffer). Touching boxes share one outline.
     */
    public static List<Segment> of(Region region, int grow) {
        Region area = region.grow(grow);
        if (area.isEmpty()) {
            return List.of();
        }
        // A grid cut only where boxes start or end, so it stays small whatever the size of the claim.
        TreeSet<Integer> xCuts = new TreeSet<>();
        TreeSet<Integer> zCuts = new TreeSet<>();
        for (Region.Box box : area.boxes()) {
            xCuts.add(box.minX());
            xCuts.add(box.maxX() + 1);
            zCuts.add(box.minZ());
            zCuts.add(box.maxZ() + 1);
        }
        int[] xs = xCuts.stream().mapToInt(Integer::intValue).toArray();
        int[] zs = zCuts.stream().mapToInt(Integer::intValue).toArray();
        int w = xs.length - 1;
        int d = zs.length - 1;
        boolean[] grid = new boolean[w * d];
        for (Region.Box box : area.boxes()) {
            int i0 = Arrays.binarySearch(xs, box.minX());
            int i1 = Arrays.binarySearch(xs, box.maxX() + 1);
            int j0 = Arrays.binarySearch(zs, box.minZ());
            int j1 = Arrays.binarySearch(zs, box.maxZ() + 1);
            for (int i = i0; i < i1; i++) {
                for (int j = j0; j < j1; j++) {
                    grid[j * w + i] = true;
                }
            }
        }
        List<Segment> segments = new ArrayList<>();
        // Lines along x, at every z cut: runs of grid cells whose covered state differs across the line.
        for (int j = 0; j <= d; j++) {
            int start = -1;
            for (int i = 0; i <= w; i++) {
                boolean edge = i < w && (cell(grid, w, d, i, j - 1) != cell(grid, w, d, i, j));
                if (edge && start < 0) {
                    start = i;
                } else if (!edge && start >= 0) {
                    segments.add(new Segment(xs[start], zs[j], xs[i], zs[j]));
                    start = -1;
                }
            }
        }
        // Lines along z, at every x cut.
        for (int i = 0; i <= w; i++) {
            int start = -1;
            for (int j = 0; j <= d; j++) {
                boolean edge = j < d && (cell(grid, w, d, i - 1, j) != cell(grid, w, d, i, j));
                if (edge && start < 0) {
                    start = j;
                } else if (!edge && start >= 0) {
                    segments.add(new Segment(xs[i], zs[start], xs[i], zs[j]));
                    start = -1;
                }
            }
        }
        return segments;
    }

    private static boolean cell(boolean[] grid, int w, int d, int i, int j) {
        return i >= 0 && j >= 0 && i < w && j < d && grid[j * w + i];
    }
}
