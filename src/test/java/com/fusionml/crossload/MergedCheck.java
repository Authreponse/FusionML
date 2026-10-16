package com.fusionml.crossload;

import com.fusionml.mc.McInstance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 检查合并出来的游戏包里，某几个关键类**最终用的是谁的字节** —— 原版还是对方平台补丁。
 *
 * <p>用途：{@code onlyAddsMembers} 的判据依赖"读得到新增接口的形状"，
 * 而那个形状要从对方平台运行时包里读。真机路径上运行时清单是有的，
 * 但离线工具（{@link WhyReplaced}）读不到，会保守地判成"必需"。
 * 所以必须有这么一个工具：**直接问合并产物**，避免"判据看着对、产物其实不对"。
 *
 * <p>判法：把合并包里那个类的字节，与原版包、补丁包里的同名类逐字节比对，
 * 看它等于哪一边。这比复算判据可靠 —— 它看的是事实。
 *
 * <p>用法：{@code java -cp build\classes;build\test-classes com.fusionml.crossload.MergedCheck
 * <合并包路径> <类内部名，可多个>}
 */
public final class MergedCheck {

    private MergedCheck() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("用法：MergedCheck <merged-base jar|latest> <内部名...>");
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
        String versionId = System.getProperty("fusion.version", "26.3-Fabric 0.19.5");
        Path vanillaJar = instance.resolve(versionId).clientJar;
        Path patchedJar = PatchedBase.locate(instance, "neoforge", PatchedBase.mcKeyOf(versionId));

        Path merged;
        if ("latest".equalsIgnoreCase(args[0])) {
            Path dir = instance.gameDir().resolve(".fusionml").resolve("base");
            Path best = null;
            long bestTime = -1;
            try (var s = Files.newDirectoryStream(dir, "merged-base-*.jar")) {
                for (Path p : s) {
                    long t = Files.getLastModifiedTime(p).toMillis();
                    if (t > bestTime) {
                        bestTime = t;
                        best = p;
                    }
                }
            }
            if (best == null) {
                System.out.println("在 " + dir + " 里没找到合并产物");
                return;
            }
            merged = best;
        } else {
            merged = Path.of(args[0]);
        }

        System.out.println("合并包：" + merged.getFileName());
        System.out.println("原版包：" + vanillaJar.getFileName());
        System.out.println("补丁包：" + patchedJar.getFileName());
        List<String> classes = new java.util.ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            classes.add(args[i].replace('.', '/'));
        }
        try (com.fusionml.util.FastZip mz = com.fusionml.util.FastZip.open(merged);
             com.fusionml.util.FastZip vz = com.fusionml.util.FastZip.open(vanillaJar);
             com.fusionml.util.FastZip pz = com.fusionml.util.FastZip.open(patchedJar)) {
            for (String wArg : classes) {
                String w = wArg;
                byte[] mb = read(mz, w);
                if (mb == null && !w.contains("$")) {
                    int slash = w.lastIndexOf('/');
                    if (slash > 0) {
                        String alt = w.substring(0, slash) + "$" + w.substring(slash + 1);
                        byte[] altBytes = read(mz, alt);
                        if (altBytes != null) {
                            w = alt;
                            mb = altBytes;
                        }
                    }
                }
                if (mb == null) {
                    System.out.println("== " + w + "：合并包里没有这个类");
                    continue;
                }
                byte[] vb = read(vz, w);
                byte[] pb = read(pz, w);
                boolean eqV = vb != null && java.util.Arrays.equals(mb, vb);
                boolean eqP = pb != null && java.util.Arrays.equals(mb, pb);
                String who = eqV && eqP ? "两边字节相同（无法区分）"
                        : eqV ? "原版（保留了原版字节）"
                        : eqP ? "对方平台补丁（整类替换）"
                        : "都不是（被改写过）";
                System.out.println("== " + w + "：" + who);
            }
        }
    }

    private static byte[] read(com.fusionml.util.FastZip z, String internal) throws Exception {
        com.fusionml.util.FastZip.Entry e = z.entry(internal + ".class");
        return e == null ? null : z.read(e);
    }
}
