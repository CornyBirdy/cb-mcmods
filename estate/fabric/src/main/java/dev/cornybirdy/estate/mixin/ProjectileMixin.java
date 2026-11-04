package dev.cornybirdy.estate.mixin;

import dev.cornybirdy.estate.Protection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Projectiles ask {@code mayInteract} before changing a block (breaking pots and chorus, lighting TNT and campfires,
 * pressing buttons...). Inside a claim only projectiles from trusted players may.
 */
@Mixin(Projectile.class)
public abstract class ProjectileMixin {

    @Inject(method = "mayInteract", at = @At("RETURN"), cancellable = true)
    private void estate$respectClaims(ServerLevel level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && !Protection.projectileMayInteract((Projectile) (Object) this, level, pos)) {
            cir.setReturnValue(false);
        }
    }
}
