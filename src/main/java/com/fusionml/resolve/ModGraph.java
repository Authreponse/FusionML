package com.fusionml.resolve;

import com.fusionml.meta.Dependency;
import com.fusionml.meta.ModMetadata;
import com.fusionml.version.VersionRange;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模组依赖解析与分层拓扑排序。
 *
 * <p>产出 {@link Plan#tiers}：同一层内的模组之间没有任何依赖或被依赖关系，
 * 因此可以完全并行加载；层与层之间严格有序。
 * 这样"加载顺序正确"和"能并行"就不再矛盾 —— 这是加载器整体速度的结构性来源。
 *
 * <p>错误处理立场：不静默吞掉问题。所有问题（缺依赖、版本不匹配、重复 modId、循环依赖）
 * 都被逐条收集成可读文本，交给上层一次性展示给玩家，而不是抛一个只有开发能看懂的堆栈。
 */
public final class ModGraph {

    /** 一条问题记录。 */
    public record Problem(String modId, String message, boolean fatal) {
        @Override
        public String toString() {
            return (fatal ? "[错误] " : "[提示] ") + modId + "：" + message;
        }
    }

    /**
     * 把问题列表压成可读的若干行。
     *
     * <p>整合包里"同一个 id 冲突出现几十次"是常态，逐条打印会把真正要看的那几条淹掉。
     * 这里按 modId 归组：同一模组只展开前两条，其余折算成计数。
     */
    public static List<String> summarize(List<Problem> problems, int maxPerMod) {
        java.util.Map<String, List<Problem>> grouped = new LinkedHashMap<>();
        for (Problem p : problems) {
            grouped.computeIfAbsent(p.modId(), k -> new ArrayList<>(2)).add(p);
        }
        List<String> out = new ArrayList<>(grouped.size() * 2);
        for (Map.Entry<String, List<Problem>> e : grouped.entrySet()) {
            List<Problem> list = e.getValue();
            int shown = Math.min(list.size(), Math.max(1, maxPerMod));
            for (int i = 0; i < shown; i++) {
                out.add(list.get(i).toString());
            }
            if (list.size() > shown) {
                out.add("         （同一模组另有 " + (list.size() - shown) + " 条同类问题）");
            }
        }
        return out;
    }

    /** 解析结果。 */
    public static final class Plan {
        public final List<ModMetadata> all;
        public final Map<String, ModMetadata> byId;
        public final List<List<ModMetadata>> tiers;
        public final List<Problem> problems;

        Plan(List<ModMetadata> all, Map<String, ModMetadata> byId,
             List<List<ModMetadata>> tiers, List<Problem> problems) {
            this.all = all;
            this.byId = byId;
            this.tiers = tiers;
            this.problems = problems;
        }

        public boolean fatal() {
            for (Problem p : problems) {
                if (p.fatal()) {
                    return true;
                }
            }
            return false;
        }

        public int fatalCount() {
            int n = 0;
            for (Problem p : problems) {
                if (p.fatal()) {
                    n++;
                }
            }
            return n;
        }

        /** 最大层级 = 理论最短加载轮数。 */
        public int depth() {
            return tiers.size();
        }
    }

    private ModGraph() {
    }

    /**
     * @param mods     已读取出的全部模组元数据
     * @param client   当前是否客户端侧（用于 side 判定）
     * @param provided 由加载器/游戏自身提供的依赖（例如 minecraft、forge、fabricloader）及其版本
     */
    public static Plan compute(List<ModMetadata> mods, boolean client, Map<String, String> provided) {
        List<Problem> problems = new ArrayList<>();
        Map<String, ModMetadata> byId = new LinkedHashMap<>(Math.max(16, mods.size() * 2));
        List<ModMetadata> active = new ArrayList<>(mods.size());

        // 1) 去重 + 侧过滤
        for (ModMetadata m : mods) {
            if (m.id == null || m.id.isBlank()) {
                problems.add(new Problem(m.jarName(), "元数据缺少 modId，已跳过", false));
                continue;
            }
            ModMetadata exist = byId.get(m.id);
            if (exist != null) {
                if (exist.jarIndex() == m.jarIndex()) {
                    // 同一个 jar 的另一端元数据（多端同包）：这是同一个模组的两套声明，
                    // 合并进来即可，绝不能报成"两个模组抢一个 id"。
                    exist.mergeFrom(m);
                    continue;
                }
                // 独立安装的模组 vs 某个模组内嵌的副本：优先用独立安装的那一份。
                // 否则一个把整份 Fabric API 打包进去的模组，会把玩家真正安装的 Fabric API 顶掉，
                // 之后所有依赖它的模组都会莫名其妙出问题。
                if (exist.nested && !m.nested) {
                    int idx = active.indexOf(exist);
                    if (idx >= 0) {
                        active.set(idx, m);
                        byId.put(m.id, m);
                        problems.add(new Problem(m.id, "已用独立安装的 " + m.jarName()
                                + " 取代嵌套包里的同名副本（" + exist.jarName() + "）", false));
                        continue;
                    }
                }
                if (!exist.nested && m.nested) {
                    problems.add(new Problem(m.id, "已忽略嵌套包 " + m.jarName()
                            + " 里的同名副本，沿用独立安装的 " + exist.jarName(), false));
                    continue;
                }
                problems.add(new Problem(m.id, "modId 冲突：已由 " + exist.jarName()
                        + " 提供，又与 " + m.jarName() + " 撞车"
                        + "（同 id 多包会导致注册冲突，已保留先加载的那个）", true));
                continue;
            }
            byId.put(m.id, m);
            active.add(m);
        }

        Map<String, String> providers = new HashMap<>(provided);
        for (ModMetadata m : active) {
            providers.put(m.id, m.version);
            // 本模组声明"顶替提供"的 id 也算已提供。
            // Fabric API 通过 provides 声明了几十个子模块 id，不认这个字段就会把
            // 依赖它们的模组全部误报成"缺少必需依赖"。
            for (String alias : m.provides) {
                providers.putIfAbsent(alias, m.version);
            }
        }

        // 2) 依赖校验
        int n = active.size();
        Map<String, Integer> indexOf = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) {
            indexOf.put(active.get(i).id, i);
        }
        List<Set<Integer>> edges = new ArrayList<>(n);
        int[] inDegree = new int[n];
        for (int i = 0; i < n; i++) {
            edges.add(new LinkedHashSet<>());
        }

        for (int i = 0; i < n; i++) {
            ModMetadata m = active.get(i);
            for (Dependency d : m.dependencies) {
                if (!d.side().appliesTo(client)) {
                    continue;
                }
                String depId = d.modId();
                String have = providers.get(depId);
                boolean present = have != null;
                if (!present) {
                    if (d.mandatory()) {
                        problems.add(new Problem(m.id, "缺少必需依赖 " + depId
                                + (d.versionRange() == null || d.versionRange().isBlank()
                                ? "" : "（要求 " + d.versionRange() + "）"), true));
                    } else if ("不兼容".equals(d.reason()) || "冲突".equals(d.reason())) {
                        // breaks/conflicts 写的是"如果有就不行"，present 才处理
                    } else {
                        problems.add(new Problem(m.id, "可选依赖 " + depId + " 未安装", false));
                    }
                    continue;
                }
                if ("不兼容".equals(d.reason()) || "冲突".equals(d.reason())) {
                    problems.add(new Problem(m.id, "与已安装的 " + depId + " " + have
                            + " 声明为" + d.reason() + "，两者不能共存", true));
                    continue;
                }
                if (d.versionRange() != null && !d.versionRange().isBlank()) {
                    VersionRange range = VersionRange.of(d.versionRange());
                    if (!range.accepts(have)) {
                        problems.add(new Problem(m.id, "依赖 " + depId + " 的版本不满足：需要 "
                                + d.versionRange() + "，实际安装 " + have, d.mandatory()));
                        if (range.degraded()) {
                            problems.add(new Problem(m.id, "依赖 " + depId + " 的版本区间写法无法完全解析："
                                    + d.versionRange() + "（已按宽松匹配处理）", false));
                        }
                    }
                }
                Integer target = indexOf.get(depId);
                if (target != null && target != i) {
                    // 被依赖者先加载
                    if (edges.get(target).add(i)) {
                        inDegree[i]++;
                    }
                }
                if (target != null && target == i) {
                    problems.add(new Problem(m.id, "依赖了自己", true));
                }
            }
            // 加载顺序约束
            for (Dependency d : m.dependencies) {
                if (d.ordering() == Dependency.Ordering.NONE) {
                    continue;
                }
                Integer other = indexOf.get(d.modId());
                if (other == null) {
                    continue;
                }
                if (d.ordering() == Dependency.Ordering.BEFORE) {
                    if (edges.get(i).add(other)) {
                        inDegree[other]++;
                    }
                } else {
                    if (edges.get(other).add(i)) {
                        inDegree[i]++;
                    }
                }
            }
        }

        // 3) Kahn 分层：同层并行、层间有序
        List<List<ModMetadata>> tiers = new ArrayList<>();
        int[] deg = inDegree.clone();
        Deque<Integer> ready = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            if (deg[i] == 0) {
                ready.add(i);
            }
        }
        int placed = 0;
        while (!ready.isEmpty()) {
            List<Integer> layer = new ArrayList<>(ready.size());
            int count = ready.size();
            for (int k = 0; k < count; k++) {
                layer.add(ready.poll());
            }
            List<ModMetadata> tier = new ArrayList<>(layer.size());
            for (int idx : layer) {
                tier.add(active.get(idx));
                placed++;
            }
            tiers.add(tier);
            for (int idx : layer) {
                for (int next : edges.get(idx)) {
                    if (--deg[next] == 0) {
                        ready.add(next);
                    }
                }
            }
        }

        // 4) 环检测：还剩下的就是环里的节点
        if (placed < n) {
            List<ModMetadata> cyclic = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (deg[i] > 0) {
                    cyclic.add(active.get(i));
                }
            }
            problems.add(new Problem(String.join("、", cyclic.stream().map(x -> x.id).toList()),
                    "检测到循环依赖，涉及 " + cyclic.size() + " 个模组："
                            + findCycle(active, edges, deg)
                            + "。这些模组已放到最后一层强制加载，但顺序可能不符合预期", true));
            tiers.add(cyclic);
        }

        return new Plan(active, byId, tiers, problems);
    }

    /** 从环里挑出一条真实路径，方便玩家一眼看出问题出在哪。 */
    private static String findCycle(List<ModMetadata> active, List<Set<Integer>> edges, int[] deg) {
        for (int start = 0; start < active.size(); start++) {
            if (deg[start] <= 0) {
                continue;
            }
            List<Integer> path = new ArrayList<>();
            Set<Integer> seen = new LinkedHashSet<>();
            int cur = start;
            while (cur >= 0 && seen.add(cur)) {
                path.add(cur);
                int next = -1;
                for (int cand : edges.get(cur)) {
                    if (deg[cand] > 0) {
                        next = cand;
                        break;
                    }
                }
                cur = next;
            }
            if (cur >= 0 && path.contains(cur)) {
                StringBuilder sb = new StringBuilder();
                boolean started = false;
                for (int idx : path) {
                    if (idx == cur) {
                        started = true;
                    }
                    if (started) {
                        sb.append(active.get(idx).id).append(" → ");
                    }
                }
                sb.append(active.get(cur).id);
                return sb.toString();
            }
        }
        return "（已定位到环，但未能抽出路径）";
    }
}
