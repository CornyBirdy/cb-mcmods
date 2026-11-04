package dev.cornybirdy.estate.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import dev.cornybirdy.estate.Protection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Farmland in a claim isn't trampled by untrusted players or by mobs. */
@Mixin(FarmlandBlock.class)
public abstract class FarmlandBlockMixin {

    @WrapWithCondition(method = "fallOn", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/FarmlandBlock;turnToBaseBlock(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V"))
    private boolean estate$keepCrops(FarmlandBlock self, Entity entity, BlockState state, Level level, BlockPos pos) {
        return Protection.mayTrample(entity, level, pos);
    }
}
