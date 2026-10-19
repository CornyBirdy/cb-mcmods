package dev.cornybirdy.gatekeeper.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cornybirdy.gatekeeper.Gatekeeper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.portal.PortalShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

/**
 * Fire placed in an obsidian frame lights a nether portal. While the Nether is off the game is told there's no frame,
 * so the fire just burns, and the portal is remembered so it lights up once the Nether is turned back on.
 */
@Mixin(BaseFireBlock.class)
public abstract class BaseFireBlockMixin {

    @WrapOperation(method = "onPlace", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/portal/PortalShape;findEmptyPortalShape"))
    private Optional<PortalShape> gatekeeper$keepPortalDark(LevelAccessor level, BlockPos pos, Direction.Axis axis,
                                                            Operation<Optional<PortalShape>> original) {
        Optional<PortalShape> shape = original.call(level, pos, axis);
        if (shape.isPresent() && level instanceof ServerLevel serverLevel && !Gatekeeper.mayLightNetherPortal(serverLevel, pos)) {
            return Optional.empty();
        }
        return shape;
    }
}
