package com.fusionml.crossload;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 临时诊断工具：直接对真实模组目录跑一遍 {@link PatchedBase#allMixinTargets}，
 * 确认"禁止提前加载"的黑名单真的覆盖到 {@code DeferredRegister}。
 *
 * <p>存在的理由：离线自检走的是 {@code prepare(...)}，不经过 {@code forLaunch(...)}，
 * 所以它看不到黑名单那一行日志。而真机跑一轮要 150 秒。
 * 这个工具几秒钟就能回答"名单对不对"，把排查成本从分钟级压到秒级。
 *
 * <p>用法：{@code java -cp build\classes;build\test-classes com.fusionml.crossload.TargetProbe}
 * （模组目录从 {@code build/gamedir.txt} 读，不在命令行里传中文路径）。
 */
public final class TargetProbe {

    private TargetProbe() {
    }

    public static void main(String[] args) throws Exception {
        Path gameDir = gameDir();
        System.out.println("游戏目录：" + gameDir);
        Path mods = gameDir.resolve("mods");
        if (!Files.isDirectory(mods)) {
            System.out.println("找不到 mods 目录：" + mods);
            return;
        }
        List<Path> jars = new ArrayList<>();
        try (var s = Files.newDirectoryStream(mods, "*.jar")) {
            for (Path p : s) {
                jars.add(p);
            }
        }
        System.out.println("模组包：" + jars.size() + " 个");

        // 差额在宿主平台的嵌套包缓存（.fabric/processedMods）里，肇事模组就在那儿。
        com.fusionml.mc.McInstance instance = com.fusionml.mc.McInstance.open(gameDir);
        List<Path> all = PatchedBase.expandModJars(instance, jars, System.out::println);
        System.out.println("模组包全集：" + all.size() + " 个");
        java.util.Set<String> targets = PatchedBase.allMixinTargets(all, System.out::println);
        System.out.println("Mixin 目标总数：" + targets.size());
        // 命令行容量的自查：这批名单能不能塞进 -D 参数。
        // 实测过 846 个目标 ≈ 34KB，超过 Windows 上限，进程会以 error=206 起不来，
        // 而且日志里看不出原因。所以这里直接报出字节数。
        int bytes = String.join(",", targets).length();
        System.out.println("拼成单个 -D 参数的字节数：" + bytes
                + (bytes > 30000 ? "（超过 Windows 命令行上限，必须走文件通道）" : "（可走属性通道）"));
        String key = "net/neoforged/neoforge/registries/DeferredRegister";
        System.out.println("关键判据 " + key + " 在名单里=" + targets.contains(key));

        List<String> foreign = targets.stream()
                .filter(t -> t.startsWith("net/neoforged/") || t.startsWith("net/minecraftforge/"))
                .toList();
        System.out.println("其中对方平台类型 " + foreign.size() + " 个：");
        for (String t : foreign) {
            System.out.println("  · " + t);
        }
        // 判据健康度自查：合并时如果读不到新增接口的"形状"，判据会静默退化成
        // "一律必需"，把该保留原版字节的类也整类替换，宿主平台的 @Overwrite 会崩。
        // 所以这里直接报出"能不能读到接口"，以及会做出什么判断。
        System.out.println("--- 接口判据健康度 ---");
        System.out.println("libraries 目录属性=" + System.getProperty("fusion.librariesDir", "（未设）"));
        Instance2Shape(instance);
    }

    private static void Instance2Shape(com.fusionml.mc.McInstance instance) {
        System.setProperty("fusion.librariesDir",
                instance.librariesDir().toAbsolutePath().toString());
        for (String i : new String[] {
                "net/neoforged/neoforge/registries/IRegistryExtension",
                "net/neoforged/neoforge/common/extensions/IDataComponentMapBuilderExtensions"}) {
            System.out.println("  " + i + " → 判据=" + PatchedBase.describeInterface(i));
        }
    }

    /** 从 build/gamedir.txt 读游戏目录（UTF-8），避免在命令行里传中文路径。 */
    private static Path gameDir() throws Exception {
        Path f = Path.of("build", "gamedir.txt");
        if (Files.isRegularFile(f)) {
            for (String line : Files.readAllLines(f, java.nio.charset.StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (!s.isEmpty() && !s.startsWith("#")) {
                    return Path.of(s);
                }
            }
        }
        throw new IllegalStateException("build/gamedir.txt 里没有可用的路径");
    }
}
