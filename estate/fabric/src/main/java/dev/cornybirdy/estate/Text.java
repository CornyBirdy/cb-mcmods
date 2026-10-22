package dev.cornybirdy.estate;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;

/**
 * A small subset of MiniMessage, so the Fabric config reads like the Paper one. Supports colour names
 * ({@code <gray>}), hex colours ({@code <#ff8800>}), {@code <bold>}, {@code <italic>}, {@code <underlined>},
 * {@code <strikethrough>}, {@code <obfuscated>}, {@code <reset>}, closing tags ({@code </gray>}) and placeholders
 * ({@code <player>}), which are inserted as plain text.
 */
final class Text {

    private static final Map<String, ChatFormatting> COLOURS = Map.ofEntries(
            Map.entry("black", ChatFormatting.BLACK),
            Map.entry("dark_blue", ChatFormatting.DARK_BLUE),
            Map.entry("dark_green", ChatFormatting.DARK_GREEN),
            Map.entry("dark_aqua", ChatFormatting.DARK_AQUA),
            Map.entry("dark_red", ChatFormatting.DARK_RED),
            Map.entry("dark_purple", ChatFormatting.DARK_PURPLE),
            Map.entry("gold", ChatFormatting.GOLD),
            Map.entry("gray", ChatFormatting.GRAY),
            Map.entry("dark_gray", ChatFormatting.DARK_GRAY),
            Map.entry("blue", ChatFormatting.BLUE),
            Map.entry("green", ChatFormatting.GREEN),
            Map.entry("aqua", ChatFormatting.AQUA),
            Map.entry("red", ChatFormatting.RED),
            Map.entry("light_purple", ChatFormatting.LIGHT_PURPLE),
            Map.entry("yellow", ChatFormatting.YELLOW),
            Map.entry("white", ChatFormatting.WHITE));

    private Text() {
    }

    static MutableComponent parse(String template, Map<String, String> placeholders) {
        MutableComponent root = Component.empty();
        Deque<Frame> stack = new ArrayDeque<>();
        Style style = Style.EMPTY;
        StringBuilder plain = new StringBuilder();

        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            int close = c == '<' ? template.indexOf('>', i) : -1;
            if (close < 0) {
                plain.append(c);
                i++;
                continue;
            }
            String tag = template.substring(i + 1, close).toLowerCase(Locale.ROOT);
            boolean closing = tag.startsWith("/");
            String name = closing ? tag.substring(1) : tag;

            if (!closing && placeholders.containsKey(name)) {
                plain.append(placeholders.get(name));
                i = close + 1;
                continue;
            }
            Style next = closing ? null : apply(style, name);
            if (!closing && next == null) {
                // Unknown tag: keep it as text, like MiniMessage does.
                plain.append(c);
                i++;
                continue;
            }

            flush(root, plain, style);
            if (closing) {
                style = pop(stack, name, style);
            } else if (name.equals("reset")) {
                stack.clear();
                style = Style.EMPTY;
            } else {
                stack.push(new Frame(name, style));
                style = next;
            }
            i = close + 1;
        }
        flush(root, plain, style);
        return root;
    }

    private static void flush(MutableComponent root, StringBuilder plain, Style style) {
        if (!plain.isEmpty()) {
            root.append(Component.literal(plain.toString()).setStyle(style));
            plain.setLength(0);
        }
    }

    /** Closes the most recent tag with this name and everything opened after it. */
    private static Style pop(Deque<Frame> stack, String name, Style current) {
        for (Frame frame : stack) {
            if (frame.name.equals(name)) {
                while (!stack.isEmpty()) {
                    Frame popped = stack.pop();
                    if (popped == frame) {
                        return popped.before;
                    }
                }
            }
        }
        return current;
    }

    private static Style apply(Style style, String name) {
        if (name.startsWith("#") && name.length() == 7) {
            try {
                return style.withColor(TextColor.fromRgb(Integer.parseInt(name.substring(1), 16)));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return switch (name) {
            case "bold", "b" -> style.withBold(true);
            case "italic", "i", "em" -> style.withItalic(true);
            case "underlined", "u" -> style.withUnderlined(true);
            case "strikethrough", "st" -> style.withStrikethrough(true);
            case "obfuscated", "obf" -> style.withObfuscated(true);
            case "reset" -> Style.EMPTY;
            default -> {
                ChatFormatting colour = COLOURS.get(name.replace("grey", "gray"));
                yield colour != null ? style.withColor(colour) : null;
            }
        };
    }

    private record Frame(String name, Style before) {
    }
}
