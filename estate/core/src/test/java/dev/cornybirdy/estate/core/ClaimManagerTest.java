package dev.cornybirdy.estate.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimManagerTest {

    static final String WORLD = "minecraft:overworld";
    final UUID alice = UUID.randomUUID();
    final UUID bob = UUID.randomUUID();
    long now = 1_000_000;
    EstateConfig config;
    ClaimManager manager;

    @BeforeEach
    void setUp() {
        config = new EstateConfig();
        config.refresh();
        manager = new ClaimManager(ClaimStore.MEMORY, config, () -> now);
        manager.seen(alice, "Alice");
        manager.seen(bob, "Bob");
    }

    /** The tests think in 8x8 squares ("cells") to keep the numbers readable; claims are exact to the block. */
    static final int CELL = 8;
    static final int CELL_AREA = CELL * CELL;

    static Region square(int cx, int cz, int size) {
        return Region.box(cx * CELL, cz * CELL, (cx + size) * CELL - 1, (cz + size) * CELL - 1);
    }

    static Region cell(int cx, int cz) {
        return square(cx, cz, 1);
    }

    Claim claim(UUID owner, Region region, int minY, int maxY) {
        ClaimManager.Change change = manager.create(WORLD, owner, "test", region, minY, maxY, owner, PlacementOracle.NONE, false);
        assertTrue(change.ok(), () -> "claim refused: " + change.check());
        return change.claim();
    }

    @Test
    void budgetIsFlatAndReturnedOnUnclaim() {
        assertEquals(4096, manager.budgetLimit(alice));
        Claim first = claim(alice, square(0, 0, 4), 60, 80);
        assertEquals(16 * CELL_AREA, manager.budgetUsed(alice));
        Check tooBig = manager.check(WORLD, alice, square(20, 20, 7), 60, 80, null, PlacementOracle.NONE, false);
        assertEquals(Problem.NO_BUDGET, tooBig.problem());
        manager.delete(first, alice, "unclaim");
        assertEquals(0, manager.budgetUsed(alice));
        assertTrue(manager.check(WORLD, alice, square(20, 20, 8), 60, 80, null, PlacementOracle.NONE, false).allowed());
    }

    @Test
    void adminGrantRaisesBudgetUpToTheGrantCap() {
        manager.setBonusBudget(alice, 100_000, null);
        assertEquals(16384, manager.budgetLimit(alice));
        manager.setBonusBudget(alice, 0, null);
        assertEquals(4096, manager.budgetLimit(alice));
    }

    @Test
    void claimCountIsCapped() {
        config.maxClaimsPerPlayer = 2;
        claim(alice, square(0, 0, 1), 60, 70);
        claim(alice, square(10, 0, 1), 60, 70);
        assertEquals(Problem.TOO_MANY_CLAIMS,
                manager.check(WORLD, alice, square(20, 0, 1), 60, 70, null, PlacementOracle.NONE, false).problem());
    }

    @Test
    void overlapAndBufferBlockStrangersButNotTrustedOrTowns() {
        Claim home = claim(alice, square(0, 0, 2), 60, 80);
        Check overlap = manager.check(WORLD, bob, cell(1, 1), 60, 80, null, PlacementOracle.NONE, false);
        assertEquals(Problem.OVERLAP, overlap.problem());
        assertSame(home, overlap.other());

        Check buffer = manager.check(WORLD, bob, cell(2, 0), 60, 80, null, PlacementOracle.NONE, false);
        assertEquals(Problem.BUFFER, buffer.problem());
        assertTrue(manager.check(WORLD, bob, cell(3, 0), 60, 80, null, PlacementOracle.NONE, false).allowed());

        manager.trust(home, bob, TrustTier.BUILD, alice);
        assertTrue(manager.check(WORLD, bob, cell(2, 0), 60, 80, null, PlacementOracle.NONE, false).allowed());
        manager.trust(home, bob, TrustTier.ACCESS, alice);
        assertFalse(manager.check(WORLD, bob, cell(2, 0), 60, 80, null, PlacementOracle.NONE, false).allowed());

        manager.createTown(WORLD, "spawn", -100, -100, 100, 100, null);
        assertTrue(manager.check(WORLD, bob, cell(2, 0), 60, 80, null, PlacementOracle.NONE, false).allowed());
    }

    @Test
    void undergroundBaseCanSitBelowASurfaceClaim() {
        claim(alice, square(0, 0, 2), 60, 90);
        assertTrue(manager.check(WORLD, bob, square(0, 0, 2), -40, 0, null, PlacementOracle.NONE, false).allowed());
        // Too close below: inside the surface claim's vertical buffer.
        assertEquals(Problem.BUFFER,
                manager.check(WORLD, bob, square(0, 0, 2), 30, 55, null, PlacementOracle.NONE, false).problem());
    }

    @Test
    void buildRulesInClaimAndBuffer() {
        Claim home = claim(alice, square(0, 0, 2), 60, 80);
        assertNull(manager.checkBuild(WORLD, 3, 70, 3, alice));
        assertEquals(Denial.Kind.CLAIM, manager.checkBuild(WORLD, 3, 70, 3, bob).kind());
        // x = 16 is the first block outside the claim, inside the 8-block buffer.
        assertEquals(Denial.Kind.BUFFER, manager.checkBuild(WORLD, 16, 70, 3, bob).kind());
        assertNull(manager.checkBuild(WORLD, 24, 70, 3, bob));
        // Far above the claim and its buffer.
        assertNull(manager.checkBuild(WORLD, 3, 120, 3, bob));
        assertNull(manager.checkBuild(WORLD, 16, 70, 3, alice));

        manager.trust(home, bob, TrustTier.CONTAINERS, alice);
        assertNotNull(manager.checkBuild(WORLD, 3, 70, 3, bob));
        assertNull(manager.checkAction(WORLD, 3, 70, 3, bob, Permission.CONTAINERS, Flag.CONTAINER_ACCESS));
        manager.trust(home, bob, TrustTier.BUILD, alice);
        assertNull(manager.checkBuild(WORLD, 3, 70, 3, bob));
        assertNull(manager.checkBuild(WORLD, 16, 70, 3, bob));
        assertFalse(manager.has(home, bob, Permission.TRUST));
    }

    @Test
    void heightCapLimitsTowersInBuffers() {
        config.bufferHeightCap = 12;
        Claim home = claim(alice, square(0, 0, 2), 60, 80);
        manager.trust(home, bob, TrustTier.BUILD, alice);
        int ground = manager.groundLevel(home);
        assertNull(manager.checkBuild(WORLD, 17, ground + 12, 3, bob));
        assertEquals(Denial.Kind.HEIGHT_CAP, manager.checkBuild(WORLD, 17, ground + 13, 3, bob).kind());
        assertNull(manager.checkBuild(WORLD, 17, ground + 40, 3, alice));
    }

    @Test
    void othersBlocksStopClaimingUnlessFriendOrInactive() {
        PlacementOracle bobBuiltHere = (world, region, minY, maxY, counts) -> counts.test(bob) ? bob : null;
        Check check = manager.check(WORLD, alice, square(0, 0, 2), 60, 80, null, bobBuiltHere, false);
        assertEquals(Problem.OTHERS_BLOCKS, check.problem());
        assertEquals(bob, check.blocker());

        now += 31L * 86_400;
        manager.seen(alice, "Alice");
        assertTrue(manager.check(WORLD, alice, square(0, 0, 2), 60, 80, null, bobBuiltHere, false).allowed());
    }

    @Test
    void resizeChargesAndRefundsTheOwner() {
        Claim home = claim(alice, square(0, 0, 2), 60, 80);
        assertTrue(manager.resize(home, square(0, 0, 3), 60, 80, alice, PlacementOracle.NONE, false).allowed());
        assertEquals(9 * CELL_AREA, manager.budgetUsed(alice));
        assertTrue(manager.resize(home, square(0, 0, 1), 60, 80, alice, PlacementOracle.NONE, false).allowed());
        assertEquals(CELL_AREA, manager.budgetUsed(alice));
        assertSame(home, manager.claimAt(WORLD, 1, 70, 1));
        assertNull(manager.claimAt(WORLD, 9, 70, 1));
    }

    @Test
    void pooledClaimsShareTheCost() {
        Claim town = claim(alice, square(0, 0, 8), 60, 80);
        assertEquals(64 * CELL_AREA, manager.budgetUsed(alice));
        manager.trust(town, bob, TrustTier.BUILD, alice);
        assertNull(manager.donate(town, bob, 20 * CELL_AREA));
        assertEquals(44 * CELL_AREA, manager.budgetUsed(alice));
        assertEquals(20 * CELL_AREA, manager.budgetUsed(bob));
        // Alice can now grow beyond her own budget.
        Region bigger = town.region().union(square(8, 0, 2));
        assertTrue(manager.resize(town, bigger, 60, 80, alice, PlacementOracle.NONE, false).allowed());
        assertEquals(48 * CELL_AREA, manager.budgetUsed(alice));
        assertEquals(Problem.NO_BUDGET, manager.withdraw(town, bob, 20 * CELL_AREA));
        // Shrinking below the donations gives them back.
        assertTrue(manager.resize(town, square(0, 0, 4), 60, 80, alice, PlacementOracle.NONE, false).allowed());
        assertEquals(0, manager.budgetUsed(alice));
        assertEquals(16 * CELL_AREA, manager.budgetUsed(bob));
    }

    @Test
    void claimsAreExactToTheBlock() {
        Claim home = claim(alice, Region.box(0, 0, 4, 2), 60, 70);
        assertEquals(15, home.size());
        assertSame(home, manager.claimAt(WORLD, 4, 65, 2));
        assertNull(manager.claimAt(WORLD, 5, 65, 2));
        assertNull(manager.claimAt(WORLD, -1, 65, 0));
        // The buffer starts right at the edge: 8 blocks out is too close, 9 is fine.
        assertEquals(Problem.BUFFER,
                manager.check(WORLD, bob, Region.box(12, 0, 14, 2), 60, 70, null, PlacementOracle.NONE, false).problem());
        assertTrue(manager.check(WORLD, bob, Region.box(13, 0, 15, 2), 60, 70, null, PlacementOracle.NONE, false).allowed());
        assertEquals(1, manager.claimsNear(WORLD, 12, 1, 8).size());
        assertTrue(manager.claimsNear(WORLD, 13, 1, 8).isEmpty());
        // A town zone only lifts the buffer for the part of the claim inside it.
        manager.createTown(WORLD, "market", 10, -50, 50, 50, null);
        assertTrue(manager.check(WORLD, bob, Region.box(12, 0, 14, 2), 60, 70, null, PlacementOracle.NONE, false).allowed());
        assertEquals(Problem.BUFFER,
                manager.check(WORLD, bob, Region.box(9, 0, 14, 2), 60, 70, null, PlacementOracle.NONE, false).problem());
    }

    @Test
    void inactiveClaimsExpireUnlessExempt() {
        Claim a = claim(alice, square(0, 0, 1), 60, 70);
        Claim b = claim(bob, square(10, 10, 1), 60, 70);
        manager.setExpiryExempt(b, true, null);
        now += 29L * 86_400;
        assertTrue(manager.expireInactive(id -> false).isEmpty());
        now += 2L * 86_400;
        List<Claim> removed = manager.expireInactive(id -> false);
        assertEquals(List.of(a), removed);
        assertNotNull(manager.claim(b.id()));
    }

    @Test
    void flagsUseDefaultsUntilOverridden() {
        Claim home = claim(alice, square(0, 0, 1), 60, 70);
        assertTrue(manager.flag(home, Flag.EXPLOSIONS));
        assertFalse(manager.flag(home, Flag.PVP));
        assertFalse(manager.pvpAllowed(WORLD, 1, 65, 1));
        manager.setFlag(home, Flag.PVP, true, alice);
        assertTrue(manager.pvpAllowed(WORLD, 1, 65, 1));
        assertTrue(config.ownerMayEdit(Flag.PVP));
        assertFalse(config.ownerMayEdit(Flag.EXPLOSIONS));
    }

    @Test
    void otherWorldsAreNotClaimable() {
        assertEquals(Problem.WORLD_NOT_CLAIMABLE,
                manager.check("minecraft:the_nether", alice, square(0, 0, 1), 60, 70, null, PlacementOracle.NONE, false).problem());
    }
}
