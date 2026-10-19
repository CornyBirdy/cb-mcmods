package dev.cornybirdy.gatekeeper;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns ender chests, the End and the Nether on or off while the server runs. Server-side only: vanilla clients just
 * get told no.
 * <ul>
 *     <li>Ender chests off: they can't be opened, placed or taken out of a crafting grid.</li>
 *     <li>The End off: end portals outside the End go dark (eyes can still be put in the frames) and light up
 *     again when it's turned back on. Anyone in the End is sent to the overworld spawn.</li>
 *     <li>The Nether off: the same for nether portals outside the Nether (frames can still be lit) and anyone in
 *     the Nether.</li>
 * </ul>
 */
public final class Gatekeeper implements ModInitializer {

    static final Identifier ADMIN_PERMISSION = Identifier.fromNamespaceAndPath("gatekeeper", "admin");
    static final Identifier ENDER_CHEST_BYPASS = Identifier.fromNamespaceAndPath("gatekeeper", "bypass.enderchests");
    static final Identifier END_BYPASS = Identifier.fromNamespaceAndPath("gatekeeper", "bypass.end");
    static final Identifier NETHER_BYPASS = Identifier.fromNamespaceAndPath("gatekeeper", "bypass.nether");

    private static final Logger LOGGER = LoggerFactory.getLogger("Gatekeeper");
    private static final long WARNING_COOLDOWN_MILLIS = 3000;
    private static final int DIMENSION_CHECK_TICKS = 20;

    private static final Map<UUID, Long> lastWarning = new ConcurrentHashMap<>();
    private static Settings settings;
    private static EndPortals endPortals;
    private static NetherPortals netherPortals;

    @Override
    public void onInitialize() {
        migrateFromEndLock();
        reload();

        // Created when the server starts rather than once it has started, so portals in spawn chunks are seen loading.
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            migrateFromEndLock(server);
            endPortals = new EndPortals(server);
            netherPortals = new NetherPortals(server);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            endPortals = null;
            netherPortals = null;
        });
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
            if (netherPortals != null) {
                netherPortals.chunkLoaded(level, chunk);
            }
        });
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            if (netherPortals != null) {
                netherPortals.chunkUnloaded(level, chunk);
            }
        });
        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register((blockEntity, level) -> {
            if (endPortals != null && blockEntity.getBlockState().is(Blocks.END_PORTAL)) {
                endPortals.portalLoaded(level, blockEntity.getBlockPos());
            }
        });
        ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((blockEntity, level) -> {
            if (endPortals != null) {
                endPortals.portalUnloaded(level, blockEntity.getBlockPos());
            }
        });
        UseBlockCallback.EVENT.register(Gatekeeper::onUseBlock);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (endPortals != null) {
                endPortals.tick(server.getTickCount());
                netherPortals.tick(server.getTickCount());
            }
            // Catches everyone who ends up in a closed dimension some other way: logging in there, /tp, other mods.
            if (server.getTickCount() % DIMENSION_CHECK_TICKS == 0) {
                sendPlayersOut(server);
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> lastWarning.remove(handler.getPlayer().getUUID()));
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> GatekeeperCommand.register(dispatcher));
    }

    static Settings settings() {
        return settings;
    }

    /** Reloads {@code config/gatekeeper.json}. Returns false if it couldn't be read; the old settings stay in use. */
    static boolean reload() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("gatekeeper.json");
        try {
            settings = Settings.load(file);
            return true;
        } catch (Exception e) {
            LOGGER.error("Could not read {}, using {}", file, settings == null ? "the defaults" : "the previous config", e);
            if (settings == null) {
                settings = Settings.defaults(file);
            }
            return false;
        }
    }

    /** Turns a feature on or off from {@code /gatekeeper}. Returns false if the config file couldn't be saved. */
    static boolean setEnabled(MinecraftServer server, Feature feature, boolean enabled) {
        boolean saved = true;
        try {
            settings.setEnabled(feature, enabled);
        } catch (Exception e) {
            LOGGER.error("Could not save config/gatekeeper.json, the change is lost on restart", e);
            saved = false;
        }
        if (!enabled) {
            sendPlayersOut(server);
        }
        if (endPortals != null && feature == Feature.END) {
            if (enabled) {
                endPortals.reopen(true);
            } else {
                endPortals.closeAll();
            }
        } else if (netherPortals != null && feature == Feature.NETHER) {
            if (enabled) {
                netherPortals.reopen();
            } else {
                netherPortals.closeAll();
            }
        }
        return saved;
    }

    /** Called by {@code EndPortalBlockMixin}. False stops the portal from sending the entity to the End. */
    public static boolean mayUseEndPortal(ServerLevel from, Entity entity) {
        if (settings.isEnabled(Feature.END) || from.dimension() == Level.END) {
            // The exit portal in the End always works, so nobody gets stuck there.
            return true;
        }
        if (entity instanceof ServerPlayer player) {
            if (player.checkPermission(END_BYPASS, false)) {
                return true;
            }
            warn(player, "end-blocked");
        }
        return false;
    }

    /** Called by {@code NetherPortalBlockMixin}. False stops the portal from sending the entity to the Nether. */
    public static boolean mayUseNetherPortal(ServerLevel from, Entity entity) {
        if (settings.isEnabled(Feature.NETHER) || from.dimension() == Level.NETHER) {
            return true;
        }
        if (entity instanceof ServerPlayer player) {
            if (player.checkPermission(NETHER_BYPASS, false)) {
                return true;
            }
            warn(player, "nether-blocked");
        }
        return false;
    }

    /** Called by {@code BaseFireBlockMixin}. False keeps a nether portal from lighting up. */
    public static boolean mayLightNetherPortal(ServerLevel level, BlockPos pos) {
        return netherPortals == null || netherPortals.mayLight(level, pos);
    }

    /** Called by {@code SlotMixin} for crafting result slots. */
    public static boolean mayTakeCraftedItem(Player player, ItemStack result) {
        return !result.is(Items.ENDER_CHEST) || !enderChestsBanned(player);
    }

    private static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        ItemStack held = player.getItemInHand(hand);
        BlockState clicked = level.getBlockState(hit.getBlockPos());
        if (held.is(Items.ENDER_EYE) && clicked.is(Blocks.END_PORTAL_FRAME) && !clicked.getValue(EndPortalFrameBlock.HAS_EYE)
                && !settings.isEnabled(Feature.END) && level.dimension() != Level.END && endPortals != null) {
            return endPortals.placeEye(serverPlayer, (ServerLevel) level, hit.getBlockPos(), clicked, held);
        }
        if (!enderChestsBanned(serverPlayer)) {
            return InteractionResult.PASS;
        }
        // Like vanilla: sneaking with something in either hand uses the item instead of opening the chest.
        boolean itemFirst = player.isSecondaryUseActive()
                && (!player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty());
        boolean opening = !itemFirst && clicked.is(Blocks.ENDER_CHEST);
        boolean placing = held.is(Items.ENDER_CHEST);
        if (!opening && !placing) {
            return InteractionResult.PASS;
        }
        warn(serverPlayer, "ender-chest-blocked");
        // The client already shows the chest as placed and the stack one smaller, so send the real inventory back.
        serverPlayer.containerMenu.sendAllDataToRemote();
        return InteractionResult.FAIL;
    }

    private static boolean enderChestsBanned(Player player) {
        return !settings.isEnabled(Feature.ENDER_CHESTS)
                && player instanceof ServerPlayer serverPlayer
                && !serverPlayer.checkPermission(ENDER_CHEST_BYPASS, false);
    }

    private static void sendPlayersOut(MinecraftServer server) {
        boolean endOpen = settings.isEnabled(Feature.END);
        boolean netherOpen = settings.isEnabled(Feature.NETHER);
        if (endOpen && netherOpen) {
            return;
        }
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            ResourceKey<Level> dimension = player.level().dimension();
            if (!endOpen && dimension == Level.END && !player.checkPermission(END_BYPASS, false)) {
                sendToSpawn(server, player, "sent-out-of-end", "the End");
            } else if (!netherOpen && dimension == Level.NETHER && !player.checkPermission(NETHER_BYPASS, false)) {
                sendToSpawn(server, player, "sent-out-of-nether", "the Nether");
            }
        }
    }

    private static void sendToSpawn(MinecraftServer server, ServerPlayer player, String messageKey, String dimensionName) {
        ServerLevel overworld = server.overworld();
        BlockPos spawn = overworld.getRespawnData().pos();
        int y = Math.max(spawn.getY(), overworld.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn.getX(), spawn.getZ()));
        player.teleportTo(overworld, spawn.getX() + 0.5, y, spawn.getZ() + 0.5, Set.of(), player.getYRot(), player.getXRot(), true);
        player.sendSystemMessage(settings.message(messageKey));
        LOGGER.info("Sent {} out of {} because it is disabled", player.getGameProfile().name(), dimensionName);
    }

    /** Gatekeeper used to be called EndLock. Its config is moved over on first start. */
    private static void migrateFromEndLock() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        move(configDir.resolve("endlock.json"), configDir.resolve("gatekeeper.json"));
    }

    /** Moves EndLock's saved end portals over, in {@code <world>/endlock/}. */
    private static void migrateFromEndLock(MinecraftServer server) {
        Path world = server.getWorldPath(LevelResource.ROOT);
        move(world.resolve("endlock").resolve("closed-portals.json"),
                world.resolve("gatekeeper").resolve("closed-end-portals.json"));
    }

    private static void move(Path from, Path to) {
        if (Files.exists(from) && !Files.exists(to)) {
            try {
                Files.createDirectories(to.getParent());
                Files.move(from, to);
                LOGGER.info("Moved {} to {} (EndLock is now called Gatekeeper)", from, to);
            } catch (IOException e) {
                LOGGER.error("Could not move {} to {}", from, to, e);
            }
        }
    }

    /** Tells a player why something didn't work, at most once every few seconds (portals ask every tick). */
    private static void warn(ServerPlayer player, String messageKey) {
        long now = System.currentTimeMillis();
        Long last = lastWarning.get(player.getUUID());
        if (last == null || now - last >= WARNING_COOLDOWN_MILLIS) {
            lastWarning.put(player.getUUID(), now);
            player.sendSystemMessage(settings.message(messageKey));
        }
    }
}
