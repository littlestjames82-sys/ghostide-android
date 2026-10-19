package com.ghostide.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tiny JSON parser/escaper - just enough for the IDE API. */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    public static Object parse(String text) {
        Json j = new Json(text);
        j.ws();
        Object v = j.value();
        return v;
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private Object value() {
        if (i >= s.length()) return null;
        char c = s.charAt(i);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (c == 't' || c == 'f') {
            boolean b = s.startsWith("true", i);
            i += b ? 4 : 5;
            return b;
        }
        if (c == 'n') { i += 4; return null; }
        int start = i;
        while (i < s.length() && "-+0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        String num = s.substring(start, i);
        try { return Double.parseDouble(num); }
        catch (Exception e) { return 0d; }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; ws();
        if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
        while (i < s.length()) {
            ws();
            String k = string();
            ws();
            if (i < s.length() && s.charAt(i) == ':') i++;
            ws();
            m.put(k, value());
            ws();
            if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
            if (i < s.length() && s.charAt(i) == '}') i++;
            break;
        }
        return m;
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        i++; ws();
        if (i < s.length() && s.charAt(i) == ']') { i++; return list; }
        while (i < s.length()) {
            ws();
            list.add(value());
            ws();
            if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
            if (i < s.length() && s.charAt(i) == ']') i++;
            break;
        }
        return list;
    }

    private String string() {
        StringBuilder b = new StringBuilder();
        i++; // opening quote
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') break;
            if (c == '\\' && i < s.length()) {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case '/': b.append('/'); break;
                    case '\\': b.append('\\'); break;
                    case '"': b.append('"'); break;
                    case 'u':
                        if (i + 4 <= s.length()) {
                            b.append((char) Integer.parseInt(
                                s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        break;
                    default: b.append(e);
                }
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) {
        return o instanceof List ? (List<Object>) o : new ArrayList<>();
    }

    public static String str(Map<String, Object> m, String key, String dflt) {
        Object v = m.get(key);
        return v instanceof String ? (String) v : dflt;
    }

    public static int num(Map<String, Object> m, String key, int dflt) {
        Object v = m.get(key);
        return v instanceof Number ? ((Number) v).intValue() : dflt;
    }

    public static double numd(Map<String, Object> m, String key, double dflt) {
        Object v = m.get(key);
        return v instanceof Number ? ((Number) v).doubleValue() : dflt;
    }

    public static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.toString();
    }

    public static String quote(String s) { return "\"" + esc(s) + "\""; }
}
