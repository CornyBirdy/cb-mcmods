package dev.cornybirdy.corpes;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The death browser behind {@code /corpse history}. Everyone gets their own deaths, newest in the top left corner;
 * click one to see its inventory or right-click to highlight the body. Admins can also browse every player's deaths
 * ({@code admin-see-all-deaths}) and, if {@code admin-restore} is on, take copies of items out of a death.
 */
final class HistoryGui {

    private static final int PAGE_SIZE = 45;
    private static final int BACK_SLOT = 45;
    private static final int OWN_SLOT = 49;
    private static final int PLAYERS_SLOT = 53;
    private static final int VIEW_BACK_SLOT = 51;
    private static final int HIGHLIGHT_SLOT = 52;

    private final Supplier<Settings> settings;
    private final Supplier<CorpseManager> manager;

    HistoryGui(Supplier<Settings> settings, Supplier<CorpseManager> manager) {
        this.settings = settings;
        this.manager = manager;
    }

    /** Whether this player may browse everyone's deaths. */
    boolean canBrowseAll(ServerPlayer player) {
        return Corpes.isAdmin(player) && settings.get().adminSeeAll;
    }

    boolean canOpen(ServerPlayer viewer, UUID owner) {
        return viewer.getUUID().equals(owner) || canBrowseAll(viewer);
    }

    private boolean canRestore(ServerPlayer player) {
        return Corpes.isAdmin(player) && settings.get().adminRestore;
    }

    void openDeaths(ServerPlayer viewer, UUID ownerId, String ownerName) {
        Settings s = settings.get();
        CorpseManager m = manager.get();
        if (m == null) {
            return;
        }
        if (!canOpen(viewer, ownerId)) {
            viewer.sendSystemMessage(s.message("no-permission"));
            return;
        }
        SimpleContainer container = blank();
        Map<Integer, HistoryMenu.Click> buttons = new HashMap<>();

        List<HistoryEntry> entries = m.storage().history(ownerId);
        for (int i = 0; i < entries.size() && i < PAGE_SIZE; i++) {
            HistoryEntry entry = entries.get(i);
            int index = i + 1;
            Corpse corpse = corpseOf(m, entry);
            String status = corpse == null ? "death-status-gone"
                    : corpse.skeleton ? "death-status-remains" : "death-status-body";
            String hint = canHighlight(corpse) ? "death-hint-highlight" : "death-hint";
            container.setItem(i, MenuItems.deathIcon(s, ownerId, ownerName, index, entry, status, hint));
            buttons.put(i, (player, right) -> {
                if (right && settings.get().highlightSeconds > 0) {
                    highlight(player, entry);
                } else {
                    openView(player, ownerId, ownerName, index, entry);
                }
            });
        }

        if (canBrowseAll(viewer)) {
            if (viewer.getUUID().equals(ownerId)) {
                container.setItem(PLAYERS_SLOT, MenuItems.button(s, Items.PLAYER_HEAD, "players-button",
                        "players-button-lore", true, Map.of()));
                buttons.put(PLAYERS_SLOT, (player, right) -> openPlayers(player, 0));
            } else {
                container.setItem(BACK_SLOT, MenuItems.button(s, Items.ARROW, "back-players", "back-players-lore",
                        false, Map.of()));
                buttons.put(BACK_SLOT, (player, right) -> openPlayers(player, 0));
            }
        }
        open(viewer, container, false, buttons, s.message("history-title", Map.of("player", ownerName)));
    }

    void openPlayers(ServerPlayer viewer, int page) {
        Settings s = settings.get();
        CorpseManager m = manager.get();
        if (m == null) {
            return;
        }
        if (!canBrowseAll(viewer)) {
            viewer.sendSystemMessage(s.message("no-permission"));
            return;
        }
        CorpseStorage storage = m.storage();
        List<UUID> owners = new ArrayList<>();
        for (UUID owner : storage.historyOwners()) {
            if (!storage.history(owner).isEmpty()) {
                owners.add(owner);
            }
        }
        owners.sort(Comparator.comparingLong((UUID owner) -> storage.history(owner).getFirst().time()).reversed());

        int pages = Math.max(1, (owners.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.max(0, Math.min(page, pages - 1));
        SimpleContainer container = blank();
        Map<Integer, HistoryMenu.Click> buttons = new HashMap<>();

        MinecraftServer server = viewer.level().getServer();
        for (int i = 0; i < PAGE_SIZE && current * PAGE_SIZE + i < owners.size(); i++) {
            UUID owner = owners.get(current * PAGE_SIZE + i);
            List<HistoryEntry> entries = storage.history(owner);
            String name = nameOf(server, owner);
            container.setItem(i, MenuItems.playerIcon(s, owner, name, entries.size(), entries.getFirst().time()));
            buttons.put(i, (player, right) -> openDeaths(player, owner, name));
        }

        if (current > 0) {
            container.setItem(BACK_SLOT, MenuItems.button(s, Items.ARROW, "page-previous", "page-lore", false, Map.of()));
            buttons.put(BACK_SLOT, (player, right) -> openPlayers(player, current - 1));
        }
        container.setItem(OWN_SLOT, MenuItems.button(s, Items.ARMOR_STAND, "own-deaths", "own-deaths-lore", false, Map.of()));
        buttons.put(OWN_SLOT, (player, right) -> openDeaths(player, player.getUUID(), player.getGameProfile().name()));
        if (current < pages - 1) {
            container.setItem(PLAYERS_SLOT, MenuItems.button(s, Items.ARROW, "page-next", "page-lore", false, Map.of()));
            buttons.put(PLAYERS_SLOT, (player, right) -> openPlayers(player, current + 1));
        }
        open(viewer, container, false, buttons, s.message("players-title",
                Map.of("page", String.valueOf(current + 1), "pages", String.valueOf(pages))));
    }

    void openView(ServerPlayer viewer, UUID ownerId, String ownerName, int index, HistoryEntry entry) {
        Settings s = settings.get();
        CorpseManager m = manager.get();
        if (m == null) {
            return;
        }
        if (!canOpen(viewer, ownerId)) {
            viewer.sendSystemMessage(s.message("no-permission"));
            return;
        }
        boolean restore = canRestore(viewer);
        // A copy: whatever is taken out of it is created, and the history isn't changed.
        SimpleContainer container = new SimpleContainer(CorpseLayout.SIZE);
        ItemStack[] items = entry.items();
        for (int slot = 0; slot < CorpseLayout.SIZE; slot++) {
            container.setItem(slot, items[slot].isEmpty() ? MenuItems.background(s, slot) : items[slot].copy());
        }
        container.setItem(CorpseLayout.INFO_SLOT, MenuItems.info(s, ownerId, ownerName, "history-info-name",
                restore ? "history-info-lore-restore" : "history-info-lore", entry, CorpseLayout.countStacks(items),
                Map.of("index", String.valueOf(index))));

        Map<Integer, HistoryMenu.Click> buttons = new HashMap<>();
        container.setItem(VIEW_BACK_SLOT, MenuItems.button(s, Items.ARROW, "back", "back-lore", false, Map.of()));
        buttons.put(VIEW_BACK_SLOT, (player, right) -> openDeaths(player, ownerId, ownerName));
        if (s.highlightSeconds > 0) {
            if (corpseOf(m, entry) == null) {
                container.setItem(HIGHLIGHT_SLOT, MenuItems.button(s, Items.BARRIER, "highlight-gone",
                        "highlight-gone-lore", false, Map.of()));
                buttons.put(HIGHLIGHT_SLOT, (player, right) -> { });
            } else {
                container.setItem(HIGHLIGHT_SLOT, MenuItems.button(s, Items.SPYGLASS, "highlight", "highlight-lore",
                        true, Map.of("seconds", String.valueOf(s.highlightSeconds))));
                buttons.put(HIGHLIGHT_SLOT, (player, right) -> highlight(player, entry));
            }
        }
        open(viewer, container, restore, buttons, s.message("history-menu-title",
                Map.of("player", ownerName, "index", String.valueOf(index))));
    }

    private void open(ServerPlayer viewer, SimpleContainer container, boolean takeable,
                      Map<Integer, HistoryMenu.Click> buttons, Component title) {
        viewer.openMenu(new SimpleMenuProvider((id, inventory, player) ->
                new HistoryMenu(id, inventory, container, takeable, buttons), title));
    }

    /** A container with dividers along the bottom row. */
    private static SimpleContainer blank() {
        SimpleContainer container = new SimpleContainer(CorpseLayout.SIZE);
        for (int slot = 45; slot < CorpseLayout.SIZE; slot++) {
            container.setItem(slot, MenuItems.divider());
        }
        return container;
    }

    /** The corpse a death left behind, if it hasn't been emptied yet. */
    private static Corpse corpseOf(CorpseManager manager, HistoryEntry entry) {
        return entry.corpse() == null ? null : manager.get(entry.corpse());
    }

    private boolean canHighlight(Corpse corpse) {
        return corpse != null && settings.get().highlightSeconds > 0;
    }

    private void highlight(ServerPlayer player, HistoryEntry entry) {
        Settings s = settings.get();
        CorpseManager m = manager.get();
        if (m == null || s.highlightSeconds <= 0) {
            return;
        }
        Corpse corpse = corpseOf(m, entry);
        player.closeContainer();
        if (corpse == null) {
            player.sendSystemMessage(s.message("highlight-gone"));
            return;
        }
        Map<String, String> where = Map.of(
                "player", corpse.ownerName,
                "world", MenuItems.worldName(entry.world()),
                "x", String.valueOf((int) Math.floor(entry.x())),
                "y", String.valueOf((int) Math.floor(entry.y())),
                "z", String.valueOf((int) Math.floor(entry.z())),
                "seconds", String.valueOf(s.highlightSeconds));
        player.sendSystemMessage(s.message(m.highlight(corpse, s.highlightSeconds) ? "highlighted" : "highlight-unloaded", where));
    }

    private static String nameOf(MinecraftServer server, UUID id) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) {
            return online.getGameProfile().name();
        }
        return server.services().nameToIdCache().get(id).map(NameAndId::name).orElse(id.toString().substring(0, 8));
    }
}
