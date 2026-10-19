package dev.cornybirdy.endlock;

import org.bukkit.Material;
import org.bukkit.PortalType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.EndPortalFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

final class LockListener implements Listener {

    private final EndLock plugin;
    private final Portals portals;

    LockListener(EndLock plugin, Portals portals) {
        this.plugin = plugin;
        this.portals = portals;
    }

    // Ender chests

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || block == null || block.getType() != Material.ENDER_CHEST) {
            return;
        }
        Player player = event.getPlayer();
        PlayerInventory inventory = player.getInventory();
        // Like vanilla: sneaking with something in either hand uses the item instead of opening the chest.
        if (player.isSneaking() && (!inventory.getItemInMainHand().isEmpty() || !inventory.getItemInOffHand().isEmpty())) {
            return;
        }
        if (plugin.enderChestsBanned(player)) {
            // Only the chest is blocked, so blocks can still be placed against it.
            event.setUseInteractedBlock(Event.Result.DENY);
            plugin.warn(player, "ender-chest-blocked");
        }
    }

    /** Also catches ender chests opened by other plugins, such as an /enderchest command. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (event.getInventory().getType() == InventoryType.ENDER_CHEST
                && event.getPlayer() instanceof Player player && plugin.enderChestsBanned(player)) {
            event.setCancelled(true);
            plugin.warn(player, "ender-chest-blocked");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() == Material.ENDER_CHEST && plugin.enderChestsBanned(event.getPlayer())) {
            event.setCancelled(true);
            plugin.warn(event.getPlayer(), "ender-chest-blocked");
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        ItemStack result = event.getInventory().getResult();
        if (result != null && result.getType() == Material.ENDER_CHEST
                && event.getView().getPlayer() instanceof Player player && plugin.enderChestsBanned(player)) {
            event.getInventory().setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCrafter(CrafterCraftEvent event) {
        if (event.getResult().getType() == Material.ENDER_CHEST && !plugin.isEnabled(Feature.ENDER_CHESTS)) {
            event.setCancelled(true);
        }
    }

    // The End

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.END_PORTAL && !inEnd(event.getFrom().getWorld())
                && plugin.endBanned(event.getPlayer())) {
            event.setCancelled(true);
            plugin.warn(event.getPlayer(), "end-blocked");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        if (event.getPortalType() == PortalType.ENDER && !inEnd(event.getFrom().getWorld())
                && !plugin.isEnabled(Feature.END)) {
            event.setCancelled(true);
        }
    }

    /** While the End is off, eyes still go into frames but the portal waits until it's turned back on. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEye(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || block == null || event.getHand() == null
                || event.getItem() == null || event.getItem().getType() != Material.ENDER_EYE
                || !(block.getBlockData() instanceof EndPortalFrame frame) || frame.hasEye()
                || plugin.isEnabled(Feature.END) || inEnd(block.getWorld())) {
            return;
        }
        event.setCancelled(true);
        portals.placeEye(event.getPlayer(), block, event.getHand());
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        // A tick later, so the chunk has finished loading before blocks in it change.
        plugin.getServer().getScheduler().runTask(plugin, () -> portals.chunkLoaded(event.getChunk()));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        checkInEnd(event.getPlayer());
    }

    /** Catches /tp and other plugins moving players into the End. */
    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        checkInEnd(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.forget(event.getPlayer());
    }

    private void checkInEnd(Player player) {
        // A tick later, so the player has finished joining or moving worlds first.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && inEnd(player.getWorld()) && plugin.endBanned(player)) {
                plugin.sendToSpawn(player);
            }
        });
    }

    private static boolean inEnd(World world) {
        return world != null && world.getEnvironment() == World.Environment.THE_END;
    }
}
