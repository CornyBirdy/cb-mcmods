package dev.cornybirdy.corpes;

import dev.cornybirdy.corpes.mixin.LivingEntityAccessor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Keeps track of all corpses and their open menus. Lives as long as the server. */
final class CorpseManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("Corpes");

    private final MinecraftServer server;
    private final Supplier<Settings> settings;
    private final CorpseStorage storage;
    private final CorpseVisuals visuals;
    private final Map<UUID, Corpse> corpses = new HashMap<>();
    private final Map<UUID, CorpseContainer> containers = new HashMap<>();
    private final Set<CorpseContainer> dirty = new LinkedHashSet<>();
    private final Set<UUID> pendingSkeletons = new LinkedHashSet<>();
    private final Set<Entity> pendingRemovals = new LinkedHashSet<>();

    CorpseManager(MinecraftServer server, Supplier<Settings> settings, CorpseStorage storage) {
        this.server = server;
        this.settings = settings;
        this.storage = storage;
        this.visuals = new CorpseVisuals(server);
        for (Corpse corpse : storage.loadCorpses()) {
            corpses.put(corpse.id, corpse);
        }
        LOGGER.info("Loaded {} corpses", corpses.size());
    }

    CorpseStorage storage() {
        return storage;
    }

    Corpse get(UUID id) {
        return corpses.get(id);
    }

    /**
     * Called as a dead player's inventory is about to drop. Moves everything into a new corpse, so nothing is left to
     * drop. Curse of vanishing and keepInventory have already been handled by the game at this point.
     */
    void onDeathDrops(ServerPlayer player, Inventory inventory) {
        if (inventory.isEmpty()) {
            return;
        }
        ServerLevel level = player.level();
        Vec3 spot = CorpseVisuals.safeLocation(level, player.position(), settings.get());
        ItemStack[] contents = CorpseLayout.takeFrom(inventory);
        String cause = player.getCombatTracker().getDeathMessage().getString();

        int xp = 0;
        if (settings.get().storeXp) {
            xp = ((LivingEntityAccessor) player).corpes$getBaseExperienceReward(level);
            player.skipDropExperience();
        }

        Corpse corpse = new Corpse(UUID.randomUUID(), player.getUUID(), player.getGameProfile().name(),
                CorpseVisuals.worldId(level), spot.x, spot.y, spot.z, player.getYRot(), System.currentTimeMillis(),
                cause, contents, new ArrayList<>(), xp, false);
        storage.addHistory(player.getUUID(), new HistoryEntry(corpse.createdAt, corpse.world, corpse.x, corpse.y,
                corpse.z, cause, CorpseLayout.copy(contents)), settings.get().historySize);
        // Registered before spawning, or entityLoaded would see the new body as left over and remove it.
        corpses.put(corpse.id, corpse);
        visuals.spawnBody(corpse, player.getGameProfile(), settings.get());
        storage.saveCorpse(corpse);
    }

    void open(ServerPlayer viewer, Corpse corpse) {
        if (corpse.xp > 0) {
            viewer.giveExperiencePoints(corpse.xp);
            corpse.xp = 0;
            storage.saveCorpse(corpse);
        }
        // An open container is already up to date (and may hold changes not synced yet), so only new ones render.
        CorpseContainer container = containers.computeIfAbsent(corpse.id, id -> {
            CorpseContainer created = new CorpseContainer(corpse);
            created.render();
            return created;
        });
        viewer.openMenu(new SimpleMenuProvider((id, inventory, player) -> new LootMenu(id, inventory, container, container),
                settings.get().message("menu-title", Map.of("player", corpse.ownerName))));
    }

    /** Runs at the end of every server tick: saves changed corpses and does work that can't happen mid-event. */
    void tick() {
        for (CorpseContainer container : new ArrayList<>(dirty)) {
            changed(container);
        }
        dirty.clear();

        for (Entity entity : pendingRemovals) {
            entity.discard();
        }
        pendingRemovals.clear();

        for (UUID id : new ArrayList<>(pendingSkeletons)) {
            Corpse corpse = corpses.get(id);
            if (corpse != null && isSkeletonDue(corpse) && visuals.skeletonize(corpse, settings.get())) {
                storage.saveCorpse(corpse);
            }
        }
        pendingSkeletons.clear();

        // Closes menus nobody is looking at any more, so a reopened corpse starts from fresh items.
        containers.values().removeIf(container -> !container.hasViewers() && !dirty.contains(container));

        if (server.getTickCount() % 600 == 0) {
            tickSkeletons();
        }
    }

    /** Turns corpses into skeletons once they're old enough. Runs every 30 seconds. */
    private void tickSkeletons() {
        for (Corpse corpse : corpses.values()) {
            if (isSkeletonDue(corpse) && visuals.skeletonize(corpse, settings.get())) {
                storage.saveCorpse(corpse);
            }
        }
    }

    /**
     * Checks a corpse entity that just loaded with its chunk. Entities of corpses that were emptied while
     * unloaded are removed, and overdue bodies become skeletons.
     */
    void entityLoaded(Entity entity) {
        UUID id = CorpseVisuals.corpseId(entity);
        if (id == null) {
            return;
        }
        Corpse corpse = corpses.get(id);
        if (corpse == null) {
            pendingRemovals.add(entity);
        } else if (entity instanceof Mannequin && isSkeletonDue(corpse)) {
            pendingSkeletons.add(id);
        }
    }

    void shutdown() {
        for (CorpseContainer container : new ArrayList<>(containers.values())) {
            changed(container);
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.containerMenu instanceof LootMenu) {
                player.closeContainer();
            }
        }
        containers.clear();
    }

    private void changed(CorpseContainer container) {
        Corpse corpse = container.corpse;
        if (!corpses.containsKey(corpse.id)) {
            return;
        }
        container.sync();
        if (corpse.isEmpty()) {
            remove(corpse);
        } else {
            storage.saveCorpse(corpse);
            container.render();
        }
    }

    private void remove(Corpse corpse) {
        corpses.remove(corpse.id);
        CorpseContainer container = containers.remove(corpse.id);
        if (container != null) {
            container.closeViewers();
        }
        visuals.removeAll(corpse);
        storage.deleteCorpse(corpse.id);
    }

    private boolean isSkeletonDue(Corpse corpse) {
        int minutes = settings.get().skeletonAfterMinutes;
        return minutes > 0 && !corpse.skeleton && System.currentTimeMillis() - corpse.createdAt >= minutes * 60_000L;
    }

    /**
     * The shared inventory of one corpse. Everyone looking at the same corpse shares it, so two players can't take
     * the same item. Changes are picked up at the end of the tick.
     */
    private final class CorpseContainer extends SimpleContainer implements LootMenu.Buttons {

        private final Corpse corpse;
        private boolean rendering;

        CorpseContainer(Corpse corpse) {
            super(CorpseLayout.SIZE);
            this.corpse = corpse;
        }

        /** Redraws the menu from the corpse: items, labels for empty slots, the info head and the buttons. */
        void render() {
            Settings s = settings.get();
            rendering = true;
            for (int slot = 0; slot < CorpseLayout.SIZE; slot++) {
                ItemStack item = corpse.contents[slot];
                ItemStack shown = item.isEmpty() ? MenuItems.background(s, slot) : item;
                if (!ItemStack.matches(getItem(slot), shown)) {
                    setItem(slot, shown.copy());
                }
            }
            String nameKey = corpse.skeleton ? "info-name-remains" : "info-name";
            setItem(CorpseLayout.INFO_SLOT, MenuItems.info(s, corpse.ownerId, corpse.ownerName, nameKey, "info-lore",
                    corpse.death(), corpse.stacksLeft(), Map.of()));
            setItem(CorpseLayout.EQUIP_ALL_SLOT, MenuItems.equipAllButton(s));
            setItem(CorpseLayout.TAKE_ALL_SLOT, MenuItems.takeAllButton(s));
            rendering = false;
        }

        /** Copies the menu back into the corpse. */
        void sync() {
            for (int slot = 0; slot < CorpseLayout.SIZE; slot++) {
                ItemStack item = getItem(slot);
                corpse.contents[slot] = item.isEmpty() || MenuItems.isUi(item) ? ItemStack.EMPTY : item.copy();
            }
        }

        boolean hasViewers() {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.containerMenu instanceof LootMenu menu && menu.getContainer() == this) {
                    return true;
                }
            }
            return false;
        }

        void closeViewers() {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.containerMenu instanceof LootMenu menu && menu.getContainer() == this) {
                    player.closeContainer();
                }
            }
        }

        @Override
        public void setChanged() {
            super.setChanged();
            if (!rendering) {
                dirty.add(this);
            }
        }

        /** Puts every item back in the slot it came from, or anywhere free. Items that don't fit stay in the corpse. */
        @Override
        public void equipAll(ServerPlayer player) {
            Inventory target = player.getInventory();
            for (int slot = 0; slot < CorpseLayout.SIZE; slot++) {
                ItemStack item = getItem(slot);
                if (item.isEmpty() || MenuItems.isUi(item)) {
                    continue;
                }
                int playerSlot = CorpseLayout.playerSlotFor(slot);
                if (playerSlot >= 0 && target.getItem(playerSlot).isEmpty()) {
                    target.setItem(playerSlot, item.copy());
                    setItem(slot, ItemStack.EMPTY);
                } else {
                    moveToInventory(target, slot);
                }
            }
        }

        @Override
        public void takeAll(ServerPlayer player) {
            Inventory target = player.getInventory();
            for (int slot = 0; slot < CorpseLayout.SIZE; slot++) {
                ItemStack item = getItem(slot);
                if (!item.isEmpty() && !MenuItems.isUi(item)) {
                    moveToInventory(target, slot);
                }
            }
        }

        @Override
        public boolean stillValid() {
            return corpses.containsKey(corpse.id);
        }

        private void moveToInventory(Inventory target, int slot) {
            ItemStack stack = getItem(slot).copy();
            target.add(stack);
            setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
        }
    }
}
