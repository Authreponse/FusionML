package com.fusionml.meta;

import com.fusionml.util.Toml;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Forge / NeoForge 原生元数据读取器（{@code mods.toml} / {@code neoforge.mods.toml}）。
 *
 * <p>两者的 TOML 结构同源但不等同；本读取器把差异点（文件名、部分键的默认值）
 * 参数化，而不是把 NeoForge 当成 Forge 的别名。
 *
 * <p>处理了几个现实里一定会遇到的细节：
 * <ul>
 *   <li>一个 jar 里可以有多个 {@code [[mods]]}（打包发行版）；</li>
 *   <li>{@code [[dependencies.<标签>]]} 的表名标签理论上可以和 modId 不同，
 *       所以按标签归位，归不上的合并到主模组并给出提示；</li>
 *   <li>{@code version="${file.jarVersion}"} 需要从 jar 的 MANIFEST 里取值。</li>
 * </ul>
 */
public final class ForgeMetadataReader {

    private ForgeMetadataReader() {
    }

    public static List<ModMetadata> read(byte[] tomlBytes, ModFormat format, String jarName,
                                         int jarIndex, String manifestVersion,
                                         List<String> warnings) {
        String text = new String(tomlBytes, StandardCharsets.UTF_8);
        // 去掉 UTF-8 BOM，否则第一个键名会带上不可见字符 —— 这是现场最常见的一类"莫名失败"。
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        Map<String, Object> root;
        try {
            root = Toml.parse(text);
        } catch (RuntimeException e) {
            warnings.add(jarName + "：" + format.displayName() + " 的 TOML 解析失败 —— " + e.getMessage());
            return List.of();
        }

        List<Map<String, Object>> modsTables = Toml.tables(root, "mods");
        List<ModMetadata> out = new ArrayList<>(Math.max(1, modsTables.size()));
        if (modsTables.isEmpty()) {
            warnings.add(jarName + "：" + format.displayName() + " 元数据里没有 [[mods]] 段");
            return List.of();
        }

        String license = Toml.str(root, "license", "");
        Map<String, List<Map<String, Object>>> depGroups = dependencyGroups(root);
        // 顶层 [[mixins]] 是 NeoForge 官方文档里的写法，[[mods.mixins]] 是另一部分模组
        // 与 Forge 的写法 —— 两种都在现实产物里出现，所以两种都读。

        List<Map<String, Object>> topLevelMixins = Toml.tables(root, "mixins");

        for (Map<String, Object> t : modsTables) {
            ModMetadata m = new ModMetadata(format, jarName, jarIndex, t);
            m.id = Toml.str(t, "modId", "");
            m.name = Toml.str(t, "displayName", m.id);
            m.version = substituteVersion(Toml.str(t, "version", "0"), manifestVersion);
            m.description = Toml.str(t, "description", "");
            m.license = Toml.str(t, "license", license);
            m.displayTest = Toml.str(t, "displayTest", "");
            // NeoForge / Forge 的 Mixin 声明写在 [[mixins]] 里（键名是 config）。
            // 这是"一个服务装两边配置"必须读到的第一条信息：不知道有哪些配置文件，就无从装载。
            for (Map<String, Object> mixin : Toml.tables(t, "mixins")) {
                String cfg = Toml.str(mixin, "config", "");
                if (!cfg.isBlank() && !m.mixinConfigs.contains(cfg)) {
                    m.mixinConfigs.add(cfg);
                }
            }
            for (Map<String, Object> mixin : topLevelMixins) {
                String cfg = Toml.str(mixin, "config", "");
                if (!cfg.isBlank() && !m.mixinConfigs.contains(cfg)) {
                    m.mixinConfigs.add(cfg);
                }
            }

            Object authors = t.get("authors");
            if (authors instanceof List<?> l) {
                for (Object a : l) {
                    m.authors.add(String.valueOf(a));
                }
            } else if (authors != null) {

                for (String a : authors.toString().split(",")) {
                    String s = a.trim();
                    if (!s.isEmpty()) {
                        m.authors.add(s);
                    }
                }
            }
            out.add(m);
        }

        // 依赖按标签归位；归不上的并入第一个模组，并留下提示，绝不静默丢弃。
        List<Map<String, Object>> orphan = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : depGroups.entrySet()) {
            ModMetadata owner = null;
            for (ModMetadata m : out) {
                if (m.id.equals(e.getKey())) {
                    owner = m;
                    break;
                }
            }
            if (owner == null) {
                orphan.addAll(e.getValue());
                continue;
            }
            applyDependencies(owner, e.getValue());
        }
        if (!orphan.isEmpty() && !out.isEmpty()) {
            applyDependencies(out.get(0), orphan);
            warnings.add(jarName + "：有 " + orphan.size() + " 条依赖的表名与任何 modId 都不匹配，已归到 "
                    + out.get(0).id + " 名下");
        }
        return out;
    }

    private static Map<String, List<Map<String, Object>>> dependencyGroups(Map<String, Object> root) {
        Map<String, List<Map<String, Object>>> groups = new java.util.LinkedHashMap<>();
        Object deps = root.get("dependencies");
        if (!(deps instanceof Map<?, ?> dm)) {
            return groups;
        }
        for (Map.Entry<?, ?> e : dm.entrySet()) {
            String label = String.valueOf(e.getKey());
            Object v = e.getValue();
            List<Map<String, Object>> list = new ArrayList<>();
            if (v instanceof List<?> l) {
                for (Object o : l) {
                    if (o instanceof Map<?, ?> mm) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> cast = (Map<String, Object>) mm;
                        list.add(cast);
                    }
                }
            } else if (v instanceof Map<?, ?> mm) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) mm;
                list.add(cast);
            }
            if (!list.isEmpty()) {
                groups.put(label, list);
            }
        }
        return groups;
    }

    private static void applyDependencies(ModMetadata m, List<Map<String, Object>> tables) {
        for (Map<String, Object> t : tables) {
            String depId = Toml.str(t, "modId", "");
            if (depId.isBlank()) {
                continue;
            }
            boolean mandatory = Toml.bool(t, "mandatory", true);
            String range = Toml.str(t, "versionRange", "");
            Dependency.Ordering ordering = Dependency.Ordering.of(Toml.str(t, "ordering", "NONE"));
            Dependency.Side side = Dependency.Side.of(Toml.str(t, "side", "BOTH"));
            String reason = mandatory ? "必需" : "可选";
            m.dependencies.add(new Dependency(depId, mandatory, range, ordering, side, reason));
        }
    }

    private static String substituteVersion(String version, String manifestVersion) {
        if (version == null) {
            return "0";
        }
        if (!version.contains("${")) {
            return version;
        }
        String v = version.replace("${file.jarVersion}", manifestVersion == null ? "0" : manifestVersion);
        v = v.replace("${global.mavenVersion}", "0");
        return v;
    }
}
