package dev.cornybirdy.papercorpes;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Slot layout of the 54-slot corpse menu.
 * <pre>
 * rows 0-2 (0-26)  main inventory   (player slots 9-35)
 * row  3   (27-35) hotbar           (player slots 0-8)
 * row  4   (36-44) helmet, chestplate, leggings, boots, -, offhand, -, -, -
 * row  5   (45-53) extra drops ... , [equip all], [take all]
 * </pre>
 * Slots marked "-" also take extra drops if row 5 runs out of room.
 */
final class CorpseLayout {

    static final int SIZE = 54;
    static final int EQUIP_ALL_SLOT = 52;
    static final int TAKE_ALL_SLOT = 53;

    /** Slots for drops that didn't come from a known inventory slot, in fill order. */
    static final int[] EXTRA_SLOTS = {45, 46, 47, 48, 49, 50, 51, 40, 42, 43, 44};

    private CorpseLayout() {
    }

    static boolean isButton(int guiSlot) {
        return guiSlot == EQUIP_ALL_SLOT || guiSlot == TAKE_ALL_SLOT;
    }

    /** Player inventory slot an item in this menu slot originally came from, or -1. */
    static int playerSlotFor(int guiSlot) {
        if (guiSlot >= 0 && guiSlot <= 26) {
            return guiSlot + 9;
        }
        if (guiSlot >= 27 && guiSlot <= 35) {
            return guiSlot - 27;
        }
        return switch (guiSlot) {
            case 36 -> 39; // helmet
            case 37 -> 38; // chestplate
            case 38 -> 37; // leggings
            case 39 -> 36; // boots
            case 41 -> 40; // offhand
            default -> -1;
        };
    }

    static int guiSlotFor(int playerSlot) {
        if (playerSlot >= 9 && playerSlot <= 35) {
            return playerSlot - 9;
        }
        if (playerSlot >= 0 && playerSlot <= 8) {
            return playerSlot + 27;
        }
        return switch (playerSlot) {
            case 39 -> 36;
            case 38 -> 37;
            case 37 -> 38;
            case 36 -> 39;
            case 40 -> 41;
            default -> -1;
        };
    }

    /**
     * Lays out death drops in the menu, keeping each item in the spot it had in the player's inventory.
     * Drops are matched against the inventory snapshot, so items other plugins removed from the drops
     * (or vanishing-cursed items) are left out, and items they added go to the extra slots.
     *
     * @param overflow receives drops that didn't fit in the menu; they should drop on the ground as normal
     */
    static ItemStack[] fromDeath(ItemStack[] playerContents, List<ItemStack> drops, List<ItemStack> overflow) {
        ItemStack[] layout = new ItemStack[SIZE];
        List<ItemStack> remaining = new ArrayList<>();
        for (ItemStack drop : drops) {
            if (!ItemCodec.isEmpty(drop)) {
                remaining.add(drop.clone());
            }
        }

        for (int playerSlot = 0; playerSlot < playerContents.length; playerSlot++) {
            ItemStack item = playerContents[playerSlot];
            int guiSlot = guiSlotFor(playerSlot);
            if (ItemCodec.isEmpty(item) || guiSlot < 0) {
                continue;
            }
            for (int i = 0; i < remaining.size(); i++) {
                if (remaining.get(i).equals(item)) {
                    layout[guiSlot] = remaining.remove(i);
                    break;
                }
            }
        }

        int next = 0;
        for (ItemStack item : remaining) {
            if (next < EXTRA_SLOTS.length) {
                layout[EXTRA_SLOTS[next++]] = item;
            } else {
                overflow.add(item);
            }
        }
        return layout;
    }
}
