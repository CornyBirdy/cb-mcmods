package dev.cornybirdy.estate.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * All claim rules, with no Minecraft code in it: lookups, budgets, trust, buffers, checks before claiming, and every
 * change. Changes are saved through the {@link ClaimStore} before the method returns. Not thread-safe; use it from
 * the server thread only.
 */
public final class ClaimManager {

    private static final long DAY = 86_400L;

    private final ClaimStore store;
    private final LongSupplier clock;
    private EstateConfig config;
    private final Map<Integer, Claim> claims = new LinkedHashMap<>();
    /** Per world: the claims touching each 16x16 chunk column, so lookups by position stay fast. */
    private final Map<String, Map<Long, List<Claim>>> index = new HashMap<>();
    private final Map<UUID, PlayerRecord> players = new HashMap<>();
    private final Map<Integer, TownZone> towns = new LinkedHashMap<>();
    private final Set<UUID> banned = new HashSet<>();
    private int nextClaimId = 1;
    private int nextTownId = 1;

    /** @param clock current time in epoch seconds */
    public ClaimManager(ClaimStore store, EstateConfig config, LongSupplier clock) {
        this.store = store;
        this.clock = clock;
        ClaimStore.Snapshot snapshot = store.loadAll();
        for (PlayerRecord player : snapshot.players()) {
            players.put(player.id, player);
        }
        for (Claim claim : snapshot.claims()) {
            claims.put(claim.id(), claim);
            addToIndex(claim);
            nextClaimId = Math.max(nextClaimId, claim.id() + 1);
        }
        for (TownZone town : snapshot.towns()) {
            towns.put(town.id(), town);
            nextTownId = Math.max(nextTownId, town.id() + 1);
        }
        setConfig(config);
    }

    /** Uses a new or reloaded config. */
    public void setConfig(EstateConfig config) {
        this.config = config;
    }

    public EstateConfig config() {
        return config;
    }

    public long now() {
        return clock.getAsLong();
    }

    // ------------------------------------------------------------------ lookups

    public Claim claim(int id) {
        return claims.get(id);
    }

    public Collection<Claim> claims() {
        return Collections.unmodifiableCollection(claims.values());
    }

    public boolean hasClaims() {
        return !claims.isEmpty();
    }

    public List<Claim> claimsOf(UUID owner) {
        List<Claim> list = new ArrayList<>();
        for (Claim claim : claims.values()) {
            if (claim.owner().equals(owner)) {
                list.add(claim);
            }
        }
        return list;
    }

    /** Claims the player owns or put budget into. */
    public List<Claim> claimsPaidBy(UUID player) {
        List<Claim> list = new ArrayList<>();
        for (Claim claim : claims.values()) {
            if (claim.share(player) > 0 || claim.owner().equals(player)) {
                list.add(claim);
            }
        }
        return list;
    }

    private List<Claim> claimsInChunk(String world, int chunkX, int chunkZ) {
        Map<Long, List<Claim>> chunks = index.get(world);
        List<Claim> list = chunks == null ? null : chunks.get(Cells.key(chunkX, chunkZ));
        return list == null ? List.of() : list;
    }

    /** The claim covering a block, or null if it's unclaimed. */
    public Claim claimAt(String world, int x, int y, int z) {
        for (Claim claim : claimsInChunk(world, x >> 4, z >> 4)) {
            if (claim.contains(x, y, z)) {
                return claim;
            }
        }
        return null;
    }

    /** Every claim covering a column, at any height. */
    public List<Claim> claimsInColumn(String world, int x, int z) {
        List<Claim> found = new ArrayList<>(1);
        for (Claim claim : claimsInChunk(world, x >> 4, z >> 4)) {
            if (claim.region().contains(x, z)) {
                found.add(claim);
            }
        }
        return found;
    }

    /** Every claim within {@code radius} blocks of a column (or covering it), at any height. */
    public Set<Claim> claimsNear(String world, int x, int z, int radius) {
        Set<Claim> found = new LinkedHashSet<>();
        for (int cx = (x - radius) >> 4; cx <= (x + radius) >> 4; cx++) {
            for (int cz = (z - radius) >> 4; cz <= (z + radius) >> 4; cz++) {
                for (Claim claim : claimsInChunk(world, cx, cz)) {
                    if (!found.contains(claim) && claim.region().distance(x, z) <= radius) {
                        found.add(claim);
                    }
                }
            }
        }
        return found;
    }

    /** Every claim in a world. */
    public List<Claim> claimsIn(String world) {
        List<Claim> list = new ArrayList<>();
        for (Claim claim : claims.values()) {
            if (claim.world().equals(world)) {
                list.add(claim);
            }
        }
        return list;
    }

    // ------------------------------------------------------------------ players and budgets

    /** The player's record, or null if Estate has never seen them. */
    public PlayerRecord player(UUID id) {
        return players.get(id);
    }

    public PlayerRecord playerByName(String name) {
        for (PlayerRecord player : players.values()) {
            if (player.name.equalsIgnoreCase(name)) {
                return player;
            }
        }
        return null;
    }

    public String name(UUID id) {
        PlayerRecord player = players.get(id);
        return player == null ? id.toString().substring(0, 8) : player.displayName();
    }

    /** Called on login and logout: remembers the name and counts this as being seen. */
    public PlayerRecord seen(UUID id, String name) {
        PlayerRecord player = players.get(id);
        long now = now();
        if (player == null) {
            player = new PlayerRecord(id, name);
            player.firstSeen = now;
            players.put(id, player);
        }
        if (name != null && !name.isEmpty()) {
            player.name = name;
        }
        player.lastSeen = now;
        store.savePlayer(player);
        return player;
    }

    /** Makes sure a player has a record, e.g. before trusting someone who has never joined since Estate was added. */
    public PlayerRecord ensurePlayer(UUID id, String name) {
        PlayerRecord player = players.get(id);
        if (player == null) {
            player = new PlayerRecord(id, name);
            players.put(id, player);
            store.savePlayer(player);
        }
        return player;
    }

    public boolean autoClaim(UUID id) {
        PlayerRecord player = players.get(id);
        return player != null ? player.autoClaim(config) : config.autoClaimDefault;
    }

    public void setAutoClaim(UUID id, boolean on) {
        PlayerRecord player = ensurePlayer(id, null);
        player.autoClaim = on;
        player.promptDone = true;
        store.savePlayer(player);
    }

    public void setPromptDone(UUID id) {
        PlayerRecord player = ensurePlayer(id, null);
        if (!player.promptDone) {
            player.promptDone = true;
            store.savePlayer(player);
        }
    }

    /** Admin grant: extra blocks on top of the base budget. */
    public void setBonusBudget(UUID id, int blocks, UUID actor) {
        PlayerRecord player = ensurePlayer(id, null);
        int old = player.bonusBudget;
        player.bonusBudget = Math.max(0, blocks);
        store.savePlayer(player);
        store.log(new Action(now(), actor, "admin-budget", null, null, null, null, null,
                player.displayName() + ": " + old + " -> " + player.bonusBudget));
    }

    /** Blocks the player may claim in total. The same for everyone except admin grants. */
    public int budgetLimit(UUID id) {
        PlayerRecord player = players.get(id);
        int bonus = player == null ? 0 : player.bonusBudget;
        int cap = bonus > 0 ? config.maxGrantedBudget : config.maxTotalBudget;
        return Math.max(0, Math.min(config.baseBudget + bonus, cap));
    }

    /** Blocks the player pays for across all claims, including what they donated to pooled claims. */
    public int budgetUsed(UUID id) {
        int used = 0;
        for (Claim claim : claims.values()) {
            used += claim.share(id);
        }
        return used;
    }

    public int budgetLeft(UUID id) {
        return budgetLimit(id) - budgetUsed(id);
    }

    // ------------------------------------------------------------------ permissions

    public boolean has(Claim claim, UUID player, Permission permission) {
        if (player == null) {
            return false;
        }
        if (claim.owner().equals(player)) {
            return true;
        }
        TrustTier tier = claim.trust().get(player);
        if (tier != null && config.tierAllows(tier, permission)) {
            return true;
        }
        // Everyone who paid into a pooled claim may build right next to it.
        return permission == Permission.BUFFER && claim.isMember(player);
    }

    /** A flag's value for a claim: its own setting if it has one, otherwise the config default. */
    public boolean flag(Claim claim, Flag flag) {
        Boolean value = claim.flagOverrides().get(flag);
        return value != null ? value : config.flagDefault(flag);
    }

    /** Whether a player may place or break a block here. Null means yes. */
    public Denial checkBuild(String world, int x, int y, int z, UUID actor) {
        Claim claim = claimAt(world, x, y, z);
        if (claim != null) {
            return has(claim, actor, Permission.BUILD) || !flag(claim, Flag.BLOCK_BREAK_PLACE)
                    ? null : new Denial(Denial.Kind.CLAIM, claim);
        }
        Claim buffer = bufferAt(world, x, y, z, actor);
        if (buffer != null) {
            return new Denial(Denial.Kind.BUFFER, buffer);
        }
        Claim capped = heightCapAt(world, x, y, z, actor);
        return capped == null ? null : new Denial(Denial.Kind.HEIGHT_CAP, capped);
    }

    /**
     * Whether a player may do something other than building inside a claim (open doors, chests, ...). Outside
     * claims everything is allowed. Null means yes.
     *
     * @param flag the flag that turns this protection off, or null if it's always protected
     */
    public Denial checkAction(String world, int x, int y, int z, UUID actor, Permission permission, Flag flag) {
        Claim claim = claimAt(world, x, y, z);
        if (claim == null || has(claim, actor, permission) || (flag != null && !flag(claim, flag))) {
            return null;
        }
        return new Denial(Denial.Kind.CLAIM, claim);
    }

    public boolean inTown(String world, int x, int z) {
        for (TownZone town : towns.values()) {
            if (town.contains(world, x, z)) {
                return true;
            }
        }
        return false;
    }

    /** Every town zone in a world as one region. */
    public Region townRegion(String world) {
        List<Region.Box> boxes = new ArrayList<>();
        for (TownZone town : towns.values()) {
            if (town.world().equals(world)) {
                boxes.add(town.box());
            }
        }
        return boxes.isEmpty() ? Region.EMPTY : Region.of(boxes);
    }

    /** True if the buffer rules don't apply to this player for this claim. */
    public boolean bufferWaived(Claim claim, UUID player) {
        return has(claim, player, Permission.BUFFER);
    }

    /**
     * The claim whose buffer covers a spot outside every claim and that this player may not build in, or null.
     * Buffers don't apply in town zones or to players the owner trusts with the buffer permission.
     */
    public Claim bufferAt(String world, int x, int y, int z, UUID actor) {
        int buffer = config.bufferDistance;
        if (buffer <= 0 || inTown(world, x, z)) {
            return null;
        }
        for (Claim claim : claimsNear(world, x, z, buffer)) {
            if (inBuffer(claim, x, y, z, buffer) && !bufferWaived(claim, actor)) {
                return claim;
            }
        }
        return null;
    }

    /** The claim whose height cap this spot is above, or null. Only matters if {@code bufferHeightCap} is on. */
    public Claim heightCapAt(String world, int x, int y, int z, UUID actor) {
        int cap = config.bufferHeightCap;
        int buffer = config.bufferDistance;
        if (cap <= 0 || buffer <= 0) {
            return null;
        }
        for (Claim claim : claimsNear(world, x, z, buffer)) {
            if (!claim.owner().equals(actor) && near(claim, x, z, buffer) && y > groundLevel(claim) + cap) {
                return claim;
            }
        }
        return null;
    }

    /** Roughly where the claim's build starts: the bottom of its range plus the padding. */
    public int groundLevel(Claim claim) {
        return Math.min(claim.minY() + config.verticalPadding, claim.maxY());
    }

    private boolean inBuffer(Claim claim, int x, int y, int z, int buffer) {
        if (y < claim.minY() - buffer || y > claim.maxY() + buffer) {
            return false;
        }
        if (claim.contains(x, y, z)) {
            return false;
        }
        return near(claim, x, z, buffer);
    }

    /** True if a column is within {@code distance} blocks of the claim (or in it). */
    private static boolean near(Claim claim, int x, int z, int distance) {
        return claim.region().distance(x, z) <= distance;
    }

    // ------------------------------------------------------------------ checks

    /**
     * Checks whether a claim can be created with this area and heights, or an existing claim changed to them.
     *
     * @param payer    who pays (the owner); ignored for an existing claim
     * @param existing the claim being changed, or null for a new claim
     * @param bypass   admin: skip budget, buffer, claim count and other players' blocks
     */
    public Check check(String world, UUID payer, Region region, int minY, int maxY, Claim existing,
                       PlacementOracle oracle, boolean bypass) {
        if (existing != null) {
            payer = existing.owner();
        }
        int budgetLeft = budgetLeft(payer);
        int donated = existing == null ? 0 : existing.donated();
        int oldShare = existing == null ? 0 : existing.ownerShare();
        int size = (int) Math.min(Integer.MAX_VALUE / 2, region.area());
        int cost = Math.max(0, size - donated) - oldShare;

        if (!config.isClaimable(world) && !bypass) {
            return Check.fail(Problem.WORLD_NOT_CLAIMABLE, cost, budgetLeft);
        }
        if (region.isEmpty()) {
            return Check.fail(Problem.EMPTY, cost, budgetLeft);
        }
        if (minY > maxY) {
            return Check.fail(Problem.BAD_HEIGHT, cost, budgetLeft);
        }
        if (existing == null && !bypass && claimsOf(payer).size() >= config.maxClaimsPerPlayer) {
            return Check.fail(Problem.TOO_MANY_CLAIMS, cost, budgetLeft);
        }

        // Only land that's new to the claim is checked, unless the claim got taller.
        boolean grew = existing == null || minY < existing.minY() || maxY > existing.maxY();
        Region toCheck = grew ? region : region.minus(existing.region());
        List<Claim> others = claimsIn(world);
        others.remove(existing);

        if (!toCheck.isEmpty()) {
            for (Claim other : others) {
                if (other.overlapsY(minY, maxY) && other.region().intersects(toCheck)) {
                    return Check.fail(Problem.OVERLAP, cost, budgetLeft).with(other);
                }
            }
        }

        int buffer = config.bufferDistance;
        if (!bypass && buffer > 0 && !toCheck.isEmpty()) {
            Region outsideTowns = toCheck.minus(townRegion(world));
            for (Claim other : others) {
                if (other.owner().equals(payer) || bufferWaived(other, payer)
                        || minY > other.maxY() + buffer || maxY < other.minY() - buffer) {
                    continue;
                }
                if (outsideTowns.within(other.region(), buffer)) {
                    return Check.fail(Problem.BUFFER, cost, budgetLeft).with(other);
                }
            }
        }

        if (!bypass && cost > 0 && cost > budgetLeft) {
            return Check.fail(Problem.NO_BUDGET, cost, budgetLeft);
        }

        if (!bypass && !toCheck.isEmpty()) {
            UUID owner = payer;
            UUID builder = oracle.findBuilder(world, toCheck, minY, maxY, id -> blocksCount(id, owner, existing));
            if (builder != null) {
                return Check.fail(Problem.OTHERS_BLOCKS, cost, budgetLeft).withBlocker(builder);
            }
        }
        return Check.ok(cost, budgetLeft);
    }

    /**
     * Whether a player's placed blocks stop the owner from claiming over them. They don't if it's the owner, a
     * build partner of the owner (who may not have a claim to trust them in yet), a friend the owner trusts to build,
     * or someone who's inactive or banned (their claims expire, so their loose
     * blocks shouldn't keep land locked either).
     */
    public boolean blocksCount(UUID builder, UUID owner, Claim existing) {
        if (builder.equals(owner) || banned.contains(builder) || isInactive(builder)) {
            return false;
        }
        PlayerRecord record = players.get(owner);
        if (record != null && record.buildPartners.contains(builder)) {
            return false;
        }
        if (existing != null && has(existing, builder, Permission.BUILD)) {
            return false;
        }
        for (Claim claim : claimsOf(owner)) {
            if (has(claim, builder, Permission.BUILD)) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ changes

    public record Change(Check check, Claim claim) {
        public boolean ok() {
            return check.allowed();
        }
    }

    public Change create(String world, UUID owner, String name, Region region, int minY, int maxY, UUID actor,
                         PlacementOracle oracle, boolean bypass) {
        Check check = check(world, owner, region, minY, maxY, null, oracle, bypass);
        if (!check.allowed()) {
            return new Change(check, null);
        }
        Claim claim = new Claim(nextClaimId++, world, owner, name, minY, maxY, now());
        claim.setRegion(region);
        claims.put(claim.id(), claim);
        addToIndex(claim);
        store.saveClaim(claim);
        setPromptDone(owner);
        store.log(Action.of(now(), actor, bypass && !owner.equals(actor) ? "admin-create" : "create", claim,
                claim.size() + " blocks, y " + minY + ".." + maxY));
        return new Change(check, claim);
    }

    public Check resize(Claim claim, Region region, int minY, int maxY, UUID actor, PlacementOracle oracle,
                        boolean bypass) {
        Check check = check(claim.world(), claim.owner(), region, minY, maxY, claim, oracle, bypass);
        if (!check.allowed()) {
            return check;
        }
        int oldSize = claim.size();
        String oldRange = claim.minY() + ".." + claim.maxY();
        removeFromIndex(claim);
        claim.setRegion(region);
        claim.setRange(minY, maxY);
        addToIndex(claim);
        store.saveClaim(claim);
        store.log(Action.of(now(), actor, bypass && !claim.owner().equals(actor) ? "admin-resize" : "resize", claim,
                oldSize + " -> " + claim.size() + " blocks, y " + oldRange + " -> " + minY + ".." + maxY));
        return check;
    }

    /** @param type log entry type, e.g. {@code unclaim}, {@code expire}, {@code ban}, {@code admin-delete} */
    public void delete(Claim claim, UUID actor, String type) {
        if (claims.remove(claim.id()) == null) {
            return;
        }
        removeFromIndex(claim);
        store.deleteClaim(claim.id());
        store.log(Action.of(now(), actor, type, claim, name(claim.owner()) + ", " + claim.size() + " blocks"));
    }

    public void transfer(Claim claim, UUID newOwner, UUID actor) {
        UUID old = claim.owner();
        claim.setOwner(newOwner);
        store.saveClaim(claim);
        store.log(Action.of(now(), actor, "transfer", claim, name(old) + " -> " + name(newOwner)));
    }

    /** @param tier null removes the player from the trust list */
    public void trust(Claim claim, UUID player, TrustTier tier, UUID actor) {
        claim.setTrust(player, tier);
        store.saveClaim(claim);
        store.log(Action.of(now(), actor, tier == null ? "untrust" : "trust", claim,
                name(player) + (tier == null ? "" : " as " + tier.key())));
    }

    /** @param value null goes back to the config default */
    public void setFlag(Claim claim, Flag flag, Boolean value, UUID actor) {
        claim.setFlag(flag, value);
        store.saveClaim(claim);
        store.log(Action.of(now(), actor, "flag", claim, flag.key + " = " + (value == null ? "default" : value)));
    }

    /** Lets {@code partner}'s blocks stand inside the owner's future claims. Returns false if nothing changed. */
    public boolean addBuildPartner(UUID owner, UUID partner) {
        PlayerRecord record = ensurePlayer(owner, null);
        if (owner.equals(partner) || !record.buildPartners.add(partner)) {
            return false;
        }
        store.savePlayer(record);
        store.log(new Action(now(), owner, "partner-add", null, null, null, null, null, name(partner)));
        return true;
    }

    public boolean removeBuildPartner(UUID owner, UUID partner) {
        PlayerRecord record = players.get(owner);
        if (record == null || !record.buildPartners.remove(partner)) {
            return false;
        }
        store.savePlayer(record);
        store.log(new Action(now(), owner, "partner-remove", null, null, null, null, null, name(partner)));
        return true;
    }

    public Set<UUID> buildPartners(UUID owner) {
        PlayerRecord record = players.get(owner);
        return record == null ? Set.of() : Collections.unmodifiableSet(record.buildPartners);
    }

    /** An owner's claim by id (a number) or by name (any case), or null. Names are unique per owner. */
    public Claim findClaim(UUID owner, String reference) {
        String ref = reference.trim();
        if (ref.startsWith("#")) {
            ref = ref.substring(1);
        }
        for (Claim claim : claimsOf(owner)) {
            if (claim.name().equalsIgnoreCase(ref)) {
                return claim;
            }
        }
        try {
            Claim claim = claims.get(Integer.parseInt(ref));
            return claim != null && claim.owner().equals(owner) ? claim : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Whether the owner already has another claim with this name. */
    public boolean nameTaken(UUID owner, String name, Claim except) {
        for (Claim claim : claimsOf(owner)) {
            if (claim != except && claim.name().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** {@code base}, or {@code base 2}, {@code base 3}... the first one the owner doesn't have yet. */
    public String uniqueName(UUID owner, String base) {
        String name = base;
        for (int i = 2; nameTaken(owner, name, null); i++) {
            name = base + " " + i;
        }
        return name;
    }

    public void rename(Claim claim, String name, UUID actor) {
        String old = claim.name();
        claim.setName(name);
        store.saveClaim(claim);
        store.log(Action.of(now(), actor, "rename", claim, old + " -> " + name));
    }

    /** Marks a claim as made by auto-claim (it then follows the build), or as taken over by hand. */
    public void setAutoClaimed(Claim claim, boolean autoClaimed) {
        if (claim.autoClaimed() != autoClaimed) {
            claim.setAutoClaimed(autoClaimed);
            store.saveClaim(claim);
        }
    }

    public void setExpiryExempt(Claim claim, boolean exempt, UUID actor) {
        claim.setExpiryExempt(exempt);
        store.saveClaim(claim);
        store.log(Action.of(now(), actor, "admin-exempt", claim, String.valueOf(exempt)));
    }

    /**
     * Moves blocks of a player's budget into someone else's claim, so it can be bigger than the owner's budget.
     *
     * @return null on success, otherwise why not
     */
    public Problem donate(Claim claim, UUID donor, int blocks) {
        if (blocks <= 0 || donor.equals(claim.owner())) {
            return Problem.NOT_ALLOWED;
        }
        if (blocks > budgetLeft(donor)) {
            return Problem.NO_BUDGET;
        }
        // Donations can only cover what the owner pays for now; they make room for the claim to grow.
        int room = claim.ownerShare();
        if (room <= 0) {
            return Problem.EMPTY;
        }
        int amount = Math.min(blocks, room);
        claim.setShare(donor, claim.share(donor) + amount);
        store.saveClaim(claim);
        store.log(Action.of(now(), donor, "donate", claim, amount + " blocks"));
        return null;
    }

    /** Takes donated blocks back. The owner has to be able to pay for them. Null on success. */
    public Problem withdraw(Claim claim, UUID donor, int blocks) {
        int share = claim.share(donor);
        if (donor.equals(claim.owner()) || share <= 0 || blocks <= 0) {
            return Problem.NOT_ALLOWED;
        }
        int amount = Math.min(blocks, share);
        if (amount > budgetLeft(claim.owner())) {
            return Problem.NO_BUDGET;
        }
        claim.setShare(donor, share - amount);
        store.saveClaim(claim);
        store.log(Action.of(now(), donor, "withdraw", claim, amount + " blocks"));
        return null;
    }

    // ------------------------------------------------------------------ town zones

    public Collection<TownZone> towns() {
        return Collections.unmodifiableCollection(towns.values());
    }

    public TownZone town(String name) {
        for (TownZone town : towns.values()) {
            if (town.name().equalsIgnoreCase(name)) {
                return town;
            }
        }
        return null;
    }

    public TownZone createTown(String world, String name, int x1, int z1, int x2, int z2, UUID actor) {
        TownZone town = new TownZone(nextTownId++, world, name, Math.min(x1, x2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(z1, z2));
        towns.put(town.id(), town);
        store.saveTown(town);
        store.log(new Action(now(), actor, "admin-town-create", null, world, town.minX(), null, town.minZ(),
                name + " " + town.minX() + "," + town.minZ() + " to " + town.maxX() + "," + town.maxZ()));
        return town;
    }

    public void removeTown(TownZone town, UUID actor) {
        towns.remove(town.id());
        store.deleteTown(town.id());
        store.log(new Action(now(), actor, "admin-town-remove", null, town.world(), null, null, null, town.name()));
    }

    // ------------------------------------------------------------------ expiry and bans

    /** True if the player hasn't logged in for {@code inactivityDays}. Real days, whether the server was on or not. */
    public boolean isInactive(UUID id) {
        if (config.inactivityDays <= 0) {
            return false;
        }
        PlayerRecord player = players.get(id);
        return player != null && player.lastSeen > 0 && now() - player.lastSeen > config.inactivityDays * DAY;
    }

    /** Days until the player's claims expire, or -1 if they never do. */
    public long daysUntilExpiry(UUID id) {
        PlayerRecord player = players.get(id);
        if (config.inactivityDays <= 0 || player == null || player.lastSeen <= 0) {
            return -1;
        }
        long left = player.lastSeen + config.inactivityDays * DAY - now();
        return Math.max(0, left / DAY);
    }

    /** Removes claims whose owners have been away too long. Returns what was removed. */
    public List<Claim> expireInactive(Predicate<UUID> online) {
        List<Claim> removed = new ArrayList<>();
        for (Claim claim : List.copyOf(claims.values())) {
            if (!claim.expiryExempt() && !online.test(claim.owner()) && isInactive(claim.owner())) {
                delete(claim, null, "expire");
                removed.add(claim);
            }
        }
        return removed;
    }

    /** Players who are banned for good. Their blocks no longer block others, and their claims can be removed. */
    public void setBanned(Collection<UUID> permanentlyBanned) {
        banned.clear();
        banned.addAll(permanentlyBanned);
    }

    public boolean isBanned(UUID id) {
        return banned.contains(id);
    }

    /** Removes every claim the player owns. */
    public List<Claim> deleteAllOf(UUID owner, UUID actor, String type) {
        List<Claim> removed = claimsOf(owner);
        for (Claim claim : removed) {
            delete(claim, actor, type);
        }
        return removed;
    }

    public void log(Action action) {
        store.log(action);
    }

    // ------------------------------------------------------------------ index

    private static Set<Long> chunksOf(Claim claim) {
        Set<Long> chunks = new HashSet<>();
        for (Region.Box box : claim.region().boxes()) {
            for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
                for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                    chunks.add(Cells.key(cx, cz));
                }
            }
        }
        return chunks;
    }

    private void addToIndex(Claim claim) {
        Map<Long, List<Claim>> chunks = index.computeIfAbsent(claim.world(), w -> new HashMap<>());
        for (long chunk : chunksOf(claim)) {
            chunks.computeIfAbsent(chunk, c -> new ArrayList<>(1)).add(claim);
        }
    }

    private void removeFromIndex(Claim claim) {
        Map<Long, List<Claim>> chunks = index.get(claim.world());
        if (chunks == null) {
            return;
        }
        for (long chunk : chunksOf(claim)) {
            List<Claim> list = chunks.get(chunk);
            if (list != null) {
                list.remove(claim);
                if (list.isEmpty()) {
                    chunks.remove(chunk);
                }
            }
        }
    }
}
