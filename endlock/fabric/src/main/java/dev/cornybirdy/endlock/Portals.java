package dev.cornybirdy.endlock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps end portals dark while the End is off. Eyes can still be put in the frames, and every portal that would be
 * lit, or was lit before, lights up the moment the End is turned back on. Portals in the End itself are left alone.
 * <p>
 * The portal blocks that were taken out are saved in {@code <world>/endlock/closed-portals.json}. Ones in chunks that
 * aren't loaded when the End is turned on light up once their chunk loads.
 */
final class Portals {

    private static final Logger LOGGER = LoggerFactory.getLogger("EndLock");
    private static final int REOPEN_CHECK_TICKS = 20;

    /** The 12 frames around a portal, relative to its north-west portal block, each facing into the portal. */
    private static final List<Frame> FRAMES = frames();

    private final MinecraftServer server;
    private final Path file;
    /** Portal blocks taken out while the End is off, by dimension id. */
    private final Map<String, Set<BlockPos>> closed = new HashMap<>();
    /** Lit portal blocks in loaded chunks, so turning the End off can close them straight away. */
    private final Map<ServerLevel, Set<BlockPos>> open = new HashMap<>();
    /** Lit portal blocks that loaded while the End is off. They are closed on the next tick. */
    private final Map<ServerLevel, Set<BlockPos>> toClose = new HashMap<>();

    Portals(MinecraftServer server) {
        this.server = server;
        file = server.getWorldPath(LevelResource.ROOT).resolve("endlock").resolve("closed-portals.json");
        load();
    }

    /** A portal block was placed or its chunk loaded. */
    void portalLoaded(ServerLevel level, BlockPos pos) {
        if (level.dimension() == Level.END) {
            return;
        }
        Map<ServerLevel, Set<BlockPos>> target = EndLock.settings().isEnabled(Feature.END) ? open : toClose;
        target.computeIfAbsent(level, l -> new HashSet<>()).add(pos.immutable());
    }

    /** A portal block was removed or its chunk unloaded. */
    void portalUnloaded(ServerLevel level, BlockPos pos) {
        Set<BlockPos> positions = open.get(level);
        if (positions != null) {
            positions.remove(pos);
        }
    }

    void tick(int tickCount) {
        boolean endEnabled = EndLock.settings().isEnabled(Feature.END);
        if (!endEnabled && (!toClose.isEmpty() || !open.isEmpty())) {
            // Also covers the End being turned off by editing the config and running /endlock reload.
            closeAll();
        } else if (endEnabled) {
            toClose.clear();
            if (!closed.isEmpty() && tickCount % REOPEN_CHECK_TICKS == 0) {
                reopen(false);
            }
        }
    }

    /** Takes out every lit portal in loaded chunks. Portals that load later are closed as they load. */
    void closeAll() {
        List<Map.Entry<ServerLevel, Set<BlockPos>>> lit = new ArrayList<>(open.entrySet());
        lit.addAll(toClose.entrySet());
        open.clear();
        toClose.clear();
        boolean changed = false;
        for (Map.Entry<ServerLevel, Set<BlockPos>> entry : lit) {
            ServerLevel level = entry.getKey();
            for (BlockPos pos : entry.getValue()) {
                if (level.isLoaded(pos) && level.getBlockState(pos).is(Blocks.END_PORTAL)) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    closed.computeIfAbsent(dimension(level), d -> new HashSet<>()).add(pos);
                    changed = true;
                }
            }
        }
        if (changed) {
            save();
        }
    }

    /**
     * Lights the closed portals in loaded chunks again. With {@code announce}, everyone hears the portal open, like
     * when the last eye goes in.
     */
    void reopen(boolean announce) {
        BlockPos sound = null;
        ServerLevel soundLevel = null;
        boolean changed = false;
        for (Iterator<Map.Entry<String, Set<BlockPos>>> dimensions = closed.entrySet().iterator(); dimensions.hasNext(); ) {
            Map.Entry<String, Set<BlockPos>> entry = dimensions.next();
            Identifier id = Identifier.tryParse(entry.getKey());
            ServerLevel level = id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
            if (level == null) {
                continue;
            }
            for (Iterator<BlockPos> positions = entry.getValue().iterator(); positions.hasNext(); ) {
                BlockPos pos = positions.next();
                if (!level.isLoaded(pos)) {
                    continue;
                }
                positions.remove();
                changed = true;
                BlockState state = level.getBlockState(pos);
                // Don't replace anything a player built there in the meantime.
                if (state.isAir() || !state.getFluidState().isEmpty()) {
                    level.setBlock(pos, Blocks.END_PORTAL.defaultBlockState(), Block.UPDATE_ALL);
                    if (sound == null) {
                        sound = pos;
                        soundLevel = level;
                    }
                }
            }
            if (entry.getValue().isEmpty()) {
                dimensions.remove();
            }
        }
        if (announce && sound != null) {
            soundLevel.globalLevelEvent(LevelEvent.SOUND_END_PORTAL_SPAWN, sound, 0);
        }
        if (changed) {
            save();
        }
    }

    /** Puts an eye in a frame like vanilla does, but remembers the portal instead of lighting it. */
    InteractionResult placeEye(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState frame, ItemStack eyes) {
        level.setBlock(pos, frame.setValue(EndPortalFrameBlock.HAS_EYE, true), Block.UPDATE_CLIENTS);
        level.updateNeighbourForOutputSignal(pos, Blocks.END_PORTAL_FRAME);
        level.levelEvent(LevelEvent.END_PORTAL_FRAME_FILL, pos, 0);
        if (!player.isCreative()) {
            eyes.shrink(1);
        }
        BlockPos corner = completedPortal(level, pos);
        if (corner != null) {
            Set<BlockPos> positions = closed.computeIfAbsent(dimension(level), d -> new HashSet<>());
            for (int x = 0; x < 3; x++) {
                for (int z = 0; z < 3; z++) {
                    positions.add(corner.offset(x, 0, z));
                }
            }
            save();
            player.sendSystemMessage(EndLock.settings().message("portal-waiting"));
        }
        return InteractionResult.SUCCESS;
    }

    /** The north-west portal block of the portal this frame completes, or null if a frame is still empty. */
    static BlockPos completedPortal(Level level, BlockPos frame) {
        for (Frame candidate : FRAMES) {
            BlockPos corner = frame.offset(-candidate.x, 0, -candidate.z);
            boolean complete = true;
            for (Frame other : FRAMES) {
                BlockState state = level.getBlockState(corner.offset(other.x, 0, other.z));
                if (!state.is(Blocks.END_PORTAL_FRAME) || !state.getValue(EndPortalFrameBlock.HAS_EYE)
                        || state.getValue(EndPortalFrameBlock.FACING) != other.facing) {
                    complete = false;
                    break;
                }
            }
            if (complete) {
                return corner;
            }
        }
        return null;
    }

    private static List<Frame> frames() {
        List<Frame> frames = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            frames.add(new Frame(i, -1, Direction.SOUTH));
            frames.add(new Frame(i, 3, Direction.NORTH));
            frames.add(new Frame(-1, i, Direction.EAST));
            frames.add(new Frame(3, i, Direction.WEST));
        }
        return List.copyOf(frames);
    }

    private static String dimension(Level level) {
        return level.dimension().identifier().toString();
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                Set<BlockPos> positions = new HashSet<>();
                for (JsonElement element : entry.getValue().getAsJsonArray()) {
                    JsonArray xyz = element.getAsJsonArray();
                    positions.add(new BlockPos(xyz.get(0).getAsInt(), xyz.get(1).getAsInt(), xyz.get(2).getAsInt()));
                }
                if (!positions.isEmpty()) {
                    closed.put(entry.getKey(), positions);
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Could not read {}, closed end portals won't light up again by themselves", file, e);
        }
    }

    private void save() {
        JsonObject root = new JsonObject();
        for (Map.Entry<String, Set<BlockPos>> entry : closed.entrySet()) {
            JsonArray positions = new JsonArray();
            for (BlockPos pos : entry.getValue()) {
                JsonArray xyz = new JsonArray();
                xyz.add(pos.getX());
                xyz.add(pos.getY());
                xyz.add(pos.getZ());
                positions.add(xyz);
            }
            root.add(entry.getKey(), positions);
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("Could not save {}", file, e);
        }
    }

    private record Frame(int x, int z, Direction facing) {
    }
}
