package dev.cornybirdy.corpes;

import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * A snapshot of one death, kept even after the corpse is looted.
 *
 * @param corpse the corpse this death left behind, or null for entries saved before corpses were linked
 */
record HistoryEntry(long time, String world, double x, double y, double z, String cause, ItemStack[] items, UUID corpse) {
}
