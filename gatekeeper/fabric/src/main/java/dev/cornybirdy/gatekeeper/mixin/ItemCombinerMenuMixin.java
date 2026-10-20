package dev.cornybirdy.gatekeeper.mixin;

import dev.cornybirdy.gatekeeper.Gatekeeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.inventory.SmithingMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stops banned netherite upgrades from being taken out of the smithing table. The smithing table's result slot asks
 * {@code mayPickup} here, in the menu it shares with the anvil.
 */
@Mixin(ItemCombinerMenu.class)
public abstract class ItemCombinerMenuMixin {

    @Inject(method = "mayPickup", at = @At("HEAD"), cancellable = true)
    private void gatekeeper$keepBannedNetherite(Player player, boolean hasItem, CallbackInfoReturnable<Boolean> cir) {
        ItemCombinerMenu menu = (ItemCombinerMenu) (Object) this;
        if (menu instanceof SmithingMenu
                && !Gatekeeper.mayTakeSmithedItem(player, menu.getSlot(menu.getResultSlot()).getItem())) {
            cir.setReturnValue(false);
        }
    }
}
