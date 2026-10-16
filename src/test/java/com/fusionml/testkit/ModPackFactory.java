package com.fusionml.testkit;

import com.fusionml.bytecode.ClassEmitter;
import com.fusionml.meta.ModFormat;
import com.fusionml.util.FastZipWriter;
import com.fusionml.util.Json;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * 三端测试夹具生成器。
 *
 * <p>刻意生成<b>真实的类文件</b>（由自研 {@link ClassEmitter} 产出）而不是空条目，
 * 这样压测的才是真实的解压 + 扫描 + 注解解析路径，而不是一个被简化过的假流程。
 *
 * <p>三端按比例混合：Fabric / Forge / NeoForge，并让一部分包同时携带两种元数据
 * （现实中的"多端同包"），用来验证加载器不会把它们当成同 id 冲突。
 */
public final class ModPackFactory {

    /** 生成配置。 */
    public static final class Spec {
        public int modCount = 120;
        public int minClasses = 6;
        public int maxClasses = 60;
        public long seed = 20261013L;
        public double fabricRatio = 0.5;
        public double forgeRatio = 0.3;
        /** 其余按 NeoForge 处理。 */
        public double hybridRatio = 0.1;
        public boolean withDependencies = true;
        public boolean withMixinConfig = true;
    }

    private ModPackFactory() {
    }

    public static void generate(Path dir, Spec spec) throws IOException {
        Files.createDirectories(dir);
        Random rnd = new Random(spec.seed);
        for (int i = 0; i < spec.modCount; i++) {
            String id = "testmod" + i;
            ModFormat format = pickFormat(rnd, spec);
            List<String> deps = new ArrayList<>(2);
            if (spec.withDependencies && i > 0 && rnd.nextDouble() < 0.45) {
                int depIdx = rnd.nextInt(i);
                deps.add("testmod" + depIdx);
            }
            int classCount = spec.minClasses + rnd.nextInt(Math.max(1, spec.maxClasses - spec.minClasses));
            boolean hybrid = rnd.nextDouble() < spec.hybridRatio && format == ModFormat.FABRIC;
            writeJar(dir.resolve(id + ".jar"), id, format, hybrid, classCount, deps, spec, rnd);
        }
    }

    private static ModFormat pickFormat(Random rnd, Spec spec) {
        double r = rnd.nextDouble();
        if (r < spec.fabricRatio) {
            return ModFormat.FABRIC;
        }
        if (r < spec.fabricRatio + spec.forgeRatio) {
            return ModFormat.FORGE;
        }
        return ModFormat.NEOFORGE;
    }

    private static void writeJar(Path target, String id, ModFormat format, boolean hybrid,
                                 int classCount, List<String> deps, Spec spec, Random rnd)
            throws IOException {
        // 包名按"点分"书写（这才是真实模组里的写法），路径再单独换算 ——
        // 夹具如果自己就用斜杠写包名，就会把加载器里"名字规范化"这类 bug 一起掩盖掉。
        String pkg = "com.test." + id;
        String pkgPath = pkg.replace('.', '/');
        try (OutputStream fos = new BufferedOutputStream(Files.newOutputStream(target), 1 << 16);
             FastZipWriter zip = new FastZipWriter(fos)) {
            zip.add("META-INF/MANIFEST.MF",
                    ("Manifest-Version: 1.0\r\nImplementation-Version: 1." + rnd.nextInt(50)
                            + "." + rnd.nextInt(20) + "\r\n\r\n").getBytes(StandardCharsets.UTF_8), true);

            // 主类
            String mainClass = pkgPath + "/Main";
            ClassEmitter main = new ClassEmitter(mainClass, "java/lang/Object")
                    .addDefaultConstructor();
            if (format != ModFormat.FABRIC) {
                main.addAnnotation(format.modAnnotationDesc());
            }
            for (int m = 0; m < 4; m++) {
                main.addConstantStaticMethod("value" + m, "()I", rnd.nextInt(1000));
            }
            zip.add(mainClass + ".class", main.build(), true);

            // 普通类：带一点实现（接口、常量方法），让类体有真实体积。
            // 第 0 号类刻意做成"可被注入的靶子"：实例方法 + 一个静态方法，
            // 用来覆盖注入器对两种方法形状的处理。
            for (int c = 0; c < classCount; c++) {
                ClassEmitter ce = new ClassEmitter(pkgPath + "/Helper" + c, "java/lang/Object")
                        .addInterface("java/lang/Runnable")
                        .addDefaultConstructor();
                if (c == 0) {
                    ce.addInstanceConstantMethod("compute0", "(II)I", rnd.nextInt(4096));
                }
                for (int m = 0; m < 3 + rnd.nextInt(5); m++) {
                    int index = c == 0 ? m + 1 : m;
                    ce.addConstantStaticMethod("compute" + index, "(II)I", rnd.nextInt(4096));
                }
                zip.add(pkgPath + "/Helper" + c + ".class", ce.build(), true);
            }

            // 原生注入配置 + 注入类
            if (spec.withMixinConfig && rnd.nextDouble() < 0.35) {
                Map<String, Object> cfg = new LinkedHashMap<>();
                cfg.put("package", pkg + ".mixin");
                cfg.put("mixins", List.of("HelperMixin"));
                zip.add("fusion.mixins.json",
                        Json.write(cfg).getBytes(StandardCharsets.UTF_8), true);
                ClassEmitter mixin = new ClassEmitter(pkgPath + "/mixin/HelperMixin", "java/lang/Object")
                        .addAnnotation("Lcom/fusionml/mixin/FusionMixin;",
                                Map.of("target", pkg + ".Helper0"))
                        .addDefaultConstructor()
                        .addAnnotatedEmptyStaticMethod("onCompute",
                                "(L" + pkgPath + "/Helper0;II)V",
                                new ClassEmitter.Anno("Lcom/fusionml/mixin/FusionHead;",
                                        Map.of("method", "compute0", "desc", "(II)I")));
                zip.add(pkgPath + "/mixin/HelperMixin.class", mixin.build(), true);
            }

            // 元数据
            writeMetadata(zip, id, format, deps, rnd);
            if (hybrid) {
                // 多端同包：一个 jar 里同时有 Fabric 与 Forge 两套元数据。
                // 注意 id 必须不同 —— 现实中这种包的作者也会给两个端不同的 modId 后缀，
                // 否则加载器会正确地把它们判为同 id 冲突。
                zip.add("META-INF/mods.toml",
                        forgeToml(id + "-forge", deps, "1.20.1").getBytes(StandardCharsets.UTF_8), true);
            }
            if (rnd.nextDouble() < 0.3) {
                byte[] icon = new byte[256 + rnd.nextInt(2048)];
                rnd.nextBytes(icon);
                zip.add("assets/" + id + "/icon.png", icon, true);
            }
        }
    }

    private static void writeMetadata(FastZipWriter zip, String id, ModFormat format,
                                      List<String> deps, Random rnd) throws IOException {
        switch (format) {
            case FABRIC -> zip.add("fabric.mod.json",
                    fabricJson(id, deps, false).getBytes(StandardCharsets.UTF_8), true);
            case FORGE -> zip.add("META-INF/mods.toml",
                    forgeToml(id, deps, "1.20.1").getBytes(StandardCharsets.UTF_8), true);
            case NEOFORGE -> zip.add("META-INF/neoforge.mods.toml",
                    forgeToml(id, deps, "1.21").getBytes(StandardCharsets.UTF_8), true);
        }
    }

    private static String fabricJson(String id, List<String> deps, boolean hybridTag) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 1);
        root.put("id", id);
        root.put("version", "1.0.0");
        root.put("name", "测试模组 " + id);
        root.put("description", "由 FusionML 夹具生成器产出的 Fabric 测试模组");
        root.put("authors", List.of("FusionML 测试套件"));
        root.put("license", "MIT");
        Map<String, Object> entrypoints = new LinkedHashMap<>();
        entrypoints.put("main", List.of("com.test." + id + ".Main"));
        root.put("entrypoints", entrypoints);
        Map<String, Object> depends = new LinkedHashMap<>();
        depends.put("fabricloader", ">=0.14.0");
        depends.put("minecraft", ">=1.20");
        for (String d : deps) {
            depends.put(d, ">=1.0.0");
        }
        root.put("depends", depends);
        return Json.write(root);
    }

    private static String forgeToml(String id, List<String> deps, String mcVersion) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("modLoader=\"javafml\"\n");
        sb.append("loaderVersion=\"[1,)\"\n");
        sb.append("license=\"MIT\"\n");
        sb.append("[[mods]]\n");
        sb.append("modId=\"").append(id).append("\"\n");
        sb.append("version=\"1.0.0\"\n");
        sb.append("displayName=\"测试模组 ").append(id).append("\"\n");
        sb.append("description='''\n由 FusionML 夹具生成器产出的 ")
                .append(mcVersion).append(" 测试模组\n'''\n");
        sb.append("authors=\"FusionML 测试套件\"\n");
        sb.append("[[dependencies.").append(id).append("]]\n");
        sb.append("modId=\"minecraft\"\n");
        sb.append("mandatory=true\n");
        sb.append("versionRange=\"[1.20,)\"\n");
        sb.append("ordering=\"NONE\"\n");
        sb.append("side=\"BOTH\"\n");
        for (String d : deps) {
            sb.append("[[dependencies.").append(id).append("]]\n");
            sb.append("modId=\"").append(d).append("\"\n");
            sb.append("mandatory=true\n");
            sb.append("versionRange=\"[1.0.0,)\"\n");
            sb.append("ordering=\"AFTER\"\n");
            sb.append("side=\"BOTH\"\n");
        }
        return sb.toString();
    }

    /** 人类可读的规模描述。 */
    public static String describe(Spec spec) {
        return String.format(Locale.ROOT, "%d 个模组包，每包 %d~%d 个类，Fabric %.0f%% / Forge %.0f%% / NeoForge 其余",
                spec.modCount, spec.minClasses, spec.maxClasses,
                spec.fabricRatio * 100, spec.forgeRatio * 100);
    }
}
