package com.fusionml.loader;

import com.fusionml.bytecode.HookInjector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 类字节码变换管线。
 *
 * <p>用"类名 → 规则"的哈希表做 O(1) 命中判断：绝大多数类在这里就被放过
 * （不做任何解析、不做任何拷贝），只有真正命中的类才构建规则并改写。
 * 于是"装了 200 个模组"时的变换开销与<b>命中数</b>成正比，而不是与类总数成正比。
 */
public final class TransformPipeline {

    /** 变换统计。 */
    public static final class Stats {
        public long inspected;
        public long transformed;
        public long injectedMethods;
        public final List<String> notes = new ArrayList<>(4);

        @Override
        public String toString() {
            return "检查 " + inspected + " 个类，改写 " + transformed + " 个，注入 "
                    + injectedMethods + " 个方法";
        }
    }

    private final Map<String, List<HookInjector.Hook>> hooksByClass = new HashMap<>(64);
    /**
     * 需要"解除原版密封"的类（内部名）。
     *
     * <p>这是跨端加载的必要修补，起因见 {@code com.fusionml.crossload.SealedTargets}：
     * 另一端的平台类常常是被对方平台通过"给原版打补丁加 permits"才允许存在的实现者。
     * 名单由前置分析按"本机实际装了哪些跨端包"算出来，没装跨端包时这里是空的。
     */
    private final java.util.Set<String> sealedTargets =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Stats stats = new Stats();
    private final List<String> registrationNotes = new ArrayList<>(4);
    private final java.util.Set<String> reported = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 登记一条注入规则。 */
    public void addHook(HookInjector.Hook hook) {
        String key = hook.targetClass();
        if (key == null) {
            return;
        }
        hooksByClass.computeIfAbsent(key, k -> new ArrayList<>(2)).add(hook);
    }

    /** 登记一条说明（例如"某模组的某个注入点暂不支持"），供最终报告展示。 */
    public void addNote(String note) {
        registrationNotes.add(note);
    }

    /** 登记一个需要解除密封的原版类（内部名）。 */
    public void addSealedTarget(String internalName) {
        sealedTargets.add(internalName);
    }

    public java.util.Set<String> sealedTargets() {
        return sealedTargets;
    }

    public boolean hasHooksFor(String internalName) {
        return hooksByClass.containsKey(internalName) || sealedTargets.contains(internalName);
    }

    public int hookRuleCount() {
        int n = 0;
        for (List<HookInjector.Hook> l : hooksByClass.values()) {
            n += l.size();
        }
        return n;
    }

    /** 所有被注入的目标类（内部名）。用于加载后做一次"注入自检"。 */
    public List<String> hookTargets() {
        return new ArrayList<>(hooksByClass.keySet());
    }

    public Stats stats() {
        return stats;
    }

    public List<String> notes() {
        List<String> all = new ArrayList<>(registrationNotes.size() + stats.notes.size());
        all.addAll(registrationNotes);
        all.addAll(stats.notes);
        return all;
    }

    /**
     * 对单个类应用管线。
     *
     * @return 变换后的字节；未命中时<b>返回原数组本身</b>（引用相等），调用方据此可知没有产生拷贝
     */
    public byte[] apply(String internalName, byte[] bytes) {
        stats.inspected++;
        byte[] current = bytes;
        if (sealedTargets.contains(internalName)) {
            byte[] stripped = com.fusionml.crossload.SealedTargets.strip(current);
            if (stripped != null) {
                current = stripped;
                stats.transformed++;
                if (reported.add(internalName)) {
                    System.out.println("[FusionML] 已解除密封 " + internalName.replace('/', '.')
                            + "（跨端平台的实现类需要它，原版补丁在宿主上不存在）");
                }
            }
        }
        List<HookInjector.Hook> hooks = hooksByClass.get(internalName);
        if (hooks == null || hooks.isEmpty()) {
            return current;
        }
        HookInjector.Result r = HookInjector.inject(current, hooks);
        if (r.changed()) {
            stats.transformed++;
            stats.injectedMethods += r.injectedMethods();
            if (reported.add(internalName)) {
                // 一次性日志：证明"改写确实落到了这个类上"，而不是只匹配到规则。
                System.out.println("[FusionML] 已改写 " + internalName.replace('/', '.')
                        + "：注入 " + r.injectedMethods() + " 个方法，"
                        + r.bytes().length + " 字节");
            }
        } else if (reported.add(internalName)) {
            System.out.println("[FusionML] " + internalName.replace('/', '.')
                    + " 命中规则但未改写（无匹配方法）。"
                    + (r.notes().isEmpty() ? "" : "原因：" + r.notes()));
        }
        for (String note : r.notes()) {
            if (stats.notes.size() < 64) {
                stats.notes.add(note);
            }
        }
        return r.bytes();
    }
}
