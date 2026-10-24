package dev.cornybirdy.estate.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cells, outlines, drafts, the placed-block index, auto-claim planning and the config file. */
class CoreTest {

    static final String WORLD = "minecraft:overworld";

    @Test
    void cellsRoundTripAndMeasure() {
        for (int[] c : new int[][]{{0, 0}, {-1, 5}, {123456, -98765}, {-30000000 / 8, 30000000 / 8}}) {
            long key = Cells.key(c[0], c[1]);
            assertEquals(c[0], Cells.x(key));
            assertEquals(c[1], Cells.z(key));
        }
        assertEquals(-1, Cells.of(-1, 8));
        assertEquals(0, Cells.distance(3, 3, Cells.key(0, 0), 8));
        assertEquals(1, Cells.distance(8, 3, Cells.key(0, 0), 8));
        assertEquals(1, Cells.distance(Cells.key(0, 0), Cells.key(1, 1), 8));
        assertEquals(9, Cells.distance(Cells.key(0, 0), Cells.key(2, 0), 8));
    }

    @Test
    void packedPositionsRoundTrip() {
        for (int[] p : new int[][]{{0, 0, 0}, {-1, -64, -1}, {29_999_999, 319, -29_999_999}, {5, 2000, -7}}) {
            long pos = PlacementIndex.pack(p[0], p[1], p[2]);
            assertEquals(p[0], PlacementIndex.x(pos));
            assertEquals(p[1], PlacementIndex.y(pos));
            assertEquals(p[2], PlacementIndex.z(pos));
        }
    }

    @Test
    void outlinesMergeTouchingCells() {
        assertEquals(4, Outline.of(Set.of(Cells.key(0, 0)), 8, 0).size());
        List<Outline.Segment> pair = Outline.of(Set.of(Cells.key(0, 0), Cells.key(1, 0)), 8, 0);
        assertEquals(4, pair.size());
        assertEquals(48, pair.stream().mapToInt(Outline.Segment::length).sum());
        Set<Long> ell = Set.of(Cells.key(0, 0), Cells.key(1, 0), Cells.key(0, 1));
        assertEquals(6, Outline.of(ell, 8, 0).size());
        List<Outline.Segment> buffer = Outline.of(Set.of(Cells.key(0, 0)), 8, 8);
        assertEquals(4, buffer.size());
        assertEquals(4 * 24, buffer.stream().mapToInt(Outline.Segment::length).sum());
    }

    @Test
    void draftsAreBoxesBetweenTwoCorners() {
        ClaimDraft fresh = ClaimDraft.create(WORLD, 8, new int[]{20, 70, 9}, new int[]{0, 60, 0});
        assertEquals(6, fresh.cells().size());
        assertEquals(60, fresh.minY());
        assertEquals(70, fresh.maxY());
        assertEquals(0, fresh.minX());
        assertEquals(20, fresh.maxX());

        Claim ell = Claim.load(1, WORLD, java.util.UUID.randomUUID(), "L", 50, 80, 0, false,
                Set.of(Cells.key(0, 0), Cells.key(1, 0), Cells.key(0, 1)), Map.of(), Map.of(), Map.of());
        ClaimDraft resize = ClaimDraft.resize(ell, 8);
        assertEquals(15, resize.maxX());
        assertEquals(ell.cells(), resize.cells());
        resize.setCorner(1, -8, 50, 0);
        // The L keeps its shape and the new column to the west is claimed in full.
        assertEquals(5, resize.cells().size());
        resize.setCorner(2, 7, 80, 15);
        assertEquals(Set.of(Cells.key(-1, 0), Cells.key(-1, 1), Cells.key(0, 0), Cells.key(0, 1)), resize.cells());
        resize.move(2, 1, 4);
        assertEquals(84, resize.maxY());

        ClaimDraft extend = ClaimDraft.extend(ell, 8, new int[]{16, 70, 0}, new int[]{23, 75, 7});
        assertEquals(4, extend.cells().size());
        assertEquals(50, extend.minY());
        assertEquals(80, extend.maxY());

        ClaimDraft huge = ClaimDraft.create(WORLD, 8, new int[]{-1_000_000, 0, -1_000_000}, new int[]{1_000_000, 0, 1_000_000});
        assertTrue(huge.tooBig());
        assertTrue(huge.cells().isEmpty());
    }

    @Test
    void placementIndexFindsBuildersAndDropsStaleRecords() {
        long[] now = {1000};
        PlacementStore.Memory store = new PlacementStore.Memory();
        PlacementIndex index = new PlacementIndex(store, () -> now[0], () -> 90);
        UUID bob = UUID.randomUUID();
        index.placed(WORLD, 3, 64, 3, bob, "minecraft:stone");
        assertEquals(bob, index.at(WORLD, 3, 64, 3).player());
        assertEquals(bob, index.findBuilder(WORLD, Set.of(Cells.key(0, 0)), 8, 0, 100, id -> true, (w, x, y, z, b) -> true));
        assertNull(index.findBuilder(WORLD, Set.of(Cells.key(0, 0)), 8, 70, 100, id -> true, (w, x, y, z, b) -> true));
        assertNull(index.findBuilder(WORLD, Set.of(Cells.key(0, 0)), 8, 0, 100, id -> true, (w, x, y, z, b) -> false));
        index.clearCache();
        assertNull(index.at(WORLD, 3, 64, 3));

        index.placed(WORLD, 3, 64, 3, bob, "minecraft:stone");
        now[0] += 91L * 86_400;
        assertNull(index.at(WORLD, 3, 64, 3));
    }

    @Test
    void autoClaimNeedsAClusterNotAPillar() {
        EstateConfig config = new EstateConfig();
        config.refresh();
        long now = 1000;
        ClaimManager manager = new ClaimManager(ClaimStore.MEMORY, config, () -> now);
        PlacementIndex index = new PlacementIndex(new PlacementStore.Memory(), () -> now, () -> 90);
        UUID alice = UUID.randomUUID();
        manager.seen(alice, "Alice");
        manager.setAutoClaim(alice, true);

        // A 60 block pillar: never more than 33 blocks within 16 of the top.
        for (int y = 64; y < 124; y++) {
            index.placed(WORLD, 100, y, 100, alice, "minecraft:dirt");
        }
        assertInstanceOf(AutoClaimPlanner.Nothing.class,
                AutoClaimPlanner.plan(manager, index, WORLD, alice, 100, 123, 100, b -> true));

        // A 7x7 floor and a bit of wall: a real build.
        List<int[]> placed = new ArrayList<>();
        for (int x = 0; x < 7; x++) {
            for (int z = 0; z < 7; z++) {
                index.placed(WORLD, x, 64, z, alice, "minecraft:oak_planks");
                placed.add(new int[]{x, 64, z});
            }
        }
        index.placed(WORLD, 0, 65, 0, alice, "minecraft:oak_planks");
        AutoClaimPlanner.Plan plan = AutoClaimPlanner.plan(manager, index, WORLD, alice, 0, 65, 0, b -> true);
        AutoClaimPlanner.Create create = assertInstanceOf(AutoClaimPlanner.Create.class, plan);
        assertEquals(50, create.blocks());
        assertEquals(54, create.minY());
        assertEquals(75, create.maxY());
        // Torches don't count.
        assertInstanceOf(AutoClaimPlanner.Nothing.class,
                AutoClaimPlanner.plan(manager, index, WORLD, alice, 0, 65, 0, b -> !b.equals("minecraft:oak_planks")));

        Claim claim = manager.create(WORLD, alice, "home", create.cells(), create.minY(), create.maxY(), alice,
                PlacementOracle.NONE, false).claim();
        assertNotNull(claim);
        // Building on past the edge grows it.
        index.placed(WORLD, 9, 64, 0, alice, "minecraft:oak_planks");
        AutoClaimPlanner.Grow grow = assertInstanceOf(AutoClaimPlanner.Grow.class,
                AutoClaimPlanner.plan(manager, index, WORLD, alice, 9, 64, 0, b -> true));
        assertTrue(grow.cells().contains(Cells.key(1, 0)));
        // Building high above it moves the top.
        AutoClaimPlanner.Grow up = assertInstanceOf(AutoClaimPlanner.Grow.class,
                AutoClaimPlanner.plan(manager, index, WORLD, alice, 2, 90, 2, b -> true));
        assertTrue(up.verticalOnly());
        assertEquals(100, up.maxY());

        // With auto-claim off the player gets the tip instead.
        UUID bob = UUID.randomUUID();
        manager.seen(bob, "Bob");
        for (int x = 200; x < 207; x++) {
            for (int z = 200; z < 208; z++) {
                index.placed(WORLD, x, 64, z, bob, "minecraft:stone");
            }
        }
        assertInstanceOf(AutoClaimPlanner.Prompt.class,
                AutoClaimPlanner.plan(manager, index, WORLD, bob, 203, 64, 203, b -> true));
    }

    @Test
    void configKeepsAdminValuesAndValidates(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("estate.json");
        EstateConfig config = ConfigFile.load(file);
        assertTrue(Files.exists(file));
        assertNull(config.set("baseBudget", "100"));
        assertNotNull(config.set("cellSize", "1"));
        assertNotNull(config.set("bufferDistance", "lots"));
        assertNotNull(config.set("nope", "1"));
        assertNull(config.set("flagDefaults.pvp", "on"));
        assertNull(config.set("trustTiers.access", "interact,containers"));
        assertTrue(config.tierAllows(TrustTier.ACCESS, Permission.CONTAINERS));
        ConfigFile.save(file, config);

        // An older file without newer settings keeps its values and gets the new ones added.
        String text = Files.readString(file).replace("\"inactivityDays\": 30,", "");
        Files.writeString(file, text);
        EstateConfig loaded = ConfigFile.load(file);
        assertEquals(100, loaded.baseBudget);
        assertTrue(loaded.flagDefault(Flag.PVP));
        assertEquals(30, loaded.inactivityDays);
        assertTrue(Files.readString(file).contains("inactivityDays"));

        Files.writeString(file, Files.readString(file).replace("\"configVersion\": 1", "\"configVersion\": 99"));
        assertThrows(java.io.IOException.class, () -> ConfigFile.load(file));
    }

    @Test
    void messagesAreBundled(@TempDir Path dir) throws Exception {
        Map<String, String> messages = ConfigFile.loadMessages(dir.resolve("messages.json"));
        assertTrue(messages.containsKey("no-budget"));
        for (Problem problem : Problem.values()) {
            assertTrue(messages.containsKey(problem.messageKey), problem.messageKey);
        }
        Set<String> keys = new HashSet<>(messages.keySet());
        assertTrue(keys.size() > 50);
    }
}
