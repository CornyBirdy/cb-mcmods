package dev.cornybirdy.estate.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Works out the border lines of a claim (or of its buffer) so they can be drawn with particles. */
public final class Outline {

    /** A straight border line on block edges, from (x1, z1) to (x2, z2). */
    public record Segment(int x1, int z1, int x2, int z2) {
        public int length() {
            return Math.abs(x2 - x1) + Math.abs(z2 - z1);
        }
    }

    private static final long MAX_GRID = 4_000_000;

    private Outline() {
    }

    /**
     * The outline of the area covered by these cells, grown by {@code grow} blocks on every side (0 for the claim
     * itself, the buffer distance for its buffer). Touching cells share one outline.
     */
    public static List<Segment> of(Collection<Long> cells, int cellSize, int grow) {
        if (cells.isEmpty()) {
            return List.of();
        }
        int unit = grow == 0 ? cellSize : 1;
        int pad = grow == 0 ? 0 : grow;
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (long cell : cells) {
            minX = Math.min(minX, Cells.x(cell));
            minZ = Math.min(minZ, Cells.z(cell));
            maxX = Math.max(maxX, Cells.x(cell));
            maxZ = Math.max(maxZ, Cells.z(cell));
        }
        // Grid origin and size in units.
        int originX = minX * cellSize - pad;
        int originZ = minZ * cellSize - pad;
        long width = ((long) (maxX - minX + 1) * cellSize + 2L * pad) / unit;
        long depth = ((long) (maxZ - minZ + 1) * cellSize + 2L * pad) / unit;
        if (width * depth > MAX_GRID) {
            // Cells spread far apart: draw each cell on its own rather than allocate a huge grid.
            List<Segment> segments = new ArrayList<>();
            for (long cell : cells) {
                segments.addAll(of(List.of(cell), cellSize, grow));
            }
            return segments;
        }
        int w = (int) width;
        int d = (int) depth;
        boolean[] grid = new boolean[w * d];
        int cellUnits = cellSize / unit;
        for (long cell : cells) {
            int startX = (Cells.x(cell) * cellSize - originX - pad) / unit;
            int startZ = (Cells.z(cell) * cellSize - originZ - pad) / unit;
            int padUnits = pad / unit;
            for (int i = startX; i < startX + cellUnits + 2 * padUnits; i++) {
                for (int j = startZ; j < startZ + cellUnits + 2 * padUnits; j++) {
                    grid[j * w + i] = true;
                }
            }
        }
        // Edges between covered and uncovered units, keyed by line and side so they can be merged.
        Map<Long, TreeMap<Integer, Integer>> horizontal = new HashMap<>();
        Map<Long, TreeMap<Integer, Integer>> vertical = new HashMap<>();
        for (int j = 0; j < d; j++) {
            for (int i = 0; i < w; i++) {
                if (!grid[j * w + i]) {
                    continue;
                }
                if (j == 0 || !grid[(j - 1) * w + i]) {
                    addEdge(horizontal, j, 0, i);
                }
                if (j == d - 1 || !grid[(j + 1) * w + i]) {
                    addEdge(horizontal, j + 1, 1, i);
                }
                if (i == 0 || !grid[j * w + i - 1]) {
                    addEdge(vertical, i, 0, j);
                }
                if (i == w - 1 || !grid[j * w + i + 1]) {
                    addEdge(vertical, i + 1, 1, j);
                }
            }
        }
        List<Segment> segments = new ArrayList<>();
        for (Map.Entry<Long, TreeMap<Integer, Integer>> line : horizontal.entrySet()) {
            int z = originZ + (int) (line.getKey() >> 1) * unit;
            for (int[] run : runs(line.getValue())) {
                segments.add(new Segment(originX + run[0] * unit, z, originX + (run[1] + 1) * unit, z));
            }
        }
        for (Map.Entry<Long, TreeMap<Integer, Integer>> line : vertical.entrySet()) {
            int x = originX + (int) (line.getKey() >> 1) * unit;
            for (int[] run : runs(line.getValue())) {
                segments.add(new Segment(x, originZ + run[0] * unit, x, originZ + (run[1] + 1) * unit));
            }
        }
        return segments;
    }

    private static void addEdge(Map<Long, TreeMap<Integer, Integer>> lines, int line, int side, int position) {
        lines.computeIfAbsent(((long) line << 1) | side, k -> new TreeMap<>()).put(position, position);
    }

    /** Joins neighbouring unit edges into runs of [first, last]. */
    private static List<int[]> runs(TreeMap<Integer, Integer> positions) {
        List<int[]> runs = new ArrayList<>();
        int start = Integer.MIN_VALUE;
        int last = Integer.MIN_VALUE;
        for (int position : positions.keySet()) {
            if (start != Integer.MIN_VALUE && position == last + 1) {
                last = position;
                continue;
            }
            if (start != Integer.MIN_VALUE) {
                runs.add(new int[]{start, last});
            }
            start = position;
            last = position;
        }
        if (start != Integer.MIN_VALUE) {
            runs.add(new int[]{start, last});
        }
        return runs;
    }
}
