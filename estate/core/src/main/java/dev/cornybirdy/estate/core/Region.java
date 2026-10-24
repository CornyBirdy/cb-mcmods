package dev.cornybirdy.estate.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/**
 * An area of block columns, exact to the block: any union of rectangles. Immutable. It's always kept in the same
 * form (strips along x, each split into rectangles along z, neighbouring strips merged when they match), so two
 * regions covering the same columns are equal and have the same boxes.
 */
public final class Region {

    /** The columns from (minX, minZ) to (maxX, maxZ), both corners included. */
    public record Box(int minX, int minZ, int maxX, int maxZ) {

        /** A box between two corners given in any order. */
        public static Box of(int x1, int z1, int x2, int z2) {
            return new Box(Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2));
        }

        public int width() {
            return maxX - minX + 1;
        }

        public int depth() {
            return maxZ - minZ + 1;
        }

        public long area() {
            return (long) width() * depth();
        }

        public boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        public boolean intersects(Box other) {
            return other.minX <= maxX && minX <= other.maxX && other.minZ <= maxZ && minZ <= other.maxZ;
        }

        /**
         * Horizontal distance in blocks from a column to the nearest column of this box, counted like a chessboard
         * king moves (so a buffer is square). 0 inside the box, 1 right next to it.
         */
        public int distance(int x, int z) {
            int dx = x < minX ? minX - x : Math.max(0, x - maxX);
            int dz = z < minZ ? minZ - z : Math.max(0, z - maxZ);
            return Math.max(dx, dz);
        }

        /** Distance between the nearest columns of two boxes, the same way. 0 if they overlap. */
        public int distance(Box other) {
            int dx = Math.max(0, Math.max(other.minX - maxX, minX - other.maxX));
            int dz = Math.max(0, Math.max(other.minZ - maxZ, minZ - other.maxZ));
            return Math.max(dx, dz);
        }

        public Box grow(int blocks) {
            return new Box(minX - blocks, minZ - blocks, maxX + blocks, maxZ + blocks);
        }
    }

    public static final Region EMPTY = new Region(List.of());

    private final List<Box> boxes;
    private final long area;

    private Region(List<Box> boxes) {
        this.boxes = List.copyOf(boxes);
        long total = 0;
        for (Box box : boxes) {
            total += box.area();
        }
        this.area = total;
    }

    public static Region of(Box box) {
        return new Region(List.of(box));
    }

    /** The columns covered by any of these boxes (they may overlap). */
    public static Region of(Collection<Box> boxes) {
        return combine(List.copyOf(boxes), List.of(), Op.UNION);
    }

    public static Region box(int x1, int z1, int x2, int z2) {
        return of(Box.of(x1, z1, x2, z2));
    }

    public List<Box> boxes() {
        return boxes;
    }

    public long area() {
        return area;
    }

    public boolean isEmpty() {
        return boxes.isEmpty();
    }

    /** The smallest box around the whole region, or null if it's empty. */
    public Box bounds() {
        if (boxes.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Box box : boxes) {
            minX = Math.min(minX, box.minX);
            minZ = Math.min(minZ, box.minZ);
            maxX = Math.max(maxX, box.maxX);
            maxZ = Math.max(maxZ, box.maxZ);
        }
        return new Box(minX, minZ, maxX, maxZ);
    }

    public boolean contains(int x, int z) {
        for (Box box : boxes) {
            if (box.contains(x, z)) {
                return true;
            }
        }
        return false;
    }

    public boolean intersects(Region other) {
        return within(other, 0);
    }

    /** Distance from a column to the nearest column of the region (see {@link Box#distance(int, int)}). */
    public int distance(int x, int z) {
        int best = Integer.MAX_VALUE;
        for (Box box : boxes) {
            best = Math.min(best, box.distance(x, z));
        }
        return best;
    }

    /** True if some column of this region is at most {@code distance} blocks from some column of the other. */
    public boolean within(Region other, int distance) {
        for (Box a : boxes) {
            for (Box b : other.boxes) {
                if (a.distance(b) <= distance) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The column of the region nearest to a column, as {x, z}, or null if it's empty. */
    public int[] nearest(int x, int z) {
        Box best = null;
        for (Box box : boxes) {
            if (best == null || box.distance(x, z) < best.distance(x, z)) {
                best = box;
            }
        }
        return best == null ? null : new int[]{
                Math.max(best.minX, Math.min(best.maxX, x)), Math.max(best.minZ, Math.min(best.maxZ, z))};
    }

    public Region union(Region other) {
        return combine(boxes, other.boxes, Op.UNION);
    }

    public Region minus(Region other) {
        return combine(boxes, other.boxes, Op.MINUS);
    }

    public Region intersect(Region other) {
        return combine(boxes, other.boxes, Op.INTERSECT);
    }

    /** This region with {@code blocks} more on every side. */
    public Region grow(int blocks) {
        if (blocks == 0) {
            return this;
        }
        List<Box> grown = new ArrayList<>(boxes.size());
        for (Box box : boxes) {
            grown.add(box.grow(blocks));
        }
        return of(grown);
    }

    /** The middle of the region, weighted by area, as {x, z}; moved onto the region if it falls outside it. */
    public int[] middle() {
        if (boxes.isEmpty()) {
            return new int[]{0, 0};
        }
        double sx = 0;
        double sz = 0;
        for (Box box : boxes) {
            sx += (box.minX + box.maxX) / 2.0 * box.area();
            sz += (box.minZ + box.maxZ) / 2.0 * box.area();
        }
        int x = (int) Math.floor(sx / area);
        int z = (int) Math.floor(sz / area);
        return contains(x, z) ? new int[]{x, z} : nearest(x, z);
    }

    // ------------------------------------------------------------------ combining

    private enum Op {
        UNION, MINUS, INTERSECT;

        boolean keep(boolean inA, boolean inB) {
            return switch (this) {
                case UNION -> inA || inB;
                case MINUS -> inA && !inB;
                case INTERSECT -> inA && inB;
            };
        }
    }

    /**
     * Combines two lists of boxes. The x axis is cut wherever any box starts or ends; inside each strip both sides
     * are plain lists of z intervals, which are combined, and then neighbouring strips with the same intervals are
     * joined back together. That always gives the same boxes for the same columns.
     */
    private static Region combine(List<Box> a, List<Box> b, Op op) {
        if (a.isEmpty() && b.isEmpty()) {
            return EMPTY;
        }
        TreeSet<Integer> cuts = new TreeSet<>();
        for (Box box : a) {
            cuts.add(box.minX);
            cuts.add(box.maxX + 1);
        }
        for (Box box : b) {
            cuts.add(box.minX);
            cuts.add(box.maxX + 1);
        }
        Integer[] xs = cuts.toArray(new Integer[0]);
        List<Box> out = new ArrayList<>();
        int[] runIntervals = null;
        int runStart = 0;
        int runEnd = 0;
        for (int i = 0; i + 1 < xs.length; i++) {
            int x0 = xs[i];
            int x1 = xs[i + 1] - 1;
            int[] intervals = combineIntervals(intervals(a, x0, x1), intervals(b, x0, x1), op);
            if (runIntervals != null && runEnd + 1 == x0 && Arrays.equals(runIntervals, intervals)) {
                runEnd = x1;
                continue;
            }
            flush(out, runIntervals, runStart, runEnd);
            runIntervals = intervals;
            runStart = x0;
            runEnd = x1;
        }
        flush(out, runIntervals, runStart, runEnd);
        return new Region(out);
    }

    private static void flush(List<Box> out, int[] intervals, int x0, int x1) {
        if (intervals == null) {
            return;
        }
        for (int i = 0; i < intervals.length; i += 2) {
            out.add(new Box(x0, intervals[i], x1, intervals[i + 1]));
        }
    }

    /** The z intervals of the boxes covering the strip x0..x1, merged, as {start, end, start, end, ...}. */
    private static int[] intervals(List<Box> boxes, int x0, int x1) {
        List<int[]> found = new ArrayList<>();
        for (Box box : boxes) {
            if (box.minX <= x0 && box.maxX >= x1) {
                found.add(new int[]{box.minZ, box.maxZ});
            }
        }
        found.sort((p, q) -> Integer.compare(p[0], q[0]));
        List<Integer> merged = new ArrayList<>();
        for (int[] interval : found) {
            int last = merged.size() - 1;
            if (last > 0 && interval[0] <= merged.get(last) + 1) {
                merged.set(last, Math.max(merged.get(last), interval[1]));
            } else {
                merged.add(interval[0]);
                merged.add(interval[1]);
            }
        }
        int[] result = new int[merged.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = merged.get(i);
        }
        return result;
    }

    private static int[] combineIntervals(int[] a, int[] b, Op op) {
        if (op == Op.UNION && b.length == 0) {
            return a;
        }
        TreeSet<Integer> cuts = new TreeSet<>();
        for (int i = 0; i < a.length; i += 2) {
            cuts.add(a[i]);
            cuts.add(a[i + 1] + 1);
        }
        for (int i = 0; i < b.length; i += 2) {
            cuts.add(b[i]);
            cuts.add(b[i + 1] + 1);
        }
        List<Integer> out = new ArrayList<>();
        Integer previous = null;
        for (int cut : cuts) {
            if (previous != null && op.keep(covers(a, previous), covers(b, previous))) {
                int last = out.size() - 1;
                if (last > 0 && out.get(last) + 1 == previous) {
                    out.set(last, cut - 1);
                } else {
                    out.add(previous);
                    out.add(cut - 1);
                }
            }
            previous = cut;
        }
        int[] result = new int[out.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = out.get(i);
        }
        return result;
    }

    private static boolean covers(int[] intervals, int z) {
        for (int i = 0; i < intervals.length; i += 2) {
            if (z >= intervals[i] && z <= intervals[i + 1]) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Region other && boxes.equals(other.boxes);
    }

    @Override
    public int hashCode() {
        return boxes.hashCode();
    }

    @Override
    public String toString() {
        return "Region" + boxes;
    }
}
