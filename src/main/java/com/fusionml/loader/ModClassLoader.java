package com.fusionml.loader;

import com.fusionml.util.FastZip;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模组类加载器。
 *
 * <ul>
 *   <li><b>O(1) 定位</b>：类名直接查索引拿到 (jar, 条目)，不在加载时遍历 jar，
 *       也不需要把整个 mods 目录打进 URL 数组让 JVM 逐个试探。</li>
 *   <li><b>并行可重入</b>：注册为 parallel-capable，多个线程可以同时加载不同的类，
 *       避免类加载器级的全局锁串行化（这是大型整合包启动时最隐蔽的瓶颈之一）。</li>
 *   <li><b>变换内联</b>：类字节从解压到改写再到 defineClass 全在一条路径上完成，
 *       不落盘、不生成中间文件。</li>
 *   <li><b>资源索引按需构建</b>：只加载类、不需要资源的场景（比如纯逻辑模组、
 *       以及我们的基准测试）完全不必付出建资源索引的内存代价。</li>
 * </ul>
 */
public final class ModClassLoader extends ClassLoader {

    static {
        registerAsParallelCapable();
    }

    /** 运行时统计，用于报告"类加载到底花了多少时间、做了多少次变换"。 */
    public static final class Stats {
        public final AtomicLong classesLoaded = new AtomicLong();
        public final AtomicLong bytesRead = new AtomicLong();
        public final AtomicLong transformed = new AtomicLong();
        public final AtomicLong resources = new AtomicLong();
    }

    private final List<FastZip> jars;
    private final Map<String, ModClassIndex.Loc> classIndex;
    private final TransformPipeline pipeline;
    private final Stats stats = new Stats();
    private volatile Map<String, Integer> resourceIndex;

    public ModClassLoader(ClassLoader parent, List<FastZip> jars,
                          Map<String, ModClassIndex.Loc> classIndex, TransformPipeline pipeline) {
        super(parent);
        this.jars = jars;
        this.classIndex = classIndex;
        this.pipeline = pipeline;
    }

    public Stats stats() {
        return stats;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        String internal = name.replace('.', '/');
        ModClassIndex.Loc loc = classIndex.get(internal);
        if (loc == null) {
            throw new ClassNotFoundException(name);
        }
        byte[] bytes;
        try {
            bytes = readClass(loc);
        } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
        }
        byte[] transformed = pipeline.apply(internal, bytes);
        if (transformed != bytes) {
            stats.transformed.incrementAndGet();
        }
        stats.classesLoaded.incrementAndGet();
        stats.bytesRead.addAndGet(bytes.length);
        return defineClass(name, transformed, 0, transformed.length);
    }

    private byte[] readClass(ModClassIndex.Loc loc) throws IOException {
        FastZip zip = jars.get(loc.jarIndex());
        FastZip.Entry entry = zip.entry(loc.entryName());
        if (entry == null) {
            throw new IOException("jar 内已找不到条目 " + loc.entryName()
                    + "（jar 在索引建立后被修改过？）");
        }
        return zip.read(entry);
    }

    @Override
    protected URL findResource(String name) {

        return null;
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        byte[] data = readResource(name);
        if (data != null) {
            return new ByteArrayInputStream(data);
        }
        return super.getResourceAsStream(name);
    }

    public byte[] readResource(String name) {
        Map<String, Integer> idx = resourceIndex;
        if (idx == null) {
            synchronized (this) {
                if (resourceIndex == null) {
                    resourceIndex = buildResourceIndex();
                }
                idx = resourceIndex;
            }
        }
        Integer jarIndex = idx.get(name);
        if (jarIndex == null) {
            return null;
        }
        FastZip zip = jars.get(jarIndex);
        FastZip.Entry entry = zip.entry(name);
        if (entry == null) {
            return null;
        }
        try {
            stats.resources.incrementAndGet();
            return zip.read(entry);
        } catch (IOException e) {
            return null;
        }
    }

    public List<String> listResources(String prefix, int limit) {
        List<String> out = new ArrayList<>(Math.min(limit, 256));
        for (FastZip zip : jars) {
            for (FastZip.Entry e : zip.entries()) {
                if (!e.isDirectory() && e.name().startsWith(prefix)) {
                    out.add(e.name());
                    if (out.size() >= limit) {
                        return out;
                    }
                }
            }
        }
        return out;
    }

    private Map<String, Integer> buildResourceIndex() {
        Map<String, Integer> idx = new HashMap<>(1024);
        for (int j = 0; j < jars.size(); j++) {
            for (FastZip.Entry e : jars.get(j).entries()) {
                if (!e.isDirectory() && !e.isClass()) {
                    idx.putIfAbsent(e.name(), j);
                }
            }
        }
        return idx;
    }

    public void close() {
        for (FastZip zip : jars) {
            try {
                zip.close();
            } catch (IOException ignored) {
                // 关闭失败不影响退出，忽略。
            }
        }
    }

    public List<FastZip> jars() {
        return jars;
    }
}
