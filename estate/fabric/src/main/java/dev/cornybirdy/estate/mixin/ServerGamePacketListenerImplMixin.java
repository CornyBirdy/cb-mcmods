package dev.cornybirdy.estate.mixin;

import dev.cornybirdy.estate.Estate;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A punch (arm swing) is all the server hears of a left-click in the air; the claim tool uses it to pick a far corner. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "handlePunch", at = @At("TAIL"))
    private void estate$punch(ServerboundPunchPacket packet, CallbackInfo ci) {
        Estate.punched(player);
    }
}
