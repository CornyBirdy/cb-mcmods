package dev.cornybirdy.endlock.mixin;

import dev.cornybirdy.endlock.EndLock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No destination means the portal does nothing, the same as when a dimension doesn't exist. */
@Mixin(EndPortalBlock.class)
public abstract class EndPortalBlockMixin {

    @Inject(method = "getPortalDestination", at = @At("HEAD"), cancellable = true)
    private void endlock$closeTheEnd(ServerLevel level, Entity entity, BlockPos pos,
                                     CallbackInfoReturnable<TeleportTransition> cir) {
        if (!EndLock.mayUseEndPortal(level, entity)) {
            cir.setReturnValue(null);
        }
    }
}
