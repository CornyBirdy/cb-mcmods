package dev.cornybirdy.gatekeeper;

import dev.cornybirdy.gatekeeper.PortalFile.Pos;
import org.bukkit.Axis;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.entity.Player;
import org.bukkit.event.world.PortalCreateEvent;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps nether portals dark while the Nether is off. Frames can still be lit, but the portal waits, and every portal
 * that was closed or lit in the meantime lights up the moment the Nether is turned back on. Portals in the Nether
 * itself are left alone.
 * <p>
 * One spot inside each closed portal is saved in {@code closed-nether-portals.yml}. Turning the Nether back on lights
 * a fire there, which makes the game light the portal like flint and steel would. Ones in chunks that aren't loaded
 * light up once their chunk loads.
 */
final class NetherPortals {

    private static final double WAITING_MESSAGE_DISTANCE = 8;
    private static final BlockData PORTAL_X = portal(Axis.X);
    private static final BlockData PORTAL_Z = portal(Axis.Z);

    private final Gatekeeper plugin;
    private final File file;
    /** A spot inside each portal taken out or kept dark while the Nether is off, by world name. */
    private final Map<String, Set<Pos>> closed = new HashMap<>();

    NetherPortals(Gatekeeper plugin) {
        this.plugin = plugin;
        file = new File(plugin.getDataFolder(), "closed-nether-portals.yml");
        PortalFile.load(file, closed);
    }

    /** Takes out every lit portal in loaded chunks. Portals in other chunks are closed as they load. */
    void closeAll() {
        boolean changed = false;
        for (World world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                changed |= closeIn(chunk);
            }
        }
        if (changed) {
            save();
        }
    }

    /** Called a tick after a chunk loads. */
    void chunkLoaded(Chunk chunk) {
        if (!chunk.isLoaded()) {
            return;
        }
        if (!plugin.isEnabled(Feature.NETHER)) {
            if (closeIn(chunk)) {
                save();
            }
        } else if (closed.containsKey(chunk.getWorld().getName())) {
            reopen();
        }
    }

    /** Lights the closed portals in loaded chunks again. */
    void reopen() {
        boolean changed = false;
        for (Iterator<Map.Entry<String, Set<Pos>>> worlds = closed.entrySet().iterator(); worlds.hasNext(); ) {
            Map.Entry<String, Set<Pos>> entry = worlds.next();
            World world = plugin.getServer().getWorld(entry.getKey());
            if (world == null) {
                continue;
            }
            // Copied, since lighting a portal changes blocks and could end up back here.
            for (Pos pos : List.copyOf(entry.getValue())) {
                if (!world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) {
                    continue;
                }
                entry.getValue().remove(pos);
                changed = true;
                Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
                // Don't replace anything a player built there in the meantime.
                if (block.getType().isAir() || Tag.FIRE.isTagged(block.getType())) {
                    if (!block.getType().isAir()) {
                        block.setType(Material.AIR);
                    }
                    block.setType(Material.FIRE);
                }
            }
            if (entry.getValue().isEmpty()) {
                worlds.remove();
            }
        }
        if (changed) {
            save();
        }
    }

    /** Keeps a portal someone lit dark while the Nether is off, and remembers it. */
    void portalCreated(PortalCreateEvent event) {
        World world = event.getWorld();
        if (event.getReason() != PortalCreateEvent.CreateReason.FIRE || plugin.isEnabled(Feature.NETHER)
                || world.getEnvironment() == World.Environment.NETHER || event.getBlocks().isEmpty()) {
            return;
        }
        event.setCancelled(true);
        BlockState inside = event.getBlocks().stream()
                .filter(state -> state.getType() == Material.NETHER_PORTAL)
                .findFirst()
                .orElse(event.getBlocks().getFirst());
        closed.computeIfAbsent(world.getName(), w -> new HashSet<>())
                .add(new Pos(inside.getX(), inside.getY(), inside.getZ()));
        save();

        if (event.getEntity() instanceof Player player) {
            player.sendMessage(plugin.message("nether-portal-waiting"));
            return;
        }
        Location center = inside.getLocation().toCenterLocation();
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(center) < WAITING_MESSAGE_DISTANCE * WAITING_MESSAGE_DISTANCE) {
                player.sendMessage(plugin.message("nether-portal-waiting"));
            }
        }
    }

    private boolean closeIn(Chunk chunk) {
        World world = chunk.getWorld();
        // Most chunks can say straight away that they have no portal blocks.
        if (world.getEnvironment() == World.Environment.NETHER || !(chunk.contains(PORTAL_X) || chunk.contains(PORTAL_Z))) {
            return false;
        }
        ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false);
        List<Block> found = new ArrayList<>();
        for (int y = world.getMinHeight(); y < world.getMaxHeight(); y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (snapshot.getBlockType(x, y, z) == Material.NETHER_PORTAL) {
                        found.add(chunk.getBlock(x, y, z));
                    }
                }
            }
        }
        boolean changed = false;
        for (Block block : found) {
            // Taking out one portal block makes the game break the rest of that portal.
            if (block.getType() == Material.NETHER_PORTAL) {
                block.setType(Material.AIR);
                closed.computeIfAbsent(world.getName(), w -> new HashSet<>())
                        .add(new Pos(block.getX(), block.getY(), block.getZ()));
                changed = true;
            }
        }
        return changed;
    }

    private void save() {
        PortalFile.save(file, closed, plugin.getLogger());
    }

    private static BlockData portal(Axis axis) {
        return Material.NETHER_PORTAL.createBlockData(data -> ((Orientable) data).setAxis(axis));
    }
}
