package com.fusionml.meta;

import com.fusionml.util.FastZip;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 元数据读取总入口：识别 jar 里存在哪些端的元数据，分派给对应的原生读取器。
 *
 * <p>一个 jar 可以同时携带多端元数据（现实中确实存在这种"多端同包"的模组）。
 * 本读取器会为每一种识别到的格式各产出一条 {@link ModMetadata}，而不是挑一个、
 * 丢掉其他的 —— 这正是"三端同时原生加载"的基础。
 */
public final class MetadataReader {

    public record Result(List<ModMetadata> mods, List<String> warnings) {
    }

    private MetadataReader() {
    }

    public static Result read(FastZip zip, int jarIndex) throws IOException {
        return read(zip, jarIndex, false);
    }

    /**
     * @param nested 这条元数据是否来自嵌套 jar（外层包内嵌的子包）
     */
    public static Result read(FastZip zip, int jarIndex, boolean nested) throws IOException {
        String jarName = zip.path().getFileName().toString();
        List<String> warnings = new ArrayList<>(2);
        List<ModMetadata> mods = new ArrayList<>(2);

        FastZip.Entry manifest = zip.entry("META-INF/MANIFEST.MF");
        Map<String, String> manifestAttrs = manifest == null ? Map.of()
                : manifestAttributes(zip.read(manifest));
        String manifestVersion = manifestVersion(manifestAttrs);

        FastZip.Entry fabricEntry = zip.entry(ModFormat.FABRIC.metadataPath());
        if (fabricEntry != null) {
            ModMetadata m = FabricMetadataReader.read(zip.read(fabricEntry), jarName, jarIndex, warnings);
            if (m != null && !m.id.isBlank()) {
                mods.add(m);
            } else if (m != null) {
                warnings.add(jarName + "：fabric.mod.json 缺少 id，已忽略该端元数据");
            }
        }

        FastZip.Entry neoEntry = zip.entry(ModFormat.NEOFORGE.metadataPath());
        FastZip.Entry forgeEntry = zip.entry(ModFormat.FORGE.metadataPath());
        if (neoEntry != null) {
            mods.addAll(ForgeMetadataReader.read(zip.read(neoEntry), ModFormat.NEOFORGE, jarName,
                    jarIndex, manifestVersion, warnings));
        }
        if (forgeEntry != null) {
            byte[] bytes = zip.read(forgeEntry);
            // 1.20.4 及更早的 NeoForge 也用 mods.toml。用内容特征区分，而不是靠猜。
            ModFormat format = looksLikeNeoForge(bytes) ? ModFormat.NEOFORGE : ModFormat.FORGE;
            if (format == ModFormat.NEOFORGE) {
                warnings.add(jarName + "：mods.toml 看起来是旧版 NeoForge 格式，已按 NeoForge 原生处理");
            }
            mods.addAll(ForgeMetadataReader.read(bytes, format, jarName, jarIndex,
                    manifestVersion, warnings));
        }

        for (ModMetadata m : mods) {
            m.jarBytes(zip.fileSize());
            m.nested = nested;
        }
        // MANIFEST 的 MixinConfigs 属性：Forge/NeoForge 生态里这才是主流的声明方式。
        // 实测证据：Iceberg-26.2-forge 的 mods.toml 里一条 [[mixins]] 都没有，

        // 现象是"模组在列表里、Mixin 却完全没生效"，很难往"元数据没读全"上想。
        String manifestConfigs = manifestAttrs.get("mixinconfigs");
        if (manifestConfigs != null && !manifestConfigs.isBlank()) {
            for (ModMetadata m : mods) {
                if (m.format() == ModFormat.FABRIC) {
                    // Fabric 的规范是写在 fabric.mod.json 的 mixins 字段里，
                    // 这个属性对 Fabric 端没有约定含义，不去猜。
                    continue;
                }
                for (String cfg : manifestConfigs.split("[,\\s]+")) {
                    String c = cfg.trim();
                    if (!c.isEmpty() && !m.mixinConfigs.contains(c)) {
                        m.mixinConfigs.add(c);
                    }
                }
            }
        }
        if (mods.isEmpty()) {
            // 不是错误：jar 可能只是前置库或资源包。静默跳过即可，但要让调用方能区分。
            return new Result(List.of(), List.of());
        }
        return new Result(mods, warnings);
    }

    private static boolean looksLikeNeoForge(byte[] tomlBytes) {
        String text = new String(tomlBytes, StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT);
        return text.contains("neoforge") || text.contains("neoForge".toLowerCase(java.util.Locale.ROOT))
                || text.contains("[[mixins]]") || text.contains("featureflags");
    }

    /**
     * 自己解析 MANIFEST.MF（含 72 字节折行续行的处理）。
     * 只为了取一个版本号，不值得动用整个 jar 框架。
     */
    static Map<String, String> manifestAttributes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return Map.of();
        }
        String text = new String(bytes, StandardCharsets.UTF_8);

        Map<String, String> out = new java.util.LinkedHashMap<>(8);
        String current = null;
        StringBuilder value = new StringBuilder();
        java.util.function.BiConsumer<String, String> flush = (k, v) -> {
            if (k != null) {
                out.put(k.toLowerCase(java.util.Locale.ROOT), v.trim());
            }
        };
        for (String rawLine : text.split("\r\n|\n|\r")) {
            if (rawLine.isEmpty()) {
                continue;
            }
            if (rawLine.charAt(0) == ' ') {

                if (current != null) {
                    value.append(rawLine, 1, rawLine.length());
                }
                continue;
            }
            flush.accept(current, value.toString());
            int colon = rawLine.indexOf(':');
            if (colon < 0) {
                current = null;
                value.setLength(0);
                continue;
            }
            current = rawLine.substring(0, colon).trim();
            value.setLength(0);
            value.append(rawLine.substring(colon + 1).trim());
        }
        flush.accept(current, value.toString());
        return out;
    }

    /** 版本号的取值顺序与官方启动器一致：实现版本优先，其次规范版本。 */
    static String manifestVersion(Map<String, String> attrs) {
        String v = attrs.get("implementation-version");
        if (v == null || v.isBlank()) {
            v = attrs.get("specification-version");
        }
        return v == null || v.isBlank() ? null : v;
    }
}
