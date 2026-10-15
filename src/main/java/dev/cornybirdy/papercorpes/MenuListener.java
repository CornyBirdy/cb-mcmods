package dev.cornybirdy.papercorpes;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.Set;

/** Lets players take items out of a corpse but never put any in, so corpses can't be used as storage. */
final class MenuListener implements Listener {

    private static final Set<InventoryAction> TAKE_ACTIONS = EnumSet.of(
            InventoryAction.PICKUP_ALL,
            InventoryAction.PICKUP_HALF,
            InventoryAction.PICKUP_ONE,
            InventoryAction.PICKUP_SOME,
            InventoryAction.MOVE_TO_OTHER_INVENTORY,
            InventoryAction.DROP_ALL_SLOT,
            InventoryAction.DROP_ONE_SLOT,
            InventoryAction.HOTBAR_SWAP
    );

    private final PaperCorpes plugin;
    private final CorpseManager manager;

    MenuListener(PaperCorpes plugin, CorpseManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof CorpseMenu menu) || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        int rawSlot = event.getRawSlot();
        boolean inCorpse = rawSlot >= 0 && rawSlot < top.getSize();
        if (inCorpse) {
            if (rawSlot == CorpseLayout.EQUIP_ALL_SLOT || rawSlot == CorpseLayout.TAKE_ALL_SLOT) {
                event.setCancelled(true);
                boolean equip = rawSlot == CorpseLayout.EQUIP_ALL_SLOT;
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (equip) {
                        menu.equipAll(player);
                    } else {
                        menu.takeAll(player);
                    }
                    manager.changed(menu);
                });
                return;
            }
            if (menu.isUi(event.getCurrentItem()) || !TAKE_ACTIONS.contains(event.getAction())
                    || (event.getAction() == InventoryAction.HOTBAR_SWAP && !swapTargetEmpty(event, player))) {
                event.setCancelled(true);
                return;
            }
        } else if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY
                || event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            event.setCancelled(true);
            return;
        }

        plugin.getServer().getScheduler().runTask(plugin, () -> manager.changed(menu));
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof CorpseMenu)) {
            return;
        }
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < top.getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder(false) instanceof CorpseMenu menu) {
            manager.menuClosed(menu, event.getPlayer());
        }
    }

    /** A hotbar swap only takes from the corpse if the hotbar or offhand slot is empty; otherwise it would put an item in. */
    private static boolean swapTargetEmpty(InventoryClickEvent event, Player player) {
        ItemStack target = event.getClick() == ClickType.SWAP_OFFHAND || event.getHotbarButton() < 0
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItem(event.getHotbarButton());
        return ItemCodec.isEmpty(target);
    }
}
