package com.fusionml.install;

import com.fusionml.mc.McDownloader;
import com.fusionml.mc.McInstance;
import com.fusionml.mc.McVersion;
import com.fusionml.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从零装出一个<b>完整可启动的实例</b>（连 Minecraft 本体一起）。
 *
 * <h2>为什么要有这个类</h2>
 *
 * <p>以前的安装器是"往玩家已经装好的实例里加东西"：玩家必须先用官方启动器
 * （或 PCL/HMCL）把某个版本装好，我们才谈得上安装。这对新手是最难的一步 ——
 * 他得先知道"要装哪个版本、装在哪、用什么 Java"，才能开始用 FusionML。
 *
 * <p>Fabric 官方安装器不是这么做的：它<b>自己把 Minecraft 拉下来</b>。
 * 本类补上同样的能力。装完之后那个目录是<b>自包含的</b>：
 * 版本 jar、全部 libraries、assets、natives 都齐了，直接就能启动。
 *
 * <h2>装出来的目录结构</h2>
 * <pre>
 * &lt;目标目录&gt;/
 *   versions/&lt;版本&gt;/&lt;版本&gt;.jar         ← 游戏本体
 *   versions/&lt;版本&gt;/&lt;版本&gt;.json        ← 原版版本定义
 *   libraries/…                        ← 全部依赖库（按 Mojang 的路径规则落位）
 *   assets/indexes/&lt;assetIndex&gt;.json   ← 资源索引
 *   assets/objects/xx/xxxx…            ← 资源本体（按 sha1 分片存放）
 *   .fusionml/                         ← 我们的状态目录
 * </pre>
 *
 * <p>下载一律走 {@link McDownloader#ensure}：它<b>先查本地文件是否已通过 sha1 校验</b>，
 * 通过就复用，不重复下载。所以重复安装是廉价的，中断后重跑也能接着来。
 */
public final class FullInstance {

    /** 进度输出。 */
    public interface Log {
        void line(String s);
    }

    /** 装机结果。 */
    public record Result(Path gameDir, Path versionJson, Path clientJar,
                         int librariesDownloaded, int librariesReused,
                         int assetsDownloaded, int assetsReused) {
    }

    private FullInstance() {
    }

    /**
     * 装一个完整实例。
     *
     * @param gameDir    目标目录（会被创建；不需要预先存在）
     * @param versionId  要装的 Minecraft 版本 id（如 {@code 26.3}）
     * @param withAssets 是否下载 assets（约 1.4 GB）。首次装机必须下；
     *                   只想验证启动链路时可以跳过
     */
    public static Result install(Path gameDir, String versionId, boolean withAssets,
                                 McDownloader dl, Log log) throws IOException {
        Files.createDirectories(gameDir);
        log.line("目标目录：" + gameDir.toAbsolutePath());

        // 1) 版本清单 → 版本 json 地址
        AutoFetch.VersionInfo info = AutoFetch.resolve(dl, versionId, log::line);
        if (info == null) {
            throw new IOException("版本清单里找不到 " + versionId);
        }
        log.line("版本 " + info.id() + "（" + info.type() + "，发布于 " + info.releaseTime() + "）");

        // 2) 版本 json 全文（含 libraries 与 assetIndex 的完整描述）
        byte[] versionJsonBytes = dl.fetch(info.jsonUrl());
        McVersion version = McVersion.parse(versionJsonBytes);
        log.line("依赖库 " + version.libraries.size() + " 个，资源索引 " + version.assetIndexId);

        Path versionsDir = gameDir.resolve("versions").resolve(versionId);
        Files.createDirectories(versionsDir);

        // 3) 客户端 jar
        Path clientJar = versionsDir.resolve(versionId + ".jar");
        boolean jarNew = dl.ensure(clientJar, info.clientUrl(), info.clientSha1(),
                (what, done, total) -> log.line("  客户端 jar "
                        + (total > 0 ? (done * 100 / total) + "%" : done + " B")));
        if (jarNew) {
            log.line("已下载客户端 jar（" + (info.clientSize() / 1048576) + " MB）");
        } else {
            log.line("客户端 jar 已存在且校验通过（复用）");
        }

        // 4) 原版版本定义原样落盘。
        //    我们要在它基础上写"自己"的版本（继承 + javaagent），所以先留一份原始定义。
        Path vanillaJson = versionsDir.resolve(versionId + ".json");
        Files.write(vanillaJson, versionJsonBytes);

        // 5) 全部依赖库
        int libNew = 0;
        int libOld = 0;
        Path libraries = gameDir.resolve("libraries");
        for (McVersion.Library lib : version.libraries) {
            if (lib.url() == null || lib.url().isBlank()) {
                continue;
            }
            Path target = libraries.resolve(lib.path());
            if (dl.ensure(target, lib.url(), lib.sha1(), McDownloader.Progress.SILENT)) {
                libNew++;
            } else {
                libOld++;
            }
        }
        log.line("依赖库：新下载 " + libNew + " 个，复用 " + libOld + " 个");

        // 6) assets
        int asNew = 0;
        int asOld = 0;
        if (withAssets) {
            Path indexes = gameDir.resolve("assets").resolve("indexes");
            Files.createDirectories(indexes);
            Path indexFile = indexes.resolve(version.assetIndexId + ".json");
            if (dl.ensure(indexFile, version.assetIndexUrl, version.assetIndexSha1,
                    McDownloader.Progress.SILENT)) {
                log.line("已下载资源索引（" + (version.assetIndexSize / 1048576) + " MB）");
            }
            Map<String, Object> index = Json.parseObject(Files.readAllBytes(indexFile));
            Map<String, Object> objects = Json.obj(index.get("objects"));
            Path objectsDir = gameDir.resolve("assets").resolve("objects");
            int total = objects.size();
            int done = 0;
            for (Map.Entry<String, Object> e : objects.entrySet()) {
                Map<String, Object> o = Json.obj(e.getValue());
                String hash = Json.str(o, "hash", "");
                if (hash.length() < 2) {
                    continue;
                }
                String sub = hash.substring(0, 2);
                Path target = objectsDir.resolve(sub).resolve(hash);
                String url = "https://resources.download.minecraft.net/" + sub + "/" + hash;
                if (dl.ensure(target, url, hash, McDownloader.Progress.SILENT)) {
                    asNew++;
                } else {
                    asOld++;
                }
                done++;
                if (done % 500 == 0) {
                    log.line("  资源 " + done + "/" + total);
                }
            }
            log.line("资源文件：新下载 " + asNew + " 个，复用 " + asOld + " 个");
        } else {
            log.line("（按要求跳过资源文件下载 —— 首次装机请务必下载，否则没有音效与语言）");
        }

        return new Result(gameDir, vanillaJson, clientJar, libNew, libOld, asNew, asOld);
    }

    /**
     * 写"我们自己的"版本定义 —— 继承刚装好的原版版本，只追加 javaagent。
     *
     * <p>与 {@code FusionInstaller.injectIntoHostVersion} 的目标一致：
     * 让启动器启动时自动带上加载器，而 <b>mainClass 与 libraries 都从原版继承</b>
     * （写死了就等于顶掉平台启动器，模组不加载、平台参数全丢）。
     */
    public static Path writeOwnVersion(Path gameDir, String ownId, String vanillaId,
                                       Path loaderJar) throws IOException {
        Path dir = gameDir.resolve("versions").resolve(ownId);
        Files.createDirectories(dir);
        Map<String, Object> version = new LinkedHashMap<>();
        version.put("id", ownId);
        version.put("time", java.time.Instant.now().toString());
        version.put("releaseTime", java.time.Instant.now().toString());
        version.put("type", "release");
        version.put("inheritsFrom", vanillaId);
        String rel = gameDir.relativize(loaderJar).toString().replace('\\', '/');
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("jvm", List.of(
                "-javaagent:${game_directory}/" + rel,
                "-Dfusion.gameDir=${game_directory}",
                "-Dfusion.version=" + ownId,
                "-Dfusion.client=true"));
        version.put("arguments", arguments);
        Path json = dir.resolve(ownId + ".json");
        Files.writeString(json, Json.write(version), StandardCharsets.UTF_8);
        return json;
    }

    /** 让 {@link McInstance} 能用（它要求目录里有 versions/ 或 libraries/）。 */
    public static McInstance open(Path gameDir) {
        return McInstance.open(gameDir);
    }
}
