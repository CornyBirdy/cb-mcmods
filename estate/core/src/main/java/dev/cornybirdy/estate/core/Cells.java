package dev.cornybirdy.estate.core;

/** Packs two coordinates (like a chunk's x and z) into one long: x in the high 32 bits, z in the low 32 bits. */
public final class Cells {

    private Cells() {
    }

    public static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public static int x(long key) {
        return (int) (key >> 32);
    }

    public static int z(long key) {
        return (int) key;
    }
}
