package com.fusionml.crossload;

import com.fusionml.mc.McInstance;
import com.fusionml.bytecode.ClassFile;
import com.fusionml.bytecode.SuperclassBridge;
import com.fusionml.util.FastZip;
import com.fusionml.util.FastZipWriter;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 跨端的「补丁原版基座」：把对方平台<b>官方那份打过补丁的原版客户端 jar</b> 拿进来，
 * 并且排在原版 jar <b>之前</b>。
 *
 * <h2>为什么非做不可</h2>
 *
 * <p>对方平台（NeoForge / Forge）提供 API 的方式不是"加载时改写"，而是
 * <b>静态给原版类打补丁</b>。举两个直接踩到的例子：
 *
 * <ul>
 *   <li>补丁后 {@code net.minecraft.core.Registry} 会多实现
 *       {@code net.neoforged.neoforge.registries.IRegistryExtension}；</li>
 *   <li>补丁后 {@code net.minecraft.core.MappedRegistry} 的超类会被换成
 *       {@code net.neoforged.neoforge.registries.BaseMappedRegistry}。</li>
 * </ul>
 *
 * <p>宿主平台（Fabric）的原版 jar 上没有这些补丁，于是对方模组一走到注册表就撞：
 *
 * <pre>
 * LambdaConversionException: Invalid receiver type interface net.minecraft.core.Registry;
 *   not a subtype of implementation type interface net.neoforged.neoforge.registries.IRegistryExtension
 * </pre>
 *
 * <p>这条报错看起来像"模组写错了"，其实是"原版字节不是对方平台认可的那一份"。
 * 它也正是<b>兼容层与原生接入的分界</b>：兼容层会去模仿对方的 API 形状，
 * 而原生接入是直接用它官方产出的这份补丁字节。
 *
 * <h2>放在类路径的哪个位置</h2>
 *
 * <p>必须排在原版 jar 之前 —— 这个位置就是 ModLauncher 里
 * {@code minecraft-client-patched} 所在的位置：类加载器先从补丁 jar 取字节，
 * <b>Mixin 再在这些字节之上叠加</b>。所以两端是叠加关系，不是二选一：
 * 对方平台的补丁在下，宿主平台的 Mixin 在上。
 *
 * <p>不用"启动后再改写字节"的办法，是因为那样拿到的字节已经在 Mixin 之后，
 * 再换掉就等于把宿主平台模组的 Mixin 结果一起丢掉。
 *
 * <h2>开关</h2>
 *
 * <ul>
 *   <li>{@code -Dfusion.patchedBase=auto|off|<jar 路径>}，默认 {@code auto}：
 *       只在模组目录里真的存在对方平台格式的包时才启用；</li>
 *   <li>{@code -Dfusion.patchedBase.scope=all|<前缀列表>}，默认 {@code all}。
 *       给一个"只替换某些包"的细调旋钮（例如只换 {@code net/minecraft/core/} 与
 *       {@code net/minecraft/resources/}），万一遇到宿主平台某个模组的 Mixin
 *       与对方补丁冲突，可以先用它把范围收窄再定位。</li>
 * </ul>
 */
public final class PatchedBase {

    /**
     * 合并产物的格式版本：改合并规则时把它 +1，旧缓存立即失效。
     *
     * <p>但它只认"数据"，不认"代码" —— 实测踩过：改了拼接的逻辑（指令扫描、
     * 常量池移植），却忘了 +1，于是真机复用了旧产物，白跑一轮还以为是"改了没生效"。
     * 所以 {@link #rulesFingerprint()} 会把**实现代码的指纹**也算进缓存键：
     * 只要 {@code PatchedBase} 或 {@code ConstantPoolTransplant} 的代码变了，缓存自动失效，
     * 不再依赖"记得手动 +1"这件事。
     */
    private static final int CACHE_FORMAT = 22;

    /**
     * 实现代码的指纹：把本类与常量池移植类的**资源字节**哈希一下。
     *
     * <p>这不是加密用途，只求"代码一变、指纹就变"。用资源流读自己的 class 文件，
     * 在 jar 里与在 classes 目录里都能工作。
     */
    private static String rulesFingerprint() {
        StringBuilder sb = new StringBuilder(32);
        for (String res : new String[] {
                "com/fusionml/crossload/PatchedBase.class",
                "com/fusionml/bytecode/ConstantPoolTransplant.class",
                "com/fusionml/bytecode/ClassFile.class"}) {
            try (java.io.InputStream in = PatchedBase.class.getClassLoader()
                    .getResourceAsStream(res)) {
                if (in == null) {
                    sb.append("?");
                    continue;
                }
                long h = 1125899906842597L;
                int b;
                while ((b = in.read()) != -1) {
                    h = 31 * h + b;
                }
                sb.append(Long.toHexString(h)).append('.');
            } catch (Throwable t) {
                sb.append("!");
            }
        }
        return sb.toString();
    }

    public static final String PROP_MODE = "fusion.patchedBase";
    /**
     * 替换范围：{@code all}，或逗号分隔的内部名前缀。
     *
     * <p>默认是 {@code net/minecraft/core/,net/minecraft/resources/} —— 也就是
     * 对方平台 API 形状真正落脚的那一小块（注册表、Holder、资源键）。
     * 为什么不默认整份：实测出来的，整份换会让宿主平台模组的 Mixin 注入点匹配不到目标
     * （对方平台那份是反编译再编译的，指令序列与原版不逐条一致），
     * 报 {@code Critical injection failure ... Scanned 0 target(s)} 之后整局起不来。
     * 范围收在这一小块，是"对方平台的注册表契约要成立"与"宿主平台模组照旧"之间
     * 唯一能同时站住的位置；需要更宽时用这个属性调，日志里会如实写出范围与类数。
     */
    public static final String PROP_SCOPE = "fusion.patchedBase.scope";
    /**
     * 默认范围：对方平台 API 形状所在的那一块。
     *
     * <h2>为什么从"只换两个包"扩到了整个 net/minecraft</h2>
     *
     * <p>最初默认只换 {@code net/minecraft/core/} 与 {@code net/minecraft/resources/}，
     * 理由是"整份换会让宿主模组的 Mixin 匹配不到目标"。但实测发现范围收窄有个硬伤：
     * **对方模组依赖的类不只在 core/resources 里** ——
     * 例如 {@code net.minecraft.commands.synchronization.ArgumentTypeInfos}
     * （NeoForge 给它加了 {@code registerByClass}），收窄时它根本没被处理，
     * 于是对方模组一调就 {@code NoSuchMethodError}。
     *
     * <p>现在敢放开范围，是因为**合并不再是"整类换"**：范围只决定"哪些类进入判据"，
     * 而每个类还要过一遍"只新增成员 → 保留原版/拼接、动过成员 → 整类换"的判定。
     * 宿主模组的 Mixin 目标由 {@code keepVanilla} 单独保护，
     * 所以范围变大不会把注入点一起冲掉 —— 风险由判据承担，不再由范围承担。
     */
    private static final String DEFAULT_SCOPE = "net/minecraft/";

    public static final String PROP_RESOLVED = "fusion.patchedBase.jar";

    public static final String PROP_TYPES = "fusion.patchedBase.types";
    /**
     * 模组 Mixin 的目标类（逗号分隔的内部名）——这是一份"禁止提前加载"的黑名单。
     *
     * <p>Mixin 的硬规则是"目标类一旦被加载就不能再注入"，而 {@code Class.forName(..., false, cl)}
     * 的 {@code false} 只关掉初始化、<b>不阻止加载</b>。所以任何"为了诊断/预热而加载类"的代码
     * 都可能替模组抢跑，报出一句离原因极远的
     * {@code MixinTargetAlreadyLoadedException ... was loaded too early}。
     * 实测事故：诊断用的身份打印加载了 {@code DeferredRegister}，
     * 直接让 {@code yumi_mc_core} 的 Mixin 失败并炸掉 axiom 的 preLaunch 入口点。
     *
     * <p>把这份名单写成 JVM 级属性，是为了让"另一份类加载器"里的桥接代码也能读到它
     * （我们的类会被两份加载器各定义一次，静态字段各一份，属性是唯一可靠通道）。
     */
    public static final String PROP_MIXIN_TARGETS = "fusion.mixinTargets";
    /**
     * 黑名单的<b>文件</b>形态：{@code -Dfusion.mixinTargetsFile=<路径>}。
     *
     * <p>为什么要另开一条文件通道，而不是只用系统属性：实测撞上了 Windows 的命令行长度上限。
     * 本整合包扫出 <b>846</b> 个 Mixin 目标，拼成一个 {@code -D} 参数大约 34KB，
     * 超过 CreateProcess 的上限，于是进程根本起不来，报一句跟原因毫不相干的：
     *
     * <pre>
     * IOException: Cannot run program "java.exe":
     *   CreateProcess error=206, 文件名或扩展名太长。
     * </pre>
     *
     * <p>更糟的是它的表现形式很有迷惑性：日志里既没有崩溃也没有报错（因为 JVM 没起来），
     * 看起来像"问题消失了"。所以属性通道仍然保留（小整合包时更快、无需文件），
     * 但**文件是权威通道**：属性只放一个路径，真正的名单写在文件里。
     */
    public static final String PROP_TARGETS_FILE = "fusion.mixinTargetsFile";
    /**
     * 本机 {@code libraries} 目录：判"新增接口是不是对方模组硬依赖"时要去那里读接口的形状。
     *
     * <p>为什么要显式传：合并发生在启动流程很靠前的位置，而"借来的跨端运行时包"
     * 是后面才解析出来的（实测日志：合并在第 14 行，运行时分拨在第 44 行）。
     * 不显式传的话，合并那一刻拿不到任何对方平台的 jar，判据退化成"一律必需"，
     * 于是把该保留原版字节的类也整类换掉，宿主平台的 {@code @Overwrite} Mixin 就崩了。
     */
    public static final String PROP_LIBRARIES = "fusion.librariesDir";

    private PatchedBase() {
    }

    /**
     * 启动器侧的总入口：决定这次要不要用补丁基座，并把它准备好。
     *
     * @return 应当排在原版 jar 之前的那个 jar；不需要时返回 {@code null}
     */
    public static Path forLaunch(McInstance instance, List<Path> modJars, String versionId,
                                 Consumer<String> log) {
        String mode = System.getProperty(PROP_MODE, "auto").trim();
        if ("off".equalsIgnoreCase(mode) || "false".equalsIgnoreCase(mode)) {
            log.accept("跨端补丁基座：已按 " + PROP_MODE + "=off 关闭");
            return null;
        }
        if (!"auto".equalsIgnoreCase(mode) && !mode.isEmpty()) {
            Path p = Path.of(mode);
            if (!Files.isRegularFile(p)) {
                log.accept("跨端补丁基座：指定的 jar 不存在：" + p);
                return null;
            }
            System.setProperty(PROP_RESOLVED, p.toAbsolutePath().toString());
            log.accept("跨端补丁基座：使用手动指定的一份 " + p.getFileName());
            return p;
        }

        // 一个纯 Fabric 整合包不该被换掉原版字节 —— 那是白担风险。

        // 先把"禁止提前加载"的黑名单扫出来，再谈要不要换基座。
        // 顺序很重要：这份名单的作用是保护**所有**模组的 Mixin，跟"这次换不换基座"无关 ——
        // 放在 foreign==0 的提前返回之后，纯 Fabric 整合包就得不到保护了。
        java.util.Set<String> allTargets = allMixinTargets(expandModJars(instance, modJars, log), log);
        if (!allTargets.isEmpty()) {

            // 原因见 PROP_TARGETS_FILE 的注释：846 个类名拼成 -D 参数约 34KB，

            Path targetsFile = instance.gameDir().resolve(".fusionml")
                    .resolve("mixin-targets.txt");
            try {
                Files.createDirectories(targetsFile.getParent());
                Files.write(targetsFile, allTargets, java.nio.charset.StandardCharsets.UTF_8);
                System.setProperty(PROP_TARGETS_FILE, targetsFile.toAbsolutePath().toString());
            } catch (Throwable t) {

                System.setProperty(PROP_MIXIN_TARGETS, String.join(",", allTargets));
                log.accept("跨端补丁基座：黑名单文件写不了（" + t + "），退回属性通道");
            }
            // 自证一行：这份名单必须真的覆盖到"对方平台运行时里的类"。
            // 上一轮就是漏了这种类（DeferredRegister 在 NeoForge 运行时包里，不在补丁包里），

            boolean coversForeign = allTargets.contains(
                    "net/neoforged/neoforge/registries/DeferredRegister");
            log.accept("跨端补丁基座：禁止提前加载名单 " + allTargets.size() + " 个目标"
                    + "（其中对方平台运行时类 DeferredRegister "
                    + (coversForeign ? "已覆盖" : "未覆盖") + "）");
        }
        int foreign = foreignModCount(modJars);
        if (foreign == 0) {
            log.accept("跨端补丁基座：模组目录里没有对方平台格式的包，沿用原版字节");
            return null;
        }
        String mcKey = mcKeyOf(versionId);

        Path vanillaJar = null;
        try {
            vanillaJar = instance.resolve(versionId).clientJar;
        } catch (Throwable t) {
            log.accept("跨端补丁基座：解析宿主版本定义失败（" + t + "）");
        }
        // 命名空间检查：合并是"按内部名搬运"，只有不混淆的版本才成立。

        // 这一步是 1.21.1 / 1.20.1 适配的第一道门。实测那两个版本的 jar 是混淆的

        // 与其搬出一份坏包让游戏崩在启动期，不如**在这里就明确说"做不到、需要 remap 层"**。
        if (vanillaJar != null) {
            log.accept("跨端补丁基座：宿主游戏包命名空间 —— " + namespaceReport(vanillaJar));
            if (!isDeobfuscated(vanillaJar)) {
                log.accept("跨端补丁基座：这个版本的游戏包是**混淆**的，"
                        + "而合并是按内部名搬运的（26.3 不混淆所以成立）。"
                        + "本次**不做合并**，跨端注册将不可用 —— "
                        + "要在该版本上工作，需要先补上 remap 层（见 README 第十四节）。");
                return null;
            }
        }
        Path jar = null;
        String platform = null;
        Path neo = locate(instance, "neoforge", mcKey);
        if (neo != null) {
            jar = neo;
            platform = "NeoForge";
        } else {
            Path forge = locate(instance, "forge", mcKey);
            if (forge != null) {
                jar = forge;
                platform = "Forge";
            }
        }
        if (jar == null) {
            log.accept("跨端补丁基座：本机没有 " + mcKey + " 的对方平台补丁原版 jar，"
                    + "本次只能按原版字节驱动（对方模组碰注册表 API 时会失败）");
            return null;
        }
        try {
            // 关键一步：宿主平台模组要 Mixin 的类，一律保留原版字节。
            // 理由不是猜测，是实测出来的：对方平台的补丁原版是"反编译再重新编译"的产物，
            // 方法体里的指令序列与原版并不逐条一致，而 Mixin 的注入点是按指令序列匹配的。

            // （报 Critical injection failure ... Scanned 0 target(s)），直接崩在启动阶段。
            // 把这些类留给原版，宿主平台的 Mixin 就还是原来那套输入，行为不变。
            // 这里必须用**模组全集**（含宿主平台的嵌套包缓存），不能用传进来的 modJars。

            // 踩过一次，代价是"保护名单看着有 403 个、却恰好漏掉出事的那个模组"：
            // modJars 只有 mods 目录里的 37 个包，而 Fabric 会把嵌套包解到

            // fabric-registry-sync-v0 就住在那里 —— 它要 Mixin 的 RegistryDataLoader

            java.util.Set<String> keepVanilla = hostMixinTargets(expandModJars(instance, modJars, null), jar, log);
            if (vanillaJar == null || !Files.isRegularFile(vanillaJar)) {
                log.accept("跨端补丁基座：拿不到宿主上的原版客户端包，本次只能按原版字节驱动");
                return null;
            }
            // 把 libraries 目录交给"接口形状"判据用 —— **必须在 prepare 之前设置**。

            // （实测日志：合并在第 14 行，运行时解析在第 44 行），

            // 于是 DataComponentMap$Builder 被整类替换，宿主平台的 @Overwrite Mixin 直接崩。

            System.setProperty(PROP_LIBRARIES, instance.librariesDir().toAbsolutePath().toString());
            Path base = prepare(jar, vanillaJar,
                    instance.gameDir().resolve(".fusionml").resolve("base"),
                    scope(), keepVanilla, log);
            System.setProperty(PROP_RESOLVED, base.toAbsolutePath().toString());
            log.accept("跨端补丁基座：" + platform + " 的补丁原版 " + jar.getFileName()
                    + " 已排在原版 jar 之前（" + foreign + " 个跨端模组包需要它）");
            return base;
        } catch (Throwable t) {
            // 注意这条日志的**严重性**：它意味着整个合并没做成，跨端那半边等于没启用
            // （对方模组碰注册表 API 时会失败）。所以除了打印异常，还要把栈打出来 ——
            // 踩过一次：这里只打了一句 "准备失败（保持原版字节）"，
            // 而真正的原因（一行 substring 越界）藏在栈里，白跑了好几轮。
            log.accept("跨端补丁基座准备失败（保持原版字节，跨端注册将不可用）：" + t);
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            for (String line : sw.toString().split("\n")) {
                if (line.contains("com.fusionml")) {
                    log.accept("    " + line.trim());
                }
            }
            return null;
        }
    }

    public static String scope() {
        String s = System.getProperty(PROP_SCOPE, DEFAULT_SCOPE).trim();
        return s.isEmpty() ? "all" : s;
    }

    /**
     * 游戏包里的类名是"真名"还是"混淆名"。
     *
     * <h2>为什么必须先判断这个</h2>
     *
     * <p>{@link #prepare} 的做法是"**按内部名**把对方平台的补丁类搬进游戏包"。
     * 这只有在两边命名空间一致时才成立：
     *
     * <ul>
     *   <li><b>26.3 不混淆</b>：游戏包里就是 {@code net/minecraft/core/Registry.class}，
     *       对方补丁产物也用同一套名字 —— 搬运即正确。</li>
     *   <li><b>1.21.1 / 1.20.1 混淆</b>：实测 {@code 1.21.1-Fabric 0.19.5.jar} 的
     *       23519 个条目里，{@code net/} 下只有 26 个入口类，其余是
     *       {@code chn.class}/{@code cho.class} 这种混淆名；
     *       而模组与对方补丁用的是另一套命名空间（{@code class_2378} 之类）。
     *       这时按名字搬运**一个都匹配不上**，或者塞进一批名字冲突的类导致
     *       {@code LinkageError}。</li>
     * </ul>
     *
     * <p>所以合并之前先探测一次：混淆的话，**不要假装能合**，
     * 而是明确报告"这个版本需要 remap 层"（见 README 第十四节）——
     * 报一句清楚的"做不到"，远好过搬出一份坏包再让游戏崩在启动期。
     *
     * <p>判据用 {@code net/minecraft/core/Registry.class} 在不在：
     * 它是不混淆版本最稳的锚点（注册表核心类，任何版本都有，且必然在 {@code net/minecraft/core/} 下）。
     */
    public static boolean isDeobfuscated(Path gameJar) {
        if (gameJar == null || !Files.isRegularFile(gameJar)) {
            return false;
        }
        try (FastZip z = FastZip.open(gameJar)) {
            return z.entry("net/minecraft/core/Registry.class") != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 游戏包命名空间的诊断描述（合并时写进日志，避免"默默搬了 0 个类"）。 */
    public static String namespaceReport(Path gameJar) {
        if (gameJar == null || !Files.isRegularFile(gameJar)) {
            return "游戏包不存在";
        }
        int mc = 0;
        int obfLike = 0;
        int total = 0;
        try (FastZip z = FastZip.open(gameJar)) {
            for (FastZip.Entry e : z.entries()) {
                if (!e.isClass() || e.isDirectory()) {
                    continue;
                }
                total++;
                String n = e.name();
                if (n.startsWith("net/minecraft/")) {
                    mc++;
                } else if (n.indexOf('/') < 0) {
                    // 顶层就是 x.class：典型的混淆布局
                    obfLike++;
                }
            }
        } catch (Throwable t) {
            return "读不了游戏包：" + t;
        }
        boolean deobf = isDeobfuscated(gameJar);
        return (deobf ? "不混淆（真名）" : "**混淆**")
                + "：类 " + total + " 个，其中 net/minecraft/ 下 " + mc
                + " 个、顶层裸类 " + obfLike + " 个";
    }

    /**
     * 造出这次真正要交给宿主平台的"游戏包"：<b>原版客户端包 + 对方平台的补丁覆盖</b>。
     *
     * <h2>为什么不能只是"旁挂一个补丁 jar"</h2>
     *
     * <p>试过了，宿主会明确拒绝：把补丁 jar 排在原版 jar 之前，宿主依然从原版 jar 取
     * {@code net.minecraft.core.Registry}，并且报出原因——
     *
     * <pre>
     * can't load class net.minecraft.core.Registry at …patched-base-….jar
     * as it hasn't been exposed to the game
     * </pre>
     *
     * <p>宿主平台 0.19 起对类路径做了分组：只有"游戏包"这一组里的 jar 才允许提供游戏类。
     * 旁挂的 jar 不在那一组里，于是补丁等于没生效，而失败点离真正原因很远。
     * 所以补丁必须<b>长在游戏包里</b>：把原版包整份搬运、再把范围内的类换成对方平台的补丁字节。
     * 这也是对方平台自己那份 {@code minecraft-client-patched} 的用法（它就是"游戏包"）。
     *
     * <p>搬运是原样搬压缩数据（{@link FastZip#readRaw}），不解压也不重压，
     * 所以 40MB 的包是秒级完成，并且结果会按"原版包 + 补丁包 + 范围"的指纹缓存起来，
     * 只有源包变了才会重做。
     */
    public static Path prepare(Path patchedJar, Path vanillaJar, Path cacheDir, String scope,
                               java.util.Set<String> keepVanilla, Consumer<String> log)
            throws IOException {
        String s = scope == null ? "all" : scope.trim();
        boolean all = s.isEmpty() || "all".equalsIgnoreCase(s);
        if (all && (keepVanilla == null || keepVanilla.isEmpty())) {

            return patchedJar;
        }
        String[] prefixes = all ? new String[0] : s.split("[,;]");
        for (int i = 0; i < prefixes.length; i++) {
            prefixes[i] = prefixes[i].trim().replace('.', '/');
        }
        Files.createDirectories(cacheDir);
        String stamp = Long.toHexString(patchedJar.toFile().lastModified())
                + "-" + Long.toHexString(patchedJar.toFile().length())
                + "-" + Long.toHexString(vanillaJar.toFile().length())
                // 合并规则本身变了也要换缓存：否则旧产物会被一直复用，
                // 而"日志说改了、现象却没变"是最费时间的一类故障（刚踩过一次）。
                + "-v" + CACHE_FORMAT
                + "-" + rulesFingerprint()
                + "-" + Integer.toHexString(s.hashCode())
                + "-" + Integer.toHexString(keepVanilla == null ? 0
                        : String.join(",", new java.util.TreeSet<>(keepVanilla)).hashCode());
        Path out = cacheDir.resolve("merged-base-" + stamp + ".jar");
        if (Files.isRegularFile(out) && Files.size(out) > 0) {
            // 命中缓存。

            // 但必须**显式说一句**："这次没重新合并"。踩过一次：改了合并规则却忘了

            // 而真机跑的还是旧产物 —— 白跑了一整轮（150 秒 + 一次崩溃分析）。

            log.accept("跨端补丁基座：命中合并缓存（本次未重新合并）→ " + out.getFileName()
                    + "；若刚改过合并规则，请确认 CACHE_FORMAT 已 +1（当前 " + CACHE_FORMAT + "）");
            // 命中缓存：预热名单也从它旁边那份 .types.txt 读回来，
            // 否则"第二次启动不预热"会变成一个只在首次启动后出现的怪故障。
            Path types = out.resolveSibling(out.getFileName() + ".types.txt");
            if (Files.isRegularFile(types)) {
                try {
                    System.setProperty(PROP_TYPES, String.join(",",
                            Files.readAllLines(types, java.nio.charset.StandardCharsets.UTF_8)));
                } catch (Throwable ignored) {

                }
            }
            return out;
        }
        long t0 = System.nanoTime();
        // 供"API 契约"判据读字节用；每次合并都重置，避免复用上一次的包。
        PATCHED_JAR_CACHE = patchedJar;
        VANILLA_JAR_CACHE = vanillaJar;
        CURRENT_VANILLA_JAR = vanillaJar;
        CURRENT_PATCHED_JAR = patchedJar;
        STRUCTURAL_CONFLICTS.clear();
        MIXIN_INJECTION_AT_RISK.clear();
        KEPT_VANILLA.clear();
        REQUIRED_IFACE_CACHE.clear();
        REPLACED_SEEDS.clear();
        SPLICE_COUNTS.clear();
        SPLICE_ATTEMPTED.clear();
        SPLICE_REFUSED.clear();
        spliceFailed.clear();
        apiOverridesMixin.clear();
        // 每次真正合并前清空：诊断列表不能跨次累积，否则第二次运行会报出上次的残留。
        INTERFACE_MISSES.clear();
        INTERFACE_CACHE.clear();

        java.util.Map<String, Path> overlay = new java.util.LinkedHashMap<>(1024);

        // 这不是可选优化，是必须的一步：补丁后的 Registry 头部指向 IRegistryExtension，

        // 会以 NoClassDefFoundError 收场（实测：日志里能同时看到"IRegistryExtension 由宿主定义成功"
        // 和"定义 Registry 时解析 IRegistryExtension 失败"）。先把这些对方类型预热解析掉，
        // 顺序就成立了。
        java.util.Set<String> foreignTypes = new java.util.TreeSet<>();
        try (FastZip pz = FastZip.open(patchedJar)) {
            for (FastZip.Entry e : pz.entries()) {
                if (!e.isClass() || e.isDirectory()) {
                    continue;
                }
                if (!all && !matches(prefixes, e.name())) {
                    continue;
                }
                String internal = e.name().substring(0, e.name().length() - ".class".length());
                // 宿主要 Mixin 的类一律保留原版字节 —— 注意这里**不能**再加 `all &&` 的条件。

                // 这里踩过一个代价很大的坑：原来写的是 `if (all && keepVanilla.contains(...))`，

                // 后果实测：net.minecraft.resources.RegistryDataLoader 明明在保留名单里

                // 正确语义：**无论整份还是收窄，模组的注入目标都必须保留原版字节**。
                // 这是硬约束：Mixin 的注入点按指令序列匹配，而对方那份补丁是反编译再编译的，

                // 但有一类例外，而且必须是例外：**对方平台 API 契约真正落脚的那些类**。

                //   · 它是某个宿主模组的 Mixin 目标（所以"保护名单"里有它）；

                // 如果因为"它在保护名单里"就保留原版字节，接口就丢了，跨端注册直接退回

                // 实测就是这么互为掣肘：保护名单从 403 扩到 783 之后，

                // 取舍规则：**API 契约优先于注入点保护**。
                // 理由是两者的失败方式不同 —— 丢了接口是"对方模组的模组跑不起来"（硬失败），

                // 注册表模型里是根，不能赌。
                // 【这里曾经有一个 `continue`，它绕过了后面所有精细判据 —— 已移除】

                // 实测后果：`ArgumentTypeInfos` 在保护名单里（某个宿主 Mixin 打它），
                // 于是它没进 overlay；而对方平台自己的 `NeoForgeMod` 静态初始化里调

                //     at net.neoforged.neoforge.common.NeoForgeMod.lambda$static$0
                // 启动直接崩。而它本来是可以"拼接"的（原版 8 个方法全留 + 补进对方的第 9 个）。

                overlay.put(e.name(), patchedJar);
                try {
                    ClassFile cf = ClassFile.read(pz.read(e));
                    collectForeignHeaderTypes(cf, foreignTypes);
                    if (keepVanilla != null && keepVanilla.contains(internal)) {
                        apiOverridesMixin.add(internal);
                    }
                } catch (Throwable ignored) {

                }
            }
        }
        Path tmp = cacheDir.resolve(out.getFileName() + ".tmp");

        // 为什么不能边写边记：ZIP 里的条目顺序没有保证，`Foo$14` 完全可能排在 `Foo` 前面，

        // 而这正是实测崩溃（ByteBufCodecs$14 构造器对不上）的成因。
        try (FastZip vz2 = FastZip.open(vanillaJar); FastZip pz2 = FastZip.open(patchedJar)) {
            for (String entry : overlay.keySet()) {
                String in = entry.substring(0, entry.length() - ".class".length());
                FastZip.Entry ve2 = vz2.entry(entry);
                FastZip.Entry pe2 = pz2.entry(entry);
                if (ve2 == null || pe2 == null) {
                    continue;
                }
                try {
                    byte[] vb = vz2.read(ve2);
                    byte[] pb2 = pz2.read(pe2);

                    //  但 net.minecraft.world.level.Level 也被换，于是 c2me 的 MixinWorld

                    //  于是我改成"超类变了 + 又是 Mixin 目标 → 保留原版"，
                    //  结果第二次崩在：
                    //    VerifyError: Bad type on operand stack

                    // 使用它的代码都会在校验期失败（硬崩）；而保留原版又会让宿主模组的

                    // 用对方字节（对方是"提供 API 的一方"，它的字节必须自洽），

                    if (superclassChanged(vb, pb2)) {

                        // 这一条是"两个致命选项之间"的最后判据，实测出来的：

                        //     VerifyError: 'MappedRegistry' is not assignable to 'BaseMappedRegistry'
                        //     —— 硬崩，没有选择余地。

                        //     c2me 的 MixinWorld 注入点抹掉了：
                        //       Scanned 0 target(s) → SharedConstants.<clinit> 崩 → 进程退出

                        // 有 → 必须换；没有 → 保留原版（宿主注入点优先）。
                        boolean foreignNeedsNewSuper = foreignUsesAsNewSuperclass(
                                in, pb2, pcfFor(pb2));
                        // 目标改成"**先能启动**"之后，这里的取舍规则跟着变了。

                        // 之前的规则是"对方按新超类用它 → 整类换"，理由是避免 VerifyError。
                        // 但实测有一条硬约束是它没覆盖的：

                        //   可它**同时是宿主模组 fabric-content-registries 的 Mixin 目标**，
                        //   换成对方字节就让那个注入点匹配 0 个目标 → 启动即崩：
                        //     fluid.EntityMixin checkIfUnderSwimmableFluid ... Scanned 0 target(s)
                        //     → Minecraft has crashed!

                        // 两个都是硬需求时，能不能跑起来只取决于**哪一边会立刻崩**：
                        //   · 保留原版 → 宿主 Mixin 能注入，游戏能起来；
                        //     代价是跨端模组调 `setData` 时缺方法（可诊断、且不是启动期硬崩）。
                        //   · 换对方字节 → 宿主 Mixin 注入失败，**游戏根本起不来**。

                        // 所以：**宿主 Mixin 目标一律保留原版**，除非对方对它的依赖
                        // 会导致宿主那些字节**必然校验失败**（`MappedRegistry` 就是这种：
                        // 对方按新超类使用它，不换就是 VerifyError）。

                        boolean strict = foreignNeedsNewSuper
                                && foreignNeedsStrictSuperclassType(in, pb2);
                        if (TRACE_CLASSES.contains(in)) {
                            log.accept("  · 超类裁决 " + in + "：对方按新超类用它="
                                    + foreignNeedsNewSuper + "，必须跟对方类型=" + strict
                                    + (strict ? " → 整类换" : " → 保留原版（宿主 Mixin 优先，保启动）"));
                        }
                        if (keepVanilla != null && keepVanilla.contains(in) && !strict) {
                            KEPT_VANILLA.add(in);
                            if (STRUCTURAL_CONFLICTS.size() < 200) {
                                STRUCTURAL_CONFLICTS.add(in + "（保留原版：宿主 Mixin 优先，保启动）");
                            }
                            continue;
                        }
                        if (keepVanilla != null && keepVanilla.contains(in)) {
                            if (STRUCTURAL_CONFLICTS.size() < 200) {
                                STRUCTURAL_CONFLICTS.add(in + "（改用对方字节：对方按新超类使用它）");
                            }
                        }
                        continue;                 
                    }
                    if (keepVanilla != null && keepVanilla.contains(in)
                            && !carriesRequiredInterface(in)) {
                        KEPT_VANILLA.add(in);
                        continue;
                    }
                    // 宿主 Mixin 目标 + **接口**被对方改过（超类没变）→ 拼接做不到，

                    // 预扫必须做出同样的判断，否则匿名兄弟会跟错宿主。
                    // 注意：超类变过的情况**不在这里** —— 那种必须整类换（见上）。
                    if (keepVanilla != null && keepVanilla.contains(in)
                            && interfacesChanged(vb, pb2)) {
                        KEPT_VANILLA.add(in);
                        continue;
                    }
                    if (onlyAddsMembers(vb, pb2, patchedJar)
                            && !interfacesChanged(vb, pb2)) {
                        KEPT_VANILLA.add(in);
                    }
                } catch (Throwable ignored) {

                }
            }
        }

        for (String in : new ArrayList<>(overlay.keySet())) {
            String bare = in.substring(0, in.length() - ".class".length());
            String outer = anonymousOuterOf(bare);
            if (outer != null && KEPT_VANILLA.contains(outer)) {
                KEPT_VANILLA.add(bare);
            }
        }
        int total = 0;
        int replaced = 0;
        int additiveKept = 0;
        int splicedClasses = 0;
        try (FastZip vz = FastZip.open(vanillaJar);
             FastZip pz = FastZip.open(patchedJar);
             OutputStream os = Files.newOutputStream(tmp);
             FastZipWriter writer = new FastZipWriter(os)) {
            java.util.Set<String> done = new java.util.HashSet<>(overlay.size() * 2);
            for (FastZip.Entry e : vz.entries()) {
                if (isSignature(e.name())) {

                    // 而部分类加载路径（例如离线自检里的 URLClassLoader）会真的去校验它，

                    continue;
                }
                FastZip.Entry pe = overlay.containsKey(e.name()) ? pz.entry(e.name()) : null;
                // 这个类的内部名：后面几个判据（Mixin 目标保护、拼接）都要用它。

                // 注意要先判"是不是 .class" —— 目录条目与非类资源的名字可能很短

                // 实测踩过一次：StringIndexOutOfBoundsException: Range [0, -2) out of bounds，

                // "补丁基座准备失败（保持原版字节）" —— 整个合并白做，症状离原因很远。
                String internal = null;
                if (e.isClass() && !e.isDirectory()
                        && e.name().endsWith(".class")
                        && e.name().length() > ".class".length()) {
                    internal = e.name().substring(0, e.name().length() - ".class".length());
                }
                if (TRACE_CLASSES.contains(internal)) {
                    log.accept("  · 循环见到 " + internal + "，在补丁覆盖里="
                            + (pe != null) + "，已在 done 里=" + done.contains(e.name()));
                }
                if (pe != null && internal != null) {
                    if (TRACE_CLASSES.contains(internal)) {
                        log.accept("  · 进入裁决 " + internal + "：写入循环");
                    }

                    // 这次踩到的坑正是"离线判据说该换、产物却是原版"，

                    if (TRACE_CLASSES.contains(internal)) {
                        log.accept("  · 分支追踪 " + internal
                                + "：Mixin目标=" + (keepVanilla != null && keepVanilla.contains(internal))
                                + "，超类变=" + superclassChanged(vz.read(e), pz.read(pe))
                                + "，接口变=" + interfacesChanged(vz.read(e), pz.read(pe))
                                + "，只新增=" + onlyAddsMembers(vz.read(e), pz.read(pe), patchedJar)
                                + "，预处理保留=" + KEPT_VANILLA.contains(internal)
                                + "，结构性冲突=" + STRUCTURAL_CONFLICTS.contains(internal));
                    }
                    // 匿名内部类必须与它的宿主类**同进同退**。

                    // 这一条是踩出来的，代价是一次自检崩溃：

                    // 原因：`ByteBufCodecs` 走"只新增 → 保留原版/拼接"，而它的匿名兄弟

                    // 匿名类的编号（$1、$14）是编译器按出现顺序分配的，两次独立编译
                    // 里同一个编号不是同一个逻辑类，所以**它们不能各自独立地决定用谁的字节**：
                    // 宿主用谁的，兄弟就必须用谁的。
                    String outer = anonymousOuterOf(internal);
                    // 注意：**这里不要再对 STRUCTURAL_CONFLICTS 做"保留原版"的早退出**。

                    // 踩过一次：上一轮"结构性冲突"的语义是"保留原版"，

                    // 后来把语义改成"仍要整类换、只是记录牺牲"（因为超类变化必然 VerifyError），

                    // 崩在 VerifyError: 'MappedRegistry' is not assignable to 'BaseMappedRegistry'。

                    //   MappedRegistry：Mixin目标=true，超类变=true，预处理保留=false，结构性冲突=true
                    // 预扫已经负责"记录"，写入循环只该负责"执行"，两者不能各有一套语义。
                    // 宿主类这次是"保留原版/拼接"的话，匿名兄弟也必须保留原版 ——
                    // 否则原版的方法会去 new 一个已经换成对方字节的匿名类，构造器签名对不上。

                    // 所以不受 zip 条目顺序影响。
                    if (outer != null && KEPT_VANILLA.contains(outer)) {
                        writer.addRaw(e.name(), e.method(), e.crc(), e.size(), vz.readRaw(e));
                        total++;
                        additiveKept++;
                        done.add(e.name());
                        continue;
                    }

                    // 因为整类换会把对方**新增**的方法带进原版类，而宿主平台模组的
                    // @Overwrite 型 Mixin 是按"原版没有这个方法"写的，于是崩在
                    //   InvalidMixinException: get(Lnet/minecraft/core/component/DataComponentType;)...

                    // （实测：fabric-item-api-v1 的 overwrites.requireAnnotations=true 就是这么崩的）

                    // 当前这轮先做**安全的那一半**：只新增的类保留原版字节。
                    // 代价是对方模组看不到那些新增的 API 方法；好处是宿主平台不崩。
                    // 这是明确取舍，不是遗漏 —— 后续要做的是"把新增成员拼进原版字节"，

                    boolean onlyAdds = onlyAddsMembers(vz.read(e), pz.read(pe), patchedJar);
                    if (onlyAdds) {

                        // 也不能保留原版 —— 实测：Holder 只是多实现了 IHolderExtension

                        // 可对方版本的 Registry 会调 Holder.getKey()，而那是通过

                        boolean ifaceChanged = interfacesChanged(vz.read(e), pz.read(pe));

                        // 这一步是必须的，不能用"保留原版"糊过去 —— 实测：

                        // 按"只新增"判据它会保留原版；但对方版本的

                        boolean spliceSafe = !superclassChanged(vz.read(e), pz.read(pe))
                                && !ifaceChanged;
                        // 接口变了的类要分两种，不能一刀切 —— 这是一次回归换来的教训。

                        //  · Holder 多了 IHolderExtension（纯 default）：**必须**用对方字节，
                        //    因为对方版本的 Registry 会调 Holder.getKey()（那个接口提供的

                        //    （也是纯 default）：**必须**保留原版字节，否则宿主平台的
                        //    @Overwrite Mixin 崩（fabric-item-api-v1 的 requireAnnotations）。

                        //  1) 这个类是不是宿主模组 Mixin 的目标（keepVanilla）——
                        //     是 → 换掉会直接崩掉某个模组的注入点，风险最高，优先保留原版；

                        // 这个组合判据在实测的两个反例上都给出了正确答案，

                        boolean mixinProtected = keepVanilla != null && keepVanilla.contains(internal);
                        // "必然崩"的接口判据要放在 Mixin 保护**之前**。

                        // 踩过一次：Registry 既是宿主 Mixin 目标、又被对方当 IRegistryExtension

                        // 那是**没有选择余地**的硬崩（Java 的 lambda 接收者转换在运行期检查），
                        // 而"换对方字节"最多是 Mixin 注入点失配（可能崩、且我们会记进报告）。
                        // 所以这条必须优先。
                        boolean ifaceFatal = ifaceChanged
                                && foreignUsesClassAsNewInterface(ClassFile.read(pz.read(pe)),
                                        addedInterfacesOf(vz.read(e), pz.read(pe)));
                        boolean callersUse = !ifaceFatal && foreignCallersUse(ClassFile.read(vz.read(e)),
                                ClassFile.read(pz.read(pe)), patchedJar);
                        // 裁决顺序（**Mixin 保护优先**，这是"先能启动"目标下的硬规则）：

                        //     宿主平台自己的代码（实测 Holder 的对方内部类 Holder$Reference 调 getKey）。
                        //  2) 否则 → 保留原版（Mixin 能注入，启动不崩）。

                        // 注意：`ifaceFatal`（对方把本类当新接口类型接收）**不能**压过 Mixin 保护。

                        // 宿主 Mixin 撞名（`get` 已存在）→
                        //   InvalidMixinException: ... because @Overwrite is required
                        //   → 启动崩。
                        // 两害相权：Mixin 注入失败是**启动必崩**；
                        // 而 LambdaConversionException 只在跨端模组真正走到那条路径时才崩
                        //（功能缺失，可诊断）。所以 Mixin 保护必须优先。
                        boolean ifaceNeeded = ifaceChanged
                                && (callersUse || (!mixinProtected && ifaceFatal));
                        // 还有一种情况：这个类是 Mixin 目标，但**它的 Mixin 不声明任何方法名**。

                        // 那种 Mixin 只会 @Inject/@Redirect 到已有方法，**不会"创建"新方法**，
                        // 所以整类换不会引起 `@Overwrite is required` 撞名 —— 可以安全地换。

                        // 实测 Registry：它在保护名单里，Mixin 声明的方法名是**空集**，
                        // 而对方（yumi_mc_core 的 NeoForge 路径）把它当 IRegistryExtension 接收。

                        // ——启动必崩。既然它的 Mixin 不声明方法名、换了也不会撞，
                        // 那就必须换。
                        boolean mixinDeclaresNoMethod = mixinProtected && ifaceFatal
                                && !mixinDeclaresAnyMethod(internal);
                        if (mixinDeclaresNoMethod && !ifaceNeeded) {
                            ifaceNeeded = true;
                            if (TRACE_CLASSES.contains(internal)) {
                                log.accept("  · 接口裁决 " + internal
                                        + "：Mixin 不声明方法名（不会撞名）→ 虽然被保护也必须整类换");
                            }
                        }
                        if (TRACE_CLASSES.contains(internal)) {
                            log.accept("  · 接口裁决 " + internal + "：接口变=" + ifaceChanged
                                    + "，Mixin保护=" + mixinProtected
                                    + "，必然崩=" + ifaceFatal
                                    + "，调用者用到=" + callersUse
                                    + "，合并后=" + ifaceNeeded
                                    + (ifaceNeeded ? " → 整类换" : " → 拼接/保留原版")
                                    + "；细节 " + HOOK_TRACE.getOrDefault(internal, "（无）"));
                        }
                        if (ifaceNeeded) {
                            writer.addRaw(e.name(), pe.method(), pe.crc(), pe.size(), pz.readRaw(pe));
                            total++;
                            replaced++;
                            done.add(e.name());
                            continue;
                        }
                        byte[] withExtras = spliceSafe
                                ? spliceNewMethods(vz.read(e), pz.read(pe), internal) : null;
                        // Mixin 目标类还要多一道检查：**拼接进来的方法名不能撞上 Mixin 自己声明的方法**。

                        // 实测（ClientCommonPacketListenerImpl）：
                        //   fabric-networking-api-v1 的 accessor Mixin 要"创建" getConnection()，
                        //   而对方（NeoForge）**也已经加了同名方法**；我们把对方那个拼进去之后，
                        //   Mixin 发现目标类上已经有这个方法，就要求用 @Overwrite：
                        //     InvalidMixinException: getConnection()... cannot overwrite method

                        //   → MixinApplyError → BootstrapMethodError → 启动崩。

                        // 和之前 DataComponentMapBuilderMixin 那次是同一类问题，
                        // 结论也一样：**对方新增的方法名与 Mixin 要注入/创建的方法名冲突时，
                        // 只能纯保留原版**（拼接会替 Mixin 把方法先占掉）。
                        if (withExtras != null && keepVanilla != null && keepVanilla.contains(internal)
                                && mixinDeclaredNamesConflict(internal, withExtras)) {
                            if (spliceFailed.size() < 12) {
                                spliceFailed.add(internal + "（拼接会与宿主 Mixin 声明的方法撞名，改纯保留原版）");
                            }
                            withExtras = null;
                        }
                        if (withExtras != null) {
                            // 写完之前先自检：拼接产物必须是"能被重新解析且索引自洽"的类。
                            // 这一步是把"启动期才暴露的 VerifyError"提前到合并期。
                            String why = selfCheck(withExtras);
                            if (why != null) {
                                if (spliceFailed.size() < 12) {
                                    spliceFailed.add(internal + "（自检：" + why + "）");
                                }
                                writer.addRaw(e.name(), e.method(), e.crc(), e.size(),
                                        vz.readRaw(e));
                                total++;
                                additiveKept++;
                                KEPT_VANILLA.add(internal);
                                done.add(e.name());
                                continue;
                            }
                            writer.add(e.name(), withExtras, true);
                            total++;
                            additiveKept++;
                            splicedClasses++;
                            KEPT_VANILLA.add(internal);
                            done.add(e.name());
                            continue;
                        }

                        // 代价只是对方模组看不到那几个新增成员。
                        writer.addRaw(e.name(), e.method(), e.crc(), e.size(), vz.readRaw(e));
                        total++;
                        additiveKept++;
                        KEPT_VANILLA.add(internal);
                        done.add(e.name());
                        continue;
                    }

                    //  a) 这个类是宿主模组的 Mixin 目标 —— 整类换会把注入点抹掉
                    //     （实测：RegistryDataLoader 的 @ModifyArg 报 could not find any
                    //     targets，因为 NeoForge 把 load(...) 重载成了 5 个参数，
                    //     而 Mixin 按原版 4 参数签名找）。

                    //     实测 CustomPacketPayload：

                    //       而 Fabric 的 networking API（MixinExtras 桥接）还在按 2 参数调：

                    boolean vanillaMethodMissing = vanillaMethodMissingFromPatched(
                            ClassFile.read(vz.read(e)), ClassFile.read(pz.read(pe)));
                    if ((keepVanilla != null && keepVanilla.contains(internal))
                            || vanillaMethodMissing) {
                        // 但在拼接之前必须先排除两类"拼接做不到"的改动：

                        //     对方的字节按新超类编译，会崩在
                        //     VerifyError: Bad type on operand stack ... not assignable to

                        //     （实测：yumi_mc_core 的 NeoForge 路径就是这么崩的，

                        boolean splicesafe = !superclassChanged(vz.read(e), pz.read(pe))
                                && !interfacesChanged(vz.read(e), pz.read(pe));
                        byte[] spliced = splicesafe
                                ? spliceNewMethods(vz.read(e), pz.read(pe), internal) : null;
                        if (spliced != null) {
                            String why = selfCheck(spliced);
                            if (why == null) {
                                writer.add(e.name(), spliced, true);
                                total++;
                                splicedClasses++;
                                done.add(e.name());
                                continue;
                            }
                            if (spliceFailed.size() < 12) {
                                spliceFailed.add(internal + "（自检：" + why + "）");
                            }
                        }

                        // 裁决顺序（每一条都是实测换来的）：

                        //     实测 Level/c2me：换掉就是 Scanned 0 target(s)）；

                        //     → 也保留原版。目标改成"先能启动"之后的取舍：
                        //     实测 Entity 的补丁字节真的调了 setData（判据为真），
                        //     但它同时是 fabric-content-registries 的 Mixin 目标 ——

                        //     （不换必然 VerifyError，实测 MappedRegistry/RegistryBuilder）；

                        //     实测 LivingEntity/fabric-content-registries）。

                        // 注意这一段的判据必须与**预扫**完全一致，否则会出现
                        // "预扫说保留、写入循环说换"的分岔（昨天踩过一次）。
                        if (superclassChanged(vz.read(e), pz.read(pe))) {
                            boolean foreignNeeds = foreignUsesAsNewSuperclass(
                                    internal, pz.read(pe), ClassFile.read(pz.read(pe)));
                            boolean strict = foreignNeeds
                                    && foreignNeedsStrictSuperclassType(internal, pz.read(pe));
                            if (!strict) {
                                if (MIXIN_INJECTION_AT_RISK.size() < 200) {
                                    MIXIN_INJECTION_AT_RISK.add(internal
                                            + (foreignNeeds
                                            ? "（超类变化，对方按新超类用它但不 new 它）"
                                            : "（超类变化，对方未按新超类使用）"));
                                }
                                writer.addRaw(e.name(), e.method(), e.crc(), e.size(), vz.readRaw(e));
                                total++;
                                additiveKept++;
                                KEPT_VANILLA.add(internal);
                                done.add(e.name());
                                continue;
                            }
                            writer.addRaw(e.name(), pe.method(), pe.crc(), pe.size(), pz.readRaw(pe));
                            replaced++;
                            done.add(e.name());
                            continue;
                        }

                        // 但有一条**必须优先**的例外：对方把这个类**当成新增接口类型**接收

                        // —— 没有选择余地，必须换对方字节。

                        // 实测：Registry 就落在这个兜底分支（它接口变过、又是 Mixin 目标），
                        // 之前一律保留原版 → 启动崩在 yumi_mc_core 的 NeoForge 路径上。
                        java.util.Set<String> addedIfaces =
                                addedInterfacesOf(vz.read(e), pz.read(pe));
                        if (!addedIfaces.isEmpty()
                                && foreignUsesClassAsNewInterface(ClassFile.read(pz.read(pe)),
                                        addedIfaces)
                                && (!isHostMixinTargetProtected(internal, keepVanilla)
                                    || !mixinDeclaresAnyMethod(internal))) {                            if (TRACE_CLASSES.contains(internal)) {
                                log.accept("  · 兜底裁决 " + internal
                                        + "：对方当新接口类型接收=true → 整类换（否则必然崩）");
                            }
                            writer.addRaw(e.name(), pe.method(), pe.crc(), pe.size(), pz.readRaw(pe));
                            replaced++;
                            done.add(e.name());
                            continue;
                        }
                        if (TRACE_CLASSES.contains(internal) && !addedIfaces.isEmpty()) {
                            log.accept("  · 兜底裁决 " + internal
                                    + "：对方当新接口类型接收=true，但它同时是宿主 Mixin 目标"
                                    + " → **保留原版**（两害相权：Mixin 注入失败是启动必崩，"
                                    + "而接口缺失只在跨端模组真正调用时才崩）");
                        }
                        if (MIXIN_INJECTION_AT_RISK.size() < 200) {
                            MIXIN_INJECTION_AT_RISK.add(internal);
                        }
                        writer.addRaw(e.name(), e.method(), e.crc(), e.size(), vz.readRaw(e));
                        total++;
                        additiveKept++;
                        KEPT_VANILLA.add(internal);
                        done.add(e.name());
                        continue;
                    }
                    // 最后一道路障：**整类换也不能让宿主 Mixin 撞名**。

                    // 实测（DataComponentMap$Builder）：它不在保留名单里（黑名单没覆盖到

                    // Mixin 的 DataComponentMapBuilderMixin 想"创建"同名方法时就变成"覆盖"，

                    //   InvalidMixinException: get(...) cannot overwrite method in

                    //   → MixinApplyError → BootstrapMethodError → 启动崩。

                    // 判据不看"是否在保留名单"，而是直接看**这个类的 Mixin 声明了哪些方法名**，
                    // 以及**对方字节里新增了哪些同名方法** —— 有交集就必须保留原版，
                    // 让 Mixin 自己去创建那些方法。
                    if (mixinReplacementConflicts(internal, vz.read(e), pz.read(pe))) {
                        MIXIN_INJECTION_AT_RISK.add(internal + "（整类换会让宿主 Mixin 撞名，改保留原版）");
                        log.accept("  · 撞名保护 " + internal + "（整类换会让宿主 Mixin 撞名 → 保留原版）");
                        writer.addRaw(e.name(), e.method(), e.crc(), e.size(), vz.readRaw(e));
                        total++;
                        additiveKept++;
                        KEPT_VANILLA.add(internal);
                        done.add(e.name());
                        continue;
                    }
                    writer.addRaw(e.name(), pe.method(), pe.crc(), pe.size(), pz.readRaw(pe));
                    replaced++;
                    done.add(e.name());
                    continue;
                }                writer.addRaw(e.name(), e.method(), e.crc(), e.size(), vz.readRaw(e));
                total++;
            }

            for (FastZip.Entry e : pz.entries()) {
                if (done.contains(e.name()) || !overlay.containsKey(e.name())) {
                    continue;
                }
                writer.addRaw(e.name(), e.method(), e.crc(), e.size(), pz.readRaw(e));
                replaced++;
            }
        }
        Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        // 而"读不到"和"真的必需"在日志里长得一样，所以必须显式报出来。
        java.util.List<String> misses = interfaceMisses();
        if (!misses.isEmpty()) {
            log.accept("跨端补丁基座：注意 —— 有 " + misses.size()
                    + " 个新增接口读不到形状（判据退化为\"必需\"，会整类替换）："
                    + String.join("、", misses.subList(0, Math.min(3, misses.size()))));
        }
        // 另一处必须显式报出来的取舍：这些类既是宿主模组的 Mixin 目标、又是对方平台

        java.util.List<String> overrides = apiOverrides();
        if (!overrides.isEmpty()) {
            log.accept("跨端补丁基座：取舍 —— " + overrides.size()
                    + " 个类同时是\"模组 Mixin 目标\"与\"对方 API 契约承载者\"，"
                    + "本次选择满足 API 契约（改用对方字节，放弃注入点保护）："
                    + String.join("、", overrides.subList(0, Math.min(3, overrides.size()))));
        }
        java.util.List<String> missingReq = missingRequired();
        if (!missingReq.isEmpty()) {
            log.accept("跨端补丁基座：另有 " + missingReq.size()
                    + " 个类因为读不到接口形状而按\"必需\"保守处理（放弃注入点保护）："
                    + String.join("、", missingReq.subList(0, Math.min(3, missingReq.size()))));
        }
        log.accept("跨端补丁基座：已合并出游戏包（原版 " + total + " 条 + 覆盖 " + replaced
                + " 条，其中 " + additiveKept + " 条是\"对方只新增成员、保留原版字节\""
                + (splicedClasses == 0 ? "" : "、" + splicedClasses + " 条是\"保留原版 + 拼接对方新增方法\"")
                + "，范围 " + s + "）→ " + out.getFileName()
                + "，耗时 " + ((System.nanoTime() - t0) / 1_000_000) + " ms");
        java.util.List<String> splicedList = splicedReport();
        if (!splicedList.isEmpty()) {
            log.accept("跨端补丁基座：方法级拼接 " + splicedList.size() + " 个类 —— "
                    + "保留原版字节（宿主模组的 Mixin 注入点因此还在），"
                    + "同时补进对方新增的方法（对方模组因此调得到）："
                    + String.join("、", splicedList.subList(0, Math.min(4, splicedList.size()))));
        }
        java.util.List<String> failedList = spliceFailures();
        if (!failedList.isEmpty()) {
            log.accept("跨端补丁基座：有 " + failedList.size()
                    + " 个类拼接失败、退回整类换（这些类的宿主 Mixin 注入点可能失效）："
                    + String.join("、", failedList.subList(0, Math.min(4, failedList.size()))));
        }
        java.util.List<String> structural = structuralConflicts();
        if (!structural.isEmpty()) {
            log.accept("跨端补丁基座：结构性冲突 " + structural.size()
                    + " 个类 —— 对方换了它们的超类，而它们又是宿主模组的 Mixin 目标；"
                    + "Java 没有多继承、两边只能留一边，本次选择保留原版（宿主模组优先），"
                    + "对方基于新超类的能力在这些类上缺失："
                    + String.join("、", structural.subList(0, Math.min(5, structural.size()))));
        }
        java.util.List<String> risk = mixinInjectionProtected();
        if (!risk.isEmpty()) {
            log.accept("跨端补丁基座：注入点保护 " + risk.size()
                    + " 个类 —— 它们既要被对方改动、又是宿主模组的 Mixin 目标，"
                    + "拼接做不到（超类或接口变过）；本次保留原版字节，"
                    + "对方那部分能力在这些类上缺失（依赖这些新增成员的跨端模组会失败）："
                    + String.join("、", risk.subList(0, Math.min(5, risk.size()))));
        }
        // 预热名单最后过一道安全闸：**凡是模组 Mixin 要注入的目标，一律从名单里剔掉**。

        // 但"只收接口"是一个**基于经验的判据**（接口极少是注入目标），而不是硬保证 ——

        // 宿主平台模组的 Mixin 目标清单是我们自己算出来的（hostMixinTargets），

        // 事故原文（yumi_mc_core 的 DeferredRegisterMixin，见 collectForeignHeaderTypes 注释）：
        // MixinTargetAlreadyLoadedException ... was loaded too early.
        if (!foreignTypes.isEmpty() && keepVanilla != null && !keepVanilla.isEmpty()) {
            int before = foreignTypes.size();
            foreignTypes.removeAll(keepVanilla);
            if (foreignTypes.size() < before) {
                log.accept("跨端补丁基座：预热名单剔除 " + (before - foreignTypes.size())
                        + " 个模组 Mixin 的目标类（提前加载它们会导致 MixinTargetAlreadyLoadedException）");
            }
        }

        if (!foreignTypes.isEmpty()) {
            System.setProperty(PROP_TYPES, String.join(",", foreignTypes));
            try {
                Files.write(out.resolveSibling(out.getFileName() + ".types.txt"),
                        foreignTypes, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Throwable ignored) {

            }
            log.accept("跨端补丁基座：预热解析名单 " + foreignTypes.size() + " 个对方类型（"
                    + String.join("、", new ArrayList<>(foreignTypes).subList(0,
                            Math.min(4, foreignTypes.size())))
                    + (foreignTypes.size() > 4 ? "…" : "") + "）");
        }
        return out;
    }

    /** 拼接失败（退回整类换）的类（诊断用）。 */
    private static final java.util.List<String> spliceFailed =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    private static final java.util.Map<String, Integer> SPLICE_COUNTS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 以原版字节为基，把对方平台<b>新增的</b>方法拼进去。
     *
     * <p>这是"方法级拼接"的落地。对方改了原版已有成员的签名时（实测：NeoForge 把
     * {@code RegistryDataLoader.load(LoaderFactory,List,List,Executor)} 变成带第 5 个
     * {@code boolean} 参数的版本），而宿主平台的模组又按<b>原版签名</b>注入 ——
     * 两边都要满足，就只能把两个重载都留下。
     *
     * <p>只拼"原版里没有的同名同描述符方法"（真正的新增），不覆盖任何原版方法：
     * 覆盖会破坏宿主模组的注入点，那正是我们要避免的。
     *
     * @return 拼好的类字节；拼不了返回 {@code null}（调用方退回整类换并记录）
     */
    private static byte[] spliceNewMethods(byte[] vanillaBytes, byte[] patchedBytes, String internal) {
        try {
            ClassFile v = ClassFile.read(vanillaBytes);
            ClassFile p = ClassFile.read(patchedBytes);
            java.util.Set<String> have = new java.util.HashSet<>(v.methods().length * 2);
            for (ClassFile.Member m : v.methods()) {
                have.add(v.utf8(m.nameIndex) + v.utf8(m.descIndex));
            }
            // 能不能连构造器一起拼？条件是"对方保留了原版的全部构造器签名"。

            // 只有这样才安全：对方的新构造器是**纯新增的重载**，

            boolean canSpliceConstructors = true;
            {
                java.util.Set<String> patchedCtors = new java.util.HashSet<>();
                int vanillaCtors = 0;
                for (ClassFile.Member m : p.methods()) {
                    if ("<init>".equals(p.utf8(m.nameIndex))) {
                        patchedCtors.add(p.utf8(m.descIndex));
                    }
                }
                for (ClassFile.Member m : v.methods()) {
                    if (!"<init>".equals(v.utf8(m.nameIndex))) {
                        continue;
                    }
                    vanillaCtors++;
                    if (!patchedCtors.contains(v.utf8(m.descIndex))) {
                        canSpliceConstructors = false;   
                    }
                }
                if (vanillaCtors == 0) {
                    canSpliceConstructors = false;       
                }
            }
            int spliced = 0;
            int attempted = 0;
            for (ClassFile.Member m : p.methods()) {
                String name = p.utf8(m.nameIndex);
                String desc = p.utf8(m.descIndex);
                if (name == null || desc == null) {
                    continue;
                }

                // 理由：它们的语义与这个类自己的初始化顺序绑在一起，直接搬会让同一个对象

                // 【但有例外，而且是必须的例外】

                // NoSuchMethodError。实测 TicketType：
                //   NeoForgeMod 注册 TicketType 时调 <init>(long, int, boolean)，

                //       at net.neoforged.neoforge.common.NeoForgeMod.lambda$static$70

                //   · 原版**有**构造器（说明它不是纯 record 合成体，链路可控）；
                //   · 对方**保留了原版的全部构造器签名**（否则说明对方改过构造语义，

                if (name.startsWith("<")) {
                    if (!"<init>".equals(name) || !canSpliceConstructors) {
                        continue;
                    }
                }
                if (have.contains(name + desc)) {
                    continue;      
                }
                if ((m.access & 0x0002) != 0) {

                    continue;
                }
                attempted++;
                // 新方法自己 `new` 的那个匿名兄弟，必须在原版里也能用 —— 否则不能拼。
                if (newsDivergentAnonymous(v, p, m, internal)) {
                    if (SPLICE_REFUSED.size() < 24) {
                        SPLICE_REFUSED.add(internal + "#" + name + desc + "（引用匿名内部类，两份编译产物编号含义不同）");
                    }
                    continue;
                }
                if (v.spliceMethodFrom(p, m)) {
                    spliced++;
                } else if (SPLICE_REFUSED.size() < 24) {
                    // 记下"想拼但拼不了"的方法，方便判断是不是判据/扫描能力不够，
                    // 而不是"本来就没有可拼的东西"。
                    SPLICE_REFUSED.add(internal + "#" + name + desc);
                }
            }
            SPLICE_ATTEMPTED.put(internal, attempted);

            // 实测就是这么崩的（TicketType 是个 record，对方新增了一个组件字段，

            // 只在**构造器可以安全拼接**时搬字段：否则"一半字段按原版、一半按对方"，
            // 对象状态会自相矛盾。字段搬运本身是安全的（新增字段不影响原有成员）。
            int fieldsAdded = 0;
            if (canSpliceConstructors) {
                java.util.Set<String> haveFields = new java.util.HashSet<>();
                for (ClassFile.Member f : v.fields()) {
                    haveFields.add(v.utf8(f.nameIndex) + " " + v.utf8(f.descIndex));
                }
                for (ClassFile.Member f : p.fields()) {
                    String fn = p.utf8(f.nameIndex);
                    String fd = p.utf8(f.descIndex);
                    if (fn == null || fd == null || haveFields.contains(fn + " " + fd)) {
                        continue;                     
                    }
                    if ((f.access & 0x0008) != 0) {

                        continue;
                    }
                    byte[] raw = p.importMemberForBridge(v, f);
                    if (raw != null) {
                        v.addFieldRaw(raw);
                        haveFields.add(fn + " " + fd);
                        fieldsAdded++;
                    }
                }
            }
            if (spliced == 0 && fieldsAdded == 0) {
                return null;
            }
            SPLICE_COUNTS.put(internal, spliced);
            LAST_SPLICED_CLASS = v;
            return v.toBytes();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 这个方法是不是 `new` 了一个"两份编译产物里含义不同的匿名兄弟"。
     *
     * <h2>为什么必须跳过</h2>
     *
     * <p>匿名内部类的编号（{@code Foo$1}、{@code Foo$14}）是**编译器按出现顺序分配的**。
     * 原版与对方补丁是两次独立编译，同一个编号指向的**不是同一个逻辑类**。
     * 于是把对方的方法拼进原版类之后，它 `new Foo$14` 拿到的是**原版那个 $14**，
     * 构造器签名对不上，运行期直接抛：
     *
     * <pre>
     * NoSuchMethodError: 'void net.minecraft.network.codec.ByteBufCodecs$14.&lt;init&gt;(int)'
     *   at net.minecraft.network.codec.ByteBufCodecs.stringUtf8
     * </pre>
     *
     * <p>（实测来源：放开合并范围到整个 {@code net/minecraft/} 之后立刻撞上。）
     *
     * <p>参照项目 Forbric 在它的合并器里对这种情况有专门的处理，
     * 注释写得很清楚：{@code "cannot be spliced. Drop it (keep base's own method if any), record."}
     * 我们也采取同样的立场：**宁可少拼一个方法，也不要拼出一个运行期必炸的类。**
     *
     * <p>判据：被拼的方法的常量池里出现 {@code <本类名>$数字} 形式的类型，
     * 且这个类型在两份编译产物里的**方法签名集合不同**（说明它不是同一个逻辑类）。
     */
    private static boolean newsDivergentAnonymous(ClassFile vanilla, ClassFile patched,
                                                  ClassFile.Member method, String internal) {
        try {
            String prefix = internal + "$";
            // 只看**这个被拼的方法自己**引用的匿名兄弟 —— 而不是整个类的常量池。

            java.util.Set<String> refs = anonymousRefsOfMethod(patched, method, prefix);
            if (refs.isEmpty()) {
                return false;
            }
            java.util.Set<String> vanillaMethods = vMethodKeys(VANILLA_JAR_CACHE, internal);
            for (String anon : refs) {
                byte[] vBytes = readEntryBytes(VANILLA_JAR_CACHE, anon + ".class", true);
                byte[] pBytes = readEntryBytes(CURRENT_PATCHED_JAR, anon + ".class", false);
                if (vBytes == null || pBytes == null) {
                    // 一方没有这个编号的类：编号含义确实不同，不能拼。
                    return true;
                }
                if (!memberSignatureKey(ClassFile.read(vBytes))
                        .equals(memberSignatureKey(ClassFile.read(pBytes)))) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            // 判不了就当作"不能拼"：保守，宁可少拼。
            return true;
        }
    }

    /**
     * 一个方法的字节码里 `new`/`checkcast`/`anewarray` 到的"本类匿名兄弟"。
     *
     * <p>只看**这个方法自己**引用了谁 —— 而不是整个类的常量池。
     * 一开始扫的是类的常量池，于是"类里任何一个方法引用了 $N"都会让它拒绝拼接，
     * 既误伤太多、又没抓住真正要看的东西。
     */
    private static java.util.Set<String> anonymousRefsOfMethod(ClassFile cf, ClassFile.Member method,
                                                               String prefix) {
        java.util.Set<String> out = new java.util.HashSet<>(4);
        byte[] data = cf.rawBytes();
        for (ClassFile.Attr a : method.attrs) {
            if (!"Code".equals(cf.utf8(a.nameIndex()))) {
                continue;
            }
            int start = a.offset();
            int codeLen = ClassFile.u4iPublic(data, start + 4);
            int p = start + 8;
            int end = p + codeLen;
            while (p < end) {
                int op = data[p] & 0xFF;

                if (op == 0xBB || op == 0xBD || op == 0xC0 || op == 0xC1 || op == 0xC5) {
                    int idx = ((data[p + 1] & 0xFF) << 8) | (data[p + 2] & 0xFF);
                    ClassFile.Cp c = cf.cp(idx);
                    if (c != null && c.tag == 7) {
                        String n = cf.utf8(c.a);
                        if (n != null && n.startsWith(prefix)) {
                            out.add(n);
                        }
                    }
                }
                p += instructionLength(data, p, op, end);
            }
        }
        return out;
    }

    private static int instructionLength(byte[] b, int p, int op, int end) {
        switch (op) {
            case 0x12: return 2;
            case 0x13: case 0xB2: case 0xB3: case 0xB4: case 0xB5:
            case 0xB6: case 0xB7: case 0xB8: case 0xBB: case 0xBD:
            case 0xC0: case 0xC1: case 0x11: case 0x14: case 0x84: case 0xA9:
                return 3;
            case 0xB9: case 0xBA: case 0xC8: case 0xC9: return 5;
            case 0xC5: return 4;
            case 0xC4: return (p + 1 < end && (b[p + 1] & 0xFF) == 0x84) ? 6 : 4;
            case 0xAA: {
                int q = (p + 4) & ~3;
                int lo = i4b(b, q + 4);
                int hi = i4b(b, q + 8);
                long size = (long) hi - lo + 1;
                return (size < 0 || q + 12 + 4L * size > end) ? 1 : (int) (q + 12 + 4L * size - p);
            }
            case 0xAB: {
                int q = (p + 4) & ~3;
                int n = i4b(b, q + 4);
                return (n < 0 || q + 8 + 8L * n > end) ? 1 : (int) (q + 8 + 8L * n - p);
            }
            case 0x10: case 0x15: case 0x16: case 0x17: case 0x18: case 0x19:
            case 0x36: case 0x37: case 0x38: case 0x39: case 0x3A: case 0xBC:
            case 0xC6: case 0xC7:
                return 2;
            default:
                if (op >= 0x99 && op <= 0xA8) {
                    return 3;                   
                }
                return 1;
        }
    }

    private static int i4b(byte[] b, int p) {
        return ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16)
                | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
    }

    /** 取原版类的方法签名集合（用于判断同一编号是不是同一个逻辑类）。 */
    private static java.util.Set<String> vMethodKeys(Path jar, String internal) {
        java.util.Set<String> out = new java.util.HashSet<>();
        byte[] b = readEntryBytes(jar, internal + ".class", true);
        if (b == null) {
            return out;
        }
        ClassFile cf = ClassFile.read(b);
        for (ClassFile.Member m : cf.methods()) {
            String n = cf.utf8(m.nameIndex);
            String d = cf.utf8(m.descIndex);
            if (n != null && d != null) {
                out.add(n + d);
            }
        }
        return out;
    }

    /** 一个类的"方法签名集合"，用来判断两份编译产物里同名内部类是不是同一个逻辑类。 */
    private static String memberSignatureKey(ClassFile cf) {
        java.util.TreeSet<String> keys = new java.util.TreeSet<>();
        for (ClassFile.Member m : cf.methods()) {
            String n = cf.utf8(m.nameIndex);
            String d = cf.utf8(m.descIndex);
            if (n != null && d != null) {
                keys.add(n + d);
            }
        }
        return String.join("|", keys);
    }

    private static byte[] readEntryBytes(Path jar, String entry, boolean vanillaFirst) {
        Path target = vanillaFirst ? VANILLA_JAR_CACHE : jar;
        if (target == null) {
            return null;
        }
        try (FastZip z = FastZip.open(target)) {
            FastZip.Entry e = z.entry(entry);
            return e == null ? null : z.read(e);
        } catch (Throwable t) {
            return null;
        }
    }

    private static final java.util.Set<String> TRACE_CLASSES =
            new java.util.LinkedHashSet<>(java.util.List.of(
                    "net/minecraft/core/MappedRegistry",
                    "net/minecraft/core/Registry",
                    "net/minecraft/world/entity/LivingEntity",
                    "net/minecraft/world/entity/Entity",
                    "net/minecraft/core/Holder",
                    "net/minecraft/core/component/DataComponentMap$Builder",
                    "net/minecraft/commands/synchronization/ArgumentTypeInfos",
                    "net/minecraft/world/level/Level"));

    /**
     * "为了保住宿主模组的注入点，而保留了原版字节"的类（拼接做不到的那些）。
     *
     * <p>这些类的**对方平台能力是缺失的**，必须如实报出来：
     * 依赖它们新增成员的跨端模组会失败，而失败原因在别处看不出来。
     */
    private static final java.util.List<String> MIXIN_INJECTION_AT_RISK =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    public static java.util.List<String> mixinInjectionProtected() {
        return new ArrayList<>(MIXIN_INJECTION_AT_RISK);
    }

    private static String vanillaSuperOf(String internal) {
        try {
            Path v = VANILLA_JAR_CACHE;
            if (v == null) {
                return "java/lang/Object";
            }
            try (FastZip z = FastZip.open(v)) {
                FastZip.Entry e = z.entry(internal + ".class");
                if (e == null) {
                    return "java/lang/Object";
                }
                String s = ClassFile.read(z.read(e)).superName();
                return s == null ? "java/lang/Object" : s;
            }
        } catch (Throwable t) {
            return "java/lang/Object";
        }
    }

    private static ClassFile pcfFor(byte[] patchedBytes) {
        try {
            return ClassFile.read(patchedBytes);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 对方平台有没有**按新超类使用**这个类 —— 即：对方的字节里，有没有谁
     * 把这个类的实例当作新超类（或其子类能提供的类型）来用。
     *
     * <h2>为什么这是"超类变了要不要换"的决定性判据</h2>
     *
     * <p>超类变化时两个选项都可能致命，实测各撞过一次：
     *
     * <ul>
     *   <li><b>不换</b> → 如果对方按新超类使用它，就崩在
     *       {@code VerifyError: 'MappedRegistry' is not assignable to 'BaseMappedRegistry'}。
     *       实测来源：{@code RegistryBuilder.create()}，它把 {@code MappedRegistry}
     *       当 {@code BaseMappedRegistry} 用（对方自己的 API 契约）。</li>
     *   <li><b>换</b> → 如果宿主模组的 Mixin 打在它上面，注入点会匹配 0 个目标。
     *       实测来源：{@code c2me} 的 {@code MixinWorld} 打在 {@code Level} 上，
     *       报 {@code Scanned 0 target(s)}，进而把 {@code SharedConstants.<clinit>} 拖崩。</li>
     * </ul>
     *
     * <p>所以要问的是：**对方到底依不依赖那个新超类**。
     * 判据是"对方的字节里有没有对这个类调用新超类才有的方法" —— 有则是硬契约，
     * 没有则说明对方只是借这个类挂了个接口（那种情况下接口本身就够了）。
     */
    /**
     * 对方平台有没有**按新超类使用**这个类。
     *
     * <h2>判据是什么、为什么是这样</h2>
     *
     * <p>超类变化时两个选项都可能致命（实测各撞一次）：
     * 不换 → 对方按新超类用它就 {@code VerifyError}；换 → 宿主 Mixin 注入点失配。
     * 所以必须知道"对方到底依不依赖那个新超类"。
     *
     * <p>试过两种信号，都**区分不了**：
     * <ul>
     *   <li>"谁调用本类上的新超类方法" —— 字节码里 owner 写的是**父类**
     *       （实测 {@code RegistryBuilder} 里是 {@code BaseMappedRegistry.setMaxId}，不是
     *       {@code MappedRegistry.setMaxId}），所以按本类名找永远找不到；</li>
     *   <li>"本类的 {@code <init>} 是否调新超类构造器" —— **两边都有**
     *       （{@code Level} 调 {@code AttachmentHolder.<init>}，
     *       {@code MappedRegistry} 调 {@code BaseMappedRegistry.<init>}），
     *       因为构造器必然要调父类，这是语法强制的，不构成差异。</li>
     * </ul>
     *
     * <p>真正区分它们的是：**这个类自己的方法体里，有没有调用"只存在于新超类"的方法**。
     * <ul>
     *   <li>{@code MappedRegistry} 的方法体里调 {@code setMaxId}/{@code setSync}（新超类独有）
     *       → 它的行为与新超类绑死，必须用对方字节；</li>
     *   <li>{@code Level} 的方法体只调自己的和原版的方法（attachment 能力是通过
     *       {@code ILevelExtension} 接口提供的）→ 保留原版不影响它自己。</li>
     * </ul>
     */
    private static boolean foreignUsesAsNewSuperclass(String internal, byte[] patchedBytes,
                                                      ClassFile patched) {
        try {
            if (patched == null) {
                return true;                  
            }
            String newSuper = patched.superName();
            if (newSuper == null || "java/lang/Object".equals(newSuper)) {
                return false;
            }

            java.util.Set<String> superMethods = new java.util.HashSet<>();
            ClassFile cur = readFromJars(newSuper + ".class");
            int depth = 0;
            while (cur != null && depth++ < 6) {
                for (ClassFile.Member m : cur.methods()) {
                    String n = cur.utf8(m.nameIndex);
                    if (n != null && !n.startsWith("<")) {
                        superMethods.add(n);
                    }
                }
                String next = cur.superName();
                cur = next == null || "java/lang/Object".equals(next)
                        ? null : readFromJars(next + ".class");
            }
            if (superMethods.isEmpty()) {
                return true;                  
            }

            String vs = vanillaSuperOf(internal);
            ClassFile vCur = "java/lang/Object".equals(vs) ? null : readFromJars(vs + ".class");
            int vDepth = 0;
            while (vCur != null && vDepth++ < 6) {
                for (ClassFile.Member m : vCur.methods()) {
                    String n = vCur.utf8(m.nameIndex);
                    if (n != null) {
                        superMethods.remove(n);
                    }
                }
                String next = vCur.superName();
                vCur = next == null || "java/lang/Object".equals(next)
                        ? null : readFromJars(next + ".class");
            }
            if (superMethods.isEmpty()) {
                return false;                 
            }

            boolean bySuper = patched.referencesAnyMethodOfDeep(
                    java.util.Set.of(newSuper), superMethods);
            boolean byName = patched.referencesAnyMethodName(superMethods);
            if (TRACE_CLASSES.contains(internal)) {
                TRACE_DETAIL.put(internal, "独有方法 " + superMethods.size() + " 个"
                        + "，本类按 owner 命中=" + bySuper + "，按名字命中=" + byName
                        + "，命中名=" + firstHits(patched, superMethods));
            }
            return bySuper || byName;
        } catch (Throwable t) {
            return true;                      
        }
    }

    /**
     * 对方对这个类的依赖，是否**必须**跟对方的新超类类型（不跟就必然校验失败）。
     *
     * <h2>为什么要再分一层</h2>
     *
     * <p>{@link #foreignUsesAsNewSuperclass} 回答的是"对方有没有用到新超类的能力"，
     * 但"用到"有两种，后果完全不同：
     *
     * <ul>
     *   <li><b>对方把本类的实例当新超类来实现自己的逻辑</b> —— 例如
     *       {@code RegistryBuilder.create()} 里，{@code new MappedRegistry} 之后
     *       直接 {@code invokespecial BaseMappedRegistry.setMaxId}。
     *       我们在原版 {@code MappedRegistry} 上补不出那个超类，所以**必须是对方字节**，
     *       否则 {@code VerifyError: 'MappedRegistry' is not assignable to 'BaseMappedRegistry'}。
     *       特征是：对方**新建**这个类，并对它调用新超类方法。</li>
     *   <li><b>对方只是拿到一个实例去 use 它的新能力</b> —— 例如对 {@code Entity}
     *       调用 {@code setData}。这时保留原版只会让那个方法**找不到**
     *       （{@code NoSuchMethodError}，是运行期、可诊断的失败），
     *       而不是启动期校验崩。宿主模组的 Mixin 反而必须靠原版字节才能注入。</li>
     * </ul>
     *
     * <p>判据：**对方有没有 `new` 这个类**（在对方自己的字节里找 {@code new <本类>}）。
     * 有 → 它构造的实例直接当新超类用，必须跟对方类型；
     * 没有 → 它只是拿到别处传进来的实例，保留原版即可（宿主 Mixin 优先）。
     *
     * <p>实测两边各一例：{@code MappedRegistry} 是前者（{@code RegistryBuilder} 里
     * 有 {@code new MappedRegistry}），{@code Entity}/{@code Level} 是后者
     * （对方不 `new` 它们，而是从游戏流程里拿到）。
     */
    private static boolean foreignNeedsStrictSuperclassType(String internal, byte[] patchedBytes) {
        try {
            java.util.List<Path> sources = new ArrayList<>(4);
            Path pj = patchedJarOf();
            if (pj != null) {
                sources.add(pj);
            }
            sources.addAll(runtimeJars());
            for (Path src : sources) {
                try (FastZip z = FastZip.open(src)) {
                    int scanned = 0;
                    for (FastZip.Entry e : z.entries()) {
                        if (!e.isClass() || e.isDirectory() || ++scanned > 8000) {
                            continue;
                        }
                        try {
                            ClassFile cf = ClassFile.read(z.read(e));
                            if (cf.newsClass(internal)) {
                                return true;
                            }
                        } catch (Throwable ignored) {

                        }
                    }
                } catch (Throwable ignored) {

                }
            }
            return false;
        } catch (Throwable t) {
            // 出错时保守：按"必须跟对方"处理（宁可整类换，也不要拼出校验不过的类）
            return true;
        }
    }

    private static final java.util.Map<String, String> HOOK_TRACE =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static java.util.Map<String, String> hookTrace() {
        return new java.util.HashMap<>(HOOK_TRACE);
    }

    private static final java.util.Map<String, String> TRACE_DETAIL =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static java.util.Map<String, String> traceDetail() {
        return new java.util.HashMap<>(TRACE_DETAIL);
    }

    private static String firstHits(ClassFile patched, java.util.Set<String> names) {
        java.util.Set<String> hits = new java.util.TreeSet<>();
        for (int i = 1; i < 65536; i++) {
            ClassFile.Cp c = patched.cp(i);
            if (c == null) {
                if (i > 64) {
                    break;
                }
                continue;
            }
            if (c.tag != 10 && c.tag != 11) {
                continue;
            }
            ClassFile.Cp nt = patched.cp(c.b);
            ClassFile.Cp cls = patched.cp(c.a);
            if (nt == null || cls == null) {
                continue;
            }
            String n = patched.utf8(nt.a);
            if (n != null && names.contains(n)) {
                hits.add(n + "@" + patched.utf8(cls.a));
            }
        }
        return hits.isEmpty() ? "（无）" : String.join("、", hits);
    }

    /**
     * 把"对方是否按新超类使用这个类"的判据过程打出来（诊断用）。
     *
     * <p>只看一个是/否没法判断它是不是误命中 —— 实测 {@code Entity} 与 {@code Level}
     * 的补丁超类**完全一样**（都是 {@code AttachmentHolder}），判据却给出相反答案，
     * 那就必须把"新超类独有方法"与"本类实际调用了哪些"摆出来看。
     */
    public static java.util.List<String> explainSuperclassUsage(String internal,
                                                                byte[] patchedBytes,
                                                                ClassFile patched) {
        java.util.List<String> out = new ArrayList<>(8);
        try {
            String newSuper = patched.superName();
            out.add("新超类=" + newSuper);
            java.util.Set<String> superMethods = new java.util.HashSet<>();
            ClassFile cur = readFromJars(newSuper + ".class");
            while (cur != null) {
                for (ClassFile.Member m : cur.methods()) {
                    String n = cur.utf8(m.nameIndex);
                    if (n != null && !n.startsWith("<")) {
                        superMethods.add(n);
                    }
                }
                String next = cur.superName();
                cur = next == null || "java/lang/Object".equals(next)
                        ? null : readFromJars(next + ".class");
            }
            out.add("新超类链上的方法名 " + superMethods.size() + " 个："
                    + String.join("、", new java.util.TreeSet<>(superMethods)));
            String vs = vanillaSuperOf(internal);
            ClassFile vCur = "java/lang/Object".equals(vs) ? null : readFromJars(vs + ".class");
            while (vCur != null) {
                for (ClassFile.Member m : vCur.methods()) {
                    String n = vCur.utf8(m.nameIndex);
                    if (n != null) {
                        superMethods.remove(n);
                    }
                }
                String next = vCur.superName();
                vCur = next == null || "java/lang/Object".equals(next)
                        ? null : readFromJars(next + ".class");
            }
            out.add("去掉原版祖先同名后，新超类独有方法 " + superMethods.size() + " 个："
                    + String.join("、", new java.util.TreeSet<>(superMethods)));

            java.util.Set<String> hit = new java.util.TreeSet<>();
            for (int i = 1; i < 65536; i++) {
                ClassFile.Cp c = patched.cp(i);
                if (c == null) {
                    if (i > 64) {
                        break;
                    }
                    continue;
                }
                if (c.tag != 10 && c.tag != 11) {
                    continue;
                }
                ClassFile.Cp nt = patched.cp(c.b);
                if (nt == null) {
                    continue;
                }
                String n = patched.utf8(nt.a);
                if (n != null && superMethods.contains(n)) {
                    hit.add(n);
                }
            }
            out.add("本类自己调用了其中 " + hit.size() + " 个："
                    + (hit.isEmpty() ? "（无 → 判据给出 false）" : String.join("、", hit)));
        } catch (Throwable t) {
            out.add("诊断失败：" + t);
        }
        return out;
    }

    /**
     * 仅供离线验证：把超类桥接暴露出去（见 {@code BridgeProbe}）。
     */
    public static byte[] bridgeSuperclassForTest(byte[] vanillaBytes, byte[] patchedBytes,
                                                 String internal, java.util.List<String> notes) {
        return bridgeSuperclass(vanillaBytes, patchedBytes, internal, notes);
    }

    /** 仅供离线诊断：设置"当前补丁包"，好让判据能扫对方字节。 */
    public static void setPatchedJarForTest(Path jar) {
        CURRENT_PATCHED_JAR = jar;
        PATCHED_JAR_CACHE = jar;
    }

    /** 仅供离线诊断：设置"原版包"，判据要拿它算"新超类独有的方法"。 */
    public static void setVanillaJarForTest(Path jar) {
        VANILLA_JAR_CACHE = jar;
    }

    private static volatile Path CURRENT_VANILLA_JAR;

    static void useVanillaJar(Path jar) {
        CURRENT_VANILLA_JAR = jar;
        VANILLA_JAR_CACHE = jar;
    }

    /**
     * 仅供离线诊断：把"对方是否按新超类使用该类"的判据暴露出去（见 {@code WhyReplaced}）。
     */
    public static boolean foreignUsesAsNewSuperclassForTest(String internal, byte[] patchedBytes,
                                                            ClassFile patched) {
        return foreignUsesAsNewSuperclass(internal, patchedBytes, patched);
    }

    private static Path patchedJarOf() {
        return CURRENT_PATCHED_JAR;
    }

    /**
     * 超类桥接：把对方**超类**的能力**组合进**原版类，而不是让它继承。
     *
     * <h2>为什么必须这么做</h2>
     *
     * <p>对方平台有些补丁是靠**换超类**实现的，而宿主模组的 Mixin 又打在同一批类上。
     * 两边都要，Java 却没有多继承 —— 只在这两个坏选项里选一个，都必然崩：
     *
     * <ul>
     *   <li>换对方字节 → 宿主注入点匹配 0 个目标。实测：c2me 的 {@code MixinWorld}
     *       打在 {@code Level} 上，报 {@code Scanned 0 target(s)}，
     *       进而把 {@code SharedConstants.<clinit>} 拖崩、进程直接退出。</li>
     *   <li>保留原版 → 对方按新超类使用它，校验期失败。实测：{@code RegistryBuilder.create()}
     *       把 {@code MappedRegistry} 当 {@code BaseMappedRegistry} 用，报
     *       {@code VerifyError: ... is not assignable to ...}。</li>
     * </ul>
     *
     * <p>第三条路就是桥接：**让原版类自己长出对方超类的能力**。
     * 具体到 {@code Level} 这个实例，需要做的非常少 —— 实测对方超类
     * {@code AttachmentHolder} 只带进来 **1 个实例字段**（{@code attachments}）
     * 和 **13 个方法**，而它的构造器只多做了"把 {@code attachments} 置 null"：
     *
     * <pre>
     * AttachmentHolder.&lt;init&gt;()V:
     *   0: aload_0
     *   1: invokespecial Object.&lt;init&gt;()V      ← 与原版 Level 的首条指令完全一样
     *   4: aload_0
     *   5: aconst_null
     *   6: putfield attachments : Map          ← 只需把这 3 条补进原版 Level 的 &lt;init&gt;
     * </pre>
     *
     * <p>于是"继承"被换成了"组合"，两边都成立：
     * 原版 {@code Level} 的方法体一个字节没动（宿主模组的注入点还在），
     * 而 {@code getData()}/{@code setData()} 这些方法真的存在于 {@code Level} 上
     * （对方模组调得到）。
     *
     * <h2>适用条件（不满足就放弃桥接）</h2>
     *
     * <ol>
     *   <li>对方超类自己直接继承 {@code java/lang/Object}（否则它也会带进更复杂的链）；</li>
     *   <li>对方超类<b>没有实例字段初始化依赖其他超类构造逻辑</b>（本例只置 null，最简单）；</li>
     *   <li>子类对超类构造器的调用只有一处（本例确认只有 1 处）；</li>
     *   <li>子类对超类方法/字段的引用都能被搬进来的成员满足。</li>
     * </ol>
     *
     * @return 桥接后的类字节；不适用返回 {@code null}（调用方退回原判据）
     */
    private static byte[] bridgeSuperclass(byte[] vanillaBytes, byte[] patchedBytes,
                                           String internal, java.util.List<String> notes) {
        try {
            ClassFile v = ClassFile.read(vanillaBytes);
            ClassFile p = ClassFile.read(patchedBytes);
            String newSuper = p.superName();
            if (newSuper == null || "java/lang/Object".equals(newSuper)) {
                return null;
            }
            // 对方超类住在 NeoForge 的运行时包里，不在打补丁的原版包里 ——
            // 所以要用"会去搜运行时包"的 readFromJars，而不是只查补丁包的 readEntryBytes。
            ClassFile sup = readFromJars(newSuper + ".class");
            if (sup == null) {
                notes.add(internal + "：读不到对方超类 " + newSuper);
                return null;
            }

            if (sup.superName() == null || !"java/lang/Object".equals(sup.superName())) {
                notes.add(internal + "：对方超类 " + newSuper + " 不是直接继承 Object，暂不桥接");
                return null;
            }
            // 条件 2：超类构造器必须是"只置字段为 null/常量"这种可复制的简单形态
            String ctorPlan = simpleDefaultCtorPlan(sup, newSuper);
            if (ctorPlan == null) {
                notes.add(internal + "：对方超类构造器不是可复制的简单形态，暂不桥接");
                return null;
            }

            int movedFields = 0;
            java.util.Set<String> existingFields = new java.util.HashSet<>();
            for (ClassFile.Member f : v.fields()) {
                existingFields.add(v.utf8(f.nameIndex) + " " + v.utf8(f.descIndex));
            }
            for (ClassFile.Member f : sup.fields()) {
                String key = sup.utf8(f.nameIndex) + " " + sup.utf8(f.descIndex);
                if (existingFields.contains(key)) {
                    continue;                     
                }

                // 而 <clinit> 我们并不桥接（它的语义和"这个类自己的初始化顺序"绑在一起）。
                // 硬搬一个 static final 会连 ConstantValue 属性一起带过来 —— 实测报

                // 那些方法会通过 CP 直接引用 NeoForge 的静态字段，不需要本类再有一份）。
                if ((f.access & 0x0008) != 0) {
                    continue;
                }
                byte[] raw = importMemberRaw(sup, v, f);
                if (raw == null) {
                    notes.add(internal + "：超类字段 " + sup.utf8(f.nameIndex)
                            + " 搬不进来（原因：" + ClassFile.lastBridgeImportReason() + "）");
                    return null;
                }
                v.addFieldRaw(raw);
                existingFields.add(key);
                movedFields++;
            }

            int movedMethods = 0;
            java.util.Set<String> existingMethods = new java.util.HashSet<>();
            for (ClassFile.Member m : v.methods()) {
                existingMethods.add(v.utf8(m.nameIndex) + v.utf8(m.descIndex));
            }
            for (ClassFile.Member m : sup.methods()) {
                String name = sup.utf8(m.nameIndex);
                String desc = sup.utf8(m.descIndex);
                if ("<init>".equals(name)) {
                    continue;
                }
                String key = name + desc;
                if (existingMethods.contains(key)) {
                    continue;                     
                }
                byte[] raw = importMemberRaw(sup, v, m);
                if (raw == null) {
                    notes.add(internal + "：超类方法 " + name + " 搬不进来（原因："
                            + ClassFile.lastBridgeImportReason() + "）");
                    return null;
                }
                v.addMethodRaw(raw);
                existingMethods.add(key);
                movedMethods++;
            }

            for (String itf : p.interfaces()) {
                v.addInterface(itf);
            }

            if (!rewriteSuperCtorCalls(v, internal, newSuper, ctorPlan)) {
                notes.add(internal + "：<init> 里的超类构造器调用改不动，暂不桥接");
                return null;
            }
            notes.add(internal + "：超类桥接成功（超类 " + newSuper + "，搬入 "
                    + movedFields + " 个字段、" + movedMethods + " 个方法、"
                    + p.interfaces().length + " 个接口）");
            return v.toBytes();
        } catch (Throwable t) {
            notes.add(internal + "：超类桥接异常 " + t);
            return null;
        }
    }

    private static byte[] importMemberRaw(ClassFile source, ClassFile target, ClassFile.Member m) {

        if (!source.preallocateLdcConstants(target, m)) {
            return null;
        }
        return source.importMemberForBridge(target, m);
    }

    /**
     * 判断对方超类的构造器是不是"可以照抄的简单形态"，并给出要补的字段初始化计划。
     *
     * <p>只接受这种形态：{@code super(); this.f = <null|0|常量>; return;}。
     * 更复杂的构造器（有循环、有别的调用、依赖参数）一律拒绝 ——
     * 因为桥接时我们要把它"平铺"进子类的 {@code <init>}，抄错了就是运行期字段为 null。
     *
     * @return 形如 {@code "attachments:Ljava/util/Map;"} 的字段列表；不适用返回 {@code null}
     */
    private static String simpleDefaultCtorPlan(ClassFile sup, String superName) {
        ClassFile.Member ctor = null;
        for (ClassFile.Member m : sup.methods()) {
            if ("<init>".equals(sup.utf8(m.nameIndex)) && "()V".equals(sup.utf8(m.descIndex))) {
                ctor = m;
                break;
            }
        }
        if (ctor == null) {
            return null;                          
        }

        StringBuilder sb = new StringBuilder();
        for (ClassFile.Member f : sup.fields()) {
            if ((f.access & 0x0008) != 0) {
                continue;                         
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(sup.utf8(f.nameIndex)).append(':').append(sup.utf8(f.descIndex));
        }
        return sb.toString();
    }

    private static boolean rewriteSuperCtorCalls(ClassFile v, String internal, String newSuper,
                                                 String ctorPlan) {
        return SuperclassBridge.rewrite(v, internal, newSuper, ctorPlan);
    }

    /**
     * 结构性冲突（超类被对方换过、但该类又是宿主模组的 Mixin 目标）。
     *
     * <p>Java 没有多继承，这类冲突**在字节码层无解**：两边的超类只能留一个。
     * 我们选择保留原版（宿主模组优先），并如实把类名记下来 ——
     * 因为"假装解决了"比"明确记录牺牲了什么"危害大得多。
     */
    private static final java.util.List<String> STRUCTURAL_CONFLICTS =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    public static java.util.List<String> structuralConflicts() {
        return new ArrayList<>(STRUCTURAL_CONFLICTS);
    }

    private static final java.util.Set<String> KEPT_VANILLA =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 如果 {@code internal} 是匿名内部类（{@code 宿主$数字}），返回宿主类的内部名；否则返回 {@code null}。
     */
    private static String anonymousOuterOf(String internal) {
        int dollar = internal.lastIndexOf('$');
        if (dollar <= 0 || dollar == internal.length() - 1) {
            return null;
        }
        String tail = internal.substring(dollar + 1);
        for (int i = 0; i < tail.length(); i++) {
            if (!Character.isDigit(tail.charAt(i))) {
                return null;               // 不是纯数字：是具名内部类，可以独立处理
            }
        }
        return internal.substring(0, dollar);
    }

    /** 想拼但因为字节码扫描能力不足而放弃的方法（诊断用）。 */
    private static final java.util.List<String> SPLICE_REFUSED =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    private static final java.util.Map<String, Integer> SPLICE_ATTEMPTED =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static java.util.List<String> spliceRefusals() {
        return new ArrayList<>(SPLICE_REFUSED);
    }

    public static java.util.Map<String, Integer> spliceAttempts() {
        return new java.util.HashMap<>(SPLICE_ATTEMPTED);
    }

    /**
     * 拼接产物的自检：重新解析它，并检查所有常量池引用的**类型**是否用对。
     *
     * <p>为什么必须在合并期做这一步：拼接产物的错误（索引越界、类型错用）
     * 只会在游戏启动、JVM 校验那个类时才暴露，报的是
     * {@code VerifyError: Illegal type at constant pool entry N} —— 离合并期很远，
     * 定位一次要跑一整轮 150 秒的真机。
     *
     * <p>把检查前移到合并期，就能"当场发现问题、当场退回保守方案"，
     * 而不是把一个坏类交给游戏再崩。
     *
     * @return 问题描述；没问题返回 {@code null}
     */
    private static String selfCheck(byte[] classBytes) {
        try {
            ClassFile cf = ClassFile.read(classBytes);

            // JVM 的 defineClass 也过，但宿主平台的类加载流水线用的是 ASM，
            // 而 ASM 对**属性结构**更严格。实测崩在

            // 原因是"丢弃属性时没把属性计数减掉"：属性头写了 0 长度、计数却还是原值，

            // 所以必须借 ASM 当第二双眼睛（它本来就在类路径上，宿主平台自己要用）。
            String asmWhy = asmParseCheck(classBytes);
            if (asmWhy != null) {
                return "ASM 解析失败：" + asmWhy;
            }
            // 用**已验证过的那个扫描器**来走字节码，而不是在这里再写一份。

            // 把好端端的拼接**误判成失败**并退回整类换 —— 比没有自检更糟。

            // 而拼接产物里原版常量池是原样搬过去的 —— 索引本来就该在那里，不需要映射。

            // 实测 ArgumentTypeInfos 就是被这个假错挡住的：它明明能拼、也被拼出来了，
            // 却被自检否掉退回纯原版 → 对方 NeoForgeMod 调 registerByClass 时

            java.util.Set<String> vanillaSigs = new java.util.HashSet<>();
            ClassFile vf = readVanillaClass(ClassFile.read(classBytes).thisName());
            if (vf != null) {
                for (ClassFile.Member m : vf.methods()) {
                    vanillaSigs.add(vf.utf8(m.nameIndex) + vf.utf8(m.descIndex));
                }
            }
            for (ClassFile.Member m : cf.methods()) {
                String sig = cf.utf8(m.nameIndex) + cf.utf8(m.descIndex);
                if (!vanillaSigs.isEmpty() && vanillaSigs.contains(sig)) {
                    continue;                     
                }
                for (ClassFile.Attr a : m.attrs) {
                    if (!"Code".equals(cf.utf8(a.nameIndex()))) {
                        continue;
                    }
                    String why = com.fusionml.bytecode.ConstantPoolTransplant
                            .verifyCodeReferences(cf, classBytes, a.offset(),
                                    ClassFile.u4iPublic(classBytes, a.offset() + 4));
                    if (why != null) {
                        return cf.utf8(m.nameIndex) + "：" + why;
                    }
                }
            }
            return null;
        } catch (Throwable t) {
            return "解析失败：" + t;
        }
    }

    /**
     * 用 ASM 解析一遍字节（借宿主平台的 ASM 当"第二双眼睛"）。
     *
     * <p>为什么需要它：我们自己的字节码工具是按"长度跳过属性"的，所以
     * "属性计数与内容不一致"这类结构错误它看不出来；JVM 的 {@code defineClass}
     * 也偏宽松。但宿主平台的类加载流水线（Fabric 的 {@code GameTransformer}）
     * 用 ASM 读这些类，ASM 对属性结构严格得多 —— 它崩了，游戏就在启动期起不来，
     * 而且报错位置（{@code readTypeAnnotationTarget}）离我们的改动很远。
     *
     * <p>用反射而不是直接 import：编译期不一定有 ASM（我们的加载器要能在没有游戏的
     * 环境里独立构建），而运行期它一定在（宿主平台自己依赖它）。拿不到就跳过检查。
     *
     * @return 失败原因；读不了或没问题返回 {@code null}
     */
    private static String asmParseCheck(byte[] classBytes) {
        try {
            Class<?> reader = Class.forName("org.objectweb.asm.ClassReader");
            Object cr = reader.getConstructor(byte[].class).newInstance((Object) classBytes);
            Class<?> visitor = Class.forName("org.objectweb.asm.ClassVisitor");
            java.lang.reflect.Method accept = reader.getMethod("accept", visitor, int.class);

            accept.invoke(cr, null, 0);
            return null;
        } catch (ClassNotFoundException e) {
            return null;                      
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return cause.getClass().getSimpleName()
                    + (cause.getMessage() == null ? "" : "：" + cause.getMessage());
        } catch (Throwable t) {
            return null;                      // 反射失败不当作"类坏了"
        }
    }

    private static int u2(byte[] b, int p) {
        return ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
    }

    private static int i4(byte[] b, int p) {
        return ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16)
                | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
    }

    /**
     * 对方补丁是不是把超类换掉了。
     *
     * <p>换超类的类**不能拼接**：拼接只能往原版字节里加方法，改不了 `super_class` 字段，
     * 而对方那些按新超类编译的字节（例如 {@code RegistryBuilder.create()}）会在校验期对不上类型。
     *
     * <p>实测：{@code MappedRegistry} 的超类从 {@code java/lang/Object} 换成
     * {@code BaseMappedRegistry}，若只拼接不换整类，就崩在
     * {@code VerifyError: Bad type on operand stack ... not assignable to BaseMappedRegistry}。
     */
    private static boolean superclassChanged(byte[] vanillaBytes, byte[] patchedBytes) {
        try {
            return !java.util.Objects.equals(
                    ClassFile.read(vanillaBytes).superName(),
                    ClassFile.read(patchedBytes).superName());
        } catch (Throwable t) {

            return true;
        }
    }

    /**
     * 对方平台**是否真的用到了**这个类新增的那些接口。
     *
     * <h2>为什么需要这个判据</h2>
     *
     * <p>"新增接口"有两种性质完全相反的形态，而它们的字节形状一模一样：
     *
     * <ul>
     *   <li>{@code Holder} 多了 {@code IHolderExtension}（纯 default）：
     *       对方版本的 {@code Registry} 会调 {@code Holder.getKey()} —— 那是该接口提供的
     *       default 方法。不给对方字节就 {@code NoSuchMethodError}。
     *       <b>这是活契约，必须换。</b></li>
     *   <li>{@code DataComponentMap$Builder} 多了 {@code IDataComponentMapBuilderExtensions}
     *       （也是纯 default）：对方平台并没有谁去调它，纯粹是顺手加的便利接口。
     *       而把它带进原版类，宿主平台的 {@code @Overwrite} Mixin 就会崩。
     *       <b>这不是活契约，留着会坏事。</b></li>
     * </ul>
     *
     * <p>判据：接口里那些**新增的方法名**，有没有在对方平台自己的字节里被引用。
     * 有引用 → 活契约；没有 → 便利接口。
     */
    /**
     * 对方的**内部类**（{@code 本类$xxx}）有没有引用这些方法名。
     *
     * <p>这是 {@code Holder} 那次崩溃的真正成因：调用者不在本类的常量池里，
     * 而在对方的 {@code Holder$Reference} 里。所以必须去补丁包里按前缀找。
     */
    private static boolean foreignInnerClassesUse(String self, java.util.Set<String> wantedNames) {
        if (self == null) {
            return true;
        }
        try {
            String prefix = self + "$";
            Path pj = patchedJarOf();
            if (pj == null) {
                return true;
            }
            try (FastZip z = FastZip.open(pj)) {
                for (FastZip.Entry e : z.entries()) {
                    if (!e.isClass() || e.isDirectory() || !e.name().startsWith(prefix)) {
                        continue;
                    }
                    try {
                        ClassFile cf = ClassFile.read(z.read(e));
                        if (cf.referencesAnyMethodName(wantedNames)) {
                            return true;
                        }
                    } catch (Throwable ignored) {

                    }
                }
            }
            return false;
        } catch (Throwable t) {
            return true;                      
        }
    }

    /**
     * 宿主 Mixin 声明的方法名，与"拼接补进去的方法名"有没有撞上。
     *
     * <p>撞上就意味着 Mixin 会以为"目标类已有该方法"，从而要求 {@code @Overwrite}：
     * <pre>
     *   InvalidMixinException: getConnection()... cannot overwrite method in
     *     ...ClientCommonPacketListenerImpl because @Overwrite is required
     * </pre>
     *
     * <p>所以这种情况必须**纯保留原版**，让 Mixin 自己把方法创建出来。
     */
    private static boolean mixinDeclaredNamesConflict(String internal, byte[] splicedBytes) {
        try {
            java.util.Set<String> mixinNames = mixinNamesOf(internal);
            if (mixinNames == null || mixinNames.isEmpty()) {
                return false;
            }

            java.util.Set<String> spliced = new java.util.HashSet<>();
            ClassFile cf = ClassFile.read(splicedBytes);
            for (ClassFile.Member m : cf.methods()) {
                String n = cf.utf8(m.nameIndex);
                if (n != null) {
                    spliced.add(n);
                }
            }
            ClassFile vf = readVanillaClass(internal);
            if (vf != null) {
                for (ClassFile.Member m : vf.methods()) {
                    String n = vf.utf8(m.nameIndex);
                    if (n != null) {
                        spliced.remove(n);
                    }
                }
            }
            for (String n : spliced) {
                if (mixinNames.contains(n)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 供诊断工具读"宿主 Mixin 声明的方法名"（走归一键查询）。 */
    public static java.util.Set<String> mixinNamesForTest(String internal) {
        return mixinNamesOf(internal);
    }

    /** 供诊断工具读"宿主 Mixin 声明的方法名"表。 */
    public static java.util.Map<String, java.util.Set<String>> mixinDeclaredMethods() {
        return new java.util.HashMap<>(MIXIN_DECLARED_METHODS);
    }

    public static java.util.List<Path> expandModJarsForTest(McInstance instance,
                                                             java.util.List<Path> modJars) {
        return expandModJars(instance, modJars, null);
    }

    /** 宿主 Mixin 自己声明的方法名，按目标类归档（用于判断撞名）。 */
    private static final java.util.Map<String, java.util.Set<String>> MIXIN_DECLARED_METHODS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 记下某个 Mixin 类声明的实例方法名。 */
    private static void recordMixinDeclared(String target, ClassFile mixinClass) {
        if (target == null || mixinClass == null) {
            return;
        }
        java.util.Set<String> set = MIXIN_DECLARED_METHODS.computeIfAbsent(
                target, k -> java.util.concurrent.ConcurrentHashMap.newKeySet());
        for (ClassFile.Member m : mixinClass.methods()) {
            String n = mixinClass.utf8(m.nameIndex);
            if (n != null && !n.startsWith("<")) {
                set.add(n);
            }
        }
    }

    private static ClassFile readVanillaClass(String internal) {
        Path vj = CURRENT_VANILLA_JAR;
        if (vj == null) {
            return null;
        }
        try (FastZip z = FastZip.open(vj)) {
            FastZip.Entry e = z.entry(internal + ".class");
            return e == null ? null : ClassFile.read(z.read(e));
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 目标类的宿主 Mixin 是不是**一个方法名都没声明**。
     *
     * <p>这种 Mixin 只会 {@code @Inject}/{@code @Redirect} 到已有方法，
     * 不会创建新方法，所以整类换不会引起 {@code @Overwrite is required} 撞名 —— 可以安全换。
     * 反过来说，只要 Mixin 声明了方法名，整类换就可能撞名，必须保留原版。
     */
    private static boolean mixinDeclaresAnyMethod(String internal) {
        java.util.Set<String> names = mixinNamesOf(internal);
        return names != null && !names.isEmpty();
    }

    /**
     * 查"某个类有没有 Mixin 声明的方法名"，**两种内部名写法都试**。
     *
     * <p>踩过一次：{@code @Mixin(DataComponentMap$Builder.class)} 注解里给的是
     * {@code net/minecraft/core/component/DataComponentMap$Builder}（用 {@code $}），
     * 而从 jar 条目名推出来的内部名是 {@code .../DataComponentMap/Builder}（用 {@code /}）——
     * 两者指的是同一个类。表里只存了注解那种、查表只用条目那种，
     * 于是判据永远查不到，撞名检查形同虚设（实测 DataComponentMap$Builder 就是这么漏的）。
     *
     * <p>做法：先按原样查，查不到再把**包路径之后的所有分隔符**换成 {@code $} 再查一次。
     */
    private static java.util.Set<String> mixinNamesOf(String internal) {
        java.util.Set<String> s = MIXIN_DECLARED_METHODS.get(internal);
        if (s != null) {
            return s;
        }

        // 逐个把候选 '／' 换成 '$' 试（层数很少，开销可忽略）。
        String cur = internal;
        int idx = cur.lastIndexOf('/');
        while (idx > 0) {
            cur = cur.substring(0, idx) + "$" + cur.substring(idx + 1);
            s = MIXIN_DECLARED_METHODS.get(cur);
            if (s != null) {
                return s;
            }
            idx = cur.lastIndexOf('/', idx - 1);
        }
        return null;
    }

    /**
     * 整类换会不会让宿主 Mixin 撞名。
     *
     * <p>判据：目标类有一个 Mixin 声明了方法名 {@code N}，而**对方的字节里也有 {@code N}**
     * （原版没有）。这时 Mixin 从"创建方法"变成"覆盖方法"，需要 {@code @Overwrite}，
     * 而模组没写 —— 于是 {@code InvalidMixinException}、启动崩。
     *
     * <p>解法是保留原版：Mixin 把 {@code N} 创建出来，对方那部分能力在这些类上缺失
     * （可诊断的运行期失败，而不是启动必崩）。
     */
    private static boolean mixinReplacementConflicts(String internal, byte[] vanillaBytes,
                                                     byte[] patchedBytes) {
        try {
            java.util.Set<String> mixinNames = mixinNamesOf(internal);
            if (mixinNames == null || mixinNames.isEmpty()) {
                return false;
            }
            ClassFile v = ClassFile.read(vanillaBytes);
            ClassFile p = ClassFile.read(patchedBytes);
            java.util.Set<String> had = new java.util.HashSet<>();
            for (ClassFile.Member m : v.methods()) {
                String n = v.utf8(m.nameIndex);
                if (n != null) {
                    had.add(n);
                }
            }
            for (ClassFile.Member m : p.methods()) {
                String n = p.utf8(m.nameIndex);
                if (n != null && !had.contains(n) && mixinNames.contains(n)) {
                    return true;              // 对方新增了与 Mixin 同名的方法
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 这个类是不是"宿主 Mixin 目标，且注入失败会直接崩启动"。
     *
     * <p>用来给"两害相权"定标准：
     * <ul>
     *   <li>宿主 Mixin 注入失败（{@code Scanned 0 target(s)}）→ **启动必崩**，
     *       实测 {@code LivingEntity}（fabric-content-registries 的 fluid.LivingEntityMixin）
     *       与 {@code Level}（c2me）。</li>
     *   <li>对方接口缺失（{@code LambdaConversionException}）→ 只在**跨端模组真正走到
     *       那条路径时**才崩，属于功能缺失而不是启动失败。</li>
     * </ul>
     *
     * <p>所以"先能启动"的目标下，Mixin 目标一律保留原版。
     * 真正的解法是拼接/桥接（让原版字节自己长出对方的能力），那是后续工作。
     */
    private static boolean isHostMixinTargetProtected(String internal,
                                                      java.util.Set<String> keepVanilla) {
        return keepVanilla != null && keepVanilla.contains(internal);
    }

    private static java.util.Set<String> addedInterfacesOf(byte[] vanillaBytes, byte[] patchedBytes) {
        java.util.Set<String> out = new java.util.HashSet<>();
        try {
            ClassFile v = ClassFile.read(vanillaBytes);
            ClassFile p = ClassFile.read(patchedBytes);
            java.util.Set<String> had = new java.util.HashSet<>(java.util.Arrays.asList(v.interfaces()));
            for (String i : p.interfaces()) {
                if (!had.contains(i)) {
                    out.add(i);
                }
            }
        } catch (Throwable ignored) {

        }
        return out;
    }

    /**
     * 对方有没有把本类**当成新增的那个接口类型**接收（lambda 接收者 / 参数类型）。
     *
     * <p>这是"保留原版会必然崩"的判据：Java 的 lambda 接收者转换是**运行期**检查的，
     * 类没实现那个接口就抛
     * {@code LambdaConversionException: Invalid receiver type interface X; not a subtype
     * of implementation type interface Y} —— 且无法绕过。
     *
     * <p>判法（保守但有效）：在对方平台的字节里找
     * {@code invokedynamic} 的**方法类型描述符**里，有没有把本类当参数、
     * 这个参数又被转成新接口。这里退一步用更简单的近似：
     * 只要对方字节里同时出现"本类"和"新接口"作为同一个 {@code LambdaMetafactory}
     * 引导参数的方法类型，就算命中。
     */
    private static boolean foreignUsesClassAsNewInterface(ClassFile patched,
                                                          java.util.Set<String> addedInterfaces) {
        try {
            String self = patched.thisName();
            if (self == null || addedInterfaces.isEmpty()) {
                return false;
            }
            // 缓存：这个判据要扫对方平台的几千个类，而同一个类可能被问多次
            // （预扫一次、写入循环一次）。不缓存时合并会从 70 秒涨到好几分钟 ——
            // 实测就是这样，所以这里必须记忆化。
            String key = self + "|" + new java.util.TreeSet<>(addedInterfaces);
            Boolean cached = IFACE_FATAL_CACHE.get(key);
            if (cached != null) {
                return cached;
            }
            boolean result = scanForInterfaceReceiver(self, addedInterfaces);
            IFACE_FATAL_CACHE.put(key, result);
            return result;
        } catch (Throwable t) {
            return true;                      // 出错保守：按"必然崩"处理
        }
    }

    /** 接口"必然崩"判据的记忆化表。 */
    private static final java.util.Map<String, Boolean> IFACE_FATAL_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 一次扫描，建"哪些类提到了本类"的索引；之后每次判据都是 O(1) 查表。
     *
     * <p>为什么要索引化：原来每次判据都把所有 jar 扫一遍，实测合并从 70 秒涨到
     * 500 多秒 —— 而接口变过的类有几十个，等于把同一批 jar 反复读了几十遍。
     * 这里只在第一次建一次索引：记录"每个类名出现在哪些类文件里"，
     * 之后判"本类 + 新接口是否同时出现在某个类里"就是两次查表取交集。
     *
     * <p>仍然用字节级子串搜索而不是完整解析 —— 常量池的 UTF8 项在 .class 里就是明文，
     * 够用且快得多。
     */
    /**
     * 查表判断"对方是否把本类当新接口类型接收"。
     *
     * <p>用一次建好的索引：先看"谁提到了本类"，再看这些类里有没有同时提到新接口。
     * 每个类的判据于是变成两次查表 + 一次小集合遍历，而不是重扫所有 jar
     * （不索引化时实测合并要 500 多秒）。
     */
    private static boolean scanForInterfaceReceiver(String self,
                                                    java.util.Set<String> addedInterfaces) {
        try {
            java.util.Map<String, java.util.Set<String>> idx = receiverIndex();
            java.util.Set<String> whoSelf = idx.get(self);
            if (whoSelf == null || whoSelf.isEmpty()) {
                return false;
            }
            for (String itf : addedInterfaces) {
                java.util.Set<String> whoIface = idx.get(itf);
                if (whoIface == null || whoIface.isEmpty()) {
                    continue;
                }
                java.util.Set<String> smaller = whoSelf.size() <= whoIface.size()
                        ? whoSelf : whoIface;
                java.util.Set<String> larger = smaller == whoSelf ? whoIface : whoSelf;
                for (String w : smaller) {
                    if (larger.contains(w)) {
                        return true;              
                    }
                }
            }
            return false;
        } catch (Throwable t) {
            return true;                      // 出错保守：按"必然崩"处理
        }
    }

    private static volatile java.util.Map<String, java.util.Set<String>> RECEIVER_INDEX;
    private static final Object RECEIVER_INDEX_LOCK = new Object();

    private static java.util.Map<String, java.util.Set<String>> receiverIndex() {
        java.util.Map<String, java.util.Set<String>> idx = RECEIVER_INDEX;
        if (idx != null) {
            return idx;
        }
        synchronized (RECEIVER_INDEX_LOCK) {
            if (RECEIVER_INDEX != null) {
                return RECEIVER_INDEX;
            }
            long t0 = System.nanoTime();
            java.util.Map<String, java.util.Set<String>> built = new java.util.HashMap<>(1 << 16);
            java.util.List<Path> sources = new ArrayList<>(4);
            Path pj = patchedJarOf();
            if (pj != null) {
                sources.add(pj);
            }
            sources.addAll(runtimeJars());
            for (Path src : sources) {
                try (FastZip z = FastZip.open(src)) {
                    for (FastZip.Entry e : z.entries()) {
                        if (!e.isClass() || e.isDirectory()) {
                            continue;
                        }
                        byte[] bytes;
                        try {
                            bytes = z.read(e);
                        } catch (Throwable ignored) {
                            continue;
                        }
                        String who = e.name();

                        for (String cls : classesMentionedIn(bytes)) {
                            built.computeIfAbsent(cls, k -> new java.util.HashSet<>(4)).add(who);
                        }
                    }
                } catch (Throwable ignored) {

                }
            }
            RECEIVER_INDEX = built;
            if (TRACE_CLASSES.contains("__merge__")) {

            }
            return built;
        }
    }

    private static java.util.Set<String> classesMentionedIn(byte[] b) {
        java.util.Set<String> out = new java.util.HashSet<>(32);
        int i = 0;
        while (i < b.length) {
            if (b[i] != 'L') {
                i++;
                continue;
            }

            int j = i + 1;
            boolean ok = false;
            while (j < b.length) {
                byte c = b[j];
                if (c == ';') {
                    ok = true;
                    break;
                }
                boolean legal = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                        || (c >= '0' && c <= '9') || c == '/' || c == '$' || c == '_';
                if (!legal) {
                    break;
                }
                j++;
            }
            if (ok && j > i + 1 && j - i < 300) {
                out.add(new String(b, i + 1, j - i - 1,
                        java.nio.charset.StandardCharsets.UTF_8));
            }
            i = ok ? j + 1 : i + 1;
        }
        return out;
    }

    /**
     * 对方是否**删掉了原版已有的方法**（按 名字+描述符 精确比对）。
     *
     * <p>为什么要单独判这一条：它和"新增方法"的后果完全不同。
     * 对方新增方法 → 不处理只是"用不了新功能"（可接受）；
     * 对方**删**了原版方法 → 任何还按原版签名调用的代码都会
     * {@code NoSuchMethodError}（硬崩，且调用者可能是宿主平台自己的代码）。
     *
     * <p>实测 {@code CustomPacketPayload}：原版 {@code codec(FallbackProvider, List)}
     * 被对方换成了 4 参数版本，而 Fabric 的 networking API 还在按 2 参数调。
     * 这种情况必须**拼接**（原版字节为基 + 补进对方新增的方法），
     * 整类换就会把原版那个签名弄丢。
     */
    private static boolean vanillaMethodMissingFromPatched(ClassFile vanilla, ClassFile patched) {
        try {
            java.util.Set<String> have = new java.util.HashSet<>();
            for (ClassFile.Member m : patched.methods()) {
                have.add(patched.utf8(m.nameIndex) + patched.utf8(m.descIndex));
            }
            for (ClassFile.Member m : vanilla.methods()) {
                String n = vanilla.utf8(m.nameIndex);
                if (n == null || n.startsWith("<")) {
                    continue;                     
                }
                if ((m.access & 0x0002) != 0) {
                    continue;                     
                }
                String key = n + vanilla.utf8(m.descIndex);
                if (!have.contains(key)) {
                    return true;                  
                }
            }
            return false;
        } catch (Throwable t) {
            return false;                         
        }
    }

    /**
     * 对方**新增的方法**里，有没有引用这些（接口新增的）方法名。
     *
     * <p>为什么这条最关键：拼接会把对方新增的方法搬进原版类，但**搬不来接口**。
     * 如果那个方法体引用了"只存在于新增接口上"的方法，运行期就是
     * {@code NoSuchMethodError}。
     *
     * <p>实测 {@code Holder}：原版 {@code Holder$Reference} 只调 {@code Holder.is()}，
     * 而对方版本调 {@code Holder.getKey()}（{@code IHolderExtension} 的 default 方法）。
     * 我们拼进来之后调用点就悬空了 —— 启动即崩。
     */
    private static boolean patchedClassUsesAddedMethods(ClassFile vanilla, ClassFile patched,
                                                        java.util.Set<String> wantedNames) {
        try {

            java.util.Set<String> existing = new java.util.HashSet<>();
            for (ClassFile.Member m : vanilla.methods()) {
                existing.add(vanilla.utf8(m.nameIndex) + vanilla.utf8(m.descIndex));
            }
            boolean anyNew = false;
            for (ClassFile.Member m : patched.methods()) {
                String key = patched.utf8(m.nameIndex) + patched.utf8(m.descIndex);
                if (!existing.contains(key)) {
                    anyNew = true;
                    break;
                }
            }
            if (!anyNew) {
                return false;                 
            }

            return patched.referencesAnyMethodName(wantedNames);
        } catch (Throwable t) {
            return true;                      
        }
    }

    /**
     * 宿主自己的"内部类 / 同级嵌套类"里，有没有调用这些方法（按名字）。
     *
     * <p>为什么需要：判据原本只扫对方平台的字节，漏掉了**原版自己的调用者**。
     * 实测 {@code Holder} 就栽在这里 ——
     * 对方给 {@code Holder} 加了 {@code IHolderExtension}（含 default 方法 {@code getKey()}），
     * 而调用者是原版的内部类 {@code Holder$Reference.equals}：
     * <pre>
     *   NoSuchMethodError: 'ResourceKey net.minecraft.core.Holder.getKey()'
     *     at net.minecraft.core.Holder$Reference.equals(Holder.java)
     * </pre>
     * {@code Holder$Reference} 保留原版字节，它照原版编译结果去调 {@code Holder.getKey()}，
     * 而原版 {@code Holder} 上根本没有这个方法 —— 于是启动就崩。
     *
     * <p>做法：拿"本类的内部类名"去原版游戏包里找（{@code 本类$xxx.class}），
     * 看它们有没有引用这些"接口新增方法"。
     */
    private static boolean vanillaInnerClassesUse(ClassFile patched,
                                                  java.util.Set<String> wanted) {
        try {
            String internals = System.getProperty("fusion.foreignRuntimeJars", "");

            Path vj = CURRENT_VANILLA_JAR;
            if (vj == null) {
                return true;                  
            }

            String self = patched.thisName();
            if (self == null) {
                return true;                  
            }
            String prefix = self + "$";

            try (FastZip z = FastZip.open(vj)) {
                int scanned = 0;
                for (FastZip.Entry e : z.entries()) {
                    if (!e.isClass() || e.isDirectory() || ++scanned > 6000) {
                        continue;
                    }
                    String n = e.name();
                    boolean inner = n.startsWith(prefix);
                    if (!inner) {
                        continue;
                    }
                    try {
                        ClassFile cf = ClassFile.read(z.read(e));
                        if (cf.referencesAnyMethodName(wanted)) {
                            return true;
                        }
                    } catch (Throwable ignored) {

                    }
                }
            }
            return false;
        } catch (Throwable t) {
            return true;                      
        }
    }

    private static boolean foreignCallersUse(ClassFile vanilla, ClassFile patched,
                                             Path patchedJar) {
        try {
            java.util.Set<String> added = new java.util.HashSet<>();
            for (String i : patched.interfaces()) {
                boolean had = false;
                for (String o : vanilla.interfaces()) {
                    if (o.equals(i)) {
                        had = true;
                    }
                }
                if (!had) {
                    added.add(i);
                }
            }
            if (added.isEmpty()) {
                return false;
            }

            java.util.Set<String> wanted = new java.util.HashSet<>();
            for (String i : added) {
                ClassFile iface = readFromJars(i + ".class");
                if (iface == null) {
                    return true;              
                }
                for (ClassFile.Member m : iface.methods()) {
                    String n = iface.utf8(m.nameIndex);
                    String d = iface.utf8(m.descIndex);
                    if (n != null && d != null && !n.startsWith("<")) {
                        wanted.add(n + d);
                    }
                }
            }
            if (wanted.isEmpty()) {
                return false;
            }

            if (foreignReferencesAny(patchedJar, added, wanted)) {
                return true;
            }

            // 实测踩到（Holder）：

            //   因为 Holder$Reference 走的是原版字节，它照着原版编译结果去调

            // 教训：判断"这个接口方法必须存在吗"，不能只看对方平台 ——

            // 注意这里要传**名字集合**而不是"名字+描述符"：

            java.util.Set<String> wantedNames = new java.util.HashSet<>();
            for (String w : wanted) {
                int paren = w.indexOf('(');
                wantedNames.add(paren > 0 ? w.substring(0, paren) : w);
            }
            boolean innerHit = vanillaInnerClassesUse(patched, wantedNames);
            boolean addedHit = patchedClassUsesAddedMethods(vanilla, patched, wantedNames);
            if (TRACE_CLASSES.contains(patched.thisName())) {
                HOOK_TRACE.put(patched.thisName(), "新增接口=" + added + "，要用的方法名="
                        + new java.util.TreeSet<>(wantedNames)
                        + "，原版内部类命中=" + innerHit + "，对方新增方法命中=" + addedHit);
            }
            if (innerHit || addedHit) {
                return true;
            }

            // 前面三条都没命中，是因为调用者根本不在本类的常量池里：
            // 原版 Holder$Reference 只调 Holder.is()，而**对方版本的 Holder$Reference**

            boolean foreignInnerHit = foreignInnerClassesUse(patched.thisName(), wantedNames);
            if (TRACE_CLASSES.contains(patched.thisName())) {
                HOOK_TRACE.put(patched.thisName(), HOOK_TRACE.getOrDefault(patched.thisName(), "")
                        + "，对方内部类命中=" + foreignInnerHit);
            }
            if (foreignInnerHit) {
                return true;
            }

            // 但**对方版本的 Holder$Reference** 调 `Holder.getKey()`（对方自己写的实现）。

            //   Registry 是宿主 Mixin 目标（所以前面被 `!mixinProtected` 挡住 → 保留原版），
            //   可对方（yumi_mc_core 的 NeoForge 路径）会做

            //   —— **必然崩，没有选择余地**。
            // 而"换对方字节"最多是 Mixin 注入点失配（可能崩、且被我们记进报告）。
            // 两害相权，必然崩的那边不能选。
            boolean foreignTakesAsInterface = foreignUsesClassAsNewInterface(patched, added);
            if (TRACE_CLASSES.contains(patched.thisName())) {
                HOOK_TRACE.put(patched.thisName(),
                        HOOK_TRACE.getOrDefault(patched.thisName(), "")
                        + "，对方当新接口类型接收=" + foreignTakesAsInterface);
            }
            if (foreignTakesAsInterface) {
                return true;
            }
            return addedHit;
        } catch (Throwable t) {
            return true;                      
        }
    }

    private static volatile Path CURRENT_PATCHED_JAR;

    private static ClassFile readFromJars(String entry) {

        // 顺序不能反也不能漏：对方**超类**（如 AttachmentHolder）住在 NeoForge 的
        // universal 包里，而不是打补丁的原版包里 —— 只查前者会报"读不到对方超类"，
        // 桥接就直接放弃了（实测第一次跑就是这样）。
        Path jar = CURRENT_PATCHED_JAR;
        if (jar != null) {
            try (FastZip z = FastZip.open(jar)) {
                FastZip.Entry e = z.entry(entry);
                if (e != null) {
                    return ClassFile.read(z.read(e));
                }
            } catch (Throwable ignored) {

            }
        }
        for (Path rt : runtimeJars()) {
            try (FastZip z = FastZip.open(rt)) {
                FastZip.Entry e = z.entry(entry);
                if (e != null) {
                    return ClassFile.read(z.read(e));
                }
            } catch (Throwable ignored) {

            }
        }
        return null;
    }

    /**
     * 补丁包里有没有谁**调用**了这些接口方法。
     *
     * <p>看的是 {@code Methodref}/{@code InterfaceMethodref} 的 **owner** 是否就是那几个新接口，
     * 而不是"有没有同名方法"。这一点很关键：只比方法名会撞上大量常见名字
     * （{@code get}/{@code apply}），把便利接口误判成活契约 ——
     * 实测 {@code DataComponentMap$Builder} 就是这么被误判的。
     */
    private static boolean foreignReferencesAny(Path patchedJar, java.util.Set<String> ifaces,
                                                java.util.Set<String> wanted) {
        try (FastZip z = FastZip.open(patchedJar)) {
            int scanned = 0;
            for (FastZip.Entry e : z.entries()) {
                if (!e.isClass() || e.isDirectory()) {
                    continue;
                }

                if (++scanned > 4000) {
                    break;
                }
                try {
                    ClassFile cf = ClassFile.read(z.read(e));
                    if (cf.referencesAnyMethodOf(ifaces, wanted)) {
                        return true;
                    }
                } catch (Throwable ignored) {

                }
            }
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 对方补丁是不是给这个类加了接口。
     *
     * <p>加接口的类**不能拼接**（拼接改不了 interfaces 列表），必须整类换。
     * 实测：{@code Registry} 多了 {@code IRegistryExtension}，若只拼接，对方模组的
     * {@code DeferredRegister.addEntries} 会抛
     * {@code LambdaConversionException: ...not a subtype of ...IRegistryExtension}。
     */
    private static boolean interfacesChanged(byte[] vanillaBytes, byte[] patchedBytes) {
        try {
            return !java.util.Objects.equals(
                    String.join(",", ClassFile.read(vanillaBytes).interfaces()),
                    String.join(",", ClassFile.read(patchedBytes).interfaces()));
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 仅供离线自检/诊断使用：对外暴露"方法级拼接"，好在不启动游戏的前提下
     * 先用 JVM 的校验器验一遍产物（见 {@code SpliceProbe}）。
     */
    public static byte[] spliceNewMethodsForTest(byte[] vanillaBytes, byte[] patchedBytes,
                                                 String internal) {
        byte[] out = spliceNewMethods(vanillaBytes, patchedBytes, internal);
        LAST_SPLICED[0] = LAST_SPLICED_CLASS;
        return out;
    }

    private static final ClassFile[] LAST_SPLICED = new ClassFile[1];
    private static volatile ClassFile LAST_SPLICED_CLASS;

    public static ClassFile[] lastSplicedClass() {
        return LAST_SPLICED;
    }

    public static java.util.List<String> splicedReport() {        java.util.List<String> out = new ArrayList<>(SPLICE_COUNTS.size());
        SPLICE_COUNTS.forEach((k, n) -> out.add(k + "（+" + n + " 个方法）"));
        return out;
    }

    /** 拼接失败的类。 */
    public static java.util.List<String> spliceFailures() {
        return new ArrayList<>(spliceFailed);
    }

    /**
     * 宿主平台（Fabric）模组要 Mixin 的原版类。
     *
     * <p>为什么要算，而不是拍一个包名范围：Mixin 的注入点是按<b>指令序列</b>匹配的，
     * 而对方平台的补丁原版是"反编译 + 重新编译"的产物，方法体的指令序列与原版并不逐条一致。
     * 把对方补丁塞给这些类，宿主平台模组的注入点就会匹配到 0 个目标，
     * 报的是一句很难读的 {@code Critical injection failure ... Scanned 0 target(s)}，然后整局游戏起不来。
     *
     * <p>算法：只认宿主平台格式的模组包（带 {@code fabric.mod.json}），
     * 读它的 Mixin 配置（{@code *.mixins.json}），再读配置里列出的注入类上的
     * {@code @Mixin} 注解，取注解里声明的目标类。全程只解压"注入类"这几个条目，
     * 不做全包扫描。
     */
    /**
     * 扫出<b>所有</b>模组包的 Mixin 目标类，不分平台格式 —— 这是"禁止提前加载"的黑名单。
     *
     * <p>和 {@link #hostMixinTargets} 的区别，是拿事故换来的：
     * 那个方法只认带 {@code fabric.mod.json} 的包，因为它当时的用途是"决定哪些原版类保留原版字节"
     * （只有宿主平台模组的注入点才会被换基座打断）。但"禁止提前加载"这件事**不分平台** ——
     * 一个 NeoForge 格式的包（例如 {@code yumi_mc_core}）注入的是 NeoForge 的类，
     * 我们提前加载那些类同样会崩。
     *
     * <p>实测事故：{@code yumi_mc_core} 是 NeoForge 格式，被 {@code hostMixinTargets} 整个跳过，
     * 于是它的目标 {@code DeferredRegister} 不在黑名单里，诊断代码把它加载了，
     * 报 {@code MixinTargetAlreadyLoadedException ... was loaded too early}。
     *
     * <p>这里的扫描刻意"宽进"：只要 jar 里有名字含 {@code mixin} 的 json，就试着当 Mixin 配置读；
     * 读不动就跳过。宁可多收几个类名，也不要漏掉一个真实目标。
     */
    /**
     * 把"模组包"扩展到<b>游戏实际加载的全集</b> —— 这一步是止血的关键，写清楚它为什么必要。
     *
     * <h2>只看 {@code mods} 目录是不够的</h2>
     *
     * <p>实测：本整合包 {@code mods} 里只有 <b>37</b> 个 jar，而游戏日志写着
     * <b>{@code Loading 151 mods}</b>。差额不是小数目，而且恰好包含肇事者。
     *
     * <p>原因是宿主平台（Fabric）会把模组里的<b>嵌套 jar（JiJ）</b>解出来，
     * 缓存到 {@code <gameDir>/.fabric/processedMods/}，再从那里加载。
     * 本实例那个目录里有 <b>112</b> 个 jar。37 + 112 ≈ 151，正好对上。
     *
     * <p>而肇事模组 {@code yumi_mc_core} 就住在那里：
     *
     * <pre>
     * .fabric/processedMods/yumi_mc_core-1.1.3+26.2-….jar
     *   ├── fabric.mod.json                    ← 它是双格式包
     *   ├── META-INF/neoforge.mods.toml
     *   ├── yumi_mc_core.mixins.json
     *   └── yumi_mc_core.neoforge.mixins.json  ← 崩溃的那个配置在这里
     * </pre>
     *
     * <p>所以"只扫 {@code mods}"等于把最大的一批模组排除在保护范围之外：
     * 它们的 Mixin 目标进不了黑名单，我们的诊断/预热代码就会提前加载那些目标，
     * 报 {@code MixinTargetAlreadyLoadedException ... was loaded too early}，
     * 连带炸掉别的模组的入口点。这就是那轮崩溃的真正原因 ——
     * 前面几处修复方向都对，但**没扫到这个目录，所以全都够不着那个类**。
     *
     * <p>做法是"宽进"：把 {@code mods} 与其下所有子目录里的 jar、
     * 以及宿主平台的嵌套包缓存目录一并收进来。多扫几个包只是多花几十毫秒，
     * 漏扫一个却是一整局起不来。
     */
    public static List<Path> expandModJars(McInstance instance, List<Path> modJars,
                                           Consumer<String> log) {
        List<Path> out = new ArrayList<>(modJars == null ? 64 : modJars.size() + 128);
        java.util.Set<Path> seen = new java.util.HashSet<>();
        if (modJars != null) {
            for (Path p : modJars) {
                if (p != null && seen.add(p.toAbsolutePath().normalize())) {
                    out.add(p);
                }
            }
        }
        int before = out.size();
        // 宿主平台的嵌套包缓存：这是"37 vs 151"那个差额的来源。
        for (String rel : new String[] {".fabric/processedMods", ".fabric/remappedJars",
                ".cache/fabric/processedMods"}) {
            try {
                Path dir = instance.gameDir().resolve(rel);
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, "*.jar")) {
                    for (Path p : s) {
                        if (seen.add(p.toAbsolutePath().normalize())) {
                            out.add(p);
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 某个缓存目录读不了不影响其余部分。
            }
        }

        try {
            Path mods = instance.gameDir().resolve("mods");
            if (Files.isDirectory(mods)) {
                try (java.util.stream.Stream<Path> s = Files.walk(mods, 3)) {
                    s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)
                                    .endsWith(".jar"))
                            .forEach(p -> {
                                if (seen.add(p.toAbsolutePath().normalize())) {
                                    out.add(p);
                                }
                            });
                }
            }
        } catch (Throwable ignored) {

        }
        if (log != null && out.size() != before) {
            log.accept("跨端补丁基座：模组包全集 " + out.size() + " 个（mods 传入 " + before
                    + " 个，另含宿主平台嵌套包缓存等 —— 只看 mods 会漏掉未解包的模组）");
        }
        return out;
    }

    /**
     * 对方平台这份补丁是不是<b>只新增成员</b>（原版已有的字段/方法它一个都没动）。
     *
     * <h2>这个判据为什么关键</h2>
     *
     * <p>整类覆盖会把对方平台<b>新增</b>的方法带进原版类。而宿主平台模组的
     * {@code @Overwrite} 型 Mixin 是按"原版没有这个方法"写的，
     * 一旦这个方法突然存在，Mixin 会在 APPLY 阶段直接失败：
     *
     * <pre>
     * InvalidMixinException: get(Lnet/minecraft/core/component/DataComponentType;)Ljava/lang/Object;
     *   in fabric-item-api-v1.mixins.json:DataComponentMapBuilderMixin
     *   cannot overwrite method in net.minecraft.core.component.DataComponentMap$Builder
     *   because @Overwrite is required by the parent configuration
     * </pre>
     *
     * <p>（实测来源：{@code fabric-item-api-v1.mixins.json} 里
     * {@code "overwrites": {"requireAnnotations": true}}。）
     *
     * <p>判据用"成员签名是否一致"而不是"字节是否一致"：反编译再编译会让方法体指令序列变化，
     * 但**方法名+描述符**是稳定的契约。只要原版每个成员在补丁里都还在（名字与描述符都能对上），
     * 就认为对方没有"破坏性改动"，这一类交给原版字节是安全的。
     *
     * <p>反过来，一旦对方**删掉或改了**某个原有成员的签名，就必须用对方的字节 ——
     * 那种情况下对方模组是硬依赖新形状的，保留原版反而会崩。
     *
     * @return true 表示"补丁只是新增，没有动原版已有的成员"
     */
    private static boolean onlyAddsMembers(byte[] vanilla, byte[] patched, Path patchedJar) {
        try {
            ClassFile v = ClassFile.read(vanilla);
            ClassFile p = ClassFile.read(patched);
            // 超类变了就不是"只新增"：那是结构性改动，必须用对方的字节。
            if (!java.util.Objects.equals(v.superName(), p.superName())) {
                return false;
            }
            // 接口的判据要分两种，不能一刀切"接口变了就整类换" —— 这一条是踩出来的。

            // 反例一（必须用对方字节）：net.minecraft.core.Registry 多了 IRegistryExtension。

            // 反例二（必须保留原版字节）：net.minecraft.core.component.DataComponentMap$Builder

            //   而把它带进原版类，宿主平台的 @Overwrite Mixin 就会失败：
            //   InvalidMixinException: get(Lnet/minecraft/core/component/DataComponentType;)...

            //   （fabric-item-api-v1.mixins.json:DataComponentMapBuilderMixin）

            if (!java.util.Objects.equals(interfaceKey(v), interfaceKey(p))) {
                if (addsRequiredInterface(v, p, patchedJar)) {
                    return false;       // 新增了带抽象方法的接口：必须用对方的字节
                }
            }
            java.util.Set<String> pMethods = memberKeys(p, true);
            java.util.Set<String> pFields = memberKeys(p, false);
            for (String k : memberKeys(v, true)) {
                if (!pMethods.contains(k)) {
                    return false;       // 原版有、补丁没有 → 对方删/改了方法，必须用对方的
                }
            }
            for (String k : memberKeys(v, false)) {
                if (!pFields.contains(k)) {
                    return false;       
                }
            }
            return true;
        } catch (Throwable t) {
            // 解不开就按"不是纯新增"处理：宁可换整类（保守），

            return false;
        }
    }

    private static String interfaceKey(ClassFile cf) {
        return String.join(",", cf.interfaces());
    }

    /**
     * 补丁新增的接口里，有没有"带抽象方法"的（即对方模组硬依赖的）。
     *
     * <p>判据的理由见 {@link #onlyAddsMembers} 里的两段反例。
     * 接口本身不在补丁原版包里（它们住在对方平台的运行时包里），
     * 所以要去本机的 libraries 里找 —— 找不到就**保守地认为必需**（宁可整类换，
     * 也不要因为读不到而丢掉对方模组的 API 契约）。
     */
    private static boolean addsRequiredInterface(ClassFile vanilla, ClassFile patched,
                                                 Path patchedJar) {
        java.util.Set<String> vIfaces = new java.util.HashSet<>();
        for (String i : vanilla.interfaces()) {
            vIfaces.add(i);
        }
        for (String i : patched.interfaces()) {
            if (vIfaces.contains(i)) {
                continue;                       
            }
            Integer abstractCount = abstractMethodCount(i);
            if (abstractCount == null) {
                return true;                    
            }
            if (abstractCount > 0) {
                return true;                    
            }
        }
        return false;
    }

    private static Integer abstractMethodCount(String internal) {
        if (INTERFACE_CACHE.containsKey(internal)) {
            return INTERFACE_CACHE.get(internal);
        }
        Integer result = null;
        java.util.List<Path> jars = runtimeJars();
        for (Path jar : jars) {
            try (FastZip z = FastZip.open(jar)) {
                FastZip.Entry e = z.entry(internal + ".class");
                if (e == null) {
                    continue;
                }
                ClassFile cf = ClassFile.read(z.read(e));
                int n = 0;
                for (ClassFile.Member m : cf.methods()) {
                    String name = cf.utf8(m.nameIndex);
                    if (name == null || name.startsWith("<")) {
                        continue;
                    }
                    boolean isStatic = (m.access & 0x0008) != 0;
                    boolean isAbstract = (m.access & 0x0400) != 0;
                    if (!isStatic && isAbstract) {
                        n++;
                    }
                }
                result = n;
                break;
            } catch (Throwable ignored) {

            }
        }

        // 那正是宿主平台 @Overwrite Mixin 崩掉的形态。所以这里必须留下痕迹，
        // 否则"读不到"和"真的必需"在日志里长得一模一样。
        if (result == null && INTERFACE_MISSES.size() < 8) {
            INTERFACE_MISSES.add(internal + "（可查 jar " + jars.size() + " 个）");
        }
        INTERFACE_CACHE.put(internal, result);
        return result;
    }

    private static final java.util.List<String> INTERFACE_MISSES =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    public static String describeInterface(String internal) {
        Integer n = abstractMethodCount(internal);
        if (n == null) {
            return "读不到形状（判据会保守地按\"必需\"处理，整类替换）";
        }
        return n == 0 ? "纯 default（0 个抽象方法）→ 保留原版字节"
                : n + " 个抽象方法 → 对方硬依赖，整类替换";
    }

    public static java.util.List<String> interfaceMisses() {
        return new ArrayList<>(INTERFACE_MISSES);
    }

    /** 本机 libraries 里对方平台的运行时包（接口住在里面）。缓存一次，扫描很贵。 */
    private static volatile java.util.List<Path> runtimeJarsCache;
    /** 上次缓存对应的属性原文：属性一变就重算，避免"先调用后设置"时把空表缓存住。 */
    private static volatile String runtimeJarsCacheKey;

    private static java.util.List<Path> runtimeJars() {
        String keyNow = System.getProperty("fusion.foreignRuntimeJars", "");
        java.util.List<Path> cached = runtimeJarsCache;
        if (cached != null && keyNow.equals(runtimeJarsCacheKey)) {
            return cached;
        }
        java.util.List<Path> out = new ArrayList<>(64);

        String raw = System.getProperty("fusion.foreignRuntimeJars", "");
        for (String p : raw.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)
                + "|,")) {
            String s = p.trim();
            if (!s.isEmpty()) {
                try {
                    Path path = Path.of(s);
                    if (Files.isRegularFile(path)) {
                        out.add(path);
                    }
                } catch (Throwable ignored) {

                }
            }
        }
        if (out.isEmpty()) {

            // 其次是 fusion.gameDir 向上找 —— 两条都用上，因为"读不到接口"
            // 会让判据静默退化，后果是宿主平台的 Mixin 崩，症状离原因极远。
            java.util.List<Path> roots = new ArrayList<>(2);
            String libs = System.getProperty(PROP_LIBRARIES, "");
            if (!libs.isBlank()) {
                roots.add(Path.of(libs));
            }
            String gameDir = System.getProperty("fusion.gameDir", "");
            if (!gameDir.isBlank()) {
                for (Path p = Path.of(gameDir); p != null; p = p.getParent()) {
                    Path l = p.resolve("libraries");
                    if (Files.isDirectory(l)) {
                        roots.add(l);
                        break;
                    }
                }
            }
            for (Path root : roots) {
                Path neo = root.resolve("net").resolve("neoforged");
                if (!Files.isDirectory(neo)) {
                    continue;
                }
                try (java.util.stream.Stream<Path> s = Files.walk(neo, 6)) {
                    s.filter(p -> p.getFileName().toString()
                                    .toLowerCase(Locale.ROOT).endsWith(".jar"))
                            .forEach(out::add);
                } catch (Throwable ignored) {

                }
                if (!out.isEmpty()) {
                    break;
                }
            }
        }
        runtimeJarsCache = out;
        runtimeJarsCacheKey = keyNow;
        return out;
    }

    private static final java.util.Map<String, Integer> INTERFACE_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 成员的"名字+描述符"集合，用来做与版本无关的契约比较。 */
    private static java.util.Set<String> memberKeys(ClassFile cf, boolean methods) {
        ClassFile.Member[] ms = methods ? cf.methods() : cf.fields();
        java.util.Set<String> out = new java.util.HashSet<>(ms.length * 2);
        for (ClassFile.Member m : ms) {
            String name = cf.utf8(m.nameIndex);
            String desc = cf.utf8(m.descIndex);
            if (name != null && desc != null) {
                out.add(name + desc);
            }
        }
        return out;
    }

    /**
     * 这个类是不是"对方平台 API 契约的承载者"—— 即它在对方补丁里多实现了
     * 一个带抽象方法的接口。
     *
     * <p>这类类必须用对方的字节，**哪怕它同时是某个宿主模组的 Mixin 目标**。
     * 理由与取舍见调用处的长注释（API 契约优先于注入点保护）。
     */
    private static boolean carriesRequiredInterface(String internal) {
        Boolean cached = REQUIRED_IFACE_CACHE.get(internal);
        if (cached != null) {
            return cached;
        }
        boolean result = false;
        boolean couldRead = false;
        Path patchedJar = patchedJarCache();
        Path vanillaJar = vanillaJarCache();
        if (patchedJar != null && vanillaJar != null) {
            try (FastZip pz = FastZip.open(patchedJar); FastZip vz = FastZip.open(vanillaJar)) {
                FastZip.Entry pe = pz.entry(internal + ".class");
                FastZip.Entry ve = vz.entry(internal + ".class");
                if (pe != null && ve != null) {
                    ClassFile pcf = ClassFile.read(pz.read(pe));
                    ClassFile vcf = ClassFile.read(vz.read(ve));
                    couldRead = true;
                    // 三种"必须用对方字节"的情形，缺一不可：

                    boolean byInterface = addsRequiredInterface(vcf, pcf, patchedJar);
                    if (isForeign(pcf.superName()) || byInterface
                            || dependsOnReplaced(vcf, pcf, internal)) {
                        result = true;

                        REPLACED_SEEDS.add(internal);
                    }
                }
            } catch (Throwable ignored) {

            }
        }

        // 方向不能反，这是踩出来的：这个方法的调用点在"保护名单命中"那一支上 ——

        // 如果读不到时返回 false，结果就是"因为读不到，所以把 API 契约丢了"，
        // 实测后果是 VerifyError：

        // 反过来，读不到却当"必需"的代价只是"这个类的注入点保护没生效"，
        // 而且如果真的发生了，日志里那行"取舍"会把它写出来（可诊断）。
        if (!couldRead) {
            MISSING_REQUIRED_CACHE.add(internal);
            result = true;
        }
        REQUIRED_IFACE_CACHE.put(internal, result);
        return result;
    }

    /** 已经确定"必须用对方字节"的类（闭包传染的种子与结果）。 */
    private static final java.util.Set<String> REPLACED_SEEDS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 这个类是否**引用了**对方平台新增/改动的成员，从而必须跟着一起用对方字节。
     *
     * <h2>为什么需要"传染"这条规则</h2>
     *
     * <p>实测：{@code Registry} 用了对方字节之后，它调用了 {@code Holder.getDelegate()} ——
     * 那是对方平台给 {@code Holder} 加的方法。而 {@code Holder} 因为"同时是模组 Mixin 目标"
     * 被保护名单留成了原版，于是运行到 {@code Registry.safeCastToReference} 就抛：
     *
     * <pre>
     * NoSuchMethodError: 'net.minecraft.core.Holder net.minecraft.core.Holder.getDelegate()'
     *   at net.minecraft.core.Registry.safeCastToReference(Registry.java:52)
     * </pre>
     *
     * <p>判据只看"新增接口有没有抽象方法"是抓不到这种情况的：
     * {@code Holder} 新增的 {@code IHolderExtension} 恰好是**纯 default**，
     * 按接口判据它会"可以不要"；但对方的 {@code Registry} 实实在在调了
     * {@code Holder} 上的新方法，不给就 `NoSuchMethodError`。
     *
     * <p>所以需要一条**闭包规则**：用对方字节的类，它引用到的类也要用对方字节。
     * 这里用一个可验证的近似 —— 如果对方版本的该类引用了"对方平台自己的类型"，
     * 或者引用了原版版本没有的成员，就认为它属于同一条依赖链。
     */
    private static boolean dependsOnReplaced(ClassFile vanilla, ClassFile patched, String internal) {
        if (REPLACED_SEEDS.contains(internal)) {
            return true;
        }
        // 近似判据：对方版本的方法体里引用了对方平台的类型（net/neoforged/、net/minecraftforge/），
        // 而原版版本没有 —— 说明它的行为已经和对方平台的字节绑定在一起。
        java.util.Set<String> pRefs = foreignRefs(patched);
        if (pRefs.isEmpty()) {
            return false;
        }
        java.util.Set<String> vRefs = foreignRefs(vanilla);
        pRefs.removeAll(vRefs);
        return !pRefs.isEmpty();
    }

    private static java.util.Set<String> foreignRefs(ClassFile cf) {
        java.util.Set<String> out = new java.util.HashSet<>(32);

        // 所以先探到一个连续为空的区间再停，避免拍一个可能不够大的常数。
        int misses = 0;
        for (int i = 1; i < 65536 && misses < 64; i++) {
            ClassFile.Cp c = cf.cp(i);
            if (c == null) {
                misses++;
                continue;
            }
            misses = 0;
            String n = c.utf8;
            if (n != null && (n.startsWith("net/neoforged/") || n.startsWith("net/minecraftforge/"))) {
                out.add(n);
            }
        }
        return out;
    }

    /** 把"因为读不到接口形状而走了保守分支"的类（诊断用）。 */
    private static final java.util.Set<String> MISSING_REQUIRED_CACHE =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static java.util.List<String> missingRequired() {
        return new ArrayList<>(MISSING_REQUIRED_CACHE);
    }

    private static volatile Path PATCHED_JAR_CACHE;
    private static volatile Path VANILLA_JAR_CACHE;

    private static Path patchedJarCache() {
        return PATCHED_JAR_CACHE;
    }

    private static Path vanillaJarCache() {
        return VANILLA_JAR_CACHE;
    }

    private static final java.util.Map<String, Boolean> REQUIRED_IFACE_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** "API 契约压过注入点保护"的类（诊断用）：这些类的 Mixin 目标保护被主动放弃了。 */
    private static final java.util.List<String> apiOverridesMixin =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** 把"因为 API 契约而放弃注入点保护"的类报告出来。 */
    public static java.util.List<String> apiOverrides() {
        return new ArrayList<>(apiOverridesMixin);
    }

    public static java.util.Set<String> allMixinTargets(List<Path> modJars, Consumer<String> log) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        if (modJars == null || modJars.isEmpty()) {
            return out;
        }
        long t0 = System.nanoTime();
        int scanned = 0;
        for (Path jar : modJars) {
            if (jar == null || !Files.isRegularFile(jar)) {
                continue;
            }
            try (FastZip zip = FastZip.open(jar)) {
                scanned++;
                for (FastZip.Entry e : zip.entries()) {
                    String name = e.name();
                    if (e.isDirectory() || !name.endsWith(".json")
                            || !name.toLowerCase(Locale.ROOT).contains("mixin")) {
                        continue;
                    }
                    java.util.Map<String, Object> root;
                    try {
                        root = com.fusionml.util.Json.parseObject(zip.read(e));
                    } catch (Throwable t) {
                        continue;
                    }
                    String pkg = com.fusionml.util.Json.str(root, "package", "");
                    List<String> listed = new ArrayList<>(8);
                    for (String key : List.of("mixins", "client", "server")) {
                        for (Object o : com.fusionml.util.Json.arr(root.get(key))) {
                            listed.add(String.valueOf(o));
                        }
                    }
                    for (String simple : listed) {
                        String entry = (pkg.isEmpty() ? ""
                                : pkg.replace('.', '/') + "/") + simple.replace('.', '/') + ".class";
                        FastZip.Entry ce = zip.entry(entry);
                        if (ce == null) {
                            continue;
                        }
                        List<ClassAnn.Ann> anns;
                        try {
                            anns = ClassAnn.readAnnotations(zip.read(ce));
                        } catch (Throwable t) {
                            continue;
                        }
                        for (ClassAnn.Ann a : anns) {
                            if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(a.desc())) {
                                continue;
                            }
                            for (String v : a.strList("value")) {
                                addTarget(out, v);
                            }
                            for (String v : a.strList("targets")) {
                                addTarget(out, v);
                            }
                        }
                    }
                }
            } catch (Throwable t) {

            }
        }
        if (log != null) {
            log.accept("跨端补丁基座：全部模组包 " + scanned + " 个，扫出 Mixin 目标 " + out.size()
                    + " 个（这是禁止提前加载的黑名单），耗时 "
                    + ((System.nanoTime() - t0) / 1_000_000) + " ms");
        }
        return out;
    }

    public static java.util.Set<String> hostMixinTargets(List<Path> modJars, Path patchedJar,
                                                         Consumer<String> log) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        if (modJars == null || modJars.isEmpty()) {
            return out;
        }
        long t0 = System.nanoTime();
        int configs = 0;
        int classes = 0;
        for (Path jar : modJars) {
            if (jar == null || !Files.isRegularFile(jar)) {
                continue;
            }
            try (FastZip zip = FastZip.open(jar)) {
                if (zip.entry("fabric.mod.json") == null) {
                    // 不是宿主平台格式的包：它要注入的类不构成"必须留原版"的理由。
                    continue;
                }
                for (FastZip.Entry e : zip.entries()) {
                    String name = e.name();
                    if (e.isDirectory() || !name.endsWith(".json")
                            || !name.toLowerCase(Locale.ROOT).contains("mixin")) {
                        continue;
                    }
                    java.util.Map<String, Object> root;
                    try {
                        root = com.fusionml.util.Json.parseObject(zip.read(e));
                    } catch (Throwable t) {
                        continue;
                    }
                    String pkg = com.fusionml.util.Json.str(root, "package", "");
                    List<String> listed = new ArrayList<>(8);
                    for (String key : List.of("mixins", "client", "server")) {
                        for (Object o : com.fusionml.util.Json.arr(root.get(key))) {
                            listed.add(String.valueOf(o));
                        }
                    }
                    if (listed.isEmpty()) {
                        continue;
                    }
                    configs++;
                    for (String simple : listed) {
                        String entry = (pkg.isEmpty() ? ""
                                : pkg.replace('.', '/') + "/") + simple.replace('.', '/') + ".class";
                        FastZip.Entry ce = zip.entry(entry);
                        if (ce == null) {
                            continue;
                        }
                        classes++;
                        List<ClassAnn.Ann> anns;
                        try {
                            anns = ClassAnn.readAnnotations(zip.read(ce));
                        } catch (Throwable t) {
                            continue;
                        }
                        for (ClassAnn.Ann a : anns) {
                            if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(a.desc())) {
                                continue;
                            }
                            // 顺便记下这个 Mixin **声明了哪些方法名** —— 合并时要判断
                            // "对方新增的方法"会不会与它们撞名（撞名会让 Mixin 要求

                            ClassFile mixinCf = null;
                            try {
                                mixinCf = ClassFile.read(zip.read(ce));
                            } catch (Throwable ignored) {

                            }
                            for (String v : a.strList("value")) {
                                addTarget(out, v);
                                recordMixinDeclared(normalizeTarget(v), mixinCf);
                            }
                            for (String v : a.strList("targets")) {
                                addTarget(out, v);
                                recordMixinDeclared(normalizeTarget(v), mixinCf);
                            }
                        }
                    }
                }
            } catch (Throwable t) {

            }
        }
        if (!out.isEmpty() && patchedJar != null) {
            out.retainAll(names(patchedJar));
        }
        if (log != null) {
            log.accept("跨端补丁基座：宿主平台模组的 Mixin 配置 " + configs + " 条、注入类 "
                    + classes + " 个，命中补丁类 " + out.size() + " 个——这些类保留原版字节，"
                    + "耗时 " + ((System.nanoTime() - t0) / 1_000_000) + " ms");
            if (!out.isEmpty()) {
                List<String> sample = new ArrayList<>(out);
                int show = Math.min(5, sample.size());
                log.accept("   · 例如 " + String.join("、",
                        sample.subList(0, show).stream()
                                .map(s -> s.substring(s.lastIndexOf('/') + 1)).toList())
                        + (sample.size() > show ? "…" : ""));
            }
        }
        return out;
    }

    /** 把 {@code @Mixin} 的目标写法统一成内部名（与 {@link #addTarget} 同一套规则）。 */
    private static String normalizeTarget(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        if (v.startsWith("L") && v.endsWith(";")) {
            v = v.substring(1, v.length() - 1);
        } else {
            v = v.replace('.', '/');
        }
        return v;
    }

    private static void addTarget(java.util.Set<String> out, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String v = value.trim();
        if (v.startsWith("L") && v.endsWith(";")) {
            v = v.substring(1, v.length() - 1);          
        } else {
            v = v.replace('.', '/');
        }
        out.add(v);
    }

    private static java.util.Set<String> names(Path jar) {
        java.util.Set<String> out = new java.util.HashSet<>(16384);
        try (FastZip zip = FastZip.open(jar)) {
            for (FastZip.Entry e : zip.entries()) {
                if (e.isClass() && !e.isDirectory()) {
                    out.add(e.name().substring(0, e.name().length() - ".class".length()));
                }
            }
        } catch (Throwable ignored) {
            return out;
        }
        return out;
    }

    private static boolean matches(String[] prefixes, String name) {
        for (String p : prefixes) {
            if (!p.isEmpty() && name.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 收集一个补丁类在头部（超类 + 直接接口）里用到的"对方平台"类型。
     *
     * <p><b>只收接口，坚决不收普通类。</b>这一条是实测踩出来的，不是谨慎起见：
     * 模组平台的 Mixin 有一条硬规则 —— <b>目标类一旦被加载就不能再注入</b>。
     * 而"预热"这个动作本身就是 {@code Class.forName}，等于替模组把它的注入目标提前加载了。
     *
     * <p>实测事故：{@code yumi_mc_core} 的 {@code DeferredRegisterMixin} 要注入
     * {@code net.neoforged.neoforge.registries.DeferredRegister}，而我们把
     * {@code DeferredRegister} 当成"补丁类头部要解析的对方类型"预热掉了，于是启动崩在：
     *
     * <pre>
     * MixinTargetAlreadyLoadedException: Critical problem:
     *   yumi_mc_core.neoforge.mixins.json:DeferredRegisterMixin from mod yumi_mc_core
     *   target net.neoforged.neoforge.registries.DeferredRegister was loaded too early.
     * </pre>
     *
     * <p>为什么会收进 {@code DeferredRegister}：它是 {@code Registry}/{@code MappedRegistry}
     * 的<b>超类链</b>上出现的类型，而超类是要在类定义时解析的。但"要解析"和"由我们提前加载"
     * 是两件事 —— 超类完全可以交给 JVM 在真正需要时按正常顺序解析。真正会以
     * {@code NoClassDefFoundError} 收场的是<b>接口</b>：接口的方法签名会反过来引用宿主类，
     * 形成"边定义边解析"的循环。所以这里只留接口。
     */
    private static void collectForeignHeaderTypes(ClassFile cf, java.util.Set<String> out) {
        for (String i : cf.interfaces()) {
            if (isForeign(i)) {
                out.add(i);
            }
        }
    }

    /** 是不是对方平台自己的类型（NeoForge / Forge）。 */
    private static boolean isForeign(String internal) {
        return internal != null
                && (internal.startsWith("net/neoforged/")
                        || internal.startsWith("net/minecraftforge/"));
    }

    public static java.util.List<String> warmUpTypes() {
        String raw = System.getProperty(PROP_TYPES, "");
        if (raw.isBlank()) {
            return java.util.List.of();
        }
        java.util.List<String> out = new ArrayList<>(8);
        for (String part : raw.split(",")) {
            String s = part.trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    private static boolean isSignature(String name) {
        if (!name.startsWith("META-INF/")) {
            return false;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA")
                || upper.endsWith(".EC") || upper.contains("SIG-");
    }

    public static int foreignModCount(List<Path> modJars) {
        if (modJars == null || modJars.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (Path jar : modJars) {
            if (jar == null || !Files.isRegularFile(jar)) {
                continue;
            }
            try (FastZip zip = FastZip.open(jar)) {
                if (zip.entry("META-INF/neoforge.mods.toml") != null
                        || zip.entry("META-INF/mods.toml") != null) {
                    n++;
                }
            } catch (Throwable ignored) {

            }
        }
        return n;
    }

    /**
     * 找本机同版本的"补丁原版 jar"。
     *
     * <p>它不在版本定义里（是对方平台自己的引导器直接起的），所以按目录规律找：
     * NeoForge 是 {@code libraries/net/neoforged/minecraft-client-patched/<版本>/}。
     * 只在与当前 {@code mcKey}（例如 {@code 26.3}）同小版本的目录里挑，
     * 跨小版本借基座比不借还糟。
     */
    public static Path locate(McInstance instance, String platform, String mcKey) {
        String group = "neoforge".equals(platform)
                ? "net/neoforged/minecraft-client-patched"
                : "net/neoforged/minecraft-client-patched";
        Path dir = instance.librariesDir().resolve(group);
        if (!Files.isDirectory(dir)) {
            return null;
        }
        List<Path> hits = new ArrayList<>(4);
        try (DirectoryStream<Path> versions = Files.newDirectoryStream(dir)) {
            for (Path v : versions) {
                if (!Files.isDirectory(v)) {
                    continue;
                }
                String name = v.getFileName().toString();
                if (mcKey != null && !mcKey.isBlank() && !name.startsWith(mcKey + ".")
                        && !name.startsWith(mcKey)) {
                    continue;
                }
                try (DirectoryStream<Path> files =
                             Files.newDirectoryStream(v, "*.jar")) {
                    for (Path f : files) {
                        String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (n.contains("patched")) {
                            hits.add(f);
                        }
                    }
                }
            }
        } catch (IOException e) {
            return null;
        }
        hits.sort(Comparator.comparing(p -> p.getParent().getFileName().toString()));
        return hits.isEmpty() ? null : hits.get(hits.size() - 1);
    }

    /** 版本定义里的 Minecraft 版本键（例如 {@code 26.3-Fabric 0.19.5} → {@code 26.3}）。 */
    public static String mcKeyOf(String versionId) {
        if (versionId == null) {
            return "";
        }
        String s = versionId.trim();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '-' || c == '_' || c == ' ') {
                return s.substring(0, i);
            }
        }
        return s;
    }
}
