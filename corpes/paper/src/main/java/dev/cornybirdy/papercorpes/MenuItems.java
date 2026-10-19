package dev.cornybirdy.papercorpes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

/** The non-item parts of the corpse and history menus: labels, dividers, the info head and buttons. */
final class MenuItems {

    private static final String[] EQUIPMENT_LABELS = {"slot-helmet", "slot-chestplate", "slot-leggings", "slot-boots", "slot-offhand"};

    private final PaperCorpes plugin;

    MenuItems(PaperCorpes plugin) {
        this.plugin = plugin;
    }

    boolean isUi(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(plugin.uiKey(), PersistentDataType.BYTE);
    }

    /** What an empty menu slot shows: a label for equipment slots, nothing for inventory slots, a divider otherwise. */
    ItemStack background(int slot) {
        for (int i = 0; i < CorpseLayout.EQUIPMENT_SLOTS.length; i++) {
            if (CorpseLayout.EQUIPMENT_SLOTS[i] == slot) {
                return label(EQUIPMENT_LABELS[i]);
            }
        }
        return CorpseLayout.playerSlotFor(slot) >= 0 ? null : divider();
    }

    /** Fills the menu from saved contents, with labels and dividers in the empty spots. */
    void fill(Inventory inventory, ItemStack[] contents) {
        for (int slot = 0; slot < CorpseLayout.SIZE; slot++) {
            inventory.setItem(slot, ItemCodec.isEmpty(contents[slot]) ? background(slot) : contents[slot].clone());
        }
    }

    ItemStack equipAllButton() {
        return button(Material.ARMOR_STAND, "equip-all", "equip-all-lore");
    }

    ItemStack takeAllButton() {
        return button(Material.HOPPER, "take-all", "take-all-lore");
    }

    /** The owner's head, showing when, where and how they died and how much is left. */
    ItemStack info(UUID ownerId, String ownerName, String nameKey, String loreKey, HistoryEntry death, int stacks,
                   TagResolver... extra) {
        TagResolver resolver = TagResolver.resolver(TagResolver.resolver(extra),
                Placeholder.unparsed("player", ownerName),
                Placeholder.unparsed("date", plugin.settings().formatDate(death.time())),
                Placeholder.unparsed("cause", death.cause().isEmpty() ? "?" : death.cause()),
                Placeholder.unparsed("world", death.world()),
                Placeholder.unparsed("x", String.valueOf((int) Math.floor(death.x()))),
                Placeholder.unparsed("y", String.valueOf((int) Math.floor(death.y()))),
                Placeholder.unparsed("z", String.valueOf((int) Math.floor(death.z()))),
                Placeholder.unparsed("items", String.valueOf(stacks)));
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        head.editMeta(SkullMeta.class, meta -> {
            meta.setPlayerProfile(Bukkit.createProfile(ownerId, ownerName));
            meta.displayName(plugin.itemText(nameKey, resolver));
            meta.lore(plugin.itemLore(loreKey, resolver));
            markUi(meta);
        });
        return head;
    }

    private ItemStack button(Material material, String nameKey, String loreKey) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(plugin.itemText(nameKey));
            meta.lore(plugin.itemLore(loreKey));
            meta.setEnchantmentGlintOverride(true);
            markUi(meta);
        });
        return item;
    }

    private ItemStack label(String nameKey) {
        ItemStack item = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        item.editMeta(meta -> {
            meta.displayName(plugin.itemText(nameKey));
            meta.lore(plugin.itemLore("slot-empty"));
            markUi(meta);
        });
        return item;
    }

    private ItemStack divider() {
        ItemStack item = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        item.editMeta(meta -> {
            meta.displayName(Component.empty());
            meta.setHideTooltip(true);
            markUi(meta);
        });
        return item;
    }

    private void markUi(ItemMeta meta) {
        meta.getPersistentDataContainer().set(plugin.uiKey(), PersistentDataType.BYTE, (byte) 1);
    }
}
