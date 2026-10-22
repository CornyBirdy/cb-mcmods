package dev.cornybirdy.estate.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cornybirdy.estate.Protection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Ravager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Ravagers don't trample crops and leaves in claims. */
@Mixin(Ravager.class)
public abstract class RavagerMixin {

    @WrapOperation(method = "aiStep", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;)Z"))
    private boolean estate$spareClaims(ServerLevel level, BlockPos pos, boolean drop, Entity entity, Operation<Boolean> original) {
        return !Protection.mobMayNotDestroy(level, pos) && original.call(level, pos, drop, entity);
    }
}
