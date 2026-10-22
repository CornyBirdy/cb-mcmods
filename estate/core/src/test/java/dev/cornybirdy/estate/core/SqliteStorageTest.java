package dev.cornybirdy.estate.core;

import dev.cornybirdy.estate.core.storage.SqliteStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteStorageTest {

    static final String WORLD = "minecraft:overworld";

    @Test
    void claimsPlayersAndTownsSurviveARestart(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("estate.db");
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        EstateConfig config = new EstateConfig();
        config.refresh();
        int claimId;
        try (SqliteStorage storage = new SqliteStorage(file)) {
            ClaimManager manager = new ClaimManager(storage, config, () -> 5000);
            manager.seen(alice, "Alice");
            manager.setAutoClaim(alice, true);
            manager.setBonusBudget(bob, 10, alice);
            Claim claim = manager.create(WORLD, alice, "Home", ClaimManagerTest.square(0, 0, 3), 50, 90, alice,
                    PlacementOracle.NONE, false).claim();
            claimId = claim.id();
            manager.trust(claim, bob, TrustTier.CONTAINERS, alice);
            manager.setFlag(claim, Flag.PVP, true, alice);
            manager.donate(claim, bob, 2);
            manager.createTown(WORLD, "Spawn", -10, -10, 10, 10, alice);
            assertFalse(storage.actionsForClaim(claimId, 10).get().isEmpty());
        }
        try (SqliteStorage storage = new SqliteStorage(file)) {
            ClaimManager manager = new ClaimManager(storage, config, () -> 6000);
            Claim claim = manager.claim(claimId);
            assertNotNull(claim);
            assertEquals("Home", claim.name());
            assertEquals(9, claim.size());
            assertEquals(50, claim.minY());
            assertEquals(TrustTier.CONTAINERS, claim.trust().get(bob));
            assertEquals(Boolean.TRUE, claim.flagOverrides().get(Flag.PVP));
            assertEquals(2, claim.share(bob));
            assertEquals(7, claim.ownerShare());
            assertTrue(manager.autoClaim(alice));
            assertEquals(10, manager.player(bob).bonusBudget);
            assertEquals("Alice", manager.player(alice).name);
            assertNotNull(manager.town("spawn"));
            assertEquals(8, manager.cellSize());
            assertTrue(manager.claimAt(WORLD, 20, 60, 20) != null);
        }
    }

    @Test
    void placementsAreQueuedAndReadBack(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("estate.db");
        UUID bob = UUID.randomUUID();
        long pos = PlacementIndex.pack(-5, 70, 12);
        long chunk = PlacementIndex.chunk(-5, 12);
        try (SqliteStorage storage = new SqliteStorage(file)) {
            storage.put(WORLD, pos, chunk, new PlacementStore.Placed(bob, "minecraft:stone", 1234));
            Map<Long, PlacementStore.Placed> loaded = storage.loadChunk(WORLD, chunk);
            assertEquals("minecraft:stone", loaded.get(pos).block());
            assertEquals(1, storage.placementCount(bob).get());
        }
        try (SqliteStorage storage = new SqliteStorage(file)) {
            assertEquals(bob, storage.loadChunk(WORLD, chunk).get(pos).player());
            List<SqliteStorage.PlacementRow> rows = storage.placementsBy(bob, 10).get();
            assertEquals(1, rows.size());
            assertEquals(-5, rows.get(0).x());
            storage.remove(WORLD, pos, chunk);
            assertTrue(storage.loadChunk(WORLD, chunk).isEmpty());
            storage.put(WORLD, pos, chunk, new PlacementStore.Placed(bob, "minecraft:stone", 10));
            assertEquals(1, storage.purgePlacements(100).get());
        }
    }

    @Test
    void aNewerDatabaseIsLeftAlone(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("estate.db");
        try (SqliteStorage storage = new SqliteStorage(file)) {
            storage.saveCellSize(8);
        }
        try (java.sql.Connection c = new org.sqlite.JDBC().connect("jdbc:sqlite:" + file, new java.util.Properties());
             java.sql.Statement st = c.createStatement()) {
            st.execute("PRAGMA user_version=" + (SqliteStorage.SCHEMA_VERSION + 1));
        }
        assertThrows(IllegalStateException.class, () -> new SqliteStorage(file));
        assertEquals(Set.of(), Set.of());
    }
}
