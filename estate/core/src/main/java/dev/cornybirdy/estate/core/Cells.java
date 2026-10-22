package dev.cornybirdy.estate.core;

/**
 * The world is split into square cells of {@code cellSize} x {@code cellSize} blocks. A cell is stored as one long:
 * its cell x in the high 32 bits and its cell z in the low 32 bits.
 */
public final class Cells {

    private Cells() {
    }

    public static long key(int cellX, int cellZ) {
        return ((long) cellX << 32) | (cellZ & 0xFFFFFFFFL);
    }

    public static int x(long key) {
        return (int) (key >> 32);
    }

    public static int z(long key) {
        return (int) key;
    }

    /** The cell coordinate a block coordinate falls in. Works for negative coordinates too. */
    public static int of(int block, int cellSize) {
        return Math.floorDiv(block, cellSize);
    }

    public static long containing(int blockX, int blockZ, int cellSize) {
        return key(of(blockX, cellSize), of(blockZ, cellSize));
    }

    /**
     * Horizontal distance in blocks from a block to the nearest block of a cell, counted like a chessboard king
     * moves (so a buffer is square). 0 if the block is inside the cell.
     */
    public static int distance(int blockX, int blockZ, long cell, int cellSize) {
        int minX = x(cell) * cellSize;
        int minZ = z(cell) * cellSize;
        int dx = blockX < minX ? minX - blockX : Math.max(0, blockX - (minX + cellSize - 1));
        int dz = blockZ < minZ ? minZ - blockZ : Math.max(0, blockZ - (minZ + cellSize - 1));
        return Math.max(dx, dz);
    }

    /** Distance in blocks between the nearest blocks of two cells, the same way as {@link #distance}. */
    public static int distance(long a, long b, int cellSize) {
        int dx = Math.abs(x(a) - x(b));
        int dz = Math.abs(z(a) - z(b));
        return Math.max(dx == 0 ? 0 : (dx - 1) * cellSize + 1, dz == 0 ? 0 : (dz - 1) * cellSize + 1);
    }

    /** How many cells out a buffer of {@code blocks} blocks can reach. */
    public static int reach(int blocks, int cellSize) {
        return blocks <= 0 ? 0 : (blocks - 1) / cellSize + 1;
    }

    public static String describe(long cell) {
        return x(cell) + "," + z(cell);
    }
}
