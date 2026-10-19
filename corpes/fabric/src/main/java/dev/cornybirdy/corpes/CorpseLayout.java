package dev.cornybirdy.corpes;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.Arrays;

/**
 * Slot layout of the 54-slot corpse menu, the same as the Paper plugin's. It mirrors the vanilla inventory screen:
 * equipment on top, then the main inventory, then the hotbar, then the controls.
 * <pre>
 * row 0   (0-8)   helmet, chestplate, leggings, boots, offhand, -, -, -, [info]
 * rows 1-3 (9-35) main inventory   (player slots 9-35, same numbers)
 * row 4   (36-44) hotbar           (player slots 0-8)
 * row 5   (45-53) extra items x6, -, [equip all], [take all]
 * </pre>
 * Slots marked "-" are dividers, but also take extra items if row 5 runs out of room.
 */
final class CorpseLayout {

    static final int SIZE = 54;
    static final int INFO_SLOT = 8;
    static final int EQUIP_ALL_SLOT = 52;
    static final int TAKE_ALL_SLOT = 53;

    /** Equipment slots in the top row, in order: helmet, chestplate, leggings, boots, offhand. */
    static final int[] EQUIPMENT_SLOTS = {0, 1, 2, 3, 4};

    /** Slots for items that didn't come from a known inventory slot, in fill order. */
    static final int[] EXTRA_SLOTS = {45, 46, 47, 48, 49, 50, 5, 6, 7};

    private CorpseLayout() {
    }

    static boolean isButton(int guiSlot) {
        return guiSlot == EQUIP_ALL_SLOT || guiSlot == TAKE_ALL_SLOT;
    }

    /** Player inventory slot an item in this menu slot originally came from, or -1. */
    static int playerSlotFor(int guiSlot) {
        if (guiSlot >= 9 && guiSlot <= 35) {
            return guiSlot;
        }
        if (guiSlot >= 36 && guiSlot <= 44) {
            return guiSlot - 36;
        }
        return switch (guiSlot) {
            case 0 -> 39; // helmet
            case 1 -> 38; // chestplate
            case 2 -> 37; // leggings
            case 3 -> 36; // boots
            case 4 -> 40; // offhand
            default -> -1;
        };
    }

    static int guiSlotFor(int playerSlot) {
        if (playerSlot >= 9 && playerSlot <= 35) {
            return playerSlot;
        }
        if (playerSlot >= 0 && playerSlot <= 8) {
            return playerSlot + 36;
        }
        return switch (playerSlot) {
            case 39 -> 0;
            case 38 -> 1;
            case 37 -> 2;
            case 36 -> 3;
            case 40 -> 4;
            default -> -1;
        };
    }

    static ItemStack[] empty() {
        ItemStack[] layout = new ItemStack[SIZE];
        Arrays.fill(layout, ItemStack.EMPTY);
        return layout;
    }

    /**
     * Moves everything out of a dead player's inventory into a new menu layout, keeping each item in the spot it had.
     * Items that don't fit stay in the inventory and drop on the ground as normal.
     */
    static ItemStack[] takeFrom(Inventory inventory) {
        ItemStack[] layout = empty();
        int nextExtra = 0;
        for (int playerSlot = 0; playerSlot < inventory.getContainerSize(); playerSlot++) {
            ItemStack item = inventory.getItem(playerSlot);
            if (item.isEmpty()) {
                continue;
            }
            int guiSlot = guiSlotFor(playerSlot);
            if (guiSlot < 0) {
                if (nextExtra >= EXTRA_SLOTS.length) {
                    continue;
                }
                guiSlot = EXTRA_SLOTS[nextExtra++];
            }
            layout[guiSlot] = item.copy();
            inventory.setItem(playerSlot, ItemStack.EMPTY);
        }
        return layout;
    }

    static int countStacks(ItemStack[] items) {
        int count = 0;
        for (ItemStack item : items) {
            if (!item.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    static ItemStack[] copy(ItemStack[] items) {
        ItemStack[] copy = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            copy[i] = items[i].copy();
        }
        return copy;
    }
}
