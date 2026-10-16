package com.fusionml.crossload;

import com.fusionml.mc.McInstance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 检查某个类有没有被记进"宿主 Mixin 声明的方法名"表。
 *
 * <p>起因：{@code DataComponentMap$Builder} 的 Mixin（fabric-item-api-v1）撞名没被拦住，
 * 需要确认是"根本没记进表"还是"记了但判据没命中"。
 */
public final class MixinMapProbe {

    private MixinMapProbe() {
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
        List<Path> jars = PatchedBase.expandModJarsForTest(instance, null);
        System.out.println("模组包全集：" + jars.size());
        java.util.Set<String> targets = PatchedBase.hostMixinTargets(jars, null, line -> { });
        System.out.println("保留名单：" + targets.size());
        java.util.Map<String, java.util.Set<String>> declared = PatchedBase.mixinDeclaredMethods();
        System.out.println("记了方法名的目标类：" + declared.size());
        for (String a : args) {
            String internal = a.replace('.', '/');
            System.out.println("== " + internal);
            System.out.println("   在保留名单里=" + targets.contains(internal));
            java.util.Set<String> m = PatchedBase.mixinNamesForTest(internal);
            System.out.println("   Mixin 声明的方法名（经归一键查询）=" + (m == null ? "（没记到）" : m));
        }
        // 顺带把含 Builder 的键列出来，看命名是不是对不上
        System.out.println("-- 含 DataComponentMap 的键 --");
        for (String k : declared.keySet()) {
            if (k.contains("DataComponentMap")) {
                System.out.println("   " + k + " → " + declared.get(k));
            }
        }
    }
}
