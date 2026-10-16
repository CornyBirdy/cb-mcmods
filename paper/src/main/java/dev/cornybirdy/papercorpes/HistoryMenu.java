package dev.cornybirdy.papercorpes;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

/**
 * Admin-only copy of an old death inventory. Taking items out creates them, which is how admins restore
 * items a player lost. Nothing done here changes the history or any corpse.
 */
final class HistoryMenu implements InventoryHolder {

    private final Inventory inventory;

    HistoryMenu(PaperCorpes plugin, UUID ownerId, String ownerName, int index, HistoryEntry entry) {
        this.inventory = Bukkit.createInventory(this, CorpseLayout.SIZE, plugin.message("history-menu-title",
                Placeholder.unparsed("player", ownerName), Placeholder.unparsed("index", String.valueOf(index))));
        MenuItems items = plugin.menuItems();
        items.fill(inventory, entry.items());
        inventory.setItem(CorpseLayout.INFO_SLOT, items.info(ownerId, ownerName, "history-info-name", "history-info-lore",
                entry, ItemCodec.countStacks(entry.items()), Placeholder.unparsed("index", String.valueOf(index))));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
