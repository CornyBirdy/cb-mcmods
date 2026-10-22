package dev.cornybirdy.estate.core;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A set of cells in one world with a vertical range, an owner, a trust list and flag overrides. Only
 * {@link ClaimManager} changes claims, so its cell index stays correct; everyone else gets read-only views.
 */
public final class Claim {

    private final int id;
    private final String world;
    private final long created;
    private UUID owner;
    private String name;
    private int minY;
    private int maxY;
    private boolean expiryExempt;
    private final Set<Long> cells = new HashSet<>();
    private final Map<UUID, TrustTier> trust = new HashMap<>();
    private final Map<Flag, Boolean> flags = new EnumMap<>(Flag.class);
    /** Cells other players paid for out of their own budget (pooled claims). The owner pays the rest. */
    private final Map<UUID, Integer> shares = new HashMap<>();

    public Claim(int id, String world, UUID owner, String name, int minY, int maxY, long created) {
        this.id = id;
        this.world = world;
        this.owner = owner;
        this.name = name;
        this.minY = minY;
        this.maxY = maxY;
        this.created = created;
    }

    public int id() {
        return id;
    }

    public String world() {
        return world;
    }

    public UUID owner() {
        return owner;
    }

    public String name() {
        return name;
    }

    public int minY() {
        return minY;
    }

    public int maxY() {
        return maxY;
    }

    public long created() {
        return created;
    }

    public boolean expiryExempt() {
        return expiryExempt;
    }

    public Set<Long> cells() {
        return Collections.unmodifiableSet(cells);
    }

    public Map<UUID, TrustTier> trust() {
        return Collections.unmodifiableMap(trust);
    }

    /** Only the flags this claim overrides; the rest use the config defaults. */
    public Map<Flag, Boolean> flagOverrides() {
        return Collections.unmodifiableMap(flags);
    }

    public Map<UUID, Integer> shares() {
        return Collections.unmodifiableMap(shares);
    }

    public int size() {
        return cells.size();
    }

    public boolean containsCell(long cell) {
        return cells.contains(cell);
    }

    public boolean containsY(int y) {
        return y >= minY && y <= maxY;
    }

    public boolean contains(int x, int y, int z, int cellSize) {
        return containsY(y) && cells.contains(Cells.containing(x, z, cellSize));
    }

    public boolean overlapsY(int otherMinY, int otherMaxY) {
        return otherMinY <= maxY && minY <= otherMaxY;
    }

    /** Cells paid for by contributors. */
    public int donated() {
        int total = 0;
        for (int share : shares.values()) {
            total += share;
        }
        return total;
    }

    /** Cells the owner pays for. */
    public int ownerShare() {
        return Math.max(0, cells.size() - donated());
    }

    /** Cells a player pays for in this claim. */
    public int share(UUID player) {
        return player.equals(owner) ? ownerShare() : shares.getOrDefault(player, 0);
    }

    /** The owner and everyone who put budget into the claim. */
    public Set<UUID> members() {
        Set<UUID> members = new LinkedHashSet<>();
        members.add(owner);
        members.addAll(shares.keySet());
        return members;
    }

    public boolean isMember(UUID player) {
        return owner.equals(player) || shares.containsKey(player);
    }

    // Changes, only from ClaimManager and storage.

    void setOwner(UUID owner) {
        this.owner = owner;
        shares.remove(owner);
        trust.remove(owner);
    }

    void setName(String name) {
        this.name = name;
    }

    void setRange(int minY, int maxY) {
        this.minY = minY;
        this.maxY = maxY;
    }

    void setExpiryExempt(boolean exempt) {
        this.expiryExempt = exempt;
    }

    void setCells(Set<Long> newCells) {
        cells.clear();
        cells.addAll(newCells);
        trimShares();
    }

    void setTrust(UUID player, TrustTier tier) {
        if (tier == null) {
            trust.remove(player);
        } else {
            trust.put(player, tier);
        }
    }

    void setFlag(Flag flag, Boolean value) {
        if (value == null) {
            flags.remove(flag);
        } else {
            flags.put(flag, value);
        }
    }

    void setShare(UUID player, int cells) {
        if (cells <= 0) {
            shares.remove(player);
        } else {
            shares.put(player, cells);
        }
    }

    /** When a claim shrinks below what was donated, contributors get the difference back, biggest share first. */
    private void trimShares() {
        int excess = donated() - cells.size();
        while (excess > 0) {
            UUID biggest = null;
            for (Map.Entry<UUID, Integer> entry : shares.entrySet()) {
                if (biggest == null || entry.getValue() > shares.get(biggest)) {
                    biggest = entry.getKey();
                }
            }
            setShare(biggest, shares.get(biggest) - 1);
            excess--;
        }
    }

    /** Used by storage when loading. */
    public static Claim load(int id, String world, UUID owner, String name, int minY, int maxY, long created,
                             boolean expiryExempt, Set<Long> cells, Map<UUID, TrustTier> trust,
                             Map<Flag, Boolean> flags, Map<UUID, Integer> shares) {
        Claim claim = new Claim(id, world, owner, name, minY, maxY, created);
        claim.expiryExempt = expiryExempt;
        claim.cells.addAll(cells);
        claim.trust.putAll(trust);
        claim.flags.putAll(flags);
        claim.shares.putAll(shares);
        return claim;
    }

    @Override
    public String toString() {
        return "Claim#" + id + "(" + name + ", " + cells.size() + " cells, y " + minY + ".." + maxY + ")";
    }
}
