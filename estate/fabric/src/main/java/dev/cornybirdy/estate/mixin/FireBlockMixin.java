package dev.cornybirdy.estate.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cornybirdy.estate.Protection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fire doesn't spread into claims and doesn't burn blocks inside them away. */
@Mixin(FireBlock.class)
public abstract class FireBlockMixin {

    @Inject(method = "checkBurnOut", at = @At("HEAD"), cancellable = true)
    private void estate$dontBurn(Level level, BlockPos pos, int chance, RandomSource random, int age, CallbackInfo ci) {
        if (Protection.fireProtected(level, pos)) {
            ci.cancel();
        }
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean estate$dontSpreadIn(ServerLevel level, BlockPos pos, BlockState state, Operation<Boolean> original) {
        return !Protection.fireProtected(level, pos) && original.call(level, pos, state);
    }
}
