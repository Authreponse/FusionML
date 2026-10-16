package com.fusionml.mc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minecraft 素材的本地仓库。
 *
 * <p>目录布局刻意与官方启动器保持一致（{@code libraries/}、{@code assets/}、{@code versions/}），
 * 这样两边可以互相复用已经下好的文件 —— 你原本为了玩游戏下载的那几个 GB 不必再来一遍。
 *
 * <pre>
 *   &lt;根&gt;/versions/&lt;版本&gt;/&lt;版本&gt;.json
 *   &lt;根&gt;/versions/&lt;版本&gt;/&lt;版本&gt;.jar        （客户端）
 *   &lt;根&gt;/libraries/&lt;Maven 路径&gt;
 *   &lt;根&gt;/assets/indexes/&lt;id&gt;.json
 *   &lt;根&gt;/assets/objects/&lt;前两位&gt;/&lt;sha1&gt;
 * </pre>
 */
public final class McCache {

    private static final String VERSION_MANIFEST = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json";

    private final Path root;
    private final McDownloader downloader = new McDownloader();
    private Map<String, Object> manifest;

    public McCache(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    public McDownloader downloader() {
        return downloader;
    }

    /** 取版本索引（带内存缓存）。 */
    private Map<String, Object> manifest() throws IOException {
        if (manifest == null) {
            manifest = com.fusionml.util.Json.parseObject(downloader.fetch(VERSION_MANIFEST));
        }
        return manifest;
    }

    /** 列出正式版（release）版本号，从新到旧。 */
    public List<String> releaseVersions() throws IOException {
        List<String> out = new ArrayList<>();
        for (Object o : com.fusionml.util.Json.arr(manifest().get("versions"))) {
            Map<String, Object> v = com.fusionml.util.Json.obj(o);
            if ("release".equals(com.fusionml.util.Json.str(v, "type"))) {
                out.add(com.fusionml.util.Json.str(v, "id"));
            }
        }
        return out;
    }

    public String latestRelease() throws IOException {
        Map<String, Object> latest = com.fusionml.util.Json.obj(manifest().get("latest"));
        return com.fusionml.util.Json.str(latest, "release", "");
    }

    /** 版本元数据 JSON 的本地路径。 */
    public Path versionJsonPath(String versionId) {
        return root.resolve("versions").resolve(versionId).resolve(versionId + ".json");
    }

    public Path versionJarPath(String versionId) {
        return root.resolve("versions").resolve(versionId).resolve(versionId + ".jar");
    }

    /**
     * 确保版本 JSON 与客户端 jar 都在本地（并校验 SHA1）。
     * 若本地已有官方启动器下好的同名文件，会被直接复用。
     */
    public McVersion ensureVersion(String versionId, McDownloader.Progress progress)
            throws IOException {
        String url = null;
        for (Object o : com.fusionml.util.Json.arr(manifest().get("versions"))) {
            Map<String, Object> v = com.fusionml.util.Json.obj(o);
            if (versionId.equals(com.fusionml.util.Json.str(v, "id"))) {
                url = com.fusionml.util.Json.str(v, "url");
                break;
            }
        }
        if (url == null) {
            throw new IOException("版本索引里没有 " + versionId
                    + "（可用 --mc-versions 查看正式版列表）");
        }
        Path jsonPath = versionJsonPath(versionId);
        if (!Files.isRegularFile(jsonPath)) {
            Files.createDirectories(jsonPath.getParent());
            byte[] data = downloader.fetch(url);
            Files.write(jsonPath, data);
        }
        McVersion version = McVersion.parse(Files.readAllBytes(jsonPath));
        if (version.clientUrl != null && !version.clientUrl.isBlank()) {
            downloader.ensure(versionJarPath(versionId), version.clientUrl, version.clientSha1, progress);
        }
        return version;
    }

    public Path libraryPath(McVersion.Library lib) {
        String path = lib.path();
        if (path == null || path.isBlank()) {

            String[] parts = lib.name().split(":");
            if (parts.length >= 3) {
                path = parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/"
                        + parts[1] + "-" + parts[2] + ".jar";
            }
        }
        return root.resolve("libraries").resolve(path);
    }

    public List<Path> ensureLibraries(McVersion version, Map<String, Boolean> features,
                                      McDownloader.Progress progress) throws IOException {
        List<Path> out = new ArrayList<>();
        boolean first = true;
        for (McVersion.Library lib : version.applicableLibraries(features)) {
            Path jar = libraryPath(lib);
            if (!Files.isRegularFile(jar) && lib.url() != null && !lib.url().isBlank()) {
                downloader.ensure(jar, lib.url(), lib.sha1(), first ? progress : McDownloader.Progress.SILENT);
                first = false;
            }
            if (Files.isRegularFile(jar)) {
                out.add(jar);
            }
        }
        return out;
    }

    public Path assetIndexPath(String indexId) {
        return root.resolve("assets").resolve("indexes").resolve(indexId + ".json");
    }

    public Path assetObjectPath(String hash) {
        return root.resolve("assets").resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
    }

    /** 确保资源索引在本地。 */
    public Map<String, Object> ensureAssetIndex(McVersion version) throws IOException {
        Path indexPath = assetIndexPath(version.assetIndexId);
        if (!Files.isRegularFile(indexPath)) {
            Files.createDirectories(indexPath.getParent());
            Files.write(indexPath, downloader.fetch(version.assetIndexUrl));
        }
        return com.fusionml.util.Json.parseObject(Files.readAllBytes(indexPath));
    }

    public long countMissingAssets(Map<String, Object> index) {
        Map<String, Object> objects = com.fusionml.util.Json.obj(index.get("objects"));
        long missing = 0;
        for (Map.Entry<String, Object> e : objects.entrySet()) {
            Map<String, Object> obj = com.fusionml.util.Json.obj(e.getValue());
            String hash = com.fusionml.util.Json.str(obj, "hash", "");
            if (hash.isEmpty()) {
                continue;
            }
            if (!Files.isRegularFile(assetObjectPath(hash))) {
                missing++;
            }
        }
        return missing;
    }

    /** 下载全部资源对象。{@code resourcesUrl} 来自版本索引。 */
    public int downloadAssets(Map<String, Object> index, String resourcesBaseUrl,
                              McDownloader.Progress progress) throws IOException {
        Map<String, Object> objects = com.fusionml.util.Json.obj(index.get("objects"));
        int done = 0;
        List<Map.Entry<String, Object>> entries = new ArrayList<>(objects.entrySet());
        for (Map.Entry<String, Object> e : entries) {
            Map<String, Object> obj = com.fusionml.util.Json.obj(e.getValue());
            String hash = com.fusionml.util.Json.str(obj, "hash", "");
            if (hash.isEmpty()) {
                continue;
            }
            Path target = assetObjectPath(hash);
            if (Files.isRegularFile(target)) {
                continue;
            }
            String url = resourcesBaseUrl + hash.substring(0, 2) + "/" + hash;
            downloader.ensure(target, url, hash, McDownloader.Progress.SILENT);
            if ((++done % 200) == 0) {
                progress.update("资源文件", done, entries.size());
            }
        }
        return done;
    }

    /** 资源下载的基地址，从版本索引里取。 */
    public String resourcesBaseUrl() {
        return "https://resources.download.minecraft.net/";
    }

    public Path nativesDir(String versionId) {
        return root.resolve("natives").resolve(versionId);
    }

    public static Map<String, Boolean> defaultFeatures() {
        Map<String, Boolean> m = new LinkedHashMap<>();
        m.put("is_demo_user", false);
        m.put("has_custom_resolution", false);
        m.put("has_quick_plays_support", false);
        m.put("is_quick_play_singleplayer", false);
        m.put("is_quick_play_multiplayer", false);
        m.put("is_quick_play_realms", false);
        return m;
    }
}
