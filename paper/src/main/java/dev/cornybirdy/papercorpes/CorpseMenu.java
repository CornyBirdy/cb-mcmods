package dev.cornybirdy.papercorpes;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

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

    /** Redraws the menu from the corpse: items, labels for empty slots, the info head and the buttons. */
    void render() {
        MenuItems items = plugin.menuItems();
        items.fill(inventory, corpse.contents());
        String nameKey = corpse.skeleton() ? "info-name-remains" : "info-name";
        inventory.setItem(CorpseLayout.INFO_SLOT,
                items.info(corpse.ownerId(), corpse.ownerName(), nameKey, "info-lore", corpse.death(),
                        corpse.stacksLeft()));
        inventory.setItem(CorpseLayout.EQUIP_ALL_SLOT, items.equipAllButton());
        inventory.setItem(CorpseLayout.TAKE_ALL_SLOT, items.takeAllButton());
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
        return plugin.menuItems().isUi(item);
    }

    private static ItemStack leftover(Map<Integer, ItemStack> notAdded) {
        return notAdded.isEmpty() ? null : notAdded.values().iterator().next();
    }
}
