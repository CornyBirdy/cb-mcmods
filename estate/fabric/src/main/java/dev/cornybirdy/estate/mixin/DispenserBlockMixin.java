package dev.cornybirdy.estate.mixin;

import dev.cornybirdy.estate.Protection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A dispenser outside a claim can't fire into it (lava, water, fire charges, TNT, shulker boxes...). */
@Mixin(DispenserBlock.class)
public abstract class DispenserBlockMixin {

    @Inject(method = "dispenseFrom", at = @At("HEAD"), cancellable = true)
    private void estate$notIntoClaims(ServerLevel level, BlockState state, BlockPos pos, CallbackInfo ci) {
        if (!Protection.mayDispense(level, state, pos)) {
            ci.cancel();
        }
    }
}
