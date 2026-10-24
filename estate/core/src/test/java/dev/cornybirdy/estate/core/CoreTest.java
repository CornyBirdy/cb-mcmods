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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cells, outlines, drafts, the placed-block index, auto-claim planning and the config file. */
class CoreTest {

    static final String WORLD = "minecraft:overworld";

    @Test
    void keysRoundTrip() {
        for (int[] c : new int[][]{{0, 0}, {-1, 5}, {123456, -98765}, {-30000000 / 16, 30000000 / 16}}) {
            long key = Cells.key(c[0], c[1]);
            assertEquals(c[0], Cells.x(key));
            assertEquals(c[1], Cells.z(key));
        }
    }

    @Test
    void regionsAreExactAndCanonical() {
        Region square = Region.box(9, 9, 0, 0);
        assertEquals(100, square.area());
        assertEquals(1, square.union(Region.box(10, 0, 19, 9)).boxes().size());
        Region ell = square.union(Region.box(10, 0, 19, 4));
        assertEquals(150, ell.area());
        assertEquals(ell, Region.box(10, 0, 19, 4).union(square));
        Region holed = square.minus(Region.box(3, 3, 5, 5));
        assertEquals(91, holed.area());
        assertFalse(holed.contains(4, 4));
        assertTrue(holed.contains(2, 4));
        assertEquals(25, square.intersect(Region.box(5, 5, 20, 20)).area());
        assertEquals(3, square.distance(12, 5));
        assertEquals(0, square.distance(5, 5));
        assertTrue(square.within(Region.box(17, 0, 20, 0), 8));
        assertFalse(square.within(Region.box(18, 0, 20, 0), 8));
        Region cells = Region.EMPTY;
        for (int x = 0; x < 10; x++) {
            for (int z = 0; z < 10; z++) {
                cells = cells.union(Region.box(x * 8, z * 8, x * 8 + 7, z * 8 + 7));
            }
        }
        assertEquals(List.of(new Region.Box(0, 0, 79, 79)), cells.boxes());
        assertTrue(ell.contains(ell.middle()[0], ell.middle()[1]));
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
    void outlinesFollowTheBlocks() {
        List<Outline.Segment> square = Outline.of(Region.box(0, 0, 9, 9), 0);
        assertEquals(4, square.size());
        assertEquals(40, square.stream().mapToInt(Outline.Segment::length).sum());
        Region ell = Region.box(0, 0, 9, 9).union(Region.box(10, 0, 19, 4));
        assertEquals(6, Outline.of(ell, 0).size());
        assertEquals(60, Outline.of(ell, 0).stream().mapToInt(Outline.Segment::length).sum());
        Region holed = Region.box(0, 0, 9, 9).minus(Region.box(3, 3, 5, 5));
        assertEquals(52, Outline.of(holed, 0).stream().mapToInt(Outline.Segment::length).sum());
        List<Outline.Segment> buffer = Outline.of(Region.box(0, 0, 9, 9), 8);
        assertEquals(4, buffer.size());
        assertEquals(4 * 26, buffer.stream().mapToInt(Outline.Segment::length).sum());
    }

    @Test
    void draftsAreBoxesBetweenTwoCorners() {
        ClaimDraft fresh = ClaimDraft.create(WORLD, new int[]{20, 70, 9}, new int[]{0, 60, 0});
        assertEquals(21 * 10, fresh.region().area());
        assertEquals(60, fresh.minY());
        assertEquals(70, fresh.maxY());
        assertEquals(0, fresh.minX());
        assertEquals(20, fresh.maxX());

        Region shape = Region.box(0, 0, 15, 7).union(Region.box(0, 8, 7, 15));
        Claim ell = Claim.load(1, WORLD, UUID.randomUUID(), "L", 50, 80, 0, false, false, shape, Map.of(), Map.of(), Map.of());
        ClaimDraft resize = ClaimDraft.resize(ell);
        assertEquals(15, resize.maxX());
        assertEquals(shape, resize.region());
        resize.setCorner(1, -3, 50, 0);
        // The L keeps its shape and the new strip to the west is claimed in full.
        assertEquals(shape.area() + 3 * 16, resize.region().area());
        resize.setCorner(2, 7, 80, 15);
        assertEquals(Region.box(-3, 0, 7, 15), resize.region());
        resize.move(2, 1, 4);
        assertEquals(84, resize.maxY());

        ClaimDraft extend = ClaimDraft.extend(ell, new int[]{16, 70, 0}, new int[]{20, 75, 7});
        assertEquals(shape.area() + 5 * 8, extend.region().area());
        assertEquals(50, extend.minY());
        assertEquals(80, extend.maxY());

        ClaimDraft huge = ClaimDraft.create(WORLD, new int[]{-1_000_000, 0, -1_000_000}, new int[]{1_000_000, 0, 1_000_000});
        assertTrue(huge.tooBig());
    }

    @Test
    void placementIndexFindsBuildersAndDropsStaleRecords() {
        long[] now = {1000};
        PlacementStore.Memory store = new PlacementStore.Memory();
        PlacementIndex index = new PlacementIndex(store, () -> now[0], () -> 90);
        UUID bob = UUID.randomUUID();
        index.placed(WORLD, 3, 64, 3, bob, "minecraft:stone");
        assertEquals(bob, index.at(WORLD, 3, 64, 3).player());
        assertEquals(bob, index.findBuilder(WORLD, Region.box(0, 0, 3, 3), 0, 100, id -> true, (w, x, y, z, b) -> true));
        assertNull(index.findBuilder(WORLD, Region.box(0, 0, 2, 7), 0, 100, id -> true, (w, x, y, z, b) -> true));
        assertNull(index.findBuilder(WORLD, Region.box(0, 0, 7, 7), 70, 100, id -> true, (w, x, y, z, b) -> true));
        assertNull(index.findBuilder(WORLD, Region.box(0, 0, 7, 7), 0, 100, id -> true, (w, x, y, z, b) -> false));
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
        // The build's box plus the margin on every side.
        int margin = config.autoClaimMargin;
        assertEquals(Region.box(-margin, -margin, 6 + margin, 6 + margin), create.region());
        // Torches don't count.
        assertInstanceOf(AutoClaimPlanner.Nothing.class,
                AutoClaimPlanner.plan(manager, index, WORLD, alice, 0, 65, 0, b -> !b.equals("minecraft:oak_planks")));

        Claim claim = manager.create(WORLD, alice, "home", create.region(), create.minY(), create.maxY(), alice,
                PlacementOracle.NONE, false).claim();
        assertNotNull(claim);
        // Building on past the edge grows it, without leaving a gap.
        int far = 6 + margin + 5;
        index.placed(WORLD, far, 64, 0, alice, "minecraft:oak_planks");
        AutoClaimPlanner.Grow grow = assertInstanceOf(AutoClaimPlanner.Grow.class,
                AutoClaimPlanner.plan(manager, index, WORLD, alice, far, 64, 0, b -> true));
        assertTrue(grow.region().contains(far + margin, 0));
        assertTrue(grow.region().contains(far - 3, 0));
        assertFalse(grow.region().contains(far + margin + 1, 0));
        // An auto-made claim shrinks to what's left of the build, and goes when it's all gone.
        manager.setAutoClaimed(claim, true);
        AutoClaimPlanner.Fit fit = AutoClaimPlanner.refit(manager, index, claim, b -> true);
        assertEquals(claim.region(), fit.region());
        for (int x = 0; x < 7; x++) {
            for (int z = 0; z < 7; z++) {
                if (x > 1 || z > 1) {
                    index.removed(WORLD, x, 64, z);
                }
            }
        }
        index.removed(WORLD, 0, 65, 0);
        fit = AutoClaimPlanner.refit(manager, index, claim, b -> true);
        assertEquals(Region.box(-margin, -margin, 1 + margin, 1 + margin), fit.region());
        for (int x = 0; x < 2; x++) {
            for (int z = 0; z < 2; z++) {
                index.removed(WORLD, x, 64, z);
            }
        }
        assertNull(AutoClaimPlanner.refit(manager, index, claim, b -> true));
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
        assertNotNull(config.set("baseBudget", "-1"));
        assertNotNull(config.set("bufferDistance", "lots"));
        assertNotNull(config.set("nope", "1"));
        assertNull(config.set("flagDefaults.explosions", "off"));
        assertNotNull(config.set("flagDefaults.pvp", "on"));
        assertNull(config.set("trustTiers.access", "interact,containers"));
        assertTrue(config.tierAllows(TrustTier.ACCESS, Permission.CONTAINERS));
        ConfigFile.save(file, config);

        // An older file without newer settings keeps its values and gets the new ones added.
        String text = Files.readString(file).replace("\"inactivityDays\": 30,", "");
        Files.writeString(file, text);
        EstateConfig loaded = ConfigFile.load(file);
        assertEquals(100, loaded.baseBudget);
        assertFalse(loaded.flagDefault(Flag.EXPLOSIONS));
        assertEquals(30, loaded.inactivityDays);
        assertTrue(Files.readString(file).contains("inactivityDays"));

        Files.writeString(file, Files.readString(file).replace("\"configVersion\": 3", "\"configVersion\": 99"));
        assertThrows(java.io.IOException.class, () -> ConfigFile.load(file));
    }

    @Test
    void oldConfigsCountBudgetsInBlocks(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("estate.json");
        Files.writeString(file, "{\"configVersion\": 1, \"cellSize\": 8, \"baseBudget\": 100, \"maxTotalBudget\": 100,"
                + " \"maxGrantedBudget\": 256, \"newClaimSize\": 3, \"bufferDistance\": 5}");
        EstateConfig config = ConfigFile.load(file);
        assertEquals(6400, config.baseBudget);
        assertEquals(6400, config.maxTotalBudget);
        assertEquals(16384, config.maxGrantedBudget);
        assertEquals(5, config.bufferDistance);
        String saved = Files.readString(file);
        assertFalse(saved.contains("cellSize"));
        assertFalse(saved.contains("newClaimSize"));
        assertTrue(Files.exists(dir.resolve("estate.json.v1.bak")));
    }

    @Test
    void pvpAndMobFlagsAreDropped(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("estate.json");
        Files.writeString(file, "{\"configVersion\": 2, \"flagDefaults\": {\"pvp\": true, \"mobGriefing\": false,"
                + " \"explosions\": false}, \"ownerEditableFlags\": [\"pvp\", \"mobGriefing\"]}");
        EstateConfig config = ConfigFile.load(file);
        assertFalse(config.flagDefault(Flag.EXPLOSIONS));
        assertEquals(EstateConfig.defaultEditableFlags(), config.ownerEditableFlags);
        assertTrue(config.ownerMayEdit(Flag.FIRE_SPREAD));
        assertNull(Flag.byKey("pvp"));
        String saved = Files.readString(file);
        assertFalse(saved.contains("pvp"));
        assertFalse(saved.contains("mobGriefing"));
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

    @Test
    void unchangedMessagesFollowNewDefaults(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("messages.json");
        ConfigFile.loadMessages(file);
        Path snapshot = dir.resolve(".messages.json.defaults");
        assertTrue(Files.exists(snapshot));
        // Pretend an older version wrote different defaults: one message the admin kept, one they changed.
        String text = Files.readString(file).replace(ConfigFile.bundledMessages().get("claim-gone"), "old default")
                .replace(ConfigFile.bundledMessages().get("draft-none"), "admin wording");
        Files.writeString(file, text);
        Files.writeString(snapshot, Files.readString(snapshot)
                .replace(ConfigFile.bundledMessages().get("claim-gone"), "old default")
                .replace(ConfigFile.bundledMessages().get("draft-none"), "older default"));
        Map<String, String> messages = ConfigFile.loadMessages(file);
        assertEquals(ConfigFile.bundledMessages().get("claim-gone"), messages.get("claim-gone"));
        assertEquals("admin wording", messages.get("draft-none"));
        assertFalse(messages.containsKey("_version"));

        // A file from before messages had a version is backed up and replaced.
        Files.writeString(file, "{\"claim-gone\": \"very old\"}");
        messages = ConfigFile.loadMessages(file);
        assertEquals(ConfigFile.bundledMessages().get("claim-gone"), messages.get("claim-gone"));
        assertTrue(Files.exists(dir.resolve("messages.json.v1.bak")));
    }
}
