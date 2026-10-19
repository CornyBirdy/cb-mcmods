package dev.cornybirdy.gatekeeper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Reads and writes the closed portal files: {@code {"minecraft:overworld": [[x, y, z], ...]}}. */
final class PortalFile {

    private static final Logger LOGGER = LoggerFactory.getLogger("Gatekeeper");

    private PortalFile() {
    }

    static void load(Path file, Map<String, Set<BlockPos>> into) {
        if (!Files.exists(file)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                Set<BlockPos> positions = new HashSet<>();
                for (JsonElement element : entry.getValue().getAsJsonArray()) {
                    JsonArray xyz = element.getAsJsonArray();
                    positions.add(new BlockPos(xyz.get(0).getAsInt(), xyz.get(1).getAsInt(), xyz.get(2).getAsInt()));
                }
                if (!positions.isEmpty()) {
                    into.put(entry.getKey(), positions);
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Could not read {}, closed portals in it won't light up again by themselves", file, e);
        }
    }

    static void save(Path file, Map<String, Set<BlockPos>> positionsByDimension) {
        JsonObject root = new JsonObject();
        for (Map.Entry<String, Set<BlockPos>> entry : positionsByDimension.entrySet()) {
            JsonArray positions = new JsonArray();
            for (BlockPos pos : entry.getValue()) {
                JsonArray xyz = new JsonArray();
                xyz.add(pos.getX());
                xyz.add(pos.getY());
                xyz.add(pos.getZ());
                positions.add(xyz);
            }
            root.add(entry.getKey(), positions);
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("Could not save {}", file, e);
        }
    }
}
