package com.fusionml.launch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 找一个满足版本要求的 Java 运行时。
 *
 * <p>为什么值得单独写一个：Minecraft 对 Java 版本的要求是硬性的（26.3 要 Java 25），
 * 而一台机器上通常装了不止一个 JDK。用错版本的表现是启动直接抛
 * {@code UnsupportedClassVersionError}，玩家完全看不出该换哪个 Java。
 * 这里提前扫描并选对，把这类错误消灭在启动之前。
 *
 * <p>扫描顺序：当前 JVM → {@code JAVA_HOME} → 常见安装目录；在满足要求的前提下取版本最低的那个。
 */
public final class JavaFinder {

    private JavaFinder() {
    }

    public record Found(Path javaExe, int major, String home) {
        @Override
        public String toString() {
            return "Java " + major + "（" + home + "）";
        }
    }

    /** 当前 JVM 的 Java 主版本。 */
    public static int currentMajor() {
        String v = System.getProperty("java.specification.version", "21");
        if (v.startsWith("1.")) {
            v = v.substring(2);
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return 21;
        }
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public static Path currentJava() {
        String home = System.getProperty("java.home");
        if (home == null) {
            return null;
        }
        Path exe = Path.of(home, "bin", isWindows() ? "java.exe" : "java");
        return Files.isRegularFile(exe) ? exe : null;
    }

    /**
     * 找一个主版本不低于 {@code required} 的 Java。
     *
     * @return 找到的运行时；找不到返回 null，调用方应提示"请安装 Java X"
     */
    public static Found find(int required) {
        int need = required <= 0 ? currentMajor() : required;
        List<Found> candidates = new ArrayList<>();
        addCandidate(candidates, System.getProperty("java.home"));
        addCandidate(candidates, System.getenv("JAVA_HOME"));
        for (Path root : searchRoots()) {
            addCandidatesFrom(candidates, root);
        }
        Found best = null;
        for (Found f : candidates) {
            if (f.major() >= need && (best == null || f.major() < best.major())) {
                // 满足要求且版本最低者优先：刚好够用通常最稳。
                best = f;
            }
        }
        if (best == null) {
            for (Found f : candidates) {
                if (best == null || f.major() > best.major()) {
                    best = f;
                }
            }
        }
        return best;
    }

    private static void addCandidate(List<Found> out, String home) {
        if (home == null || home.isBlank()) {
            return;
        }
        Path homePath = Path.of(home);
        int major = readMajor(homePath);
        if (major <= 0) {
            return;
        }
        Path exe = homePath.resolve("bin").resolve(isWindows() ? "java.exe" : "java");
        if (Files.isRegularFile(exe)) {
            out.add(new Found(exe, major, homePath.toAbsolutePath().toString()));
        }
    }

    /** 读 JDK 的 release 文件取版本号（比跑一次 java -version 快得多）。 */
    private static int readMajor(Path home) {
        Path release = home.resolve("release");
        if (Files.isReadable(release)) {
            try {
                for (String line : Files.readAllLines(release, StandardCharsets.UTF_8)) {
                    if (line.startsWith("JAVA_VERSION=")) {
                        String v = line.substring("JAVA_VERSION=".length()).replace("\"", "").trim();
                        if (v.startsWith("1.")) {
                            v = v.substring(2);
                        }
                        int dot = v.indexOf('.');
                        return Integer.parseInt(dot < 0 ? v : v.substring(0, dot));
                    }
                }
            } catch (IOException | NumberFormatException ignored) {

            }
        }
        String name = home.getFileName() == null ? "" : home.getFileName().toString();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(name);
        int major = 0;
        while (m.find()) {
            major = Math.max(major, Integer.parseInt(m.group(1)));
        }
        return major;
    }

    private static List<Path> searchRoots() {
        List<Path> roots = new ArrayList<>();
        if (isWindows()) {
            for (String base : List.of("C:\\Program Files\\Java", "C:\\Program Files\\Eclipse Adoptium",
                    "C:\\Program Files\\Zulu", "C:\\Program Files\\Microsoft",
                    "C:\\Program Files\\BellSoft", "C:\\Program Files\\Amazon Corretto",
                    "C:\\Program Files\\Semeru", "C:\\Program Files (x86)\\Java")) {
                roots.add(Path.of(base));
            }
            String pf = System.getenv("ProgramFiles");
            if (pf != null) {
                roots.add(Path.of(pf, "Java"));
            }
        } else {
            roots.add(Path.of("/usr/lib/jvm"));
            roots.add(Path.of("/Library/Java/JavaVirtualMachines"));
            String home = System.getProperty("user.home", "");
            if (!home.isEmpty()) {
                roots.add(Path.of(home, ".sdkman", "candidates", "java"));
            }
        }
        return roots;
    }

    private static void addCandidatesFrom(List<Found> out, Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return;
        }
        Set<String> seen = new LinkedHashSet<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            for (Path child : ds) {
                if (!Files.isDirectory(child)) {
                    continue;
                }
                Path contents = child.resolve("Contents").resolve("Home");
                Path actual = Files.isDirectory(contents) ? contents : child;
                Path exe = actual.resolve("bin").resolve(isWindows() ? "java.exe" : "java");
                if (Files.isRegularFile(exe) && seen.add(exe.toAbsolutePath().toString())) {
                    addCandidate(out, actual.toAbsolutePath().toString());
                }
            }
        } catch (IOException ignored) {

        }
    }
}
