package dev.cornybirdy.endlock;

import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.type.EndPortalFrame;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps end portals dark while the End is off. Eyes can still be put in the frames, and every portal that would be
 * lit, or was lit before, lights up the moment the End is turned back on. Portals in the End itself are left alone.
 * <p>
 * The portal blocks that were taken out are saved in {@code closed-portals.yml}. Ones in chunks that aren't loaded
 * when the End is turned on light up once their chunk loads.
 */
final class Portals {

    /** The 12 frames around a portal, relative to its north-west portal block, each facing into the portal. */
    private static final List<Frame> FRAMES = frames();

    private final EndLock plugin;
    private final File file;
    /** Portal blocks taken out while the End is off, by world name. */
    private final Map<String, Set<Pos>> closed = new HashMap<>();

    Portals(EndLock plugin) {
        this.plugin = plugin;
        file = new File(plugin.getDataFolder(), "closed-portals.yml");
        load();
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
        if (!plugin.isEnabled(Feature.END)) {
            if (closeIn(chunk)) {
                save();
            }
        } else if (closed.containsKey(chunk.getWorld().getName())) {
            reopen(false);
        }
    }

    /**
     * Lights the closed portals in loaded chunks again. With {@code announce}, everyone hears the portal open, like
     * when the last eye goes in.
     */
    void reopen(boolean announce) {
        boolean changed = false;
        boolean lit = false;
        for (Iterator<Map.Entry<String, Set<Pos>>> worlds = closed.entrySet().iterator(); worlds.hasNext(); ) {
            Map.Entry<String, Set<Pos>> entry = worlds.next();
            World world = plugin.getServer().getWorld(entry.getKey());
            if (world == null) {
                continue;
            }
            for (Iterator<Pos> positions = entry.getValue().iterator(); positions.hasNext(); ) {
                Pos pos = positions.next();
                if (!world.isChunkLoaded(pos.x >> 4, pos.z >> 4)) {
                    continue;
                }
                positions.remove();
                changed = true;
                Block block = world.getBlockAt(pos.x, pos.y, pos.z);
                // Don't replace anything a player built there in the meantime.
                if (block.getType().isAir() || block.isLiquid()) {
                    block.setType(Material.END_PORTAL);
                    lit = true;
                }
            }
            if (entry.getValue().isEmpty()) {
                worlds.remove();
            }
        }
        if (announce && lit) {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                player.playSound(player.getLocation(), Sound.BLOCK_END_PORTAL_SPAWN, 1f, 1f);
            }
        }
        if (changed) {
            save();
        }
    }

    /** Puts an eye in a frame like vanilla does, but remembers the portal instead of lighting it. */
    void placeEye(Player player, Block frame, EquipmentSlot hand) {
        EndPortalFrame data = (EndPortalFrame) frame.getBlockData();
        data.setEye(true);
        frame.setBlockData(data);
        frame.getWorld().playSound(frame.getLocation().toCenterLocation(), Sound.BLOCK_END_PORTAL_FRAME_FILL, 1f, 1f);
        player.swingHand(hand);
        if (player.getGameMode() != GameMode.CREATIVE) {
            ItemStack eyes = player.getInventory().getItem(hand);
            eyes.setAmount(eyes.getAmount() - 1);
            player.getInventory().setItem(hand, eyes);
        }
        Block corner = completedPortal(frame);
        if (corner != null) {
            Set<Pos> positions = closed.computeIfAbsent(frame.getWorld().getName(), w -> new HashSet<>());
            for (int x = 0; x < 3; x++) {
                for (int z = 0; z < 3; z++) {
                    positions.add(new Pos(corner.getX() + x, corner.getY(), corner.getZ() + z));
                }
            }
            save();
            player.sendMessage(plugin.message("portal-waiting"));
        }
    }

    /** The north-west portal block of the portal this frame completes, or null if a frame is still empty. */
    static Block completedPortal(Block frame) {
        for (Frame candidate : FRAMES) {
            Block corner = frame.getRelative(-candidate.x, 0, -candidate.z);
            boolean complete = true;
            for (Frame other : FRAMES) {
                Block block = corner.getRelative(other.x, 0, other.z);
                if (!(block.getBlockData() instanceof EndPortalFrame data) || !data.hasEye()
                        || data.getFacing() != other.facing) {
                    complete = false;
                    break;
                }
            }
            if (complete) {
                return corner;
            }
        }
        return null;
    }

    private boolean closeIn(Chunk chunk) {
        if (chunk.getWorld().getEnvironment() == World.Environment.THE_END) {
            return false;
        }
        boolean changed = false;
        for (BlockState state : chunk.getTileEntities(false)) {
            if (state.getType() == Material.END_PORTAL) {
                Block block = state.getBlock();
                block.setType(Material.AIR);
                closed.computeIfAbsent(chunk.getWorld().getName(), w -> new HashSet<>())
                        .add(new Pos(block.getX(), block.getY(), block.getZ()));
                changed = true;
            }
        }
        return changed;
    }

    private static List<Frame> frames() {
        List<Frame> frames = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            frames.add(new Frame(i, -1, BlockFace.SOUTH));
            frames.add(new Frame(i, 3, BlockFace.NORTH));
            frames.add(new Frame(-1, i, BlockFace.EAST));
            frames.add(new Frame(3, i, BlockFace.WEST));
        }
        return List.copyOf(frames);
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String world : yaml.getKeys(false)) {
            Set<Pos> positions = new HashSet<>();
            for (String line : yaml.getStringList(world)) {
                Pos pos = Pos.parse(line);
                if (pos != null) {
                    positions.add(pos);
                }
            }
            if (!positions.isEmpty()) {
                closed.put(world, positions);
            }
        }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of("End portal blocks EndLock took out while the End is off.",
                "They light up again when the End is turned back on. Don't edit this file."));
        for (Map.Entry<String, Set<Pos>> entry : closed.entrySet()) {
            yaml.set(entry.getKey(), entry.getValue().stream().map(Pos::toString).sorted().toList());
        }
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save " + file + ": " + e.getMessage());
        }
    }

    private record Frame(int x, int z, BlockFace facing) {
    }

    private record Pos(int x, int y, int z) {

        static Pos parse(String text) {
            String[] parts = text.split(",");
            try {
                return new Pos(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()),
                        Integer.parseInt(parts[2].trim()));
            } catch (RuntimeException e) {
                return null;
            }
        }

        @Override
        public String toString() {
            return x + "," + y + "," + z;
        }
    }
}
