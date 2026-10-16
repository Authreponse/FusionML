package com.fusionml.crossload;

import com.fusionml.bytecode.ClassFile;
import com.fusionml.mc.McInstance;
import com.fusionml.util.FastZip;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 为什么某个类被"整类替换"而不是"保留原版"——把判据的每一步摆出来。
 *
 * <p>起因：{@code DataComponentMap$Builder} 在真机上仍然被换成了对方的字节，
 * 于是 fabric-item-api-v1 的 {@code @Overwrite} Mixin 崩掉；但离线看它的成员差异
 * 又像是"只新增"。判据是多条件的（超类 / 接口 / 成员），必须逐条打印才知道是哪一条否掉的。
 *
 * <p>用法：{@code java -cp build\classes;build\test-classes com.fusionml.crossload.WhyReplaced
 * net/minecraft/core/component/DataComponentMap$Builder}
 */
public final class WhyReplaced {

    private WhyReplaced() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("用法：WhyReplaced <内部名，可多个>");
            return;
        }
        Path gameDir = null;
        Path f = Path.of("build", "gamedir.txt");
        for (String line : Files.readAllLines(f, java.nio.charset.StandardCharsets.UTF_8)) {
            String s = line.trim();
            if (!s.isEmpty() && !s.startsWith("#")) {
                gameDir = Path.of(s);
                break;
            }
        }
        McInstance instance = McInstance.open(gameDir);
        // gamedir.txt 指向整合包根；版本目录名要从实例里推。
        String versionId = System.getProperty("fusion.version", "26.3-Fabric 0.19.5");
        McInstance.Effective eff = instance.resolve(versionId);
        Path vanillaJar = eff.clientJar;
        Path patchedJar = PatchedBase.locate(instance, "neoforge",
                PatchedBase.mcKeyOf(versionId));
        System.out.println("原版 jar：" + vanillaJar);
        System.out.println("补丁 jar：" + patchedJar);
        // 判据要扫"对方平台的字节"，所以先把它设进上下文。
        PatchedBase.setPatchedJarForTest(patchedJar);
        PatchedBase.setVanillaJarForTest(vanillaJar);
        // 对方平台**运行时包**也要给：真正按新超类使用某个类的代码
        //（例如 RegistryBuilder 用 MappedRegistry）住在 NeoForge 的 universal 包里，
        // 不在打补丁的原版包里。不给的话判据会一律返回"对方没按新超类用它"。
        try {
            String mcKey = PatchedBase.mcKeyOf(versionId);
            var rt = ForeignRuntime.resolve(instance, "neoforge", mcKey, line -> { });
            StringBuilder sb = new StringBuilder();
            for (Path p : rt.jars()) {
                if (sb.length() > 0) {
                    sb.append(java.io.File.pathSeparator);
                }
                sb.append(p.toAbsolutePath());
            }
            if (sb.length() > 0) {
                System.setProperty("fusion.foreignRuntimeJars", sb.toString());
            }
        } catch (Throwable t) {
            System.out.println("（运行时包解析失败，判据会偏保守：" + t + "）");
        }
        // 让接口形状判据也能读到对方运行时包。
        System.setProperty("fusion.librariesDir",
                instance.librariesDir().toAbsolutePath().toString());

        Set<String> wanted = new LinkedHashSet<>();
        for (String a : args) {
            // cmd 会把命令行里的 $ 吃掉（`DataComponentMap$Builder` 传到 JVM 时
            // 已经变成 `DataComponentMap`），所以额外支持一种"不带 $" 的写法：
            // 用 `/` 当内部类分隔符（例如 net/minecraft/core/component/DataComponentMap/Builder），
            // 由我们在这里还原成 $。
            wanted.add(a.replace('.', '/'));
        }
        try (FastZip vj = FastZip.open(vanillaJar); FastZip pj = FastZip.open(patchedJar)) {
            for (String wArg : wanted) {
                String w = wArg;
                String entry = w + ".class";
                FastZip.Entry ve = vj.entry(entry);
                FastZip.Entry pe = pj.entry(entry);
                // cmd 会把命令行的 $ 吃掉，所以支持用 / 写内部类分隔符：
                // 若按原样找不到，就把最后一段当成内部类再试一次（$ 形态）。
                if (ve == null && pe == null && !w.contains("$")) {
                    int slash = w.lastIndexOf('/');
                    if (slash > 0) {
                        String alt = w.substring(0, slash) + "$" + w.substring(slash + 1);
                        FastZip.Entry ve2 = vj.entry(alt + ".class");
                        FastZip.Entry pe2 = pj.entry(alt + ".class");
                        if (ve2 != null || pe2 != null) {
                            w = alt;
                            entry = alt + ".class";
                            ve = ve2;
                            pe = pe2;
                        }
                    }
                }
                System.out.println("== " + w);
                if (ve == null || pe == null) {
                    System.out.println("   原版有=" + (ve != null) + "，补丁有=" + (pe != null));
                    continue;
                }
                ClassFile v = ClassFile.read(vj.read(ve));
                ClassFile p = ClassFile.read(pj.read(pe));
                System.out.println("   超类一致=" + java.util.Objects.equals(v.superName(), p.superName())
                        + "（原版 " + v.superName() + " / 补丁 " + p.superName() + "）");
                System.out.println("   接口一致=" + java.util.Objects.equals(
                        String.join(",", v.interfaces()), String.join(",", p.interfaces()))
                        + "（原版 [" + String.join(",", v.interfaces()) + "] / 补丁 ["
                        + String.join(",", p.interfaces()) + "]）");
                Set<String> vm = methods(v);
                Set<String> pm = methods(p);
                Set<String> lost = new LinkedHashSet<>(vm);
                lost.removeAll(pm);
                System.out.println("   原版方法 " + vm.size() + " → 补丁 " + pm.size()
                        + "；补丁里没有的原版方法 " + lost.size() + " 个" + (lost.isEmpty() ? "" : " ← 这一条会否掉"));
                for (String s : lost) {
                    System.out.println("      · " + s);
                }
                Set<String> vf = fields(v);
                Set<String> pf = fields(p);
                Set<String> lf = new LinkedHashSet<>(vf);
                lf.removeAll(pf);
                System.out.println("   原版字段 " + vf.size() + " → 补丁 " + pf.size()
                        + "；补丁里没有的原版字段 " + lf.size() + " 个" + (lf.isEmpty() ? "" : " ← 这一条会否掉"));
                for (String s : lf) {
                    System.out.println("      · " + s);
                }
                // 新增接口的性质：带抽象方法的接口是对方模组硬依赖的（必须整类换），
                // 全 default 的只是便利接口（保留原版字节，否则宿主平台的 @Overwrite 会崩）。
                Set<String> added = new LinkedHashSet<>();
                for (String i : p.interfaces()) {
                    boolean has = false;
                    for (String oi : v.interfaces()) {
                        if (oi.equals(i)) {
                            has = true;
                        }
                    }
                    if (!has) {
                        added.add(i);
                    }
                }
                for (String i : added) {
                    Class<?> c = null;
                    try {
                        // 用系统属性里的运行时包找接口，找不到就用类加载器试试。
                        c = Class.forName(i.replace('/', '.'), false,
                                WhyReplaced.class.getClassLoader());
                    } catch (Throwable ignored) {
                        // 找不到也无所谓，下面按"读不到"处理
                    }
                    java.lang.reflect.Method[] ms = c == null ? new java.lang.reflect.Method[0]
                            : c.getDeclaredMethods();
                    int abs = 0;
                    for (java.lang.reflect.Method m : ms) {
                        if (java.lang.reflect.Modifier.isAbstract(m.getModifiers())) {
                            abs++;
                        }
                    }
                    System.out.println("   新增接口 " + i + "："
                            + (c == null ? "读不到（判据会保守地按'必需'处理）"
                            : "抽象方法 " + abs + " 个"
                            + (abs > 0 ? " → 对方模组硬依赖，整类换" : " → 纯便利接口，保留原版")));
                }
                System.out.println("   结论：会被" + (lost.isEmpty() && lf.isEmpty()
                        && java.util.Objects.equals(v.superName(), p.superName())
                        && java.util.Objects.equals(String.join(",", v.interfaces()),
                                String.join(",", p.interfaces()))
                        ? "保留原版" : "整类替换") + "字节");
                // 超类变化时的决定性判据：对方平台有没有"按新超类使用"这个类。
                // 有 → 必须换（否则 VerifyError）；没有 → 保留原版（保住宿主 Mixin 注入点）。
                if (!java.util.Objects.equals(v.superName(), p.superName())) {
                    boolean needed = PatchedBase.foreignUsesAsNewSuperclassForTest(
                            w, pj.read(pe), p);
                    System.out.println("   超类变化：" + v.superName() + " → " + p.superName()
                            + "；对方按新超类使用它=" + needed
                            + (needed ? "（必须整类换）" : "（保留原版以保住宿主注入点）"));
                    // 把判据内部过程打出来：否则只能看到一个是/否，
                    // 分不清它是真信号（对方确实依赖新超类）还是误命中（常见方法名撞上了）。
                    for (String line : PatchedBase.explainSuperclassUsage(w, pj.read(pe), p)) {
                        System.out.println("      " + line);
                    }
                }
            }
        }
    }

    private static Set<String> methods(ClassFile cf) {
        Set<String> out = new LinkedHashSet<>();
        for (ClassFile.Member m : cf.methods()) {
            out.add(cf.utf8(m.nameIndex) + cf.utf8(m.descIndex));
        }
        return out;
    }

    private static Set<String> fields(ClassFile cf) {
        Set<String> out = new LinkedHashSet<>();
        for (ClassFile.Member m : cf.fields()) {
            out.add(cf.utf8(m.nameIndex) + cf.utf8(m.descIndex));
        }
        return out;
    }
}
