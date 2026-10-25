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
    void buildPartnersCanHelpBuildWithoutBlockingTheClaim() {
        PlacementOracle bobBuiltHere = (world, region, minY, maxY, counts) -> counts.test(bob) ? bob : null;
        assertEquals(Problem.OTHERS_BLOCKS,
                manager.check(WORLD, alice, square(0, 0, 2), 60, 80, null, bobBuiltHere, false).problem());

        assertTrue(manager.addBuildPartner(alice, bob));
        assertFalse(manager.addBuildPartner(alice, bob));
        assertFalse(manager.addBuildPartner(alice, alice));
        assertTrue(manager.check(WORLD, alice, square(0, 0, 2), 60, 80, null, bobBuiltHere, false).allowed());
        // It's one-way: Bob still can't claim over Alice's blocks.
        PlacementOracle aliceBuiltHere = (world, region, minY, maxY, counts) -> counts.test(alice) ? alice : null;
        assertEquals(Problem.OTHERS_BLOCKS,
                manager.check(WORLD, bob, square(0, 0, 2), 60, 80, null, aliceBuiltHere, false).problem());

        assertTrue(manager.removeBuildPartner(alice, bob));
        assertFalse(manager.removeBuildPartner(alice, bob));
        assertEquals(Problem.OTHERS_BLOCKS,
                manager.check(WORLD, alice, square(0, 0, 2), 60, 80, null, bobBuiltHere, false).problem());
    }

    @Test
    void claimsAreFoundByNameOrIdAndNamesStayUnique() {
        Claim farm = manager.create(WORLD, alice, "Farm", cell(0, 0), 60, 80, alice, PlacementOracle.NONE, false).claim();
        Claim home = manager.create(WORLD, alice, "Home Base", cell(5, 5), 60, 80, alice, PlacementOracle.NONE, false).claim();
        Claim bobs = manager.create(WORLD, bob, "Farm", cell(10, 10), 60, 80, bob, PlacementOracle.NONE, false).claim();

        assertSame(farm, manager.findClaim(alice, "farm"));
        assertSame(home, manager.findClaim(alice, "  HOME base "));
        assertSame(home, manager.findClaim(alice, String.valueOf(home.id())));
        assertSame(home, manager.findClaim(alice, "#" + home.id()));
        assertSame(bobs, manager.findClaim(bob, "Farm"));
        assertNull(manager.findClaim(alice, String.valueOf(bobs.id())));
        assertNull(manager.findClaim(alice, "Castle"));

        assertTrue(manager.nameTaken(alice, "FARM", null));
        assertFalse(manager.nameTaken(alice, "Farm", farm));
        assertFalse(manager.nameTaken(alice, "Castle", null));
        assertEquals("Castle", manager.uniqueName(alice, "Castle"));
        assertEquals("Farm 2", manager.uniqueName(alice, "Farm"));
    }

    @Test
    void sharedClaimsAreOwnedByEveryMemberAndPaidByTheCommunity() {
        Community town = manager.createCommunity(alice, "Town");
        assertNull(manager.createCommunity(bob, "TOWN"));
        assertTrue(manager.invite(town, bob, alice));
        assertFalse(manager.invite(town, bob, alice));
        assertFalse(manager.has(claim(alice, square(0, 0, 8), 60, 80), bob, Permission.BUILD));
        assertTrue(manager.acceptInvite(town, bob));
        assertEquals(2 * 4096, manager.communityBudgetLimit(town));

        Claim big = manager.claimAt(WORLD, 1, 70, 1);
        assertEquals(0, manager.budgetLeft(alice));
        assertNull(manager.assignCommunity(big, town, alice));
        // The creator's blocks are back, and the community pays now.
        assertEquals(4096, manager.budgetLeft(alice));
        assertEquals(4096, manager.communityBudgetLeft(town));
        assertTrue(manager.has(big, bob, Permission.BUILD));
        assertTrue(manager.has(big, bob, Permission.TRUST));
        assertTrue(manager.isOwnerLike(big, bob));

        // Members' claims go right next to it without a buffer; outsiders still get one.
        assertTrue(manager.check(WORLD, bob, cell(8, 0), 60, 80, null, PlacementOracle.NONE, false).allowed());
        UUID cy = UUID.randomUUID();
        manager.seen(cy, "Cy");
        assertEquals(Problem.BUFFER, manager.check(WORLD, cy, cell(8, 0), 60, 80, null, PlacementOracle.NONE, false).problem());
        // Members' blocks don't stop each other from claiming.
        PlacementOracle bobBuiltHere = (world, region, minY, maxY, counts) -> counts.test(bob) ? bob : null;
        assertTrue(manager.check(WORLD, alice, cell(20, 20), 60, 80, null, bobBuiltHere, false).allowed());

        // Growing is limited by the community's budget, not the owner's.
        assertTrue(manager.resize(big, square(0, 0, 11), 60, 80, bob, PlacementOracle.NONE, false).allowed());
        assertEquals(Problem.NO_BUDGET, manager.resize(big, square(0, 0, 12), 60, 80, bob, PlacementOracle.NONE, false).problem());

        // Stopping sharing puts the cost back on the owner, who has to be able to pay it.
        assertEquals(Problem.NO_BUDGET, manager.assignCommunity(big, null, alice));
        assertTrue(manager.resize(big, square(0, 0, 8), 60, 80, bob, PlacementOracle.NONE, false).allowed());
        assertNull(manager.assignCommunity(big, null, alice));
        assertEquals(0, manager.budgetLeft(alice));
        assertFalse(manager.has(big, bob, Permission.BUILD));
    }

    @Test
    void leavingOrDisbandingStopsSharing() {
        Community town = manager.createCommunity(alice, "Town");
        manager.invite(town, bob, alice);
        manager.acceptInvite(town, bob);
        Claim bobs = claim(bob, square(0, 0, 2), 60, 80);
        assertNull(manager.assignCommunity(bobs, town, bob));
        assertTrue(manager.has(bobs, alice, Permission.BUILD));
        assertEquals(Problem.NOT_ALLOWED, manager.assignCommunity(bobs, manager.createCommunity(alice, "Other"), bob));

        assertFalse(manager.removeMember(town, alice, alice));
        assertTrue(manager.removeMember(town, bob, bob));
        assertEquals(0, bobs.communityId());
        assertFalse(manager.has(bobs, alice, Permission.BUILD));

        manager.invite(town, bob, alice);
        manager.acceptInvite(town, bob);
        assertNull(manager.assignCommunity(bobs, town, bob));
        manager.disband(town, alice);
        assertEquals(0, bobs.communityId());
        assertNull(manager.community(town.id()));
        assertTrue(manager.communitiesOf(bob).isEmpty());
    }

    @Test
    void everyoneElseGetsTheDefaultTierButNeverManage() {
        Claim home = claim(alice, square(0, 0, 2), 60, 80);
        UUID cy = UUID.randomUUID();
        assertFalse(manager.has(home, cy, Permission.INTERACT));
        assertTrue(manager.setDefaultTier(home, TrustTier.CONTAINERS, alice));
        assertTrue(manager.has(home, cy, Permission.INTERACT));
        assertTrue(manager.has(home, cy, Permission.CONTAINERS));
        assertFalse(manager.has(home, cy, Permission.BUILD));
        assertFalse(manager.setDefaultTier(home, TrustTier.MANAGE, alice));
        manager.trust(home, cy, TrustTier.BUILD, alice);
        assertTrue(manager.has(home, cy, Permission.BUILD));
        assertTrue(manager.setDefaultTier(home, null, alice));
        assertFalse(manager.has(home, bob, Permission.INTERACT));
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
        manager.setFlag(home, Flag.EXPLOSIONS, false, alice);
        assertFalse(manager.flag(home, Flag.EXPLOSIONS));
        manager.setFlag(home, Flag.EXPLOSIONS, null, alice);
        assertTrue(manager.flag(home, Flag.EXPLOSIONS));
        assertTrue(config.ownerMayEdit(Flag.EXPLOSIONS));
        assertFalse(config.ownerMayEdit(Flag.BLOCK_BREAK_PLACE));
    }

    @Test
    void otherWorldsAreNotClaimable() {
        assertEquals(Problem.WORLD_NOT_CLAIMABLE,
                manager.check("minecraft:the_nether", alice, square(0, 0, 1), 60, 70, null, PlacementOracle.NONE, false).problem());
    }
}
