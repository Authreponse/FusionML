package com.fusionml.crossload;

import com.fusionml.mc.McInstance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 检查某个原版类是否被"宿主要 Mixin 的清单"（{@code keepVanilla}）保住。
 *
 * <p>起因：{@code net.minecraft.resources.RegistryDataLoader} 被换成了对方的字节，
 * 于是 fabric-registry-sync-v0 的 {@code @ModifyArg} 找不到注入点，报
 * {@code Critical injection failure ... could not find any targets matching ...}。
 * 但它的 Mixin 配置与注入类都在、{@code @Mixin} 注解也写了目标 ——
 * 所以要么是扫描没覆盖到那个包，要么是注解解析漏了。
 * 这个工具把答案直接摆出来。
 *
 * <p>用法：{@code java -cp build\classes;build\test-classes com.fusionml.crossload.KeepProbe
 * net/minecraft/resources/RegistryDataLoader}
 */
public final class KeepProbe {

    private KeepProbe() {
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
        String mcKey = PatchedBase.mcKeyOf(versionId);
        Path patchedJar = PatchedBase.locate(instance, "neoforge", mcKey);

        // 用"模组全集"扫，和真机一致。
        List<Path> mods = new ArrayList<>();
        try (var s = Files.newDirectoryStream(instance.gameDir().resolve("mods"), "*.jar")) {
            for (Path p : s) {
                mods.add(p);
            }
        }
        List<Path> all = PatchedBase.expandModJars(instance, mods, null);
        System.out.println("模组包全集：" + all.size());

        Set<String> targets = PatchedBase.hostMixinTargets(all, patchedJar, null);
        System.out.println("hostMixinTargets 命中补丁类：" + targets.size());
        for (String a : args) {
            String w = a.replace('.', '/');
            System.out.println("== " + w + " 在保留名单里=" + targets.contains(w));
        }
    }
}
