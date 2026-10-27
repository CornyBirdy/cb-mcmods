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

import java.util.ArrayList;
import java.util.List;
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
        TagResolver resolver = deathResolver(ownerName, death, stacks, extra);
        return head(ownerId, ownerName, plugin.itemText(nameKey, resolver), plugin.itemLore(loreKey, resolver));
    }

    private TagResolver deathResolver(String ownerName, HistoryEntry death, int stacks, TagResolver... extra) {
        return TagResolver.resolver(TagResolver.resolver(extra),
                Placeholder.unparsed("player", ownerName),
                Placeholder.unparsed("date", plugin.settings().formatDate(death.time())),
                Placeholder.unparsed("cause", death.cause().isEmpty() ? "?" : death.cause()),
                Placeholder.unparsed("world", death.world()),
                Placeholder.unparsed("x", String.valueOf((int) Math.floor(death.x()))),
                Placeholder.unparsed("y", String.valueOf((int) Math.floor(death.y()))),
                Placeholder.unparsed("z", String.valueOf((int) Math.floor(death.z()))),
                Placeholder.unparsed("items", String.valueOf(stacks)));
    }

    private ItemStack head(UUID ownerId, String ownerName, Component name, List<Component> lore) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        head.editMeta(SkullMeta.class, meta -> {
            meta.setPlayerProfile(Bukkit.createProfile(ownerId, ownerName));
            meta.displayName(name);
            meta.lore(lore);
            markUi(meta);
        });
        return head;
    }

    private ItemStack button(Material material, String nameKey, String loreKey) {
        return button(material, nameKey, loreKey, true);
    }

    /** A clickable menu item. Menu code decides what clicking it does; the item itself can't be taken. */
    ItemStack button(Material material, String nameKey, String loreKey, boolean glint, TagResolver... resolvers) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(plugin.itemText(nameKey, resolvers));
            meta.lore(plugin.itemLore(loreKey, resolvers));
            if (glint) {
                meta.setEnchantmentGlintOverride(true);
            }
            markUi(meta);
        });
        return item;
    }

    /** One entry of the death list: the owner's head with when, where and how they died, and what became of the body. */
    ItemStack deathIcon(UUID ownerId, String ownerName, int index, HistoryEntry death, String statusKey, String hintKey) {
        TagResolver resolver = deathResolver(ownerName, death, ItemCodec.countStacks(death.items()),
                Placeholder.unparsed("index", String.valueOf(index)));
        List<Component> lore = new ArrayList<>(plugin.itemLore("death-lore", resolver));
        lore.addAll(plugin.itemLore(statusKey));
        lore.add(Component.empty());
        lore.addAll(plugin.itemLore(hintKey));
        return head(ownerId, ownerName, plugin.itemText("death-name", resolver), lore);
    }

    /** One entry of the player list: their head with how many deaths are on record. */
    ItemStack playerIcon(UUID playerId, String playerName, int deaths, long lastDeath) {
        TagResolver resolver = TagResolver.resolver(
                Placeholder.unparsed("player", playerName),
                Placeholder.unparsed("deaths", String.valueOf(deaths)),
                Placeholder.unparsed("date", plugin.settings().formatDate(lastDeath)));
        return head(playerId, playerName, plugin.itemText("player-name", resolver), plugin.itemLore("player-lore", resolver));
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

    ItemStack divider() {
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
