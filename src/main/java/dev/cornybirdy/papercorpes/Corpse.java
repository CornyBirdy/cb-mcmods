package dev.cornybirdy.papercorpes;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class Corpse {

    private final UUID id;
    private final UUID ownerId;
    private final String ownerName;
    private final String world;
    private final double x;
    private final double y;
    private final double z;
    private final float yaw;
    private final long createdAt;
    private final String cause;
    private final ItemStack[] contents;
    private final List<UUID> entityIds;
    private int xp;
    private boolean skeleton;

    Corpse(UUID id, UUID ownerId, String ownerName, Location location, long createdAt, String cause,
           ItemStack[] contents, int xp) {
        this(id, ownerId, ownerName, location.getWorld().getName(), location.getX(), location.getY(), location.getZ(),
                location.getYaw(), createdAt, cause, contents, new ArrayList<>(), xp, false);
    }

    private Corpse(UUID id, UUID ownerId, String ownerName, String world, double x, double y, double z, float yaw,
                   long createdAt, String cause, ItemStack[] contents, List<UUID> entityIds, int xp, boolean skeleton) {
        this.id = id;
        this.ownerId = ownerId;
        this.ownerName = ownerName;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.createdAt = createdAt;
        this.cause = cause;
        this.contents = contents;
        this.entityIds = entityIds;
        this.xp = xp;
        this.skeleton = skeleton;
    }

    UUID id() {
        return id;
    }

    UUID ownerId() {
        return ownerId;
    }

    String ownerName() {
        return ownerName;
    }

    long createdAt() {
        return createdAt;
    }

    String cause() {
        return cause;
    }

    float yaw() {
        return yaw;
    }

    /** Menu layout, see {@link CorpseLayout}. Changes are made in place. */
    ItemStack[] contents() {
        return contents;
    }

    /** UUIDs of the body, hitbox and skeleton display entities. */
    List<UUID> entityIds() {
        return entityIds;
    }

    int xp() {
        return xp;
    }

    void setXp(int xp) {
        this.xp = xp;
    }

    boolean skeleton() {
        return skeleton;
    }

    void setSkeleton(boolean skeleton) {
        this.skeleton = skeleton;
    }

    boolean isEmpty() {
        for (ItemStack item : contents) {
            if (!ItemCodec.isEmpty(item)) {
                return false;
            }
        }
        return true;
    }

    /** The corpse location, or null if its world isn't loaded. */
    Location location() {
        World w = Bukkit.getWorld(world);
        return w == null ? null : new Location(w, x, y, z, yaw, 0);
    }

    void write(ConfigurationSection out) {
        out.set("id", id.toString());
        out.set("owner", ownerId.toString());
        out.set("owner-name", ownerName);
        out.set("world", world);
        out.set("x", x);
        out.set("y", y);
        out.set("z", z);
        out.set("yaw", yaw);
        out.set("created", createdAt);
        out.set("cause", cause);
        out.set("xp", xp);
        out.set("skeleton", skeleton);
        out.set("entities", entityIds.stream().map(UUID::toString).toList());
        ItemCodec.write(out, "items", contents);
    }

    static Corpse read(ConfigurationSection in) {
        List<UUID> entities = new ArrayList<>();
        for (String s : in.getStringList("entities")) {
            entities.add(UUID.fromString(s));
        }
        return new Corpse(
                UUID.fromString(in.getString("id")),
                UUID.fromString(in.getString("owner")),
                in.getString("owner-name", "?"),
                in.getString("world"),
                in.getDouble("x"),
                in.getDouble("y"),
                in.getDouble("z"),
                (float) in.getDouble("yaw"),
                in.getLong("created"),
                in.getString("cause", ""),
                ItemCodec.read(in, "items", CorpseLayout.SIZE),
                entities,
                in.getInt("xp"),
                in.getBoolean("skeleton")
        );
    }
}
