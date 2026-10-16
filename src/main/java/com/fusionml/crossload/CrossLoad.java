package com.fusionml.crossload;

import com.fusionml.launch.CrossPlatformBridge;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 跨端驱动的两个挂载点（游戏进程内）。
 *
 * <p>一个跨端模组要真正"跑起来"，需要三件事按顺序发生：
 * <ol>
 *   <li><b>它的类能被看见</b> —— 由 {@link CrossPlatformBridge} 在宿主平台准备 Mixin 配置之前
 *       把包加进平台类路径（这一步同时让它的 Mixin 生效）；</li>
 *   <li><b>它的入口点被构造、它的注册表条目真的写进去</b> —— 原版引导之后、游戏主循环
 *       刚要开始的时候，见 {@link #onGameReady()}。</li>
 * </ol>
 *
 * <p>第 2、3 步的时刻是实测出来的，写在这里免得后人再踩一遍：
 * 原版要求"注册表对象已建好、且里面的 Holder 已经绑定"之后，
 * 对方的 {@code DeferredRegister} 才肯工作（否则分别会撞
 * {@code Not bootstrapped} 与 {@code Trying to access unbound value}）。
 * 这两件事都发生在原版引导里，所以窗口只能开在引导之后；
 * 而写入冻结的注册表则由对方平台自己的 {@code GameData.unfreezeData/freezeData} 兜住。
 */
public final class CrossLoad {

    private static volatile boolean bootstrapped;
    private static volatile ClassLoader host;
    private static volatile boolean client = true;
    private static volatile String platform = "neoforge";
    private static volatile Path gameDir;
    private static volatile ForeignModLoader.Result result;

    private CrossLoad() {
    }

    /** 打到控制台的前缀：与前置分析保持一致的风格，便于日志里一眼分辨。 */
    private static void say(String line) {
        System.out.println("[FusionML/跨端] " + line);
    }

    /**
     * 建立对方平台的全局状态（幂等）。前置分析与驱动入口都会调用它。
     *
     * <p>为什么可能要在<b>原版引导之前</b>就建：补丁原版字节里的静态初始化会回调 FML
     * （最典型的是 {@code FeatureFlags.<clinit>} → {@code FeatureFlagLoader} →
     * {@code FMLLoader.getCurrent()}），而原版引导在游戏主函数里、早于我们的驱动挂载点。
     * 见 {@link FmlEnvironment} 的长注释。
     */
    public static boolean standUpFml(Consumer<String> log) {
        try {
            ClassLoader host = CrossPlatformBridge.hostClassLoader();
            if (host == null) {
                host = ClassLoader.getSystemClassLoader();
            }
            return FmlEnvironment.standUp(host, line -> log.accept(line));
        } catch (Throwable t) {
            log.accept("建立对方平台全局状态时出错（相关跨端模组会失败）：" + t);
            return false;
        }
    }

    /**
     * 挂载点：{@code Minecraft#run} / {@code MinecraftServer#runServer} 的入口 ——
     * 也就是"原版引导已经走完、游戏主循环刚要开始"。
     *
     * <p>一次性把三件事做完：构造跨端模组 → 发出注册表事件 → 补上生命周期事件。
     */
    public static void onGameReady(Object self) {
        if (bootstrapped) {
            return;
        }
        bootstrapped = true;
        // 第一行无条件打出来：这条日志本身就是"挂载点真的被调用了"的证据。
        // 之前那次排查就是卡在"到底进没进来"上 —— 没有这行，日志里既没有成功也没有失败，
        // 只能靠猜。
        say("挂载点已进入（" + (self == null ? "?" : self.getClass().getSimpleName())
                + "），跨端模组包属性 = " + System.getProperty("fusion.foreignModJars", "（未设置）"));
        try {
            List<Path> jars = readPaths(System.getProperty("fusion.foreignModJars", ""));
            if (jars.isEmpty()) {
                return;
            }
            platform = System.getProperty("fusion.foreignPlatform", "neoforge");
            client = Boolean.parseBoolean(System.getProperty("fusion.client", "true"));
            gameDir = System.getProperty("fusion.gameDir", "").isBlank()
                    ? null : Path.of(System.getProperty("fusion.gameDir"));
            host = CrossPlatformBridge.hostClassLoader();
            if (host == null) {
                say("拿不到宿主平台的类加载器，跨端驱动跳过");
                return;
            }
            // 对方平台的运行时（API / 事件总线）在这一刻才登记进宿主类路径。
            // 更早登记会让跨端模组的 Mixin 插件在配置阶段就"看得见 FML 的类"，
            // 从而在平台还没初始化时调用它 —— 那是启动崩溃，不是警告（详见桥里的注释）。
            List<Path> runtime = CrossPlatformBridge.foreignRuntime();
            if (!runtime.isEmpty() && Boolean.parseBoolean(
                    System.getProperty("fusion.fmlEarly", "false"))) {
                // 前置分析阶段（原版引导之前）就已经登记过了 —— 因为类路径里放的是
                // 对方平台补丁过的原版字节，这些字节在定义时就要解析对方的类型。
                // 再登记一次没有意义，重复加同一份 jar 只会让类路径变长。
                say("跨端运行时 " + runtime.size() + " 个已在前置阶段登记（补丁原版字节需要）");
            } else if (!runtime.isEmpty()) {
                int ok = CrossPlatformBridge.addToHostClassPath(runtime);
                say("跨端运行时包 " + ok + "/" + runtime.size() + " 个已在本刻登记进宿主类路径");
            } else {
                say("没有解析到跨端运行时包：跨端模组只做 Mixin，不做入口点驱动");
            }
            say("开始驱动 " + jars.size() + " 个非宿主平台模组包（宿主加载器 "
                    + host.getClass().getName() + "）");
            result = ForeignModLoader.install(host, jars, platform, client, CrossLoad::say);
            say(result.summary());
            for (ForeignModLoader.Loaded m : result.mods()) {
                if (!m.ok()) {
                    say("  · " + m.modId() + " 未驱动：" + m.error());
                }
            }
            for (String note : result.notes()) {
                say("  · " + note);
            }
            boolean fired = ForeignModLoader.fireRegisterEvents(host, CrossLoad::say);
            if (fired) {
                ForeignModLoader.fireLifecycle(host, client, CrossLoad::say);
            }
            report(result);
        } catch (Throwable t) {
            say("跨端驱动失败（不影响宿主平台）：" + t);
        }
    }

    /** 把结论落盘 + 交给 HUD/报告读取（静态字段在两个加载器里各一份，只能走系统属性）。 */
    private static void report(ForeignModLoader.Result r) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("跨端驱动报告（宿主平台 ").append(System.getProperty("fusion.hostPlatform", "?"))
                .append(" → ").append(r.platform()).append("）").append('\n');
        sb.append("运行时可用：").append(r.runtimeUsable() ? "是" : "否").append('\n');
        sb.append("模组：").append(r.okCount()).append('/').append(r.mods().size())
                .append(" 已驱动").append('\n');
        for (ForeignModLoader.Loaded m : r.mods()) {
            sb.append("  - ").append(m.modId()).append(' ').append(m.version())
                    .append(" （").append(m.name()).append("）")
                    .append(m.ok() ? " 入口 " + m.entryClass() : " 失败：" + m.error())
                    .append('\n');
        }
        for (String note : r.notes()) {
            sb.append("  注：").append(note).append('\n');
        }
        String text = sb.toString();
        System.setProperty("fusion.crossload.summary", r.summary());
        System.setProperty("fusion.crossload.ok", String.valueOf(r.okCount()));
        System.setProperty("fusion.crossload.total", String.valueOf(r.mods().size()));
        if (gameDir != null) {
            try {
                Path dir = gameDir.resolve(".fusionml");
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("crossload-report.txt"), text,
                        StandardCharsets.UTF_8);
            } catch (Throwable t) {
                say("报告写盘失败：" + t);
            }
        }
    }

    private static List<Path> readPaths(String raw) {
        List<Path> out = new ArrayList<>(4);
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String part : raw.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (!part.isBlank()) {
                out.add(Path.of(part));
            }
        }
        return out;
    }

    /** 给别处（HUD、报告）用的一行结论。 */
    public static String summary() {
        return System.getProperty("fusion.crossload.summary", "");
    }

    /** 便于单测/诊断：当前是否已经驱动过。 */
    public static boolean driven() {
        return ForeignModLoader.installed();
    }

    static Consumer<String> logger() {
        return CrossLoad::say;
    }
}
