package dev.cornybirdy.estate.mixin;

import dev.cornybirdy.estate.Protection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Pistons (and flying machines) can't push or pull blocks across a claim border. */
@Mixin(PistonBaseBlock.class)
public abstract class PistonBaseBlockMixin {

    @Inject(method = "moveBlocks", at = @At("HEAD"), cancellable = true)
    private void estate$stayInside(Level level, BlockPos pos, Direction direction, boolean extending,
                                   CallbackInfoReturnable<Boolean> cir) {
        if (!level.isClientSide() && !Protection.mayPistonMove(level, pos, direction, extending)) {
            cir.setReturnValue(false);
        }
    }
}
