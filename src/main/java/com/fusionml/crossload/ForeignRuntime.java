package com.fusionml.crossload;

import com.fusionml.mc.McInstance;
import com.fusionml.mc.McVersion;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * "另一端"的运行时在哪。
 *
 * <p>要让非宿主平台的模组真正跑起来，光有它自己的包不够 —— 还得有那一端的 API 与事件总线
 * （NeoForge 的 {@code net.neoforged.bus}、{@code net.neoforged.fml}、
 * {@code net.neoforged.neoforge} 这些类）。这些东西是那一端的官方产物，
 * 我们自己实现一遍既没有必要、也会和"原生"背道而驰。
 *
 * <p>所以策略是：<b>本机只要装过一份同版本的该端，就把它的运行时借过来用</b>。
 * 这对玩家来说零额外动作（整合包里往往本来就有另一端的启动项），
 * 而且版本天然对得上 —— 拿别的版本的 API 去驱动模组才是真正的麻烦。
 *
 * <p>只挑真正需要的那几个 jar，不去动 log4j / asm / guava 这类"两边都有"的库：
 * 宿主平台已经有它们了，再塞一份进去就是"同一个库被两份加载"的经典故障。
 */
public final class ForeignRuntime {

    /** 解析结果。 */
    public record Found(String platform, String sourceVersion, List<Path> jars, String note) {
        public boolean usable() {
            return !jars.isEmpty();
        }

        public String summary() {
            if (usable()) {
                return platform + " 运行时 " + jars.size() + " 个（借自 " + sourceVersion + "）";
            }
            return platform + " 运行时缺失：" + note;
        }
    }

    private ForeignRuntime() {
    }

    /**
     * 这个包里有没有"FML 引导本体"。
     *
     * <p>这条判断是整套时机安排的支点，值得写清楚：对方平台的 API 类
     * （例如 {@code IRegistryExtension}）必须在原版类被定义的<b>那一刻</b>就能被解析到，
     * 所以它得早进类路径；但 FML 的引导类一旦早早可见，跨端模组里的 Mixin 插件
     * 就会"找得到就去用"，在平台还没初始化时抛 {@code There is no current FML Loader} ——
     * 那是启动崩溃，而不是一句警告（找不到类时 Mixin 只记一笔就继续）。
     * 所以按这条判据把运行时拆成"早登记"与"驱动时才登记"两拨。
     */
    public static boolean carriesFml(Path jar) {
        if (jar == null || !Files.isRegularFile(jar)) {
            return false;
        }
        try (com.fusionml.util.FastZip zip = com.fusionml.util.FastZip.open(jar)) {
            return zip.entry("net/neoforged/fml/loading/FMLLoader.class") != null
                    || zip.entry("net/minecraftforge/fml/loading/FMLLoader.class") != null;
        } catch (Throwable t) {
            // 读不了就按"含 FML"处理：宁可晚一点登记，也不要冒启动崩溃的风险。
            return true;
        }
    }

    /** 早登记的那一拨：不含 FML 引导本体的运行时包。 */
    public static List<Path> earlySubset(List<Path> jars) {
        List<Path> out = new ArrayList<>(jars.size());
        for (Path p : jars) {
            if (!carriesFml(p)) {
                out.add(p);
            }
        }
        return out;
    }

    /** 驱动时才登记的那一拨：含 FML 引导本体的运行时包。 */
    public static List<Path> lateSubset(List<Path> jars) {
        List<Path> out = new ArrayList<>(jars.size());
        for (Path p : jars) {
            if (carriesFml(p)) {
                out.add(p);
            }
        }
        return out;
    }

    /** 手动指定优先：{@code -Dfusion.foreignRuntime.jars=a.jar;b.jar}。 */
    private static final String PROP_JARS = "fusion.foreignRuntime.jars";

    /**
     * 解析某一端的运行时。
     *
     * @param instance   当前实例（用来找到同一份 .minecraft 下的其它版本）
     * @param platform   {@code neoforge} / {@code forge}
     * @param mcKey      当前 Minecraft 版本键，例如 {@code 26.3}
     */
    public static Found resolve(McInstance instance, String platform, String mcKey,
                                Consumer<String> log) {
        String override = System.getProperty(PROP_JARS, "");
        if (!override.isBlank()) {
            List<Path> jars = new ArrayList<>(4);
            for (String part : override.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                if (!part.isBlank()) {
                    jars.add(Path.of(part.trim()));
                }
            }
            return new Found(platform, "手动指定", jars, "");
        }
        String marker = "neoforge".equals(platform)
                ? "net.neoforged.fancymodloader:loader:" : "net.minecraftforge:forge:";
        try {
            for (String id : instance.versionIds()) {
                if (mcKey != null && !mcKey.isBlank() && !id.startsWith(mcKey)) {
                    continue;
                }
                McInstance.Effective eff;
                try {
                    eff = instance.resolve(id);
                } catch (Throwable t) {
                    continue;
                }
                boolean isTarget = false;
                for (McVersion.Library lib : eff.libraries) {
                    if (lib.name() != null && lib.name().startsWith(marker)) {
                        isTarget = true;
                        break;
                    }
                }
                if (!isTarget) {
                    continue;
                }
                List<Path> jars = pick(instance, eff, platform, log);
                if (jars.isEmpty()) {
                    return new Found(platform, id, List.of(),
                            "找到 " + id + "，但它需要的运行时包一个都没落地");
                }
                return new Found(platform, id, jars, "");
            }
        } catch (Throwable t) {
            return new Found(platform, "?", List.of(), "扫描本机版本时出错：" + t);
        }
        return new Found(platform, "?", List.of(),
                "本机没有安装 " + mcKey + " 的 " + platform
                        + " 端；装一个同版本的那一端，或把它的 jar 列表写进 "
                        + "-D" + PROP_JARS);
    }

    /** 从一份版本定义里挑出我们真正需要的 jar。 */
    private static List<Path> pick(McInstance instance, McInstance.Effective eff, String platform,
                                   Consumer<String> log) {
        // 只要 API / 事件总线 / 元数据这几类，其余交给宿主平台已有的库。
        String[] wanted = "neoforge".equals(platform)
                ? new String[]{"net.neoforged.fancymodloader:loader:", "net.neoforged:bus:",
                               "net.jodah:typetools:", "org.apache.maven:maven-artifact:",
                               // mergetool 的 api 包里带着 net.neoforged.api.distmarker.Dist ——
                               // 它是 FML 大量签名里的类型（例如 FMLLoader 的构造参数），
                               // 少了它连"建立 FML 全局状态"这一步都做不了。
                               "net.neoforged:mergetool:"}
                : new String[]{"net.minecraftforge:forge:", "net.minecraftforge:fmlloader:",
                               "net.minecraftforge:bus:", "net.jodah:typetools:",
                               "org.apache.maven:maven-artifact:"};
        LinkedHashSet<Path> out = new LinkedHashSet<>();
        for (McVersion.Library lib : eff.libraries) {
            if (lib.name() == null) {
                continue;
            }
            boolean hit = false;
            for (String w : wanted) {
                if (lib.name().startsWith(w)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                continue;
            }
            Path p = instance.localLibraryPath(lib);
            if (p != null && Files.isRegularFile(p)) {
                out.add(p);
            }
        }
        // NeoForge 的主 jar（universal）不在版本定义的 libraries 里 ——
        // 它是启动时由它自己的引导器按坐标找到的，所以我们按目录规律自己找。
        out.addAll(findNeoForgeJar(instance, eff, platform));
        return new ArrayList<>(out);
    }

    private static List<Path> findNeoForgeJar(McInstance instance, McInstance.Effective eff,
                                              String platform) {
        if (!"neoforge".equals(platform)) {
            return List.of();
        }
        Path dir = instance.librariesDir().resolve("net/neoforged/neoforge");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        // 版本前缀取自这一端的 fancymodloader 版本所在的目录名（26.2 / 26.3 …），
        // 再在其中挑最新的一份；找不到就退化为"所有候选里最新"。
        List<Path> candidates = new ArrayList<>(4);
        try (DirectoryStream<Path> versions = Files.newDirectoryStream(dir)) {
            for (Path v : versions) {
                if (!Files.isDirectory(v)) {
                    continue;
                }
                try (DirectoryStream<Path> files = Files.newDirectoryStream(v, "neoforge-*.jar")) {
                    for (Path f : files) {
                        String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (n.contains("universal")) {
                            candidates.add(f);
                        }
                    }
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        candidates.sort(Comparator.comparing(p -> p.getParent().getFileName().toString()));
        if (candidates.isEmpty()) {
            return List.of();
        }
        return List.of(candidates.get(candidates.size() - 1));
    }
}

