package dev.cornybirdy.gatekeeper.mixin;

import dev.cornybirdy.gatekeeper.Gatekeeper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Nether portals outside the Nether are taken out while it's off, so this only matters for one that slips through,
 * such as one placed with /setblock.
 */
@Mixin(NetherPortalBlock.class)
public abstract class NetherPortalBlockMixin {

    @Inject(method = "getPortalDestination", at = @At("HEAD"), cancellable = true)
    private void gatekeeper$closeTheNether(ServerLevel level, Entity entity, BlockPos pos,
                                          CallbackInfoReturnable<TeleportTransition> cir) {
        if (!Gatekeeper.mayUseNetherPortal(level, entity)) {
            cir.setReturnValue(null);
        }
    }
}
