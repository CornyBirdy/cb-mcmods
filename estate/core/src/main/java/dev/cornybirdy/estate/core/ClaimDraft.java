package dev.cornybirdy.estate.core;

import java.util.HashSet;
import java.util.Set;

/**
 * A claim being drawn or resized before it's confirmed, as a box between two corners (like a Litematica area): corner
 * 1 and corner 2 are block coordinates. Horizontally the claim takes every cell the box touches; vertically it runs
 * from the lower corner's y to the higher corner's y.
 * <ul>
 *     <li>{@link Mode#NEW}: a new claim of exactly the box.</li>
 *     <li>{@link Mode#EXTEND}: an existing claim plus the box (drawing next to your claim adds to it).</li>
 *     <li>{@link Mode#RESIZE}: the box starts as the claim's bounds. Inside the original bounds the claim keeps its
 *     shape (cells outside the box are dropped); land the box gains beyond them is claimed in full.</li>
 * </ul>
 */
public final class ClaimDraft {

    public enum Mode {
        NEW, EXTEND, RESIZE
    }

    /** Box sizes above this many cells aren't worked out, so a typo can't make the server count millions of cells. */
    public static final int MAX_BOX_CELLS = 16_384;

    /** The claim being changed, or null for a new claim. */
    public final Integer claimId;
    public final String world;
    public final Mode mode;
    private final int cellSize;
    private final Set<Long> base;
    private final int baseMinCX;
    private final int baseMinCZ;
    private final int baseMaxCX;
    private final int baseMaxCZ;
    private final int baseMinY;
    private final int baseMaxY;
    /** corners[0] is corner 1, corners[1] corner 2, each {x, y, z}. */
    private final int[][] corners = new int[2][3];

    private ClaimDraft(Integer claimId, String world, Mode mode, int cellSize, Set<Long> base, int baseMinY, int baseMaxY) {
        this.claimId = claimId;
        this.world = world;
        this.mode = mode;
        this.cellSize = cellSize;
        this.base = Set.copyOf(base);
        int minCX = Integer.MAX_VALUE;
        int minCZ = Integer.MAX_VALUE;
        int maxCX = Integer.MIN_VALUE;
        int maxCZ = Integer.MIN_VALUE;
        for (long cell : base) {
            minCX = Math.min(minCX, Cells.x(cell));
            minCZ = Math.min(minCZ, Cells.z(cell));
            maxCX = Math.max(maxCX, Cells.x(cell));
            maxCZ = Math.max(maxCZ, Cells.z(cell));
        }
        this.baseMinCX = minCX;
        this.baseMinCZ = minCZ;
        this.baseMaxCX = maxCX;
        this.baseMaxCZ = maxCZ;
        this.baseMinY = baseMinY;
        this.baseMaxY = baseMaxY;
    }

    /** A new claim between two corners. */
    public static ClaimDraft create(String world, int cellSize, int[] corner1, int[] corner2) {
        ClaimDraft draft = new ClaimDraft(null, world, Mode.NEW, cellSize, Set.of(), 0, 0);
        draft.setCorner(1, corner1[0], corner1[1], corner1[2]);
        draft.setCorner(2, corner2[0], corner2[1], corner2[2]);
        return draft;
    }

    /** An existing claim plus the box between two corners. */
    public static ClaimDraft extend(Claim claim, int cellSize, int[] corner1, int[] corner2) {
        ClaimDraft draft = new ClaimDraft(claim.id(), claim.world(), Mode.EXTEND, cellSize, claim.cells(), claim.minY(), claim.maxY());
        draft.setCorner(1, corner1[0], corner1[1], corner1[2]);
        draft.setCorner(2, corner2[0], corner2[1], corner2[2]);
        return draft;
    }

    /** Resizing a claim: the box starts as its bounds, corner 1 at the low corner and corner 2 at the high one. */
    public static ClaimDraft resize(Claim claim, int cellSize) {
        ClaimDraft draft = new ClaimDraft(claim.id(), claim.world(), Mode.RESIZE, cellSize, claim.cells(), claim.minY(), claim.maxY());
        draft.setCorner(1, draft.baseMinCX * cellSize, claim.minY(), draft.baseMinCZ * cellSize);
        draft.setCorner(2, draft.baseMaxCX * cellSize + cellSize - 1, claim.maxY(), draft.baseMaxCZ * cellSize + cellSize - 1);
        return draft;
    }

    /** @param which 1 or 2 */
    public int[] corner(int which) {
        return corners[which - 1].clone();
    }

    /** @param which 1 or 2 */
    public void setCorner(int which, int x, int y, int z) {
        corners[which - 1][0] = x;
        corners[which - 1][1] = y;
        corners[which - 1][2] = z;
    }

    /** Moves one coordinate of a corner. @param axis 0 = x, 1 = y, 2 = z */
    public void move(int which, int axis, int amount) {
        corners[which - 1][axis] += amount;
    }

    public int minX() {
        return Math.min(corners[0][0], corners[1][0]);
    }

    public int maxX() {
        return Math.max(corners[0][0], corners[1][0]);
    }

    public int minZ() {
        return Math.min(corners[0][2], corners[1][2]);
    }

    public int maxZ() {
        return Math.max(corners[0][2], corners[1][2]);
    }

    /** Bottom of the claim. When extending, the claim's own range is kept too. */
    public int minY() {
        int box = Math.min(corners[0][1], corners[1][1]);
        return mode == Mode.EXTEND ? Math.min(box, baseMinY) : box;
    }

    public int maxY() {
        int box = Math.max(corners[0][1], corners[1][1]);
        return mode == Mode.EXTEND ? Math.max(box, baseMaxY) : box;
    }

    /** How many cells the box itself covers. */
    public long boxCells() {
        long width = (long) Cells.of(maxX(), cellSize) - Cells.of(minX(), cellSize) + 1;
        long depth = (long) Cells.of(maxZ(), cellSize) - Cells.of(minZ(), cellSize) + 1;
        return width * depth;
    }

    public boolean tooBig() {
        return boxCells() > MAX_BOX_CELLS;
    }

    /** The cells the claim would have. Empty if the box is {@link #tooBig()}. */
    public Set<Long> cells() {
        Set<Long> cells = new HashSet<>();
        if (tooBig()) {
            return cells;
        }
        int minCX = Cells.of(minX(), cellSize);
        int maxCX = Cells.of(maxX(), cellSize);
        int minCZ = Cells.of(minZ(), cellSize);
        int maxCZ = Cells.of(maxZ(), cellSize);
        if (mode == Mode.EXTEND) {
            cells.addAll(base);
        }
        for (int x = minCX; x <= maxCX; x++) {
            for (int z = minCZ; z <= maxCZ; z++) {
                long cell = Cells.key(x, z);
                boolean insideOldBounds = x >= baseMinCX && x <= baseMaxCX && z >= baseMinCZ && z <= baseMaxCZ;
                if (mode != Mode.RESIZE || !insideOldBounds || base.contains(cell)) {
                    cells.add(cell);
                }
            }
        }
        return cells;
    }
}
