package dev.cornybirdy.estate.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A claim shape being edited in the menu before it's confirmed: a new claim, or a resize of an existing one.
 * Each side can grow or shrink by one row of cells; the top and bottom move by a few blocks.
 */
public final class ClaimDraft {

    public enum Side {
        NORTH, SOUTH, EAST, WEST, UP, DOWN
    }

    /** The claim being resized, or null for a new claim. */
    public final Integer claimId;
    public final String world;
    private final Set<Long> cells;
    private int minY;
    private int maxY;
    private Side side = Side.NORTH;

    public ClaimDraft(Integer claimId, String world, Set<Long> cells, int minY, int maxY) {
        this.claimId = claimId;
        this.world = world;
        this.cells = new HashSet<>(cells);
        this.minY = minY;
        this.maxY = maxY;
    }

    public static ClaimDraft of(Claim claim) {
        return new ClaimDraft(claim.id(), claim.world(), claim.cells(), claim.minY(), claim.maxY());
    }

    /** A square of {@code size} x {@code size} cells around a block. */
    public static ClaimDraft square(String world, int blockX, int blockZ, int cellSize, int size, int minY, int maxY) {
        int cx = Cells.of(blockX, cellSize) - (size - 1) / 2;
        int cz = Cells.of(blockZ, cellSize) - (size - 1) / 2;
        Set<Long> cells = new HashSet<>();
        for (int x = cx; x < cx + size; x++) {
            for (int z = cz; z < cz + size; z++) {
                cells.add(Cells.key(x, z));
            }
        }
        return new ClaimDraft(null, world, cells, minY, maxY);
    }

    public Set<Long> cells() {
        return cells;
    }

    public int minY() {
        return minY;
    }

    public int maxY() {
        return maxY;
    }

    public Side side() {
        return side;
    }

    public void setSide(Side side) {
        this.side = side;
    }

    /** Adds a row of cells on the chosen side, or raises the top / lowers the bottom. False if nothing changed. */
    public boolean grow(int verticalStep, int worldMinY, int worldMaxY) {
        switch (side) {
            case UP -> {
                int top = Math.min(worldMaxY, maxY + verticalStep);
                boolean changed = top != maxY;
                maxY = top;
                return changed;
            }
            case DOWN -> {
                int bottom = Math.max(worldMinY, minY - verticalStep);
                boolean changed = bottom != minY;
                minY = bottom;
                return changed;
            }
            default -> {
                Set<Long> added = new HashSet<>();
                for (Map.Entry<Integer, int[]> line : lines().entrySet()) {
                    int[] range = line.getValue();
                    added.add(switch (side) {
                        case NORTH -> Cells.key(line.getKey(), range[0] - 1);
                        case SOUTH -> Cells.key(line.getKey(), range[1] + 1);
                        case WEST -> Cells.key(range[0] - 1, line.getKey());
                        default -> Cells.key(range[1] + 1, line.getKey());
                    });
                }
                return cells.addAll(added);
            }
        }
    }

    /** Removes the outermost row of cells on the chosen side, or lowers the top / raises the bottom. */
    public boolean shrink(int verticalStep) {
        switch (side) {
            case UP -> {
                int top = Math.max(minY, maxY - verticalStep);
                boolean changed = top != maxY;
                maxY = top;
                return changed;
            }
            case DOWN -> {
                int bottom = Math.min(maxY, minY + verticalStep);
                boolean changed = bottom != minY;
                minY = bottom;
                return changed;
            }
            default -> {
                Set<Long> removed = new HashSet<>();
                for (Map.Entry<Integer, int[]> line : lines().entrySet()) {
                    int[] range = line.getValue();
                    if (range[0] == range[1]) {
                        // Only one cell left in this line; removing it would cut the claim apart.
                        continue;
                    }
                    removed.add(switch (side) {
                        case NORTH -> Cells.key(line.getKey(), range[0]);
                        case SOUTH -> Cells.key(line.getKey(), range[1]);
                        case WEST -> Cells.key(range[0], line.getKey());
                        default -> Cells.key(range[1], line.getKey());
                    });
                }
                return cells.removeAll(removed);
            }
        }
    }

    /** For north/south: per cell x, the lowest and highest cell z. For east/west: per cell z, the x range. */
    private Map<Integer, int[]> lines() {
        boolean northSouth = side == Side.NORTH || side == Side.SOUTH;
        Map<Integer, int[]> lines = new HashMap<>();
        for (long cell : cells) {
            int line = northSouth ? Cells.x(cell) : Cells.z(cell);
            int along = northSouth ? Cells.z(cell) : Cells.x(cell);
            int[] range = lines.computeIfAbsent(line, k -> new int[]{along, along});
            range[0] = Math.min(range[0], along);
            range[1] = Math.max(range[1], along);
        }
        return lines;
    }
}
