package dev.cornybirdy.estate.core;

import dev.cornybirdy.estate.core.storage.SqliteStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
            manager.setFlag(claim, Flag.FIRE_SPREAD, false, alice);
            manager.donate(claim, bob, 100);
            manager.setAutoClaimed(claim, true);
            manager.createTown(WORLD, "Spawn", -10, -10, 10, 10, alice);
            assertFalse(storage.actionsForClaim(claimId, 10).get().isEmpty());
        }
        try (SqliteStorage storage = new SqliteStorage(file)) {
            ClaimManager manager = new ClaimManager(storage, config, () -> 6000);
            Claim claim = manager.claim(claimId);
            assertNotNull(claim);
            assertEquals("Home", claim.name());
            assertEquals(9 * 64, claim.size());
            assertEquals(ClaimManagerTest.square(0, 0, 3), claim.region());
            assertEquals(50, claim.minY());
            assertEquals(TrustTier.CONTAINERS, claim.trust().get(bob));
            assertEquals(Boolean.FALSE, claim.flagOverrides().get(Flag.FIRE_SPREAD));
            assertEquals(100, claim.share(bob));
            assertTrue(claim.autoClaimed());
            assertEquals(9 * 64 - 100, claim.ownerShare());
            assertTrue(manager.autoClaim(alice));
            assertEquals(10, manager.player(bob).bonusBudget);
            assertEquals("Alice", manager.player(alice).name);
            assertNotNull(manager.town("spawn"));
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
        new SqliteStorage(file).close();
        try (java.sql.Connection c = new org.sqlite.JDBC().connect("jdbc:sqlite:" + file, new java.util.Properties());
             java.sql.Statement st = c.createStatement()) {
            st.execute("PRAGMA user_version=" + (SqliteStorage.SCHEMA_VERSION + 1));
        }
        assertThrows(IllegalStateException.class, () -> new SqliteStorage(file));
    }

    @Test
    void cellClaimsFromVersion1BecomeBlockExact(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("estate.db");
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        new SqliteStorage(file).close();
        // Turn the fresh file back into a version 1 file with an L-shaped claim of three 8x8 cells.
        try (java.sql.Connection c = new org.sqlite.JDBC().connect("jdbc:sqlite:" + file, new java.util.Properties());
             java.sql.Statement st = c.createStatement()) {
            st.execute("DROP TABLE claim_boxes");
            st.execute("ALTER TABLE claims DROP COLUMN auto_claimed");
            st.execute("CREATE TABLE claim_cells (claim_id INTEGER NOT NULL, cx INTEGER NOT NULL, cz INTEGER NOT NULL,"
                    + " PRIMARY KEY (claim_id, cx, cz)) WITHOUT ROWID");
            st.execute("ALTER TABLE claim_shares RENAME COLUMN blocks TO cells");
            st.execute("INSERT INTO meta (key, value) VALUES ('cell_size', '8')");
            st.execute("INSERT INTO claims (id, world, owner, name, min_y, max_y, created) VALUES (1, '" + WORLD + "', '"
                    + alice + "', 'Old', 60, 80, 0)");
            st.execute("INSERT INTO claim_cells VALUES (1, 0, 0), (1, 1, 0), (1, -1, 1)");
            st.execute("INSERT INTO claim_shares VALUES (1, '" + bob + "', 1)");
            st.execute("INSERT INTO players (uuid, name, bonus_budget) VALUES ('" + bob + "', 'Bob', 2)");
            st.execute("PRAGMA user_version=1");
        }
        EstateConfig config = new EstateConfig();
        config.refresh();
        try (SqliteStorage storage = new SqliteStorage(file)) {
            ClaimManager manager = new ClaimManager(storage, config, () -> 5000);
            Claim claim = manager.claim(1);
            assertEquals(Region.box(0, 0, 15, 7).union(Region.box(-8, 8, -1, 15)), claim.region());
            assertEquals(3 * 64, claim.size());
            assertEquals(64, claim.share(bob));
            assertEquals(128, manager.player(bob).bonusBudget);
            assertEquals(claim, manager.claimAt(WORLD, -8, 70, 15));
        }
        assertTrue(java.nio.file.Files.exists(dir.resolve("estate.db.v1.bak")));
    }
}
