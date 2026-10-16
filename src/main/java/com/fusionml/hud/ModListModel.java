package com.fusionml.hud;

import com.fusionml.meta.Dependency;
import com.fusionml.meta.ModFormat;
import com.fusionml.meta.ModMetadata;
import com.fusionml.resolve.ModGraph;
import com.fusionml.runtime.FusionRuntime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 模组列表的数据模型。
 *
 * <p>界面、命令行、日志报告三处共用同一个模型 —— "界面上显示的"和"日志里记的"永远一致。
 * 否则玩家遇到问题时两边对不上，排查成本会翻倍。
 */
public final class ModListModel {

    /** 列表里的一个模组。 */
    public static final class Entry {
        public final ModMetadata meta;
        /** 加载层次，从 1 开始；同一层内并行加载。 */
        public final int tier;
        public final List<String> issues = new ArrayList<>(1);
        public boolean fatal;
        public int dependencyCount;
        public int dependentCount;
        /** 该模组是否携带原生注入配置。 */
        public boolean hasInjections;

        Entry(ModMetadata meta, int tier) {
            this.meta = meta;
            this.tier = tier;
        }

        public String name() {
            return meta.display();
        }

        public String id() {
            return meta.id;
        }

        public String version() {
            return meta.version;
        }

        public ModFormat format() {
            return meta.format();
        }

        /** 一行状态摘要，显示在列表右侧。 */
        public String status() {
            if (fatal) {
                return "有错误";
            }
            if (!issues.isEmpty()) {
                return "注意";
            }
            return "正常";
        }

        @Override
        public String toString() {
            return name() + " " + version() + " [" + format().displayName() + "] 第" + tier + "层 "
                    + status();
        }
    }

    /** 汇总统计。 */
    public record Stats(int total, Map<ModFormat, Integer> byFormat, int problemMods,
                        int totalDependencies, int totalClasses, int tiers, long jarBytes) {
        /** 某一端有多少个模组。菜单据此决定"要不要显示这个分组"。 */
        public int count(ModFormat f) {
            return byFormat.getOrDefault(f, 0);
        }

        public String formatLine() {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<ModFormat, Integer> e : byFormat.entrySet()) {
                if (sb.length() > 0) {
                    sb.append(" · ");
                }
                sb.append(e.getKey().displayName()).append(' ').append(e.getValue());
            }
            return sb.toString();
        }
    }

    /** 排序方式。 */
    public enum Sort {
        /** 有问题的排最前，然后按名称。 */
        PROBLEM_FIRST(Comparator.comparing((Entry e) -> !e.fatal).thenComparing(Entry::name)),
        /** 按加载层次，再按名称。 */
        TIER(Comparator.comparingInt((Entry e) -> e.tier).thenComparing(Entry::name)),
        NAME(Comparator.comparing(Entry::name)),
        /** 被依赖最多的排前面（这些通常就是核心前置）。 */
        DEPENDENTS(Comparator.comparingInt((Entry e) -> -e.dependentCount).thenComparing(Entry::name));

        final Comparator<Entry> comparator;

        Sort(Comparator<Entry> c) {
            this.comparator = c;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Stats stats;

    private ModListModel(List<Entry> entries, Stats stats) {
        this.entries.addAll(entries);
        this.stats = stats;
    }

    /** 从一次加载会话构建列表。 */
    public static ModListModel from(FusionRuntime.Session session) {
        Map<String, Integer> tierOf = new LinkedHashMap<>();
        List<List<ModMetadata>> tiers = session.plan.tiers;
        for (int i = 0; i < tiers.size(); i++) {
            for (ModMetadata m : tiers.get(i)) {
                tierOf.put(m.id, i + 1);
            }
        }
        Map<String, Entry> byId = new LinkedHashMap<>();
        List<Entry> list = new ArrayList<>(session.mods.size());
        for (ModMetadata m : session.mods) {
            Entry e = new Entry(m, tierOf.getOrDefault(m.id, 1));
            byId.put(m.id, e);
            list.add(e);
        }
        for (Entry e : list) {
            for (Dependency d : e.meta.dependencies) {
                Entry provider = byId.get(d.modId());
                if (provider != null && provider != e) {
                    e.dependencyCount++;
                    provider.dependentCount++;
                }
            }
        }
        for (ModGraph.Problem p : session.plan.problems) {
            for (Entry e : list) {
                if (p.modId().equals(e.id()) || p.modId().contains(e.id())) {
                    e.issues.add(p.message());
                    e.fatal |= p.fatal();
                }
            }
        }
        for (Entry e : list) {
            e.hasInjections = !e.meta.mixinConfigs.isEmpty();
        }
        Map<ModFormat, Integer> byFormat = new EnumMap<>(ModFormat.class);
        int problems = 0;
        int deps = 0;
        for (Entry e : list) {
            byFormat.merge(e.format(), 1, Integer::sum);
            if (e.fatal) {
                problems++;
            }
            deps += e.dependencyCount;
        }
        Stats stats = new Stats(list.size(), byFormat, problems, deps,
                (int) session.index.totalClasses(), session.plan.depth(), session.totalJarBytes);
        return new ModListModel(list, stats);
    }

    public Stats stats() {
        return stats;
    }

    public List<Entry> all() {
        return entries;
    }

    /**
     * 过滤 + 排序。
     *
     * @param query    关键词，匹配名称 / modId / 版本，不区分大小写
     * @param platform 平台过滤，null 表示全部
     * @param sort     排序方式
     */
    public List<Entry> query(String query, ModFormat platform, Sort sort) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Entry> out = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            if (platform != null && e.format() != platform) {
                continue;
            }
            if (!q.isEmpty()) {
                String hay = (e.name() + " " + e.id() + " " + e.version()).toLowerCase(Locale.ROOT);
                if (!hay.contains(q)) {
                    continue;
                }
            }
            out.add(e);
        }
        out.sort(sort.comparator);
        return out;
    }

    /** 生成一段纯文本列表，供命令行使用（与界面同样的过滤逻辑）。 */
    public String toText(String query, ModFormat platform, Sort sort, int limit) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append(String.format(Locale.ROOT, "%-28s %-12s %-9s %-6s %-6s %s%n",
                "名称", "版本", "平台", "层次", "状态", "modId"));
        sb.append("-".repeat(88)).append(System.lineSeparator());
        List<Entry> list = query(query, platform, sort);
        int shown = 0;
        for (Entry e : list) {
            if (shown++ >= limit) {
                sb.append("...（共 ").append(list.size()).append(" 个，已显示前 ")
                        .append(limit).append(" 个）").append(System.lineSeparator());
                break;
            }
            sb.append(String.format(Locale.ROOT, "%-28s %-12s %-9s %-6s %-6s %s%n",
                    trim(e.name(), 26), trim(e.version(), 12), e.format().displayName(),
                    e.tier, e.status(), e.id()));
            for (String issue : e.issues) {
                sb.append("       └ ").append(issue).append(System.lineSeparator());
            }
        }
        return sb.toString();
    }

    /**
     * 按显示宽度截断字符串：中日韩全角字符算两格。
     * 这是"对齐"能真正对齐的前提 —— 直接用 length() 会让中英混排的列表歪掉。
     */
    static String trim(String s, int width) {
        if (s == null) {
            return "";
        }
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += charWidth(s.charAt(i));
            if (w > width) {
                return s.substring(0, i) + "…";
            }
        }
        return s;
    }

    /** 全角判定：把常见 CJK 区段按两格宽度处理。 */
    static int charWidth(char c) {
        if (c == 0) {
            return 0;
        }
        if ((c >= 0x1100 && c <= 0x115F) || (c >= 0x2E80 && c <= 0xA4CF)
                || (c >= 0xAC00 && c <= 0xD7A3) || (c >= 0xF900 && c <= 0xFAFF)
                || (c >= 0xFE30 && c <= 0xFE6F) || (c >= 0xFF00 && c <= 0xFF60)
                || (c >= 0xFFE0 && c <= 0xFFE6)) {
            return 2;
        }
        return 1;
    }

    /** 计算显示宽度（列对齐用）。 */
    public static int displayWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += charWidth(s.charAt(i));
        }
        return w;
    }
}
