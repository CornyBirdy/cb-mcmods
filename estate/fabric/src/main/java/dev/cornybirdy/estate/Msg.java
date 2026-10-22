package dev.cornybirdy.estate;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Player-facing text from {@code config/estate-messages.json}, in a MiniMessage-like format. Placeholders are
 * {@code <name>} tags filled from key/value pairs.
 */
final class Msg {

    private final Map<String, String> templates;

    Msg(Map<String, String> templates) {
        this.templates = templates;
    }

    String raw(String key) {
        return templates.getOrDefault(key, key);
    }

    /** @param pairs placeholder names and values, alternating */
    MutableComponent get(String key, Object... pairs) {
        return Text.parse(raw(key), placeholders(pairs));
    }

    /** Message text for an item name or lore line: not italic, unlike vanilla's default for custom names. */
    MutableComponent item(String key, Object... pairs) {
        return get(key, pairs).withStyle(style -> style.withItalic(false));
    }

    /** A multi-line message split on {@code \n}, for item lore. */
    List<Component> lore(String key, Object... pairs) {
        Map<String, String> placeholders = placeholders(pairs);
        return java.util.Arrays.stream(raw(key).split("\n"))
                .map(line -> (Component) Text.parse(line, placeholders).withStyle(style -> style.withItalic(false)))
                .toList();
    }

    static Map<String, String> placeholders(Object... pairs) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]).toLowerCase(java.util.Locale.ROOT), String.valueOf(pairs[i + 1]));
        }
        return map;
    }
}
