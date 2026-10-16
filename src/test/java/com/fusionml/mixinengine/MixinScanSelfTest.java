package com.fusionml.mixinengine;

import com.fusionml.util.FastZip;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mixin 配置扫描的自检：拿真实模组包跑一遍，看能不能把配置都认出来。
 *
 * <p>为什么必须用真实模组：Mixin 配置的字段名与文件名各模组五花八门，
 * 用自己造的样例永远发现不了"某个模组叫 xxx.mixin.json"这种事。
 */
public final class MixinScanSelfTest {

    static final String MODS = "D:\\我的世界\\服务器\\.minecraft\\versions\\"
            + "26.3-Fabric 0.19.5\\mods";

    public static void main(String[] a) throws Exception {
        Path dir = Path.of(a.length > 0 ? a[0] : MODS);
        if (!Files.isDirectory(dir)) {
            System.out.println("模组目录不存在：" + dir);
            return;
        }
        List<Path> jars = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.jar")) {
            for (Path p : ds) {
                jars.add(p);
            }
        }
        jars.sort(Comparator.comparing(p -> p.getFileName().toString()));
        System.out.println("模组包 = " + jars.size() + " 个（" + dir + "）");
        System.out.println();

        long t0 = System.nanoTime();
        List<FastZip> zips = new ArrayList<>();
        try {
            for (Path p : jars) {
                try {
                    zips.add(FastZip.open(p));
                } catch (Throwable t) {
                    System.out.println("  跳过打不开的包：" + p.getFileName() + "（" + t + "）");
                }
            }
            for (boolean client : new boolean[]{true, false}) {
                MixinConfigScanner.Scan s = MixinConfigScanner.scan(zips, client);
                System.out.println("=== 运行侧 = " + (client ? "客户端" : "服务端") + " ===");
                System.out.println("  Mixin 配置文件 = " + s.configs().size());
                System.out.println("  待注入类       = " + s.size());

                Map<String, Integer> byJar = new LinkedHashMap<>();
                int clientOnly = 0;
                int serverOnly = 0;
                int withPlugin = 0;
                for (MixinConfigScanner.Entry e : s.entries()) {
                    byJar.merge(e.sourceJar(), 1, Integer::sum);
                    if (e.clientOnly()) {
                        clientOnly++;
                    }
                    if (e.serverOnly()) {
                        serverOnly++;
                    }
                    if (!e.plugin().isBlank()) {
                        withPlugin++;
                    }
                }
                System.out.println("  仅客户端 = " + clientOnly + "，仅服务端 = " + serverOnly
                        + "，带自定义 plugin = " + withPlugin);
                System.out.println("  涉及模组包 = " + byJar.size());
                System.out.println();
            }
        } finally {
            for (FastZip z : zips) {
                try {
                    z.close();
                } catch (Throwable ignored) {
                    // 关闭失败不影响结论。
                }
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("总耗时 = " + ms + " ms");

        // 抽几个具体条目打印，证明解析结果是真的（不是空壳）。
        List<FastZip> z2 = new ArrayList<>();
        try {
            for (Path p : jars) {
                try {
                    z2.add(FastZip.open(p));
                } catch (Throwable ignored) {
                    // 前面已报过，这里静默跳过。
                }
            }
            MixinConfigScanner.Scan s = MixinConfigScanner.scan(z2, true);
            System.out.println();
            System.out.println("=== 抽样（前 8 个待注入类）===");
            s.entries().stream().limit(8).forEach(e -> {
                System.out.println("  " + e.mixinClass()
                        + "   <- " + shortName(e.sourceJar()) + " / " + e.configPath()
                        + (e.clientOnly() ? "  [仅客户端]" : ""));
            });
        } finally {
            for (FastZip z : z2) {
                try {
                    z.close();
                } catch (Throwable ignored) {
                    // 同上。
                }
            }
        }
    }

    static String shortName(String jar) {
        String s = jar.replace('\\', '/');
        int i = s.lastIndexOf('/');
        return i < 0 ? s : s.substring(i + 1);
    }
}
