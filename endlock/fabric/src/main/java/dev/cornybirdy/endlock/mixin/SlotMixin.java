package dev.cornybirdy.endlock.mixin;

import dev.cornybirdy.endlock.EndLock;
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
    private void endlock$keepCraftedEnderChests(Player player, CallbackInfoReturnable<Boolean> cir) {
        Slot slot = (Slot) (Object) this;
        if (slot instanceof ResultSlot && !EndLock.mayTakeCraftedItem(player, slot.getItem())) {
            cir.setReturnValue(false);
        }
    }
}
