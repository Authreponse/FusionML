package com.fusionml.crossload;

import com.fusionml.bytecode.ClassFile;
import com.fusionml.mc.McInstance;
import com.fusionml.util.FastZip;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 超类桥接的离线验证：把原版类与"对方换了超类"的补丁类做桥接，
 * 然后**让 JVM 真的去定义它**，确认产物合法。
 *
 * <p>为什么必须先离线验证：桥接要动 {@code <init>} 的字节码与
 * {@code Code} 属性，任何一处算错（属性长度、max_stack、常量池索引）
 * 都会产出坏类，而症状出现在游戏启动期、离这里极远。
 * 这一课前面已经上过好几次（{@code VerifyError: Illegal type at constant pool entry 188}
 * 就是这类），所以这次先验证再接线。
 *
 * <p>用法：{@code java -cp build\classes;build\test-classes com.fusionml.crossload.BridgeProbe
 * net.minecraft.world.level.Level}
 */
public final class BridgeProbe {

    private BridgeProbe() {
    }

    /** 把 defineClass 暴露出来，好让 JVM 真的跑一遍字节码校验。 */
    private static final class DefiningLoader extends ClassLoader {
        DefiningLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
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
        // 对方**超类**住在 NeoForge 运行时包里，不在打补丁的原版包里 ——
        // 所以必须把运行时清单也交给判据，否则会报"读不到对方超类"。
        ForeignRuntime.Found runtime = ForeignRuntime.resolve(instance, "neoforge",
                PatchedBase.mcKeyOf(versionId), line -> System.out.println("[运行时] " + line));
        StringBuilder rt = new StringBuilder();
        for (Path jar : runtime.jars()) {
            if (rt.length() > 0) {
                rt.append(java.io.File.pathSeparator);
            }
            rt.append(jar.toAbsolutePath());
        }
        System.setProperty("fusion.foreignRuntimeJars", rt.toString());
        System.out.println("运行时包 " + runtime.jars().size() + " 个");
        System.out.println("原版包：" + vanillaJar.getFileName());
        System.out.println("补丁包：" + patchedJar.getFileName());

        List<String> targets = args.length > 0 ? List.of(args)
                : List.of("net/minecraft/world/level/Level");
        for (String a : targets) {
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
                ClassFile p = ClassFile.read(pb);
                System.out.println("   原版：超类 " + v.superName() + "，方法 " + v.methods().length
                        + "，字段 " + v.fields().length + "，接口 "
                        + String.join("、", v.interfaces()));
                System.out.println("   补丁：超类 " + p.superName() + "，方法 " + p.methods().length
                        + "，字段 " + p.fields().length + "，接口 "
                        + String.join("、", p.interfaces()));
                java.util.List<String> notes = new java.util.ArrayList<>();
                byte[] bridged = PatchedBase.bridgeSuperclassForTest(vb, pb, internal, notes);
                for (String n : notes) {
                    System.out.println("   注：" + n);
                }
                if (bridged == null) {
                    System.out.println("   桥接未产出（调用方应退回原判据）");
                    continue;
                }
                ClassFile b = ClassFile.read(bridged);
                System.out.println("   桥接后：超类 " + b.superName()
                        + "，方法 " + b.methods().length
                        + "，字段 " + b.fields().length
                        + "，接口 " + String.join("、", b.interfaces()));
                // 关键：让 JVM 真的校验一遍。
                try {
                    DefiningLoader loader = new DefiningLoader(
                            BridgeProbe.class.getClassLoader());
                    Class<?> c = loader.define(internal.replace('/', '.'), bridged);
                    System.out.println("   JVM 校验：通过（已定义 " + c.getName() + "）");
                } catch (Throwable t) {
                    Throwable root = t;
                    while (root.getCause() != null && root.getCause() != root) {
                        root = root.getCause();
                    }
                    System.out.println("   JVM 校验：失败 —— " + root);
                }
                // 顺带看桥接是否把关键方法搬进来了
                for (String probe : new String[] {"getData", "setData", "hasAttachments"}) {
                    boolean has = false;
                    for (ClassFile.Member m : b.methods()) {
                        if (probe.equals(b.utf8(m.nameIndex))) {
                            has = true;
                        }
                    }
                    System.out.println("   方法 " + probe + " 存在=" + has);
                }
            }
        }
    }
}
