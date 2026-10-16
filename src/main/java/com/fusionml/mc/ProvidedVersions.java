package com.fusionml.mc;

import com.fusionml.util.FastZip;
import com.fusionml.util.Json;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

// 探测"当前环境到底提供了哪些东西、各自是什么版本"。
//
// 为什么必须探测而不是写死：加载器要靠这些版本号去校验模组声明的依赖
// （例如某个模组写着 fabricloader >=0.19.5）。如果加载器自以为装的是 0.15.11，
// 就会把一堆本来没问题的模组报成"依赖版本不满足"——这类误报比真正的缺依赖更烦人，
// 因为它会让人去改根本没坏的东西。
//
// 探测来源都是本地权威数据，不靠猜：
//   * Minecraft 版本：客户端 jar 内部的 version.json（官方自己写的，最准）
//   * 加载器版本：libraries 下对应 Maven 目录名（目录名就是版本号）
//   * Java 版本：当前运行时
public final class ProvidedVersions {

    private ProvidedVersions() {
    }

    public static Map<String, String> detect(McInstance instance, Path clientJar, String versionId) {
        Map<String, String> out = new LinkedHashMap<>();
        String mc = minecraftVersion(clientJar);
        if (mc == null || mc.isBlank()) {
            // 退回从版本名里截取：PCL/官方启动器的版本名通常形如 "26.3-Fabric 0.19.5"。
            String id = versionId == null ? "" : versionId;
            int dash = id.indexOf('-');
            mc = dash > 0 ? id.substring(0, dash) : id;
        }
        out.put("minecraft", mc);
        out.put("java", System.getProperty("java.version", ""));
        putIfFound(out, instance, "fabricloader", "net/fabricmc/fabric-loader");
        putIfFound(out, instance, "fabric", "net/fabricmc/fabric-loader");
        putIfFound(out, instance, "forge", "net/minecraftforge/forge");
        putIfFound(out, instance, "neoforge", "net/neoforged/neoforge");
        putIfFound(out, instance, "fabric-api", "net/fabricmc/fabric-api/fabric-api");
        return out;
    }

    // 从客户端 jar 里的 version.json 读官方版本号。
    public static String minecraftVersion(Path clientJar) {
        if (clientJar == null || !Files.isRegularFile(clientJar)) {
            return null;
        }
        try (FastZip zip = FastZip.open(clientJar)) {
            FastZip.Entry e = zip.entry("version.json");
            if (e == null) {
                return null;
            }
            Map<String, Object> v = Json.parseObject(zip.read(e));
            return Json.str(v, "id", null);
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    // 在 libraries 下按 Maven 路径找版本目录，取其中最高的版本号。
    private static void putIfFound(Map<String, String> out, McInstance instance, String key,
                                   String mavenDir) {
        Path dir = instance.librariesDir().resolve(mavenDir);
        if (!Files.isDirectory(dir)) {
            return;
        }
        String best = null;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path child : ds) {
                if (!Files.isDirectory(child)) {
                    continue;
                }
                String name = child.getFileName().toString();
                if (best == null || name.compareToIgnoreCase(best) > 0) {
                    best = name;
                }
            }
        } catch (IOException ignored) {
            // 读不了就当没装
        }
        if (best != null) {
            out.put(key, best);
        }
    }

    public static String describe(Map<String, String> versions) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : versions.entrySet()) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(e.getKey()).append(' ').append(e.getValue());
        }
        return sb.toString();
    }
}
