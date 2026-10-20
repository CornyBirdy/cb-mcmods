package dev.cornybirdy.gatekeeper;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/** Reads and writes the closed portal files: one list of {@code "x,y,z"} spots per world. */
final class PortalFile {

    private PortalFile() {
    }

    static void load(File file, Map<String, Set<Pos>> into) {
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
                into.put(world, positions);
            }
        }
    }

    static void save(File file, Map<String, Set<Pos>> positionsByWorld, Logger logger) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of("Portals Gatekeeper closed. They light up again when they're turned back on.",
                "Don't edit this file."));
        for (Map.Entry<String, Set<Pos>> entry : positionsByWorld.entrySet()) {
            yaml.set(entry.getKey(), entry.getValue().stream().map(Pos::toString).sorted().toList());
        }
        try {
            yaml.save(file);
        } catch (IOException e) {
            logger.severe("Could not save " + file + ": " + e.getMessage());
        }
    }

    /** A block position, saved as {@code "x,y,z"}. */
    record Pos(int x, int y, int z) {

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
