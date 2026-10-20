package dev.cornybirdy.gatekeeper;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps nether portals dark while the Nether is off. Frames can still be lit, but the portal waits, and every portal
 * that was closed or lit in the meantime lights up the moment the Nether is turned back on. Portals in the Nether
 * itself are left alone.
 * <p>
 * One spot inside each closed portal is saved in {@code <world>/gatekeeper/closed-nether-portals.json}. Turning the
 * Nether back on lights a fire there, which makes the game light the portal like flint and steel would. Ones in
 * chunks that aren't loaded light up once their chunk loads.
 */
final class NetherPortals {

    private static final int REOPEN_CHECK_TICKS = 20;
    private static final double WAITING_MESSAGE_DISTANCE = 8;

    private final MinecraftServer server;
    private final Path file;
    /** A spot inside each portal taken out or kept dark while the Nether is off, by dimension id. */
    private final Map<String, Set<BlockPos>> closed = new HashMap<>();
    /** Loaded chunks outside the Nether, so turning it off can find the lit portals in them. */
    private final Map<ServerLevel, Map<ChunkPos, LevelChunk>> loaded = new HashMap<>();
    /** Chunks that loaded while the Nether is off. Their portals are closed on the next tick. */
    private final Map<ServerLevel, Map<ChunkPos, LevelChunk>> toScan = new HashMap<>();
    private boolean wasEnabled;

    NetherPortals(MinecraftServer server) {
        this.server = server;
        file = server.getWorldPath(LevelResource.ROOT).resolve("gatekeeper").resolve("closed-nether-portals.json");
        wasEnabled = Gatekeeper.settings().isEnabled(Feature.NETHER);
        load();
    }

    void chunkLoaded(ServerLevel level, LevelChunk chunk) {
        if (level.dimension() == Level.NETHER) {
            return;
        }
        ChunkPos key = chunk.getPos();
        loaded.computeIfAbsent(level, l -> new HashMap<>()).put(key, chunk);
        if (!Gatekeeper.settings().isEnabled(Feature.NETHER)) {
            toScan.computeIfAbsent(level, l -> new HashMap<>()).put(key, chunk);
        }
    }

    void chunkUnloaded(ServerLevel level, LevelChunk chunk) {
        ChunkPos key = chunk.getPos();
        Map<ChunkPos, LevelChunk> chunks = loaded.get(level);
        if (chunks != null) {
            chunks.remove(key);
        }
        chunks = toScan.get(level);
        if (chunks != null) {
            chunks.remove(key);
        }
    }

    void tick(int tickCount) {
        boolean enabled = Gatekeeper.settings().isEnabled(Feature.NETHER);
        if (wasEnabled && !enabled) {
            // Also covers the Nether being turned off by editing the config and running /gatekeeper reload.
            closeAll();
        } else if (!enabled && !toScan.isEmpty()) {
            Map<ServerLevel, Map<ChunkPos, LevelChunk>> chunks = new HashMap<>(toScan);
            toScan.clear();
            close(chunks);
        } else if (enabled && !closed.isEmpty() && tickCount % REOPEN_CHECK_TICKS == 0) {
            reopen();
        }
        wasEnabled = enabled;
    }

    /** Takes out every lit portal in loaded chunks. Portals in other chunks are closed as they load. */
    void closeAll() {
        wasEnabled = false;
        toScan.clear();
        close(loaded);
    }

    /** Lights the closed portals in loaded chunks again. */
    void reopen() {
        wasEnabled = true;
        boolean changed = false;
        for (Iterator<Map.Entry<String, Set<BlockPos>>> dimensions = closed.entrySet().iterator(); dimensions.hasNext(); ) {
            Map.Entry<String, Set<BlockPos>> entry = dimensions.next();
            Identifier id = Identifier.tryParse(entry.getKey());
            ServerLevel level = id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
            if (level == null) {
                continue;
            }
            // Copied, since lighting a portal changes blocks and could end up back here.
            for (BlockPos pos : List.copyOf(entry.getValue())) {
                if (!level.isLoaded(pos)) {
                    continue;
                }
                entry.getValue().remove(pos);
                changed = true;
                BlockState state = level.getBlockState(pos);
                // Don't replace anything a player built there in the meantime.
                if (state.isAir() || state.getBlock() instanceof BaseFireBlock) {
                    if (!state.isAir()) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                    level.setBlock(pos, BaseFireBlock.getState(level, pos), Block.UPDATE_ALL);
                }
            }
            if (entry.getValue().isEmpty()) {
                dimensions.remove();
            }
        }
        if (changed) {
            save();
        }
    }

    /**
     * Called by {@code BaseFireBlockMixin} when fire is about to light a portal. Returns false, and remembers the
     * portal, if it has to stay dark.
     */
    boolean mayLight(ServerLevel level, BlockPos pos) {
        if (Gatekeeper.settings().isEnabled(Feature.NETHER) || level.dimension() == Level.NETHER) {
            return true;
        }
        closed.computeIfAbsent(dimension(level), d -> new HashSet<>()).add(pos.immutable());
        save();
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
                    < WAITING_MESSAGE_DISTANCE * WAITING_MESSAGE_DISTANCE) {
                player.sendSystemMessage(Gatekeeper.settings().message("nether-portal-waiting"));
            }
        }
        return false;
    }

    private void close(Map<ServerLevel, Map<ChunkPos, LevelChunk>> chunks) {
        boolean changed = false;
        for (Map.Entry<ServerLevel, Map<ChunkPos, LevelChunk>> entry : chunks.entrySet()) {
            ServerLevel level = entry.getKey();
            for (LevelChunk chunk : List.copyOf(entry.getValue().values())) {
                for (BlockPos pos : findPortalBlocks(chunk)) {
                    // Taking out one portal block makes the game break the rest of that portal.
                    if (level.getBlockState(pos).is(Blocks.NETHER_PORTAL)) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                        closed.computeIfAbsent(dimension(level), d -> new HashSet<>()).add(pos);
                        changed = true;
                    }
                }
            }
        }
        if (changed) {
            save();
        }
    }

    private static List<BlockPos> findPortalBlocks(LevelChunk chunk) {
        List<BlockPos> found = new ArrayList<>();
        ChunkPos chunkPos = chunk.getPos();
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            // Most sections can say straight away that they have no portal blocks.
            if (section.hasOnlyAir() || !section.maybeHas(state -> state.is(Blocks.NETHER_PORTAL))) {
                continue;
            }
            int minY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (section.getBlockState(x, y, z).is(Blocks.NETHER_PORTAL)) {
                            found.add(new BlockPos(chunkPos.getMinBlockX() + x, minY + y, chunkPos.getMinBlockZ() + z));
                        }
                    }
                }
            }
        }
        return found;
    }

    private static String dimension(Level level) {
        return level.dimension().identifier().toString();
    }

    private void load() {
        PortalFile.load(file, closed);
    }

    private void save() {
        PortalFile.save(file, closed);
    }
}
