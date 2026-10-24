package dev.cornybirdy.estate.core;

/** An admin-defined area where buffers don't apply, so people can build right next to each other. */
public record TownZone(int id, String world, String name, int minX, int minZ, int maxX, int maxZ) {

    public boolean contains(String world, int x, int z) {
        return this.world.equals(world) && x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    public Region.Box box() {
        return new Region.Box(minX, minZ, maxX, maxZ);
    }
}
