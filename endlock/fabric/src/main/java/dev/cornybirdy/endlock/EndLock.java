package dev.cornybirdy.endlock;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
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
import net.minecraft.world.phys.BlockHitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns ender chests and the End on or off. Server-side only: vanilla clients just get told no.
 * <ul>
 *     <li>Ender chests off: they can't be opened, placed or taken out of a crafting grid.</li>
 *     <li>The End off: end portals outside the End go dark (eyes can still be put in the frames) and light up
 *     again when it's turned back on. Anyone in the End is sent to the overworld spawn.</li>
 * </ul>
 */
public final class EndLock implements ModInitializer {

    static final Identifier ADMIN_PERMISSION = Identifier.fromNamespaceAndPath("endlock", "admin");
    static final Identifier ENDER_CHEST_BYPASS = Identifier.fromNamespaceAndPath("endlock", "bypass.enderchests");
    static final Identifier END_BYPASS = Identifier.fromNamespaceAndPath("endlock", "bypass.end");

    private static final Logger LOGGER = LoggerFactory.getLogger("EndLock");
    private static final long WARNING_COOLDOWN_MILLIS = 3000;
    private static final int END_CHECK_TICKS = 20;

    private static final Map<UUID, Long> lastWarning = new ConcurrentHashMap<>();
    private static Settings settings;
    private static Portals portals;

    @Override
    public void onInitialize() {
        reload();

        // Created when the server starts rather than once it has started, so portals in spawn chunks are seen loading.
        ServerLifecycleEvents.SERVER_STARTING.register(server -> portals = new Portals(server));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> portals = null);
        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register((blockEntity, level) -> {
            if (portals != null && blockEntity.getBlockState().is(Blocks.END_PORTAL)) {
                portals.portalLoaded(level, blockEntity.getBlockPos());
            }
        });
        ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((blockEntity, level) -> {
            if (portals != null) {
                portals.portalUnloaded(level, blockEntity.getBlockPos());
            }
        });
        UseBlockCallback.EVENT.register(EndLock::onUseBlock);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (portals != null) {
                portals.tick(server.getTickCount());
            }
            // Catches everyone who ends up in the End some other way: logging in there, /tp, other mods.
            if (server.getTickCount() % END_CHECK_TICKS == 0) {
                sendPlayersOutOfEnd(server);
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> lastWarning.remove(handler.getPlayer().getUUID()));
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> EndLockCommand.register(dispatcher));
    }

    static Settings settings() {
        return settings;
    }

    /** Reloads {@code config/endlock.json}. Returns false if it couldn't be read; the old settings stay in use. */
    static boolean reload() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("endlock.json");
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

    /** Turns a feature on or off from {@code /endlock}. Returns false if the config file couldn't be saved. */
    static boolean setEnabled(MinecraftServer server, Feature feature, boolean enabled) {
        boolean saved = true;
        try {
            settings.setEnabled(feature, enabled);
        } catch (Exception e) {
            LOGGER.error("Could not save config/endlock.json, the change is lost on restart", e);
            saved = false;
        }
        if (feature == Feature.END && !enabled) {
            sendPlayersOutOfEnd(server);
            if (portals != null) {
                portals.closeAll();
            }
        } else if (feature == Feature.END && portals != null) {
            portals.reopen(true);
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
                && !settings.isEnabled(Feature.END) && level.dimension() != Level.END && portals != null) {
            return portals.placeEye(serverPlayer, (ServerLevel) level, hit.getBlockPos(), clicked, held);
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

    private static void sendPlayersOutOfEnd(MinecraftServer server) {
        if (settings.isEnabled(Feature.END)) {
            return;
        }
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            if (player.level().dimension() == Level.END && !player.checkPermission(END_BYPASS, false)) {
                sendToSpawn(server, player);
            }
        }
    }

    private static void sendToSpawn(MinecraftServer server, ServerPlayer player) {
        ServerLevel overworld = server.overworld();
        BlockPos spawn = overworld.getRespawnData().pos();
        int y = Math.max(spawn.getY(), overworld.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn.getX(), spawn.getZ()));
        player.teleportTo(overworld, spawn.getX() + 0.5, y, spawn.getZ() + 0.5, Set.of(), player.getYRot(), player.getXRot(), true);
        player.sendSystemMessage(settings.message("sent-out-of-end"));
        LOGGER.info("Sent {} out of the End because it is disabled", player.getGameProfile().name());
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
