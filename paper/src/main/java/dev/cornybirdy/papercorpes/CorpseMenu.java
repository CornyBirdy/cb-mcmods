package dev.cornybirdy.papercorpes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Map;

/**
 * The loot menu of one corpse. Everyone looking at the same corpse shares this one inventory, so two players
 * can't take the same item.
 */
final class CorpseMenu implements InventoryHolder {

    private final PaperCorpes plugin;
    private final Corpse corpse;
    private final Inventory inventory;

    CorpseMenu(PaperCorpes plugin, Corpse corpse) {
        this.plugin = plugin;
        this.corpse = corpse;
        this.inventory = Bukkit.createInventory(this, CorpseLayout.SIZE,
                plugin.message("menu-title", Placeholder.unparsed("player", corpse.ownerName())));
        render();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    Corpse corpse() {
        return corpse;
    }

    private void render() {
        ItemStack[] contents = corpse.contents();
        for (int slot = 0; slot < CorpseLayout.SIZE; slot++) {
            ItemStack item;
            if (slot == CorpseLayout.EQUIP_ALL_SLOT) {
                item = button(Material.ARMOR_STAND, "equip-all", "equip-all-lore");
            } else if (slot == CorpseLayout.TAKE_ALL_SLOT) {
                item = button(Material.HOPPER, "take-all", "take-all-lore");
            } else if (!ItemCodec.isEmpty(contents[slot])) {
                item = contents[slot].clone();
            } else if (slot >= 36 && CorpseLayout.playerSlotFor(slot) < 0) {
                item = filler();
            } else {
                item = null;
            }
            inventory.setItem(slot, item);
        }
    }

    /** Copies the menu back into the corpse. Call after anything changes the menu. */
    void sync() {
        ItemStack[] contents = corpse.contents();
        for (int slot = 0; slot < CorpseLayout.SIZE; slot++) {
            ItemStack item = inventory.getItem(slot);
            contents[slot] = ItemCodec.isEmpty(item) || isUi(item) ? null : item.clone();
        }
    }

    /** Puts every item back in the slot it came from, or anywhere free. Items that don't fit stay in the corpse. */
    void equipAll(Player player) {
        PlayerInventory target = player.getInventory();
        for (int slot = 0; slot < CorpseLayout.SIZE; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (ItemCodec.isEmpty(item) || isUi(item)) {
                continue;
            }
            int playerSlot = CorpseLayout.playerSlotFor(slot);
            if (playerSlot >= 0 && ItemCodec.isEmpty(target.getItem(playerSlot))) {
                target.setItem(playerSlot, item);
                inventory.setItem(slot, null);
            } else {
                inventory.setItem(slot, leftover(target.addItem(item)));
            }
        }
    }

    void takeAll(Player player) {
        PlayerInventory target = player.getInventory();
        for (int slot = 0; slot < CorpseLayout.SIZE; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (!ItemCodec.isEmpty(item) && !isUi(item)) {
                inventory.setItem(slot, leftover(target.addItem(item)));
            }
        }
    }

    boolean isUi(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(plugin.uiKey(), PersistentDataType.BYTE);
    }

    private static ItemStack leftover(Map<Integer, ItemStack> notAdded) {
        return notAdded.isEmpty() ? null : notAdded.values().iterator().next();
    }

    private ItemStack button(Material material, String nameKey, String loreKey) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(plugin.itemText(nameKey));
            meta.lore(List.of(plugin.itemText(loreKey)));
            meta.getPersistentDataContainer().set(plugin.uiKey(), PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }

    private ItemStack filler() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        item.editMeta(meta -> {
            meta.displayName(Component.text(" "));
            meta.getPersistentDataContainer().set(plugin.uiKey(), PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }
}
