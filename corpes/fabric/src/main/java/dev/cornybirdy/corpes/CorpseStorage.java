package dev.cornybirdy.corpes;

import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Reads and writes corpse and history files in {@code <world>/corpes/}. Files are serialized on the main thread and
 * written by a single background thread, so writes to the same file always land in order. Items are saved with
 * the game's data version and upgraded by Minecraft's data fixer when loaded after an update.
 */
final class CorpseStorage {

    private static final Logger LOGGER = LoggerFactory.getLogger("Corpes");

    private final MinecraftServer server;
    private final RegistryOps<Tag> ops;
    private final Path corpseDir;
    private final Path historyDir;
    private final Map<UUID, List<HistoryEntry>> historyCache = new HashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Corpes-IO");
        thread.setDaemon(true);
        return thread;
    });

    CorpseStorage(MinecraftServer server) {
        this.server = server;
        this.ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        Path root = server.getWorldPath(LevelResource.ROOT).resolve("corpes");
        this.corpseDir = root.resolve("corpses");
        this.historyDir = root.resolve("history");
        try {
            Files.createDirectories(corpseDir);
            Files.createDirectories(historyDir);
        } catch (IOException e) {
            LOGGER.error("Could not create {}", root, e);
        }
    }

    List<Corpse> loadCorpses() {
        List<Corpse> corpses = new ArrayList<>();
        try (Stream<Path> files = Files.list(corpseDir)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".dat")).toList()) {
                try {
                    corpses.add(readCorpse(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())));
                } catch (Exception e) {
                    LOGGER.warn("Could not load corpse {}", file.getFileName(), e);
                }
            }
        } catch (IOException e) {
            LOGGER.error("Could not list {}", corpseDir, e);
        }
        return corpses;
    }

    void saveCorpse(Corpse corpse) {
        write(corpseDir.resolve(corpse.id + ".dat"), writeCorpse(corpse));
    }

    void deleteCorpse(UUID id) {
        Path file = corpseDir.resolve(id + ".dat");
        io.execute(() -> {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                LOGGER.warn("Could not delete {}", file.getFileName(), e);
            }
        });
    }

    /** Everyone with a history file. */
    List<UUID> historyOwners() {
        List<UUID> owners = new ArrayList<>();
        try (Stream<Path> files = Files.list(historyDir)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (name.endsWith(".dat")) {
                    try {
                        owners.add(UUID.fromString(name.substring(0, name.length() - 4)));
                    } catch (IllegalArgumentException ignored) {
                        // not one of ours
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.error("Could not list {}", historyDir, e);
        }
        return owners;
    }

    /** Deaths of a player, newest first. */
    List<HistoryEntry> history(UUID owner) {
        return historyCache.computeIfAbsent(owner, this::readHistory);
    }

    void addHistory(UUID owner, HistoryEntry entry, int maxEntries) {
        List<HistoryEntry> entries = history(owner);
        entries.addFirst(entry);
        while (entries.size() > maxEntries) {
            entries.removeLast();
        }
        CompoundTag root = new CompoundTag();
        ListTag list = new ListTag();
        for (HistoryEntry e : entries) {
            CompoundTag tag = new CompoundTag();
            tag.putLong("time", e.time());
            tag.putString("world", e.world());
            tag.putDouble("x", e.x());
            tag.putDouble("y", e.y());
            tag.putDouble("z", e.z());
            tag.putString("cause", e.cause());
            if (e.corpse() != null) {
                tag.putString("corpse", e.corpse().toString());
            }
            tag.put("items", writeItems(e.items()));
            list.add(tag);
        }
        root.put("entries", list);
        NbtUtils.addCurrentDataVersion(root);
        write(historyDir.resolve(owner + ".dat"), root);
    }

    /** Waits for pending writes to finish. */
    void close() {
        io.shutdown();
        try {
            if (!io.awaitTermination(10, TimeUnit.SECONDS)) {
                LOGGER.warn("Timed out waiting for corpse files to save");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private CompoundTag writeCorpse(Corpse corpse) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", corpse.id.toString());
        tag.putString("owner", corpse.ownerId.toString());
        tag.putString("owner-name", corpse.ownerName);
        tag.putString("world", corpse.world);
        tag.putDouble("x", corpse.x);
        tag.putDouble("y", corpse.y);
        tag.putDouble("z", corpse.z);
        tag.putFloat("yaw", corpse.yaw);
        tag.putLong("created", corpse.createdAt);
        tag.putString("cause", corpse.cause);
        tag.putInt("xp", corpse.xp);
        tag.putBoolean("skeleton", corpse.skeleton);
        tag.putString("entities", String.join(",", corpse.entityIds.stream().map(UUID::toString).toList()));
        tag.put("items", writeItems(corpse.contents));
        NbtUtils.addCurrentDataVersion(tag);
        return tag;
    }

    private Corpse readCorpse(CompoundTag tag) {
        int version = NbtUtils.getDataVersion(tag, currentVersion());
        List<UUID> entities = new ArrayList<>();
        for (String id : tag.getStringOr("entities", "").split(",")) {
            if (!id.isBlank()) {
                entities.add(UUID.fromString(id));
            }
        }
        return new Corpse(
                UUID.fromString(tag.getStringOr("id", "")),
                UUID.fromString(tag.getStringOr("owner", "")),
                tag.getStringOr("owner-name", "?"),
                tag.getStringOr("world", "minecraft:overworld"),
                tag.getDoubleOr("x", 0),
                tag.getDoubleOr("y", 0),
                tag.getDoubleOr("z", 0),
                tag.getFloatOr("yaw", 0),
                tag.getLongOr("created", 0),
                tag.getStringOr("cause", ""),
                readItems(tag.getListOrEmpty("items"), version),
                entities,
                tag.getIntOr("xp", 0),
                tag.getBooleanOr("skeleton", false));
    }

    private List<HistoryEntry> readHistory(UUID owner) {
        List<HistoryEntry> entries = new ArrayList<>();
        Path file = historyDir.resolve(owner + ".dat");
        if (!Files.exists(file)) {
            return entries;
        }
        try {
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            int version = NbtUtils.getDataVersion(root, currentVersion());
            root.getListOrEmpty("entries").compoundStream().forEach(tag -> entries.add(new HistoryEntry(
                    tag.getLongOr("time", 0),
                    tag.getStringOr("world", "?"),
                    tag.getDoubleOr("x", 0),
                    tag.getDoubleOr("y", 0),
                    tag.getDoubleOr("z", 0),
                    tag.getStringOr("cause", ""),
                    readItems(tag.getListOrEmpty("items"), version),
                    parseId(tag.getStringOr("corpse", "")))));
        } catch (Exception e) {
            LOGGER.warn("Could not read death history {}", file.getFileName(), e);
        }
        return entries;
    }

    private ListTag writeItems(ItemStack[] items) {
        ListTag list = new ListTag();
        for (int slot = 0; slot < items.length; slot++) {
            if (items[slot].isEmpty()) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.putInt("Slot", slot);
            entry.store("item", ItemStack.CODEC, ops, items[slot]);
            list.add(entry);
        }
        return list;
    }

    private ItemStack[] readItems(ListTag list, int dataVersion) {
        ItemStack[] items = CorpseLayout.empty();
        int current = currentVersion();
        list.compoundStream().forEach(entry -> {
            int slot = entry.getIntOr("Slot", -1);
            Tag itemTag = entry.get("item");
            if (slot < 0 || slot >= items.length || itemTag == null) {
                return;
            }
            if (dataVersion < current) {
                itemTag = server.getFixerUpper()
                        .update(References.ITEM_STACK, new Dynamic<>(NbtOps.INSTANCE, itemTag), dataVersion, current)
                        .getValue();
            }
            items[slot] = ItemStack.CODEC.parse(ops, itemTag)
                    .resultOrPartial(error -> LOGGER.warn("Could not load item in slot {}: {}", slot, error))
                    .orElse(ItemStack.EMPTY);
        });
        return items;
    }

    private static UUID parseId(String id) {
        try {
            return id.isEmpty() ? null : UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int currentVersion() {
        return SharedConstants.getCurrentVersion().dataVersion().version();
    }

    private void write(Path file, CompoundTag tag) {
        io.execute(() -> {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try {
                NbtIo.writeCompressed(tag, tmp);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                LOGGER.error("Could not save {}", file.getFileName(), e);
            }
        });
    }
}
