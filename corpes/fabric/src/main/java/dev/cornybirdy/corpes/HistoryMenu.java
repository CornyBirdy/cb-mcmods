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

import java.util.HashMap;
import java.util.Map;

/**
 * One screen of the death browser: the list of someone's deaths, the list of players, or a single death's inventory.
 * The container belongs to the player it was opened for, so nothing done here changes the history or any corpse.
 * Buttons run the action they were given; every other item can only be taken out (as a copy) if {@code takeable}.
 * Nothing can ever be put in.
 */
final class HistoryMenu extends ChestMenu {

    /** What a button does. {@code rightClick} is true for a right-click. */
    interface Click {
        void on(ServerPlayer player, boolean rightClick);
    }

    private final boolean takeable;
    private final Map<Integer, Click> buttons;

    HistoryMenu(int containerId, Inventory playerInventory, Container container, boolean takeable,
                Map<Integer, Click> buttons) {
        super(MenuType.GENERIC_9x6, containerId, playerInventory, container, 6);
        this.takeable = takeable;
        this.buttons = new HashMap<>(buttons);
        for (int i = 0; i < CorpseLayout.SIZE; i++) {
            Slot original = slots.get(i);
            Slot slot = new MenuSlot(container, original.getContainerSlot(), original.x, original.y);
            slot.index = original.index;
            slots.set(i, slot);
        }
    }

    @Override
    public void clicked(int slotId, int button, ContainerInput input, Player player) {
        if (slotId >= 0 && slotId < CorpseLayout.SIZE) {
            Click click = buttons.get(slotId);
            if (click != null) {
                if (input == ContainerInput.PICKUP && player instanceof ServerPlayer serverPlayer
                        && serverPlayer.level().getServer() != null) {
                    // Opening another screen from inside a click is asking for trouble, so wait for the tick to end.
                    boolean right = button == 1;
                    serverPlayer.level().getServer().execute(() -> click.on(serverPlayer, right));
                }
                return;
            }
            // Creative middle-click would copy items, and labels must never move.
            if (!takeable || input == ContainerInput.CLONE || MenuItems.isUi(slots.get(slotId).getItem())) {
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
        return index < CorpseLayout.SIZE && takeable ? super.quickMoveStack(player, index) : ItemStack.EMPTY;
    }

    @Override
    public boolean canDragTo(Slot slot) {
        return slot.index >= CorpseLayout.SIZE && super.canDragTo(slot);
    }

    private final class MenuSlot extends Slot {

        MenuSlot(Container container, int slot, int x, int y) {
            super(container, slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(Player player) {
            return takeable && !MenuItems.isUi(getItem());
        }
    }
}
