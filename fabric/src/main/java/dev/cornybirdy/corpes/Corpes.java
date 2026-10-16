package dev.cornybirdy.corpes;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.UUID;

/** Server-side corpses for Fabric. Players don't need the mod; it only uses vanilla entities and menus. */
public final class Corpes implements ModInitializer {

    static final Identifier USE_PERMISSION = Identifier.fromNamespaceAndPath("corpes", "use");
    static final Identifier ADMIN_PERMISSION = Identifier.fromNamespaceAndPath("corpes", "admin");

    private static final Logger LOGGER = LoggerFactory.getLogger("Corpes");

    private static Settings settings;
    private static CorpseManager manager;

    @Override
    public void onInitialize() {
        reload();

        // Created when the server starts rather than once it has started, so corpses in spawn chunks are seen loading.
        ServerLifecycleEvents.SERVER_STARTING.register(server ->
                manager = new CorpseManager(server, () -> settings, new CorpseStorage(server)));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (manager != null) {
                manager.shutdown();
                manager.storage().close();
                manager = null;
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (manager != null) {
                manager.tick();
            }
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (manager != null) {
                manager.entityLoaded(entity);
            }
        });
        UseEntityCallback.EVENT.register(Corpes::onUseEntity);
        // Corpse bodies are invulnerable, but creative players can still hit them.
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                CorpseVisuals.corpseId(entity) != null ? InteractionResult.FAIL : InteractionResult.PASS);
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) ->
                CorpseCommand.register(dispatcher, () -> settings, () -> manager));
    }

    /** Called by {@code InventoryMixin} when a dead player's inventory is about to drop. */
    public static void onDeathDrops(ServerPlayer player, Inventory inventory) {
        if (manager == null) {
            return;
        }
        try {
            manager.onDeathDrops(player, inventory);
        } catch (RuntimeException e) {
            // Whatever is still in the inventory drops on the ground as normal.
            LOGGER.error("Could not create a corpse for {}", player.getGameProfile().name(), e);
        }
    }

    /** Reloads {@code config/corpes.json}. Returns false if it couldn't be read; the old settings stay in use. */
    static boolean reload() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("corpes.json");
        try {
            settings = Settings.load(file);
            return true;
        } catch (Exception e) {
            LOGGER.error("Could not read {}, using {}", file, settings == null ? "the defaults" : "the previous config", e);
            if (settings == null) {
                settings = Settings.defaults();
            }
            return false;
        }
    }

    static boolean isAdmin(ServerPlayer player) {
        return player.checkPermission(ADMIN_PERMISSION, PermissionLevel.GAMEMASTERS);
    }

    private static InteractionResult onUseEntity(Player player, Level level, InteractionHand hand, Entity entity,
                                                 EntityHitResult hitResult) {
        UUID id = CorpseVisuals.corpseId(entity);
        if (id == null) {
            return InteractionResult.PASS;
        }
        if (!(player instanceof ServerPlayer serverPlayer) || manager == null || hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.SUCCESS;
        }
        Corpse corpse = manager.get(id);
        if (corpse == null) {
            entity.discard();
            return InteractionResult.SUCCESS;
        }
        boolean allowed = settings.allowAnyoneLoot || serverPlayer.getUUID().equals(corpse.ownerId) || isAdmin(serverPlayer);
        if (!allowed) {
            serverPlayer.sendSystemMessage(settings.message("not-yours"));
            return InteractionResult.SUCCESS;
        }
        manager.open(serverPlayer, corpse);
        return InteractionResult.SUCCESS;
    }
}
