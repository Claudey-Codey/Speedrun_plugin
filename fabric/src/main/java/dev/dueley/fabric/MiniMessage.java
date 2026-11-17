package dev.dueley.fabric;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A small MiniMessage reader that turns the plugin's MiniMessage strings into vanilla text components, so every
 * message can be copied 1:1 from the Paper plugin.
 * <p>
 * Supported: named colours and {@code <#rrggbb>}, {@code <color:..>}, {@code <b>}, {@code <i>}, {@code <u>},
 * {@code <st>}, {@code <obf>} (and their long names), {@code <gradient:..>}, {@code <click:action:'value'>},
 * {@code <hover:show_text:'mini message'>}, closing tags and {@code <reset>}. Like MiniMessage, a closing tag closes
 * the last open tag with that name and everything opened after it, unclosed tags last until the end, and anything
 * that is not a known tag (for example {@code <seconds>} in a usage line) stays as literal text.
 */
public final class MiniMessage {
    private MiniMessage() {
    }

    public static MutableComponent parse(String input) {
        Node root = new Node("", Style.EMPTY);
        List<Node> stack = new ArrayList<>();
        stack.add(root);
        StringBuilder text = new StringBuilder();
        int i = 0;
        while (i < input.length()) {
            char c = input.charAt(i);
            if (c == '\\' && i + 1 < input.length() && (input.charAt(i + 1) == '<' || input.charAt(i + 1) == '\\')) {
                text.append(input.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c != '<') {
                text.append(c);
                i++;
                continue;
            }
            int end = findTagEnd(input, i + 1);
            if (end < 0) {
                text.append(c);
                i++;
                continue;
            }
            String content = input.substring(i + 1, end);
            Node top = stack.getLast();
            if (content.startsWith("/")) {
                String name = tagName(content.substring(1));
                int idx = -1;
                for (int s = stack.size() - 1; s > 0; s--) {
                    if (stack.get(s).name.equals(name)) {
                        idx = s;
                        break;
                    }
                }
                if (idx > 0 || isKnown(name)) {
                    flush(text, top);
                    if (idx > 0) {
                        while (stack.size() > idx) {
                            stack.removeLast();
                        }
                    }
                    // an unmatched closing tag of a known kind is dropped, like MiniMessage does
                    i = end + 1;
                    continue;
                }
                text.append(input, i, end + 1);
                i = end + 1;
                continue;
            }
            Node node = open(content);
            if (node == null) {
                if (tagName(content).equals("reset")) {
                    flush(text, top);
                    while (stack.size() > 1) {
                        stack.removeLast();
                    }
                    i = end + 1;
                    continue;
                }
                text.append(input, i, end + 1); // not a tag: keep it as text
                i = end + 1;
                continue;
            }
            flush(text, top);
            top.children.add(node);
            stack.add(node);
            i = end + 1;
        }
        flush(text, stack.getLast());
        return build(root, null);
    }

    // ───────────────────────── parsing ─────────────────────────

    private static final class Node {
        final String name;
        final Style style;
        final List<Object> children = new ArrayList<>();
        int[] gradient; // RGB stops, when this is a <gradient>

        Node(String name, Style style) {
            this.name = name;
            this.style = style;
        }
    }

    private static int findTagEnd(String s, int from) {
        char quote = 0;
        for (int j = from; j < s.length(); j++) {
            char c = s.charAt(j);
            if (quote != 0) {
                if (c == '\\' && j + 1 < s.length()) {
                    j++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '<') {
                return -1; // "a < b" style text, not a tag
            } else if (c == '>') {
                return j;
            }
        }
        return -1;
    }

    private static String tagName(String content) {
        int colon = content.indexOf(':');
        return (colon < 0 ? content : content.substring(0, colon)).toLowerCase(Locale.ROOT);
    }

    private static boolean isKnown(String name) {
        return colorOf(name) != null || decoration(Style.EMPTY, name) != null
                || name.equals("gradient") || name.equals("click") || name.equals("hover")
                || name.equals("color") || name.equals("colour") || name.equals("c");
    }

    /** Split "a:'b:c':d" into [a, b:c, d]. */
    private static List<String> args(String content) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int j = 0; j < content.length(); j++) {
            char c = content.charAt(j);
            if (quote != 0) {
                if (c == '\\' && j + 1 < content.length()) {
                    cur.append(content.charAt(++j));
                } else if (c == quote) {
                    quote = 0;
                } else {
                    cur.append(c);
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == ':') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static Node open(String content) {
        String name = tagName(content);
        TextColor color = colorOf(name);
        if (color != null) {
            return new Node(name, Style.EMPTY.withColor(color));
        }
        Style deco = decoration(Style.EMPTY, name);
        if (deco != null) {
            return new Node(name, deco);
        }
        List<String> a = args(content);
        switch (name) {
            case "color", "colour", "c" -> {
                TextColor c = a.size() > 1 ? colorOf(a.get(1).toLowerCase(Locale.ROOT)) : null;
                return c == null ? null : new Node(name, Style.EMPTY.withColor(c));
            }
            case "gradient" -> {
                List<Integer> stops = new ArrayList<>();
                for (int k = 1; k < a.size(); k++) {
                    TextColor c = colorOf(a.get(k).toLowerCase(Locale.ROOT));
                    if (c != null) {
                        stops.add(c.getValue());
                    }
                }
                if (stops.isEmpty()) {
                    stops.add(0xFFFFFF);
                    stops.add(0x000000);
                } else if (stops.size() == 1) {
                    stops.add(stops.getFirst());
                }
                Node n = new Node(name, Style.EMPTY);
                n.gradient = stops.stream().mapToInt(Integer::intValue).toArray();
                return n;
            }
            case "click" -> {
                if (a.size() < 3) {
                    return null;
                }
                String value = a.get(2);
                ClickEvent ev = switch (a.get(1).toLowerCase(Locale.ROOT)) {
                    case "copy_to_clipboard" -> new ClickEvent.CopyToClipboard(value);
                    case "suggest_command" -> new ClickEvent.SuggestCommand(value);
                    case "run_command" -> new ClickEvent.RunCommand(value);
                    default -> null;
                };
                return ev == null ? null : new Node(name, Style.EMPTY.withClickEvent(ev));
            }
            case "hover" -> {
                if (a.size() < 3 || !a.get(1).equalsIgnoreCase("show_text")) {
                    return null;
                }
                return new Node(name, Style.EMPTY.withHoverEvent(new HoverEvent.ShowText(parse(a.get(2)))));
            }
            default -> {
                return null;
            }
        }
    }

    private static TextColor colorOf(String name) {
        if (name.startsWith("#") && name.length() == 7) {
            try {
                return TextColor.fromRgb(Integer.parseInt(name.substring(1), 16));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        String n = switch (name) {
            case "grey" -> "gray";
            case "dark_grey" -> "dark_gray";
            default -> name;
        };
        if (!n.matches("[a-z_]+")) {
            return null;
        }
        return TextColor.parseColor(n).result().orElse(null); // the 16 named colours

    }

    private static Style decoration(Style s, String name) {
        return switch (name) {
            case "b", "bold" -> s.withBold(true);
            case "i", "em", "italic" -> s.withItalic(true);
            case "u", "underlined" -> s.withUnderlined(true);
            case "st", "strikethrough" -> s.withStrikethrough(true);
            case "obf", "obfuscated" -> s.withObfuscated(true);
            default -> null;
        };
    }

    private static void flush(StringBuilder text, Node into) {
        if (!text.isEmpty()) {
            into.children.add(text.toString());
            text.setLength(0);
        }
    }

    // ───────────────────────── building ─────────────────────────

    /** Colours one character at a time across a gradient (same maths as Adventure's GradientTag). */
    private static final class Gradient {
        final int[] stops;
        final int size;
        int index;

        Gradient(int[] stops, int size) {
            this.stops = stops;
            this.size = size;
        }

        int next() {
            double multiplier = size <= 1 ? 0 : (double) (stops.length - 1) / (size - 1);
            double position = index++ * multiplier;
            int low = (int) Math.floor(position);
            int high = Math.min(stops.length - 1, (int) Math.ceil(position));
            float t = (float) (position - low);
            return lerp(t, stops[Math.min(low, stops.length - 1)], stops[high]);
        }

        static int lerp(float t, int a, int b) {
            int ar = a >> 16 & 0xFF, ag = a >> 8 & 0xFF, ab = a & 0xFF;
            int br = b >> 16 & 0xFF, bg = b >> 8 & 0xFF, bb = b & 0xFF;
            return Math.round(ar + t * (br - ar)) << 16 | Math.round(ag + t * (bg - ag)) << 8 | Math.round(ab + t * (bb - ab));
        }
    }

    private static MutableComponent build(Node node, Gradient gradient) {
        MutableComponent out = Component.empty().withStyle(node.style);
        Gradient g = node.gradient != null ? new Gradient(node.gradient, textLength(node)) : gradient;
        for (Object child : node.children) {
            if (child instanceof String s) {
                if (g == null) {
                    out.append(Component.literal(s));
                } else {
                    s.codePoints().forEach(cp -> out.append(Component.literal(Character.toString(cp)).withColor(g.next())));
                }
            } else {
                Node n = (Node) child;
                if (g != null && n.style.getColor() != null) {
                    // an explicit colour inside a gradient wins, but still uses up its share of the gradient
                    g.index += textLength(n);
                    out.append(build(n, null));
                } else {
                    out.append(build(n, g));
                }
            }
        }
        return out;
    }

    private static int textLength(Node node) {
        int n = 0;
        for (Object child : node.children) {
            n += child instanceof String s ? s.codePointCount(0, s.length()) : textLength((Node) child);
        }
        return n;
    }
}
