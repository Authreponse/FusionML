package com.fusionml.crossload;

import com.fusionml.mc.McInstance;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 把本机某个库 jar 复制到一个纯 ASCII 路径，便于用 javap 之类的命令行工具去读它。
 *
 * <p>存在的理由很实际：工作目录里有中文路径，命令行工具（以及经过几层转发的命令行）
 * 会把这些字节弄乱，于是"读一个类"这种小事会变成"路径语法不正确"。
 * 交给 JVM 自己复制就没这个问题 —— 路径是它从 UTF-8 文件里读出来的。
 */
public final class LibraryPeek {

    public static void main(String[] args) throws Exception {
        // 命令行经过几层转发时很容易多出成对的引号，而 Path.of 会因为它直接失败。
        for (int i = 0; i < args.length; i++) {
            String s = args[i].trim();
            if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
                args[i] = s.substring(1, s.length() - 1);
            }
        }
        if (args.length >= 3 && "search".equals(args[0])) {
            search(args[1], args[2]);
            return;
        }
        if (args.length >= 2 && "searchall".equals(args[0])) {

            search(librariesDirOfCurrentGame().toString(), args[1]);
            return;
        }
        String needle = args.length > 0 ? args[0] : "fabric-loader";
        String dest = args.length > 1 ? args[1] : "build/peek/lib.jar";
        Path gameDir = Path.of(Files.readString(Path.of("build/gamedir.txt"),
                StandardCharsets.UTF_8).trim());
        Path instanceRoot = instanceRoot(gameDir);
        McInstance instance = McInstance.open(instanceRoot);
        String versionId = gameDir.getFileName().toString();
        McInstance.Effective eff = instance.resolve(versionId);
        Path hit = null;
        List<Path> candidates = new java.util.ArrayList<>();
        if (eff.clientJar != null) {
            candidates.add(eff.clientJar);
        }
        for (com.fusionml.mc.McVersion.Library lib : eff.applicableLibraries()) {
            Path p = instance.localLibraryPath(lib);
            if (p != null) {
                candidates.add(p);
            }
        }
        // 也扫一遍整个 libraries 目录：宿主平台自己加的库不一定在版本定义里。
        try (var walk = Files.walk(instance.librariesDir())) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)
                            .endsWith(".jar"))
                    .forEach(candidates::add);
        }
        for (Path p : candidates) {
            if (p.getFileName().toString().toLowerCase(Locale.ROOT)
                    .contains(needle.toLowerCase(Locale.ROOT))) {
                hit = p;
                break;
            }
        }
        if (hit == null) {
            System.out.println("没有找到名字里含 " + needle + " 的库");
            System.exit(2);
        }
        Path out = Path.of(dest).toAbsolutePath();
        Files.createDirectories(out.getParent());
        Files.copy(hit, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        System.out.println("已复制：" + hit.getFileName() + " → " + out);
    }

    private static void search(String target, String pattern) throws Exception {
        byte[] needle = pattern.getBytes(StandardCharsets.UTF_8);
        List<Path> jars = new java.util.ArrayList<>();
        Path p = Path.of(target);
        if (Files.isDirectory(p)) {
            try (var walk = Files.walk(p)) {
                walk.filter(Files::isRegularFile).forEach(jars::add);
            }
        } else {
            jars.add(p);
        }
        int hits = 0;
        for (Path jar : jars) {
            List<String> names = new java.util.ArrayList<>();
            try (com.fusionml.util.FastZip zip = com.fusionml.util.FastZip.open(jar)) {
                for (com.fusionml.util.FastZip.Entry e : zip.entries()) {
                    if (!e.isClass() || e.isDirectory()) {
                        continue;
                    }
                    byte[] b = zip.read(e);
                    if (indexOf(b, needle) >= 0) {
                        names.add(e.name());
                    }
                }
            } catch (Throwable t) {
                continue;
            }
            if (!names.isEmpty()) {
                hits++;
                System.out.println("== " + jar.getFileName());
                for (String n : names) {
                    System.out.println("   " + n);
                }
            }
        }
        System.out.println("共在 " + hits + " 个包里找到包含该字符串的类");
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static Path instanceRoot(Path gameDir) {
        Path p = gameDir.toAbsolutePath();
        while (p != null && !"versions".equals(String.valueOf(p.getFileName()))) {
            p = p.getParent();
        }
        return p == null ? gameDir.getParent() : p.getParent();
    }

    private static Path librariesDirOfCurrentGame() throws java.io.IOException {
        Path gameDir = Path.of(Files.readString(Path.of("build/gamedir.txt"),
                StandardCharsets.UTF_8).trim());
        return instanceRoot(gameDir).resolve("libraries");
    }
}
