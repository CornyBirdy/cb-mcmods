package dev.cornybirdy.estate;

import dev.cornybirdy.estate.core.Claim;
import dev.cornybirdy.estate.core.ClaimManager;
import dev.cornybirdy.estate.core.ConfigFile;
import dev.cornybirdy.estate.core.Denial;
import dev.cornybirdy.estate.core.EstateConfig;
import dev.cornybirdy.estate.core.PlacementIndex;
import dev.cornybirdy.estate.core.PlacementOracle;
import dev.cornybirdy.estate.core.PlayerRecord;
import dev.cornybirdy.estate.core.storage.SqliteStorage;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserBanListEntry;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Everything Estate holds while a server runs. Created when the server starts, closed when it stops. */
final class EstateRuntime {

    static final Logger LOGGER = LoggerFactory.getLogger("Estate");

    private static final long WARNING_COOLDOWN_MILLIS = 2500;
    private static final int ENTER_CHECK_TICKS = 10;
    private static final int BAN_CHECK_TICKS = 20 * 60;
    private static final int EXPIRY_CHECK_TICKS = 20 * 60 * 60;
    private static final int SEEN_UPDATE_TICKS = 20 * 60 * 10;
    private static final int PURGE_TICKS = 20 * 60 * 60 * 24;

    final MinecraftServer server;
    final SqliteStorage storage;
    final ClaimManager manager;
    final PlacementIndex placements;
    final Borders borders;
    final AutoClaimer autoClaimer;
    final ClaimTool claimTool;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Path configFile;
    private final Path messagesFile;
    EstateConfig config;
    Msg msg;
    BlockKinds kinds;

    EstateRuntime(MinecraftServer server) {
        this.server = server;
        Path configDir = FabricLoader.getInstance().getConfigDir();
        this.configFile = configDir.resolve("estate.json");
        this.messagesFile = configDir.resolve("estate-messages.json");
        try {
            this.config = ConfigFile.load(configFile);
            this.msg = new Msg(ConfigFile.loadMessages(messagesFile));
        } catch (Exception e) {
            // Refuse to start rather than run with protection settings nobody chose.
            throw new IllegalStateException("Estate could not read " + configFile + " or " + messagesFile
                    + ". Fix or delete the file and start again.", e);
        }
        Path database = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("estate").resolve("estate.db");
        this.storage = new SqliteStorage(database);
        this.manager = new ClaimManager(storage, config, () -> System.currentTimeMillis() / 1000);
        this.placements = new PlacementIndex(storage, manager::now, () -> config.placedBlockRetentionDays);
        this.kinds = new BlockKinds(config.accessBlocks, config.containerBlocks, config.autoClaimIgnoredBlocks);
        this.borders = new Borders(this);
        this.autoClaimer = new AutoClaimer(this);
        this.claimTool = new ClaimTool(this);
        LOGGER.info("Estate loaded {} claims", manager.claims().size());
    }

    void close() {
        storage.close();
    }

    /** Re-reads both config files. Returns null on success, otherwise what went wrong; the old files stay in use. */
    String reload() {
        try {
            EstateConfig loaded = ConfigFile.load(configFile);
            Msg loadedMsg = new Msg(ConfigFile.loadMessages(messagesFile));
            applyConfig(loaded);
            msg = loadedMsg;
            return null;
        } catch (Exception e) {
            LOGGER.error("Could not reload the Estate config", e);
            return e.getMessage() == null ? e.toString() : e.getMessage();
        }
    }

    /** Saves the config after a live change from /land admin set or the settings menu. Null on success. */
    String saveConfig() {
        applyConfig(config);
        try {
            ConfigFile.save(configFile, config);
            return null;
        } catch (Exception e) {
            LOGGER.error("Could not save {}", configFile, e);
            return e.getMessage();
        }
    }

    private void applyConfig(EstateConfig loaded) {
        loaded.refresh();
        config = loaded;
        manager.setConfig(loaded);
        kinds = new BlockKinds(loaded.accessBlocks, loaded.containerBlocks, loaded.autoClaimIgnoredBlocks);
    }

    // ------------------------------------------------------------------ players

    Session session(ServerPlayer player) {
        return sessions.computeIfAbsent(player.getUUID(), id -> new Session());
    }

    void joined(ServerPlayer player) {
        PlayerRecord record = manager.seen(player.getUUID(), player.getGameProfile().name());
        Session session = session(player);
        Claim here = claimAt(player.level(), player.blockPosition());
        session.currentClaim = here == null ? 0 : here.id();
        // A claim tool left over from a crash or a stop is useless now.
        ClaimTool.removeTools(player);
        if (record.firstSeen == record.lastSeen) {
            LOGGER.info("First visit from {}; auto-claim is {}", record.displayName(), record.autoClaim(config) ? "on" : "off");
        }
    }

    void left(ServerPlayer player) {
        manager.seen(player.getUUID(), player.getGameProfile().name());
        ClaimTool.removeTools(player);
        sessions.remove(player.getUUID());
    }

    boolean isAdmin(ServerPlayer player) {
        return player.checkPermission(Estate.ADMIN_PERMISSION, net.minecraft.server.permissions.PermissionLevel.GAMEMASTERS);
    }

    /** Admin bypass mode: may build and open everything everywhere. */
    boolean bypassing(ServerPlayer player) {
        Session session = sessions.get(player.getUUID());
        return session != null && session.bypass;
    }

    // ------------------------------------------------------------------ worlds

    static String world(Level level) {
        return level.dimension().identifier().toString();
    }

    ServerLevel level(String world) {
        Identifier id = Identifier.tryParse(world);
        return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    Claim claimAt(Level level, BlockPos pos) {
        return manager.claimAt(world(level), pos.getX(), pos.getY(), pos.getZ());
    }

    /** Checks placed-block records against the world, so blocks that are gone don't stop anyone claiming. */
    PlacementOracle oracle() {
        return (world, region, minY, maxY, counts) -> placements.findBuilder(world, region, minY, maxY,
                counts, (w, x, y, z, block) -> {
                    ServerLevel level = level(w);
                    BlockPos pos = new BlockPos(x, y, z);
                    if (level == null || !level.isLoaded(pos)) {
                        return null;
                    }
                    return BlockKinds.id(level.getBlockState(pos).getBlock()).equals(block);
                });
    }

    // ------------------------------------------------------------------ messages

    void send(ServerPlayer player, String key, Object... pairs) {
        player.sendSystemMessage(msg.get(key, pairs));
    }

    void actionBar(ServerPlayer player, String key, Object... pairs) {
        player.sendOverlayMessage(msg.get(key, pairs));
    }

    /** Sends a message at most once every couple of seconds per kind; for things that can happen every tick. */
    void warn(ServerPlayer player, String key, Object... pairs) {
        Session session = session(player);
        long now = System.currentTimeMillis();
        Long last = session.lastWarning.get(key);
        if (last == null || now - last >= WARNING_COOLDOWN_MILLIS) {
            session.lastWarning.put(key, now);
            player.sendOverlayMessage(msg.get(key, pairs));
        }
    }

    /** Tells a player why they can't do something here. */
    void deny(ServerPlayer player, Denial denial) {
        Claim claim = denial.claim();
        String owner = manager.name(claim.owner());
        switch (denial.kind()) {
            case CLAIM -> warn(player, "denied-claim", "owner", owner, "claim", claim.name());
            case BUFFER -> warn(player, "denied-buffer", "owner", owner);
            case HEIGHT_CAP -> warn(player, "denied-height-cap", "owner", owner,
                    "height", manager.groundLevel(claim) + config.bufferHeightCap);
        }
    }

    String name(UUID id) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        return online != null ? online.getGameProfile().name() : manager.name(id);
    }

    /** Finds a player by name: online, known to Estate, or in the server's name cache. */
    NameAndId findPlayer(String name) {
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) {
            return online.nameAndId();
        }
        PlayerRecord known = manager.playerByName(name);
        if (known != null) {
            return new NameAndId(known.id, known.displayName());
        }
        return server.services().nameToIdCache().get(name).orElse(null);
    }

    void notifyOwner(UUID owner, Component message) {
        ServerPlayer player = server.getPlayerList().getPlayer(owner);
        if (player != null) {
            player.sendSystemMessage(message);
        }
    }

    // ------------------------------------------------------------------ ticking

    void tick() {
        long tick = server.getTickCount();
        if (tick % ENTER_CHECK_TICKS == 0) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                enterLeave(player);
            }
        }
        borders.tick(tick);
        claimTool.tick(tick);
        if (tick % BAN_CHECK_TICKS == 0) {
            checkBans();
        }
        if (tick % SEEN_UPDATE_TICKS == 0) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                manager.seen(player.getUUID(), player.getGameProfile().name());
            }
        }
        // First run a minute after start, so admins can still exempt claims after a long downtime.
        if (tick % EXPIRY_CHECK_TICKS == 20 * 60) {
            expire();
        }
        if (tick % PURGE_TICKS == 20 * 90) {
            purgePlacements();
        }
    }

    private void enterLeave(ServerPlayer player) {
        Session session = session(player);
        Claim here = claimAt(player.level(), player.blockPosition());
        int id = here == null ? 0 : here.id();
        if (id == session.currentClaim) {
            return;
        }
        Claim previous = manager.claim(session.currentClaim);
        session.currentClaim = id;
        if (!config.enterLeaveMessages) {
            return;
        }
        if (here != null) {
            if (here.owner().equals(player.getUUID())) {
                actionBar(player, "enter-own", "claim", here.name());
            } else {
                actionBar(player, "enter-other", "owner", manager.name(here.owner()), "claim", here.name());
            }
        } else if (previous != null) {
            if (previous.owner().equals(player.getUUID())) {
                actionBar(player, "leave-own", "claim", previous.name());
            } else {
                actionBar(player, "leave-other", "owner", manager.name(previous.owner()), "claim", previous.name());
            }
        }
    }

    /** Players banned for good lose their claims. Temporary bans just let inactivity expiry run its course. */
    void checkBans() {
        List<UUID> permanent = new ArrayList<>();
        var bans = server.getPlayerList().getBans();
        for (PlayerRecord player : knownOwnersAndPlayers()) {
            UserBanListEntry entry = bans.get(new NameAndId(player.id, player.name));
            if (entry != null && entry.getExpires() == null) {
                permanent.add(player.id);
            }
        }
        manager.setBanned(permanent);
        if (!config.unclaimOnPermanentBan) {
            return;
        }
        for (UUID id : permanent) {
            List<Claim> removed = manager.deleteAllOf(id, null, "ban");
            if (!removed.isEmpty()) {
                LOGGER.info("Removed {} claim(s) of {} because they are permanently banned", removed.size(), manager.name(id));
            }
        }
    }

    private List<PlayerRecord> knownOwnersAndPlayers() {
        Map<UUID, PlayerRecord> found = new HashMap<>();
        for (Claim claim : manager.claims()) {
            PlayerRecord record = manager.player(claim.owner());
            found.put(claim.owner(), record != null ? record : new PlayerRecord(claim.owner(), ""));
        }
        return new ArrayList<>(found.values());
    }

    /** Removes claims of players who haven't logged in for {@code inactivityDays}. Returns how many. */
    int expire() {
        List<Claim> removed = manager.expireInactive(id -> server.getPlayerList().getPlayer(id) != null);
        for (Claim claim : removed) {
            LOGGER.info("Claim #{} '{}' of {} expired ({} blocks): owner inactive for over {} days", claim.id(), claim.name(),
                    manager.name(claim.owner()), claim.size(), config.inactivityDays);
        }
        return removed.size();
    }

    private void purgePlacements() {
        int days = config.placedBlockRetentionDays;
        if (days <= 0) {
            return;
        }
        storage.purgePlacements(manager.now() - days * 86_400L).whenComplete((count, error) -> server.execute(() -> {
            if (error != null) {
                LOGGER.error("Could not clean up old placed-block records", error);
            } else if (count > 0) {
                placements.clearCache();
                LOGGER.info("Removed {} placed-block records older than {} days", count, days);
            }
        }));
    }
}
