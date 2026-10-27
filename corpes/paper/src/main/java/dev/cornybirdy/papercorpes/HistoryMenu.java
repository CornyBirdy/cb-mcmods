package dev.cornybirdy.papercorpes;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * One screen of the death browser: the list of someone's deaths, the list of players, or a single death's inventory.
 * It belongs to the player it was opened for, so nothing done here changes the history or any corpse. Buttons run
 * the action they were given; every other item can only be taken out (as a copy) if {@code takeable}.
 */
final class HistoryMenu implements InventoryHolder {

    /** What a button does. {@code rightClick} is true for a right-click. */
    interface Click {
        void on(Player player, boolean rightClick);
    }

    private final Inventory inventory;
    private final boolean takeable;
    private final Map<Integer, Click> buttons = new HashMap<>();

    HistoryMenu(Component title, boolean takeable) {
        this.inventory = Bukkit.createInventory(this, CorpseLayout.SIZE, title);
        this.takeable = takeable;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** Whether items in this menu can be taken out, as copies. Only for admins with {@code admin-restore} on. */
    boolean takeable() {
        return takeable;
    }

    void button(int slot, ItemStack item, Click click) {
        inventory.setItem(slot, item);
        buttons.put(slot, click);
    }

    void decoration(int slot, ItemStack item) {
        inventory.setItem(slot, item);
    }

    Click button(int slot) {
        return buttons.get(slot);
    }
}
