package com.jvfault.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal JSON writer for the exception module (kept dependency-free).
 *
 * @since v0.2.0 (2016)
 * @author jvfault team
 */
final class JsonWriter {

    private JsonWriter() {
    }

    static String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        write(sb, value);
        return sb.toString();
    }

    static void write(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            writeString(sb, (String) value);
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Map) {
            writeMap(sb, (Map<?, ?>) value);
        } else if (value instanceof Iterable) {
            writeIterable(sb, (Iterable<?>) value);
        } else {
            writeString(sb, String.valueOf(value));
        }
    }

    private static void writeString(StringBuilder sb, String value) {
        sb.append('"');
        escapeAndAppend(sb, value);
        sb.append('"');
    }

    static void escapeAndAppend(StringBuilder sb, String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
    }

    private static void writeMap(StringBuilder sb, Map<?, ?> map) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!first) sb.append(',');
            writeString(sb, String.valueOf(e.getKey()));
            sb.append(':');
            write(sb, e.getValue());
            first = false;
        }
        sb.append('}');
    }

    private static void writeIterable(StringBuilder sb, Iterable<?> iterable) {
        sb.append('[');
        boolean first = true;
        for (Object item : iterable) {
            if (!first) sb.append(',');
            write(sb, item);
            first = false;
        }
        sb.append(']');
    }

    static Map<String, String> singleMap(String key, String value) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(key, value);
        return Collections.unmodifiableMap(map);
    }
}
