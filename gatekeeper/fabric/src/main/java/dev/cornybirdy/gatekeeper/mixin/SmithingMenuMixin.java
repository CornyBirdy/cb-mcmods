package dev.cornybirdy.gatekeeper.mixin;

import dev.cornybirdy.gatekeeper.Gatekeeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.SmithingMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Stops banned netherite upgrades from being taken out of the smithing table. */
@Mixin(SmithingMenu.class)
public abstract class SmithingMenuMixin {

    @Inject(method = "mayPickup", at = @At("HEAD"), cancellable = true)
    private void gatekeeper$keepBannedNetherite(Player player, boolean hasItem, CallbackInfoReturnable<Boolean> cir) {
        SmithingMenu menu = (SmithingMenu) (Object) this;
        if (!Gatekeeper.mayTakeSmithedItem(player, menu.getSlot(menu.getResultSlot()).getItem())) {
            cir.setReturnValue(false);
        }
    }
}
