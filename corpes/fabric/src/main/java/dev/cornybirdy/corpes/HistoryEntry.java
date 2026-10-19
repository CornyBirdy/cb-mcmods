package dev.cornybirdy.corpes;

import net.minecraft.world.item.ItemStack;

/** A snapshot of one death, kept even after the corpse is looted. */
record HistoryEntry(long time, String world, double x, double y, double z, String cause, ItemStack[] items) {
}
