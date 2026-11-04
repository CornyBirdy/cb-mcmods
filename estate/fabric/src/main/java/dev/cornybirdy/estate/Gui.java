package dev.cornybirdy.estate;

import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A chest screen made of buttons, which works with vanilla clients. Nothing can be taken out or put in; clicks on
 * buttons run their action. A screen is built by a {@link Builder} function, so it can redraw itself in place after a
 * click without reopening.
 */
final class Gui extends ChestMenu {

    enum Click {
        LEFT, RIGHT, SHIFT_LEFT, SHIFT_RIGHT;

        boolean right() {
            return this == RIGHT || this == SHIFT_RIGHT;
        }

        boolean shift() {
            return this == SHIFT_LEFT || this == SHIFT_RIGHT;
        }
    }

    @FunctionalInterface
    interface Action {
        void run(ServerPlayer player, Click click);
    }

    /** Fills a screen: puts items and actions in it. Called again by {@link #redraw}. */
    @FunctionalInterface
    interface Builder {
        void build(Gui gui, ServerPlayer player);
    }

    private static final String UI_KEY = "estate_ui";

    private final SimpleContainer container;
    private final int size;
    private final Map<Integer, Action> actions = new HashMap<>();
    private final Builder builder;
    private final ServerPlayer owner;
    private Consumer<ServerPlayer> onClose;

    private Gui(int id, Inventory inventory, SimpleContainer container, int rows, ServerPlayer owner, Builder builder) {
        super(rowsType(rows), id, inventory, container, rows);
        this.container = container;
        this.size = rows * 9;
        this.owner = owner;
        this.builder = builder;
    }

    /**
     * The last screen built for a fake player. Fake players (machines from other mods, game tests) can't see
     * screens, so theirs are built without being sent; tests look at them here.
     */
    static Gui lastOffscreen;

    /** Opens a screen with {@code rows} rows (1 to 6). */
    static void open(ServerPlayer player, Component title, int rows, Builder builder) {
        SimpleContainer container = new SimpleContainer(rows * 9);
        if (player instanceof FakePlayer) {
            Gui gui = new Gui(0, player.getInventory(), container, rows, player, builder);
            gui.redraw();
            lastOffscreen = gui;
            return;
        }
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> {
            Gui gui = new Gui(id, inventory, container, rows, player, builder);
            gui.redraw();
            return gui;
        }, title));
    }

    /** Clears the screen and builds it again, e.g. after a button changed something. */
    void redraw() {
        actions.clear();
        for (int i = 0; i < size; i++) {
            container.setItem(i, filler());
        }
        builder.build(this, owner);
        broadcastChanges();
    }

    void set(int slot, ItemStack item, Action action) {
        container.setItem(slot, markUi(item));
        if (action != null) {
            actions.put(slot, action);
        } else {
            actions.remove(slot);
        }
    }

    void set(int slot, ItemStack item) {
        set(slot, item, null);
    }

    int size() {
        return size;
    }

    void onClose(Consumer<ServerPlayer> onClose) {
        this.onClose = onClose;
    }

    @Override
    public void clicked(int slotId, int button, ContainerInput input, Player player) {
        if (!(player instanceof ServerPlayer serverPlayer) || slotId < 0 || slotId >= size) {
            // Clicks in the player's own inventory are ignored too, so nothing can be shift-clicked in.
            return;
        }
        Action action = actions.get(slotId);
        if (action == null || (input != ContainerInput.PICKUP && input != ContainerInput.QUICK_MOVE)) {
            return;
        }
        Click click = input == ContainerInput.QUICK_MOVE
                ? (button == 1 ? Click.SHIFT_RIGHT : Click.SHIFT_LEFT)
                : (button == 1 ? Click.RIGHT : Click.LEFT);
        // Run after the click packet is handled, so actions may open another screen safely.
        serverPlayer.level().getServer().execute(() -> {
            if (serverPlayer.containerMenu == this) {
                action.run(serverPlayer, click);
            }
        });
    }

    /** Runs a button's action straight away, as if clicked. Returns false if the slot isn't a button. */
    boolean press(int slot, Click click) {
        Action action = actions.get(slot);
        if (action == null) {
            return false;
        }
        action.run(owner, click);
        return true;
    }

    ItemStack itemAt(int slot) {
        return container.getItem(slot);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canDragTo(Slot slot) {
        return false;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (onClose != null && player instanceof ServerPlayer serverPlayer) {
            onClose.accept(serverPlayer);
        }
    }

    // ------------------------------------------------------------------ items

    /** An item with a name and lore. */
    static ItemStack item(Item type, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(type);
        item.set(DataComponents.CUSTOM_NAME, name);
        if (!lore.isEmpty()) {
            item.set(DataComponents.LORE, new ItemLore(lore));
        }
        return item;
    }

    static ItemStack glow(ItemStack item) {
        item.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return item;
    }

    private static ItemStack filler() {
        ItemStack item = new ItemStack(Items.STAINED_GLASS_PANE.gray());
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

    private static MenuType<ChestMenu> rowsType(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };
    }
}
