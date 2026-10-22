package dev.cornybirdy.estate.core.storage;

import dev.cornybirdy.estate.core.Action;
import dev.cornybirdy.estate.core.Cells;
import dev.cornybirdy.estate.core.Claim;
import dev.cornybirdy.estate.core.ClaimStore;
import dev.cornybirdy.estate.core.Flag;
import dev.cornybirdy.estate.core.PlacementIndex;
import dev.cornybirdy.estate.core.PlacementStore;
import dev.cornybirdy.estate.core.PlayerRecord;
import dev.cornybirdy.estate.core.TownZone;
import dev.cornybirdy.estate.core.TrustTier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Everything Estate saves, in one SQLite file ({@code <world>/estate/estate.db}). SQLite commits are atomic and the
 * file uses a write-ahead log, so pulling the plug can lose at most the last second of placed-block records and never
 * corrupts claims.
 * <p>
 * The connection is only touched by one background thread. Claim changes wait until they're committed; placed-block
 * records are queued and committed together once a second.
 * <p>
 * The schema version is kept in {@code PRAGMA user_version}. Opening an older file backs it up and upgrades it with
 * the steps in {@link #MIGRATIONS}; a file from a newer Estate is refused rather than touched.
 */
public final class SqliteStorage implements ClaimStore, PlacementStore, AutoCloseable {

    /** One entry per schema version; entry i upgrades version i to i+1. Never edit an existing entry. */
    private static final List<String[]> MIGRATIONS = List.<String[]>of(
            new String[]{
                    "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
                    "CREATE TABLE worlds (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE)",
                    "CREATE TABLE players (id INTEGER PRIMARY KEY, uuid TEXT NOT NULL UNIQUE, name TEXT NOT NULL DEFAULT '',"
                            + " first_seen INTEGER NOT NULL DEFAULT 0, last_seen INTEGER NOT NULL DEFAULT 0, auto_claim INTEGER,"
                            + " prompt_done INTEGER NOT NULL DEFAULT 0, bonus_budget INTEGER NOT NULL DEFAULT 0)",
                    "CREATE TABLE blocks (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE)",
                    "CREATE TABLE claims (id INTEGER PRIMARY KEY, world TEXT NOT NULL, owner TEXT NOT NULL, name TEXT NOT NULL,"
                            + " min_y INTEGER NOT NULL, max_y INTEGER NOT NULL, created INTEGER NOT NULL,"
                            + " expiry_exempt INTEGER NOT NULL DEFAULT 0)",
                    "CREATE TABLE claim_cells (claim_id INTEGER NOT NULL, cx INTEGER NOT NULL, cz INTEGER NOT NULL,"
                            + " PRIMARY KEY (claim_id, cx, cz)) WITHOUT ROWID",
                    "CREATE TABLE claim_trust (claim_id INTEGER NOT NULL, player TEXT NOT NULL, tier TEXT NOT NULL,"
                            + " PRIMARY KEY (claim_id, player)) WITHOUT ROWID",
                    "CREATE TABLE claim_flags (claim_id INTEGER NOT NULL, flag TEXT NOT NULL, value INTEGER NOT NULL,"
                            + " PRIMARY KEY (claim_id, flag)) WITHOUT ROWID",
                    "CREATE TABLE claim_shares (claim_id INTEGER NOT NULL, player TEXT NOT NULL, cells INTEGER NOT NULL,"
                            + " PRIMARY KEY (claim_id, player)) WITHOUT ROWID",
                    "CREATE TABLE town_zones (id INTEGER PRIMARY KEY, world TEXT NOT NULL, name TEXT NOT NULL,"
                            + " min_x INTEGER NOT NULL, min_z INTEGER NOT NULL, max_x INTEGER NOT NULL, max_z INTEGER NOT NULL)",
                    // One row per block a player placed that's still there. A full history (breaks, containers,
                    // rollback) can be added later as a separate events table.
                    "CREATE TABLE placements (world INTEGER NOT NULL, pos INTEGER NOT NULL, chunk INTEGER NOT NULL,"
                            + " player INTEGER NOT NULL, block INTEGER NOT NULL, time INTEGER NOT NULL,"
                            + " PRIMARY KEY (world, pos)) WITHOUT ROWID",
                    "CREATE INDEX placements_chunk ON placements (world, chunk)",
                    "CREATE INDEX placements_player ON placements (player, time)",
                    "CREATE INDEX placements_time ON placements (time)",
                    "CREATE TABLE actions (id INTEGER PRIMARY KEY, time INTEGER NOT NULL, actor TEXT, type TEXT NOT NULL,"
                            + " claim_id INTEGER, world TEXT, x INTEGER, y INTEGER, z INTEGER, details TEXT NOT NULL DEFAULT '')",
                    "CREATE INDEX actions_claim ON actions (claim_id, time)",
                    "CREATE INDEX actions_actor ON actions (actor, time)",
                    "CREATE INDEX actions_time ON actions (time)"
            });

    public static final int SCHEMA_VERSION = MIGRATIONS.size();

    /** A placed block found by an admin lookup. */
    public record PlacementRow(String world, int x, int y, int z, UUID player, String block, long time) {
    }

    private sealed interface Op permits Put, Remove {
    }

    private record Put(String world, long pos, long chunk, Placed placed) implements Op {
    }

    private record Remove(String world, long pos) implements Op {
    }

    private final Path file;
    private final ScheduledExecutorService thread;
    private final ConcurrentLinkedQueue<Op> pending = new ConcurrentLinkedQueue<>();
    // Only used on the database thread.
    private Connection connection;
    private final Map<String, Integer> worldIds = new HashMap<>();
    private final Map<Integer, String> worldNames = new HashMap<>();
    private final Map<UUID, Integer> playerIds = new HashMap<>();
    private final Map<Integer, UUID> playerUuids = new HashMap<>();
    private final Map<String, Integer> blockIds = new HashMap<>();
    private final Map<Integer, String> blockNames = new HashMap<>();
    private volatile boolean closed;

    /** Opens (and if needed creates or upgrades) the database. Throws if it can't be used. */
    public SqliteStorage(Path file) {
        this.file = file;
        this.thread = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread t = new Thread(runnable, "Estate database");
            t.setDaemon(true);
            return t;
        });
        call(this::open);
        thread.scheduleWithFixedDelay(() -> {
            try {
                flush();
            } catch (Exception e) {
                // Keep the queue; it's retried on the next run.
                org.slf4j.LoggerFactory.getLogger("Estate").error("Could not save placed-block records", e);
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    private Void open() throws Exception {
        Files.createDirectories(file.toAbsolutePath().getParent());
        // The driver is created directly instead of through DriverManager, which can't see it from a mod class loader.
        Driver driver = (Driver) Class.forName("org.sqlite.JDBC", true, SqliteStorage.class.getClassLoader())
                .getDeclaredConstructor().newInstance();
        connection = driver.connect("jdbc:sqlite:" + file.toAbsolutePath(), new Properties());
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=FULL");
            st.execute("PRAGMA busy_timeout=10000");
        }
        int version;
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            version = rs.next() ? rs.getInt(1) : 0;
        }
        if (version > SCHEMA_VERSION) {
            connection.close();
            throw new IllegalStateException(file + " was made by a newer version of Estate (schema " + version
                    + ", this version knows " + SCHEMA_VERSION + "). Install that version again or restore a backup;"
                    + " the file was not changed.");
        }
        if (version > 0 && version < SCHEMA_VERSION) {
            Path backup = file.resolveSibling(file.getFileName() + ".v" + version + ".bak");
            Files.deleteIfExists(backup);
            try (Statement st = connection.createStatement()) {
                st.execute("VACUUM INTO '" + backup.toAbsolutePath().toString().replace("'", "''") + "'");
            }
        }
        if (version < SCHEMA_VERSION) {
            connection.setAutoCommit(false);
            try (Statement st = connection.createStatement()) {
                for (int v = version; v < SCHEMA_VERSION; v++) {
                    for (String sql : MIGRATIONS.get(v)) {
                        st.execute(sql);
                    }
                }
                st.execute("PRAGMA user_version=" + SCHEMA_VERSION);
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
        loadIds("SELECT id, name FROM worlds", worldIds, worldNames);
        loadIds("SELECT id, name FROM blocks", blockIds, blockNames);
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("SELECT id, uuid FROM players")) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString(2));
                playerIds.put(uuid, rs.getInt(1));
                playerUuids.put(rs.getInt(1), uuid);
            }
        }
        return null;
    }

    private void loadIds(String sql, Map<String, Integer> ids, Map<Integer, String> names) throws SQLException {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                ids.put(rs.getString(2), rs.getInt(1));
                names.put(rs.getInt(1), rs.getString(2));
            }
        }
    }

    // ------------------------------------------------------------------ ClaimStore

    @Override
    public Snapshot loadAll() {
        return call(() -> {
            Map<Integer, Set<Long>> cells = new HashMap<>();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT claim_id, cx, cz FROM claim_cells")) {
                while (rs.next()) {
                    cells.computeIfAbsent(rs.getInt(1), id -> new HashSet<>()).add(Cells.key(rs.getInt(2), rs.getInt(3)));
                }
            }
            Map<Integer, Map<UUID, TrustTier>> trust = new HashMap<>();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT claim_id, player, tier FROM claim_trust")) {
                while (rs.next()) {
                    TrustTier tier = TrustTier.byKey(rs.getString(3));
                    if (tier != null) {
                        trust.computeIfAbsent(rs.getInt(1), id -> new HashMap<>()).put(UUID.fromString(rs.getString(2)), tier);
                    }
                }
            }
            Map<Integer, Map<Flag, Boolean>> flags = new HashMap<>();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT claim_id, flag, value FROM claim_flags")) {
                while (rs.next()) {
                    Flag flag = Flag.byKey(rs.getString(2));
                    if (flag != null) {
                        flags.computeIfAbsent(rs.getInt(1), id -> new EnumMap<>(Flag.class)).put(flag, rs.getInt(3) != 0);
                    }
                }
            }
            Map<Integer, Map<UUID, Integer>> shares = new HashMap<>();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT claim_id, player, cells FROM claim_shares")) {
                while (rs.next()) {
                    shares.computeIfAbsent(rs.getInt(1), id -> new HashMap<>()).put(UUID.fromString(rs.getString(2)), rs.getInt(3));
                }
            }
            List<Claim> claims = new ArrayList<>();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id, world, owner, name, min_y, max_y, created, expiry_exempt FROM claims")) {
                while (rs.next()) {
                    int id = rs.getInt(1);
                    claims.add(Claim.load(id, rs.getString(2), UUID.fromString(rs.getString(3)), rs.getString(4),
                            rs.getInt(5), rs.getInt(6), rs.getLong(7), rs.getInt(8) != 0,
                            cells.getOrDefault(id, Set.of()), trust.getOrDefault(id, Map.of()),
                            flags.getOrDefault(id, Map.of()), shares.getOrDefault(id, Map.of())));
                }
            }
            List<PlayerRecord> players = new ArrayList<>();
            try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(
                    "SELECT uuid, name, first_seen, last_seen, auto_claim, prompt_done, bonus_budget FROM players")) {
                while (rs.next()) {
                    PlayerRecord player = new PlayerRecord(UUID.fromString(rs.getString(1)), rs.getString(2));
                    player.firstSeen = rs.getLong(3);
                    player.lastSeen = rs.getLong(4);
                    int autoClaim = rs.getInt(5);
                    player.autoClaim = rs.wasNull() ? null : autoClaim != 0;
                    player.promptDone = rs.getInt(6) != 0;
                    player.bonusBudget = rs.getInt(7);
                    players.add(player);
                }
            }
            List<TownZone> towns = new ArrayList<>();
            try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(
                    "SELECT id, world, name, min_x, min_z, max_x, max_z FROM town_zones")) {
                while (rs.next()) {
                    towns.add(new TownZone(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5),
                            rs.getInt(6), rs.getInt(7)));
                }
            }
            Integer cellSize = null;
            try (PreparedStatement ps = connection.prepareStatement("SELECT value FROM meta WHERE key = 'cell_size'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        cellSize = Integer.parseInt(rs.getString(1));
                    }
                }
            }
            return new Snapshot(claims, players, towns, cellSize);
        });
    }

    @Override
    public void saveClaim(Claim claim) {
        // Copied on the calling thread, so later changes can't race with the write.
        int id = claim.id();
        String world = claim.world();
        String owner = claim.owner().toString();
        String name = claim.name();
        int minY = claim.minY();
        int maxY = claim.maxY();
        long created = claim.created();
        boolean exempt = claim.expiryExempt();
        List<Long> cells = List.copyOf(claim.cells());
        Map<UUID, TrustTier> trust = Map.copyOf(claim.trust());
        Map<Flag, Boolean> flags = Map.copyOf(claim.flagOverrides());
        Map<UUID, Integer> shares = Map.copyOf(claim.shares());
        transaction(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO claims (id, world, owner, name, min_y, max_y, created, expiry_exempt) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                            + " ON CONFLICT(id) DO UPDATE SET world = excluded.world, owner = excluded.owner, name = excluded.name,"
                            + " min_y = excluded.min_y, max_y = excluded.max_y, expiry_exempt = excluded.expiry_exempt")) {
                ps.setInt(1, id);
                ps.setString(2, world);
                ps.setString(3, owner);
                ps.setString(4, name);
                ps.setInt(5, minY);
                ps.setInt(6, maxY);
                ps.setLong(7, created);
                ps.setInt(8, exempt ? 1 : 0);
                ps.executeUpdate();
            }
            deleteChildren(id);
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO claim_cells (claim_id, cx, cz) VALUES (?, ?, ?)")) {
                for (long cell : cells) {
                    ps.setInt(1, id);
                    ps.setInt(2, Cells.x(cell));
                    ps.setInt(3, Cells.z(cell));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO claim_trust (claim_id, player, tier) VALUES (?, ?, ?)")) {
                for (Map.Entry<UUID, TrustTier> entry : trust.entrySet()) {
                    ps.setInt(1, id);
                    ps.setString(2, entry.getKey().toString());
                    ps.setString(3, entry.getValue().key());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO claim_flags (claim_id, flag, value) VALUES (?, ?, ?)")) {
                for (Map.Entry<Flag, Boolean> entry : flags.entrySet()) {
                    ps.setInt(1, id);
                    ps.setString(2, entry.getKey().key);
                    ps.setInt(3, entry.getValue() ? 1 : 0);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO claim_shares (claim_id, player, cells) VALUES (?, ?, ?)")) {
                for (Map.Entry<UUID, Integer> entry : shares.entrySet()) {
                    ps.setInt(1, id);
                    ps.setString(2, entry.getKey().toString());
                    ps.setInt(3, entry.getValue());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }

    private void deleteChildren(int id) throws SQLException {
        for (String table : List.of("claim_cells", "claim_trust", "claim_flags", "claim_shares")) {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM " + table + " WHERE claim_id = ?")) {
                ps.setInt(1, id);
                ps.executeUpdate();
            }
        }
    }

    @Override
    public void deleteClaim(int claimId) {
        transaction(() -> {
            deleteChildren(claimId);
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM claims WHERE id = ?")) {
                ps.setInt(1, claimId);
                ps.executeUpdate();
            }
        });
    }

    @Override
    public void savePlayer(PlayerRecord player) {
        UUID uuid = player.id;
        String name = player.name;
        long firstSeen = player.firstSeen;
        long lastSeen = player.lastSeen;
        Boolean autoClaim = player.autoClaim;
        boolean promptDone = player.promptDone;
        int bonus = player.bonusBudget;
        transaction(() -> {
            playerId(uuid);
            try (PreparedStatement ps = connection.prepareStatement("UPDATE players SET name = ?, first_seen = ?,"
                    + " last_seen = ?, auto_claim = ?, prompt_done = ?, bonus_budget = ? WHERE uuid = ?")) {
                ps.setString(1, name);
                ps.setLong(2, firstSeen);
                ps.setLong(3, lastSeen);
                if (autoClaim == null) {
                    ps.setNull(4, Types.INTEGER);
                } else {
                    ps.setInt(4, autoClaim ? 1 : 0);
                }
                ps.setInt(5, promptDone ? 1 : 0);
                ps.setInt(6, bonus);
                ps.setString(7, uuid.toString());
                ps.executeUpdate();
            }
        });
    }

    @Override
    public void saveTown(TownZone town) {
        transaction(() -> {
            try (PreparedStatement ps = connection.prepareStatement("INSERT OR REPLACE INTO town_zones"
                    + " (id, world, name, min_x, min_z, max_x, max_z) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                ps.setInt(1, town.id());
                ps.setString(2, town.world());
                ps.setString(3, town.name());
                ps.setInt(4, town.minX());
                ps.setInt(5, town.minZ());
                ps.setInt(6, town.maxX());
                ps.setInt(7, town.maxZ());
                ps.executeUpdate();
            }
        });
    }

    @Override
    public void deleteTown(int townId) {
        transaction(() -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM town_zones WHERE id = ?")) {
                ps.setInt(1, townId);
                ps.executeUpdate();
            }
        });
    }

    @Override
    public void saveCellSize(int cellSize) {
        transaction(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR REPLACE INTO meta (key, value) VALUES ('cell_size', ?)")) {
                ps.setString(1, String.valueOf(cellSize));
                ps.executeUpdate();
            }
        });
    }

    @Override
    public void log(Action action) {
        if (closed) {
            return;
        }
        thread.execute(() -> {
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO actions"
                    + " (time, actor, type, claim_id, world, x, y, z, details) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setLong(1, action.time());
                ps.setString(2, action.actor() == null ? null : action.actor().toString());
                ps.setString(3, action.type());
                setInteger(ps, 4, action.claimId());
                ps.setString(5, action.world());
                setInteger(ps, 6, action.x());
                setInteger(ps, 7, action.y());
                setInteger(ps, 8, action.z());
                ps.setString(9, action.details());
                ps.executeUpdate();
            } catch (SQLException e) {
                org.slf4j.LoggerFactory.getLogger("Estate").error("Could not write to the action log: {}", action, e);
            }
        });
    }

    private static void setInteger(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    // ------------------------------------------------------------------ PlacementStore

    @Override
    public Map<Long, Placed> loadChunk(String world, long chunk) {
        return call(() -> {
            flush();
            Map<Long, Placed> found = new HashMap<>();
            Integer worldId = worldIds.get(world);
            if (worldId == null) {
                return found;
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT pos, player, block, time FROM placements WHERE world = ? AND chunk = ?")) {
                ps.setInt(1, worldId);
                ps.setLong(2, chunk);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID player = playerUuids.get(rs.getInt(2));
                        if (player != null) {
                            found.put(rs.getLong(1), new Placed(player, blockNames.getOrDefault(rs.getInt(3), "?"), rs.getLong(4)));
                        }
                    }
                }
            }
            return found;
        });
    }

    @Override
    public void put(String world, long pos, long chunk, Placed placed) {
        pending.add(new Put(world, pos, chunk, placed));
    }

    @Override
    public void remove(String world, long pos, long chunk) {
        pending.add(new Remove(world, pos));
    }

    /** Commits queued placed-block records. Database thread only. */
    private void flush() throws SQLException {
        if (pending.isEmpty() || connection == null) {
            return;
        }
        connection.setAutoCommit(false);
        try (PreparedStatement put = connection.prepareStatement(
                "INSERT OR REPLACE INTO placements (world, pos, chunk, player, block, time) VALUES (?, ?, ?, ?, ?, ?)");
             PreparedStatement remove = connection.prepareStatement("DELETE FROM placements WHERE world = ? AND pos = ?")) {
            Op op;
            while ((op = pending.peek()) != null) {
                if (op instanceof Put p) {
                    put.setInt(1, worldId(p.world()));
                    put.setLong(2, p.pos());
                    put.setLong(3, p.chunk());
                    put.setInt(4, playerId(p.placed().player()));
                    put.setInt(5, blockId(p.placed().block()));
                    put.setLong(6, p.placed().time());
                    put.executeUpdate();
                } else if (op instanceof Remove r) {
                    remove.setInt(1, worldId(r.world()));
                    remove.setLong(2, r.pos());
                    remove.executeUpdate();
                }
                pending.poll();
            }
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private int worldId(String world) throws SQLException {
        return id(world, worldIds, worldNames, "worlds");
    }

    private int blockId(String block) throws SQLException {
        return id(block, blockIds, blockNames, "blocks");
    }

    private int id(String name, Map<String, Integer> ids, Map<Integer, String> names, String table) throws SQLException {
        Integer id = ids.get(name);
        if (id != null) {
            return id;
        }
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO " + table + " (name) VALUES (?)")) {
            ps.setString(1, name);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement("SELECT id FROM " + table + " WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                id = rs.getInt(1);
            }
        }
        ids.put(name, id);
        names.put(id, name);
        return id;
    }

    private int playerId(UUID uuid) throws SQLException {
        Integer id = playerIds.get(uuid);
        if (id != null) {
            return id;
        }
        try (PreparedStatement ps = connection.prepareStatement("INSERT OR IGNORE INTO players (uuid) VALUES (?)")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement("SELECT id FROM players WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                id = rs.getInt(1);
            }
        }
        playerIds.put(uuid, id);
        playerUuids.put(id, uuid);
        return id;
    }

    // ------------------------------------------------------------------ lookups and upkeep (asynchronous)

    /** The newest blocks a player placed that are still recorded. */
    public CompletableFuture<List<PlacementRow>> placementsBy(UUID player, int limit) {
        return async(() -> {
            flush();
            List<PlacementRow> rows = new ArrayList<>();
            Integer id = playerIds.get(player);
            if (id == null) {
                return rows;
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT world, pos, block, time FROM placements WHERE player = ? ORDER BY time DESC LIMIT ?")) {
                ps.setInt(1, id);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long pos = rs.getLong(2);
                        rows.add(new PlacementRow(worldNames.getOrDefault(rs.getInt(1), "?"), PlacementIndex.x(pos),
                                PlacementIndex.y(pos), PlacementIndex.z(pos), player,
                                blockNames.getOrDefault(rs.getInt(3), "?"), rs.getLong(4)));
                    }
                }
            }
            return rows;
        });
    }

    /** How many recorded blocks a player has. */
    public CompletableFuture<Integer> placementCount(UUID player) {
        return async(() -> {
            flush();
            Integer id = playerIds.get(player);
            if (id == null) {
                return 0;
            }
            try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM placements WHERE player = ?")) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    /** Log lines for a claim, newest first. */
    public CompletableFuture<List<Action>> actionsForClaim(int claimId, int limit) {
        return actions("WHERE claim_id = ?", ps -> ps.setInt(1, claimId), limit);
    }

    /** Log lines by a player, newest first. */
    public CompletableFuture<List<Action>> actionsBy(UUID actor, int limit) {
        return actions("WHERE actor = ?", ps -> ps.setString(1, actor.toString()), limit);
    }

    /** The newest log lines. */
    public CompletableFuture<List<Action>> recentActions(int limit) {
        return actions("", ps -> {
        }, limit);
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private CompletableFuture<List<Action>> actions(String where, Binder binder, int limit) {
        return async(() -> {
            List<Action> rows = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement("SELECT time, actor, type, claim_id, world, x, y, z, details"
                    + " FROM actions " + where + " ORDER BY time DESC, id DESC LIMIT " + Math.max(1, limit))) {
                binder.bind(ps);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String actor = rs.getString(2);
                        rows.add(new Action(rs.getLong(1), actor == null ? null : UUID.fromString(actor), rs.getString(3),
                                integer(rs, 4), rs.getString(5), integer(rs, 6), integer(rs, 7), integer(rs, 8),
                                rs.getString(9)));
                    }
                }
            }
            return rows;
        });
    }

    private static Integer integer(ResultSet rs, int column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    /** Deletes placed-block records older than a time (epoch seconds). Returns how many were deleted. */
    public CompletableFuture<Integer> purgePlacements(long before) {
        return async(() -> {
            flush();
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM placements WHERE time < ?")) {
                ps.setLong(1, before);
                return ps.executeUpdate();
            }
        });
    }

    /** Waits until everything queued so far is committed. */
    public void sync() {
        call(() -> {
            flush();
            return null;
        });
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        try {
            call(() -> {
                flush();
                if (connection != null) {
                    try (Statement st = connection.createStatement()) {
                        st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                    }
                    connection.close();
                }
                return null;
            });
        } finally {
            closed = true;
            thread.shutdown();
            try {
                thread.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ------------------------------------------------------------------ threading

    private interface SqlTask {
        void run() throws Exception;
    }

    private void transaction(SqlTask task) {
        call(() -> {
            connection.setAutoCommit(false);
            try {
                task.run();
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
            return null;
        });
    }

    private <T> T call(Callable<T> task) {
        if (closed) {
            throw new StorageException("The Estate database is closed", null);
        }
        try {
            return thread.submit(task).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StorageException("Interrupted while saving", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new StorageException("Database error: " + cause.getMessage(), cause);
        }
    }

    private <T> CompletableFuture<T> async(Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (closed) {
            future.completeExceptionally(new StorageException("The Estate database is closed", null));
            return future;
        }
        thread.execute(() -> {
            try {
                future.complete(task.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /** Something went wrong reading or writing the database. */
    public static final class StorageException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
