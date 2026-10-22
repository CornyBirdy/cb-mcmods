package dev.cornybirdy.estate.mixin;

import dev.cornybirdy.estate.Protection;
import net.minecraft.world.entity.ai.goal.BreakDoorGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Zombies don't break down doors in claims. */
@Mixin(BreakDoorGoal.class)
public abstract class BreakDoorGoalMixin {

    @Inject(method = "canUse", at = @At("RETURN"), cancellable = true)
    private void estate$notInClaims(CallbackInfoReturnable<Boolean> cir) {
        DoorInteractGoalAccessor goal = (DoorInteractGoalAccessor) this;
        if (cir.getReturnValueZ() && goal.estate$doorPos() != null
                && Protection.mobMayNotDestroy(goal.estate$mob().level(), goal.estate$doorPos())) {
            cir.setReturnValue(false);
        }
    }
}
