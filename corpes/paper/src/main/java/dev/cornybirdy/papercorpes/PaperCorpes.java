package dev.cornybirdy.papercorpes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public final class PaperCorpes extends JavaPlugin {

    private static final long SKELETON_CHECK_TICKS = 20L * 30;

    private NamespacedKey corpseKey;
    private NamespacedKey uiKey;
    private Settings settings;
    private MenuItems menuItems;
    private CorpseStorage storage;
    private CorpseManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        corpseKey = new NamespacedKey(this, "corpse");
        uiKey = new NamespacedKey(this, "ui");
        settings = Settings.load(getConfig());
        menuItems = new MenuItems(this);

        storage = new CorpseStorage(this);
        manager = new CorpseManager(this, storage);
        manager.loadAll();

        getServer().getPluginManager().registerEvents(new CorpseListener(this, manager), this);
        getServer().getPluginManager().registerEvents(new MenuListener(this, manager), this);
        registerCommand("corpse", new CorpseCommand(this, storage, new HistoryGui(this, storage, manager)));

        getServer().getScheduler().runTaskTimer(this, manager::tickSkeletons, SKELETON_CHECK_TICKS, SKELETON_CHECK_TICKS);
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.shutdown();
        }
        if (storage != null) {
            storage.close();
        }
    }

    void reload() {
        reloadConfig();
        settings = Settings.load(getConfig());
    }

    Settings settings() {
        return settings;
    }

    MenuItems menuItems() {
        return menuItems;
    }

    NamespacedKey corpseKey() {
        return corpseKey;
    }

    NamespacedKey uiKey() {
        return uiKey;
    }

    /** Formats a MiniMessage string from the {@code messages} section of the config. */
    Component message(String key, TagResolver... resolvers) {
        String raw = getConfig().getString("messages." + key, key);
        return MiniMessage.miniMessage().deserialize(raw, resolvers);
    }

    /** Same as {@link #message} but without the default italic style item names get. */
    Component itemText(String key, TagResolver... resolvers) {
        return message(key, resolvers).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /** Item lore from the config. The entry can be a list of lines or a single line. */
    List<Component> itemLore(String key, TagResolver... resolvers) {
        String path = "messages." + key;
        List<String> lines = getConfig().isList(path) ? getConfig().getStringList(path) : List.of(getConfig().getString(path, ""));
        List<Component> lore = new ArrayList<>();
        for (String line : lines) {
            lore.add(MiniMessage.miniMessage().deserialize(line, resolvers)
                    .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        }
        return lore;
    }
}
