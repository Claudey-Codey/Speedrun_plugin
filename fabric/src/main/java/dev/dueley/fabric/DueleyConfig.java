package dev.dueley.fabric;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code config/dueley.yml}: the same file, keys and defaults as the plugin's {@code config.yml}.
 * <p>
 * Minecraft doesn't ship a YAML library, so this reads the small YAML subset the file uses: comments, nested
 * {@code key: value} maps by indentation, scalars, and lists written as {@code [5, 4, 3]} or as {@code - 5} lines.
 * The getters behave like Bukkit's {@code getInt/getDouble/getBoolean/getString/getIntegerList} with an explicit default.
 */
public final class DueleyConfig {
    private final Map<String, Object> values = new LinkedHashMap<>();

    private DueleyConfig() {
    }

    public static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("dueley.yml");
    }

    /** Write the default file if there is none (like saveDefaultConfig). */
    public static void saveDefault() {
        Path file = file();
        if (Files.exists(file)) {
            return;
        }
        try (InputStream in = DueleyConfig.class.getResourceAsStream("/dueley.yml")) {
            if (in == null) {
                return;
            }
            Files.createDirectories(file.getParent());
            Files.copy(in, file);
        } catch (IOException e) {
            DueleyMod.LOGGER.warn("Could not write {}: {}", file, e.getMessage());
        }
    }

    /** Read the file again (like reloadConfig). A missing or broken file gives the defaults. */
    public static DueleyConfig load() {
        DueleyConfig c = new DueleyConfig();
        Path file = file();
        if (!Files.exists(file)) {
            return c;
        }
        try {
            c.parse(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            DueleyMod.LOGGER.warn("Could not read {}: {}", file, e.toString());
        }
        return c;
    }

    // ───────────────────────── getters (Bukkit semantics) ─────────────────────────

    public int getInt(String path, int def) {
        Object v = values.get(path);
        if (v instanceof Number n) {
            return n.intValue();
        }
        return def;
    }

    public double getDouble(String path, double def) {
        Object v = values.get(path);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        return def;
    }

    public boolean getBoolean(String path, boolean def) {
        Object v = values.get(path);
        return v instanceof Boolean b ? b : def;
    }

    public String getString(String path, String def) {
        Object v = values.get(path);
        return v == null || v instanceof List<?> ? def : v.toString();
    }

    public List<Integer> getIntegerList(String path) {
        List<Integer> out = new ArrayList<>();
        if (!(values.get(path) instanceof List<?> list)) {
            return out;
        }
        for (Object o : list) {
            if (o instanceof Number n) {
                out.add(n.intValue());
            } else if (o instanceof String s) {
                try {
                    out.add(Integer.valueOf(s.trim()));
                } catch (NumberFormatException ignored) {
                    // skipped, like Bukkit
                }
            }
        }
        return out;
    }

    // ───────────────────────── YAML subset ─────────────────────────

    private void parse(List<String> lines) {
        List<String> pathStack = new ArrayList<>(); // key per indentation level
        List<Integer> indentStack = new ArrayList<>();
        String listKey = null;
        int listIndent = -1;
        for (String raw : lines) {
            String line = stripComment(raw);
            if (line.isBlank()) {
                continue;
            }
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') {
                indent++;
            }
            String body = line.trim();
            if (body.startsWith("- ") || body.equals("-")) {
                if (listKey != null && indent >= listIndent) {
                    @SuppressWarnings("unchecked")
                    List<Object> list = (List<Object>) values.computeIfAbsent(listKey, k -> new ArrayList<>());
                    list.add(scalar(body.substring(1).trim()));
                }
                continue;
            }
            int colon = body.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            while (!indentStack.isEmpty() && indentStack.getLast() >= indent) {
                indentStack.removeLast();
                pathStack.removeLast();
            }
            String key = unquote(body.substring(0, colon).trim());
            String value = body.substring(colon + 1).trim();
            String full = pathStack.isEmpty() ? key : String.join(".", pathStack) + "." + key;
            listKey = null;
            if (value.isEmpty()) {
                // a section, or a block list on the following "- " lines
                indentStack.add(indent);
                pathStack.add(key);
                listKey = full;
                listIndent = indent;
            } else if (value.startsWith("[") && value.endsWith("]")) {
                List<Object> list = new ArrayList<>();
                String inner = value.substring(1, value.length() - 1).trim();
                if (!inner.isEmpty()) {
                    for (String part : inner.split(",")) {
                        list.add(scalar(part.trim()));
                    }
                }
                values.put(full, list);
            } else {
                values.put(full, scalar(value));
            }
        }
    }

    private static String stripComment(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && (s.startsWith("'") && s.endsWith("'") || s.startsWith("\"") && s.endsWith("\""))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static Object scalar(String s) {
        if (s.length() >= 2 && (s.startsWith("'") && s.endsWith("'") || s.startsWith("\"") && s.endsWith("\""))) {
            return s.substring(1, s.length() - 1);
        }
        switch (s.toLowerCase()) {
            case "true", "yes", "on" -> {
                return Boolean.TRUE;
            }
            case "false", "no", "off" -> {
                return Boolean.FALSE;
            }
            default -> {
            }
        }
        try {
            if (s.matches("[-+]?\\d+")) {
                long l = Long.parseLong(s);
                return l == (int) l ? (Object) (int) l : (Object) l;
            }
            if (s.matches("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?")) {
                return Double.parseDouble(s);
            }
        } catch (NumberFormatException ignored) {
            // fall through to a string
        }
        return s;
    }
}
