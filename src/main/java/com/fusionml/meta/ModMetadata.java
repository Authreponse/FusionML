package com.fusionml.meta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 三端元数据的统一视图。
 *
 * <p>注意这里"统一"的只是<b>查询接口</b>：{@code raw} 里始终保留该端格式的原始结构，
 * 各端原生适配器需要什么就拿什么，不做有损转换。
 */
public final class ModMetadata {

    private final ModFormat format;
    private final String jarName;
    private final int jarIndex;
    private long jarBytes;
    private final Map<String, Object> raw;

    public String id = "";
    public String name = "";
    public String version = "0";
    public String description = "";
    public final List<String> authors = new ArrayList<>(2);
    public final List<Dependency> dependencies = new ArrayList<>(4);
    public final List<String> mixinConfigs = new ArrayList<>(2);
    /** 平台原生入口点：Fabric 是入口点类名；Forge/NeoForge 是 {@code @Mod} 主类名。 */
    public final List<String> entrypointClasses = new ArrayList<>(2);
    /** Fabric 入口点分类（main / client / server ...）→ 类名列表。 */
    public final Map<String, List<String>> entrypoints = new LinkedHashMap<>();
    /**
     * 同一个 jar 还提供了哪些端的元数据（多端同包）。
     *
     * <p>例如 {@code DistantHorizons-...-fabric-neoforge.jar} 里同时有
     * {@code fabric.mod.json} 与 {@code neoforge.mods.toml}。这时两边的 modId 相同，
     * 但那是<b>同一个模组</b>的两套声明，不是"两个模组抢一个 id"。
     * 它们会被合并成一条记录，另一种格式记在这里，注册阶段再对每种格式各跑一次适配器。
     */
    public final List<ModFormat> alternativeFormats = new ArrayList<>(1);
    /**
     * 本模组额外"顶替"提供的 modId（Fabric 的 {@code provides}）。
     *
     * <p>最典型的例子是 Fabric API：它自己是一个模组，同时通过 {@code provides} 声明
     * 自己内含 {@code fabric-api-base}、{@code fabric-lifecycle-events-v1} 等一大批子模块 id。
     * 不解析这个字段，加载器就会把依赖这些子模块的模组全部报成"缺少必需依赖"。
     */
    public final List<String> provides = new ArrayList<>(4);
    /**
     * 这条元数据是否来自"嵌套 jar"（外层模组包内嵌的子包）。
     *
     * <p>用途只有一个但很关键：当同一个 modId 既有独立安装的模组、又有某个模组内嵌的副本时，
     * 必须优先采用<b>独立安装</b>的那一份。否则一个把整份 Fabric API 打包进去的模组，
     * 可能把玩家真正安装的 Fabric API 顶掉，后续所有依赖它的模组都会出问题。
     */
    public boolean nested;
    public String license = "";
    public String displayTest = "";

    public ModMetadata(ModFormat format, String jarName, int jarIndex, Map<String, Object> raw) {
        this.format = format;
        this.jarName = jarName;
        this.jarIndex = jarIndex;
        this.raw = raw;
    }

    public ModFormat format() {
        return format;
    }

    public String jarName() {
        return jarName;
    }

    public int jarIndex() {
        return jarIndex;
    }

    public long jarBytes() {
        return jarBytes;
    }

    public void jarBytes(long v) {
        this.jarBytes = v;
    }

    public Map<String, Object> raw() {
        return raw;
    }

    /** 展示名：没写 displayName 就退回 modId。 */
    public String display() {
        if (name != null && !name.isBlank()) {
            return name;
        }
        return id;
    }

    /**
     * 把同一个 jar 里另一端的元数据合并进来。
     *
     * <p>合并而不是并列，是因为它们在语义上是同一个模组：一个 modId、一份模组。
     * 但两端各自声明的入口点、依赖、mixin 配置都要保留（注册阶段会按格式分别使用）。
     */
    public void mergeFrom(ModMetadata other) {
        if (other.format() != format && !alternativeFormats.contains(other.format())) {
            alternativeFormats.add(other.format());
        }
        for (Map.Entry<String, List<String>> e : other.entrypoints.entrySet()) {
            List<String> list = entrypoints.computeIfAbsent(e.getKey(),
                    k -> new ArrayList<>(2));
            for (String c : e.getValue()) {
                if (!list.contains(c)) {
                    list.add(c);
                }
            }
        }
        for (String c : other.entrypointClasses) {
            if (!entrypointClasses.contains(c)) {
                entrypointClasses.add(c);
            }
        }
        for (String c : other.mixinConfigs) {
            if (!mixinConfigs.contains(c)) {
                mixinConfigs.add(c);
            }
        }
        for (String a : other.authors) {
            if (!authors.contains(a)) {
                authors.add(a);
            }
        }
        for (Dependency d : other.dependencies) {
            boolean dup = false;
            for (Dependency existing : dependencies) {
                if (existing.modId().equals(d.modId())
                        && String.valueOf(existing.versionRange())
                                .equals(String.valueOf(d.versionRange()))) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                dependencies.add(d);
            }
        }
        if (description == null || description.isBlank()) {
            description = other.description;
        }
        if (license == null || license.isBlank()) {
            license = other.license;
        }
        if (name == null || name.isBlank()) {
            name = other.name;
        }
    }

    /** 界面用的一行摘要。 */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(display()).append("  ").append(version).append("  [").append(format.displayName()).append(']');
        if (!authors.isEmpty()) {
            sb.append("  作者：").append(String.join("、", authors));
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return summary();
    }
}
