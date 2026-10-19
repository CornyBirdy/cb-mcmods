package dev.cornybirdy.corpes;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.HashMap;
import java.util.LinkedHashSet;
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
        Map<String, String> placeholders = new HashMap<>(extra);
        placeholders.put("player", ownerName);
        placeholders.put("date", settings.formatDate(death.time()));
        placeholders.put("cause", death.cause().isEmpty() ? "?" : death.cause());
        placeholders.put("world", worldName(death.world()));
        placeholders.put("x", String.valueOf((int) Math.floor(death.x())));
        placeholders.put("y", String.valueOf((int) Math.floor(death.y())));
        placeholders.put("z", String.valueOf((int) Math.floor(death.z())));
        placeholders.put("items", String.valueOf(stacks));

        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        head.set(DataComponents.PROFILE, ResolvableProfile.createUnresolved(ownerId));
        head.set(DataComponents.CUSTOM_NAME, settings.itemText(nameKey, placeholders));
        head.set(DataComponents.LORE, new ItemLore(settings.itemLore(loreKey, placeholders)));
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

    private static ItemStack divider() {
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
