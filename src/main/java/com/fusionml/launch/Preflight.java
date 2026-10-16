package com.fusionml.launch;

import com.fusionml.earlywindow.EarlyWindow;
import com.fusionml.earlywindow.EarlyWindowConfig;
import com.fusionml.earlywindow.EarlyWindows;
import com.fusionml.runtime.FusionRuntime;

import java.nio.file.Files;
import java.nio.file.Path;

// 前置分析：在平台运行时启动之前完成我们自己的那部分工作。
//
// 为什么放在 javaagent 的 premain 而不是接管主类：
// 平台的运行时（Fabric 的 Knot、NeoForge 的 ModLauncher）与它的类加载器是**不可分割**的 ——
// 模组的 Mixin、入口点注入、类路径分组全靠它自己的类加载器语义。
// 我们接管主类就得复刻这一整套语义，稍有偏差就出现"库被两份加载、日志系统失灵"这类
// 极难定位的问题（实测踩了三次）。而平台自己当主类时，这套语义天然正确。
//
// 所以分工改成：
//   * 我们（premain）：早期窗口、并行发现、类索引、依赖分析、注入规则装配 —— 全部前置完成；
//   * 平台（main）：按它自己的方式加载运行时、应用 Mixin、调用模组入口点；
//   * 我们的字节码注入走 javaagent，与类由谁加载无关，因此照常生效。
public final class Preflight {

    private Preflight() {
    }

    /** 是否处于"只做前置分析后退出"的验证模式。 */
    public static boolean bootOnly() {
        return Boolean.parseBoolean(System.getProperty("fusion.bootOnly", "false"));
    }

    public static void run() {
        String gameDirProp = System.getProperty("fusion.gameDir", "");
        if (gameDirProp.isBlank()) {
            return;
        }
        Path gameDir = Path.of(gameDirProp);
        Path modsDir = gameDir.resolve("mods");
        String version = System.getProperty("fusion.version", "?");
        // 客户端 / 服务端：服务端不注册 HUD，也不做任何与渲染相关的事。
        boolean client = Boolean.parseBoolean(System.getProperty("fusion.client", "true"));
        String platform = detectPlatform(version);
        long startedAt = System.nanoTime();
        // 同时打到控制台：早期窗口是给玩家看的，日志是给排查用的，两者都需要。
        java.util.function.Consumer<String> console = line ->
                System.out.println("[FusionML/前置] " + line);
        console.accept("开始前置分析：版本 " + version);
        console.accept("平台 " + platform + "，运行侧 " + (client ? "客户端" : "服务端"));
        console.accept("模组目录 " + modsDir);

        EarlyWindowConfig ewConfig = EarlyWindowConfig.load();
        // 不要自动关闭：关闭时机交给游戏内主加载页面（它画出第一帧后我们才收窗口）。
        // 这样平台引导期间不会出现"窗口关了、游戏还没出来"的黑屏空档。
        ewConfig.closeOnFinish = false;
        // 服务端一律用控制台实现，不建图形窗口。
        //
        // 两个原因，都是实测出来的：
        //   1) 专用服务器上弹一个窗口本身就是错的（服务器常常是无界面运行的）；
        //   2) Swing 会拉起 AWT 事件线程，服务器进程结束后 JVM 迟迟不退
        //      （实测：服务器已经"Stopping server"，进程还活着）。
        // 显式给了 -Dfusion.earlywindow=swing 时仍然尊重使用者，但服务端默认走控制台。
        String earlyWindowChoice = System.getProperty("fusion.earlywindow", "");
        if (!client && earlyWindowChoice.isBlank()) {
            earlyWindowChoice = "console";
        }
        EarlyWindow window = EarlyWindows.create(ewConfig, earlyWindowChoice);
        // 诊断用：把选中的实现报出来。
        // 上一次"早期窗口没出现"就是因为这条信息不可见，无法区分
        // "窗口创建失败"、"被判定为无头环境"、还是"根本没被调用"。
        console.accept("早期窗口实现：" + window.getClass().getName()
                + "（headless=" + java.awt.GraphicsEnvironment.isHeadless()
                + "，配置启用=" + ewConfig.enabled + "）");
        try {
            window.open();
            console.accept("早期窗口已请求显示");
            window.log("游戏版本 " + version);
            window.stage("扫描模组", 0.1);
            window.log("模组目录 " + modsDir);

            if (!Files.isDirectory(modsDir)) {
                window.log("没有 mods 目录，跳过前置分析");
                window.finish("准备启动平台运行时");
                return;
            }

            FusionRuntime.Options o = new FusionRuntime.Options();
            o.modsDir(modsDir);
            o.client(true);
            // 注册交给平台运行时去做（它才是模组语义的权威）；
            // 我们只做发现、索引、依赖分析与注入规则装配。
            o.registerMods(false);
            o.verifyInjections(false);
            o.earlyWindow = window;
            o.earlyWindowEnabled = true;

            try (FusionRuntime.Session session = FusionRuntime.load(o)) {
                window.stage("装配注入规则", 0.92);
                registerGameHudHook(session, console);
                registerCrossPlatformHook(session, platform, console);
                FusionAgent.installPipeline(session.pipeline);
                bridgeCrossPlatform(session, modsDir, platform, console);
                window.log("模组 " + session.mods.size() + " 个，类 "
                        + session.index.totalClasses() + " 个，注入规则 "
                        + session.pipeline.hookRuleCount() + " 条");
                console.accept("模组 " + session.mods.size() + " 个（" + session.modsByFormat.size()
                        + " 种平台）、类 " + session.index.totalClasses() + " 个、加载层次 "
                        + session.plan.depth() + " 层、注入规则 "
                        + session.pipeline.hookRuleCount() + " 条，耗时 "
                        + String.format(java.util.Locale.ROOT, "%.0f",
                                (System.nanoTime() - startedAt) / 1e6) + " ms");
                for (String w : com.fusionml.runtime.FusionRuntime.groupWarnings(session.warnings)) {
                    console.accept("提示 " + w);
                }
                if (session.plan.fatal()) {
                    window.log("发现 " + session.plan.fatalCount() + " 个需处理的问题");
                }
                window.finish("前置分析完成，交给平台运行时");
                if (bootOnly()) {
                    // 验证模式：前置阶段做完就退出，用来单独确认这一层是好的。
                    window.log("（验证模式：前置分析完成后退出）");
                    System.out.println("[FusionML] 前置分析完成（验证模式，不进入平台运行时）");
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    window.close();
                    System.exit(0);
                }
                if (!client) {
                    // 服务端没有"游戏窗口随后出现"这回事：早期窗口留着的唯一结果就是
                    // 一直挡在桌面上，直到 75 秒兜底超时才关（实测就是这样）。
                    // 所以服务端在前置分析做完时立刻收掉它。
                    window.finish("服务端前置分析完成");
                    window.close();
                    System.out.println("[FusionML] 服务端模式：早期窗口已关闭");
                } else {
                    // 交给游戏内页面接手：它会画出第一帧后关闭这个窗口。
                    com.fusionml.hud.FusionHud.handOverEarlyWindow(window);
                    startWindowKeeper(window);
                }
            }
        } catch (Throwable t) {
            // 前置分析失败绝不能拦住游戏启动：它是增强，不是前提。
            System.out.println("[FusionML] 前置分析失败（不影响游戏启动）：" + t);
            try {
                window.log("前置分析失败：" + t);
            } catch (Throwable ignored) {
                // 忽略
            }
        } finally {
            // 注意：非验证模式下**故意不关**窗口。
            // 平台启动后还要 20 秒左右才轮到我们在游戏内画第一帧，
            // 这段时间若没有窗口兜着，玩家看到的就是黑屏。
            if (bootOnly()) {
                window.close();
            }
        }
    }

    /**
     * 早期窗口的看门线程：保证它一定会关。
     *
     * <p>只依赖"游戏内页面画出第一帧"来关闭是不可靠的 —— 页面渲染一旦出问题，
     * 窗口就会永远赖在游戏窗口上面（实测发生过一次，体验很糟）。
     * 所以这里加两个兜底：游戏窗口一旦真的出现，或者超过硬性时限，就关闭它。
     */
    private static void startWindowKeeper(EarlyWindow window) {
        Thread keeper = new Thread(() -> {
            // 硬性时限：无论发生什么，到点就关。宁可少显示一会儿，也不能赖在游戏上面不走。
            long deadline = System.currentTimeMillis() + 75_000;
            while (System.currentTimeMillis() < deadline) {
                if (com.fusionml.hud.FusionHud.earlyWindowClosed()) {
                    return;                       // 游戏内页面已接手并关掉了它
                }
                if (com.fusionml.hud.FusionHud.isGameLoopStarted()
                        || gameWindowUp() || gameIsRunning()) {
                    // 游戏窗口已经存在：早期窗口的使命结束，再留着就是挡路。
                    sleep(1500);                  // 让游戏内页面有机会先画出来，衔接更顺
                    if (!com.fusionml.hud.FusionHud.earlyWindowClosed()) {
                        try {
                            window.finish("游戏已就绪");
                            window.close();
                            com.fusionml.hud.FusionHud.markEarlyWindowClosed();
                            System.out.println("[FusionML] 游戏窗口已就绪，早期窗口已关闭");
                        } catch (Throwable ignored) {
                            // 关闭失败也无所谓
                        }
                    }
                    return;
                }
                sleep(400);
            }
            try {
                window.close();
                System.out.println("[FusionML] 早期窗口超时关闭（兜底）");
            } catch (Throwable ignored) {
                // 忽略
            }
        }, "fusion-early-window-keeper");
        keeper.setDaemon(true);
        keeper.start();
    }

    /** 游戏自己的窗口是否已经创建（用 GLFW 句柄判断，最直接）。 */
    private static boolean gameWindowUp() {
        try {
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
            Object mc = mcClass.getMethod("getInstance").invoke(null);
            if (mc == null) {
                return false;
            }
            Object win = mcClass.getMethod("getWindow").invoke(mc);
            if (win == null) {
                return false;
            }
            Object handle = win.getClass().getMethod("getWindow").invoke(win);
            return handle instanceof Long l && l != 0L;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 更宽松的判据：只要 Minecraft 实例已经建好，就说明游戏起来了。
     *
     * <p>拿 GLFW 句柄更精确，但依赖反射链条上的三个方法都能成功；
     * 这里用"实例存在"作为备用信号，保证兜底逻辑在各种情况下都能生效。
     */
    private static boolean gameIsRunning() {
        try {
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
            return mcClass.getMethod("getInstance").invoke(null) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 从版本名判端。三种平台的版本名各有特征（PCL/官方启动器生成时都会带上）：
     * {@code 26.3-Fabric 0.19.5} / {@code 26.3-NeoForge_26.3.0.39} / {@code 1.20.1-Forge_47.x}。
     * 判错的后果只是日志与 HUD 策略不准，所以用宽松匹配即可，不做严格校验。
     */
    private static String detectPlatform(String version) {
        String v = version == null ? "" : version.toLowerCase(java.util.Locale.ROOT);
        if (v.contains("neoforge")) {
            return "NeoForge";
        }
        if (v.contains("forge")) {
            return "Forge";
        }
        if (v.contains("fabric")) {
            return "Fabric";
        }
        return "原版（无模组平台）";
    }

    /**
     * 用我们自己的注入引擎把 HUD 挂到游戏的渲染流程上。
     *
     * <p>目标方法是 {@code Hud.extractRenderState(GuiGraphicsExtractor, DeltaTracker)}：
     * 它在每帧收集 HUD 绘制内容，是最合适的挂载点。钩子签名必须是
     * {@code (Hud self, GuiGraphicsExtractor, DeltaTracker)} —— 实例方法的 this 也要传，
     * 我们的注入器会校验这一点，签名不符会直接跳过而不是生成一个必然崩溃的类。
     */
    private static void registerGameHudHook(FusionRuntime.Session session,
                                            java.util.function.Consumer<String> console) {
        // 服务端没有任何渲染流程，注册 HUD 只会平白多一条永远不命中的规则。
        if (!Boolean.parseBoolean(System.getProperty("fusion.client", "true"))) {
            console.accept("服务端模式：跳过游戏内 HUD 注册");
            return;
        }
        // 挂载点选 Hud.extractRenderState：它的签名里自带渲染提取器，是唯一验证安全的挂载点。
        // （主加载页面已撤销：它要往 TitleScreen 注入，而那个类改写后触发 ClassFormatError 崩游戏。）
        // 钩子用 Object 形参接收游戏类型，这样加载器 jar 不绑死在任何游戏类上；
        // 压栈的游戏类型传给 Object 形参在字节码层面完全合法。
        session.pipeline.addHook(new com.fusionml.bytecode.HookInjector.Hook(
                "net/minecraft/client/gui/Hud", "extractRenderState",
                "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
                "com/fusionml/hud/FusionHud", "onHudExtract",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
                com.fusionml.bytecode.HookInjector.Point.HEAD));
        // 早期窗口的关闭信号：给游戏主循环入口挂一个钩子。
        // 它的语义正好是"游戏窗口已就绪、开始跑帧循环了"，比在外部线程里
        // 反射探测 Minecraft 实例可靠得多（那个探测会触发游戏类的初始化，
        // 在守护线程上很容易失败或卡住，实测就因此没能及时关掉窗口）。
        session.pipeline.addHook(new com.fusionml.bytecode.HookInjector.Hook(
                "net/minecraft/client/Minecraft", "run", "()V",
                "com/fusionml/hud/FusionHud", "onGameLoopStarted",
                "(Ljava/lang/Object;)V",
                com.fusionml.bytecode.HookInjector.Point.HEAD));
        try {
            com.fusionml.hud.FusionHud.setModList(
                    com.fusionml.hud.ModListModel.from(session));
            console.accept("已注册游戏内 HUD（帧率 + 模组列表），挂载点 Hud#extractRenderState");
        } catch (Throwable t) {
            console.accept("注册游戏内 HUD 失败：" + t);
        }
    }

    /**
     * 跨端桥的挂载点装配。
     *
     * <p>时机是这件事的全部：宿主平台准备 Mixin 配置的入口方法一执行完，配置集合就定型了，
     * 之后再加进去的配置不会作用于任何类。所以钩子必须挂在那个方法的<b>头部</b>，
     * 而不是等游戏跑起来之后（第一次就是这么做的，结果是"提交成功、服务里 0 条"）。
     */
    private static void registerCrossPlatformHook(FusionRuntime.Session session, String platform,
                                                 java.util.function.Consumer<String> console) {
        // 宿主平台名交给游戏进程里的桥使用（同一份类可能有两份，系统属性是唯一可靠通道）。
        System.setProperty("fusion.hostPlatform", platform);
        // Mixin 阶段切换的挂载点：这是"能安全碰 Mixin 的最早时刻"，
        // 也是任何一次配置定型之前的最后机会。理由写在 CrossPlatformBridge 的注释里。
        session.pipeline.addHook(new com.fusionml.bytecode.HookInjector.Hook(
                "org/spongepowered/asm/mixin/MixinEnvironment", "gotoPhase",
                "(Lorg/spongepowered/asm/mixin/MixinEnvironment$Phase;)V",
                "com/fusionml/launch/CrossPlatformBridge", "onMixinPhaseChange",
                "(Ljava/lang/Object;)V",
                com.fusionml.bytecode.HookInjector.Point.HEAD));
        // 跨端模组的入口点驱动：两个挂载点都与宿主是哪一端无关，
        // 所以两端都挂（对方平台的类由对方的运行时包提供，见 bridgeCrossPlatform）。
        //
        // 跨端模组的入口点驱动：挂在"原版引导已经走完、游戏主循环刚要开始"的位置。
        //
        // 时机是实测出来的，不是猜的。试过更早的两个位置，都被原版机制挡住：
        //   * Bootstrap#bootStrap 头部：bootstrapped 还没置位，模组构造时的
        //     DeferredRegister 直接撞 "Not bootstrapped (called from registry ...)"；
        //   * BuiltInRegistries#bootStrap 头部：注册表对象建好了但 Holder 还没绑定，
        //     DeferredRegister 建 DeferredItem 时会撞 "Trying to access unbound value"。
        // 客户端与服务端各挂一个（整合服务器起来时第二个会因已装载而跳过）。
        session.pipeline.addHook(new com.fusionml.bytecode.HookInjector.Hook(
                "net/minecraft/client/Minecraft", "run", "()V",
                "com/fusionml/crossload/CrossLoad", "onGameReady",
                "(Ljava/lang/Object;)V", com.fusionml.bytecode.HookInjector.Point.HEAD));
        session.pipeline.addHook(new com.fusionml.bytecode.HookInjector.Hook(
                "net/minecraft/server/MinecraftServer", "runServer", "()V",
                "com/fusionml/crossload/CrossLoad", "onGameReady",
                "(Ljava/lang/Object;)V", com.fusionml.bytecode.HookInjector.Point.HEAD));
        if ("Fabric".equals(platform)) {
            session.pipeline.addHook(new com.fusionml.bytecode.HookInjector.Hook(
                    "net/fabricmc/loader/impl/launch/FabricMixinBootstrap", "init",
                    "(Lnet/fabricmc/api/EnvType;Lnet/fabricmc/loader/impl/FabricLoaderImpl;)V",
                    "com/fusionml/launch/CrossPlatformBridge", "onFabricMixinBootstrap",
                    "(Ljava/lang/Object;Ljava/lang/Object;)V",
                    com.fusionml.bytecode.HookInjector.Point.HEAD));
            console.accept("跨端挂载点：MixinEnvironment#gotoPhase（注册配置）+ "
                    + "FabricMixinBootstrap#init（加类路径）+ Minecraft#run / "
                    + "MinecraftServer#runServer（驱动入口点与注册表）");
        } else {
            // NeoForge/Forge 的 Mixin 服务由 ModLauncher 拉起，引导方法名随版本变动；
            // 跨端包在启动参数里就写进它的 legacyClassPath，
            // Mixin 配置则由游戏内的兜底路径注册（见 FusionHud.onGameLoopStarted）。
            console.accept("跨端挂载点：" + platform
                    + " 走启动参数 + 游戏内兜底注册（该端 Mixin 引导方法名随版本变动）；"
                    + "入口点驱动仍挂 Minecraft#run / MinecraftServer#runServer");
        }
    }

    /**
     * 找出"非宿主平台"的模组包与它们的 Mixin 配置，交给游戏进程里的跨端桥。
     *
     * <p>两条排除规则都是必要的：
     * <ul>
     *   <li>宿主平台自己能发现的模组（格式与宿主相同）由平台处理 —— 我们插手只会重复注册；</li>
     *   <li>双端包（同一个包里既有 fabric.mod.json 又有 neoforge.mods.toml）在宿主上会走宿主那一份，
     *       再把它另一端的 Mixin 配置也注册进来等于两套变体同时生效，是自找冲突。</li>
     * </ul>
     *
     * <p>数据用 JVM 系统属性传递：同一份类会被系统类加载器与平台类加载器各加载一份，
     * 静态字段是"各一份"，只有系统属性两边都看得见。
     */
    private static void bridgeCrossPlatform(FusionRuntime.Session session, Path modsDir,
                                            String platform,
                                            java.util.function.Consumer<String> console) {
        com.fusionml.meta.ModFormat host = switch (platform) {
            case "Fabric" -> com.fusionml.meta.ModFormat.FABRIC;
            case "NeoForge" -> com.fusionml.meta.ModFormat.NEOFORGE;
            case "Forge" -> com.fusionml.meta.ModFormat.FORGE;
            default -> null;
        };
        if (host == null) {
            return;
        }
        // 必须用会话里那份顺序：jarIndex 指的是它的下标，而它不是 mods 目录的原始顺序
        // （加载器按包体大小排过序，大包优先）。自己重扫目录会取到完全不相干的包，
        // 而且不会报错 —— 实测就是这样把几个纯 Fabric 模组当成"跨端包"报出来的。
        java.util.List<Path> jars = session.jarPaths;
        java.util.List<Path> foreignJars = new java.util.ArrayList<>();
        java.util.List<String> foreignConfigs = new java.util.ArrayList<>();
        java.util.List<String> labels = new java.util.ArrayList<>();
        for (com.fusionml.meta.ModMetadata mod : session.mods) {
            // 嵌套包（JiJ）暂不参与：它们的 jarIndex 指向解压缓存里的条目，
            // 路径语义与 mods 目录不同，等真有整合包这么用时再补。
            if (mod.nested || mod.jarIndex() < 0 || mod.jarIndex() >= jars.size()) {
                continue;
            }
            if (mod.format() == host || mod.alternativeFormats.contains(host)) {
                continue;
            }
            Path jar = jars.get(mod.jarIndex());
            if (!foreignJars.contains(jar)) {
                foreignJars.add(jar);
            }
            for (String cfg : mod.mixinConfigs) {
                if (!foreignConfigs.contains(cfg)) {
                    foreignConfigs.add(cfg);
                }
            }
            if (labels.size() < 6) {
                labels.add((mod.id.isEmpty() ? mod.jarName() : mod.id)
                        + "(" + mod.format().displayName() + ")");
            }
        }
        if (foreignJars.isEmpty() && foreignConfigs.isEmpty()) {
            console.accept("跨端：没有非 " + host.displayName() + " 的模组，本次无需桥接");
            return;
        }
        CrossPlatformBridge.setForeignJars(foreignJars);
        CrossPlatformBridge.setForeignMixinConfigs(foreignConfigs);
        // "对方平台的运行时"：事件总线与 API 类。
        //
        // 只做非宿主那一端：宿主自己的运行时本来就在跑。
        // 解析来源是本机同版本的该端实例（整合包通常本来就有另一端的启动项），
        // 拿不到就如实报出来，而不是让模组在半路抛 NoClassDefFoundError。
        // 非宿主那一端是谁：按"跨端包里实际出现的格式"判断，而不是按宿主反过来猜。
        // 一个整合包里理论上可以同时有 NeoForge 与 Forge 模组；本机目前的实现是
        // 一次只借一端的运行时，因此取数量多的那一端，并把另一端的数量如实报出来。
        int neo = 0;
        int forge = 0;
        for (com.fusionml.meta.ModMetadata mod : session.mods) {
            if (mod.format() == host || mod.alternativeFormats.contains(host)) {
                continue;
            }
            if (mod.format() == com.fusionml.meta.ModFormat.NEOFORGE) {
                neo++;
            } else if (mod.format() == com.fusionml.meta.ModFormat.FORGE) {
                forge++;
            }
        }
        boolean both = neo > 0 && forge > 0;
        String foreign = neo >= forge ? "neoforge" : "forge";
        if (both) {
            console.accept("跨端：同时存在 NeoForge " + neo + " 个与 Forge " + forge
                    + " 个模组，本次借 " + ("neoforge".equals(foreign) ? "NeoForge" : "Forge")
                    + " 的运行时驱动；另一端的入口点暂不驱动");
        }
        System.setProperty("fusion.foreignPlatform", foreign);
        java.util.List<Path> runtimeJars = java.util.List.of();
        try {
            com.fusionml.mc.McInstance instance =
                    com.fusionml.mc.McInstance.open(Path.of(System.getProperty("fusion.gameDir", ".")));
            com.fusionml.crossload.ForeignRuntime.Found found =
                    com.fusionml.crossload.ForeignRuntime.resolve(instance, foreign,
                            mcKeyOf(System.getProperty("fusion.version", "")), console);
            runtimeJars = found.jars();
            CrossPlatformBridge.setForeignRuntime(found.jars());
            console.accept("跨端运行时：" + found.summary());
            // 运行时按"有没有 FML 引导本体"拆成两拨，这是本轮修好
            // LambdaConversionException 的关键之一：
            //   · 对方 API（neoforge / bus / …）必须早登记 —— 补丁原版类在定义时
            //     就要解析 IRegistryExtension、BaseMappedRegistry 这些类型；
            //   · FML 引导本体必须晚登记 —— 早登记会让跨端模组的 Mixin 插件
            //     在平台还没初始化时去用它，直接崩在 "There is no current FML Loader"。
            java.util.List<Path> early =
                    com.fusionml.crossload.ForeignRuntime.earlySubset(found.jars());
            java.util.List<Path> late =
                    com.fusionml.crossload.ForeignRuntime.lateSubset(found.jars());
            CrossPlatformBridge.setForeignEarlyRuntime(early);
            // 对方引导包（FML）要不要也早登记：只有"类路径里放的是对方平台补丁过的
            // 原版字节"时才必须 —— 那些字节的静态初始化会回调 FML，
            // 而原版引导在游戏主函数里，早于我们的驱动挂载点。
            // 没有补丁基座时保持"晚登记"，因为早登记 FML 会让跨端模组的 Mixin 插件
            // 在平台没起来时去用它（那条路会直接崩）。
            String fmlMode = System.getProperty("fusion.fmlState", "auto").trim();
            boolean baseInUse = !System.getProperty(
                    com.fusionml.crossload.PatchedBase.PROP_RESOLVED, "").isBlank();
            boolean fmlEarly = switch (fmlMode.toLowerCase(java.util.Locale.ROOT)) {
                case "early" -> true;
                case "late", "off", "false" -> false;
                default -> baseInUse;
            };
            System.setProperty("fusion.fmlEarly", String.valueOf(fmlEarly));
            if (!late.isEmpty()) {
                console.accept("跨端 FML 引导包："
                        + (fmlEarly ? "与原版引导同期登记（因为用了对方平台的补丁原版字节）"
                                    : "推迟到驱动那一刻登记"));
            }
            if (!early.isEmpty() || !late.isEmpty()) {
                console.accept("跨端运行时分拨：早登记 " + early.size() + " 个（对方 API），"
                        + (fmlEarly ? "含引导本体的 " + late.size() + " 个也已同期登记"
                                    : "驱动时再登记 " + late.size() + " 个（含 FML 引导本体）"));
            }
        } catch (Throwable t) {
            console.accept("跨端运行时解析失败（跨端模组将只做 Mixin，不做入口点驱动）：" + t);
        }
        // 原版"密封类型"的解除密封：对方平台的 API 类里有相当一部分是"靠对方平台给原版
        // 打补丁加 permits 才能存在"的实现者；宿主上是没有那份补丁的，不补这一手，
        // 加载对方的类会以 IncompatibleClassChangeError 失败（报的话听起来像模组写错了）。
        // 名单是**算出来的**：只覆盖"本机实际装载的跨端包"直接需要的那些原版类型。
        try {
            String clientJar = System.getProperty("fusion.clientJar", "");
            if (!clientJar.isBlank()) {
                java.util.List<Path> scan = new java.util.ArrayList<>(foreignJars);
                scan.addAll(runtimeJars);
                java.util.List<String> targets =
                        com.fusionml.crossload.SealedTargets.compute(scan, Path.of(clientJar), console);
                for (String t : targets) {
                    session.pipeline.addSealedTarget(t);
                }
                if (!targets.isEmpty()) {
                    int show = Math.min(4, targets.size());
                    console.accept("跨端：需解除密封的原版类型 " + targets.size() + " 个（"
                            + String.join("、", targets.subList(0, show).stream()
                                    .map(s -> s.substring(s.lastIndexOf('/') + 1)).toList())
                            + (targets.size() > show ? "…" : "") + "）");
                }
            }
        } catch (Throwable t) {
            console.accept("跨端密封修补计算失败（相关跨端模组可能加载不了）：" + t);
        }
        // 游戏内的兜底路径也用同一份配置：挂载点万一没挂上（例如平台方法签名变了），
        // 至少还有一次注册机会，而且日志会明确写出走的是兜底。
        com.fusionml.hud.FusionHud.setExtraMixinConfigs(foreignConfigs);
        console.accept("跨端：" + foreignJars.size() + " 个非 " + host.displayName() + " 模组包（"
                + String.join("、", labels) + (labels.size() >= 6 ? "…" : "") + "），Mixin 配置 "
                + foreignConfigs.size() + " 条");
    }

    /**
     * 版本定义里的 Minecraft 版本键（例如 {@code 26.3-Fabric 0.19.5} → {@code 26.3}）。
     *
     * <p>用来在"本机另一端的实例"里挑同一个小版本的运行时：
     * 跨端借来的 API 必须与被驱动的模组同一代，否则比不借还糟。
     */
    private static String mcKeyOf(String versionId) {
        if (versionId == null) {
            return "";
        }
        String s = versionId.trim();
        int cut = s.length();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '-' || c == '_' || c == ' ') {
                cut = i;
                break;
            }
        }
        return s.substring(0, cut);
    }
}

