package dev.cornybirdy.gatekeeper;

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
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

final class LockListener implements Listener {

    private final Gatekeeper plugin;
    private final EndPortals endPortals;
    private final NetherPortals netherPortals;

    LockListener(Gatekeeper plugin, EndPortals endPortals, NetherPortals netherPortals) {
        this.plugin = plugin;
        this.endPortals = endPortals;
        this.netherPortals = netherPortals;
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

    // The End and the Nether. Portals outside them are taken out while they're off, so these only matter for one
    // that slips through, such as one placed with /setblock.

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        World from = event.getFrom().getWorld();
        Player player = event.getPlayer();
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.END_PORTAL && !in(from, World.Environment.THE_END)
                && plugin.endBanned(player)) {
            event.setCancelled(true);
            plugin.warn(player, "end-blocked");
        } else if (event.getCause() == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
                && !in(from, World.Environment.NETHER) && plugin.netherBanned(player)) {
            event.setCancelled(true);
            plugin.warn(player, "nether-blocked");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        World from = event.getFrom().getWorld();
        if ((event.getPortalType() == PortalType.ENDER && !in(from, World.Environment.THE_END) && !plugin.isEnabled(Feature.END))
                || (event.getPortalType() == PortalType.NETHER && !in(from, World.Environment.NETHER)
                && !plugin.isEnabled(Feature.NETHER))) {
            event.setCancelled(true);
        }
    }

    /** While the Nether is off, frames still catch fire but the portal waits until it's turned back on. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortalCreate(PortalCreateEvent event) {
        netherPortals.portalCreated(event);
    }

    /** While the End is off, eyes still go into frames but the portal waits until it's turned back on. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEye(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || block == null || event.getHand() == null
                || event.getItem() == null || event.getItem().getType() != Material.ENDER_EYE
                || !(block.getBlockData() instanceof EndPortalFrame frame) || frame.hasEye()
                || plugin.isEnabled(Feature.END) || in(block.getWorld(), World.Environment.THE_END)) {
            return;
        }
        event.setCancelled(true);
        endPortals.placeEye(event.getPlayer(), block, event.getHand());
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        // A tick later, so the chunk has finished loading before blocks in it change.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            endPortals.chunkLoaded(event.getChunk());
            netherPortals.chunkLoaded(event.getChunk());
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        checkDimension(event.getPlayer());
    }

    /** Catches /tp and other plugins moving players into the End or the Nether. */
    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        checkDimension(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.forget(event.getPlayer());
    }

    private void checkDimension(Player player) {
        // A tick later, so the player has finished joining or moving worlds first.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                plugin.sendOutIfClosed(player);
            }
        });
    }

    private static boolean in(World world, World.Environment environment) {
        return world != null && world.getEnvironment() == environment;
    }
}
