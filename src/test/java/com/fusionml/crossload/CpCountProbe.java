package com.fusionml.crossload;

import com.fusionml.bytecode.ClassFile;
import com.fusionml.mc.McInstance;
import com.fusionml.util.FastZip;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 核对"写入的常量池计数"与"实际能读到的最大索引"是否一致。
 *
 * <p>起因：{@code ClassFormatError: Attribute name has bad constant pool index 170}
 * 而读回的最大索引是 169 —— 差 1。要么计数写大了，要么少写了一项。
 * 这个小工具把两边的数都摆出来，不用猜。
 *
 * <p>用法：{@code java -cp build\classes;build\test-classes com.fusionml.crossload\CpCountProbe
 * net/minecraft/core/NonNullList}
 */
public final class CpCountProbe {

    private CpCountProbe() {
    }

    public static void main(String[] args) throws Exception {
        Path gameDir = null;
        for (String line : Files.readAllLines(Path.of("build", "gamedir.txt"),
                java.nio.charset.StandardCharsets.UTF_8)) {
            String s = line.trim();
            if (!s.isEmpty() && !s.startsWith("#")) {
                gameDir = Path.of(s);
                break;
            }
        }
        McInstance instance = McInstance.open(gameDir);
        String versionId = System.getProperty("fusion.version", "26.3-Fabric 0.19.5");
        Path vanillaJar = instance.resolve(versionId).clientJar;
        Path patchedJar = PatchedBase.locate(instance, "neoforge", PatchedBase.mcKeyOf(versionId));
        PatchedBase.setVanillaJarForTest(vanillaJar);
        PatchedBase.setPatchedJarForTest(patchedJar);
        try {
            var rt = ForeignRuntime.resolve(instance, "neoforge", PatchedBase.mcKeyOf(versionId),
                    line -> { });
            StringBuilder sb = new StringBuilder();
            for (Path p : rt.jars()) {
                if (sb.length() > 0) {
                    sb.append(java.io.File.pathSeparator);
                }
                sb.append(p.toAbsolutePath());
            }
            System.setProperty("fusion.foreignRuntimeJars", sb.toString());
        } catch (Throwable ignored) {
            // 尽力而为
        }

        for (String a : args) {
            String internal = a.replace('.', '/');
            System.out.println("== " + internal);
            try (FastZip vz = FastZip.open(vanillaJar); FastZip pz = FastZip.open(patchedJar)) {
                FastZip.Entry ve = vz.entry(internal + ".class");
                FastZip.Entry pe = pz.entry(internal + ".class");
                if (ve == null || pe == null) {
                    System.out.println("   包里缺类");
                    continue;
                }
                byte[] vb = vz.read(ve);
                byte[] pb = pz.read(pe);
                ClassFile v = ClassFile.read(vb);
                System.out.println("   原版：可读最大索引=" + maxIndex(v)
                        + "，cpSlotCount=" + v.extraCpStats()[2]);
                // 关键：原版的**最后一个可用槽位**是不是就是 cpSlotCount-1？
                // 如果最后一个常量是 long/double（占两槽），cpSlotCount-1 可能是"空洞"，
                // 而我们就可能把新项分配到了那个空洞上 —— 这正是
                // "ClassFormatError: bad constant pool index" 的典型成因。
                System.out.println("   原版最后 4 个常量：");
                for (int i = Math.max(1, maxIndex(v) - 3); i <= maxIndex(v) + 2; i++) {
                    ClassFile.Cp c = v.cp(i);
                    System.out.println("      #" + i + " = " + (c == null ? "（空/空洞）"
                            : "tag" + c.tag + " " + c));
                }

                byte[] out = PatchedBase.spliceNewMethodsForTest(vb, pb, internal);
                if (out == null) {
                    System.out.println("   拼接未产出");
                    continue;
                }
                // 直接从**原始字节**里读 constant_pool_count，不从解析结果读。
                int rawCount = ((out[8] & 0xFF) << 8) | (out[9] & 0xFF);
                ClassFile r = ClassFile.read(out);
                int maxIdx = maxIndex(r);
                System.out.println("   拼接产物：constant_pool_count=" + rawCount
                        + "（期望 = 最大索引 + 1 = " + (maxIdx + 1) + "）");
                System.out.println("   可读最大索引=" + maxIdx
                        + "，追加项数/槽位=" + java.util.Arrays.toString(r.extraCpStats())
                        + "，原始 CP 字节=" + r.rawCpByteLength() + " 字节");
                System.out.println(rawCount == maxIdx + 1
                        ? "   ✓ 计数一致" : "   ✗ 计数不一致（差 " + (rawCount - maxIdx - 1) + "）");
                // 关键问题：属性名索引是不是超出了可读范围？
                // `ClassFormatError: Attribute name has bad constant pool index N` 说明
                // 某处写了一个 >= count 的索引 —— 也就是"分配了但没写进去"的那一项。
                int bad = 0;
                for (ClassFile.Member m : r.methods()) {
                    for (ClassFile.Attr at : m.attrs) {
                        if (at.nameIndex() >= rawCount) {
                            System.out.println("   ✗ 方法 " + r.utf8(m.nameIndex)
                                    + " 的属性名索引 " + at.nameIndex()
                                    + " >= count " + rawCount);
                            bad++;
                        }
                    }
                }
                for (ClassFile.Member f : r.fields()) {
                    for (ClassFile.Attr at : f.attrs) {
                        if (at.nameIndex() >= rawCount) {
                            System.out.println("   ✗ 字段 " + r.utf8(f.nameIndex)
                                    + " 的属性名索引 " + at.nameIndex()
                                    + " >= count " + rawCount);
                            bad++;
                        }
                    }
                }
                System.out.println(bad == 0 ? "   ✓ 属性名索引都在范围内"
                        : "   ✗ 有 " + bad + " 处属性名索引越界");
                // 把产物写到盘上，交给 javap 复核 —— 我们自己的解析器说没问题、
                // 但 JVM 说 index 170 坏，那就必须用一个**独立实现**再看一遍。
                Path dump = Path.of("build", "cp-probe-" + internal.replace('/', '_') + ".class");
                Files.write(dump, out);
                System.out.println("   已导出：" + dump.toAbsolutePath());
                // 用 JVM 真的定义一遍，拿到确切的错误信息（与真机一致）。
                try {
                    var loader = new ClassLoader(CpCountProbe.class.getClassLoader()) {
                        Class<?> def(String n, byte[] b) {
                            return defineClass(n, b, 0, b.length);
                        }
                    };
                    loader.def(internal.replace('/', '.'), out);
                    System.out.println("   JVM 校验：通过");
                } catch (Throwable t) {
                    Throwable root = t;
                    while (root.getCause() != null && root.getCause() != root) {
                        root = root.getCause();
                    }
                    System.out.println("   JVM 校验：失败 —— " + root);
                }
            }
        }
    }

    private static int maxIndex(ClassFile cf) {
        int m = 0;
        for (int i = 1; i < 65536; i++) {
            if (cf.cp(i) != null) {
                m = i;
            }
        }
        return m;
    }
}
