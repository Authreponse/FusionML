package com.fusionml.version;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 版本区间。三端写法全部支持，因为它们各自都会出现在真实模组里：
 *
 * <table border="1">
 *   <caption>支持的写法</caption>
 *   <tr><td>{@code [1.0,2.0)}</td><td>Maven 区间（Forge / NeoForge 主流）</td></tr>
 *   <tr><td>{@code [1.0,2.0) || [3.0,4.0)}</td><td>区间并集</td></tr>
 *   <tr><td>{@code >=1.0.0 <2.0.0}</td><td>比较符序列（Fabric 主流）</td></tr>
 *   <tr><td>{@code ^1.2.3} / {@code ~1.2.3}</td><td>插入号 / 波浪号（npm 风格，Fabric 也用）</td></tr>
 *   <tr><td>{@code 1.2.x} / {@code 1.2.*} / {@code *}</td><td>通配</td></tr>
 *   <tr><td>{@code 1.0.0}</td><td>精确匹配</td></tr>
 * </table>
 *
 * <p>无法解析时不会抛异常打断加载，而是退化成"字符串相等即通过"，
 * 并把这条退化记录在 {@link #degraded()} 里，方便上层提示用户。
 */
public final class VersionRange {

    /** 单个区间。 */
    private record Interval(Version low, boolean lowInclusive, Version high, boolean highInclusive) {
    }

    private final String raw;
    private final List<Interval> intervals = new ArrayList<>(2);
    private final List<Bound> comparators = new ArrayList<>(2);
    /**
     * 并集分支：{@code "A || B"} 表示"满足 A <b>或</b> 满足 B"。
     *
     * <p>这里必须单独存一份子区间列表，而不能把各分支的条件都塞进上面的
     * intervals/comparators —— 那样就变成了"同时满足所有分支"（逻辑与），
     * 语义完全相反。真实模组里 {@code "~1.20.1 || 1.21.1 || ~26.3"} 这种写法很常见，
     * 写成逻辑与会让所有版本都不匹配。
     */
    private final List<VersionRange> alternatives = new ArrayList<>(2);
    private boolean wildcard;
    private String exact;
    private boolean degraded;

    /** 比较符约束：{@code >=1.0} 之类。 */
    private record Bound(boolean greater, boolean inclusive, Version version) {
    }

    private VersionRange(String raw) {
        this.raw = raw;
    }

    public static VersionRange of(String s) {
        VersionRange r = new VersionRange(s == null ? "" : s.trim());
        r.parse();
        return r;
    }

    public boolean degraded() {
        return degraded;
    }

    public String raw() {
        return raw;
    }

    private void parse() {
        String s = raw;
        if (s.isEmpty() || "*".equals(s) || "any".equalsIgnoreCase(s)) {
            wildcard = true;
            return;
        }
        if (s.contains("||")) {
            for (String part : s.split("\\|\\|")) {
                String p = part.trim();
                if (p.isEmpty()) {
                    continue;
                }
                alternatives.add(of(p));
            }
            return;
        }
        if (s.startsWith("[") || s.startsWith("(")) {
            int close = s.indexOf(']');
            int closeParen = s.indexOf(')');
            int end = close < 0 ? closeParen : (closeParen < 0 ? close : Math.min(close, closeParen));
            if (end < 0) {
                degraded = true;
                exact = s;
                return;
            }
            String body = s.substring(1, end);
            boolean lowInc = s.charAt(0) == '[';
            boolean highInc = s.charAt(end) == ']';
            String[] parts = body.split(",", -1);
            String lo = parts.length > 0 ? parts[0].trim() : "";
            String hi = parts.length > 1 ? parts[1].trim() : "";
            if (parts.length == 1 && !lo.isEmpty()) {
                // [1.0] 表示精确匹配
                comparators.add(new Bound(true, true, Version.of(lo)));
                comparators.add(new Bound(false, true, Version.of(lo)));
                return;
            }
            intervals.add(new Interval(
                    lo.isEmpty() ? null : Version.of(lo), lowInc,
                    hi.isEmpty() ? null : Version.of(hi), highInc));
            return;
        }
        if (s.startsWith("^") || s.startsWith("~") || s.charAt(0) == '>' || s.charAt(0) == '<'
                || s.charAt(0) == '=') {
            parseComparatorChain(s);
            return;
        }
        if (s.contains("x") || s.contains("X") || s.contains("*")) {
            parseWildcard(s);
            return;
        }
        exact = s;
    }

    private void parseComparatorChain(String s) {
        String[] tokens = s.split("\\s+");
        for (String tokenRaw : tokens) {
            String token = tokenRaw.trim();
            if (token.isEmpty()) {
                continue;
            }
            if (token.startsWith("^") || token.startsWith("~")) {
                boolean caret = token.charAt(0) == '^';
                String v = token.substring(1);
                String[] seg = v.split("\\.");
                try {
                    int major = Integer.parseInt(digits(seg[0]));
                    int minor = seg.length > 1 ? Integer.parseInt(digits(seg[1])) : 0;
                    int patch = seg.length > 2 ? Integer.parseInt(digits(seg[2])) : 0;
                    Version low = Version.of(v);
                    Version high;
                    if (caret) {
                        // ^1.2.3 → [1.2.3, 2.0.0)；^0.2.3 → [0.2.3, 0.3.0)；^0.0.3 → [0.0.3, 0.0.4)
                        if (major != 0) {
                            high = Version.of((major + 1) + ".0.0");
                        } else if (minor != 0) {
                            high = Version.of("0." + (minor + 1) + ".0");
                        } else {
                            high = Version.of("0.0." + (patch + 1));
                        }
                    } else {
                        // ~1.2.3 → [1.2.3, 1.3.0)；~1.2 → [1.2, 1.3)
                        high = Version.of(major + "." + (minor + 1) + ".0");
                    }
                    intervals.add(new Interval(low, true, high, false));
                } catch (RuntimeException e) {
                    degraded = true;
                    exact = v;
                }
                continue;
            }
            if (token.startsWith(">=")) {
                comparators.add(new Bound(true, true, Version.of(token.substring(2))));
            } else if (token.startsWith("<=")) {
                comparators.add(new Bound(false, true, Version.of(token.substring(2))));
            } else if (token.startsWith(">")) {
                comparators.add(new Bound(true, false, Version.of(token.substring(1))));
            } else if (token.startsWith("<")) {
                comparators.add(new Bound(false, false, Version.of(token.substring(1))));
            } else if (token.startsWith("=")) {
                comparators.add(new Bound(true, true, Version.of(token.substring(1))));
                comparators.add(new Bound(false, true, Version.of(token.substring(1))));
            } else {
                exact = token;
            }
        }
    }

    private void parseWildcard(String s) {
        String norm = s.replace('X', 'x').replace('*', 'x');
        String[] seg = norm.split("\\.");
        Version low;
        Version high;
        try {
            if (seg.length == 1 && "x".equals(seg[0])) {
                wildcard = true;
                return;
            }
            if ("x".equals(seg[0])) {
                wildcard = true;
                return;
            }
            int major = Integer.parseInt(digits(seg[0]));
            if (seg.length == 1) {
                low = Version.of(major + ".0.0");
                high = Version.of((major + 1) + ".0.0");
            } else if ("x".equals(seg[1])) {
                low = Version.of(major + ".0.0");
                high = Version.of((major + 1) + ".0.0");
            } else {
                int minor = Integer.parseInt(digits(seg[1]));
                low = Version.of(major + "." + minor + ".0");
                high = Version.of(major + "." + (minor + 1) + ".0");
            }
            intervals.add(new Interval(low, true, high, false));
        } catch (RuntimeException e) {
            degraded = true;
            exact = s;
        }
    }

    private static String digits(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c)) {
                sb.append(c);
            } else {
                break;
            }
        }
        return sb.length() == 0 ? "0" : sb.toString();
    }

    /** 判断某个具体版本是否落在区间内。 */
    public boolean accepts(String version) {
        if (!alternatives.isEmpty()) {
            // 并集：任一分支满足即可。
            for (VersionRange alt : alternatives) {
                if (alt.accepts(version)) {
                    return true;
                }
            }
            return false;
        }
        if (wildcard) {
            return true;
        }
        if (exact != null) {
            return exact.equalsIgnoreCase(version == null ? "" : version.trim())
                    || Version.of(exact).compareTo(Version.of(version)) == 0;
        }
        Version v = Version.of(version);
        for (Interval iv : intervals) {
            if (!checkInterval(iv, v)) {
                return false;
            }
        }
        for (Bound b : comparators) {
            int c = v.compareTo(b.version());
            if (b.greater()) {
                if (b.inclusive() ? c < 0 : c <= 0) {
                    return false;
                }
            } else {
                if (b.inclusive() ? c > 0 : c >= 0) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean checkInterval(Interval iv, Version v) {
        if (iv.low() != null) {
            int c = v.compareTo(iv.low());
            if (iv.lowInclusive() ? c < 0 : c <= 0) {
                return false;
            }
        }
        if (iv.high() != null) {
            int c = v.compareTo(iv.high());
            if (iv.highInclusive() ? c > 0 : c >= 0) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        if (wildcard) {
            return "*";
        }
        if (exact != null) {
            return exact;
        }
        return raw.toLowerCase(Locale.ROOT);
    }
}
