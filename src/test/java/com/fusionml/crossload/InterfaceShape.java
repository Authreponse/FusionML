package com.fusionml.crossload;

import com.fusionml.bytecode.ClassFile;
import com.fusionml.util.FastZip;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 看一个接口的"形状"：它是不是纯 default 方法接口（没有抽象方法）。
 *
 * <p>为什么要这个判据：对方平台给原版类**加接口**有两种性质完全不同的情况 ——
 *
 * <ul>
 *   <li>{@code Registry → IRegistryExtension}：对方模组注册时**真的会用到**它
 *       （不在就会抛 {@code LambdaConversionException}），缺了就跑不起来；</li>
 *   <li>{@code DataComponentMap$Builder → IDataComponentMapBuilderExtensions}：
 *       只是对方平台顺手加的便利接口，宿主平台那边的模组根本不碰它，
 *       而把它带进原版类反而会让宿主平台的 {@code @Overwrite} Mixin 崩掉。</li>
 * </ul>
 *
 * <p>前者的方法里有**抽象方法**（对方模组的代码必须能从接口上调用它），
 * 后者通常全是 default 方法。所以"有没有抽象方法"是一个可计算的判据。
 *
 * <p>用法：{@code java -cp build\classes;build\test-classes com.fusionml.crossload.InterfaceShape
 * net/neoforged/neoforge/registries/IRegistryExtension net/neoforged/.../IDataComponentMapBuilderExtensions}
 */
public final class InterfaceShape {

    private InterfaceShape() {
    }

    public static void main(String[] args) throws Exception {
        String neoRoot = System.getProperty("fusion.neoRoot", "");
        Path libs = neoRoot.isBlank()
                ? Path.of("D:\\我的世界\\服务器\\.minecraft\\libraries")
                : Path.of(neoRoot);
        // 在 libraries 里找 NeoForge 的运行时包（目标接口住在里面，不在补丁原版包里）。
        java.util.List<Path> jars = new java.util.ArrayList<>();
        Path neoDir = libs.resolve("net").resolve("neoforged");
        if (Files.isDirectory(neoDir)) {
            try (var s = Files.walk(neoDir, 5)) {
                s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jar"))
                        .forEach(jars::add);
            }
        }
        System.out.println("候选 jar：" + jars.size() + " 个");
        for (String arg : args) {
            String name = arg.replace('.', '/');
            System.out.println("== " + name);
            boolean found = false;
            for (Path j : jars) {
                try (FastZip z = FastZip.open(j)) {
                    FastZip.Entry e = z.entry(name + ".class");
                    if (e == null) {
                        continue;
                    }
                    found = true;
                    ClassFile cf = ClassFile.read(z.read(e));
                    int abstractMethods = 0;
                    int defaultMethods = 0;
                    int staticMethods = 0;
                    java.util.List<String> abs = new java.util.ArrayList<>();
                    for (ClassFile.Member m : cf.methods()) {
                        String mn = cf.utf8(m.nameIndex);
                        if ("<clinit>".equals(mn)) {
                            continue;
                        }
                        boolean isAbstract = (m.access & 0x0400) != 0;
                        boolean isStatic = (m.access & 0x0008) != 0;
                        if (isStatic) {
                            staticMethods++;
                        } else if (isAbstract) {
                            abstractMethods++;
                            if (abs.size() < 6) {
                                abs.add(mn + cf.utf8(m.descIndex));
                            }
                        } else {
                            defaultMethods++;
                        }
                    }
                    System.out.println("   在 " + j.getFileName());
                    System.out.println("   是接口=" + cf.isInterface()
                            + "，抽象方法 " + abstractMethods
                            + "，default 方法 " + defaultMethods
                            + "，静态方法 " + staticMethods);
                    for (String a : abs) {
                        System.out.println("      · 抽象：" + a);
                    }
                    System.out.println("   判据："
                            + (abstractMethods == 0
                            ? "纯 default —— 对方模组不强制依赖，可以不要"
                            : "有抽象方法 —— 对方模组很可能真的依赖它"));
                    break;
                } catch (Throwable t) {
                    // 换下一个 jar
                }
            }
            if (!found) {
                System.out.println("   没找到（不是所有 jar 都可读，或名字不对）");
            }
        }
    }
}
