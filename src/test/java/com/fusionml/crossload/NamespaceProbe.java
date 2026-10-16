package com.fusionml.crossload;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 命名空间探测：本机的每个游戏版本是"不混淆"还是"混淆"。
 *
 * <p>这是多版本适配（1.21.1 / 1.20.1）的第一道门：合并是"按内部名把对方补丁类搬进游戏包"，
 * 只有<b>不混淆</b>的版本才成立。混淆版本按名字一个都匹配不上，
 * 必须先把 remap 层补上（见 README 第十四节）。
 *
 * <p>这个工具的价值在于：**把"能不能做"这件事变成一条可复现的事实**，
 * 而不是等到合并搬了 0 个类、游戏崩在启动期才发现。
 *
 * <p>用法：{@code java -cp build\classes;build\test-classes com.fusionml.crossload.NamespaceProbe}
 */
public final class NamespaceProbe {

    private NamespaceProbe() {
    }

    public static void main(String[] args) throws Exception {
        Path mcRoot = null;
        for (String line : Files.readAllLines(Path.of("build", "gamedir.txt"),
                java.nio.charset.StandardCharsets.UTF_8)) {
            String s = line.trim();
            if (!s.isEmpty() && !s.startsWith("#")) {
                // gamedir.txt 指向 "…\.minecraft\versions\<版本>"，往上两级就是 .minecraft
                mcRoot = Path.of(s).getParent() == null ? null : Path.of(s).getParent().getParent();
                break;
            }
        }
        if (mcRoot == null || !Files.isDirectory(mcRoot)) {
            System.out.println("推不出 .minecraft 目录");
            return;
        }
        Path versions = mcRoot.resolve("versions");
        System.out.println("版本目录：" + versions);
        System.out.println();

        try (var dirs = Files.newDirectoryStream(versions)) {
            for (Path v : dirs) {
                if (!Files.isDirectory(v)) {
                    continue;
                }
                String name = v.getFileName().toString();
                // 只看关心的那几个版本，避免把整台机器的几十个实例都扫一遍
                if (!name.startsWith("26.") && !name.startsWith("1.21.1")
                        && !name.startsWith("1.20.1")) {
                    continue;
                }
                Path jar = null;
                try (var files = Files.newDirectoryStream(v, "*.jar")) {
                    for (Path f : files) {
                        // 版本 jar 的名字与目录名一致
                        if (f.getFileName().toString().startsWith(name + ".jar")) {
                            jar = f;
                            break;
                        }
                    }
                }
                System.out.println("== " + name);
                if (jar == null) {
                    System.out.println("   没有版本 jar（只有 mods 目录？）");
                    continue;
                }
                System.out.println("   " + PatchedBase.namespaceReport(jar));
                // 顺便看这个版本能不能找到对方平台的补丁原版 jar
                try {
                    com.fusionml.mc.McInstance inst =
                            com.fusionml.mc.McInstance.open(mcRoot.resolve("versions").resolve(name));
                    System.out.println("   " + inst);
                } catch (Throwable t) {
                    System.out.println("   实例打开失败：" + t);
                }
                System.out.println();
            }
        }
    }
}
