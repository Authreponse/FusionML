package com.fusionml.version;

import java.util.ArrayList;
import java.util.List;

/**
 * 版本号。三端模组用的版本写法五花八门（{@code 1.20.1}、{@code 0.14.21+build.1}、
 * {@code 47}、{@code 1.0.0-beta.2}），所以这里采用"Maven 风格"的通用比较：
 * 按分隔符切段，数字段按数值比、字符串段按字典比，且"有预发布后缀"小于"没有后缀"。
 */
public final class Version implements Comparable<Version> {

    private final String raw;
    private final List<Object> parts;
    private final boolean hasQualifier;

    private Version(String raw, List<Object> parts, boolean hasQualifier) {
        this.raw = raw;
        this.parts = parts;
        this.hasQualifier = hasQualifier;
    }

    public static Version of(String s) {
        String raw = s == null ? "" : s.trim();
        List<Object> parts = new ArrayList<>(6);
        boolean qualifier = false;
        int i = 0;
        int n = raw.length();
        StringBuilder buf = new StringBuilder();
        boolean numeric = false;
        while (i <= n) {
            char c = i < n ? raw.charAt(i) : '.';
            boolean isDigit = Character.isDigit(c);
            if (i < n && (isDigit || Character.isLetter(c))) {
                if (buf.length() > 0 && isDigit != numeric) {
                    parts.add(numeric ? safeLong(buf.toString()) : buf.toString());
                    buf.setLength(0);
                    if (!isDigit) {
                        qualifier = true;
                    }
                }
                numeric = isDigit;
                buf.append(c);
            } else {
                if (buf.length() > 0) {
                    parts.add(numeric ? safeLong(buf.toString()) : buf.toString());
                    buf.setLength(0);
                    if (!numeric) {
                        qualifier = true;
                    }
                }
            }
            i++;
        }
        return new Version(raw, parts, qualifier);
    }

    private static Object safeLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            // 超长数字段：退化成字符串比较，绝不因为"数字太大"而抛异常。
            return s;
        }
    }

    public String raw() {
        return raw;
    }

    @Override
    public int compareTo(Version o) {
        int n = Math.min(parts.size(), o.parts.size());
        for (int i = 0; i < n; i++) {
            int c = comparePart(parts.get(i), o.parts.get(i));
            if (c != 0) {
                return c;
            }
        }
        if (parts.size() != o.parts.size()) {

            //     因为"正式的 26.3"必须大于"它的候选版 26.3-rc.3"。
            //     忽略这条会让所有写着 >=26.3-rc.3 的模组被误判为"版本不满足"。
            boolean selfLonger = parts.size() > o.parts.size();
            List<Object> longer = selfLonger ? parts : o.parts;
            for (int i = n; i < longer.size(); i++) {
                Object p = longer.get(i);
                if (p instanceof Long l) {
                    if (l == 0L) {
                        continue;
                    }
                    return selfLonger ? 1 : -1;
                }
                return selfLonger ? -1 : 1;
            }
            return 0;
        }
        return 0;
    }

    private static int comparePart(Object a, Object b) {
        boolean na = a instanceof Long;
        boolean nb = b instanceof Long;
        if (na && nb) {
            return Long.compare((Long) a, (Long) b);
        }
        if (na != nb) {

            return na ? 1 : -1;
        }
        return a.toString().compareToIgnoreCase(b.toString());
    }

    public boolean isPreRelease() {
        return hasQualifier;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Version v && compareTo(v) == 0;
    }

    @Override
    public int hashCode() {
        return raw.hashCode();
    }

    @Override
    public String toString() {
        return raw;
    }
}
