package dev.cornybirdy.corpes.mixin;

import dev.cornybirdy.corpes.Corpes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The game calls {@code dropAll} when a player dies, after it has checked keepInventory and destroyed items with
 * Curse of Vanishing. Moving the items into a corpse first leaves nothing to drop.
 */
@Mixin(Inventory.class)
public abstract class InventoryMixin {

    @Inject(method = "dropAll", at = @At("HEAD"))
    private void corpes$moveIntoCorpse(CallbackInfo ci) {
        Inventory inventory = (Inventory) (Object) this;
        if (inventory.player instanceof ServerPlayer player && player.isDeadOrDying()) {
            Corpes.onDeathDrops(player, inventory);
        }
    }
}
