package dev.cornybirdy.papercorpes;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * A snapshot of one death, kept even after the corpse is looted.
 *
 * @param corpse the corpse this death left behind, or null for entries saved before corpses were linked
 */
record HistoryEntry(long time, String world, double x, double y, double z, String cause, ItemStack[] items,
                    UUID corpse) {

    static HistoryEntry of(Corpse corpse, Location location) {
        return new HistoryEntry(corpse.createdAt(), location.getWorld().getName(), location.getX(), location.getY(),
                location.getZ(), corpse.cause(), ItemCodec.copy(corpse.contents()), corpse.id());
    }

    void write(ConfigurationSection out) {
        out.set("time", time);
        out.set("world", world);
        out.set("x", x);
        out.set("y", y);
        out.set("z", z);
        out.set("cause", cause);
        if (corpse != null) {
            out.set("corpse", corpse.toString());
        }
        out.set("layout", CorpseLayout.VERSION);
        ItemCodec.write(out, "items", items);
    }

    static HistoryEntry read(ConfigurationSection in) {
        String corpseId = in.getString("corpse");
        return new HistoryEntry(
                in.getLong("time"),
                in.getString("world", "?"),
                in.getDouble("x"),
                in.getDouble("y"),
                in.getDouble("z"),
                in.getString("cause", ""),
                CorpseLayout.upgrade(ItemCodec.read(in, "items", CorpseLayout.SIZE), in.getInt("layout", 1)),
                corpseId == null ? null : UUID.fromString(corpseId)
        );
    }
}
