package dev.cornybirdy.corpes;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * A six-row chest menu that only lets items out. Labels, dividers and buttons can't be picked up, and nothing can be
 * put in, by click, shift-click, drag or hotbar key. Used for corpses (with working buttons) and for the admin
 * history view.
 */
final class LootMenu extends ChestMenu {

    /** What the equip all / take all buttons do. Null for the history view, which has no buttons. */
    interface Buttons {
        void equipAll(ServerPlayer player);

        void takeAll(ServerPlayer player);

        boolean stillValid();
    }

    private final Buttons buttons;

    LootMenu(int containerId, Inventory playerInventory, Container container, Buttons buttons) {
        super(MenuType.GENERIC_9x6, containerId, playerInventory, container, 6);
        this.buttons = buttons;
        for (int i = 0; i < CorpseLayout.SIZE; i++) {
            Slot original = slots.get(i);
            Slot takeOnly = new TakeOnlySlot(container, original.getContainerSlot(), original.x, original.y);
            takeOnly.index = original.index;
            slots.set(i, takeOnly);
        }
    }

    @Override
    public void clicked(int slotId, int button, ContainerInput input, Player player) {
        if (slotId >= 0 && slotId < CorpseLayout.SIZE) {
            if (buttons != null && CorpseLayout.isButton(slotId)) {
                if (input == ContainerInput.PICKUP && player instanceof ServerPlayer serverPlayer) {
                    if (slotId == CorpseLayout.EQUIP_ALL_SLOT) {
                        buttons.equipAll(serverPlayer);
                    } else {
                        buttons.takeAll(serverPlayer);
                    }
                }
                return;
            }
            // Creative middle-click would copy items, and labels must never move.
            if (input == ContainerInput.CLONE || MenuItems.isUi(slots.get(slotId).getItem())) {
                return;
            }
        } else if (input == ContainerInput.QUICK_MOVE) {
            // Shift-clicking from the player's inventory would put items in.
            return;
        }
        super.clicked(slotId, button, input, player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return index < CorpseLayout.SIZE ? super.quickMoveStack(player, index) : ItemStack.EMPTY;
    }

    @Override
    public boolean canDragTo(Slot slot) {
        return slot.index >= CorpseLayout.SIZE && super.canDragTo(slot);
    }

    @Override
    public boolean stillValid(Player player) {
        return buttons == null || buttons.stillValid();
    }

    private static final class TakeOnlySlot extends Slot {

        TakeOnlySlot(Container container, int slot, int x, int y) {
            super(container, slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(Player player) {
            return !MenuItems.isUi(getItem());
        }
    }
}
