package dev.cornybirdy.corpes;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The non-item parts of the corpse and history menus: labels, dividers, the info head and buttons. */
final class MenuItems {

    private static final String UI_KEY = "corpes_ui";
    private static final String[] EQUIPMENT_LABELS = {"slot-helmet", "slot-chestplate", "slot-leggings", "slot-boots", "slot-offhand"};

    private MenuItems() {
    }

    static boolean isUi(ItemStack item) {
        CustomData data = item.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().contains(UI_KEY);
    }

    /** What an empty menu slot shows: a label for equipment slots, nothing for inventory slots, a divider otherwise. */
    static ItemStack background(Settings settings, int slot) {
        for (int i = 0; i < CorpseLayout.EQUIPMENT_SLOTS.length; i++) {
            if (CorpseLayout.EQUIPMENT_SLOTS[i] == slot) {
                return label(settings, EQUIPMENT_LABELS[i]);
            }
        }
        return CorpseLayout.playerSlotFor(slot) >= 0 ? ItemStack.EMPTY : divider();
    }

    static ItemStack equipAllButton(Settings settings) {
        return button(settings, new ItemStack(Items.ARMOR_STAND), "equip-all", "equip-all-lore");
    }

    static ItemStack takeAllButton(Settings settings) {
        return button(settings, new ItemStack(Items.HOPPER), "take-all", "take-all-lore");
    }

    /** The owner's head, showing when, where and how they died and how many stacks there are. */
    static ItemStack info(Settings settings, UUID ownerId, String ownerName, String nameKey, String loreKey,
                          HistoryEntry death, int stacks, Map<String, String> extra) {
        Map<String, String> placeholders = deathPlaceholders(settings, ownerName, death, stacks, extra);
        return head(ownerId, settings.itemText(nameKey, placeholders), settings.itemLore(loreKey, placeholders));
    }

    /** One entry of the death list: the owner's head with when, where and how they died, and what became of the body. */
    static ItemStack deathIcon(Settings settings, UUID ownerId, String ownerName, int index, HistoryEntry death,
                               String statusKey, String hintKey) {
        Map<String, String> placeholders = deathPlaceholders(settings, ownerName, death,
                CorpseLayout.countStacks(death.items()), Map.of("index", String.valueOf(index)));
        List<Component> lore = new ArrayList<>(settings.itemLore("death-lore", placeholders));
        lore.addAll(settings.itemLore(statusKey, Map.of()));
        lore.add(Component.empty());
        lore.addAll(settings.itemLore(hintKey, Map.of()));
        return head(ownerId, settings.itemText("death-name", placeholders), lore);
    }

    /** One entry of the player list: their head with how many deaths are on record. */
    static ItemStack playerIcon(Settings settings, UUID playerId, String playerName, int deaths, long lastDeath) {
        Map<String, String> placeholders = Map.of("player", playerName, "deaths", String.valueOf(deaths),
                "date", settings.formatDate(lastDeath));
        return head(playerId, settings.itemText("player-name", placeholders), settings.itemLore("player-lore", placeholders));
    }

    /** A clickable menu item. Menu code decides what clicking it does; the item itself can't be taken. */
    static ItemStack button(Settings settings, Item item, String nameKey, String loreKey, boolean glint,
                            Map<String, String> placeholders) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, settings.itemText(nameKey, placeholders));
        stack.set(DataComponents.LORE, new ItemLore(settings.itemLore(loreKey, placeholders)));
        if (glint) {
            stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        return markUi(stack);
    }

    private static Map<String, String> deathPlaceholders(Settings settings, String ownerName, HistoryEntry death,
                                                         int stacks, Map<String, String> extra) {
        Map<String, String> placeholders = new HashMap<>(extra);
        placeholders.put("player", ownerName);
        placeholders.put("date", settings.formatDate(death.time()));
        placeholders.put("cause", death.cause().isEmpty() ? "?" : death.cause());
        placeholders.put("world", worldName(death.world()));
        placeholders.put("x", String.valueOf((int) Math.floor(death.x())));
        placeholders.put("y", String.valueOf((int) Math.floor(death.y())));
        placeholders.put("z", String.valueOf((int) Math.floor(death.z())));
        placeholders.put("items", String.valueOf(stacks));
        return placeholders;
    }

    private static ItemStack head(UUID ownerId, Component name, List<Component> lore) {
        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        head.set(DataComponents.PROFILE, ResolvableProfile.createUnresolved(ownerId));
        head.set(DataComponents.CUSTOM_NAME, name);
        head.set(DataComponents.LORE, new ItemLore(lore));
        return markUi(head);
    }

    /** {@code minecraft:the_nether} shows as {@code the_nether}; other namespaces are kept. */
    static String worldName(String dimension) {
        return dimension.startsWith("minecraft:") ? dimension.substring("minecraft:".length()) : dimension;
    }

    private static ItemStack button(Settings settings, ItemStack item, String nameKey, String loreKey) {
        item.set(DataComponents.CUSTOM_NAME, settings.itemText(nameKey, Map.of()));
        item.set(DataComponents.LORE, new ItemLore(settings.itemLore(loreKey, Map.of())));
        item.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return markUi(item);
    }

    private static ItemStack label(Settings settings, String nameKey) {
        ItemStack item = new ItemStack(Items.STAINED_GLASS_PANE.lightGray());
        item.set(DataComponents.CUSTOM_NAME, settings.itemText(nameKey, Map.of()));
        item.set(DataComponents.LORE, new ItemLore(settings.itemLore("slot-empty", Map.of())));
        return markUi(item);
    }

    static ItemStack divider() {
        ItemStack item = new ItemStack(Items.STAINED_GLASS_PANE.black());
        item.set(DataComponents.CUSTOM_NAME, Component.empty());
        item.set(DataComponents.TOOLTIP_DISPLAY, new TooltipDisplay(true, new LinkedHashSet<>()));
        return markUi(item);
    }

    private static ItemStack markUi(ItemStack item) {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(UI_KEY, true);
        item.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return item;
    }
}
