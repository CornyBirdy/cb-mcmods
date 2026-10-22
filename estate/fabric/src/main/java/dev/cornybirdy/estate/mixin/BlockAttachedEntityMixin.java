package dev.cornybirdy.estate.mixin;

import dev.cornybirdy.estate.Protection;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paintings and leash knots in a claim can't be broken by untrusted players or by explosions. */
@Mixin(BlockAttachedEntity.class)
public abstract class BlockAttachedEntityMixin {

    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void estate$protect(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (Protection.protectEntity((Entity) (Object) this, level, source)) {
            cir.setReturnValue(false);
        }
    }
}
