package com.fusionml.loader;

import com.fusionml.bytecode.ClassScanner;
import com.fusionml.meta.ModFormat;
import com.fusionml.meta.ModMetadata;
import com.fusionml.runtime.CpuTopology;
import com.fusionml.runtime.FusionPool;
import com.fusionml.util.FastZip;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全量类索引。
 *
 * <p>核心设计：把所有 jar 的所有 {@code .class} 条目<b>拉平成一张一维任务表</b>再并行处理。
 * 为什么不按"一个 jar 一个任务"：模组包体积分布极不均（有 300MB 的大包，也有 20KB 的小包），
 * 按 jar 分配会让大包变成长尾；按条目分配能让每个核都均匀吃满。
 * 这依赖 {@link FastZip} 的无锁定位读 —— 同一个 jar 可以被多个线程同时解压不同条目，
 * 而这正是 {@code java.util.zip.ZipFile} 做不到的。
 *
 * <p>第二重优化：只有确实会声明 {@code @Mod} 的端（Forge / NeoForge）才需要扫注解，
 * 纯 Fabric 包直接走"只取类名"的快路径，省掉整个类结构的解析。
 */
public final class ModClassIndex {

    public record Loc(int jarIndex, String entryName, long size) {
    }

    public record Result(Map<String, Loc> byName, Map<String, List<Loc>> modClasses,
                         Map<Integer, Integer> maxClassVersionByJar, long totalClasses,
                         List<String> warnings) {
    }

    private ModClassIndex() {
    }

    public static Result build(List<FastZip> jars, List<ModMetadata> metadata, FusionPool pool,
                               CpuTopology.Info cpu) {
        // 哪些 jar 需要全量注解扫描：含 Forge / NeoForge 元数据的那些。
        Set<Integer> needAnnotations = ConcurrentHashMap.newKeySet();
        for (ModMetadata m : metadata) {
            if (m.format() != ModFormat.FABRIC) {
                needAnnotations.add(m.jarIndex());
            }
        }

        List<FastZip.Entry> flatEntries = new ArrayList<>(4096);
        List<Integer> flatJar = new ArrayList<>(4096);
        for (int j = 0; j < jars.size(); j++) {
            for (FastZip.Entry e : jars.get(j).entries()) {
                if (e.isClass()) {
                    flatEntries.add(e);
                    flatJar.add(j);
                }
            }
        }
        int total = flatEntries.size();

        // 2) 并行扫描：用平行数组承载结果，不为每个类创建包装对象。
        String[] names = new String[total];
        int[] jarOf = new int[total];
        Map<String, Loc> byName = new ConcurrentHashMap<>(Math.max(256, total * 2));
        Map<String, List<Loc>> modClasses = new ConcurrentHashMap<>();
        Map<Integer, Integer> maxVersionByJar = new ConcurrentHashMap<>();
        List<String> warnings = Collections.synchronizedList(new ArrayList<>());
        int[] duplicateClasses = {0};

        int chunk = cpu.cacheAwareChunk(4096, total, pool.workers());
        pool.parallelFor(0, total, chunk, i -> {
            FastZip.Entry entry = flatEntries.get(i);
            int j = flatJar.get(i);
            FastZip zip = jars.get(j);
            jarOf[i] = j;
            try {
                byte[] bytes = zip.read(entry);
                if (needAnnotations.contains(j)) {
                    ClassScanner.Info info = ClassScanner.scan(bytes);
                    if (info == null) {
                        warnings.add(entry.name() + " 不是有效的类文件（" + zip.path().getFileName() + "）");
                        return;
                    }
                    names[i] = info.name();
                    maxVersionByJar.merge(j, info.major(), Math::max);
                    for (ModFormat f : List.of(ModFormat.FORGE, ModFormat.NEOFORGE)) {
                        if (info.hasAnnotation(f.modAnnotationDesc())) {
                            modClasses.computeIfAbsent(f.name(),
                                            k -> Collections.synchronizedList(new ArrayList<>()))
                                    .add(new Loc(j, entry.name(), entry.size()));
                        }
                    }
                } else {
                    names[i] = ClassScanner.scanName(bytes);
                    if (names[i] != null && bytes.length >= 8) {
                        int major = ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
                        maxVersionByJar.merge(j, major, Math::max);
                    }
                }
            } catch (IOException | RuntimeException e) {
                warnings.add("读取 " + entry.name() + " 失败：" + e.getMessage());
            }
        });

        for (int i = 0; i < total; i++) {
            String n = names[i];
            if (n == null) {
                continue;
            }
            FastZip.Entry entry = flatEntries.get(i);
            Loc loc = new Loc(jarOf[i], entry.name(), entry.size());
            Loc prev = byName.putIfAbsent(n, loc);
            if (prev != null) {

                // 保留先注册的，并记录提示，避免"随机生效一个"的黑盒行为。
                duplicateClasses[0]++;
                // 只留前若干条明细：装了 Fabric API 的实例里，重复类动辄上百个

                if (warnings.size() < 8) {
                    warnings.add("类 " + n.replace('/', '.') + " 被多个模组重复提供，已采用 "
                            + jars.get(prev.jarIndex()).path().getFileName() + " 中的版本");
                }
            }
        }
        if (duplicateClasses[0] > 8) {
            warnings.add("另有 " + (duplicateClasses[0] - 8)
                    + " 个类被多个模组重复提供（已按先加载者优先处理，这是打包依赖导致的常见现象）");
        }
        return new Result(byName, modClasses, maxVersionByJar, total, warnings);
    }

    public static String internal(String className) {
        return className.replace('.', '/');
    }

    public static String dotted(String internalName) {
        return internalName.replace('/', '.');
    }
}
