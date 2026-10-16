package com.fusionml.loader;

import com.fusionml.util.FastZip;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 自有游戏类加载器：游戏类与模组类的唯一定义者。
//
// 为什么必须由我们自己拥有它（而不是沿用平台自带的那一个）：
// "一个整合包同时装三端"要求**同一份补丁基底**只存在一份，而这只有在
// "定义游戏类的人只有一个"时才成立。平台各自的类加载器都要求自己做这件事，
// 所以共存的第一步就是把定义权收到我们手里，平台运行时退化为"消费者"。
//
// 三个关键设计：
//   1) 按 DelegationPolicy 决定委派还是自留 —— 加载器侧单份、游戏与模组自留；
//   2) 类的字节来自我们自己的 FastZip 索引（O(1) 定位，不遍历目录、不依赖 URLClassLoader 的实现），
//      并且在这里**唯一**地套用变换管线 —— 未来自有 Mixin 服务也挂在这一个点上；
//   3) 并行可重入：注册为 parallel-capable，避免类加载器级全局锁把启动串行化。
public final class FusionGameClassLoader extends ClassLoader implements AutoCloseable {

    static {
        registerAsParallelCapable();
    }

    /** 一个类在哪个 jar 的哪个条目里（public 是因为它会出现在本加载器的公开签名里）。 */
    public record Loc(int jarIndex, String entryName) {
    }

    private final List<FastZip> jars;
    private final Map<String, Loc> index;
    private final com.fusionml.loader.TransformPipeline pipeline;
    private final List<String> missed = new ArrayList<>(4);

    public FusionGameClassLoader(ClassLoader parent, List<FastZip> jars,
                                 Map<String, Loc> index, com.fusionml.loader.TransformPipeline pipeline) {
        super(parent);
        this.jars = jars;
        this.index = index;
        this.pipeline = pipeline;
    }

    /** 由启动计划给出的条目列表建立索引（类名 → 位置）。 */
    public static Map<String, Loc> buildIndex(List<FastZip> jars, List<String> notes) {
        Map<String, Loc> map = new HashMap<>(8192);
        for (int i = 0; i < jars.size(); i++) {
            FastZip zip = jars.get(i);
            for (FastZip.Entry e : zip.entries()) {
                if (!e.isClass()) {
                    continue;
                }
                // 多版本 jar 会在 META-INF/versions/<N>/ 下放同一类的不同版本实现。
                // 它们不是"另一个类"，而是同一个类在特定 JVM 版本下的覆盖层，
                // 当成普通类索引会得到一个名字与内容不匹配的条目
                // （实测报错：wrong name: org/spongepowered/configurate/...）。
                if (e.name().startsWith("META-INF/")) {
                    continue;
                }
                String internal = e.name().substring(0, e.name().length() - ".class".length());
                map.putIfAbsent(internal, new Loc(i, e.name()));
            }
        }
        return map;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        Class<?> loaded = findLoadedClass(name);
        if (loaded != null) {
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
        // 加载器侧交给父加载器：全进程单份，避免同一个类出现两个定义。
        if (DelegationPolicy.parentFirst(name)) {
            try {
                return super.loadClass(name, resolve);
            } catch (ClassNotFoundException notThere) {
                // 父加载器没有 → 退回我们自己定义。
                //
                // 这条回退不是保险丝，而是必需的：委派表按"包语义"划分，
                // 但同一个包在不同环境里可能由父加载器提供、也可能没有
                // （例如 com.mojang.blaze3d.* 在真实游戏 JVM 里由类路径上的库提供，
                // 而在只挂了加载器自身的精简环境里就没有）。写死"必须由父提供"
                // 会在后一种环境里直接 NoClassDefFoundError。
                // 先父后己既保证"父有就是单份"，又保证"父没有也能跑"。
            }
        }
        synchronized (getClassLoadingLock(name)) {
            loaded = findLoadedClass(name);
            if (loaded != null) {
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
            Class<?> defined = findClassQuietly(name);
            if (defined == null) {
                // 我们这儿没有，再交给父加载器兜底（平台自己的类可能就是这种情况）。
                return super.loadClass(name, resolve);
            }
            if (resolve) {
                resolveClass(defined);
            }
            return defined;
        }
    }

    private Class<?> findClassQuietly(String name) {
        String internal = name.replace('.', '/');
        Loc loc = index.get(internal);
        if (loc == null) {
            if (missed.size() < 8) {
                missed.add(name);
            }
            return null;
        }
        try {
            byte[] bytes = jars.get(loc.jarIndex()).read(
                    jars.get(loc.jarIndex()).entry(loc.entryName()));
            // 变换点唯一：所有游戏与模组类都在这里、且只在这里被改写。
            if (pipeline != null) {
                bytes = pipeline.apply(internal, bytes);
            }
            return defineClass(name, bytes, 0, bytes.length);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** 未被索引到的类名（排查"为什么这个类没找到"时很有用）。 */
    public List<String> missedClasses() {
        return missed;
    }

    public int indexedClassCount() {
        return index.size();
    }

    public List<FastZip> jars() {
        return jars;
    }

    /** 不关闭底层 jar：它们由调用方（启动计划）持有并复用。 */
    @Override
    public void close() {
        // 故意为空
    }
}
