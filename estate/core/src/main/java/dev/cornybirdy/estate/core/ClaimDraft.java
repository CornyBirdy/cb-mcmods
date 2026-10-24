package dev.cornybirdy.estate.core;

/**
 * A claim being drawn or resized before it's confirmed, as a box between two corners (like a Litematica area): corner
 * 1 and corner 2 are block coordinates, and the claim covers exactly the blocks between them, both corners included.
 * <ul>
 *     <li>{@link Mode#NEW}: a new claim of exactly the box.</li>
 *     <li>{@link Mode#EXTEND}: an existing claim plus the box (drawing next to your claim adds to it).</li>
 *     <li>{@link Mode#RESIZE}: the box starts as the claim's bounds. Inside the original bounds the claim keeps its
 *     shape (parts outside the box are dropped); land the box gains beyond them is claimed in full.</li>
 * </ul>
 */
public final class ClaimDraft {

    public enum Mode {
        NEW, EXTEND, RESIZE
    }

    /** Boxes bigger than this many blocks (1000 x 1000) are refused straight away, so a typo can't claim a continent. */
    public static final long MAX_BOX_AREA = 1_000_000;

    /** The claim being changed, or null for a new claim. */
    public final Integer claimId;
    public final String world;
    public final Mode mode;
    private final Region base;
    private final Region baseBounds;
    private final int baseMinY;
    private final int baseMaxY;
    /** corners[0] is corner 1, corners[1] corner 2, each {x, y, z}. */
    private final int[][] corners = new int[2][3];

    private ClaimDraft(Integer claimId, String world, Mode mode, Region base, int baseMinY, int baseMaxY) {
        this.claimId = claimId;
        this.world = world;
        this.mode = mode;
        this.base = base;
        Region.Box bounds = base.bounds();
        this.baseBounds = bounds == null ? Region.EMPTY : Region.of(bounds);
        this.baseMinY = baseMinY;
        this.baseMaxY = baseMaxY;
    }

    /** A new claim between two corners. */
    public static ClaimDraft create(String world, int[] corner1, int[] corner2) {
        ClaimDraft draft = new ClaimDraft(null, world, Mode.NEW, Region.EMPTY, 0, 0);
        draft.setCorner(1, corner1[0], corner1[1], corner1[2]);
        draft.setCorner(2, corner2[0], corner2[1], corner2[2]);
        return draft;
    }

    /** An existing claim plus the box between two corners. */
    public static ClaimDraft extend(Claim claim, int[] corner1, int[] corner2) {
        ClaimDraft draft = new ClaimDraft(claim.id(), claim.world(), Mode.EXTEND, claim.region(), claim.minY(), claim.maxY());
        draft.setCorner(1, corner1[0], corner1[1], corner1[2]);
        draft.setCorner(2, corner2[0], corner2[1], corner2[2]);
        return draft;
    }

    /** Resizing a claim: the box starts as its bounds, corner 1 at the low corner and corner 2 at the high one. */
    public static ClaimDraft resize(Claim claim) {
        ClaimDraft draft = new ClaimDraft(claim.id(), claim.world(), Mode.RESIZE, claim.region(), claim.minY(), claim.maxY());
        Region.Box bounds = claim.region().bounds();
        if (bounds == null) {
            bounds = new Region.Box(0, 0, 0, 0);
        }
        draft.setCorner(1, bounds.minX(), claim.minY(), bounds.minZ());
        draft.setCorner(2, bounds.maxX(), claim.maxY(), bounds.maxZ());
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

    public int width() {
        return maxX() - minX() + 1;
    }

    public int depth() {
        return maxZ() - minZ() + 1;
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

    /** How many blocks (columns) the box itself covers. */
    public long boxArea() {
        return (long) width() * depth();
    }

    public boolean tooBig() {
        return boxArea() > MAX_BOX_AREA;
    }

    /** The box between the corners. */
    public Region box() {
        return Region.box(corners[0][0], corners[0][2], corners[1][0], corners[1][2]);
    }

    /** The area the claim would have. */
    public Region region() {
        Region box = box();
        return switch (mode) {
            case NEW -> box;
            case EXTEND -> base.union(box);
            case RESIZE -> base.intersect(box).union(box.minus(baseBounds));
        };
    }
}
