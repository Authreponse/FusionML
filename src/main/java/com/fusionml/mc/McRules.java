package com.fusionml.mc;

import com.fusionml.util.Json;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 版本 JSON 里 {@code rules} 的求值器。
 *
 * <p>Minecraft 用规则数组决定一个库在"当前系统"上要不要加载、原生库要不要解压：
 * 规则按顺序求值，命中 {@code allow} 就是允许、命中 {@code disallow} 就是拒绝，
 * <b>最后一条命中的规则说了算</b>；一条都没命中时默认拒绝。
 * 这个"最后命中者优先"的语义很容易被写成"任意命中即允许"，那是一类典型的偶发错库问题
 * （在别人机器上没事、在你这儿少一个库），所以这里严格按规范实现。
 *
 * <p>支持的匹配维度：{@code os.name}（windows/linux/osx）、{@code os.arch}（x86/x86_64/arm64）、
 * {@code os.version}（正则）、{@code features}（has_custom_resolution 之类的启动器特性）。
 */
public final class McRules {

    private McRules() {
    }

    /** 当前系统推断出的 os.name 取值。 */
    public static String currentOsName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "osx";
        }
        return "linux";
    }

    /** 当前系统推断出的 os.arch 取值（Minecraft 用的是 x86 / x86_64 / arm64 这套叫法）。 */
    public static String currentOsArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        switch (arch) {
            case "amd64":
            case "x86_64":
            case "x64":
                return "x86_64";
            case "x86":
            case "i386":
            case "i486":
            case "i586":
            case "i686":
                return "x86";
            case "aarch64":
            case "arm64":
                return "arm64";
            default:
                return arch;
        }
    }

    /**
     * 求值一个 rules 数组。
     *
     * @param rules    版本 JSON 里的 {@code rules}（可为 null，表示无条件适用）
     * @param features 启动器特性开关，例如 {@code has_custom_resolution=true}
     */
    public static boolean allows(Object rules, Map<String, Boolean> features) {
        if (!(rules instanceof List<?> list) || list.isEmpty()) {
            // 没有规则 = 无条件适用。
            return true;
        }
        boolean allowed = false;
        for (Object item : list) {
            Map<String, Object> rule = Json.obj(item);
            if (!matches(rule, features)) {
                continue;
            }
            String action = Json.str(rule, "action", "allow");
            allowed = "allow".equalsIgnoreCase(action);
        }
        return allowed;
    }

    private static boolean matches(Map<String, Object> rule, Map<String, Boolean> features) {
        Object os = rule.get("os");
        if (os instanceof Map<?, ?> osm) {
            Map<String, Object> osMap = Json.obj(osm);
            String name = Json.str(osMap, "name");
            if (name != null && !name.equalsIgnoreCase(currentOsName())) {
                return false;
            }
            String arch = Json.str(osMap, "arch");
            if (arch != null && !arch.equalsIgnoreCase(currentOsArch())) {
                return false;
            }
            String version = Json.str(osMap, "version");
            if (version != null) {
                String actual = System.getProperty("os.version", "");
                try {
                    if (!actual.matches(version)) {
                        return false;
                    }
                } catch (RuntimeException e) {
                    return false;
                }
            }
        }
        Object feat = rule.get("features");
        if (feat instanceof Map<?, ?> fm) {
            for (Map.Entry<String, Object> e : Json.obj(fm).entrySet()) {
                boolean required = Boolean.parseBoolean(String.valueOf(e.getValue()));
                boolean actual = features != null && features.getOrDefault(e.getKey(), false);
                if (required != actual) {
                    return false;
                }
            }
        }
        return true;
    }
}
