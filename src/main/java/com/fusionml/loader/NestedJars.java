package com.fusionml.loader;

import com.fusionml.util.FastZip;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

// 这不是"锦上添花"的功能，而是现代模组生态的刚需：Fabric API 一个包里塞了约 150 个
// 不读嵌套 jar，加载器就会把依赖这些子模块的模组全部误报成"缺少必需依赖"——
// 实现选择"提取到缓存目录"而不是"全程在内存里"：
//   * 嵌套 jar 必须能出现在 JVM 的类路径上（游戏启动时就要确定），而类路径只认文件，
//   * 按内容 SHA1 去重并缓存，多个模组打包同一个库时只存一份、只解一次；
//     第二次直接命中缓存，几乎不花时间。
public final class NestedJars {

    // Fabric 用 META-INF/jars/，Forge/NeoForge 用 META-INF/jarjar/。
    private static final String[] PREFIXES = {"META-INF/jars/", "META-INF/jarjar/"};

    // 嵌套层级上限。现实中很少超过两层（Fabric API 是一层），设个上限防止恶意/异常包无限递归。
    private static final int MAX_DEPTH = 3;

    private NestedJars() {
    }

    public record Result(List<Path> jars, int extracted, int reused, List<String> notes) {
        public String summary() {
            if (jars.isEmpty()) {
                return "没有嵌套 jar";
            }
            return "嵌套 jar " + jars.size() + " 个（新解压 " + extracted + "，复用 " + reused + "）";
        }
    }

    public static Result extract(List<Path> outerJars, Path cacheDir, List<String> notes) {
        // 用有序集合去重：多个外层模组常常打包同一个库（例如一堆模组都用同版本的
        // night-config），按内容哈希缓存后它们指向同一个文件；如果不去重，

        java.util.Set<Path> dedup = new java.util.LinkedHashSet<>();
        int[] extracted = {0};
        int[] reused = {0};
        if (cacheDir == null) {
            return new Result(List.of(), 0, 0, notes);
        }
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            notes.add("无法创建嵌套 jar 缓存目录，已跳过嵌套包解析：" + e.getMessage());
            return new Result(List.of(), 0, 0, notes);
        }
        for (Path jar : outerJars) {
            try (FastZip zip = FastZip.open(jar)) {
                collectFrom(zip, jar.getFileName().toString(), cacheDir, dedup, extracted, reused,
                        notes, 0);
            } catch (IOException e) {

            }
        }
        return new Result(new ArrayList<>(dedup), extracted[0], reused[0], notes);
    }

    private static void collectFrom(FastZip zip, String label, Path cacheDir,
                                    java.util.Set<Path> out,
                                    int[] extracted, int[] reused, List<String> notes, int depth)
            throws IOException {
        if (depth >= MAX_DEPTH) {
            return;
        }
        for (FastZip.Entry e : zip.entries()) {
            if (e.isDirectory() || !e.name().endsWith(".jar")) {
                continue;
            }
            if (!hasPrefix(e.name())) {
                continue;
            }
            byte[] data;
            try {
                data = zip.read(e);
            } catch (IOException ex) {
                notes.add(label + "：读取嵌套包 " + e.name() + " 失败 —— " + ex.getMessage());
                continue;
            }
            String hash = sha1(data);
            Path target = cacheDir.resolve(hash + ".jar");
            if (Files.isRegularFile(target)) {
                reused[0]++;
            } else {
                Path part = cacheDir.resolve(hash + ".jar.part");
                Files.write(part, data);
                Files.move(part, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                extracted[0]++;
            }
            out.add(target);

            try (FastZip inner = FastZip.open(data, label + "!" + e.name())) {
                collectFrom(inner, label + "!" + shortName(e.name()), cacheDir, out, extracted,
                        reused, notes, depth + 1);
            } catch (IOException ignored) {
                // 不是合法 zip 就当普通资源忽略
            }
        }
    }

    private static boolean hasPrefix(String name) {
        for (String p : PREFIXES) {
            if (name.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private static String shortName(String entry) {
        int slash = entry.lastIndexOf('/');
        return slash < 0 ? entry : entry.substring(slash + 1);
    }

    private static String sha1(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(new String(data, 0, Math.min(64, data.length),
                    java.nio.charset.StandardCharsets.ISO_8859_1).hashCode()).toLowerCase(Locale.ROOT);
        }
    }

    // 默认缓存目录：优先放在游戏目录的 .fusionml 下（跟着实例走，便于整体清理），
    // 否则退回系统临时目录。
    public static Path defaultCacheDir(Path modsDir) {
        if (modsDir != null && modsDir.getParent() != null) {
            Path parent = modsDir.getParent();
            if (Files.isDirectory(parent.resolve("libraries"))
                    || Files.exists(parent.resolve("versions"))) {
                return parent.resolve(".fusionml").resolve("nested-jars");
            }
        }
        return Path.of(System.getProperty("java.io.tmpdir", "."), "fusionml-nested-jars");
    }
}
