package dev.cornybirdy.estate.core;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * An area of block columns in one world ({@link Region}, exact to the block) with a vertical range, an owner, a
 * trust list and flag overrides. Only {@link ClaimManager} changes claims, so its index stays correct; everyone else
 * gets read-only views.
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
    /** Made by auto-claim and not changed by hand since: it shrinks with the build and goes when the build is gone. */
    private boolean autoClaimed;
    /** The community this claim is shared with (every member counts as an owner), or 0. */
    private int communityId;
    /** What everyone not on the trust list may do here, or null for nothing. */
    private TrustTier defaultTier;
    private Region region = Region.EMPTY;
    private final Map<UUID, TrustTier> trust = new HashMap<>();
    private final Map<Flag, Boolean> flags = new EnumMap<>(Flag.class);
    /** Blocks other players paid for out of their own budget (pooled claims). The owner pays the rest. */
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

    public boolean autoClaimed() {
        return autoClaimed;
    }

    /** The community this claim is assigned to, or 0 for none. */
    public int communityId() {
        return communityId;
    }

    /** The trust level everyone gets without being on the list, or null. */
    public TrustTier defaultTier() {
        return defaultTier;
    }

    public Region region() {
        return region;
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

    /** The claim's area in blocks (columns), which is what it costs. */
    public int size() {
        return (int) Math.min(Integer.MAX_VALUE, region.area());
    }

    public boolean containsY(int y) {
        return y >= minY && y <= maxY;
    }

    public boolean contains(int x, int y, int z) {
        return containsY(y) && region.contains(x, z);
    }

    public boolean overlapsY(int otherMinY, int otherMaxY) {
        return otherMinY <= maxY && minY <= otherMaxY;
    }

    /** Blocks paid for by contributors. */
    public int donated() {
        int total = 0;
        for (int share : shares.values()) {
            total += share;
        }
        return total;
    }

    /** Blocks the owner pays for. */
    public int ownerShare() {
        return Math.max(0, size() - donated());
    }

    /** Blocks a player pays for in this claim. */
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

    void setAutoClaimed(boolean autoClaimed) {
        this.autoClaimed = autoClaimed;
    }

    void setCommunity(int communityId) {
        this.communityId = communityId;
    }

    void setDefaultTier(TrustTier tier) {
        this.defaultTier = tier;
    }

    void setRegion(Region region) {
        this.region = region;
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

    void setShare(UUID player, int blocks) {
        if (blocks <= 0) {
            shares.remove(player);
        } else {
            shares.put(player, blocks);
        }
    }

    /** When a claim shrinks below what was donated, contributors get the difference back, biggest share first. */
    private void trimShares() {
        int excess = donated() - size();
        while (excess > 0) {
            UUID biggest = null;
            for (Map.Entry<UUID, Integer> entry : shares.entrySet()) {
                if (biggest == null || entry.getValue() > shares.get(biggest)) {
                    biggest = entry.getKey();
                }
            }
            int take = Math.min(excess, shares.get(biggest));
            setShare(biggest, shares.get(biggest) - take);
            excess -= take;
        }
    }

    /** Used by storage when loading. */
    public static Claim load(int id, String world, UUID owner, String name, int minY, int maxY, long created,
                             boolean expiryExempt, boolean autoClaimed, int communityId, TrustTier defaultTier, Region region,
                             Map<UUID, TrustTier> trust, Map<Flag, Boolean> flags, Map<UUID, Integer> shares) {
        Claim claim = load(id, world, owner, name, minY, maxY, created, expiryExempt, autoClaimed, region, trust, flags, shares);
        claim.communityId = communityId;
        claim.defaultTier = defaultTier;
        return claim;
    }

    /** Used by storage when loading. */
    public static Claim load(int id, String world, UUID owner, String name, int minY, int maxY, long created,
                             boolean expiryExempt, boolean autoClaimed, Region region, Map<UUID, TrustTier> trust,
                             Map<Flag, Boolean> flags, Map<UUID, Integer> shares) {
        Claim claim = new Claim(id, world, owner, name, minY, maxY, created);
        claim.expiryExempt = expiryExempt;
        claim.autoClaimed = autoClaimed;
        claim.region = region;
        claim.trust.putAll(trust);
        claim.flags.putAll(flags);
        claim.shares.putAll(shares);
        return claim;
    }

    @Override
    public String toString() {
        return "Claim#" + id + "(" + name + ", " + size() + " blocks, y " + minY + ".." + maxY + ")";
    }
}
