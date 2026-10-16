package com.fusionml.launch;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

// 引导 Fabric 官方运行时（内嵌驱动）。

// 分工要讲清楚，否则很容易越界：
//   * 我们的加载器负责：发现、并行、类索引、字节码注入、三端聚合与性能优化；
//   * 模组运行时所需的 Fabric 事实（游戏在哪、版本多少、类路径是什么、Mixin 怎么接）
//     由 Fabric 自己的实现负责 —— 我们只是把它按正确顺序启动起来。

// 启动顺序严格照 Knot 来，一步都不能少，也不能多：
//   1. FabricLauncherBase.setLauncher(我们的 launcher)   ← 必须是第一个，否则 load() 直接 NPE

//   3. provider.locateGame(launcher, args)                ← 由它去找游戏 jar 与版本

//   5. FabricLoaderImpl.INSTANCE.setGameProvider(provider) + load()

// 关键教训（踩过一次）：不要再调用版本定义里的 mainClass（那是 KnotClient）。
// KnotClient 本身就是 Fabric 的启动器，它也会去 setLauncher，于是会直接抛

public final class FabricRuntime {

    private static volatile String entrypoint;
    private static volatile boolean bootstrapped;
    private static volatile boolean launcherInstalled;
    private static volatile Object gameClassLoader;
    private static volatile Object gameProvider;

    private FabricRuntime() {
    }

    public static String entrypoint() {
        return entrypoint;
    }

    public static boolean bootstrapped() {
        return bootstrapped;
    }

    /**
     * 我们是否已经把 launcher 装到了 FabricLauncherBase 上。
     *
     * <p>这个标志很重要：一旦装上，平台自己的启动器（Knot/KnotClient）就再也起不来了
     * （它会因为重复设置 launcher 直接抛异常）。所以后续必须改交游戏本体主类。
     */
    public static boolean launcherInstalled() {
        return launcherInstalled;
    }

    /**
     * 由平台的类加载器启动游戏。
     *
     * <p>必须走这条路径，而不是我们自己反射调用游戏主类：只有平台的类加载器会在定义类之前
     * 把字节码交给 Mixin/入口点改写器，模组的 Mixin 才会生效、onInitialize 才会被调用。
     */
    public static void launchGame() throws Exception {
        Object loader = gameClassLoader;
        Object provider = gameProvider;
        if (loader == null || provider == null) {
            throw new IllegalStateException("平台类加载器尚未就绪");
        }
        Class<?> providerIface = Class.forName("net.fabricmc.loader.impl.game.GameProvider",
                false, FabricRuntime.class.getClassLoader());
        providerIface.getMethod("launch", ClassLoader.class).invoke(provider, loader);
    }

    /**
     * 引导 Fabric 运行时。必须在实例化任何 Fabric 模组入口点之前调用。
     *
     * @return true 表示成功；失败时打印可读原因并返回 false（调用方回退到自研注册流程）
     */
    public static boolean bootstrap(Path gameDir, Path modsDir, String gameVersion,
                                    boolean client, String[] gameArguments, Path clientJar,
                                    Path gameClasspathFile,
                                    Consumer<String> log) {
        ClassLoader cl = FabricRuntime.class.getClassLoader();
        Class<?> launcherIface;
        Class<?> providerIface;
        Class<?> implClass;
        Class<?> mcProviderClass;
        try {
            launcherIface = Class.forName("net.fabricmc.loader.impl.launch.FabricLauncher",
                    false, cl);
            providerIface = Class.forName("net.fabricmc.loader.impl.game.GameProvider", false, cl);
            implClass = Class.forName("net.fabricmc.loader.impl.FabricLoaderImpl", false, cl);
            mcProviderClass = Class.forName(
                    "net.fabricmc.loader.impl.game.minecraft.MinecraftGameProvider", false, cl);
        } catch (ClassNotFoundException e) {
            log.accept("环境里没有 fabric-loader，跳过 Fabric 运行时引导"
                    + "（Forge / NeoForge 实例属于正常情况）");
            return false;
        }
        try {
            // 1. 用 Fabric 自带的 Minecraft GameProvider（它知道怎么找游戏、认版本、

            Object provider = mcProviderClass.getDeclaredConstructor().newInstance();
            // 2. 创建平台的类加载器。
            //    KnotClassLoader 是包私有的，但它是"让 Mixin 真正生效"的唯一入口：
            //    它会在 defineClass 之前把字节码交给变换器。用系统类加载器加载游戏，

            Object classLoader = createGameClassLoader(cl, mcProviderClass, providerIface, provider);
            gameClassLoader = classLoader;
            gameProvider = provider;
            // 3. launcher（必须由我们提供：它回答"类路径是什么、用哪个类加载器"）
            Object launcher = installLauncher(cl, launcherIface, gameDir, gameVersion, client,
                    gameArguments, gameClasspathFile, classLoader, log);
            // 4. 把游戏类路径交给平台类加载器 —— 少了这一步，它什么都找不到
            feedClassPath(classLoader, gameClasspathFile, log);
            Object impl = implClass.getField("INSTANCE").get(null);
            // 顺序很关键：必须先 setGameProvider 再 locateGame/initialize。
            // 反过来的话，provider 内部会去查 FabricLoaderImpl.getGameProvider()，

            implClass.getMethod("setGameProvider", providerIface).invoke(impl, provider);
            providerIface.getMethod("locateGame", launcherIface, String[].class)
                    .invoke(provider, launcher, gameArguments);
            providerIface.getMethod("initialize", launcherIface).invoke(provider, launcher);
            // 5. 交给 Fabric 自己加载模组
            implClass.getMethod("load").invoke(impl);

            // 哪些该委派给父加载器"。漏掉这一步的表现是：
            //   * 库被两个类加载器各加载一份 → 库自身类之间 ClassCastException；
            //   * 或者库完全没被暴露给游戏 → joptsimple.ValueConverter 之类的 ClassNotFoundException。
            // 这两个现象我们各踩过一次，所以这一步不能省。
            providerIface.getMethod("unlockClassPath", launcherIface).invoke(provider, launcher);
            // 6. 初始化 Mixin 通道 —— 这一步不能省，而且必须在游戏类被加载之前完成。

            // 少了它会出现一个非常误导的现象：Fabric 的日志明明写着 "Loading 151 mods"，

            // 因为 Mixin 的字节码转换器没装上，于是
            //   * 模组自己的 Mixin 不会被应用；

            // 换句话说"模组全部加载失败"其实是"模组全部没启动"。
            initMixin(cl, implClass, impl, launcher);

            Object ep = providerIface.getMethod("getEntrypoint").invoke(provider);
            entrypoint = ep == null ? null : ep.toString();
            bootstrapped = true;
            log.accept("Fabric 运行时已引导：游戏版本 " + gameVersion
                    + "，游戏主类 " + entrypoint
                    + "（配置目录、模组容器、注册表、事件与 Mixin 通道均已就绪）");
            return true;
        } catch (Throwable t) {
            log.accept("Fabric 运行时引导未完成，回退到自研注册流程。原因如下：");
            for (String line : describeFailure(t)) {
                log.accept("   " + line);
            }
            bootstrapped = false;
            entrypoint = null;
            return false;
        }
    }

    /** 装我们的 FabricLauncher（必须先于 load()，否则 Fabric 内部拿不到 launcher）。 */
    private static Object createGameClassLoader(ClassLoader cl, Class<?> mcProviderClass,
                                                Class<?> providerIface, Object provider)
            throws Exception {
        Class<?> loaderClass = Class.forName(
                "net.fabricmc.loader.impl.launch.knot.KnotClassLoader", false, cl);
        Class<?> envTypeClass = Class.forName("net.fabricmc.api.EnvType", false, cl);
        Object envType = envClient(envTypeClass);
        java.lang.reflect.Constructor<?> ctor = loaderClass.getDeclaredConstructor(boolean.class,
                envTypeClass, providerIface);
        ctor.setAccessible(true);

        return ctor.newInstance(Boolean.FALSE, envType, provider);
    }

    private static Object envClient(Class<?> envTypeClass) {
        for (Object o : envTypeClass.getEnumConstants()) {
            if ("CLIENT".equals(String.valueOf(o))) {
                return o;
            }
        }
        return null;
    }

    /** 把启动计划算好的游戏类路径逐条加进平台类加载器。 */
    private static void feedClassPath(Object classLoader, Path classpathFile,
                                      Consumer<String> log) {
        if (classpathFile == null || !java.nio.file.Files.isRegularFile(classpathFile)) {
            log.accept("没有拿到游戏类路径清单，平台类加载器将只能看到 JVM 类路径");
            return;
        }
        int added = 0;
        try {
            Method addUrl = classLoader.getClass().getMethod("addUrlFwd", java.net.URL.class);
            addUrl.setAccessible(true);
            for (String line : java.nio.file.Files.readAllLines(classpathFile,
                    java.nio.charset.StandardCharsets.UTF_8)) {
                String p = line.trim();
                if (p.isEmpty()) {
                    continue;
                }
                java.nio.file.Path path = java.nio.file.Path.of(p);
                if (!java.nio.file.Files.exists(path)) {
                    continue;
                }
                addUrl.invoke(classLoader, path.toUri().toURL());
                added++;
            }
        } catch (Throwable t) {
            log.accept("向平台类加载器添加类路径时出错：" + t);
        }
        log.accept("已向平台类加载器加入 " + added + " 个类路径条目");
    }

    /** 装我们的 FabricLauncher（必须先于 load()，否则 Fabric 内部拿不到 launcher）。 */
    private static void initMixin(ClassLoader cl, Class<?> implClass, Object impl, Object launcher)
            throws Exception {
        Class<?> mixinBootstrap = Class.forName(
                "net.fabricmc.loader.impl.launch.FabricMixinBootstrap", false, cl);
        Class<?> envTypeClass = Class.forName("net.fabricmc.api.EnvType", false, cl);
        Object envType = null;
        for (Object o : envTypeClass.getEnumConstants()) {
            if ("CLIENT".equals(String.valueOf(o))) {
                envType = o;
                break;
            }
        }
        // 签名是 init(EnvType, FabricLoaderImpl)
        mixinBootstrap.getMethod("init", envTypeClass, implClass).invoke(null, envType, impl);
    }

    /** 装我们的 FabricLauncher（必须先于 load()，否则 Fabric 内部拿不到 launcher）。 */
    private static Object installLauncher(ClassLoader cl, Class<?> launcherIface, Path gameDir,
                                          String gameVersion, boolean client,
                                          String[] gameArguments, Path gameClasspathFile,
                                          Object gameClassLoader, Consumer<String> log)
            throws Exception {
        Class<?> base = Class.forName("net.fabricmc.loader.impl.launch.FabricLauncherBase", false, cl);
        Object existing = base.getMethod("getLauncher").invoke(null);
        if (existing != null) {

            return existing;
        }
        Object launcher = Proxy.newProxyInstance(cl, new Class<?>[] {launcherIface},
                new LauncherHandler(gameDir, gameVersion, client, gameArguments, cl,
                        gameClasspathFile, gameClassLoader, log));
        base.getMethod("setLauncher", launcherIface).invoke(null, launcher);
        launcherInstalled = true;
        applyProperties(base);
        return launcher;
    }

    /** 设置 Fabric 读取的属性：决定它是否走"开发环境"分支、去哪找游戏 jar。 */
    private static void applyProperties(Class<?> base) {
        try {
            Object props = base.getMethod("getProperties").invoke(null);
            if (props == null) {
                // FabricLauncherBase 的属性表默认是 null，需要主动建一个。

                // 我们取代了 Knot，就得自己补上 —— 否则后续 Mixin 引导会直接 NPE

                Method setter = base.getDeclaredMethod("setProperties", java.util.Map.class);
                setter.setAccessible(true);
                java.util.Map<String, Object> fresh = new java.util.LinkedHashMap<>();
                fresh.put("fabric.development", Boolean.FALSE);
                setter.invoke(null, fresh);
                props = base.getMethod("getProperties").invoke(null);
            }
            if (props instanceof java.util.Map<?, ?> raw) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> map = (java.util.Map<String, Object>) raw;
                map.putIfAbsent("fabric.development", Boolean.FALSE);
            }
        } catch (Throwable ignored) {

        }
    }

    /**
     * 把失败原因说清楚。
     *
     * <p>Fabric 的 FormattedException 把"哪些模组冲突"放在 getDetails() 里，
     * 顶层 getMessage() 只有一句"模组不兼容"—— 只打顶层信息等于什么都没说。
     */
    private static List<String> describeFailure(Throwable t) {
        List<String> out = new ArrayList<>();
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth++ < 6) {
            out.add("· " + cur.getClass().getSimpleName()
                    + (cur.getMessage() == null ? "" : "：" + cur.getMessage()));
            try {
                Method details = cur.getClass().getMethod("getDetails");
                Object d = details.invoke(cur);
                if (d instanceof Iterable<?> it) {
                    for (Object line : it) {
                        if (out.size() > 40) {
                            out.add("   …（其余省略）");
                            return out;
                        }
                        out.add("   " + line);
                    }
                } else if (d != null) {
                    out.add("   " + d);
                }
            } catch (Throwable ignored) {

            }
            if (cur.getCause() == cur) {
                break;
            }
            cur = cur.getCause();
        }
        return out;
    }

    /** LauncherHandler：把 Fabric 需要的运行环境事实如实告知。 */
    private static final class LauncherHandler implements InvocationHandler {
        private final Path gameDir;
        private final String gameVersion;
        private final boolean client;
        private final String[] gameArguments;
        private final ClassLoader cl;
        private final Path gameClasspathFile;
        private final Object gameClassLoader;
        private final Consumer<String> log;
        private Object envType;
        private Object mappingConfiguration;
        private List<Path> classPath;
        private boolean warnedClassPathAdd;

        LauncherHandler(Path gameDir, String gameVersion, boolean client, String[] gameArguments,
                        ClassLoader cl, Path gameClasspathFile, Object gameClassLoader,
                        Consumer<String> log) {
            this.gameDir = gameDir;
            this.gameVersion = gameVersion;
            this.client = client;
            this.gameArguments = gameArguments;
            this.cl = cl;
            this.gameClasspathFile = gameClasspathFile;
            this.gameClassLoader = gameClassLoader;
            this.log = log;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "isDevelopment":
                    return Boolean.FALSE;
                case "getEnvironmentType":
                    return envType();
                case "getEntrypoint":
                    return "net.minecraft.client.main.Main";
                case "getTargetClassLoader":
                case "getClassLoader":
                    // 必须是平台的类加载器：游戏与模组都由它加载，变换才会生效。
                    return gameClassLoader;
                case "getClassPath":
                    return classPath();
                case "isClassLoaded":
                    return findLoaded((String) args[0]);
                case "getResourceAsStream":
                    return cl.getResourceAsStream((String) args[0]);
                case "getClassByteArray": {
                    String name = ((String) args[0]).replace('.', '/') + ".class";
                    try (InputStream s = cl.getResourceAsStream(name)) {
                        return s == null ? null : s.readAllBytes();
                    }
                }
                case "loadIntoTarget":
                    return Class.forName((String) args[0], false, cl);
                case "getManifest": {
                    Path p = (Path) args[0];
                    try (com.fusionml.util.FastZip zip = com.fusionml.util.FastZip.open(p)) {
                        com.fusionml.util.FastZip.Entry e = zip.entry("META-INF/MANIFEST.MF");
                        return e == null ? null
                                : new java.util.jar.Manifest(new java.io.ByteArrayInputStream(
                                        zip.read(e)));
                    } catch (Exception ex) {
                        return null;
                    }
                }
                case "addToClassPath":
                    // Fabric 在发现模组时会要求把模组包加进类路径，这里如实转发给平台的类加载器。
                    // 之前这里是空实现，导致 Fabric 以为类路径里没有模组 —— 这会直接让模组"看不见"。
                    try {
                        Method addUrl = gameClassLoader.getClass()
                                .getMethod("addUrlFwd", java.net.URL.class);
                        addUrl.setAccessible(true);
                        addUrl.invoke(gameClassLoader, ((Path) args[0]).toUri().toURL());
                    } catch (Throwable t) {
                        if (!warnedClassPathAdd) {
                            warnedClassPathAdd = true;
                            log.accept("追加类路径失败：" + args[0] + " —— " + t);
                        }
                    }
                    return null;
                case "setClassLoader":
                    return null;
                case "setAllowedPrefixes":
                    // 转发给平台类加载器：这条信息决定哪些包由它自己加载（从而经过变换）。
                    forwardToDelegate("setAllowedPrefixes", args);
                    return null;
                case "setValidParentClassPath":
                    // 关键转发：这条信息告诉平台类加载器"哪些类路径条目应当委派给父加载器"。
                    // 不转发的话，库（log4j 等）会被系统类加载器与平台类加载器各加载一份，
                    // 表现为它们自己类之间的 ClassCastException —— 一种极难凭现象定位的错误。
                    forwardToDelegate("setValidParentClassPath", args);
                    return null;
                case "getMappingConfiguration":
                    // 26.3 起游戏本身不再混淆，不存在"开发名 → 运行时名"的映射，
                    // 但 Fabric 仍会查询命名空间，所以必须给一个真实实例而不是 null。
                    return mappingConfiguration();
                case "getGameDir":
                    return gameDir;
                case "getRawGameVersion":
                    return gameVersion;
                case "toString":
                    return "FusionLauncher";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return defaultValue(method.getReturnType());
            }
        }

        private Object envType() {
            if (envType == null) {
                try {
                    Class<?> c = Class.forName("net.fabricmc.api.EnvType", false, cl);
                    for (Object o : c.getEnumConstants()) {
                        if (String.valueOf(o).equals(client ? "CLIENT" : "SERVER")) {
                            envType = o;
                            break;
                        }
                    }
                } catch (Throwable ignored) {
                    // 拿不到就返回 null，Fabric 会在有明确错误时告诉我们
                }
            }
            return envType;
        }

        private Object mappingConfiguration() {
            if (mappingConfiguration == null) {
                try {
                    Class<?> c = Class.forName("net.fabricmc.loader.impl.launch.MappingConfiguration",
                            false, cl);
                    mappingConfiguration = c.getDeclaredConstructor().newInstance();
                } catch (Throwable ignored) {

                }
            }
            return mappingConfiguration;
        }

        private List<Path> classPath() {
            if (classPath == null) {
                List<Path> out = new ArrayList<>();
                // 必须返回**完整**类路径（加载器 + 依赖库 + 游戏 + 模组）。
                // Fabric 用这份列表来判断"哪些条目可以向游戏暴露"：平台类加载器只肯把
                // 游戏与模组交给自己定义，其余（依赖库）交给父加载器。
                // 之前我只返回"游戏 + 模组"，结果平台类加载器认为库从未被暴露，
                // 游戏解析参数时直接 ClassNotFoundException: joptsimple.ValueConverter

                String cp = System.getProperty("java.class.path", "");
                for (String part : cp.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                    if (!part.isBlank()) {
                        out.add(Path.of(part));
                    }
                }
                classPath = out;
            }
            return classPath;
        }

        private boolean findLoaded(String name) {
            try {
                Method m = ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
                m.setAccessible(true);
                return m.invoke(cl, name) != null;
            } catch (Throwable t) {
                return false;
            }
        }

        /**
         * 把调用转发给平台类加载器的内部委托对象。
         *
         * <p>KnotClassLoader 是包私有类，它把真正的逻辑放在包私有的 KnotClassDelegate 里。
         * 这两条设置（setAllowedPrefixes / setValidParentClassPath）直接决定
         * "哪些类由平台类加载器自己定义（会经过变换）、哪些交给父加载器"，
         * 丢掉它们就会出现同一个库被加载两份而互相 ClassCastException。
         */
        private void forwardToDelegate(String methodName, Object[] args) {
            try {
                Method getDelegate = gameClassLoader.getClass().getDeclaredMethod("getDelegate");
                getDelegate.setAccessible(true);
                Object delegate = getDelegate.invoke(gameClassLoader);
                if (delegate == null) {
                    return;
                }
                for (Method m : delegate.getClass().getMethods()) {
                    if (m.getName().equals(methodName)
                            && m.getParameterCount() == args.length
                            && m.getParameterTypes()[0].isInstance(args[0])) {
                        m.setAccessible(true);
                        m.invoke(delegate, args);
                        return;
                    }
                }
            } catch (Throwable t) {
                if (!warnedClassPathAdd) {
                    warnedClassPathAdd = true;
                    log.accept("转发 " + methodName + " 到平台类加载器失败：" + t);
                }
            }
        }
    }

    /** 代理未覆盖到的方法返回合理默认值（基本类型不能返回 null，否则抛 NPE）。 */
    static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == void.class) {
            return null;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return 0;
    }
}
