package com.fusionml.install;

import com.fusionml.mc.McDownloader;
import com.fusionml.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 命令行一键安装：**自动拉取**需要的游戏文件，不需要用户先去启动器里点一遍。
 *
 * <h2>为什么要做这个</h2>
 *
 * <p>原来安装器的前提是"用户已经用官方启动器把版本装好了"。这在客户端整合包里通常成立，
 * 但在**服务端**上根本不成立 —— 一台新买的 VPS 上什么都没有，
 * 而 Fabric / Quilt 的安装器恰恰是从"空目录"开始把服务端拉下来的。
 * 对照它们的做法，这里补上同样的能力。
 *
 * <h2>做法</h2>
 *
 * <p>Mojang 官方有一套公开的版本清单，分两级：
 * <ol>
 *   <li>{@code version_manifest_v2.json} —— 所有版本的列表（含每个版本的元数据地址）；</li>
 *   <li>每个版本自己的 json —— 里面有客户端与服务端 jar 的下载地址与 SHA1。</li>
 * </ol>
 *
 * <p>所以"自动拉取"就是顺着这两级走一遍。下载与校验复用 {@link McDownloader}
 * （它已经会"本地已有且校验通过就跳过"，重复执行不会白下几百 MB）。
 *
 * <h2>网络不可用时怎么办</h2>
 *
 * <p><b>明确报错，不假装成功。</b>这是刻意的：一个"装完了但少文件"的目录比一次
 * 清晰的失败要难查得多 —— 用户会在启动时看到一堆与真正原因无关的报错。
 */
public final class AutoFetch {

    private AutoFetch() {
    }

    /** 官方版本清单地址。 */
    public static final String MANIFEST_URL =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    /** 一个版本的可下载信息。 */
    public record VersionInfo(String id, String type, String releaseTime,
                              String clientUrl, String clientSha1, long clientSize,
                              String serverUrl, String serverSha1, long serverSize) {
    }

    public interface Log {
        void line(String s);
    }

    /**
     * 从官方清单里查一个版本。
     *
     * @param versionId 版本号（例如 {@code 26.3}）；{@code latest} / {@code latest-snapshot}
     *                  可以取最新正式版 / 最新快照
     * @return 版本信息；找不到返回 {@code null}
     */
    public static VersionInfo resolve(McDownloader dl, String versionId, Log log) throws IOException {
        log.line("查询版本清单：" + MANIFEST_URL);
        byte[] manifest = dl.fetch(MANIFEST_URL);
        Map<String, Object> root = Json.parseObject(manifest);

        String id = versionId;
        if ("latest".equalsIgnoreCase(versionId) || "latest-release".equalsIgnoreCase(versionId)) {
            id = Json.str(nested(root, "latest"), "release", "");
            log.line("latest → 最新正式版 " + id);
        } else if ("latest-snapshot".equalsIgnoreCase(versionId)) {
            id = Json.str(nested(root, "latest"), "snapshot", "");
            log.line("latest-snapshot → 最新快照 " + id);
        }

        String url = null;
        String type = null;
        String releaseTime = null;
        for (Object o : Json.arr(root.get("versions"))) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> vm = (Map<String, Object>) m;
            if (id.equals(Json.str(vm, "id", ""))) {
                url = Json.str(vm, "url", "");
                type = Json.str(vm, "type", "");
                releaseTime = Json.str(vm, "releaseTime", "");
                break;
            }
        }
        if (url == null || url.isBlank()) {
            log.line("清单里没有版本 " + id);
            return null;
        }
        log.line("找到版本 " + id + "（" + type + "，" + releaseTime + "）");

        Map<String, Object> vj = Json.parseObject(dl.fetch(url));
        Map<String, Object> downloads = nested(vj, "downloads");
        Map<String, Object> client = nested(downloads, "client");
        Map<String, Object> server = nested(downloads, "server");
        return new VersionInfo(id,
                type, releaseTime,
                Json.str(client, "url", ""), Json.str(client, "sha1", ""),
                numOf(client, "size"),
                Json.str(server, "url", ""), Json.str(server, "sha1", ""),
                numOf(server, "size"));
    }

    private static long numOf(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    public record Fetched(VersionInfo info, Path jar, boolean downloaded) {
    }

    /**
     * 把一个版本的服务端 / 客户端 jar 拉到指定目录。
     *
     * @param side {@code "server"} 或 {@code "client"}
     */
    public static Fetched fetch(McDownloader dl, String versionId, String side,
                                Path targetDir, Log log) throws IOException {
        VersionInfo info = resolve(dl, versionId, log);
        if (info == null) {
            throw new IOException("官方清单里找不到版本 " + versionId
                    + "。可以先用 --list-versions 看有哪些可选。");
        }
        boolean server = !"client".equalsIgnoreCase(side);
        String url = server ? info.serverUrl() : info.clientUrl();
        String sha1 = server ? info.serverSha1() : info.clientSha1();
        long size = server ? info.serverSize() : info.clientSize();
        if (url == null || url.isBlank()) {
            throw new IOException("版本 " + info.id() + " 没有"
                    + (server ? "服务端" : "客户端") + " jar 可下载"
                    + "（有些很老的版本确实不提供）。");
        }
        Files.createDirectories(targetDir);

        String fileName = server ? "server.jar" : info.id() + ".jar";
        Path target = targetDir.resolve(fileName);

        log.line("下载 " + (server ? "服务端" : "客户端") + " jar："
                + (size > 0 ? (size / 1024 / 1024) + " MB" : "大小未知")
                + " → " + target);
        long t0 = System.nanoTime();
        boolean downloaded;
        try {
            downloaded = dl.ensure(target, url, sha1, (what, done, total) -> {
                if (total > 0) {
                    int pct = (int) (done * 100 / total);
                    // 每 10% 报一次，避免刷屏又不至于没反馈。
                    if (pct / 10 != (done - 1) * 100L / Math.max(1, total) / 10) {
                        log.line("  " + pct + "% (" + (done / 1024 / 1024) + "/"
                                + (total / 1024 / 1024) + " MB)");
                    }
                }
            });
        } catch (IOException e) {
            throw new IOException("下载失败：" + e.getMessage()
                    + "\n  可能原因：没有网络、被防火墙拦截、或该版本已被官方移除。"
                    + "\n  你可以手动把 jar 放到 " + target + " 再重跑，本工具会自动跳过下载。", e);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        log.line((downloaded ? "下载完成" : "本地已有且校验通过，直接复用")
                + "（" + ms + " ms，SHA1 校验通过）");
        return new Fetched(info, target, downloaded);
    }

    /**
     * 列出清单里的版本（供 {@code --list-versions} 用）。
     *
     * @param limit 最多列多少个（0 表示全部）
     */
    public static List<String> listVersions(McDownloader dl, int limit, boolean snapshots) {
        List<String> out = new ArrayList<>();
        try {
            Map<String, Object> root = Json.parseObject(dl.fetch(MANIFEST_URL));
            for (Object o : Json.arr(root.get("versions"))) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> vm = (Map<String, Object>) m;
                String type = Json.str(vm, "type", "");
                if (!snapshots && !"release".equals(type)) {
                    continue;
                }
                out.add(Json.str(vm, "id", "") + "  [" + type + "]  "
                        + Json.str(vm, "releaseTime", ""));
                if (limit > 0 && out.size() >= limit) {
                    break;
                }
            }
        } catch (Throwable t) {
            out.add("读取清单失败：" + t);
        }
        return out;
    }

    private static Map<String, Object> nested(Map<String, Object> m, String key) {
        if (m == null) {
            return Map.of();
        }
        Object v = m.get(key);
        if (v instanceof Map<?, ?> mm) {
            @SuppressWarnings("unchecked")
            Map<String, Object> out = (Map<String, Object>) mm;
            return out;
        }
        return Map.of();
    }
}
