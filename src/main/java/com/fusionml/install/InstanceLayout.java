package com.fusionml.install;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 标准游戏目录结构。
 *
 * <p>这里定义的是玩家和启动器都认的那套目录：{@code mods/}、{@code config/}、{@code lib/}、
 * {@code libraries/}、{@code versions/}、{@code resourcepacks/}、{@code shaderpacks/}、
 * {@code saves/}、{@code logs/}、{@code crash-reports/}，外加加载器自己的 {@code .fusionml/}。
 *
 * <p>两条使用路径共用同一份定义，避免"安装器建了一套、加载器认另一套"这种经典事故：
 * <ul>
 *   <li>安装器：一次性把整套结构释放出来；</li>
 *   <li>加载器启动时：只补齐缺失的目录（<b>只创建缺失项，绝不改动已有文件</b>）。</li>
 * </ul>
 */
public final class InstanceLayout {

    /** 标准子目录（相对游戏根目录）。 */
    public static final String[] STANDARD_DIRS = {
            "mods", "config", "lib", "libraries", "versions",
            "resourcepacks", "shaderpacks", "saves", "logs", "crash-reports",
            ".fusionml",
    };

    /** 加载器本体在游戏目录里的落地位置。 */
    public static final String LOADER_JAR_RELATIVE = "lib/fusionml/fusion-ml.jar";

    /** 加载器自身的状态目录。 */
    public static final String STATE_DIR = ".fusionml";

    /** 安装清单文件名（放在状态目录里，记录本次安装创建/写入了什么）。 */
    public static final String MANIFEST = STATE_DIR + "/install-manifest.txt";

    private InstanceLayout() {
    }

    /**
     * 猜测默认游戏目录。
     *
     * <p>Windows：{@code %APPDATA%\.minecraft}；macOS：{@code ~/Library/Application Support/minecraft}；
     * 其它：{@code ~/.minecraft}。猜不到就返回当前目录，由调用方让用户自己指定。
     */
    public static Path detectDefaultGameDir() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home", ".");
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) {
                return Path.of(appData, ".minecraft");
            }
            return Path.of(home, "AppData", "Roaming", ".minecraft");
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return Path.of(home, "Library", "Application Support", "minecraft");
        }
        return Path.of(home, ".minecraft");
    }

    /** 本次 ensure 的结果。 */
    public record EnsureResult(List<String> createdDirs, List<String> skipped, Path gameDir) {
        public String summary() {
            if (createdDirs.isEmpty()) {
                return "标准目录结构已完整（未做任何改动）";
            }
            return "新建 " + createdDirs.size() + " 个目录：" + String.join("、", createdDirs);
        }
    }

    /**
     * 补齐标准目录。
     *
     * <p>语义刻意保持极窄：只对"不存在的目录"做创建；已存在的一律不动、不清理、不覆盖。
     * 这样即使玩家把某个标准目录改成符号链接或改过权限，也不会被我们破坏。
     */
    public static EnsureResult ensure(Path gameDir) throws IOException {
        List<String> created = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Files.createDirectories(gameDir);
        for (String dir : STANDARD_DIRS) {
            Path p = gameDir.resolve(dir);
            if (Files.exists(p)) {
                skipped.add(dir);
                continue;
            }
            Files.createDirectories(p);
            created.add(dir);
        }
        return new EnsureResult(created, skipped, gameDir);
    }

    /** 判断这个目录看起来是不是一个游戏目录（用于决定要不要自动补齐）。 */
    public static boolean looksLikeGameDir(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return false;
        }
        String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
        if (".minecraft".equalsIgnoreCase(name)) {
            return true;
        }
        return Files.exists(dir.resolve("launcher_profiles.json"))
                || Files.isDirectory(dir.resolve("versions"))
                || Files.isDirectory(dir.resolve("mods"));
    }

    /**
     * 加载器启动时的自动补齐：只有当模组目录的父目录确实像一个游戏目录时才动手。
     * 这样"把加载器丢进某个游戏目录跑"就能自动得到标准结构，而在任意临时目录里跑测试
     * 也不会到处乱建文件夹。
     */
    public static EnsureResult ensureForModsDir(Path modsDir) {
        if (modsDir == null || modsDir.getParent() == null) {
            return new EnsureResult(List.of(), List.of(), modsDir);
        }
        Path gameDir = modsDir.getParent();
        if (!looksLikeGameDir(gameDir)) {
            return new EnsureResult(List.of(), List.of(), gameDir);
        }
        try {
            return ensure(gameDir);
        } catch (IOException e) {
            return new EnsureResult(List.of(), List.of(), gameDir);
        }
    }

    /** 加载器本体应该落地的位置。 */
    public static Path loaderJar(Path gameDir) {
        return gameDir.resolve(LOADER_JAR_RELATIVE);
    }

    /** 早期窗口配置文件的位置。 */
    public static Path earlyWindowConfig(Path gameDir) {
        return gameDir.resolve("config/fusion-earlywindow.properties");
    }

    /** 安装清单位置。 */
    public static Path manifest(Path gameDir) {
        return gameDir.resolve(MANIFEST);
    }

    /** 写一份人类可读的安装清单。 */
    public static void writeManifest(Path gameDir, List<String> lines) throws IOException {
        Path p = manifest(gameDir);
        Files.createDirectories(p.getParent());
        StringBuilder sb = new StringBuilder(256);
        sb.append("# FusionML 安装清单").append(System.lineSeparator());
        for (String line : lines) {
            sb.append(line).append(System.lineSeparator());
        }
        Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);
    }

    /** 读回安装清单（卸载时用来只删自己建过的东西）。 */
    public static List<String> readManifest(Path gameDir) throws IOException {
        Path p = manifest(gameDir);
        if (!Files.isReadable(p)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) {
                out.add(t);
            }
        }
        return out;
    }
}
