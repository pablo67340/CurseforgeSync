package com.curseforgesync.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A dependency-free JSON reader/writer.
 *
 * <p>CurseforgeSync runs before Forge has built a mod classpath, so nothing from the game or from
 * a mod's dependencies is reachable yet. Everything this class needs is in {@code java.base}.
 *
 * <p>Objects decode to {@link LinkedHashMap}, arrays to {@link List}, numbers to {@link Double} or
 * {@link Long}, and {@code null} stays {@code null}. Line and block comments are accepted so the
 * generated config file can document itself.
 */
public final class Json {
    private Json() {
    }

    // ---------------------------------------------------------------- parsing

    public static Object parse(String text) {
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new JsonException("Trailing content at offset " + parser.index);
        }
        return value;
    }

    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new JsonException("Expected a JSON object at the top level");
        }
        return asObject(value);
    }

    public static class JsonException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public JsonException(String message) {
            super(message);
        }
    }

    private static final class Parser {
        private final String src;
        private int index;

        Parser(String src) {
            this.src = src;
        }

        boolean atEnd() {
            return index >= src.length();
        }

        void skipWhitespace() {
            while (index < src.length()) {
                char c = src.charAt(index);
                if (c == '/' && index + 1 < src.length()) {
                    char next = src.charAt(index + 1);
                    if (next == '/') {
                        while (index < src.length() && src.charAt(index) != '\n') {
                            index++;
                        }
                        continue;
                    }
                    if (next == '*') {
                        int end = src.indexOf("*/", index + 2);
                        index = end < 0 ? src.length() : end + 2;
                        continue;
                    }
                }
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r' && c != '\uFEFF') {
                    return;
                }
                index++;
            }
        }

        Object readValue() {
            if (atEnd()) {
                throw new JsonException("Unexpected end of input");
            }
            char c = src.charAt(index);
            switch (c) {
                case '{':
                    return readObject();
                case '[':
                    return readArray();
                case '"':
                    return readString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return readNumber();
            }
        }

        private void expect(String literal) {
            if (!src.startsWith(literal, index)) {
                throw new JsonException("Expected '" + literal + "' at offset " + index);
            }
            index += literal.length();
        }

        private Map<String, Object> readObject() {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            index++; // '{'
            skipWhitespace();
            if (!atEnd() && src.charAt(index) == '}') {
                index++;
                return result;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                if (atEnd() || src.charAt(index) != ':') {
                    throw new JsonException("Expected ':' after key '" + key + "'");
                }
                index++;
                skipWhitespace();
                result.put(key, readValue());
                skipWhitespace();
                if (atEnd()) {
                    throw new JsonException("Unterminated object");
                }
                char c = src.charAt(index++);
                if (c == '}') {
                    return result;
                }
                if (c != ',') {
                    throw new JsonException("Expected ',' or '}' at offset " + (index - 1));
                }
                skipWhitespace();
                // Tolerate a trailing comma before the closing brace.
                if (!atEnd() && src.charAt(index) == '}') {
                    index++;
                    return result;
                }
            }
        }

        private List<Object> readArray() {
            List<Object> result = new ArrayList<Object>();
            index++; // '['
            skipWhitespace();
            if (!atEnd() && src.charAt(index) == ']') {
                index++;
                return result;
            }
            while (true) {
                skipWhitespace();
                result.add(readValue());
                skipWhitespace();
                if (atEnd()) {
                    throw new JsonException("Unterminated array");
                }
                char c = src.charAt(index++);
                if (c == ']') {
                    return result;
                }
                if (c != ',') {
                    throw new JsonException("Expected ',' or ']' at offset " + (index - 1));
                }
                skipWhitespace();
                if (!atEnd() && src.charAt(index) == ']') {
                    index++;
                    return result;
                }
            }
        }

        private String readString() {
            if (atEnd() || src.charAt(index) != '"') {
                throw new JsonException("Expected a string at offset " + index);
            }
            index++;
            StringBuilder out = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new JsonException("Unterminated string");
                }
                char c = src.charAt(index++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw new JsonException("Unterminated escape sequence");
                }
                char esc = src.charAt(index++);
                switch (esc) {
                    case '"':
                        out.append('"');
                        break;
                    case '\\':
                        out.append('\\');
                        break;
                    case '/':
                        out.append('/');
                        break;
                    case 'b':
                        out.append('\b');
                        break;
                    case 'f':
                        out.append('\f');
                        break;
                    case 'n':
                        out.append('\n');
                        break;
                    case 'r':
                        out.append('\r');
                        break;
                    case 't':
                        out.append('\t');
                        break;
                    case 'u':
                        if (index + 4 > src.length()) {
                            throw new JsonException("Truncated unicode escape");
                        }
                        out.append((char) Integer.parseInt(src.substring(index, index + 4), 16));
                        index += 4;
                        break;
                    default:
                        throw new JsonException("Unknown escape '\\" + esc + "'");
                }
            }
        }

        private Object readNumber() {
            int start = index;
            if (!atEnd() && (src.charAt(index) == '-' || src.charAt(index) == '+')) {
                index++;
            }
            boolean floating = false;
            while (index < src.length()) {
                char c = src.charAt(index);
                if (c >= '0' && c <= '9') {
                    index++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    floating = floating || c == '.' || c == 'e' || c == 'E';
                    index++;
                } else {
                    break;
                }
            }
            String literal = src.substring(start, index);
            if (literal.isEmpty()) {
                throw new JsonException("Expected a value at offset " + start);
            }
            try {
                return floating ? (Object) Double.valueOf(literal) : (Object) Long.valueOf(literal);
            } catch (NumberFormatException e) {
                throw new JsonException("Malformed number '" + literal + "'");
            }
        }
    }

    // ------------------------------------------------------------- accessors

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Collections.<String, Object>emptyMap();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object value) {
        return value instanceof List ? (List<Object>) value : Collections.emptyList();
    }

    public static Map<String, Object> obj(Map<String, Object> parent, String key) {
        return asObject(parent.get(key));
    }

    public static List<Object> arr(Map<String, Object> parent, String key) {
        return asArray(parent.get(key));
    }

    public static String str(Map<String, Object> parent, String key, String fallback) {
        Object value = parent.get(key);
        return value instanceof String ? (String) value : fallback;
    }

    public static long num(Map<String, Object> parent, String key, long fallback) {
        Object value = parent.get(key);
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

    public static int intVal(Map<String, Object> parent, String key, int fallback) {
        return (int) num(parent, key, fallback);
    }

    public static boolean bool(Map<String, Object> parent, String key, boolean fallback) {
        Object value = parent.get(key);
        return value instanceof Boolean ? ((Boolean) value).booleanValue() : fallback;
    }

    public static List<String> strings(Map<String, Object> parent, String key) {
        List<String> out = new ArrayList<String>();
        for (Object element : arr(parent, key)) {
            if (element instanceof String) {
                out.add((String) element);
            } else if (element instanceof Number) {
                out.add(String.valueOf(((Number) element).longValue()));
            }
        }
        return out;
    }

    // --------------------------------------------------------------- writing

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(out, value, 0);
        return out.toString();
    }

    private static void writeValue(StringBuilder out, Object value, int depth) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Map) {
            writeObject(out, asObject(value), depth);
        } else if (value instanceof List) {
            writeArray(out, asArray(value), depth);
        } else if (value instanceof String) {
            writeString(out, (String) value);
        } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            out.append(value);
        } else if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d)) {
                out.append((long) d);
            } else {
                out.append(d);
            }
        } else {
            writeString(out, String.valueOf(value));
        }
    }

    private static void writeObject(StringBuilder out, Map<String, Object> map, int depth) {
        if (map.isEmpty()) {
            out.append("{}");
            return;
        }
        out.append("{\n");
        int remaining = map.size();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            indent(out, depth + 1);
            writeString(out, entry.getKey());
            out.append(": ");
            writeValue(out, entry.getValue(), depth + 1);
            if (--remaining > 0) {
                out.append(',');
            }
            out.append('\n');
        }
        indent(out, depth);
        out.append('}');
    }

    private static void writeArray(StringBuilder out, List<Object> list, int depth) {
        if (list.isEmpty()) {
            out.append("[]");
            return;
        }
        boolean scalarsOnly = true;
        for (Object element : list) {
            if (element instanceof Map || element instanceof List) {
                scalarsOnly = false;
                break;
            }
        }
        if (scalarsOnly && list.size() <= 8) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(", ");
                }
                writeValue(out, list.get(i), depth);
            }
            out.append(']');
            return;
        }
        out.append("[\n");
        for (int i = 0; i < list.size(); i++) {
            indent(out, depth + 1);
            writeValue(out, list.get(i), depth + 1);
            if (i < list.size() - 1) {
                out.append(',');
            }
            out.append('\n');
        }
        indent(out, depth);
        out.append(']');
    }

    private static void indent(StringBuilder out, int depth) {
        for (int i = 0; i < depth; i++) {
            out.append("  ");
        }
    }

    private static void writeString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }
}
