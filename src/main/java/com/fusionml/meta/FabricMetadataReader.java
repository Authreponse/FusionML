package com.fusionml.meta;

import com.fusionml.util.Json;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fabric 原生元数据读取器（{@code fabric.mod.json}）。
 *
 * <p>按 Fabric 自己的结构读，不做任何"翻译成别的格式"的动作：
 * 入口点分类、mixin 配置、depends/recommends/suggests/breaks/conflicts 的语义差异
 * 都在这里如实保留。
 */
public final class FabricMetadataReader {

    private FabricMetadataReader() {
    }

    public static ModMetadata read(byte[] jsonBytes, String jarName, int jarIndex, List<String> warnings) {
        Map<String, Object> root;
        try {
            root = Json.parseObject(jsonBytes);
        } catch (RuntimeException e) {
            warnings.add(jarName + "：fabric.mod.json 解析失败 —— " + e.getMessage());
            return null;
        }
        ModMetadata m = new ModMetadata(ModFormat.FABRIC, jarName, jarIndex, root);
        m.id = str(root, "id", "");
        m.version = str(root, "version", "0");
        m.name = str(root, "name", m.id);
        m.description = str(root, "description", "");
        m.license = str(root, "license", "");

        // authors 的两种写法都要认：字符串数组，或带 name 的对象数组。
        for (Object a : Json.arr(root.get("authors"))) {
            if (a instanceof Map<?, ?> am) {
                Object n = am.get("name");
                if (n != null) {
                    m.authors.add(n.toString());
                }
            } else if (a != null) {
                m.authors.add(a.toString());
            }
        }

        // 入口点：{"main": [...], "client": [...], "server": [...]}
        Map<String, Object> eps = Json.obj(root.get("entrypoints"));
        for (Map.Entry<String, Object> e : eps.entrySet()) {
            List<String> classes = new ArrayList<>(2);
            for (Object item : Json.arr(e.getValue())) {
                String cls = entrypointClass(item);
                if (cls != null && !cls.isBlank()) {
                    classes.add(cls);
                }
            }
            if (!classes.isEmpty()) {
                m.entrypoints.put(e.getKey(), classes);
            }
        }
        for (List<String> v : m.entrypoints.values()) {
            for (String c : v) {
                if (!m.entrypointClasses.contains(c)) {
                    m.entrypointClasses.add(c);
                }
            }
        }

        // Mixin 配置：字符串，或 {"config": "...", "environment": "..."}
        for (Object item : Json.arr(root.get("mixins"))) {
            if (item instanceof String s) {
                m.mixinConfigs.add(s);
            } else if (item instanceof Map<?, ?> im && im.get("config") != null) {
                m.mixinConfigs.add(im.get("config").toString());
            }
        }

        readDepMap(root.get("depends"), true, "必需", m);
        readDepMap(root.get("recommends"), false, "推荐", m);
        readDepMap(root.get("suggests"), false, "建议", m);
        readDepMap(root.get("breaks"), false, "不兼容", m);
        readDepMap(root.get("conflicts"), false, "冲突", m);

        // provides：本模组同时"顶替"提供的其它 modId（Fabric API 就是靠它声明一堆子模块）。
        // 支持字符串数组，也支持 [{"id":"...","version":"..."}] 这种对象写法。
        for (Object item : Json.arr(root.get("provides"))) {
            if (item instanceof String s) {
                m.provides.add(s);
            } else if (item instanceof Map<?, ?> im && im.get("id") != null) {
                m.provides.add(im.get("id").toString());
            }
        }
        return m;
    }

    /** 入口点条目可以是 "类名"、["类名","方法"] 或 {"value":"类名","adapter":"..."}。 */
    private static String entrypointClass(Object item) {
        if (item instanceof String s) {
            return s;
        }
        if (item instanceof List<?> l && !l.isEmpty()) {
            return String.valueOf(l.get(0));
        }
        if (item instanceof Map<?, ?> im && im.get("value") != null) {
            return im.get("value").toString();
        }
        return null;
    }

    private static void readDepMap(Object node, boolean mandatory, String reason, ModMetadata m) {
        if (!(node instanceof Map<?, ?> map)) {
            return;
        }
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String depId = String.valueOf(e.getKey());
            String range = null;
            Object v = e.getValue();
            if (v instanceof List<?> l) {
                StringBuilder sb = new StringBuilder();
                for (Object o : l) {
                    if (sb.length() > 0) {
                        sb.append(" || ");
                    }
                    sb.append(o);
                }
                range = sb.toString();
            } else if (v != null) {
                range = v.toString();
            }
            m.dependencies.add(new Dependency(depId, mandatory, range,
                    Dependency.Ordering.NONE, Dependency.Side.BOTH, reason));
        }
    }

    private static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v == null ? def : v.toString();
    }

    /** 供测试与报告使用：把元数据还原成 JSON 文本。 */
    public static String roundTrip(Map<String, Object> raw) {
        return Json.write(new LinkedHashMap<>(raw));
    }

    static String utf8(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
