package dev.cornybirdy.gatekeeper;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/** Which items count as netherite armor and netherite tools. */
final class Netherite {

    private static final String[] ARMOR = {"_helmet", "_chestplate", "_leggings", "_boots"};
    private static final String[] TOOLS = {"_sword", "_pickaxe", "_axe", "_shovel", "_hoe", "_spear"};

    private Netherite() {
    }

    static boolean isArmor(ItemStack stack) {
        return is(stack, ARMOR);
    }

    static boolean isTool(ItemStack stack) {
        return is(stack, TOOLS);
    }

    /** The feature that bans this item, or null if it isn't netherite gear. */
    static Feature feature(ItemStack stack) {
        return isArmor(stack) ? Feature.NETHERITE_ARMOR : isTool(stack) ? Feature.NETHERITE_TOOLS : null;
    }

    private static boolean is(ItemStack stack, String[] suffixes) {
        if (stack.isEmpty()) {
            return false;
        }
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        if (!id.startsWith("netherite_")) {
            return false;
        }
        for (String suffix : suffixes) {
            if (id.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }
}
