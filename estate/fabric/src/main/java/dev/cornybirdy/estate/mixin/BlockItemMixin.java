package dev.cornybirdy.estate.mixin;

import dev.cornybirdy.estate.Protection;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every block a player places goes through {@code BlockItem.place}. It's checked here against claims and buffers at
 * the exact spot it lands, and recorded afterwards for auto-claim and the "can't claim over others' builds" rule.
 */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {

    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void estate$checkPlace(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (!context.getLevel().isClientSide() && !Protection.mayPlace(context, ((BlockItem) (Object) this).getBlock())) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }

    @Inject(method = "place", at = @At("RETURN"))
    private void estate$recordPlace(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (!context.getLevel().isClientSide() && cir.getReturnValue().consumesAction()) {
            Protection.placed(context);
        }
    }
}
