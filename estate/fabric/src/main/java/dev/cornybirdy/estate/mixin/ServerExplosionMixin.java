package dev.cornybirdy.estate.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.cornybirdy.estate.Protection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * TNT, creepers, end crystals, beds and anchors: protected blocks are taken off the list of blocks an explosion
 * destroys, lights on fire or triggers (wind charges flipping levers and doors), before anything happens to them.
 */
@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {

    @Shadow
    @Final
    private ServerLevel level;

    @Shadow
    @Final
    private Vec3 center;

    @ModifyExpressionValue(method = "explode", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/ServerExplosion;calculateExplodedPositions()Ljava/util/List;"))
    private List<BlockPos> estate$keepClaimsIntact(List<BlockPos> positions) {
        return Protection.filterExplosion(level, center, positions);
    }
}
