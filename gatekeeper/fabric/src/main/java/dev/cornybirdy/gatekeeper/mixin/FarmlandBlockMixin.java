package dev.cornybirdy.gatekeeper.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import dev.cornybirdy.gatekeeper.Gatekeeper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** With {@code farmland-trample-hits} above 1, farmland takes several tramples before it turns to dirt. */
@Mixin(FarmlandBlock.class)
public abstract class FarmlandBlockMixin {

    @WrapWithCondition(method = "fallOn", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/FarmlandBlock;turnToBaseBlock(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V"))
    private boolean gatekeeper$sparePartlyTrampled(FarmlandBlock self, Entity entity, BlockState state, Level level, BlockPos pos) {
        return Gatekeeper.shouldTrampleFarmland(level, pos);
    }
}
