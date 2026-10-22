package dev.cornybirdy.estate.mixin;

import dev.cornybirdy.estate.Protection;
import net.minecraft.world.entity.animal.rabbit.Rabbit;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Rabbits don't eat carrots growing in claims. */
@Mixin(targets = "net.minecraft.world.entity.animal.rabbit.Rabbit$RaidGardenGoal")
public abstract class RabbitRaidGardenGoalMixin {

    @Shadow
    @Final
    private Rabbit rabbit;

    @Inject(method = "canUse", at = @At("HEAD"), cancellable = true)
    private void estate$notNearClaims(CallbackInfoReturnable<Boolean> cir) {
        if (Protection.mobGriefBlocked(rabbit)) {
            cir.setReturnValue(false);
        }
    }
}
