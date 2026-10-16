package com.fusionml.install;

import com.fusionml.launch.JavaFinder;
import com.fusionml.meta.MetadataReader;
import com.fusionml.meta.ModFormat;
import com.fusionml.meta.ModMetadata;
import com.fusionml.runtime.FusionRuntime;
import com.fusionml.util.FastZip;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 安装界面的"环境体检"。
 *
 * <p>为什么安装器要去读模组包：用户真正想知道的是"我这个目录里有哪几端的模组、能不能一起跑"。
 * 这三件事如果不说清楚，安装完的期待就只能是"试试看"：
 * <ul>
 *   <li>目录里有哪些版本（能看出这是 Fabric / NeoForge / Forge 哪一端的整合包）；</li>
 *   <li>mods 目录里三端模组各有多少个（这正是本加载器的意义所在）；</li>
 *   <li>本机 Java 是否满足要求（26.3 要 Java 25，选错直接启动失败）。</li>
 * </ul>
 *
 * <p>全部使用加载器自己的解析器（FastZip + MetadataReader），不依赖游戏也不依赖任何平台：
 * 安装阶段游戏还没起来，能用的只有我们自己这套。
 */
public final class InstallEnvironment {

    /** 一个版本目录。 */
    public record Version(String id, boolean hasJson, boolean hasJar) {
        public String platform() {
            String v = id.toLowerCase(java.util.Locale.ROOT);
            if (v.contains("neoforge")) {
                return "NeoForge";
            }
            if (v.contains("forge")) {
                return "Forge";
            }
            if (v.contains("fabric")) {
                return "Fabric";
            }
            return "原版";
        }
    }

    /** 体检结果。 */
    public record Report(Path gameDir, Path modsDir, List<Version> versions,
                         Map<ModFormat, Integer> modsByFormat, int modJars, int totalMods,
                         List<String> unknownJars, JavaInfo java, boolean installed,
                         String loaderAt, List<String> notes) {
        public String modsLine() {
            StringBuilder sb = new StringBuilder();
            for (ModFormat f : new ModFormat[]{ModFormat.FABRIC, ModFormat.NEOFORGE, ModFormat.FORGE}) {
                int n = modsByFormat.getOrDefault(f, 0);
                if (sb.length() > 0) {
                    sb.append("   ");
                }
                sb.append(f.displayName()).append(' ').append(n);
            }
            return sb.toString();
        }

        public boolean mixedEcosystems() {
            int kinds = 0;
            for (ModFormat f : new ModFormat[]{ModFormat.FABRIC, ModFormat.NEOFORGE, ModFormat.FORGE}) {
                if (modsByFormat.getOrDefault(f, 0) > 0) {
                    kinds++;
                }
            }
            return kinds > 1;
        }
    }

    /** 本机 Java 情况。 */
    public record JavaInfo(Path exe, int major, String path) {
        public String line() {
            return major > 0 ? "Java " + major + "（" + path + "）" : "没找到可用的 Java";
        }
    }

    private InstallEnvironment() {
    }

    /**
     * 扫一遍游戏目录。
     *
     * @param requiredJava 启动这份游戏需要的最低 Java 主版本（未知传 0）
     */
    public static Report scan(Path gameDir, int requiredJava) {
        List<String> notes = new ArrayList<>(4);
        Path versionsDir = gameDir.resolve("versions");
        // 版本列表可能有两个来源，两个都要看：
        //   1) 目录自己的 versions 子目录（标准 .minecraft 布局）；
        //   2) 用户直接把"某一个版本目录"拖进来（例如 ...\versions\26.3-Fabric 0.19.5），
        //      这时版本列表在它的同级目录里。
        // 只取其一都会踩坑：实测某些实例目录里会自己长出一个空的 versions 子目录，
        // 于是"只认第一个来源"就会得到"未发现版本目录"这种与事实相反的结论。
        Path parent = gameDir.getParent();
        List<Path> versionRoots = new ArrayList<>(2);
        if (Files.isDirectory(versionsDir)) {
            versionRoots.add(versionsDir);
        }
        if (parent != null && parent.getFileName() != null
                && "versions".equalsIgnoreCase(parent.getFileName().toString())) {
            versionRoots.add(parent);
            notes.add("传入的是单个版本目录，版本列表取自其同级目录");
        }
        List<Version> versions = new ArrayList<>(4);
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (Path root : versionRoots) {
            try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
                for (Path p : ds) {
                    if (!Files.isDirectory(p)) {
                        continue;
                    }
                    String id = p.getFileName().toString();
                    if (id.startsWith(".") || !seen.add(id)) {
                        continue;
                    }
                    boolean json = Files.isRegularFile(p.resolve(id + ".json"));
                    boolean jar = Files.isRegularFile(p.resolve(id + ".jar"));
                    if (json || jar) {
                        versions.add(new Version(id, json, jar));
                    }
                }
            } catch (Exception e) {
                notes.add("读取版本目录失败：" + e.getMessage());
            }
        }
        if (!versions.isEmpty()) {
            versions.sort((a, b) -> a.id().compareToIgnoreCase(b.id()));
        } else {
            notes.add("没有 versions 目录，可能不是标准的 .minecraft 目录");
        }

        Path modsDir = gameDir.resolve("mods");
        Map<ModFormat, Integer> byFormat = new EnumMap<>(ModFormat.class);
        List<String> unknown = new ArrayList<>(2);
        int modJars = 0;
        int totalMods = 0;
        if (Files.isDirectory(modsDir)) {
            List<Path> jars;
            try {
                jars = FusionRuntime.listJars(modsDir);
            } catch (Exception e) {
                jars = List.of();
                notes.add("扫描 mods 目录失败：" + e.getMessage());
            }
            for (Path p : jars) {
                modJars++;
                try (FastZip zip = FastZip.open(p)) {
                    MetadataReader.Result r = MetadataReader.read(zip, 0);
                    if (r.mods().isEmpty()) {
                        // 前置库、资源包、数据包都会走到这里，属于正常，不当错误。
                        if (unknown.size() < 8) {
                            unknown.add(p.getFileName().toString());
                        }
                        continue;
                    }
                    for (ModMetadata m : r.mods()) {
                        byFormat.merge(m.format(), 1, Integer::sum);
                        totalMods++;
                    }
                } catch (Throwable t) {
                    notes.add("读取 " + p.getFileName() + " 失败：" + t.getMessage());
                }
            }
        }

        JavaFinder.Found found = null;
        try {
            found = JavaFinder.find(Math.max(requiredJava, JavaFinder.currentMajor()));
        } catch (Throwable t) {
            notes.add("探测 Java 失败：" + t.getMessage());
        }
        JavaInfo java = found == null ? new JavaInfo(null, 0, "")
                : new JavaInfo(found.javaExe(), found.major(), found.home());
        if (java.major() > 0 && requiredJava > 0 && java.major() < requiredJava) {
            notes.add("本机 Java " + java.major() + " 低于该版本要求的 Java " + requiredJava);
        }

        boolean installed = Files.isRegularFile(InstanceLayout.loaderJar(gameDir));
        String loaderAt = "";
        try {
            for (String line : InstanceLayout.readManifest(gameDir)) {
                if (line.startsWith("installedAt=")) {
                    loaderAt = line.substring("installedAt=".length());
                }
            }
        } catch (Throwable ignored) {
            // 没有清单就是没装过
        }
        return new Report(gameDir, modsDir, versions, byFormat, modJars, totalMods,
                unknown, java, installed, loaderAt, notes);
    }
}
