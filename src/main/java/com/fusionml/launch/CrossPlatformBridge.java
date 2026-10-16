package com.fusionml.launch;

import com.fusionml.mixin.FusionMixinRegistry;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 跨端桥：把"非宿主平台"的模组包接进同一次运行。
 *
 * <p>一个整合包里同时放 Fabric 与 NeoForge/Forge 模组时，宿主平台（被启动的那个）
 * 只会发现自己格式的模组。另外两端的模组包既不在平台类路径里，它们的 Mixin 配置
 * 也没人注册 —— 结果就是"文件在 mods 目录里，但完全没参与这次运行"。
 * 这个类负责把这两件事补上：
 * <ol>
 *   <li><b>进类路径</b>：用平台自己的扩展点把模组包加进平台类加载器
 *       （Fabric 是 {@code FabricLauncher.addToClassPath}）。
 *       只有进了同一个类加载器，它们的类才会被 Mixin 变换、才谈得上"同进程"。</li>
 *   <li><b>进 Mixin 服务</b>：把它们的 Mixin 配置注册进宿主平台已经在用的那个服务。</li>
 * </ol>
 *
 * <p><b>调用时机是关键</b>：必须早于宿主平台准备 Mixin 配置的时刻
 * （Fabric 里就是 {@code FabricMixinBootstrap.init()} —— 它一执行完，配置集合就定型，
 * 之后再加进来的配置不会作用于任何类）。所以本类的方法是由我们自研的字节码注入
 * 挂在那个方法<b>头部</b>上被调用的，而不是在游戏跑起来之后。
 *
 * <p>跨进程传递数据一律用 JVM 系统属性：同一份类可能被系统类加载器与平台类加载器
 * 各加载一份，静态字段是"各一份"，只有系统属性是两边都看得见的。
 */
public final class CrossPlatformBridge {

    private static final String PROP_JARS = "fusion.foreignModJars";
    /**
     * 跨端"运行时"包列表（pathSeparator 分隔）。
     *
     * <p>与模组包分开是因为它们的角色不同：模组包是"要加载的东西"，
     * 运行时包是"加载它们所需要的那一端的 API 与事件总线"
     * （NeoForge 的 neoforge / fml / bus 等，来自本机同版本的另一端实例）。
     * 两者都必须进宿主平台那一个加载器，否则跨端模组的类与对方的 API 会分居两处。
     */
    private static final String PROP_RUNTIME = "fusion.foreignRuntimeJars";
    /**
     * 跨端运行时里"必须早登记"的那一拨（不含 FML 引导本体），逗号——
     * 也就是文件分隔符分隔。为什么它必须早于原版引导：对方平台的补丁原版
     * jar 里，原版类型的<b>接口列表与超类</b>直接指向对方的 API 类
     * （{@code Registry implements IRegistryExtension}、
     * {@code MappedRegistry extends BaseMappedRegistry}），
     * 而 JVM 在定义类时就要解析这些类型。晚一步登记，原版引导阶段一加载
     * {@code Registry} 就是 {@code NoClassDefFoundError}。
     */
    private static final String PROP_EARLY_RUNTIME = "fusion.foreignEarlyRuntimeJars";
    /** 跨端 Mixin 配置名列表（逗号分隔）。 */
    private static final String PROP_CONFIGS = "fusion.extraMixinConfigs";

    private static volatile boolean done;
    /**
     * 重入保护。
     *
     * <p>我们的注册动作本身会调用 Mixin（{@code Mixins.addConfiguration} → 创建/取用默认环境），
     * 而挂载点又在 Mixin 自己的方法里。没有这个标志就会出现"注册过程中再次进入注册"。
     */
    private static boolean running;

    private CrossPlatformBridge() {
    }

    /**
     * 注入到 {@code FabricMixinBootstrap.init(EnvType, FabricLoaderImpl)} 头部。
     *
     * <p>这里<b>只做类路径</b>，一个 Mixin 的类都不碰。原因是实测出来的硬约束：
     * 这个方法执行的第一件事是 {@code MixinBootstrap.init()}，也就是说此刻 Mixin 还没初始化。
     * 此时只要访问 {@code org.spongepowered.asm.mixin.Mixins}，它的静态初始化就会去挑选
     * Mixin 服务，而候选里有依赖 {@code net.minecraft.launchwrapper.LaunchClassLoader} 的
     * 老式服务 → 抛 {@code ExceptionInInitializerError}。
     * Java 的类初始化失败是<b>不可重试</b>的：之后任何人再碰这个类都会得到
     * {@code NoClassDefFoundError}。上一次实测就是这样把整个启动搞崩的
     * （Fabric 自己的 Mixin 引导紧跟着也失败了）。
     */
    public static void onFabricMixinBootstrap(Object envType, Object loaderImpl) {
        if (done) {
            return;
        }
        try {
            List<Path> jars = readPaths(PROP_JARS);
            if (jars.isEmpty()) {
                return;
            }
            System.out.println("[FusionML/跨端] 宿主 Fabric：把 " + jars.size()
                    + " 个跨端模组包加进平台类路径");
            System.out.println("[FusionML/跨端] 已加入平台类路径 "
                    + addToFabricClassPath(jars) + "/" + jars.size() + " 个包");
        } catch (Throwable t) {
            System.out.println("[FusionML/跨端] 加入类路径未完成（不影响宿主平台）：" + t);
        }
    }

    /**
     * 注入到 {@code MixinEnvironment.gotoPhase(Phase)} 头部：这是"能安全碰 Mixin 的最早时刻"。
     *
     * <p>为什么选这个方法：它由 Mixin 自己的代码调用，所以进入这里时 Mixin 一定已经初始化
     * （不会踩上面那个"类初始化失败"的雷）；而它又是每次阶段切换的入口，
     * 我们挂在这里就意味着"任何一次配置定型之前"都能先把自己的配置放进去。
     * 阶段顺序是 NOT_INITIALISED → PREINIT → INIT → DEFAULT，前两个阶段跳过：
     * 那时 {@code MixinBootstrap.init()} 还在跑，重入进去会干扰宿主平台自己的引导。
     */
    public static void onMixinPhaseChange(Object phase) {
        String name = String.valueOf(phase);
        if (name.contains("NOT_INITIALISED") || name.contains("PREINIT")) {
            return;
        }
        run(System.getProperty("fusion.hostPlatform", "Fabric"));
    }

    public static void run(String host) {
        if (done || running) {
            return;
        }
        running = true;
        try {
            perform(host);
            done = true;
        } catch (Throwable t) {
            // 跨端桥失败绝不能拦住宿主平台：它是增强，不是前提。
            System.out.println("[FusionML] 跨端桥未完成（不影响宿主平台）：" + t);
        } finally {
            running = false;
        }
    }

    private static void perform(String host) {
        List<Path> jars = readPaths(PROP_JARS);
        List<Path> runtime = readPaths(PROP_RUNTIME);
        List<String> configs = readList(PROP_CONFIGS);
        if (jars.isEmpty() && configs.isEmpty()) {
            return;
        }
        System.out.println("[FusionML/跨端] 宿主平台 " + host + "：跨端模组包 " + jars.size()
                + " 个、跨端运行时包 " + runtime.size() + " 个、待注册 Mixin 配置 "
                + configs.size() + " 条");
        // NeoForge / Forge 宿主的层在启动参数里就定好了（legacyClassPath），
        // 这里只补一遍 Fabric 的运行时扩展点 —— 也允许在这里重试一次：

        if (!jars.isEmpty() && "Fabric".equals(host)) {
            addToFabricClassPath(jars);
        } else if (!jars.isEmpty()) {
            addToHostClassPath(host, jars);
        }
        // 对方平台 API 的那一拨必须在这里（原版引导之前）就位：

        // 这一拨里**不含** FML 引导本体，所以不会把跨端模组的 Mixin 插件带进
        // "There is no current FML Loader" 那个启动崩溃（见下面的长注释）。
        List<Path> earlyRuntime = readPaths(PROP_EARLY_RUNTIME);
        // 用了补丁原版基座时，对方平台的引导包（FML）这一拨也必须在原版引导之前就位，

        // 这不是"能早则早"：早晚都可以的时候我们选择晚（见下面的长注释），

        List<Path> fmlRuntime = List.of();
        if (Boolean.parseBoolean(System.getProperty("fusion.fmlEarly", "false"))) {
            List<Path> all = readPaths(PROP_RUNTIME);
            List<Path> present = new ArrayList<>(all.size());
            for (Path p : all) {
                if (java.nio.file.Files.isRegularFile(p) && !earlyRuntime.contains(p)) {
                    present.add(p);
                }
            }
            fmlRuntime = present;
        }
        if (!earlyRuntime.isEmpty() || !fmlRuntime.isEmpty()) {
            List<Path> present = new ArrayList<>(earlyRuntime.size() + fmlRuntime.size());
            for (Path p : earlyRuntime) {
                if (java.nio.file.Files.isRegularFile(p)) {
                    present.add(p);
                }
            }
            present.addAll(fmlRuntime);
            int ok = present.isEmpty() ? 0 : addToHostClassPath(host, present);
            System.out.println("[FusionML/跨端] 早登记对方 API " + ok + "/" + earlyRuntime.size()
                    + " 个（补丁原版类定义时要解析它们，故必须早于原版引导）"
                    + (fmlRuntime.isEmpty() ? ""
                            : "，另加对方引导包 " + fmlRuntime.size() + " 个（补丁原版字节需要它）"));
            if (!fmlRuntime.isEmpty()) {
                com.fusionml.crossload.CrossLoad.standUpFml(
                        line -> System.out.println("[FusionML/跨端] " + line));
            }
        }
        // 注意：跨端"运行时"包里**含 FML 引导本体**的那一拨故意不在这里登记。

        // 这件事踩过一次，值得写下来：如果在宿主平台准备 Mixin 配置之前就把对方平台的
        // **FML 引导本体**放到类路径上，跨端模组里那些 Mixin 插件、Mixin 类就会

        // "There is no current FML Loader" —— 结果是启动直接崩
        // （类根本找不到时 Mixin 只记一笔就继续）。

        // 见 CrossLoad#onGameReady；而不含它的那一拨必须早登记（上面刚做完）。

        // Mixin 配置进同一个服务。
        // 用**宿主平台的类加载器**：混入类与配置资源都要由它来解析，
        // 拿错了加载器会得出"资源找不到"这种与事实相反的结论（第一次就是这么误判的）。
        // Mixin 本身只有一个类对象（宿主把 org.spongepowered.asm 委派给父加载器），
        // 所以换加载器不影响 Mixin 静态状态，只影响资源与类能不能被看见。
        ClassLoader hostLoader = hostClassLoader();
        ClassLoader cl = hostLoader != null ? hostLoader : CrossPlatformBridge.class.getClassLoader();
        System.out.println("[FusionML/跨端] 宿主类加载器 " + cl.getClass().getName());

        // 会以 NoClassDefFoundError 收场（实测：日志里能同时看到
        // "IRegistryExtension 由宿主定义成功" 与 "定义 Registry 时解析 IRegistryExtension 失败"）。
        // 先把这些对方类型解析掉，顺序就成立了。

        List<String> warm = com.fusionml.crossload.PatchedBase.warmUpTypes();
        if (!warm.isEmpty()) {
            int warmOk = 0;
            List<String> warmBad = new ArrayList<>(4);
            for (String t : warm) {
                // 安全闸：模组 Mixin 的目标一律不预热。预热也是 Class.forName，
                // 而 Mixin 的规则是"目标类一旦被加载就不能再注入"——
                // 替模组抢跑的代价是整局启动崩掉（见 identity 的注释里那次事故）。
                if (isMixinTarget(t)) {
                    continue;
                }
                String name = t.replace('/', '.');
                try {
                    Class.forName(name, false, cl);
                    warmOk++;
                } catch (Throwable e) {
                    if (warmBad.size() < 4) {
                        warmBad.add(name + "（" + e.getClass().getSimpleName() + "）");
                    }
                }
            }
            System.out.println("[FusionML/跨端] 预热解析对方类型 " + warmOk + "/" + warm.size() + " 个"
                    + (warmBad.isEmpty() ? "" : "；失败：" + String.join("、", warmBad)));
        }
        // 补丁原版基座到底有没有被宿主"看见"，以及它是不是排在原版 jar 之前：
        // 直接问平台类加载器"你从哪里解析 Registry"。这个问题比看类路径顺序可靠得多 ——
        // 实测过：把基座排进 -cp 的最前面，宿主依然会从原版 jar 里取 Registry
        // （平台的游戏类来源不是普通的类路径顺序），于是对方模组一注册就抛

        String baseJar = System.getProperty(
                com.fusionml.crossload.PatchedBase.PROP_RESOLVED, "");
        if (!baseJar.isBlank()) {
            java.net.URL src = cl.getResource("net/minecraft/core/Registry.class");
            System.out.println("[FusionML/跨端] 宿主解析 net.minecraft.core.Registry 的来源："
                    + (src == null ? "（看不到）" : src));
            System.out.println("[FusionML/跨端] 补丁原版基座：" + baseJar
                    + (src != null && src.toString().contains(
                            java.nio.file.Path.of(baseJar).getFileName().toString())
                            ? "——已被宿主采用" : "——宿主没有采用它"));
            // 类加载器身份：跨端最容易出的不是"类找不到"，而是"同一个类被两份加载器各定义一次"。
            // 那种情况下类型判断（lambda 的接收者检查、instanceof、cast）会以非常难读的方式失败，

            identity("net.minecraft.core.Registry", cl);
            identity("net.minecraft.core.MappedRegistry", cl);
            identity("net.neoforged.neoforge.registries.IRegistryExtension", cl);
            identity("net.neoforged.neoforge.registries.DeferredRegister", cl);
        }
        // 资源可见性自检：把"配置读不到"挡在注册之前，并直接说清是哪一条、什么原因。
        // 这条检查是有来历的：资源加载与类加载在宿主平台里是两条路径，
        // 只登记了类路径而没登记资源路径时，Mixin 会报一句非常容易被误读的话。
        int invisible = 0;
        StringBuilder missing = new StringBuilder();
        for (String cfg : configs) {
            if (cl.getResource(cfg) == null) {
                invisible++;
                if (invisible <= 4) {
                    missing.append(missing.length() == 0 ? "" : "、").append(cfg);
                }
            }
        }
        if (invisible > 0) {
            System.out.println("[FusionML/跨端] " + invisible + "/" + configs.size()
                    + " 条配置在平台类路径里看不到（" + missing + (invisible > 4 ? "…" : "")
                    + "），这些会注册失败");
        }
        // 逐个确认"我们加进去的 jar"到底能不能被宿主看到。

        for (Path jar : jars) {
            String sample = sampleEntry(jar);
            boolean visible = sample != null && cl.getResource(sample) != null;
            System.out.println("[FusionML/跨端]   " + (visible ? "宿主可见" : "宿主看不到")
                    + " " + jar.getFileName()
                    + (sample == null ? "（条目读取失败）" : "（样本 " + sample + "）"));
        }
        for (String line : FusionMixinRegistry.diagnose(cl)) {
            System.out.println("[FusionML/跨端] " + line);
        }
        FusionMixinRegistry.Result r = FusionMixinRegistry.register(cl, configs,
                line -> System.out.println("[FusionML/跨端] " + line));
        System.out.println("[FusionML/跨端] Mixin 配置：" + r.summary());
        for (String note : r.notes()) {
            System.out.println("[FusionML/跨端]   · " + note);
        }

        System.setProperty("fusion.bridgeConfigsVerified", String.valueOf(r.verified()));
        System.setProperty("fusion.bridgeConfigsFailed", String.valueOf(r.failed()));
    }

    /**
     * 把模组包加进宿主平台的类路径。
     *
     * <p>Fabric 用的是它自己的公开扩展点 {@code FabricLauncher.addToClassPath}：
     * 走这条路的包会被平台类加载器（Knot）正常加载，因此照样经过 Mixin 变换，
     * 也不需要把模组塞进 JVM 类路径 —— 后者会让同一个类被两份加载器各定义一次，
     * 表现为"库自己类之间的 ClassCastException"，我们踩过这个坑。
     */
    private static int addToHostClassPath(String host, List<Path> jars) {
        if ("Fabric".equals(host)) {
            return addToFabricClassPath(jars);
        }
        // NeoForge / Forge 走的不是"运行时加类路径"，而是 ModLauncher 的层（layer）机制：

        // （见 LaunchPlan），这里如实说明而不是假装成功。
        System.out.println("[FusionML/跨端] " + host
                + " 的类路径由启动参数决定，跨端包已在启动时写入 legacyClassPath");
        return jars.size();
    }

    private static int addToFabricClassPath(List<Path> jars) {
        Object launcher;
        try {
            Class<?> base = Class.forName("net.fabricmc.loader.impl.launch.FabricLauncherBase");
            launcher = base.getMethod("getLauncher").invoke(null);
        } catch (Throwable t) {
            System.out.println("[FusionML/跨端] 拿不到 Fabric 启动器：" + t);
            return 0;
        }
        if (launcher == null) {
            System.out.println("[FusionML/跨端] Fabric 启动器尚未挂载，跳过类路径扩展");
            return 0;
        }
        Method add = null;
        for (Method m : launcher.getClass().getMethods()) {
            if (m.getName().equals("addToClassPath") && m.getParameterCount() == 2
                    && Path.class.isAssignableFrom(m.getParameterTypes()[0])) {
                add = m;
                break;
            }
        }
        if (add == null) {
            System.out.println("[FusionML/跨端] 这份 Fabric 的启动器没有 addToClassPath，跳过");
            return 0;
        }
        int ok = 0;
        for (Path jar : jars) {
            try {
                add.invoke(launcher, jar, new String[0]);
                addResourceUrl(launcher, jar);
                ok++;
            } catch (Throwable t) {
                System.out.println("[FusionML/跨端] 加入类路径失败 " + jar.getFileName() + "："
                        + t.getCause());
            }
        }
        return ok;
    }

    /**
     * 除了"代码来源"，还要把 jar 登记成"资源来源"。
     *
     * <p>这是实测踩出来的第二个坑：Knot 的类加载与资源加载是两条不同的路径 ——
     * {@code addToClassPath} 只把路径登记进 codeSources（管类加载与元数据），
     * 而 {@code getResourceAsStream} 走的是它内部的 DynamicURLClassLoader。
     * 结果就是：java 类能加载了、Mixin 配置 JSON 却读不到，Mixin 报
     * {@code The specified resource 'x.mixins.json' was invalid or could not be read}。
     * 那句话字面上像"配置写错了"，实际原因是"这条资源路径没被登记"。
     */
    private static void addResourceUrl(Object launcher, Path jar) {
        try {
            Object hostLoader = launcher.getClass().getMethod("getTargetClassLoader").invoke(launcher);
            if (!(hostLoader instanceof ClassLoader loader)) {
                System.out.println("[FusionML/跨端] 宿主还没建好类加载器，资源来源暂未登记");
                return;
            }
            for (Method m : loader.getClass().getMethods()) {
                if (m.getName().equals("addUrlFwd") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == java.net.URL.class) {
                    m.setAccessible(true);
                    m.invoke(loader, jar.toUri().toURL());
                    return;
                }
            }
            System.out.println("[FusionML/跨端] 宿主类加载器没有 addUrlFwd，"
                    + "跨端包里的资源可能看不到");
        } catch (Throwable t) {
            System.out.println("[FusionML/跨端] 登记资源来源失败：" + t);
        }
    }

    /**
     * 从 jar 里取一个代表性条目名，用来验证"宿主到底能不能看到这个包里的东西"。
     *
     * <p>优先取 mixin 配置 JSON（就是注册时要读的那类资源），取不到再退而取一个 class。
     * 用 JDK 自带的 zip 只是为了这一条诊断，不涉及加载器的任何热路径。
     */
    private static String sampleEntry(Path jar) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
            String fallback = null;
            java.util.Enumeration<? extends java.util.zip.ZipEntry> e = zip.entries();
            while (e.hasMoreElements()) {
                String name = e.nextElement().getName();
                if (name.endsWith("mixins.json")) {
                    return name;
                }
                if (fallback == null && name.endsWith(".class")) {
                    fallback = name;
                }
            }
            return fallback;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 宿主平台的类加载器。
     *
     * <p>必须是它，而不是我们自己的类加载器：配置资源与混入类都由宿主平台解析，
     * 拿错加载器会得出与事实相反的结论（第一次就是这样把"资源其实可见"误判成"找不到"）。
     */
    public static ClassLoader hostClassLoader() {
        try {
            Class<?> base = Class.forName("net.fabricmc.loader.impl.launch.FabricLauncherBase");
            Object launcher = base.getMethod("getLauncher").invoke(null);
            if (launcher == null) {
                return null;
            }
            Object loader = launcher.getClass().getMethod("getTargetClassLoader").invoke(launcher);
            return loader instanceof ClassLoader l ? l : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public static void setForeignJars(List<Path> jars) {
        StringBuilder sb = new StringBuilder();
        for (Path p : jars) {
            if (sb.length() > 0) {
                sb.append(File.pathSeparator);
            }
            sb.append(p.toAbsolutePath());
        }
        System.setProperty(PROP_JARS, sb.toString());
    }

    public static void setForeignMixinConfigs(List<String> configs) {
        System.setProperty(PROP_CONFIGS, String.join(",", configs));
    }

    public static void setForeignRuntime(List<Path> jars) {
        StringBuilder sb = new StringBuilder();
        for (Path p : jars) {
            if (sb.length() > 0) {
                sb.append(File.pathSeparator);
            }
            sb.append(p.toAbsolutePath());
        }
        System.setProperty(PROP_RUNTIME, sb.toString());
    }

    public static List<Path> foreignRuntime() {
        return readPaths(PROP_RUNTIME);
    }

    /**
     * 早登记的那一拨运行时包（对方 API，不含 FML 引导本体）。
     *
     * <p>它和"驱动时才登记的运行时"是同一批包的两半，分界的理由见
     * {@code ForeignRuntime#carriesFml}：API 要早到"原版类定义时就能解析"，
     * FML 引导本体要晚到"跨端模组的 Mixin 插件不会去用它"。
     */
    public static void setForeignEarlyRuntime(List<Path> jars) {
        StringBuilder sb = new StringBuilder();
        for (Path p : jars) {
            if (sb.length() > 0) {
                sb.append(File.pathSeparator);
            }
            sb.append(p.toAbsolutePath());
        }
        System.setProperty(PROP_EARLY_RUNTIME, sb.toString());
    }

    public static List<Path> foreignEarlyRuntime() {
        return readPaths(PROP_EARLY_RUNTIME);
    }

    /**
     * 把一批包登记进宿主平台的类路径（游戏跑起来之后调用）。
     *
     * @return 成功登记的数量
     */
    public static int addToHostClassPath(List<Path> jars) {
        if (jars.isEmpty()) {
            return 0;
        }
        String host = System.getProperty("fusion.hostPlatform", "Fabric");
        return "Fabric".equals(host) ? addToFabricClassPath(jars) : addToHostClassPath(host, jars);
    }

    /**
     * 打印一行"这个关键类型由谁定义、直接实现了谁"。
     *
     * <p>跨端最难的故障不是"类找不到"，而是"同一个类被两份加载器各定义一次"：
     * 那种情况下类型判断（lambda 的接收者检查、instanceof、cast）会以非常难读的方式失败，
     * 而日志里看什么都正常。把加载器身份与接口列表摆出来，一眼就能分清是哪一种。
     *
     * <h2>但它有副作用，而且这个副作用炸过整局游戏</h2>
     *
     * <p>{@code Class.forName(name, false, cl)} 里的 {@code false} 只是"不初始化"，
     * <b>加载动作仍然发生了</b>。而模组平台的 Mixin 有一条硬规则：
     * <b>目标类一旦被加载，就不能再注入</b>。于是"打印一行诊断"就足以让模组的 Mixin 失败，
     * 而且报错离原因极远。
     *
     * <p>实测事故（这一行诊断直接导致启动崩溃）：
     *
     * <pre>
     * MixinTargetAlreadyLoadedException: Critical problem:
     *   yumi_mc_core.neoforge.mixins.json:DeferredRegisterMixin from mod yumi_mc_core
     *   target net.neoforged.neoforge.registries.DeferredRegister was loaded too early.
     *   → MixinApplyError ... FAILED during PREPARE
     *   → axiom 的 preLaunch 入口点被连带炸掉 → A mod crashed on startup!
     * </pre>
     *
     * <p>所以诊断必须让路：<b>凡是被任何模组 Mixin 盯上的类，一律不加载、只报告"跳过"</b>。
     * 诊断的价值是排查问题，不能自己变成问题。
     */
    private static void identity(String name, ClassLoader cl) {
        if (isMixinTarget(name)) {
            System.out.println("[FusionML/跨端] 类型 " + name
                    + " 是模组 Mixin 的目标，跳过身份检查（提前加载它会导致 MixinTargetAlreadyLoadedException）");
            return;
        }
        try {
            Class<?> c = Class.forName(name, false, cl);
            StringBuilder ifaces = new StringBuilder();
            for (Class<?> i : c.getInterfaces()) {
                if (ifaces.length() > 0) {
                    ifaces.append('、');
                }
                ifaces.append(i.getSimpleName()).append('@')
                        .append(Integer.toHexString(System.identityHashCode(
                                i.getClassLoader())));
            }
            ClassLoader own = c.getClassLoader();
            String owner = own == null ? "bootstrap"
                    : own.getClass().getSimpleName() + '@'
                            + Integer.toHexString(System.identityHashCode(own));
            System.out.println("[FusionML/跨端] 类型 " + name + " 由 " + owner
                    + " 定义；直接接口 " + (ifaces.length() == 0 ? "（无）" : ifaces)
                    + (c.getSuperclass() == null ? "" : "；超类 " + c.getSuperclass().getName()));
        } catch (Throwable t) {
            System.out.println("[FusionML/跨端] 类型 " + name + " 读取失败：" + t);
        }
    }

    private static volatile java.util.Set<String> mixinTargets;

    /**
     * 这个类型是不是某个模组 Mixin 的目标 —— 是的话<b>绝对不能提前加载</b>。
     *
     * <p>判据来自 {@link com.fusionml.crossload.PatchedBase#PROP_MIXIN_TARGETS}：
     * 那是启动侧扫宿主平台模组的 Mixin 配置算出来的目标清单。
     * 名单是 JVM 级属性，所以任何一份类加载器里的桥接代码都能读到。
     */
    private static boolean isMixinTarget(String internalOrBinary) {
        java.util.Set<String> set = mixinTargets;
        if (set == null) {
            set = new java.util.HashSet<>(1024);

            String file = System.getProperty(
                    com.fusionml.crossload.PatchedBase.PROP_TARGETS_FILE, "");
            boolean loaded = false;
            if (!file.isBlank()) {
                try {
                    for (String line : java.nio.file.Files.readAllLines(
                            java.nio.file.Path.of(file), java.nio.charset.StandardCharsets.UTF_8)) {
                        String s = line.trim();
                        if (!s.isEmpty()) {
                            set.add(s);
                        }
                    }
                    loaded = true;
                } catch (Throwable t) {
                    System.out.println("[FusionML/跨端] 黑名单文件读取失败（" + t + "）：" + file);
                }
            }
            if (!loaded) {
                String raw = System.getProperty(
                        com.fusionml.crossload.PatchedBase.PROP_MIXIN_TARGETS, "");
                for (String p : raw.split(",")) {
                    String s = p.trim();
                    if (!s.isEmpty()) {
                        set.add(s);
                    }
                }
                loaded = !raw.isEmpty();
            }
            // 只在"确实读到名单"时才缓存。读不到就缓存，会把"暂时没有名单"
            // 永久固化成"永远没有名单"—— 那正是这次事故的形态，不能再犯。
            if (loaded) {
                mixinTargets = set;
            }
        }
        if (set.isEmpty()) {
            return false;
        }
        return set.contains(internalOrBinary)
                || set.contains(internalOrBinary.replace('.', '/'));
    }

    private static List<Path> readPaths(String key) {
        String raw = System.getProperty(key, "");
        List<Path> out = new ArrayList<>(4);
        if (raw.isBlank()) {
            return out;
        }
        for (String part : raw.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (!part.isBlank()) {
                out.add(Path.of(part));
            }
        }
        return out;
    }

    private static List<String> readList(String key) {
        String raw = System.getProperty(key, "");
        List<String> out = new ArrayList<>(4);
        if (raw.isBlank()) {
            return out;
        }
        for (String part : raw.split(",")) {
            if (!part.isBlank() && !out.contains(part)) {
                out.add(part);
            }
        }
        return out;
    }
}
