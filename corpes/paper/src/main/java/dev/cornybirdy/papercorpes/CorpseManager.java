package dev.cornybirdy.papercorpes;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Keeps track of all corpses and their open menus. */
final class CorpseManager {

    private final PaperCorpes plugin;
    private final CorpseStorage storage;
    private final CorpseVisuals visuals;
    private final Map<UUID, Corpse> corpses = new HashMap<>();
    private final Map<UUID, CorpseMenu> menus = new HashMap<>();

    CorpseManager(PaperCorpes plugin, CorpseStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
        this.visuals = new CorpseVisuals(plugin);
    }

    void loadAll() {
        for (Corpse corpse : storage.loadCorpses()) {
            corpses.put(corpse.id(), corpse);
        }
        plugin.getLogger().info("Loaded " + corpses.size() + " corpses");
    }

    CorpseVisuals visuals() {
        return visuals;
    }

    Corpse get(UUID id) {
        return corpses.get(id);
    }

    void create(Player player, Location location, ItemStack[] contents, int xp, String cause) {
        Corpse corpse = new Corpse(UUID.randomUUID(), player.getUniqueId(), player.getName(), location,
                System.currentTimeMillis(), cause, contents, xp);
        storage.addHistory(player.getUniqueId(), HistoryEntry.of(corpse, location), plugin.settings().historySize());
        visuals.spawnBody(corpse, player.getPlayerProfile());
        corpses.put(corpse.id(), corpse);
        storage.saveCorpse(corpse);
    }

    void open(Player viewer, Corpse corpse) {
        if (corpse.xp() > 0) {
            viewer.giveExp(corpse.xp());
            corpse.setXp(0);
            storage.saveCorpse(corpse);
        }
        CorpseMenu menu = menus.computeIfAbsent(corpse.id(), id -> new CorpseMenu(plugin, corpse));
        viewer.openInventory(menu.getInventory());
    }

    /** Called after a menu changed: saves the corpse, or removes it once it's empty. */
    void changed(CorpseMenu menu) {
        Corpse corpse = menu.corpse();
        if (!corpses.containsKey(corpse.id())) {
            return;
        }
        menu.sync();
        if (corpse.isEmpty()) {
            remove(corpse);
        } else {
            storage.saveCorpse(corpse);
            menu.render();
        }
    }

    void menuClosed(CorpseMenu menu, HumanEntity viewer) {
        boolean othersStillLooking = menu.getInventory().getViewers().stream().anyMatch(v -> v != viewer);
        if (!othersStillLooking) {
            menus.remove(menu.corpse().id(), menu);
        }
    }

    void remove(Corpse corpse) {
        corpses.remove(corpse.id());
        CorpseMenu menu = menus.remove(corpse.id());
        if (menu != null) {
            new ArrayList<>(menu.getInventory().getViewers()).forEach(HumanEntity::closeInventory);
        }
        visuals.removeAll(corpse);
        storage.deleteCorpse(corpse.id());
    }

    /** Turns corpses into skeletons once they're old enough. Runs every 30 seconds. */
    void tickSkeletons() {
        for (Corpse corpse : corpses.values()) {
            if (isSkeletonDue(corpse) && visuals.skeletonize(corpse)) {
                storage.saveCorpse(corpse);
            }
        }
    }

    /**
     * Checks a corpse entity that just loaded with its chunk. Entities of corpses that were emptied while
     * unloaded are removed, and overdue bodies become skeletons.
     */
    void entityLoaded(Entity entity) {
        UUID id = visuals.corpseId(entity);
        if (id == null) {
            return;
        }
        Corpse corpse = corpses.get(id);
        if (corpse == null) {
            entity.remove();
        } else if (entity instanceof Mannequin && isSkeletonDue(corpse)) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (corpses.containsKey(id) && isSkeletonDue(corpse) && visuals.skeletonize(corpse)) {
                    storage.saveCorpse(corpse);
                }
            });
        }
    }

    void shutdown() {
        for (CorpseMenu menu : new ArrayList<>(menus.values())) {
            changed(menu);
            new ArrayList<>(menu.getInventory().getViewers()).forEach(HumanEntity::closeInventory);
        }
        menus.clear();
    }

    private boolean isSkeletonDue(Corpse corpse) {
        int minutes = plugin.settings().skeletonAfterMinutes();
        return minutes > 0 && !corpse.skeleton()
                && System.currentTimeMillis() - corpse.createdAt() >= minutes * 60_000L;
    }
}
