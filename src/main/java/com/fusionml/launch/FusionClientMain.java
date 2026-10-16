package com.fusionml.launch;

import com.fusionml.meta.ModFormat;
import com.fusionml.meta.ModMetadata;
import com.fusionml.runtime.FusionRuntime;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 接管游戏启动的主类。
 *
 * <p>这是"接入 Minecraft"的落点：版本定义里的 mainClass 被替换成本类，于是启动顺序变成
 * <pre>
 *   JVM 启动 → javaagent.premain（挂上字节码变换钩子）
 *            → 本类：加载模组 / 建索引 / 解析依赖 / 装注入规则
 *            → 对真实游戏类做一次注入自检
 *            → 反射调用真正的游戏主类，正常进游戏
 * </pre>
 *
 * <p>思路与 Fabric 的 Knot 一致，但实现完全自研，并且刻意多加了"先自检再交棒"这一步：
 * 在把控制权交出去之前先确认改写对这份游戏的字节码成立，出问题时报错点是明确的，
 * 而不是等游戏崩在几百个类之后。
 */
public final class FusionClientMain {

    public static void main(String[] args) throws Exception {
        // 注意是可变局部变量：平台运行时引导成功后会被替换成游戏本体的主类。
        String realMain = System.getProperty("fusion.realMainClass",
                "net.minecraft.client.main.Main");
        Path gameDir = Path.of(System.getProperty("fusion.gameDir", "."));
        String version = System.getProperty("fusion.version", "?");
        boolean bootOnly = Boolean.parseBoolean(System.getProperty("fusion.bootOnly", "false"));
        boolean skipMods = Boolean.parseBoolean(System.getProperty("fusion.skipMods", "false"));
        String clientJarProp = System.getProperty("fusion.clientJar", "");

        System.out.println("======================================================");
        System.out.println(" FusionML 接管启动");
        System.out.println("   游戏版本 : " + version);
        System.out.println("   游戏目录 : " + gameDir.toAbsolutePath());
        System.out.println("   运行 Java: " + System.getProperty("java.version"));
        System.out.println("   真实主类 : " + realMain);
        System.out.println("   代理状态 : "
                + (FusionAgent.available() ? "已挂载" : "未挂载（注入不可用）"));
        System.out.println("======================================================");

        // 早期窗口：从"点启动"到"进游戏"全程给玩家反馈。
        // 之前我在这里把它关掉了，理由是"游戏会接管窗口"—— 那个判断是错的：
        // 加载阶段（尤其是平台运行时引导）恰恰是最需要反馈、也最容易让人以为卡死的时候。
        com.fusionml.earlywindow.EarlyWindowConfig ewConfig =
                com.fusionml.earlywindow.EarlyWindowConfig.load();
        // 交棒给游戏之前由我们显式关闭，避免和游戏自己的窗口创建过程互相干扰。
        ewConfig.closeOnFinish = false;
        com.fusionml.earlywindow.EarlyWindow earlyWindow =
                com.fusionml.earlywindow.EarlyWindows.create(ewConfig,
                        System.getProperty("fusion.earlywindow", ""));
        earlyWindow.open();
        earlyWindow.log("游戏版本 " + version + "，目录 " + gameDir);
        earlyWindow.log("运行 Java " + System.getProperty("java.version"));
        // 引导日志同时进早期窗口与控制台。
        // 只送窗口是个错误：一旦引导失败，原因就只出现在一个可能已经关掉的窗口里，
        // 控制台和日志文件里什么都看不到 —— 排查时等于黑屏。
        java.util.function.Consumer<String> platformLog = line -> {
            System.out.println("[FusionML] " + line);
            try {
                earlyWindow.log(line);
            } catch (Throwable ignored) {
                // 窗口记录失败不影响加载
            }
        };

        FusionRuntime.Session session = null;
        Path modsDir = gameDir.resolve("mods");
        if (!skipMods) {
            if (Files.isDirectory(modsDir)) {
                // 先引导平台官方运行时（Fabric），再加载模组。
                // 顺序很重要：模组在 onInitialize 里会调用 FabricLoader.getInstance()、
                // 读配置目录、注册表和事件；运行时没起来的话这些全是 null。
                // 引导成功后，模组的初始化交给平台自己驱动（语义最准），
                // 我们依然负责发现、索引、注入与报告。
                // 注意要传"游戏版本"（26.3），不是"版本名"（26.3-Fabric 0.19.5）。
                // Fabric 用它去校验模组声明的 minecraft 依赖，传版本名会让所有模组都判不满足。
                Path clientJarPath = clientJarProp.isBlank() ? null : Path.of(clientJarProp);
                String gameVersion = com.fusionml.mc.ProvidedVersions.minecraftVersion(clientJarPath);
                if (gameVersion == null || gameVersion.isBlank()) {
                    int dash = version.indexOf('-');
                    gameVersion = dash > 0 ? version.substring(0, dash) : version;
                }
                earlyWindow.stage("引导平台运行时", 0.05);
                boolean platformReady = FabricRuntime.bootstrap(gameDir, modsDir, gameVersion,
                        true, args, clientJarPath,
                        Path.of(System.getProperty("fusion.gameClasspathFile",
                                gameDir.resolve(".fusionml").resolve("game-classpath.txt").toString())),
                        platformLog);
                if (platformReady && FabricRuntime.entrypoint() != null) {
                    // 关键：我们已经取代了平台的启动器（Knot），所以必须直接交给"游戏本体主类"。
                    // 版本定义里的 mainClass 是 KnotClient —— 它自己也会去 setLauncher，
                    // 于是会直接抛 "Duplicate setLauncher call!"。
                    System.out.println("[FusionML] 已接管平台启动器，改交游戏本体主类："
                            + FabricRuntime.entrypoint());
                    realMain = FabricRuntime.entrypoint();
                } else if (FabricRuntime.launcherInstalled()) {
                    // 我们已经占用了平台的 launcher 挂载点，平台自己的启动器（Knot）不可能再起来，
                    // 这时候再去调它必然抛 "Duplicate setLauncher call!"。
                    // 直接交给游戏本体主类：至少保证游戏能进，模组初始化缺失的问题另行报告。
                    realMain = "net.minecraft.client.main.Main";
                    platformLog.accept("平台运行时未完全就绪，但启动器挂载点已被占用；"
                            + "改为直接交给游戏本体主类 " + realMain + "（模组可能不会初始化）");
                }
                // 依赖版本校验要用"这台机器上真实装了什么"，不能靠写死的默认值。
                java.util.Map<String, String> provided = null;
                if (!clientJarProp.isBlank()) {
                    Path jar = Path.of(clientJarProp);
                    com.fusionml.mc.McInstance inst = com.fusionml.mc.McInstance.open(gameDir);
                    provided = com.fusionml.mc.ProvidedVersions.detect(inst, jar, version);
                    System.out.println("[FusionML] 环境提供：" 
                            + com.fusionml.mc.ProvidedVersions.describe(provided));
                }
                earlyWindow.stage("建立模组索引与注入规则", 0.55);
                session = loadMods(modsDir, provided, !platformReady, earlyWindow);
                if (session != null) {
                    FusionAgent.installPipeline(session.pipeline);
                }
            } else {
                System.out.println("[FusionML] 没有 mods 目录，跳过模组加载：" + modsDir);
            }
        } else {
            System.out.println("[FusionML] 已按要求跳过模组加载。");
        }

        // 先自检再交棒：对真实游戏类注入一次，让 JVM 自己判定改写是否合法。
        if (!clientJarProp.isBlank()) {
            Path clientJar = Path.of(clientJarProp);
            if (Files.isRegularFile(clientJar)) {
                try {
                    InjectionVerifier.Result r = InjectionVerifier.run(clientJar,
                            FusionClientMain.class.getClassLoader());
                    System.out.println("[FusionML] 真实游戏类注入自检：" + r.summary());
                    for (String line : r.details()) {
                        System.out.println("           " + line);
                    }
                } catch (Throwable t) {
                    System.out.println("[FusionML] 注入自检未能完成：" + t);
                }
            }
        }

        if (bootOnly) {
            System.out.println("[FusionML] 已完成接管与自检（验证模式，不进入游戏）。");
            earlyWindow.finish("自检完成（未进入游戏）");
            earlyWindow.close();
            if (session != null) {
                session.close();
            }
            System.exit(0);
        }

        // 交棒前把早期窗口收掉：它已经完成了告知进度的使命，
        // 让位给游戏自己的窗口，避免两套窗口系统在同一进程里抢注意力。
        earlyWindow.finish("加载完成，正在启动游戏…");
        try {
            Thread.sleep(350);            // 让"完成"这一帧真正画出来
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        earlyWindow.close();

        System.out.println("[FusionML] 交给游戏主类：" + realMain);
        if (FabricRuntime.bootstrapped()) {
            // 走平台的启动路径：由平台的类加载器定义游戏主类，
            // 这样 Mixin 与入口点改写才会作用于它。
            System.out.println("[FusionML] 由平台类加载器启动游戏（Mixin 与入口点改写在此生效）");
            try {
                FabricRuntime.launchGame();
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause() == null ? e : new RuntimeException(e.getCause());
            }
            return;
        }
        invokeRealMain(realMain, args);
    }

    /** 加载模组：发现 → 元数据 → 依赖 → 索引 → 注册 → 注入规则。 */
    private static FusionRuntime.Session loadMods(Path modsDir, java.util.Map<String, String> provided,
                                                  boolean registerMods,
                                                  com.fusionml.earlywindow.EarlyWindow earlyWindow) {
        FusionRuntime.Options o = new FusionRuntime.Options();
        o.modsDir(modsDir);
        o.client(true);
        // 平台运行时已经起来时，模组初始化由平台自己驱动（Fabric 会按依赖顺序调用入口点、
        // 处理 Mixin 与事件）；我们只做发现/索引/注入与报告，避免同一次初始化跑两遍。
        o.registerMods(registerMods);
        if (provided != null && !provided.isEmpty()) {
            o.provided = provided;
        }
        // 复用同一个早期窗口：平台引导阶段的进度与这里的阶段是连续的，玩家看到的是一条完整进度。
        o.earlyWindow = earlyWindow;
        o.verifyInjections(false);       // 自检在下面用真实游戏类专门做一遍
        try {
            FusionRuntime.Session session = FusionRuntime.load(o);
            System.out.println("[FusionML] 模组加载完成：" + session.mods.size() + " 个（"
                    + describeFormats(session) + "）、" + session.index.totalClasses() + " 个类、"
                    + session.pipeline.hookRuleCount() + " 条注入规则");
            if (session.plan.fatal()) {
                System.out.println("[FusionML] 发现 " + session.plan.fatalCount()
                        + " 个需处理的问题：");
                var fatalOnly = session.plan.problems.stream()
                        .filter(com.fusionml.resolve.ModGraph.Problem::fatal).toList();
                int shown = 0;
                for (String line : com.fusionml.resolve.ModGraph.summarize(fatalOnly, 1)) {
                    if (shown++ >= 12) {
                        System.out.println("           …（其余同类问题已折叠）");
                        break;
                    }
                    System.out.println("           " + line);
                }
            }
            // 注册结果：成功的折成一行，只把没成功的展开。
            // 反过来做（把全部成功项都打出来）在 200 个模组时就是几百行噪声，
            // 真出问题的那几条会被彻底淹掉。
            int ok = 0;
            java.util.List<String> failures = new java.util.ArrayList<>();
            for (String line : session.registration) {
                boolean bad = line.contains("失败") || line.contains("异常")
                        || line.contains("找不到") || line.contains("缺失")
                        || line.contains("未定位") || line.contains("不匹配");
                if (bad) {
                    if (failures.size() < 8) {
                        failures.add(line);
                    }
                } else {
                    ok++;
                }
            }
            System.out.println("[FusionML] 三端原生注册：成功 " + ok + " 项，未完成 "
                    + (session.registration.size() - ok) + " 项");
            for (String f : failures) {
                System.out.println("           " + f);
            }
            return session;
        } catch (Exception e) {
            System.out.println("[FusionML] 模组加载失败：" + e);
            return null;
        }
    }

    private static String describeFormats(FusionRuntime.Session session) {
        Map<ModFormat, Integer> byFormat = new java.util.EnumMap<>(ModFormat.class);
        for (ModMetadata m : session.mods) {
            byFormat.merge(m.format(), 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<ModFormat, Integer> e : byFormat.entrySet()) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(e.getKey().displayName()).append(' ').append(e.getValue());
        }
        return sb.length() == 0 ? "无" : sb.toString();
    }

    /** 反射调用真实主类，把参数原样传过去。 */
    private static void invokeRealMain(String realMain, String[] args) throws Exception {
        ClassLoader loader = FusionClientMain.class.getClassLoader();
        Class<?> mainClass;
        try {
            mainClass = Class.forName(realMain, true, loader);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("找不到游戏主类 " + realMain
                    + "：请确认类路径里包含客户端 jar（可用 --mc-check 查看启动计划）", e);
        }
        Method main = mainClass.getMethod("main", String[].class);
        System.out.println("[FusionML] 已加载游戏主类，开始执行 main()");
        main.invoke(null, (Object) args);
    }
}
