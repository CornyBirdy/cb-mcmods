package dev.cornybirdy.estate.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cornybirdy.estate.Protection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A wither doesn't tear through blocks in claims when it's hurt (its explosions are handled like any other). */
@Mixin(WitherBoss.class)
public abstract class WitherBossMixin {

    @WrapOperation(method = "customServerAiStep", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;)Z"))
    private boolean estate$spareClaims(ServerLevel level, BlockPos pos, boolean drop, Entity entity, Operation<Boolean> original) {
        return !Protection.mobMayNotDestroy(level, pos) && original.call(level, pos, drop, entity);
    }
}
