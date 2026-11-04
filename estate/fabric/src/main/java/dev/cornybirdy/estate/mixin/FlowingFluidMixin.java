package dev.cornybirdy.estate.mixin;

import dev.cornybirdy.estate.Protection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Water and lava from outside a claim stop at its border. */
@Mixin(FlowingFluid.class)
public abstract class FlowingFluidMixin {

    @Inject(method = "spreadTo", at = @At("HEAD"), cancellable = true)
    private void estate$stopAtClaims(LevelAccessor level, BlockPos pos, BlockState state, Direction direction,
                                     FluidState fluid, CallbackInfo ci) {
        if (level instanceof Level world && !world.isClientSide()
                && !Protection.mayFlow(world, pos.relative(direction.getOpposite()), pos)) {
            ci.cancel();
        }
    }
}
