package com.fusionml.mixinengine;

import com.fusionml.util.FastZip;
import com.fusionml.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自研 Mixin 引擎的<b>地基</b>：扫描模组包，解析它们的 Mixin 配置。
 *
 * <h2>为什么要有这一层</h2>
 *
 * <p>FusionML 原来的定位是"寄生宿主平台"：平台（Fabric / NeoForge）自己有 Mixin 引擎，
 * 我们只把另一端的模组塞进同一次运行。要做到<b>不依赖任何平台也能加载模组</b>，
 * 就必须自己实现 Mixin 引擎。而引擎的第一件事是：<b>知道有哪些 Mixin、它们要注入谁</b>。
 *
 * <p>本类只做这件事 —— 扫描 + 解析，产出结构化的清单。<b>不执行注入</b>
 * （注入是后续阶段的事）。这样每一层都能单独验证。
 *
 * <h2>Mixin 配置的真实结构（实测自 sodium 的配置）</h2>
 * <pre>
 * {
 *   "package": "net.caffeinemc.mods.sodium.mixin",     ← 下面类名要拼这个前缀
 *   "required": true,
 *   "compatibilityLevel": "JAVA_17",
 *   "plugin": "net.caffeinemc.mods.sodium.mixin.SodiumMixinPlugin",   ← 模组自定义插件
 *   "injectors": { "defaultRequire": 1 },
 *   "overwrites": { "conformVisibility": true, "requireAnnotations": true },
 *   "mixinextras": { "minVersion": "0.5.2" },
 *   "client": [ "core.MinecraftMixin", ... ],          ← 只在客户端加载
 *   "mixins": [ ... ],                                 ← 两端都加载
 *   "server": [ ... ]                                  ← 只在服务端加载
 * }
 * </pre>
 *
 * <p><b>三组列表的语义必须区分</b>：{@code client} 里的 Mixin 在服务端上注入会找不到目标
 * （例如 {@code Minecraft} 类在服务端不存在），照搬会直接崩。反过来 {@code server} 同理。
 */
public final class MixinConfigScanner {

    /** 一个待注入的 Mixin 类。 */
    public record Entry(
            /** Mixin 类名（内部名，已拼上 config 里的 package 前缀）。 */
            String mixinClass,
            /** 它来自哪个模组包（诊断用）。 */
            String sourceJar,
            /** 配置文件在包里的路径。 */
            String configPath,
            /** 只在客户端加载？ */
            boolean clientOnly,
            /** 只在服务端加载？ */
            boolean serverOnly,
            /** 模组自定义插件类名；没有则为空。 */
            String plugin,
            /** 优先级（config 里的 priority，默认 1000；数字小的先应用）。 */
            int priority,
            /** 该 config 的 defaultRequire（注入失败是否致命）。 */
            int defaultRequire) {

        public boolean appliesTo(boolean client) {
            if (clientOnly) {
                return client;
            }
            if (serverOnly) {
                return !client;
            }
            return true;
        }
    }

    /** 扫描结果。 */
    public record Scan(List<Entry> entries, List<String> configs, List<String> notes) {
        public int size() {
            return entries.size();
        }
    }

    private MixinConfigScanner() {
    }

    /**
     * 扫描一批模组包，收集全部 Mixin 配置。
     *
     * <p><b>只返回"当前运行侧会用到"的条目</b>：{@code client} 列表里的 Mixin
     * 在服务端上注入会找不到目标（例如 {@code Minecraft} 类在服务端不存在），
     * 照搬会直接崩。所以这里按 {@code client} 参数过滤掉不属于本侧的。
     *
     * @param jars   模组包（可以是 mods 目录里的，也可以是嵌套包）
     * @param labels 与 jars 一一对应的可读名字（诊断用）。可为 null
     * @param client 当前是不是客户端
     */
    public static Scan scan(List<FastZip> jars, List<String> labels, boolean client) {
        List<Entry> all = new ArrayList<>();
        List<String> configs = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (int i = 0; i < jars.size(); i++) {
            FastZip z = jars.get(i);
            String src = labels != null && i < labels.size() && labels.get(i) != null
                    ? labels.get(i) : ("jar#" + i);
            for (FastZip.Entry e : z.entries()) {
                String name = e.name();
                if (e.isDirectory() || !isMixinConfig(name)) {
                    continue;
                }
                configs.add(name);
                try {
                    parseOne(z.read(e), src, name, all, notes);
                } catch (Throwable t) {
                    // 单个配置读不出来不能影响其它模组 —— 但也必须留下痕迹，
                    // 否则表现为"某个模组的 Mixin 静默不生效"，极难定位。
                    notes.add("解析失败 " + name + "（" + src + "）：" + t);
                }
            }
        }
        // 按运行侧过滤：client 专属的只有客户端要，server 专属的只有服务端要。
        List<Entry> use = new ArrayList<>(all.size());
        int dropped = 0;
        for (Entry en : all) {
            if (en.appliesTo(client)) {
                use.add(en);
            } else {
                dropped++;
            }
        }
        notes.add("扫描 " + jars.size() + " 个模组包，找到 " + configs.size()
                + " 个 Mixin 配置，共 " + all.size() + " 个待注入类；运行侧="
                + (client ? "客户端" : "服务端") + "，本侧适用 " + use.size()
                + " 个，剔除 " + dropped + " 个（另一侧专属）");
        return new Scan(use, configs, notes);
    }

    /** 旧签名（不带 labels）：诊断里会显示 jar 序号而不是文件名。 */
    public static Scan scan(List<FastZip> jars, boolean client) {
        return scan(jars, null, client);
    }

    /**
     * 是不是 Mixin 配置文件名。
     *
     * <p>判据刻意宽松：实测各模组叫法五花八门（{@code sodium-common.mixins.json}、
     * {@code mixins.iris.json}、{@code xxx.mixin.json}、{@code xxx.mixins.json}），
     * 只要是 {@code .json} 且名字里带 {@code mixin} 就先收进来，
     * <b>靠能不能解析出 {@code package} 字段来最终判定</b> —— 这比按文件名猜可靠得多。
     */
    private static boolean isMixinConfig(String name) {
        if (!name.endsWith(".json")) {
            return false;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("mixin") && !lower.contains("refmap");
    }

    @SuppressWarnings("unchecked")
    private static void parseOne(byte[] data, String src, String path,
                                 List<Entry> out, List<String> notes) {
        if (data == null || data.length == 0) {
            return;
        }
        Map<String, Object> root = Json.parseObject(data);
        Object pkgObj = root.get("package");
        String pkg = pkgObj == null ? "" : String.valueOf(pkgObj).trim();
        if (pkg.isEmpty()) {
            // 没有 package 字段 → 不是 Mixin 配置（可能是别的 json 恰好名叫 mixins.json）。
            return;
        }
        String prefix = pkg.replace('.', '/') + "/";
        String plugin = root.get("plugin") == null ? "" : String.valueOf(root.get("plugin")).trim();
        int priority = intOf(root.get("priority"), 1000);
        Map<String, Object> injectors = Json.obj(root.get("injectors"));
        int defaultRequire = intOf(injectors.get("defaultRequire"), 1);

        collect(Json.arr(root.get("mixins")), prefix, src, path, plugin, priority,
                defaultRequire, false, false, out);
        collect(Json.arr(root.get("client")), prefix, src, path, plugin, priority,
                defaultRequire, true, false, out);
        collect(Json.arr(root.get("server")), prefix, src, path, plugin, priority,
                defaultRequire, false, true, out);
        collect(Json.arr(root.get("common")), prefix, src, path, plugin, priority,
                defaultRequire, false, false, out);
    }

    private static void collect(List<Object> list, String prefix, String src, String path,
                                String plugin, int priority, int defaultRequire,
                                boolean clientOnly, boolean serverOnly, List<Entry> out) {
        for (Object o : list) {
            if (o == null) {
                continue;
            }
            String cls = String.valueOf(o).trim();
            if (cls.isEmpty()) {
                continue;
            }
            // 配置里写的是相对类名（如 "core.MinecraftMixin"），要拼上 package。
            String internal = cls.indexOf('/') >= 0
                    ? cls
                    : prefix + cls.replace('.', '/');
            // 只在客户端/服务端的标记：由调用方按运行侧过滤，这里如实记下来。
            out.add(new Entry(internal, src, path, clientOnly, serverOnly, plugin, priority,
                    defaultRequire));
        }
    }

    private static int intOf(Object o, int dflt) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o == null) {
            return dflt;
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }
}
