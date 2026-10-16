package dev.cornybirdy.papercorpes;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.Base64;

/** Stores item arrays in YAML as base64 of Paper's versioned item bytes, so items survive Minecraft updates. */
final class ItemCodec {

    private ItemCodec() {
    }

    static boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }

    static void write(ConfigurationSection parent, String path, ItemStack[] items) {
        ConfigurationSection section = parent.createSection(path);
        for (int i = 0; i < items.length; i++) {
            if (!isEmpty(items[i])) {
                section.set(String.valueOf(i), Base64.getEncoder().encodeToString(items[i].serializeAsBytes()));
            }
        }
    }

    static ItemStack[] read(ConfigurationSection parent, String path, int size) {
        ItemStack[] items = new ItemStack[size];
        ConfigurationSection section = parent.getConfigurationSection(path);
        if (section == null) {
            return items;
        }
        for (String key : section.getKeys(false)) {
            int slot;
            try {
                slot = Integer.parseInt(key);
            } catch (NumberFormatException e) {
                continue;
            }
            String data = section.getString(key);
            if (slot >= 0 && slot < size && data != null) {
                items[slot] = ItemStack.deserializeBytes(Base64.getDecoder().decode(data));
            }
        }
        return items;
    }

    static ItemStack[] copy(ItemStack[] items) {
        ItemStack[] copy = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            copy[i] = isEmpty(items[i]) ? null : items[i].clone();
        }
        return copy;
    }
}
