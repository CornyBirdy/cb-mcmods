package dev.cornybirdy.gatekeeper.mixin;

import dev.cornybirdy.gatekeeper.Gatekeeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stops crafted ender chests from being taken out of the crafting grid's result slot. Every way of taking an
 * item out of a menu (click, shift-click, number keys, dropping) asks {@code mayPickup} first.
 */
@Mixin(Slot.class)
public abstract class SlotMixin {

    @Inject(method = "mayPickup", at = @At("HEAD"), cancellable = true)
    private void gatekeeper$keepCraftedEnderChests(Player player, CallbackInfoReturnable<Boolean> cir) {
        Slot slot = (Slot) (Object) this;
        if (slot instanceof ResultSlot && !Gatekeeper.mayTakeCraftedItem(player, slot.getItem())) {
            cir.setReturnValue(false);
        }
    }
}
