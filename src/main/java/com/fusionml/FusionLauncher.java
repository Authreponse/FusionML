package com.fusionml;

import com.fusionml.hud.FpsMonitor;
import com.fusionml.hud.HudPreviewWindow;
import com.fusionml.hud.HudRenderer;
import com.fusionml.hud.ModListModel;
import com.fusionml.meta.ModFormat;
import com.fusionml.runtime.FusionRuntime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * FusionML 命令行入口。
 *
 * <p>提供四类能力：
 * <ul>
 *   <li>{@code 报告}：完整加载一次并打出可读报告（默认行为）；</li>
 *   <li>{@code 列表}：以表格形式查看模组列表，支持搜索/过滤/排序（与 HUD 用同一套逻辑）；</li>
 *   <li>{@code 截图}：把 FPS 面板与模组列表渲染成 PNG，无图形界面环境也能跑；</li>
 *   <li>{@code 预览}：打开实时窗口，可搜索、过滤、排序、滚动、导出。</li>
 * </ul>
 */
public final class FusionLauncher {

    public static void main(String[] args) {
        Options o = new Options();
        try {
            if (!parse(args, o)) {
                return;
            }
            if (o.mcDir != null) {
                runMcMode(o);
                return;
            }
            Path mods = Path.of(o.modsDir);
            if (!Files.isDirectory(mods)) {
                System.out.println("模组目录不存在：" + mods.toAbsolutePath());
                System.out.println("用 --mods <目录> 指定模组目录（目录里放 .jar 模组包）。");
                return;
            }

            FusionRuntime.Options ro = new FusionRuntime.Options()
                    .modsDir(mods)
                    .client(!o.server)
                    .registerMods(!o.noRegister)
                    .workers(o.workers)
                    .earlyWindowChoice(o.earlyWindowChoice);

            long t0 = System.nanoTime();
            if (o.earlyWindowConfig == null) {
                o.earlyWindowConfig = com.fusionml.earlywindow.EarlyWindowConfig.load();
            }
            if (!o.earlyWindowEnabled) {
                o.earlyWindowConfig.enabled = false;
            }
            // 截图模式下不要把窗口真的弹出来 —— 截图是离屏渲染的，弹窗只会干扰。
            if (o.shot != null || o.shotEarlyWindow != null) {
                o.earlyWindowConfig.enabled = false;
            }
            ro.earlyWindowConfig = o.earlyWindowConfig;
            try (FusionRuntime.Session session = FusionRuntime.load(ro)) {
                double totalMs = (System.nanoTime() - t0) / 1e6;
                ModListModel model = ModListModel.from(session);

                if (o.shot != null) {
                    renderShot(o, session, model);
                    return;
                }
                if (o.shotEarlyWindow != null) {
                    renderEarlyWindowShot(o, session);
                    return;
                }
                if (o.openHud) {
                    new HudPreviewWindow(model).show();
                    System.out.println("已打开 HUD 预览窗口（关闭窗口即退出）。");
                    return;
                }
                if (o.list) {
                    System.out.println(model.toText(o.query, o.filter, o.sort, o.limit));
                    System.out.println();
                    System.out.printf(Locale.ROOT, "共 %d 个模组（%s），加载耗时 %.1f ms%n",
                            model.stats().total(), model.stats().formatLine(), totalMs);
                    return;
                }
                System.out.println(session.report());
            }
        } catch (Throwable t) {
            System.out.println("加载失败：" + t);
            if (o.verbose) {
                t.printStackTrace(System.out);
            }
        }
    }

    /**
     * 离屏渲染一张 HUD 截图。
     *
     * <p>帧率面板里的数字来自<b>真实测量</b>：这里把"渲染一帧 HUD"循环执行若干次，
     * 每次都把实际耗时喂给 {@link FpsMonitor}。因此截图上的曲线反映的是这台机器
     * 渲染这套 HUD 的真实帧时序，而不是随手编的数据。
     */
    private static void renderShot(Options o, FusionRuntime.Session session, ModListModel model)
            throws Exception {
        int width = 1100;
        int height = 620;
        FpsMonitor fps = new FpsMonitor(200);
        HudRenderer renderer = new HudRenderer();
        java.awt.image.BufferedImage img = null;
        int warmup = 40;
        int frames = 200;
        fps.reset();
        long clock = System.nanoTime();
        for (int i = 0; i < warmup + frames; i++) {
            long t0 = System.nanoTime();
            img = renderer.toImage(width, height, fps, model, o.query, o.filter, o.sort, true, 0);
            long dt = System.nanoTime() - t0;
            if (i >= warmup) {
                // 把每帧的真实渲染耗时累加成一条单调时间轴喂给采样器。
                clock += dt;
                fps.frame(clock);
            }
        }
        Path out = Path.of(o.shot);
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        javax.imageio.ImageIO.write(img, "png", out.toFile());
        System.out.println("已生成 HUD 截图：" + out.toAbsolutePath());
        System.out.printf(Locale.ROOT, "（面板数字来自真实测量：离屏渲染 %d 帧，平均 %.2f ms/帧 ≈ %.0f FPS）%n",
                frames, fps.avgMs(), fps.recentFps());
        System.out.printf(Locale.ROOT, "（列表内容来自本次加载：%d 个模组 / %d 个类）%n",
                model.stats().total(), model.stats().totalClasses());
    }

    /**
     * 离屏渲染早期窗口的一张"定妆照"。
     *
     * <p>内容和真实运行时一致（阶段名、进度、用时、日志尾行都取自本次真实加载），
     * 只是把窗口画到图片上，因此没有图形界面的机器也能核对外观。
     */
    private static void renderEarlyWindowShot(Options o, FusionRuntime.Session session)
            throws Exception {
        com.fusionml.earlywindow.EarlyWindowConfig cfg = o.earlyWindowConfig;
        String[] logs = new String[] {
                "运行环境：" + session.cpu,
                "并行度：" + session.pool.workers() + " 个工作线程（调用方同时参与计算）",
                "发现 " + session.jarCount + " 个模组包",
                "已识别 " + session.mods.size() + " 条模组元数据",
                "已索引 " + session.index.totalClasses() + " 个类，构成 " + session.plan.depth() + " 层加载顺序",
                "登记 " + session.pipeline.hookRuleCount() + " 条原生注入规则",
        };
        com.fusionml.earlywindow.SwingEarlyWindow.Frame frame =
                new com.fusionml.earlywindow.SwingEarlyWindow.Frame(0.62, "建立全量类索引",
                        "正在解析 com/example/mod 的 10846 个类…", logs, 0.42, false, "");
        java.awt.image.BufferedImage img = com.fusionml.earlywindow.SwingEarlyWindow
                .renderPreview(cfg, frame);
        Path out = Path.of(o.shotEarlyWindow);
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        javax.imageio.ImageIO.write(img, "png", out.toFile());
        System.out.println("已生成早期窗口截图：" + out.toAbsolutePath());
        System.out.println("外观由配置驱动，可改 " + com.fusionml.earlywindow.EarlyWindowConfig.CONFIG_PATH
                + " 或命令行 -Dfusion.ew.*；也可实现 EarlyWindow 接口整体替换。");
    }

    /**
     * "接入 Minecraft"模式：直接对着一个现成的游戏目录工作。
     *
     * <p>所有写操作都只落在<b>游戏目录内</b>（原生库、清单、配置），
     * 共享的 libraries/ 与 assets/ 只读复用，绝不改动。
     */
    private static void runMcMode(Options o) throws Exception {
        Path dir = Path.of(o.mcDir);
        if (!Files.isDirectory(dir)) {
            System.out.println("目录不存在：" + dir.toAbsolutePath());
            return;
        }
        com.fusionml.mc.McInstance instance = com.fusionml.mc.McInstance.open(dir);
        System.out.println("游戏根目录  : " + instance.root());
        System.out.println("游戏运行目录: " + instance.gameDir()
                + (instance.isolated() ? "（版本隔离布局）" : ""));

        java.util.List<String> versions = instance.versionIds();
        if (o.mcList) {
            System.out.println("可用版本（" + versions.size() + "）：");
            for (String v : versions) {
                System.out.println("  · " + v);
            }
            return;
        }
        if (versions.isEmpty()) {
            System.out.println("这个目录里没有找到任何版本定义（versions/<id>/<id>.json）。");
            return;
        }

        String versionId = o.mcVersion;
        // 指向某个版本目录时，就意味着"用这个版本"，不该再去自动挑一个。
        // 否则用户在 NeoForge 版本目录上执行，却跑起来的是 Fabric 版本 —— 很难察觉。
        if ((versionId == null || versionId.isBlank()) && instance.isolated()) {
            versionId = instance.gameDir().getFileName().toString();
            System.out.println("按目录名选用版本：" + versionId);
        }
        if (versionId == null || versionId.isBlank()) {
            versionId = pickVersion(versions);
            System.out.println("自动选择版本：" + versionId + "（可用 --mc-version 指定）");
        }
        if (!versions.contains(versionId)) {
            System.out.println("没有这个版本：" + versionId + "（用 --mc-list 查看可用版本）");
            return;
        }

        com.fusionml.mc.McInstance.Effective effective = instance.resolve(versionId);
        System.out.println("版本解析    : " + effective);
        System.out.println("Java 要求   : " + effective.javaMajor);
        System.out.println("依赖库      : " + instance.libraryStatus(effective).summary());
        System.out.println("资源目录    : " + (instance.hasAssets()
                ? "已就绪 " + instance.assetsDir() : "未找到 assets/"));
        var provided = com.fusionml.mc.ProvidedVersions.detect(instance, effective.clientJar,
                versionId);
        System.out.println("环境提供    : " + com.fusionml.mc.ProvidedVersions.describe(provided));

        Path loaderJar = selfJar();
        Path modsDir = instance.gameDir().resolve("mods");
        java.util.List<Path> modJars = new java.util.ArrayList<>();
        if (!o.skipMods && Files.isDirectory(modsDir)) {
            modJars.addAll(FusionRuntime.listJars(modsDir));
        }

        com.fusionml.launch.LaunchPlan.Options po = new com.fusionml.launch.LaunchPlan.Options();
        po.versionId = versionId;
        po.loaderJar = loaderJar;
        po.modJars = modJars;
        po.bootOnly = o.mcBoot;
        po.skipMods = o.skipMods;
        po.client = !o.server;
        po.maxMemoryMb = o.memoryMb;

        com.fusionml.launch.LaunchPlan.Plan plan =
                com.fusionml.launch.LaunchPlan.build(instance, po, System.out::println);
        System.out.println();
        System.out.println("---- 启动计划 ----");
        System.out.print(plan.describe());
        if (loaderJar == null) {
            System.out.println("警告：没有定位到加载器 jar，接管与注入将不可用"
                    + "（开发模式下请先运行 build.ps1）。");
        }
        System.out.println("模组目录    : " + modsDir + "（" + modJars.size() + " 个）");

        if (o.mcCheck) {
            System.out.println();
            System.out.println("（--mc-check：只检查不启动。加 --mc-boot 可做一次不弹窗的自检，"
                    + "加 --mc-launch 真正进入游戏。）");
            return;
        }
        if (o.clSelfTest) {
            runClassLoaderSelfTest(instance, effective, modJars);
            return;
        }
        if (o.mixinScan) {
            runMixinScan(modJars);
            return;
        }
        if (!o.mcBoot && !o.mcLaunch) {
            System.out.println();
            System.out.println("（未指定动作：--mc-check 只检查 / --mc-boot 自检不弹窗 / "
                    + "--mc-launch 进入游戏。）");
            return;
        }

        System.out.println();
        System.out.println(o.mcBoot ? "运行接管自检（不进入游戏）…" : "启动游戏…");
        Process p = com.fusionml.launch.LaunchPlan.launch(plan, true);
        int code = p.waitFor();
        System.out.println();
        System.out.println("进程结束，退出码：" + code);
    }

    /** 从现有版本里挑一个默认目标：优先 26.3，其次 26.x，最后取列表末尾。 */
    /**
     * 自有游戏类加载器的自检。
     *
     * <p>本方法较长但顺序与用意直接对应命名，便于对照。
     *
     * <p>验证的是"三端同进程"的第一块地基：我们能不能成为游戏类与模组类的**唯一定义者**。
     * 具体检查三件事——
     * <ol>
     *   <li>游戏主类能从我们的加载器里被定义出来（不是从父加载器"蹭"出来的）；</li>
     *   <li>模组类同样由我们定义（证明模组侧也进了同一个命名空间）；</li>
     *   <li>加载器侧（ASM / log4j / Mixin / 平台运行时）确实委派给了父加载器，
     *       即这些类**没有**被定义第二份——这正是当初 log4j 出 ClassCastException 的根因。</li>
     * </ol>
     */
    private static void runClassLoaderSelfTest(com.fusionml.mc.McInstance instance,
                                              com.fusionml.mc.McInstance.Effective effective,
                                              java.util.List<Path> modJars) throws Exception {
        java.util.List<Path> entries = new java.util.ArrayList<>();
        if (effective.clientJar != null) {
            entries.add(effective.clientJar);
        }
        for (com.fusionml.mc.McVersion.Library lib : effective.applicableLibraries()) {
            Path p = instance.localLibraryPath(lib);
            if (Files.isRegularFile(p)) {
                entries.add(p);
            }
        }
        entries.addAll(modJars);

        java.util.List<com.fusionml.util.FastZip> jars = new java.util.ArrayList<>();
        for (Path p : entries) {
            jars.add(com.fusionml.util.FastZip.open(p));
        }
        long t0 = System.nanoTime();
        java.util.List<String> notes = new java.util.ArrayList<>();
        java.util.Map<String, com.fusionml.loader.FusionGameClassLoader.Loc> index =
                com.fusionml.loader.FusionGameClassLoader.buildIndex(jars, notes);
        double indexMs = (System.nanoTime() - t0) / 1e6;
        System.out.println("类索引：" + index.size() + " 个类，来自 " + jars.size()
                + " 个条目，耗时 " + String.format(java.util.Locale.ROOT, "%.0f", indexMs) + " ms");
        System.out.println(com.fusionml.loader.DelegationPolicy.describe());

        try (com.fusionml.loader.FusionGameClassLoader loader =
                     new com.fusionml.loader.FusionGameClassLoader(
                             FusionLauncher.class.getClassLoader(), jars, index, null)) {
            check(loader, "net.minecraft.client.main.Main", true);
            check(loader, "net.minecraft.client.Minecraft", true);
            check(loader, "net.minecraft.world.item.Item", true);
            // 加载器侧的类必须来自父加载器（同一份）。
            check(loader, "org.objectweb.asm.ClassReader", false);
            check(loader, "org.apache.logging.log4j.LogManager", false);
            // 模组类（从 mods 目录里挑一个真实存在的）。
            for (Path mod : modJars) {
                String sample = firstClassOf(mod);
                if (sample != null && index.containsKey(sample)) {
                    check(loader, sample.replace('/', '.'), true);
                    break;
                }
            }
        }
    }

    private static void check(com.fusionml.loader.FusionGameClassLoader loader, String name,
                              boolean expectSelfDefined) {
        try {
            Class<?> c = loader.loadClass(name);
            boolean fromUs = c.getClassLoader() == loader;
            String who = fromUs ? "由我们定义" : "委派给父加载器";
            // 注意：加载器侧的类落地在哪一侧是**环境决定**的（先父后己）。
            // 真实游戏 JVM 里 asm/log4j 在 JVM 类路径上 → 委派；精简环境里没有 → 我们自己定义。
            // 所以这里只在"游戏/模组类被委派出去"时才报错——那才是真正的缺陷。
            boolean bad = !fromUs && expectSelfDefined;
            System.out.println((bad ? "  ✗ " : "  ✓ ") + name + " → " + who
                    + (bad ? "（游戏/模组类不应委派给父加载器）" : ""));
        } catch (Throwable t) {
            System.out.println("  ✗ " + name + " → 加载失败："
                    + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : "：" + t.getMessage()));
        }
    }

    /** 取一个 jar 里的第一个类名（内部名），用于模组类的取样。 */
    private static String firstClassOf(Path jar) {
        try (com.fusionml.util.FastZip zip = com.fusionml.util.FastZip.open(jar)) {
            for (com.fusionml.util.FastZip.Entry e : zip.entries()) {
                if (e.isClass() && !e.name().contains("$")) {
                    return e.name().substring(0, e.name().length() - ".class".length());
                }
            }
        } catch (Exception ignored) {
            // 忽略
        }
        return null;
    }

    /**
     * Mixin 配置清点。
     *
     * <p>这是"一个 Mixin 服务同时装三端配置"的第一步，也是最便宜的一步：
     * 先确认我们能把三端声明的配置**都找出来、都读得动**。
     * 做不到这一点，后面"装载配置"根本无从谈起 —— 而这一步只用我们自己的
     * 元数据解析器与 JSON 解析器就能验证，不需要碰 Mixin 内部。
     */
    private static void runMixinScan(java.util.List<Path> modJars) throws Exception {
        int jars = 0;
        int configs = 0;
        int mixinClasses = 0;
        int unreadable = 0;
        java.util.Map<String, Integer> byPlatform = new java.util.LinkedHashMap<>();
        java.util.List<String> failures = new java.util.ArrayList<>();
        for (Path jar : modJars) {
            jars++;
            try (com.fusionml.util.FastZip zip = com.fusionml.util.FastZip.open(jar)) {
                com.fusionml.meta.MetadataReader.Result r =
                        com.fusionml.meta.MetadataReader.read(zip, 0);
                for (com.fusionml.meta.ModMetadata m : r.mods()) {
                    byPlatform.merge(m.format().displayName(), 1, Integer::sum);
                    for (String cfg : m.mixinConfigs) {
                        configs++;
                        com.fusionml.util.FastZip.Entry entry = zip.entry(cfg);
                        if (entry == null) {
                            unreadable++;
                            if (failures.size() < 6) {
                                failures.add(jar.getFileName() + " 里找不到 " + cfg);
                            }
                            continue;
                        }
                        try {
                            java.util.Map<String, Object> root =
                                    com.fusionml.util.Json.parseObject(zip.read(entry));
                            for (String key : java.util.List.of("mixins", "client", "server")) {
                                mixinClasses += com.fusionml.util.Json.arr(root.get(key)).size();
                            }
                        } catch (Exception e) {
                            unreadable++;
                            if (failures.size() < 6) {
                                failures.add(jar.getFileName() + " 的 " + cfg + " 读取失败：" + e);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("  （跳过 " + jar.getFileName() + "：" + e.getMessage() + "）");
            }
        }
        System.out.println("扫描模组包 " + jars + " 个，平台分布：" + byPlatform);
        System.out.println("mixin 配置 " + configs + " 个，声明混入类 " + mixinClasses
                + " 个，读取失败 " + unreadable + " 个");
        for (String f : failures) {
            System.out.println("  · " + f);
        }
        if (unreadable == 0 && configs > 0) {
            System.out.println("结论：三端声明的 mixin 配置都能被我们定位并解析 ——"
                    + " 满足交给同一个 Mixin 服务装载的前提条件。");
        }
    }

    private static String pickVersion(java.util.List<String> versions) {
        for (String v : versions) {
            if (v.contains("26.3") && !v.contains("Fusion")) {
                return v;
            }
        }
        for (String v : versions) {
            if (v.startsWith("26.") && !v.contains("Fusion")) {
                return v;
            }
        }
        return versions.get(versions.size() - 1);
    }

    /** 定位当前运行的这个加载器 jar，用于替换游戏主类并作为 javaagent。 */
    private static Path selfJar() {
        try {
            java.net.URL url = FusionLauncher.class.getProtectionDomain()
                    .getCodeSource().getLocation();
            Path p = Path.of(url.toURI());
            if (Files.isRegularFile(p)) {
                return p;
            }
        } catch (Exception ignored) {
            // 落到下面的兜底
        }
        Path fallback = Path.of("build", "fusion-ml.jar");
        return Files.isRegularFile(fallback) ? fallback.toAbsolutePath() : null;
    }

    private static final class Options {
        String modsDir = "mods";
        /** 指定后进入"接入 Minecraft"模式：直接操作一个现成的游戏目录。 */
        String mcDir;
        String mcVersion;
        boolean mcList;
        boolean mcCheck;
        boolean mcBoot;
        boolean mcLaunch;
        boolean clSelfTest;
        boolean mixinScan;
        boolean skipMods;
        int memoryMb = 4096;
        boolean list;
        boolean server;
        boolean noRegister;
        boolean openHud;
        boolean verbose;
        String shot;
        String shotEarlyWindow;
        String earlyWindowChoice = "";
        boolean earlyWindowEnabled = true;
        com.fusionml.earlywindow.EarlyWindowConfig earlyWindowConfig;
        String query = "";
        ModFormat filter;
        ModListModel.Sort sort = ModListModel.Sort.PROBLEM_FIRST;
        int limit = 60;
        int workers;
    }

    private static boolean parse(String[] args, Options o) throws java.io.IOException {
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--mods" -> o.modsDir = next(args, ++i, "目录");
                case "--mc-dir" -> o.mcDir = next(args, ++i, "游戏目录");
                case "--mc-version" -> o.mcVersion = next(args, ++i, "版本");
                case "--mc-list" -> {
                    o.mcDir = o.mcDir != null ? o.mcDir : ".";
                    o.mcList = true;
                }
                case "--mc-check" -> o.mcCheck = true;
                case "--cl-selftest" -> o.clSelfTest = true;
                case "--mixin-scan" -> o.mixinScan = true;
                case "--mc-boot" -> o.mcBoot = true;
                case "--mc-launch" -> o.mcLaunch = true;
                case "--skip-mods" -> o.skipMods = true;
                case "--memory" -> o.memoryMb = Integer.parseInt(next(args, ++i, "MB"));
                case "--list" -> o.list = true;
                case "--search" -> o.query = next(args, ++i, "关键词");
                case "--filter" -> o.filter = ModFormat.ofDisplayName(next(args, ++i, "平台"));
                case "--sort" -> o.sort = parseSort(next(args, ++i, "排序"));
                case "--limit" -> o.limit = Integer.parseInt(next(args, ++i, "条数"));
                case "--server" -> o.server = true;
                case "--no-register" -> o.noRegister = true;
                case "--hud" -> o.openHud = true;
                case "--shot" -> o.shot = next(args, ++i, "输出文件");
                case "--shot-ew" -> o.shotEarlyWindow = next(args, ++i, "输出文件");
                case "--early-window" -> o.earlyWindowChoice = next(args, ++i, "实现");
                case "--no-early-window" -> o.earlyWindowEnabled = false;
                case "--list-early-windows" -> {
                    System.out.println(com.fusionml.earlywindow.EarlyWindows.describeProviders());
                    return false;
                }
                case "--write-ew-config" -> {
                    Path cfgFile = Path.of(com.fusionml.earlywindow.EarlyWindowConfig.CONFIG_PATH);
                    if (cfgFile.getParent() != null) {
                        Files.createDirectories(cfgFile.getParent());
                    }
                    Files.writeString(cfgFile, com.fusionml.earlywindow.EarlyWindowConfig.template());
                    System.out.println("已写出早期窗口配置模板：" + cfgFile.toAbsolutePath());
                    return false;
                }
                case "--workers" -> o.workers = Integer.parseInt(next(args, ++i, "线程数"));
                case "--verbose", "-v" -> o.verbose = true;
                case "--help", "-h" -> {
                    printHelp();
                    return false;
                }
                default -> {
                    System.out.println("未知参数：" + a);
                    printHelp();
                    return false;
                }
            }
        }
        return true;
    }

    private static String next(String[] args, int i, String what) {
        if (i >= args.length) {
            throw new IllegalArgumentException("参数缺少取值：" + what);
        }
        return args[i];
    }

    private static ModListModel.Sort parseSort(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "tier", "层次" -> ModListModel.Sort.TIER;
            case "name", "名称" -> ModListModel.Sort.NAME;
            case "dependents", "被依赖" -> ModListModel.Sort.DEPENDENTS;
            default -> ModListModel.Sort.PROBLEM_FIRST;
        };
    }

    private static void printHelp() {
        System.out.println("""
                FusionML 加载器

               用法：
                 java -jar fusion-ml.jar [选项]

               选项：
                 --mods <目录>        模组目录，默认 ./mods
                ── 接入现有 Minecraft ──
                 --mc-dir <目录>       游戏目录（.minecraft 根目录，或某个版本目录都行）
                 --mc-list            列出该目录里可用的版本
                 --mc-version <名字>   指定用哪个版本，默认自动挑 26.3
                 --mc-check           只解析并打印启动计划，不启动（安全，先看它会干什么）
                 --mc-boot            启动到"接管 + 注入自检"就结束，不弹游戏窗口
                 --mc-launch          真正启动游戏
                 --skip-mods          本次不加载模组（排查"是不是模组导致"时用）
                 --memory <MB>        最大内存，默认 4096
                 --list               以表格列出模组（默认输出完整加载报告）
                 --search <关键词>     过滤模组（匹配名称 / modId / 版本）
                 --filter <平台>       只看某一端：Fabric / Forge / NeoForge
                 --sort <方式>         排序：problem（默认）/ tier / name / dependents
                 --limit <条数>        列表最多显示多少条，默认 60
                 --hud                打开实时 FPS + 模组列表窗口
                 --shot <文件.png>     离屏渲染一张 HUD 截图（无界面环境可用）
                 --shot-ew <文件.png>  离屏渲染一张早期窗口截图
                 --early-window <实现>  指定早期窗口：swing / console / 类全名 / off
                 --no-early-window    不显示早期窗口
                 --list-early-windows 列出所有可用的早期窗口实现
                 --write-ew-config    写出早期窗口配置模板到 config/ 供修改
                 --server             按服务端侧解析依赖（side=SERVER）
                 --no-register        只做加载与索引，不实例化模组主类
                 --workers <线程数>    手动指定并行度，默认按 CPU 拓扑自动决定
                 --verbose, -v        出错时打印详细堆栈
                 --help, -h           显示本帮助
                """);
    }
}
