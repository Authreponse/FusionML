package com.fusionml.launch;

import com.fusionml.mc.McDownloader;
import com.fusionml.mc.McInstance;
import com.fusionml.mc.McVersion;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 启动计划：把一份版本定义变成一条可以直接交给操作系统的命令行。
 *
 * <p>这一步是"接入 Minecraft"最实在的部分，因为它要同时处理好几件容易出错的事：
 * <ul>
 *   <li><b>Java 版本</b>：26.3 要 Java 25，选错直接启动失败，所以由 JavaFinder 先算好；</li>
 *   <li><b>原生库</b>：LWJGL 的 dll/so 必须先解压并挂到 java.library.path；</li>
 *   <li><b>类路径</b>：加载器 → 客户端 → 依赖库 → 模组，顺序决定了谁先被找到；</li>
 *   <li><b>参数占位符</b>：${auth_player_name} 这类必须替换，否则会出现
 *       "能启动但进不去"这种最难查的症状。</li>
 * </ul>
 *
 * <p>接管方式与 Fabric 同思路、实现完全自研：把版本定义里的 mainClass 换成我们的
 * FusionClientMain，由它先完成模组加载与注入，再把控制权交回真正的游戏主类。
 */
public final class LaunchPlan {

    /** 启动选项。 */
    public static final class Options {
        public String versionId;
        /** 加载器 jar（同时也是 javaagent 包）。 */
        public Path loaderJar;
        public List<Path> modJars = new ArrayList<>();
        public String username = "Player";
        public String uuid = "00000000000000000000000000000000";
        public String accessToken = "0";
        public String userType = "legacy";
        public String clientId = "fusionml";
        public int maxMemoryMb = 4096;
        public int minMemoryMb;
        /** 只加载到"模组载入 + 注入完成"就结束，用于验证链路（不弹游戏窗口）。 */
        public boolean bootOnly;
        /** 不加载任何模组（排查"是不是模组导致"时很有用）。 */
        public boolean skipMods;
        /** 客户端 / 服务端。服务端不注册 HUD，也不需要渲染相关的依赖。 */
        public boolean client = true;
        public Path javaExe;
        public List<String> extraJvmArgs = new ArrayList<>();
        /** 缺库时是否自动下载。 */
        public boolean downloadMissingLibraries = true;
    }

    /** 一条具体的启动命令。 */
    public record Plan(Path javaExe, List<String> command, Path nativesDir, Path gameDir,
                       String versionId, int javaMajor, Path clientJar, String realMainClass,
                       String classpath, List<Path> libraries, List<Path> modJars) {
        public String describe() {
            StringBuilder sb = new StringBuilder(512);
            sb.append("Java        : ").append(javaMajor).append("  (").append(javaExe).append(')')
                    .append(System.lineSeparator());
            sb.append("版本        : ").append(versionId).append(System.lineSeparator());
            sb.append("游戏目录    : ").append(gameDir).append(System.lineSeparator());
            sb.append("客户端 jar  : ").append(clientJar).append(System.lineSeparator());
            sb.append("原生库目录  : ").append(nativesDir).append(System.lineSeparator());
            sb.append("依赖库      : ").append(libraries.size()).append(" 个")
                    .append(System.lineSeparator());
            sb.append("模组        : ").append(modJars.size()).append(" 个")
                    .append(System.lineSeparator());
            sb.append("真实主类    : ").append(realMainClass).append(System.lineSeparator());
            // 把**实际会用到的**主类也写出来。
            //
            // 上面那个"真实主类"是版本定义里写的（客户端启动器），服务端模式下我们会把它
            // 换成同平台的服务端启动器 —— 两者不同时，只有这里能看出来。
            // 踩过一次：加了服务端主类切换后，只看"真实主类"那行，误以为没生效；
            // 第一次修这个诊断时又取错了位置（命令行最后一项是版本号，不是主类），
            // 所以这里**按内容找**：命令行里那个"看起来像类名"的项。
            String effectiveMain = null;
            for (String a : command) {
                if (a.endsWith("KnotClient") || a.endsWith("KnotServer")
                        || a.endsWith(".fml.startup.Client") || a.endsWith(".fml.startup.Server")) {
                    effectiveMain = a;
                }
            }
            if (effectiveMain != null && !effectiveMain.equals(realMainClass)) {
                sb.append("实际主类    : ").append(effectiveMain)
                        .append("（已按侧别换成平台自己的另一个启动器）")
                        .append(System.lineSeparator());
            }
            return sb.toString();
        }
    }

    private LaunchPlan() {
    }

    public static Plan build(McInstance instance, Options o, Consumer<String> log) throws IOException {
        McInstance.Effective version = instance.resolve(o.versionId);
        Path gameDir = instance.gameDir();

        // 1) Java
        Path javaExe = o.javaExe;
        int javaMajor = version.javaMajor;
        if (javaExe == null || !Files.isRegularFile(javaExe)) {
            JavaFinder.Found found = JavaFinder.find(Math.max(javaMajor, JavaFinder.currentMajor()));
            if (found == null) {
                throw new IOException("需要 Java " + javaMajor + " 才能启动 " + o.versionId
                        + "，但本机没有找到。请安装对应的 JDK/JRE 后重试。");
            }
            javaExe = found.javaExe();
            javaMajor = found.major();
            if (version.javaMajor > 0 && found.major() < version.javaMajor) {
                throw new IOException("版本 " + o.versionId + " 要求 Java " + version.javaMajor
                        + "，本机最高只有 Java " + found.major() + "。");
            }
        }

        // 2) 依赖库：本地有就用本地；缺了且给了下载地址就补下。
        List<McVersion.Library> applicable = version.applicableLibraries();
        List<Path> libraries = new ArrayList<>(applicable.size());
        List<String> missing = new ArrayList<>();
        McDownloader downloader = new McDownloader();
        for (McVersion.Library lib : applicable) {
            Path local = instance.localLibraryPath(lib);
            if (!Files.isRegularFile(local)) {
                if (o.downloadMissingLibraries && lib.url() != null && !lib.url().isBlank()) {
                    try {
                        downloader.ensure(local, lib.url(), lib.sha1(), McDownloader.Progress.SILENT);
                    } catch (IOException e) {
                        missing.add(lib.name() + "（下载失败：" + e.getMessage() + "）");
                        continue;
                    }
                } else {
                    missing.add(lib.name());
                    continue;
                }
            }
            libraries.add(local);
        }
        if (!missing.isEmpty()) {
            // 只列前几条并说明总数：整合包缺库的列表可能很长，全打出来反而看不清重点。
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(6, missing.size()); i++) {
                sb.append(System.lineSeparator()).append("    · ").append(missing.get(i));
            }
            if (missing.size() > 6) {
                sb.append(System.lineSeparator()).append("    ...另有 ")
                        .append(missing.size() - 6).append(" 个");
            }
            throw new IOException("缺少 " + missing.size() + " 个必需依赖库：" + sb);
        }

        // 3) 原生库
        Path nativesDir = gameDir.resolve(".fusionml").resolve("natives").resolve(o.versionId);
        final List<McVersion.Library> applicableFinal = applicable;
        int extracted = NativeExtractor.extract(new NativeExtractor.McInstanceView() {
            @Override
            public List<McVersion.Library> libraries() {
                return applicableFinal;
            }

            @Override
            public Path localPath(McVersion.Library lib) {
                return instance.localLibraryPath(lib);
            }
        }, nativesDir);
        if (extracted > 0) {
            log.accept("已解压 " + extracted + " 个原生库到 " + nativesDir);
        }

        Map<String, String> params = placeholders(instance, version, gameDir, nativesDir, o);
        // 类路径要分两份，这是能不能跑通的关键：
        //   * JVM 类路径（系统类加载器）：全部条目。
        //     官方启动器给 Fabric 版本的参数就是这样，平台的引导代码要用到的库很多
        //     （fabric-loader、mixin、asm、log4j、gson…），逐个甄别既容易漏又要随版本维护。
        //   * 平台类加载器：**只拿游戏本体 + 模组 + 嵌套包**，依赖库一律留给父加载器。
        //     两个原因：
        //       1) 游戏与模组必须由平台类加载器 defineClass，变换器（Mixin、入口点改写）
        //          才会作用到它们；交给系统类加载器就等于把整套变换机制绕过去。
        //       2) 库如果两边都放，会出现同一个类被加载两份，
        //          表现为这些库自己类之间的 ClassCastException（极难凭现象定位）。
        List<Path> classpathEntries = new ArrayList<>();
        if (o.loaderJar != null) {
            classpathEntries.add(o.loaderJar);
        }
        // 模组包不进 JVM 类路径：由平台运行时自己从 mods 目录发现并按它的规则加入类加载器。
        // 这是官方启动器给 Fabric/NeoForge 版本的标准配置，也是唯一被验证过的配置 ——
        // 我们硬把模组塞进 JVM 类路径时，会遇到"库被两份加载 / 类路径分组错乱"这类问题。
        // 我们自己的前置分析直接读 mods 目录，不依赖类路径。
        if (!o.skipMods) {
            // 嵌套 jar 也必须进类路径：它们里面就是真正的模组代码与资源。
            // 父进程先把它们解出来放缓存，子进程加载模组时会直接命中同一份缓存。
            try {
                Path cacheDir = com.fusionml.loader.NestedJars.defaultCacheDir(
                        instance.gameDir().resolve("mods"));
                List<String> notes = new java.util.ArrayList<>();
                com.fusionml.loader.NestedJars.Result nested =
                        com.fusionml.loader.NestedJars.extract(o.modJars, cacheDir, notes);
                if (!nested.jars().isEmpty()) {
                    log.accept("嵌套模组包：" + nested.summary());
                }
            } catch (Throwable t) {
                log.accept("嵌套包解析未完成（不影响启动）：" + t);
            }
        }
        // 跨端的"补丁原版基座"：它是一份**合并出来的游戏包**（原版 + 对方平台的补丁覆盖），
        // 用<b>替代</b>原版 jar 的位置交给宿主平台。
        //
        // 为什么必须替代而不是旁挂：宿主平台 0.19 起给类路径分了组，只有"游戏包"那一组里的
        // jar 才允许提供 net.minecraft.* —— 旁挂的包会被明确拒绝
        // （报 "hasn't been exposed to the game"），补丁等于没生效，而现象离原因很远。
        // 替代之后，补丁就长在游戏包里，宿主平台的 Mixin 依旧叠在它之上：
        // 对方平台的补丁在下、宿主平台的注入在上，两层不互斥。
        Path base = null;
        try {
            base = com.fusionml.crossload.PatchedBase.forLaunch(instance, o.modJars,
                    o.versionId, log);
        } catch (Throwable t) {
            log.accept("跨端补丁基座未启用（沿用原版字节）：" + t);
        }
        if (base != null) {
            classpathEntries.add(base);
        } else if (version.clientJar != null) {
            classpathEntries.add(version.clientJar);
        }
        classpathEntries.addAll(libraries);
        String classpath = join(classpathEntries);
        params.put("classpath", classpath);

        List<String> jvmArgs = jvmArguments(instance, version, nativesDir, o, params);
        List<String> gameArgs = com.fusionml.launch.GameArguments.resolve(
                version.gameArgs.isEmpty() ? null : version.gameArgs.toArray(new String[0]),
                params, version.features);
        if (gameArgs.isEmpty()) {
            gameArgs = defaultGameArgs(params);
        }

        List<String> command = new ArrayList<>();
        command.add(javaExe.toAbsolutePath().toString());
        command.addAll(jvmArgs);
        if (!containsClasspathFlag(jvmArgs)) {
            command.add("-cp");
            command.add(classpath);
        }
        // 主类保持版本定义里原本的（Fabric 是 KnotClient，NeoForge 是它的 bootstrap）。
        // 我们通过 javaagent 的 premain 前置介入，而不是顶替它 ——
        // 平台的类加载语义（Mixin、入口点注入、类路径分组）与它的启动器不可分割，
        // 顶替它就必须复刻整套语义，代价与风险都远高于收益。
        //
        // 例外：**服务端**。版本定义里写的永远是客户端的启动器
        // （实测 `--server` 时仍打印 `真实主类: net.fabricmc.loader.impl.launch.knot.KnotClient`），
        // 而服务端要用同一个平台的服务端启动器。这不是"顶替平台"，
        // 只是把平台自己的两个入口选对：两边都是平台官方的启动类。
        command.add(serverMainClass(version.mainClass, o.client));
        command.addAll(gameArgs);

        return new Plan(javaExe, command, nativesDir, gameDir, o.versionId, javaMajor,
                version.clientJar, version.mainClass, classpath, libraries, o.modJars);
    }

    /**
     * 选对平台自己的启动器：客户端用版本定义里的，服务端换成同平台的 Server 版。
     *
     * <p>映射规律是按平台的类命名来的（Fabric：`KnotClient` → `KnotServer`），
     * 认不出来时**原样返回**并留给日志说明 —— 不猜一个不存在的类名，
     * 因为那会让启动失败在"找不到主类"，症状离原因很远。
     */
    private static String serverMainClass(String clientMain, boolean client) {
        if (client || clientMain == null) {
            return clientMain;
        }
        if (clientMain.endsWith("KnotClient")) {
            return clientMain.substring(0, clientMain.length() - "KnotClient".length())
                    + "KnotServer";
        }
        if (clientMain.endsWith(".Client")) {
            // NeoForge 的形如 net.neoforged.fml.startup.Client
            return clientMain.substring(0, clientMain.length() - ".Client".length()) + ".Server";
        }
        return clientMain;
    }

    /** 组装所有参数占位符的取值。 */
    private static Map<String, String> placeholders(McInstance instance, McInstance.Effective version,
                                                    Path gameDir, Path nativesDir,
                                                    Options o) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("natives_directory", nativesDir.toAbsolutePath().toString());
        p.put("launcher_name", "FusionML");
        p.put("launcher_version", "1.0.0");
        p.put("classpath_separator", File.pathSeparator);
        p.put("library_directory", instance.librariesDir().toAbsolutePath().toString());
        p.put("version_name", o.versionId);
        p.put("version_type", o.versionId);
        p.put("game_directory", gameDir.toAbsolutePath().toString());
        p.put("assets_root", instance.assetsDir().toAbsolutePath().toString());
        p.put("assets_index_name", version.assetIndexId);
        p.put("auth_player_name", o.username);
        p.put("auth_uuid", o.uuid);
        p.put("auth_access_token", o.accessToken);
        p.put("auth_session", o.accessToken);
        p.put("auth_xuid", "0");
        p.put("clientid", o.clientId);
        p.put("user_type", o.userType);
        p.put("user_properties", "{}");
        p.put("resolution_width", "1280");
        p.put("resolution_height", "720");
        return p;
    }

    /** 组装 JVM 参数：我们的（内存、原生库、接管标志）＋ 版本定义里要求的。 */
    private static List<String> jvmArguments(McInstance instance, McInstance.Effective version,
                                             Path nativesDir, Options o, Map<String, String> params) {
        List<String> jvm = new ArrayList<>();
        jvm.add("-Xmx" + o.maxMemoryMb + "m");
        if (o.minMemoryMb > 0) {
            jvm.add("-Xms" + o.minMemoryMb + "m");
        }
        jvm.add("-Djava.library.path=" + nativesDir.toAbsolutePath());
        // 刻意不设 -Dfile.encoding：Java 18+ 默认已经是 UTF-8，而控制台输出流
        // （stdout.encoding）默认跟随当前代码页。一旦强行指定 UTF-8，在中文 GBK 控制台上
        // 中文日志会整片变乱码 —— 这个坑比它想解决的问题更常见。
        // 但要把父进程的输出编码透传给子进程：否则父子两段日志会是两种编码，
        // 无论是肉眼看终端还是把日志重定向进文件，都会有一半是乱码。
        String outEncoding = System.getProperty("stdout.encoding");
        if (outEncoding == null || outEncoding.isBlank()) {
            outEncoding = System.getProperty("native.encoding");
        }
        if (outEncoding != null && !outEncoding.isBlank()) {
            jvm.add("-Dstdout.encoding=" + outEncoding);
            jvm.add("-Dstderr.encoding=" + outEncoding);
        }
        jvm.add("-Dfusion.realMainClass=" + version.mainClass);
        jvm.add("-Dfusion.gameDir=" + instance.gameDir().toAbsolutePath());
        jvm.add("-Dfusion.version=" + o.versionId);
        jvm.add("-Dfusion.bootOnly=" + o.bootOnly);
        jvm.add("-Dfusion.skipMods=" + o.skipMods);
        // 前置分析（javaagent 的 premain 阶段）需要的上下文。
        jvm.add("-Dfusion.preflight=" + !o.skipMods);
        jvm.add("-Dfusion.modsDir=" + instance.gameDir().toAbsolutePath().resolve("mods"));
        // 运行侧：服务端不注册 HUD，也不做任何渲染相关的事。
        jvm.add("-Dfusion.client=" + o.client);
        // "游戏包"是谁：启用了跨端补丁基座时，就是那份合并出来的包（原版 + 对方平台补丁），
        // 它已经在类路径上替代了原版 jar。游戏进程里的自检、版本判定、密封修补扫描
        // 都必须看同一个包，否则两边说的不是同一份文件。
        String gameJar = System.getProperty(com.fusionml.crossload.PatchedBase.PROP_RESOLVED, "");
        if (gameJar.isBlank() && version.clientJar != null) {
            gameJar = version.clientJar.toAbsolutePath().toString();
        }
        if (!gameJar.isBlank()) {
            // 交给接管主类，用来对真实游戏类做注入自检（它比在类路径里翻找可靠得多）。
            jvm.add("-Dfusion.clientJar=" + gameJar);
        }
        if (!System.getProperty(com.fusionml.crossload.PatchedBase.PROP_RESOLVED, "").isBlank()) {
            // 宿主平台的"类路径隔离"只允许它认定的那份游戏包提供 net.minecraft.*。
            // 我们交给它的是合并出来的游戏包（原版 + 对方平台补丁），但它的分组判定不认识
            // 这个新文件名，于是会拒绝加载并报 "hasn't been exposed to the game" ——
            // 补丁等于没生效，而现象离原因很远（实测踩过）。
            // 这个开关是宿主平台自己提供的：打开后 isValidParentUrl 直接放行，
            // 类路径上的游戏类照常可加载。我们并没有引入重复的游戏类
            // （原版包已经不在类路径上了），所以放行是安全的。
            jvm.add("-Dfabric.debug.disableClassPathIsolation");
        }
        // 这次类路径里到底有没有、是哪一份"补丁原版基座"：游戏进程里的报告要如实写出来，
        // 免得事后只能靠"当时是哪个整合包"去猜。
        String base = System.getProperty(com.fusionml.crossload.PatchedBase.PROP_RESOLVED, "");
        if (!base.isBlank()) {
            jvm.add("-D" + com.fusionml.crossload.PatchedBase.PROP_RESOLVED + "=" + base);
        }
        // 模组 Mixin 的目标清单也要**显式交给游戏进程**。
        //
        // 这里踩过一次，代价是一整局启动崩掉，所以写下来：
        // 启动器进程里 System.setProperty 设的属性**不会自动过继给游戏进程** ——
        // 游戏进程是一个全新的 JVM，只有命令行上 -D 传过去的才存在。
        // 漏传的后果不是"少一条日志"，而是：游戏进程里的诊断/预热代码读不到这份黑名单，
        // 于是照旧提前加载了模组的 Mixin 目标，报
        // "MixinTargetAlreadyLoadedException ... DeferredRegister was loaded too early"，
        // 连带炸掉 axiom 的 preLaunch 入口点。而那行报错离真正的原因（少传一个属性）极远。
        String mixinTargets = System.getProperty(
                com.fusionml.crossload.PatchedBase.PROP_MIXIN_TARGETS, "");
        if (!mixinTargets.isBlank()) {
            jvm.add("-D" + com.fusionml.crossload.PatchedBase.PROP_MIXIN_TARGETS + "=" + mixinTargets);
        }
        // 黑名单的文件通道。**这里只传路径，不传名单本身** —— 实测踩过一次：
        // 本整合包扫出 846 个 Mixin 目标，拼成 -D 参数约 34KB，超过 Windows 命令行上限，
        // 进程以 "CreateProcess error=206, 文件名或扩展名太长" 起不来，
        // 而日志里没有崩溃也没有报错，看起来像"问题自己好了"，极具迷惑性。
        String targetsFile = System.getProperty(
                com.fusionml.crossload.PatchedBase.PROP_TARGETS_FILE, "");
        if (!targetsFile.isBlank()) {
            jvm.add("-D" + com.fusionml.crossload.PatchedBase.PROP_TARGETS_FILE + "=" + targetsFile);
        }
        if (o.loaderJar != null) {
            // 以 Java 代理方式在"类被定义之前"拿到钩子：这样即使目标类由游戏的类加载器加载，
            // 我们也能在它成型前完成注入，而不是等它已经定义完再去想办法。
            jvm.add("-javaagent:" + o.loaderJar.toAbsolutePath());
        }
        jvm.addAll(o.extraJvmArgs);
        jvm.addAll(GameArguments.resolve(
                version.jvmArgs.isEmpty() ? null : version.jvmArgs.toArray(new String[0]),
                params, version.features));
        return jvm;
    }

    private static boolean containsClasspathFlag(List<String> args) {
        for (String a : args) {
            if ("-cp".equals(a) || "-classpath".equals(a)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断一个类路径条目是不是"平台启动器侧"的库。
     *
     * <p>这些库必须在 JVM 类路径上（系统类加载器可见），因为平台的引导代码在进入我们之前
     * 就要用到它们；而游戏本体与其余依赖库应该由平台类加载器加载，才能经过字节码变换。
     * 判据用 Maven 路径特征而不是硬编码文件列表：这样换 Fabric/NeoForge 版本也不用改代码。
     */
    static boolean isLoaderSideLibrary(Path path) {
        String s = path.toString().replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        return s.contains("/net/fabricmc/fabric-loader/")
                || s.contains("/net/fabricmc/sponge-mixin/")
                || s.contains("/net/fabricmc/access-widener/")
                || s.contains("/net/fabricmc/mapping-io/")
                || s.contains("/net/fabricmc/tiny-mappings-parser/")
                || s.contains("/org/ow2/asm/")
                || s.contains("/net/fabricmc/mixinextras/")
                || s.contains("/io/github/llamalad7/mixinextras/")
                || s.contains("/org/spongepowered/mixin/")
                || s.contains("/net/neoforged/fml/")
                || s.contains("/cpw/mods/modlauncher/")
                || s.contains("/cpw/mods/securejarhandler/")
                || s.contains("/net/minecraftforge/forgespi/")
                || s.contains("/cpw/mods/bootstraplauncher/");
    }

    /** 极老的版本可能完全没有参数定义，给一组能跑起来的最小参数。 */
    private static List<String> defaultGameArgs(Map<String, String> p) {
        List<String> out = new ArrayList<>();
        out.add("--username");
        out.add(p.get("auth_player_name"));
        out.add("--version");
        out.add(p.get("version_name"));
        out.add("--gameDir");
        out.add(p.get("game_directory"));
        out.add("--assetsDir");
        out.add(p.get("assets_root"));
        out.add("--assetIndex");
        out.add(p.get("assets_index_name"));
        out.add("--uuid");
        out.add(p.get("auth_uuid"));
        out.add("--accessToken");
        out.add(p.get("auth_access_token"));
        out.add("--userType");
        out.add(p.get("user_type"));
        out.add("--versionType");
        out.add(p.get("version_type"));
        return out;
    }

    public static String join(List<Path> entries) {
        StringBuilder sb = new StringBuilder(entries.size() * 64);
        for (Path p : entries) {
            if (sb.length() > 0) {
                sb.append(File.pathSeparator);
            }
            sb.append(p.toAbsolutePath());
        }
        return sb.toString();
    }

    /** 启动进程（继承当前终端输入输出，方便直接看到游戏日志）。 */
    public static Process launch(Plan plan, boolean inheritIO) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(plan.command());
        pb.directory(plan.gameDir().toFile());
        if (inheritIO) {
            pb.inheritIO();
        }
        return pb.start();
    }

    /** 便于日志展示：把命令行拼成一行可读文本（需要时加引号）。 */
    public static String prettyCommand(List<String> command) {
        StringBuilder sb = new StringBuilder(command.size() * 48);
        for (String c : command) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            if (c.indexOf(' ') >= 0) {
                sb.append('"').append(c).append('"');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
