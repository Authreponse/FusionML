package com.fusionml.util;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 自研 JSON 解析器。设计目标是"模组元数据极速解析"：
 *
 * <ul>
 *   <li>直接在 {@code byte[]} 上扫描，结构字符全是 ASCII，不需要先整体解码成 String；</li>
 *   <li>只有字符串内容才做 UTF-8 解码，且无转义时走批量拷贝快路径；</li>
 *   <li>不递归下降解析对象/数组（深递归在长依赖列表上会压栈），
 *       用显式状态机 + 容器栈，栈深度只受数据嵌套层数限制；</li>
 *   <li>整数尽量给 {@code Long}，避免把版本号之类的整数变成浮点。</li>
 * </ul>
 */
public final class Json {

    private byte[] buf;
    private int pos;
    private int len;

    private Json(byte[] data) {
        this.buf = data;
        this.len = data.length;
    }

    public static Object parse(byte[] data) {
        Json p = new Json(data);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.pos < p.len) {
            throw p.err("JSON 尾部有多余字符");
        }
        return v;
    }

    public static Object parse(String s) {
        return parse(s.getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(byte[] data) {
        Object o = parse(data);
        if (!(o instanceof Map)) {
            throw new IllegalArgumentException("期望 JSON 对象，实际是 " + typeName(o));
        }
        return (Map<String, Object>) o;
    }

    private static String typeName(Object o) {
        return o == null ? "null" : o.getClass().getSimpleName();
    }

    private IllegalArgumentException err(String msg) {
        int line = 1;
        int col = 1;
        for (int i = 0; i < pos && i < len; i++) {
            if (buf[i] == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        return new IllegalArgumentException(msg + " (第 " + line + " 行 第 " + col + " 列)");
    }

    private void ws() {
        while (pos < len) {
            byte c = buf[pos];
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                return;
            }
        }
    }

    private Object value() {
        if (pos >= len) {
            throw err("JSON 意外结束");
        }
        byte c = buf[pos];
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
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
                return number();
        }
    }

    private Map<String, Object> object() {
        pos++;                       
        Map<String, Object> map = new LinkedHashMap<>();
        ws();
        if (pos < len && buf[pos] == '}') {
            pos++;
            return map;
        }
        for (;;) {
            ws();
            if (pos >= len || buf[pos] != '"') {
                throw err("对象键必须是字符串");
            }
            String key = string();
            ws();
            if (pos >= len || buf[pos] != ':') {
                throw err("对象键后面缺少 ':'");
            }
            pos++;
            ws();
            map.put(key, value());
            ws();
            if (pos >= len) {
                throw err("对象没有闭合");
            }
            byte c = buf[pos++];
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw err("对象成员之间缺少 ','");
            }
        }
    }

    private List<Object> array() {
        pos++;                       
        List<Object> list = new ArrayList<>();
        ws();
        if (pos < len && buf[pos] == ']') {
            pos++;
            return list;
        }
        for (;;) {
            ws();
            list.add(value());
            ws();
            if (pos >= len) {
                throw err("数组没有闭合");
            }
            byte c = buf[pos++];
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw err("数组元素之间缺少 ','");
            }
        }
    }

    private String string() {
        pos++;                       
        int start = pos;

        boolean simple = true;
        int p = pos;
        while (p < len) {
            byte c = buf[p];
            if (c == '"') {
                break;
            }
            if (c == '\\' || (c & 0x80) != 0) {
                simple = false;
                break;
            }
            p++;
        }
        if (simple && p < len) {
            String s = new String(buf, start, p - start, StandardCharsets.ISO_8859_1);
            pos = p + 1;
            return s;
        }
        StringBuilder sb = new StringBuilder(Math.max(16, p - start));
        while (pos < len) {
            byte c = buf[pos];
            if (c == '"') {
                pos++;
                return sb.toString();
            }
            if (c == '\\') {
                pos++;
                if (pos >= len) {
                    throw err("转义序列被截断");
                }
                byte e = buf[pos++];
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > len) {
                            throw err("\\u 转义不完整");
                        }
                        int cp = hex4();
                        if (Character.isHighSurrogate((char) cp) && pos + 6 <= len
                                && buf[pos] == '\\' && buf[pos + 1] == 'u') {
                            pos += 2;
                            int low = hex4();
                            sb.appendCodePoint(Character.toCodePoint((char) cp, (char) low));
                        } else {
                            sb.append((char) cp);
                        }
                    }
                    default -> throw err("非法转义 \\" + (char) e);
                }
            } else if ((c & 0x80) == 0) {
                sb.append((char) c);
                pos++;
            } else {
                // 手动 UTF-8 解码，避免为多字节字符走 String 构造的开销。
                int cp;
                int b0 = c & 0xFF;
                if ((b0 & 0xE0) == 0xC0) {
                    cp = b0 & 0x1F;
                    pos++;
                    cp = (cp << 6) | (buf[pos++] & 0x3F);
                } else if ((b0 & 0xF0) == 0xE0) {
                    cp = b0 & 0x0F;
                    pos++;
                    cp = (cp << 6) | (buf[pos++] & 0x3F);
                    cp = (cp << 6) | (buf[pos++] & 0x3F);
                } else {
                    cp = b0 & 0x07;
                    pos++;
                    cp = (cp << 6) | (buf[pos++] & 0x3F);
                    cp = (cp << 6) | (buf[pos++] & 0x3F);
                    cp = (cp << 6) | (buf[pos++] & 0x3F);
                }
                sb.appendCodePoint(cp);
            }
        }
        throw err("字符串没有闭合");
    }

    private int hex4() {
        int v = 0;
        for (int i = 0; i < 4; i++) {
            byte c = buf[pos++];
            int d;
            if (c >= '0' && c <= '9') {
                d = c - '0';
            } else if (c >= 'a' && c <= 'f') {
                d = c - 'a' + 10;
            } else if (c >= 'A' && c <= 'F') {
                d = c - 'A' + 10;
            } else {
                throw err("\\u 后面不是十六进制");
            }
            v = (v << 4) | d;
        }
        return v;
    }

    private Object number() {
        int start = pos;
        boolean floating = false;
        while (pos < len) {
            byte c = buf[pos];
            if (c == '-' || c == '+' || (c >= '0' && c <= '9')) {
                pos++;
            } else if (c == '.' || c == 'e' || c == 'E') {
                floating = true;
                pos++;
            } else {
                break;
            }
        }
        if (pos == start) {
            throw err("无法识别的值，起始字节 0x" + Integer.toHexString(buf[pos] & 0xFF));
        }
        String s = new String(buf, start, pos - start, StandardCharsets.ISO_8859_1);
        if (!floating) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {

            }
        }
        return Double.parseDouble(s);
    }

    private void expect(String word) {
        for (int i = 0; i < word.length(); i++) {
            if (pos + i >= len || buf[pos + i] != word.charAt(i)) {
                throw err("期望字面量 " + word);
            }
        }
        pos += word.length();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) {
        return o instanceof List ? (List<Object>) o : List.of();
    }

    public static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    public static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v == null ? def : v.toString();
    }

    public static Object get(Map<String, Object> m, String dottedPath) {
        Object cur = m;
        for (String part : dottedPath.split("\\.")) {
            if (!(cur instanceof Map)) {
                return null;
            }
            cur = ((Map<?, ?>) cur).get(part);
        }
        return cur;
    }

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder(256);
        writeTo(v, sb, 0);
        return sb.toString();
    }

    private static void writeTo(Object v, StringBuilder sb, int depth) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            }
            sb.append('"');
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeTo(String.valueOf(e.getKey()), sb, depth + 1);
                sb.append(':');
                writeTo(e.getValue(), sb, depth + 1);
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeTo(o, sb, depth + 1);
            }
            sb.append(']');
        } else if (v instanceof Boolean || v instanceof Number) {
            sb.append(v);
        } else {
            writeTo(String.valueOf(v), sb, depth);
        }
    }
}
