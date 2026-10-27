package dev.cornybirdy.papercorpes;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The death browser behind {@code /corpse history}. Everyone gets their own deaths, newest in the top left corner;
 * click one to see its inventory or right-click to highlight the body. Admins can also browse every player's deaths
 * ({@code admin-see-all-deaths}) and, if {@code admin-restore} is on, take copies of items out of a death.
 */
final class HistoryGui {

    static final String ADMIN = "papercorpes.admin";

    private static final int PAGE_SIZE = 45;
    private static final int BACK_SLOT = 45;
    private static final int OWN_SLOT = 49;
    private static final int PLAYERS_SLOT = 53;
    private static final int VIEW_BACK_SLOT = 51;
    private static final int HIGHLIGHT_SLOT = 52;

    private final PaperCorpes plugin;
    private final CorpseStorage storage;
    private final CorpseManager manager;

    HistoryGui(PaperCorpes plugin, CorpseStorage storage, CorpseManager manager) {
        this.plugin = plugin;
        this.storage = storage;
        this.manager = manager;
    }

    /** Whether this player may browse everyone's deaths. */
    boolean canBrowseAll(Player player) {
        return player.hasPermission(ADMIN) && plugin.settings().adminSeeAll();
    }

    boolean canOpen(Player viewer, UUID owner) {
        return viewer.getUniqueId().equals(owner) || canBrowseAll(viewer);
    }

    private boolean canRestore(Player player) {
        return player.hasPermission(ADMIN) && plugin.settings().adminRestore();
    }

    void openDeaths(Player viewer, UUID ownerId, String ownerName) {
        if (!canOpen(viewer, ownerId)) {
            viewer.sendMessage(plugin.message("no-permission"));
            return;
        }
        MenuItems items = plugin.menuItems();
        HistoryMenu menu = new HistoryMenu(plugin.message("history-title", Placeholder.unparsed("player", ownerName)), false);
        for (int slot = 45; slot < CorpseLayout.SIZE; slot++) {
            menu.decoration(slot, items.divider());
        }

        List<HistoryEntry> entries = storage.history(ownerId);
        for (int i = 0; i < entries.size() && i < PAGE_SIZE; i++) {
            HistoryEntry entry = entries.get(i);
            int index = i + 1;
            Corpse corpse = corpseOf(entry);
            String status = corpse == null ? "death-status-gone"
                    : corpse.skeleton() ? "death-status-remains" : "death-status-body";
            String hint = canHighlight(corpse) ? "death-hint-highlight" : "death-hint";
            menu.button(i, items.deathIcon(ownerId, ownerName, index, entry, status, hint), (player, right) -> {
                if (right && plugin.settings().highlightSeconds() > 0) {
                    highlight(player, entry);
                } else {
                    openView(player, ownerId, ownerName, index, entry);
                }
            });
        }

        boolean own = viewer.getUniqueId().equals(ownerId);
        if (canBrowseAll(viewer)) {
            if (own) {
                menu.button(PLAYERS_SLOT, items.button(Material.PLAYER_HEAD, "players-button", "players-button-lore", true),
                        (player, right) -> openPlayers(player, 0));
            } else {
                menu.button(BACK_SLOT, items.button(Material.ARROW, "back-players", "back-players-lore", false),
                        (player, right) -> openPlayers(player, 0));
            }
        }
        viewer.openInventory(menu.getInventory());
    }

    void openPlayers(Player viewer, int page) {
        if (!canBrowseAll(viewer)) {
            viewer.sendMessage(plugin.message("no-permission"));
            return;
        }
        MenuItems items = plugin.menuItems();
        List<UUID> owners = new ArrayList<>();
        for (UUID owner : storage.historyOwners()) {
            if (!storage.history(owner).isEmpty()) {
                owners.add(owner);
            }
        }
        owners.sort(Comparator.comparingLong((UUID owner) -> storage.history(owner).getFirst().time()).reversed());

        int pages = Math.max(1, (owners.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.max(0, Math.min(page, pages - 1));
        HistoryMenu menu = new HistoryMenu(plugin.message("players-title",
                Placeholder.unparsed("page", String.valueOf(current + 1)),
                Placeholder.unparsed("pages", String.valueOf(pages))), false);
        for (int slot = 45; slot < CorpseLayout.SIZE; slot++) {
            menu.decoration(slot, items.divider());
        }

        for (int i = 0; i < PAGE_SIZE && current * PAGE_SIZE + i < owners.size(); i++) {
            UUID owner = owners.get(current * PAGE_SIZE + i);
            List<HistoryEntry> entries = storage.history(owner);
            OfflinePlayer offline = Bukkit.getOfflinePlayer(owner);
            String name = offline.getName() != null ? offline.getName() : owner.toString().substring(0, 8);
            menu.button(i, items.playerIcon(owner, name, entries.size(), entries.getFirst().time()),
                    (player, right) -> openDeaths(player, owner, name));
        }

        if (current > 0) {
            menu.button(BACK_SLOT, items.button(Material.ARROW, "page-previous", "page-lore", false),
                    (player, right) -> openPlayers(player, current - 1));
        }
        menu.button(OWN_SLOT, items.button(Material.ARMOR_STAND, "own-deaths", "own-deaths-lore", false),
                (player, right) -> openDeaths(player, player.getUniqueId(), player.getName()));
        if (current < pages - 1) {
            menu.button(PLAYERS_SLOT, items.button(Material.ARROW, "page-next", "page-lore", false),
                    (player, right) -> openPlayers(player, current + 1));
        }
        viewer.openInventory(menu.getInventory());
    }

    void openView(Player viewer, UUID ownerId, String ownerName, int index, HistoryEntry entry) {
        if (!canOpen(viewer, ownerId)) {
            viewer.sendMessage(plugin.message("no-permission"));
            return;
        }
        MenuItems items = plugin.menuItems();
        boolean restore = canRestore(viewer);
        HistoryMenu menu = new HistoryMenu(plugin.message("history-menu-title",
                Placeholder.unparsed("player", ownerName), Placeholder.unparsed("index", String.valueOf(index))), restore);
        items.fill(menu.getInventory(), entry.items());
        menu.decoration(CorpseLayout.INFO_SLOT, items.info(ownerId, ownerName, "history-info-name",
                restore ? "history-info-lore-restore" : "history-info-lore", entry, ItemCodec.countStacks(entry.items()),
                Placeholder.unparsed("index", String.valueOf(index))));

        menu.button(VIEW_BACK_SLOT, items.button(Material.ARROW, "back", "back-lore", false),
                (player, right) -> openDeaths(player, ownerId, ownerName));
        if (plugin.settings().highlightSeconds() > 0) {
            Corpse corpse = corpseOf(entry);
            if (corpse == null) {
                menu.button(HIGHLIGHT_SLOT, items.button(Material.BARRIER, "highlight-gone", "highlight-gone-lore", false),
                        (player, right) -> { });
            } else {
                menu.button(HIGHLIGHT_SLOT, items.button(Material.SPYGLASS, "highlight", "highlight-lore", true,
                                Placeholder.unparsed("seconds", String.valueOf(plugin.settings().highlightSeconds()))),
                        (player, right) -> highlight(player, entry));
            }
        }
        viewer.openInventory(menu.getInventory());
    }

    /** The corpse a death left behind, if it hasn't been emptied yet. */
    private Corpse corpseOf(HistoryEntry entry) {
        return entry.corpse() == null ? null : manager.get(entry.corpse());
    }

    private boolean canHighlight(Corpse corpse) {
        return corpse != null && plugin.settings().highlightSeconds() > 0;
    }

    private void highlight(Player player, HistoryEntry entry) {
        int seconds = plugin.settings().highlightSeconds();
        if (seconds <= 0) {
            return;
        }
        Corpse corpse = corpseOf(entry);
        player.closeInventory();
        if (corpse == null) {
            player.sendMessage(plugin.message("highlight-gone"));
            return;
        }
        TagResolver[] where = {
                Placeholder.unparsed("player", corpse.ownerName()),
                Placeholder.unparsed("world", entry.world()),
                Placeholder.unparsed("x", String.valueOf((int) Math.floor(entry.x()))),
                Placeholder.unparsed("y", String.valueOf((int) Math.floor(entry.y()))),
                Placeholder.unparsed("z", String.valueOf((int) Math.floor(entry.z()))),
                Placeholder.unparsed("seconds", String.valueOf(seconds))};
        player.sendMessage(plugin.message(manager.highlight(corpse, seconds) ? "highlighted" : "highlight-unloaded", where));
    }
}
