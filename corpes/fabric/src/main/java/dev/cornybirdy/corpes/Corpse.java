package dev.cornybirdy.corpes;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One corpse: who died, where, and what is left in it. */
final class Corpse {

    final UUID id;
    final UUID ownerId;
    final String ownerName;
    /** Dimension id, e.g. {@code minecraft:overworld}. */
    final String world;
    final double x;
    final double y;
    final double z;
    final float yaw;
    final long createdAt;
    final String cause;
    /** Menu layout, see {@link CorpseLayout}. Changes are made in place. */
    final ItemStack[] contents;
    /** UUIDs of the body, hitbox and skeleton display entities. */
    final List<UUID> entityIds;
    int xp;
    boolean skeleton;

    Corpse(UUID id, UUID ownerId, String ownerName, String world, double x, double y, double z, float yaw,
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
        this.entityIds = new ArrayList<>(entityIds);
        this.xp = xp;
        this.skeleton = skeleton;
    }

    boolean isEmpty() {
        return stacksLeft() == 0;
    }

    int stacksLeft() {
        return CorpseLayout.countStacks(contents);
    }

    /** When, where and how this player died, with the items still in the corpse. */
    HistoryEntry death() {
        return new HistoryEntry(createdAt, world, x, y, z, cause, contents, id);
    }
}
