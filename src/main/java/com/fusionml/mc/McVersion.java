package com.fusionml.mc;

import com.fusionml.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一份 Minecraft 版本定义的实质内容（从版本 JSON 解析而来）。
 *
 * <p>只取"把它跑起来"所必需的部分：主类、Java 版本要求、依赖库、原生库、资源索引、
 * 客户端 jar 的下载信息。刻意不解析 {@code arguments} 里那些与启动器强绑定的占位符
 * （{@code ${auth_player_name}} 之类），那些由 {@code LaunchPlan} 自己填。
 */
public final class McVersion {

    /** 一个依赖库；{@code natives} 非空表示它同时提供原生库。 */
    public record Library(String name, String path, String url, String sha1,
                          Map<String, String> natives, Object rules) {
        @Override
        public String toString() {
            return name;
        }
    }

    public final String id;
    public final String mainClass;
    public final int javaMajor;
    public final String javaComponent;
    public final String type;
    public final List<Library> libraries = new ArrayList<>();
    public final String assetIndexId;
    public final String assetIndexUrl;
    public final String assetIndexSha1;
    public final long assetIndexSize;
    public final String clientUrl;
    public final String clientSha1;
    public final long clientSize;
    public final Map<String, Object> raw;

    private McVersion(String id, String mainClass, int javaMajor, String javaComponent, String type,
                      String assetIndexId, String assetIndexUrl, String assetIndexSha1,
                      long assetIndexSize, String clientUrl, String clientSha1, long clientSize,
                      Map<String, Object> raw) {
        this.id = id;
        this.mainClass = mainClass;
        this.javaMajor = javaMajor;
        this.javaComponent = javaComponent;
        this.type = type;
        this.assetIndexId = assetIndexId;
        this.assetIndexUrl = assetIndexUrl;
        this.assetIndexSha1 = assetIndexSha1;
        this.assetIndexSize = assetIndexSize;
        this.clientUrl = clientUrl;
        this.clientSha1 = clientSha1;
        this.clientSize = clientSize;
        this.raw = raw;
    }

    public static McVersion parse(byte[] versionJson) {
        Map<String, Object> root = Json.parseObject(versionJson);
        Map<String, Object> java = Json.obj(root.get("javaVersion"));
        Map<String, Object> downloads = Json.obj(root.get("downloads"));
        Map<String, Object> client = Json.obj(downloads.get("client"));
        Map<String, Object> assets = Json.obj(root.get("assetIndex"));

        McVersion v = new McVersion(
                Json.str(root, "id", "unknown"),
                Json.str(root, "mainClass", "net.minecraft.client.main.Main"),
                (int) num(java.get("majorVersion")),
                Json.str(java, "component", ""),
                Json.str(root, "type", "release"),
                Json.str(assets, "id", ""),
                Json.str(assets, "url", ""),
                Json.str(assets, "sha1", ""),
                (long) num(assets.get("totalSize")),
                Json.str(client, "url", ""),
                Json.str(client, "sha1", ""),
                (long) num(client.get("size")),
                root);

        for (Object o : Json.arr(root.get("libraries"))) {
            Map<String, Object> lib = Json.obj(o);
            Map<String, Object> downloadsNode = Json.obj(lib.get("downloads"));
            Map<String, Object> art = Json.obj(downloadsNode.get("artifact"));
            Map<String, String> natives = new LinkedHashMap<>();
            if (lib.get("natives") instanceof Map<?, ?> nm) {
                String archBits = "x86".equals(McRules.currentOsArch()) ? "32" : "64";
                for (Map.Entry<String, Object> e : Json.obj(nm).entrySet()) {
                    natives.put(e.getKey(), String.valueOf(e.getValue()).replace("${arch}", archBits));
                }
            }
            if (art.isEmpty() && natives.isEmpty()) {
                continue;
            }
            v.libraries.add(new Library(
                    Json.str(lib, "name", ""),
                    Json.str(art, "path", ""),
                    Json.str(art, "url", ""),
                    Json.str(art, "sha1", ""),
                    natives,
                    lib.get("rules")));
        }
        return v;
    }

    /** 当前系统适用的库（已按 rules 过滤）。 */
    public List<Library> applicableLibraries(Map<String, Boolean> features) {
        List<Library> out = new ArrayList<>(libraries.size());
        for (Library lib : libraries) {
            if (McRules.allows(lib.rules(), features)) {
                out.add(lib);
            }
        }
        return out;
    }

    private static double num(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        return o == null ? 0 : Double.parseDouble(o.toString());
    }

    @Override
    public String toString() {
        return id + "（Java " + javaMajor + "，库 " + libraries.size() + " 个，主类 " + mainClass + "）";
    }
}
