package dev.cornybirdy.papercorpes;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Reads and writes corpse and history files. Files are serialized on the main thread and written by a single
 * background thread, so writes to the same file always land in order.
 */
final class CorpseStorage {

    private final PaperCorpes plugin;
    private final File corpseDir;
    private final File historyDir;
    private final Map<UUID, List<HistoryEntry>> historyCache = new HashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "PaperCorpes-IO");
        thread.setDaemon(true);
        return thread;
    });

    CorpseStorage(PaperCorpes plugin) {
        this.plugin = plugin;
        this.corpseDir = new File(plugin.getDataFolder(), "corpses");
        this.historyDir = new File(plugin.getDataFolder(), "history");
        corpseDir.mkdirs();
        historyDir.mkdirs();
    }

    List<Corpse> loadCorpses() {
        List<Corpse> corpses = new ArrayList<>();
        File[] files = corpseDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return corpses;
        }
        for (File file : files) {
            try {
                corpses.add(Corpse.read(YamlConfiguration.loadConfiguration(file)));
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Could not load corpse " + file.getName(), e);
            }
        }
        return corpses;
    }

    void saveCorpse(Corpse corpse) {
        YamlConfiguration yaml = new YamlConfiguration();
        corpse.write(yaml);
        write(corpseFile(corpse.id()), yaml.saveToString());
    }

    void deleteCorpse(UUID id) {
        File file = corpseFile(id);
        io.execute(() -> {
            try {
                Files.deleteIfExists(file.toPath());
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Could not delete " + file.getName(), e);
            }
        });
    }

    /** Everyone with a history file. */
    List<UUID> historyOwners() {
        List<UUID> owners = new ArrayList<>();
        File[] files = historyDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                try {
                    owners.add(UUID.fromString(file.getName().substring(0, file.getName().length() - 4)));
                } catch (IllegalArgumentException ignored) {
                    // not one of ours
                }
            }
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
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection section = yaml.createSection("entries");
        for (int i = 0; i < entries.size(); i++) {
            entries.get(i).write(section.createSection(String.valueOf(i)));
        }
        write(historyFile(owner), yaml.saveToString());
    }

    /** Waits for pending writes to finish. */
    void close() {
        io.shutdown();
        try {
            if (!io.awaitTermination(10, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Timed out waiting for corpse files to save");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<HistoryEntry> readHistory(UUID owner) {
        List<HistoryEntry> entries = new ArrayList<>();
        File file = historyFile(owner);
        if (!file.exists()) {
            return entries;
        }
        ConfigurationSection section = YamlConfiguration.loadConfiguration(file).getConfigurationSection("entries");
        if (section == null) {
            return entries;
        }
        section.getKeys(false).stream()
                .sorted(Comparator.comparingInt(CorpseStorage::parseIndex))
                .forEach(key -> {
                    try {
                        entries.add(HistoryEntry.read(section.getConfigurationSection(key)));
                    } catch (Exception e) {
                        plugin.getLogger().log(Level.WARNING, "Skipping broken history entry in " + file.getName(), e);
                    }
                });
        return entries;
    }

    private static int parseIndex(String key) {
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private void write(File file, String data) {
        io.execute(() -> {
            Path target = file.toPath();
            Path tmp = target.resolveSibling(file.getName() + ".tmp");
            try {
                Files.writeString(tmp, data, StandardCharsets.UTF_8);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not save " + file.getName(), e);
            }
        });
    }

    private File corpseFile(UUID id) {
        return new File(corpseDir, id + ".yml");
    }

    private File historyFile(UUID owner) {
        return new File(historyDir, owner + ".yml");
    }
}
