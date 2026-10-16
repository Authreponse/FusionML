package com.fusionml.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自研 TOML 解析器（覆盖模组元数据所需的全部子集）。
 *
 * <p>支持：标准表 {@code [a.b]}、表数组 {@code [[a.b]]}、点分键 {@code a.b.c = 1}、
 * 带引号的键、基本字符串/字面字符串/多行字符串、整数（含下划线、0x/0o/0b）、浮点、
 * 布尔、跨行数组与内联表、注释。
 *
 * <p>实现方式：单遍字符扫描，不用正则、不回溯。遇到跨行结构（多行字符串、跨行数组）
 * 天然支持，因为解析器不知道"行"这个概念 —— 这比按行切分再拼接的写法既快又不容易出错。
 */
public final class Toml {

    private final String s;
    private int p;
    private final int n;

    private Toml(String text) {
        this.s = text;
        this.n = text.length();
    }

    public static Map<String, Object> parse(String text) {
        Toml t = new Toml(text);
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> current = root;
        while (true) {
            t.skipBlank();
            if (t.p >= t.n) {
                break;
            }
            char c = t.s.charAt(t.p);
            if (c == '[') {
                current = t.tableHeader(root);
                t.expectLineEnd();
            } else {
                t.keyValue(current);
            }
        }
        return root;
    }

    private void skipBlank() {
        while (p < n) {
            char c = s.charAt(p);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                p++;
            } else if (c == '#') {
                while (p < n && s.charAt(p) != '\n') {
                    p++;
                }
            } else {
                return;
            }
        }
    }

    private void skipInline() {
        while (p < n && (s.charAt(p) == ' ' || s.charAt(p) == '\t')) {
            p++;
        }
    }

    private void skipInlineAndComments() {
        while (p < n) {
            char c = s.charAt(p);
            if (c == ' ' || c == '\t' || c == '\r') {
                p++;
            } else if (c == '#') {
                while (p < n && s.charAt(p) != '\n') {
                    p++;
                }
            } else {
                return;
            }
        }
    }

    private void expectLineEnd() {
        skipInlineAndComments();
        if (p < n && s.charAt(p) == '\n') {
            p++;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> tableHeader(Map<String, Object> root) {
        p++;                                        
        boolean isArray = p < n && s.charAt(p) == '[';
        if (isArray) {
            p++;
        }
        List<String> path = parseKeyPath(']');
        skipInline();
        if (p < n && s.charAt(p) == ']') {
            p++;
        }
        if (isArray && p < n && s.charAt(p) == ']') {
            p++;
        }
        Map<String, Object> cur = root;
        for (int i = 0; i < path.size(); i++) {
            String key = path.get(i);
            boolean last = i == path.size() - 1;
            Object existing = cur.get(key);
            if (last) {
                if (isArray) {
                    List<Object> list;
                    if (existing instanceof List<?> l) {
                        list = (List<Object>) l;
                    } else {
                        list = new ArrayList<>();
                        cur.put(key, list);
                    }
                    Map<String, Object> fresh = new LinkedHashMap<>();
                    list.add(fresh);
                    cur = fresh;
                } else {
                    if (existing instanceof Map<?, ?> m) {
                        cur = (Map<String, Object>) m;
                    } else {
                        Map<String, Object> fresh = new LinkedHashMap<>();
                        cur.put(key, fresh);
                        cur = fresh;
                    }
                }
            } else {
                if (existing instanceof List<?> l && !l.isEmpty() && l.get(l.size() - 1) instanceof Map) {
                    cur = (Map<String, Object>) l.get(l.size() - 1);
                } else if (existing instanceof Map<?, ?> m) {
                    cur = (Map<String, Object>) m;
                } else {
                    Map<String, Object> fresh = new LinkedHashMap<>();
                    cur.put(key, fresh);
                    cur = fresh;
                }
            }
        }
        return cur;
    }

    private List<String> parseKeyPath(char terminator) {
        List<String> path = new ArrayList<>(4);
        for (;;) {
            skipInline();
            if (p >= n) {
                break;
            }
            char c = s.charAt(p);
            if (c == terminator || c == '=') {
                break;
            }
            if (c == '\n') {
                break;
            }
            path.add(parseKeySegment());
            skipInline();
            if (p < n && s.charAt(p) == '.') {
                p++;
                continue;
            }
            break;
        }
        return path;
    }

    private String parseKeySegment() {
        char c = s.charAt(p);
        if (c == '"') {
            return parseBasicString();
        }
        if (c == '\'') {
            return parseLiteralString();
        }
        int start = p;
        while (p < n) {
            char k = s.charAt(p);
            if ((k >= 'A' && k <= 'Z') || (k >= 'a' && k <= 'z') || (k >= '0' && k <= '9')
                    || k == '_' || k == '-') {
                p++;
            } else {
                break;
            }
        }
        if (p == start) {
            throw err("非法键名");
        }
        return s.substring(start, p);
    }

    @SuppressWarnings("unchecked")
    private void keyValue(Map<String, Object> table) {
        List<String> path = parseKeyPath('=');
        skipInline();
        if (p >= n || s.charAt(p) != '=') {
            throw err("键后面缺少 '='");
        }
        p++;
        skipInline();
        Object value = parseValue();
        Map<String, Object> cur = table;
        for (int i = 0; i < path.size() - 1; i++) {
            Object o = cur.get(path.get(i));
            if (o instanceof Map<?, ?> m) {
                cur = (Map<String, Object>) m;
            } else {
                Map<String, Object> fresh = new LinkedHashMap<>();
                cur.put(path.get(i), fresh);
                cur = fresh;
            }
        }
        cur.put(path.get(path.size() - 1), value);
        expectLineEnd();
    }

    private Object parseValue() {
        char c = s.charAt(p);
        switch (c) {
            case '"':
                if (p + 2 < n && s.charAt(p + 1) == '"' && s.charAt(p + 2) == '"') {
                    return parseMultilineBasic();
                }
                return parseBasicString();
            case '\'':
                if (p + 2 < n && s.charAt(p + 1) == '\'' && s.charAt(p + 2) == '\'') {
                    return parseMultilineLiteral();
                }
                return parseLiteralString();
            case '[':
                return parseArray();
            case '{':
                return parseInlineTable();
            default:
                return parseScalar();
        }
    }

    private String parseBasicString() {
        p++;                                        
        StringBuilder sb = new StringBuilder(32);
        while (p < n) {
            char c = s.charAt(p++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                char e = s.charAt(p++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(s.substring(p, p + 4), 16));
                        p += 4;
                    }
                    case 'U' -> {
                        sb.appendCodePoint(Integer.parseInt(s.substring(p, p + 8), 16));
                        p += 8;
                    }
                    default -> sb.append(e);
                }
            } else {
                sb.append(c);
            }
        }
        throw err("字符串没有闭合");
    }

    private String parseLiteralString() {
        p++;
        int start = p;
        while (p < n && s.charAt(p) != '\'') {
            p++;
        }
        if (p >= n) {
            throw err("字面字符串没有闭合");
        }
        String out = s.substring(start, p);
        p++;
        return out;
    }

    private String parseMultilineBasic() {
        p += 3;
        if (p < n && s.charAt(p) == '\n') {
            p++;
        } else if (p + 1 < n && s.charAt(p) == '\r' && s.charAt(p + 1) == '\n') {
            p += 2;
        }
        StringBuilder sb = new StringBuilder(64);
        while (p < n) {
            if (s.charAt(p) == '"' && p + 2 < n && s.charAt(p + 1) == '"' && s.charAt(p + 2) == '"') {
                p += 3;
                return sb.toString();
            }
            char c = s.charAt(p++);
            if (c == '\\' && p < n) {
                char e = s.charAt(p);
                if (e == '\n' || e == '\r') {

                    while (p < n && (s.charAt(p) == ' ' || s.charAt(p) == '\t'
                            || s.charAt(p) == '\r' || s.charAt(p) == '\n')) {
                        p++;
                    }
                    continue;
                }
                p++;
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    default -> sb.append(e);
                }
            } else {
                sb.append(c);
            }
        }
        throw err("多行字符串没有闭合");
    }

    private String parseMultilineLiteral() {
        p += 3;
        if (p < n && s.charAt(p) == '\n') {
            p++;
        } else if (p + 1 < n && s.charAt(p) == '\r' && s.charAt(p + 1) == '\n') {
            p += 2;
        }
        int start = p;
        int idx = s.indexOf("'''", p);
        if (idx < 0) {
            throw err("多行字面字符串没有闭合");
        }
        p = idx + 3;
        return s.substring(start, idx);
    }

    private List<Object> parseArray() {
        p++;                                        
        List<Object> out = new ArrayList<>(4);
        for (;;) {
            skipBlank();
            if (p >= n) {
                throw err("数组没有闭合");
            }
            if (s.charAt(p) == ']') {
                p++;
                return out;
            }
            out.add(parseValue());
            skipBlank();
            if (p < n && s.charAt(p) == ',') {
                p++;
            }
        }
    }

    private Map<String, Object> parseInlineTable() {
        p++;                                        
        Map<String, Object> out = new LinkedHashMap<>();
        for (;;) {
            skipBlank();
            if (p >= n) {
                throw err("内联表没有闭合");
            }
            if (s.charAt(p) == '}') {
                p++;
                return out;
            }
            List<String> path = parseKeyPath('=');
            skipInline();
            if (p < n && s.charAt(p) == '=') {
                p++;
            }
            skipInline();
            Object v = parseValue();
            Map<String, Object> cur = out;
            for (int i = 0; i < path.size() - 1; i++) {
                Object o = cur.get(path.get(i));
                if (o instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> mm = (Map<String, Object>) m;
                    cur = mm;
                } else {
                    Map<String, Object> fresh = new LinkedHashMap<>();
                    cur.put(path.get(i), fresh);
                    cur = fresh;
                }
            }
            cur.put(path.get(path.size() - 1), v);
            skipBlank();
            if (p < n && s.charAt(p) == ',') {
                p++;
            }
        }
    }

    private Object parseScalar() {
        int start = p;
        while (p < n) {
            char c = s.charAt(p);
            if (c == '\n' || c == '\r' || c == ',' || c == ']' || c == '}' || c == '#') {
                break;
            }
            p++;
        }
        String raw = s.substring(start, p).trim();
        if (raw.isEmpty()) {
            throw err("空值");
        }
        if (raw.equals("true")) {
            return Boolean.TRUE;
        }
        if (raw.equals("false")) {
            return Boolean.FALSE;
        }
        return parseNumber(raw);
    }

    private Object parseNumber(String raw) {
        String cleaned = raw.replace("_", "");
        try {
            if (cleaned.startsWith("0x") || cleaned.startsWith("0X")) {
                return Long.parseLong(cleaned.substring(2), 16);
            }
            if (cleaned.startsWith("0o") || cleaned.startsWith("0O")) {
                return Long.parseLong(cleaned.substring(2), 8);
            }
            if (cleaned.startsWith("0b") || cleaned.startsWith("0B")) {
                return Long.parseLong(cleaned.substring(2), 2);
            }
            if (cleaned.indexOf('.') >= 0 || cleaned.indexOf('e') >= 0 || cleaned.indexOf('E') >= 0
                    || cleaned.endsWith("inf") || cleaned.endsWith("nan")) {
                return Double.parseDouble(cleaned.replace("inf", "Infinity").replace("nan", "NaN"));
            }
            return Long.parseLong(cleaned);
        } catch (NumberFormatException e) {
            // 日期时间之类的未支持类型：原样保留字符串，绝不因为一个字段解析失败就让整个模组加载不了。
            return raw;
        }
    }

    private IllegalArgumentException err(String msg) {
        int line = 1;
        for (int i = 0; i < p && i < n; i++) {
            if (s.charAt(i) == '\n') {
                line++;
            }
        }
        return new IllegalArgumentException("TOML 解析错误：" + msg + " (第 " + line + " 行)");
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> tables(Map<String, Object> root, String key) {
        Object o = root.get(key);
        if (!(o instanceof List<?> l)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>(l.size());
        for (Object e : l) {
            if (e instanceof Map<?, ?> m) {
                out.add((Map<String, Object>) m);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> table(Map<String, Object> root, String key) {
        Object o = root.get(key);
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    public static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v == null ? def : String.valueOf(v);
    }

    public static boolean bool(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String sv) {
            return Boolean.parseBoolean(sv.trim());
        }
        return def;
    }

    public static List<String> strList(Map<String, Object> m, String key) {
        Object v = m.get(key);
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object o : l) {
                out.add(String.valueOf(o));
            }
        } else if (v != null) {
            out.add(String.valueOf(v));
        }
        return out;
    }
}
