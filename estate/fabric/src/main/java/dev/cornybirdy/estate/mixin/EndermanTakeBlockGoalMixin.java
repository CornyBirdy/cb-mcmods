package dev.cornybirdy.estate.mixin;

import dev.cornybirdy.estate.Protection;
import net.minecraft.world.entity.monster.Enderman;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Endermen don't pick up blocks in or right next to claims. */
@Mixin(targets = "net.minecraft.world.entity.monster.Enderman$EndermanTakeBlockGoal")
public abstract class EndermanTakeBlockGoalMixin {

    @Shadow
    @Final
    private Enderman enderman;

    @Inject(method = "canUse", at = @At("HEAD"), cancellable = true)
    private void estate$notNearClaims(CallbackInfoReturnable<Boolean> cir) {
        if (Protection.mobGriefBlocked(enderman)) {
            cir.setReturnValue(false);
        }
    }
}
