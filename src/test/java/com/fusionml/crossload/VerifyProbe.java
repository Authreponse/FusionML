package com.fusionml.crossload;

import com.fusionml.install.InstallVerify;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 验证安装器的"双重验证"在真实目录上给出的结论。
 *
 * <p>这是把 {@code InstallVerify} 的判断<b>摆到真实数据上</b>跑一遍：
 * 每个版本目录都过两道校验，看它给出的结论与已知事实是否一致。
 *
 * <p>已知事实（来自本项目之前的实测）：
 * <ul>
 *   <li>26.3 / 26.1.2 不混淆 → 应当"通过"；</li>
 *   <li>1.21.1 / 1.20.1 混淆 → 应当"不通过"，并说明需要 remap 层。</li>
 * </ul>
 * 如果这里给出的结论与已知事实不符，就说明校验判据写错了 —— 这正是这个工具的价值。
 */
public final class VerifyProbe {

    private VerifyProbe() {
    }

    public static void main(String[] args) throws Exception {
        Path mcRoot = null;
        for (String line : Files.readAllLines(Path.of("build", "gamedir.txt"),
                java.nio.charset.StandardCharsets.UTF_8)) {
            String s = line.trim();
            if (!s.isEmpty() && !s.startsWith("#")) {
                Path p = Path.of(s);
                mcRoot = p.getParent() == null ? null : p.getParent().getParent();
                break;
            }
        }
        if (mcRoot == null || !Files.isDirectory(mcRoot)) {
            System.out.println("推不出 .minecraft 目录");
            return;
        }

        // 允许从命令行指定"要验哪个版本目录"，默认把本机全部相关版本都走一遍。
        if (args.length > 0) {
            for (String a : args) {
                run(Path.of(a));
            }
            return;
        }
        Path versions = mcRoot.resolve("versions");
        System.out.println("=== 第一道：目录校验（对整个 .minecraft）===");
        System.out.println(InstallVerify.verifyDirectory(mcRoot).render());
        System.out.println("=== 第一道：故意选错目录（用一个不存在的位置）===");
        System.out.println(InstallVerify.verifyDirectory(mcRoot.resolve("nope")).render());

        try (var ds = Files.newDirectoryStream(versions)) {
            java.util.List<Path> sorted = new java.util.ArrayList<>();
            ds.forEach(sorted::add);
            sorted.sort(java.util.Comparator.comparing(p -> p.getFileName().toString()));
            for (Path v : sorted) {
                String name = v.getFileName().toString();
                if (!name.startsWith("26.") && !name.startsWith("1.21.1") && !name.startsWith("1.20.1")) {
                    continue;
                }
                run(v);
            }
        }
    }

    private static void run(Path versionDir) {
        Path mcRoot = versionDir.getParent() == null ? null : versionDir.getParent().getParent();
        String id = versionDir.getFileName().toString();
        // 该版本常见的 Java 要求：26.x 要 25，1.21.x 要 21，1.20.x 要 17。
        int req = id.startsWith("26.") ? 25 : id.startsWith("1.21") ? 21 : 17;
        System.out.println("---------------------------------------------");
        System.out.println(InstallVerify.verifyVersion(mcRoot, id, req).render());
    }
}
