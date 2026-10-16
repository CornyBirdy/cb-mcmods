package dev.cornybirdy.papercorpes;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Slot layout of the 54-slot corpse menu. It mirrors the vanilla inventory screen: equipment on top, then the main
 * inventory, then the hotbar, then the controls.
 * <pre>
 * row 0   (0-8)   helmet, chestplate, leggings, boots, offhand, -, -, -, [info]
 * rows 1-3 (9-35) main inventory   (player slots 9-35, same numbers)
 * row 4   (36-44) hotbar           (player slots 0-8)
 * row 5   (45-53) extra drops x6, -, [equip all], [take all]
 * </pre>
 * Slots marked "-" are dividers, but also take extra drops if row 5 runs out of room.
 */
final class CorpseLayout {

    /** Version of this layout, saved with every corpse so older files can be converted. */
    static final int VERSION = 2;

    static final int SIZE = 54;
    static final int INFO_SLOT = 8;
    static final int EQUIP_ALL_SLOT = 52;
    static final int TAKE_ALL_SLOT = 53;

    /** Equipment slots in the top row, in order: helmet, chestplate, leggings, boots, offhand. */
    static final int[] EQUIPMENT_SLOTS = {0, 1, 2, 3, 4};

    /** Slots for drops that didn't come from a known inventory slot, in fill order. */
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

        placeExtras(layout, remaining, overflow);
        return layout;
    }

    /**
     * Converts items saved with an older layout. Version 1 had the main inventory in rows 0-2, the hotbar in row 3
     * and armour plus offhand in row 4.
     */
    static ItemStack[] upgrade(ItemStack[] items, int fromVersion) {
        if (fromVersion >= VERSION) {
            return items;
        }
        ItemStack[] layout = new ItemStack[SIZE];
        List<ItemStack> extras = new ArrayList<>();
        for (int oldSlot = 0; oldSlot < items.length; oldSlot++) {
            if (ItemCodec.isEmpty(items[oldSlot])) {
                continue;
            }
            int playerSlot = v1PlayerSlotFor(oldSlot);
            if (playerSlot >= 0) {
                layout[guiSlotFor(playerSlot)] = items[oldSlot];
            } else {
                extras.add(items[oldSlot]);
            }
        }
        List<ItemStack> overflow = new ArrayList<>();
        placeExtras(layout, extras, overflow);
        // Version 1 had more extra slots. Anything left over goes into free inventory spots rather than being lost.
        for (int slot = 9; slot < 45 && !overflow.isEmpty(); slot++) {
            if (layout[slot] == null) {
                layout[slot] = overflow.removeFirst();
            }
        }
        return layout;
    }

    private static void placeExtras(ItemStack[] layout, List<ItemStack> items, List<ItemStack> overflow) {
        int next = 0;
        for (ItemStack item : items) {
            while (next < EXTRA_SLOTS.length && layout[EXTRA_SLOTS[next]] != null) {
                next++;
            }
            if (next < EXTRA_SLOTS.length) {
                layout[EXTRA_SLOTS[next++]] = item;
            } else {
                overflow.add(item);
            }
        }
    }

    private static int v1PlayerSlotFor(int guiSlot) {
        if (guiSlot >= 0 && guiSlot <= 26) {
            return guiSlot + 9;
        }
        if (guiSlot >= 27 && guiSlot <= 35) {
            return guiSlot - 27;
        }
        return switch (guiSlot) {
            case 36 -> 39;
            case 37 -> 38;
            case 38 -> 37;
            case 39 -> 36;
            case 41 -> 40;
            default -> -1;
        };
    }
}
