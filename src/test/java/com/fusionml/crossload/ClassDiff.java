package com.fusionml.crossload;

import com.fusionml.bytecode.ClassFile;
import com.fusionml.mc.McInstance;
import com.fusionml.util.FastZip;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 对照组件的诊断工具：把"对方平台补丁过的原版类"与"宿主上的原版类"逐个比一比成员表。
 *
 * <p>存在的理由很具体：换用补丁原版基座之后，宿主平台（Fabric）的某个模组会报
 * {@code Critical injection failure ... Scanned 0 target(s)} —— 也就是"它要注入的方法
 * 在这个类里找不到"。这句话只说明现象，不说明"补丁到底把什么改掉了"。
 * 这个工具直接把差异摆出来，并回答一个可判定的问题：
 * <b>补丁类是"只增不减"，还是真的动了原版已有的成员？</b>
 *
 * <p>用法（内部名，斜杠分隔）：
 * <pre>
 * java -cp build\classes;build\test-classes com.fusionml.crossload.ClassDiff \
 *      &lt;补丁 jar&gt; &lt;原版 jar&gt; [类名…]
 * </pre>
 * 不给类名时对全量做统计（慢一点，但能给出"该按什么规则筛"的依据）。
 */
public final class ClassDiff {

    public static void main(String[] args) throws Exception {
        // 参数可以是"补丁 jar 原版 jar"两个路径，也可以直接是类名——
        // 后者时按本机惯例自己找（与自检同一套来源，避免两边说的不是同一份文件）。
        // 顺带把两端可能带上的引号剥掉：命令行经过几层转发时很容易多出引号，
        // 而 Path.of 会因为那个字符直接失败（这个坑刚踩过）。
        List<String> clean = new ArrayList<>();
        for (String a : args) {
            String s = a.trim();
            if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
                s = s.substring(1, s.length() - 1);
            }
            clean.add(s);
        }
        Path patchedJar;
        Path vanillaJar;
        int firstClassArg;
        if (clean.size() >= 2 && clean.get(0).toLowerCase(java.util.Locale.ROOT).endsWith(".jar")
                && clean.get(1).toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
            patchedJar = Path.of(clean.get(0));
            vanillaJar = Path.of(clean.get(1));
            firstClassArg = 2;
        } else {
            Path gameDir = Path.of(java.nio.file.Files.readString(
                    Path.of("build/gamedir.txt"), java.nio.charset.StandardCharsets.UTF_8).trim());
            String versionId = gameDir.getFileName().toString();
            McInstance instance = McInstance.open(instanceRoot(gameDir));
            McInstance.Effective eff = instance.resolve(versionId);
            patchedJar = PatchedBase.locate(instance, "neoforge", versionId.split("[-_ ]")[0]);
            vanillaJar = eff.clientJar;
            firstClassArg = 0;
            System.out.println("补丁 jar：" + patchedJar);
            System.out.println("原版 jar：" + vanillaJar);
        }
        if (patchedJar == null || vanillaJar == null) {
            System.out.println("找不到要比较的两份 jar");
            System.exit(2);
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (int i = firstClassArg; i < clean.size(); i++) {
            wanted.add(clean.get(i).replace('.', '/'));
        }
        long t0 = System.nanoTime();
        int same = 0;
        int additive = 0;
        int removed = 0;
        List<String> removedSamples = new ArrayList<>(24);
        Set<String> removedClasses = new TreeSet<>();
        try (FastZip pj = FastZip.open(patchedJar); FastZip vj = FastZip.open(vanillaJar)) {
            for (FastZip.Entry pe : pj.entries()) {
                if (!pe.isClass() || pe.isDirectory() || !pe.name().startsWith("net/minecraft/")) {
                    continue;
                }
                if (!wanted.isEmpty() && !wanted.contains(pe.name().substring(0,
                        pe.name().length() - ".class".length()))) {
                    continue;
                }
                FastZip.Entry ve = vj.entry(pe.name());
                if (ve == null) {
                    continue;
                }
                byte[] pb = pj.read(pe);
                byte[] vb = vj.read(ve);
                if (java.util.Arrays.equals(pb, vb)) {
                    same++;
                    continue;
                }
                ClassFile pc;
                ClassFile vc;
                try {
                    pc = ClassFile.read(pb);
                    vc = ClassFile.read(vb);
                } catch (RuntimeException e) {
                    continue;
                }
                Set<String> pm = members(pc);
                Set<String> vm = members(vc);
                Set<String> lost = new TreeSet<>(vm);
                lost.removeAll(pm);
                String name = pe.name();
                if (lost.isEmpty()) {
                    additive++;
                } else {
                    removed++;
                    removedClasses.add(name.substring(0, name.length() - ".class".length()));
                    if (removedSamples.size() < 24) {
                        removedSamples.add(name + " 少了 " + lost.size() + " 个成员，例如 "
                                + lost.iterator().next());
                    }
                }
                if (!wanted.isEmpty()) {
                    System.out.println("== " + name);
                    System.out.println("   超类：" + vc.superName() + " → " + pc.superName());
                    System.out.println("   接口 +" + minus(pc, vc) + " -" + minus(vc, pc));
                    System.out.println("   成员 原版 " + vm.size() + " → 补丁 " + pm.size()
                            + "，补丁里没有的成员 " + lost.size() + " 个");
                    for (String s : lost) {
                        System.out.println("     - " + s);
                    }
                }
            }
        }
        System.out.println("== 统计");
        System.out.println("   完全相同的类：" + same);
        System.out.println("   只增不减（补丁是原版的超集）：" + additive);
        System.out.println("   动到了原版已有成员：" + removed);
        for (String s : removedSamples) {
            System.out.println("     · " + s);
        }
        System.out.println("   耗时 " + ((System.nanoTime() - t0) / 1_000_000) + " ms");
    }

    /** 需要"留给原版"的类：补丁动到了原版已有的成员（注入点最可能就在这些类里）。 */
    public static Set<String> memberChangingClasses(Path patchedJar, Path vanillaJar)
            throws java.io.IOException {
        Set<String> out = new TreeSet<>();
        try (FastZip pj = FastZip.open(patchedJar); FastZip vj = FastZip.open(vanillaJar)) {
            for (FastZip.Entry pe : pj.entries()) {
                if (!pe.isClass() || pe.isDirectory()) {
                    continue;
                }
                FastZip.Entry ve = vj.entry(pe.name());
                if (ve == null) {
                    continue;
                }
                byte[] pb = pj.read(pe);
                byte[] vb = vj.read(ve);
                if (java.util.Arrays.equals(pb, vb)) {
                    continue;
                }
                try {
                    Set<String> vm = members(ClassFile.read(vb));
                    Set<String> pm = members(ClassFile.read(pb));
                    Set<String> lost = new TreeSet<>(vm);
                    lost.removeAll(pm);
                    if (!lost.isEmpty()) {
                        String n = pe.name();
                        out.add(n.substring(0, n.length() - ".class".length()));
                    }
                } catch (RuntimeException ignored) {
                    // 解不开的类不参与判断。
                }
            }
        }
        return out;
    }

    private static Set<String> members(ClassFile cf) {
        Set<String> out = new TreeSet<>();
        for (ClassFile.Member m : cf.methods()) {
            out.add("M " + cf.utf8(m.nameIndex) + cf.utf8(m.descIndex));
        }
        for (ClassFile.Member f : cf.fields()) {
            out.add("F " + cf.utf8(f.nameIndex) + " " + cf.utf8(f.descIndex));
        }
        return out;
    }

    private static String minus(ClassFile a, ClassFile b) {
        Set<String> as = new TreeSet<>(List.of(a.interfaces()));
        Set<String> bs = new TreeSet<>(List.of(b.interfaces()));
        Set<String> only = new TreeSet<>(as);
        only.removeAll(bs);
        return only.toString();
    }

    /** 从版本目录往上找到 .minecraft（与自检同一套定位方式）。 */
    private static Path instanceRoot(Path gameDir) {
        Path p = gameDir.toAbsolutePath();
        while (p != null && !"versions".equals(String.valueOf(p.getFileName()))) {
            p = p.getParent();
        }
        return p == null ? gameDir.getParent() : p.getParent();
    }
}
