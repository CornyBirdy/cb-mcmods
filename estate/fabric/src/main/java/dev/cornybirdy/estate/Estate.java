package dev.cornybirdy.estate;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;

/**
 * Land claims for Fabric servers. Players need no mod: claims are protected on the server, the menu is a chest
 * screen and borders are block displays sent only to the player looking at them. The claim rules themselves live in {@code dev.cornybirdy.estate.core}, which has
 * no Minecraft code, so they can be shared with a Paper port.
 */
public final class Estate implements ModInitializer {

    static final Identifier ADMIN_PERMISSION = Identifier.fromNamespaceAndPath("estate", "admin");

    private static EstateRuntime runtime;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> runtime = new EstateRuntime(server));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (runtime != null) {
                runtime.close();
                runtime = null;
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (runtime != null) {
                runtime.tick();
            }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (runtime != null) {
                runtime.joined(handler.getPlayer());
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (runtime != null) {
                runtime.left(handler.getPlayer());
            }
        });
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            if (runtime != null) {
                runtime.placements.forgetChunk(EstateRuntime.world(level), chunk.getPos().x(), chunk.getPos().z());
            }
        });
        // The claim tool goes first, so picking a corner inside someone's claim isn't refused as "using an item".
        UseItemCallback.EVENT.register((player, level, hand) ->
                runtime == null ? InteractionResult.PASS : runtime.claimTool.useItem(player, level, hand));
        UseBlockCallback.EVENT.register((player, level, hand, hit) ->
                runtime == null ? InteractionResult.PASS : runtime.claimTool.useBlock(player, level, hand, hit));
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
                runtime == null ? InteractionResult.PASS : runtime.claimTool.attackBlock(player, level, hand, pos));
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                runtime == null ? InteractionResult.PASS : runtime.claimTool.attackEntity(player));
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) ->
                runtime == null || runtime.claimTool.mayBreak(player));
        PlayerBlockBreakEvents.BEFORE.register(Protection::mayBreak);
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> Protection.broken(level, pos));
        UseBlockCallback.EVENT.register(Protection::useBlock);
        UseItemCallback.EVENT.register(Protection::useItem);
        UseEntityCallback.EVENT.register(Protection::useEntity);
        AttackEntityCallback.EVENT.register(Protection::attackEntity);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(Protection::allowDamage);
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> LandCommand.register(dispatcher));
    }

    /** A player swung their arm (from a mixin): a left-click with the claim tool picks a corner. */
    public static void swung(ServerPlayer player, InteractionHand hand) {
        if (runtime != null) {
            runtime.claimTool.swing(player, hand);
        }
    }

    /** Null while no server is running. */
    static EstateRuntime runtime() {
        return runtime;
    }
}
