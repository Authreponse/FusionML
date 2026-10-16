package com.fusionml.runtime;

import com.fusionml.loader.ModClassIndex;
import com.fusionml.loader.ModClassLoader;
import com.fusionml.loader.TransformPipeline;
import com.fusionml.meta.Dependency;
import com.fusionml.meta.MetadataReader;
import com.fusionml.meta.ModFormat;
import com.fusionml.meta.ModMetadata;
import com.fusionml.mixin.MixinScanner;
import com.fusionml.platform.FabricPlatformAdapter;
import com.fusionml.platform.ForgePlatformAdapter;
import com.fusionml.platform.NeoForgePlatformAdapter;
import com.fusionml.platform.PlatformAdapter;
import com.fusionml.resolve.ModGraph;
import com.fusionml.util.FastZip;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 加载器运行时：把"扫描 → 解析 → 索引 → 注入 → 注册"串成一条流水线。
 *
 * <p>三条贯穿全局的原则：
 * <ol>
 *   <li><b>能并行的地方一律并行</b>，且并行粒度按"实际工作量"切，而不是按文件数切；</li>
 *   <li><b>每个阶段独立计时</b>，让不同机器上都能看出瓶颈在哪；</li>
 *   <li><b>问题逐条记录、一次性汇报</b>，绝不"某个模组悄悄没加载"。</li>
 * </ol>
 */
public final class FusionRuntime {

    /** 加载选项。 */
    public static final class Options {
        public Path modsDir;
        /** 是否客户端侧（影响 side=CLIENT/SERVER 的依赖判定）。 */
        public boolean client = true;
        /** 是否真正执行实例化注册。基准测试里关掉它，可以只测"加载与索引"这一段。 */
        public boolean registerMods = true;
        /** 由加载器/游戏自身提供的依赖及其版本。 */
        public Map<String, String> provided = defaultProvided();
        /** 工作线程数；0 表示按 CPU 拓扑自动决定。 */
        public int workers;
        /** 是否建立类索引（基准测试可以用它单独度量索引阶段）。 */
        public boolean buildIndex = true;
        /** 是否在加载后对所有注入目标做一次"加载即校验"的自检。 */
        public boolean verifyInjections = true;
        /** 直接传入一个早期窗口实例（编程方式自定义）。为 null 时按 id/SPI 自动选择。 */
        public com.fusionml.earlywindow.EarlyWindow earlyWindow;
        /** 早期窗口实现 id 或类全名；空表示自动选择。 */
        public String earlyWindowChoice = "";
        /** 早期窗口外观配置；为 null 时从系统属性与配置文件装载。 */
        public com.fusionml.earlywindow.EarlyWindowConfig earlyWindowConfig;
        /** 是否关闭早期窗口（服务器/基准测试里通常关掉）。 */
        public boolean earlyWindowEnabled = true;
        /** 是否解析外层模组包内嵌的嵌套 jar（Fabric API 等依赖它）。 */
        public boolean nestedJars = true;
        /** 嵌套 jar 的提取缓存目录；为 null 时自动决定。 */
        public Path nestedCacheDir;

        public Options modsDir(Path p) {
            this.modsDir = p;
            return this;
        }

        public Options client(boolean v) {
            this.client = v;
            return this;
        }

        public Options registerMods(boolean v) {
            this.registerMods = v;
            return this;
        }

        public Options provide(String id, String version) {
            this.provided.put(id, version);
            return this;
        }

        public Options workers(int n) {
            this.workers = n;
            return this;
        }

        public Options verifyInjections(boolean v) {
            this.verifyInjections = v;
            return this;
        }

        public Options earlyWindow(com.fusionml.earlywindow.EarlyWindow w) {
            this.earlyWindow = w;
            return this;
        }

        public Options earlyWindowChoice(String id) {
            this.earlyWindowChoice = id;
            return this;
        }

        public Options earlyWindowEnabled(boolean v) {
            this.earlyWindowEnabled = v;
            return this;
        }
    }

    /** 一次完整加载会话的产物。 */
    public static final class Session implements AutoCloseable {
        public final CpuTopology.Info cpu;
        public final FusionPool pool;
        public final StageProfiler profiler;
        public final List<ModMetadata> mods;
        public final ModGraph.Plan plan;
        public final ModClassIndex.Result index;
        public final ModClassLoader loader;
        public final TransformPipeline pipeline;
        public final List<String> warnings;
        public final List<String> registration;
        public final Map<ModFormat, Integer> modsByFormat;
        public final long jarCount;
        public final long totalJarBytes;
        /**
         * 本次加载实际使用的模组包顺序。
         *
         * <p>必须有这个字段，不能让调用方自己再扫一遍 mods 目录：
         * 这里的顺序是按包体大小排序过的（大包优先，缩短长尾），
         * 而 {@link ModMetadata#jarIndex()} 指的就是这个顺序的下标。
         * 外面按目录顺序去取，拿到的会是**另一个包**——这个错很隐蔽，
         * 表现为"跨端模组登记成了完全不相干的几个文件"。
         */
        public final List<Path> jarPaths;
        public final com.fusionml.earlywindow.EarlyWindow earlyWindow;

        Session(CpuTopology.Info cpu, FusionPool pool, StageProfiler profiler,
                List<ModMetadata> mods, ModGraph.Plan plan, ModClassIndex.Result index,
                ModClassLoader loader, TransformPipeline pipeline, List<String> warnings,
                List<String> registration, Map<ModFormat, Integer> modsByFormat,
                long jarCount, long totalJarBytes, List<Path> jarPaths,
                com.fusionml.earlywindow.EarlyWindow earlyWindow) {
            this.cpu = cpu;
            this.pool = pool;
            this.profiler = profiler;
            this.mods = mods;
            this.plan = plan;
            this.index = index;
            this.loader = loader;
            this.pipeline = pipeline;
            this.warnings = warnings;
            this.registration = registration;
            this.modsByFormat = modsByFormat;
            this.jarCount = jarCount;
            this.totalJarBytes = totalJarBytes;
            this.jarPaths = jarPaths;
            this.earlyWindow = earlyWindow;
        }

        /** 面向玩家的可读报告。 */
        public String report() {
            StringBuilder sb = new StringBuilder(2048);
            sb.append("======== FusionML 加载报告 ========").append(System.lineSeparator());
            sb.append("CPU 拓扑      : ").append(cpu).append(System.lineSeparator());
            sb.append("并行度        : ").append(pool.workers()).append(" 个工作线程（另有调用方参与）")
                    .append(System.lineSeparator());
            sb.append("模组文件      : ").append(jarCount).append(" 个，共 ")
                    .append(String.format(Locale.ROOT, "%.1f MB", totalJarBytes / 1024.0 / 1024.0))
                    .append(System.lineSeparator());
            sb.append("模组数量      : ").append(mods.size()).append(" 个");
            if (!modsByFormat.isEmpty()) {
                sb.append("（");
                boolean first = true;
                for (Map.Entry<ModFormat, Integer> e : modsByFormat.entrySet()) {
                    if (!first) {
                        sb.append("、");
                    }
                    first = false;
                    sb.append(e.getKey().displayName()).append(' ').append(e.getValue());
                }
                sb.append("）");
            }
            sb.append(System.lineSeparator());
            sb.append("类总数        : ").append(index.totalClasses()).append(" 个")
                    .append(System.lineSeparator());
            sb.append("注入规则      : ").append(pipeline.hookRuleCount()).append(" 条，")
                    .append(pipeline.stats()).append(System.lineSeparator());
            sb.append("加载层次      : ").append(plan.depth()).append(" 层（同层并行、层间有序）")
                    .append(System.lineSeparator());

            if (!plan.problems.isEmpty()) {
                sb.append(System.lineSeparator()).append("---- 依赖与冲突检查 ----")
                        .append(System.lineSeparator());
                for (String line : ModGraph.summarize(plan.problems, 2)) {
                    sb.append("  ").append(line).append(System.lineSeparator());
                }
            }
            if (!warnings.isEmpty()) {
                sb.append(System.lineSeparator()).append("---- 提示 ----").append(System.lineSeparator());
                List<String> grouped = groupWarnings(warnings);
                int shown = 0;
                for (String w : grouped) {
                    if (shown++ >= 40) {
                        sb.append("  ...（另有 ").append(grouped.size() - 40).append(" 条提示）")
                                .append(System.lineSeparator());
                        break;
                    }
                    sb.append("  · ").append(w).append(System.lineSeparator());
                }
            }
            if (!registration.isEmpty()) {
                sb.append(System.lineSeparator()).append("---- 三端原生注册 ----")
                        .append(System.lineSeparator());
                for (String r : registration) {
                    sb.append("  ").append(r).append(System.lineSeparator());
                }
            }
            sb.append(System.lineSeparator()).append(profiler.report());
            sb.append(System.lineSeparator()).append("线程池统计    : ").append(pool.stats())
                    .append(System.lineSeparator());
            sb.append("类加载统计    : 已加载 ").append(loader.stats().classesLoaded.get())
                    .append(" 个类，累计 ").append(loader.stats().bytesRead.get() / 1024)
                    .append(" KB，改写 ").append(loader.stats().transformed.get()).append(" 个")
                    .append(System.lineSeparator());
            return sb.toString();
        }

        @Override
        public void close() {
            loader.close();
            pool.close();
        }
    }

    private FusionRuntime() {
    }

    public static Map<String, String> defaultProvided() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("minecraft", "1.21");
        m.put("java", System.getProperty("java.version"));
        m.put("fabricloader", "0.15.11");
        m.put("fabric-api", "0.100.0");
        m.put("fabric", "0.15.11");
        m.put("forge", "52.0.0");
        m.put("neoforge", "21.0.0");
        return m;
    }

    public static Session load(Options options) throws IOException {
        StageProfiler profiler = new StageProfiler();
        // 早期窗口：在整个加载过程中给玩家可见的进度与状态。
        com.fusionml.earlywindow.EarlyWindowConfig ewConfig = options.earlyWindowConfig;
        if (ewConfig == null) {
            ewConfig = com.fusionml.earlywindow.EarlyWindowConfig.load();
            if (!options.earlyWindowEnabled) {
                ewConfig.enabled = false;
            }
        }
        com.fusionml.earlywindow.EarlyWindow window = options.earlyWindow != null
                ? options.earlyWindow
                : com.fusionml.earlywindow.EarlyWindows.create(ewConfig, options.earlyWindowChoice);
        try {
            window.open();
        } catch (Throwable t) {
            // 早期窗口起不来绝不能中断加载本身。
            window = com.fusionml.earlywindow.EarlyWindows.none();
        }
        final com.fusionml.earlywindow.EarlyWindow ew = window;
        final java.util.concurrent.atomic.AtomicInteger loggedWarnings = new java.util.concurrent.atomic.AtomicInteger();
        List<String> warnings = Collections.synchronizedList(new ArrayList<>());

        ew.stage("探测运行环境", 0.02);
        profiler.begin();
        CpuTopology.Info cpu = CpuTopology.detect();
        profiler.end("1 CPU 拓扑探测");
        ew.log("运行环境：" + cpu);

        int workerCount = options.workers > 0 ? options.workers : cpu.computeWorkers();
        FusionPool pool = FusionPool.create("fusion", workerCount);
        ew.log("并行度：" + workerCount + " 个工作线程（调用方同时参与计算）");

        // 启动时自动补齐标准游戏目录结构：只在"确实是一个游戏目录"时才动手，
        // 且只创建缺失的目录，绝不改动已有文件。
        com.fusionml.install.InstanceLayout.EnsureResult layout =
                com.fusionml.install.InstanceLayout.ensureForModsDir(options.modsDir);
        if (!layout.createdDirs().isEmpty()) {
            warnings.add("已自动补齐标准游戏目录：" + layout.summary()
                    + "（位于 " + layout.gameDir().toAbsolutePath() + "）");
        }

        // 2) 扫描目录
        ew.stage("扫描模组目录", 0.05);
        profiler.begin();
        List<Path> found = listJars(options.modsDir);
        // 先并行把大小取出来缓存住，再排序。
        // 反面教材是 `sort(comparingLong(Files::size))` —— 那会在比较器里反复做系统调用，
        // 文件数一多就变成 O(n log n) 次 IO，白白拖慢第一个阶段。
        final long[] sizes = new long[found.size()];
        pool.parallelFor(0, found.size(), 64, i -> sizes[i] = safeSize(found.get(i)));
        Integer[] order = new Integer[found.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        // 大包优先：让最长的那条任务尽早开始，缩短整体长尾（LPT 调度思想）。
        java.util.Arrays.sort(order, (a, b) -> Long.compare(sizes[b], sizes[a]));
        List<Path> jarPaths = new ArrayList<>(found.size());
        for (int i : order) {
            jarPaths.add(found.get(i));
        }
        profiler.end("2 扫描模组目录");
        ew.stage("解析三端元数据", 0.12);
        ew.log("发现 " + jarPaths.size() + " 个模组包");

        // 3) 并行打开 jar 并读取元数据
        profiler.begin();
        List<FastZip> jars = new ArrayList<>(jarPaths.size());
        for (Path p : jarPaths) {
            try {
                FastZip zip = FastZip.open(p);
                jars.add(zip);
                for (String dup : zip.duplicateNames()) {
                    warnings.add(p.getFileName() + "：包内存在重名条目 " + dup
                            + "（打包异常，读取时会取最后一个，建议重新打包）");
                }
            } catch (IOException e) {
                warnings.add(p.getFileName() + "：无法作为 ZIP 打开（" + e.getMessage() + "）");
            }
        }
        // 3.5) Jar-in-Jar：把外层包里内嵌的模组包解出来并一并纳入。
        //      不做这一步，Fabric API 这类"打包了几百个子模块"的模组会被判成缺依赖。
        //      提取结果按内容哈希缓存，因此父进程（构建启动计划时）已经解过的那份会直接命中。
        if (options.nestedJars) {
            List<String> nestedNotes = new ArrayList<>(4);
            Path cacheDir = options.nestedCacheDir != null
                    ? options.nestedCacheDir
                    : com.fusionml.loader.NestedJars.defaultCacheDir(options.modsDir);
            List<Path> outerPaths = new ArrayList<>(jarPaths);
            com.fusionml.loader.NestedJars.Result nested =
                    com.fusionml.loader.NestedJars.extract(outerPaths, cacheDir, nestedNotes);
            for (Path p : nested.jars()) {
                try {
                    FastZip zip = FastZip.open(p);
                    jars.add(zip);
                    jarPaths.add(p);
                } catch (IOException e) {
                    warnings.add("嵌套包无法打开：" + p.getFileName() + " —— " + e.getMessage());
                }
            }
            for (String n : nestedNotes) {
                warnings.add(n);
            }
            if (!nested.jars().isEmpty()) {
                warnings.add("已解析嵌套模组包：" + nested.summary());
            }
        }
        MetadataReader.Result[] metaResults = new MetadataReader.Result[jars.size()];
        AtomicInteger jarFailures = new AtomicInteger();
        final int outerJarCount = jarPaths.size() - countNested(jars, jarPaths);
        pool.parallelFor(0, jars.size(), 1, i -> {
            try {
                // 外层包与嵌套包要区分开：冲突时优先级不同（见 ModMetadata.nested）。
                metaResults[i] = MetadataReader.read(jars.get(i), i, i >= outerJarCount);
                for (String w : metaResults[i].warnings()) {
                    warnings.add(w);
                }
            } catch (IOException | RuntimeException e) {
                jarFailures.incrementAndGet();
                warnings.add(jars.get(i).path().getFileName() + "：读取元数据失败 —— " + e.getMessage());
            }
        });
        List<ModMetadata> mods = new ArrayList<>();
        for (MetadataReader.Result r : metaResults) {
            if (r != null) {
                mods.addAll(r.mods());
            }
        }
        profiler.end("3 解析三端元数据");
        flushWarnings(ew, warnings, loggedWarnings);
        ew.stage("解析依赖并排序", 0.45);
        ew.log("已识别 " + mods.size() + " 条模组元数据");

        // 4) 依赖解析
        profiler.begin();
        ModGraph.Plan plan = ModGraph.compute(mods, options.client, options.provided);
        profiler.end("4 依赖解析与分层");
        if (plan.fatal()) {
            ew.log("发现 " + plan.fatalCount() + " 个必须解决的问题（详见最终报告）");
        }
        ew.stage("建立全量类索引", 0.50);

        // 5) 类索引
        profiler.begin();
        ModClassIndex.Result index = ModClassIndex.build(jars, mods, pool, cpu);
        for (String w : index.warnings()) {
            warnings.add(w);
        }
        profiler.end("5 建立全量类索引");
        flushWarnings(ew, warnings, loggedWarnings);
        ew.log("已索引 " + index.totalClasses() + " 个类，构成 " + plan.depth() + " 层加载顺序");
        ew.stage("收集注入规则", 0.88);

        // 6) 注入规则
        profiler.begin();
        TransformPipeline pipeline = new TransformPipeline();
        List<String> mixinNotes = Collections.synchronizedList(new ArrayList<>());
        int rules = MixinScanner.collect(jars, pipeline, mixinNotes);
        for (String n : mixinNotes) {
            warnings.add(n);
        }
        profiler.end("6 收集注入规则");
        if (rules > 0) {
            ew.log("登记 " + rules + " 条原生注入规则");
        }
        ew.stage("执行三端原生注册", 0.90);

        // 7) 分层注册
        ModClassLoader loader = new ModClassLoader(
                FusionRuntime.class.getClassLoader(), jars, index.byName(), pipeline);
        List<String> registration = Collections.synchronizedList(new ArrayList<>());
        if (options.registerMods) {
            profiler.begin();
            Map<ModFormat, PlatformAdapter> adapters = adapters();
            for (List<ModMetadata> tier : plan.tiers) {
                // 同一层内没有相互依赖，可以放心并行；层间严格串行保证顺序语义。
                pool.parallelFor(0, tier.size(), 1, i -> {
                    ModMetadata mod = tier.get(i);
                    // 多端同包的模组，每一种端都要各跑一次自己那套原生注册流程。
                    java.util.List<ModFormat> formats = new ArrayList<>(2);
                    formats.add(mod.format());
                    formats.addAll(mod.alternativeFormats);
                    for (ModFormat format : formats) {
                        PlatformAdapter adapter = adapters.get(format);
                        if (adapter == null) {
                            registration.add(mod.id + " → 未知格式 " + format);
                            continue;
                        }
                        if (!adapter.expectsRegistration(mod)) {
                            // 纯库模组：没有入口点是正常的，安静跳过，不产生告警。
                            continue;
                        }
                        try {
                            List<String> mains = adapter.locateMainClasses(mod, index, warnings);
                            if (mains.isEmpty()) {
                                registration.add(mod.jarName() + "（" + format.displayName()
                                        + "）→ 未定位到主类");
                                continue;
                            }
                            for (String line : adapter.register(mod, mains, loader,
                                    options.client, warnings)) {
                                registration.add(format.displayName() + " | " + line);
                            }
                        } catch (Throwable t) {
                            registration.add(format.displayName() + " | " + mod.id
                                    + " → 注册流程异常：" + t);
                        }
                    }
                });
            }
            profiler.end("7 三端原生注册");
        }

        // 8) 注入自检：把每一个被注入的目标类真正加载并初始化一次。
        //    这一步的价值很高：类初始化会强制 JVM 完成链接与字节码校验，
        //    因此"改写后的类是否合法"不再依赖我们的自信，而是由 JVM 本体给出结论。
        if (options.verifyInjections && pipeline.hookRuleCount() > 0) {
            ew.stage("注入自检", 0.97);
            profiler.begin();
            int ok = 0;
            int failed = 0;
            for (String target : pipeline.hookTargets()) {
                if (!index.byName().containsKey(target)) {
                    // 目标是游戏/其它前置里的类，当前环境不存在，属于正常情况。
                    continue;
                }
                try {
                    Class.forName(ModClassIndex.dotted(target), true, loader);
                    ok++;
                } catch (Throwable t) {
                    failed++;
                    registration.add("注入自检失败：" + ModClassIndex.dotted(target)
                            + " → " + t.getClass().getSimpleName()
                            + (t.getMessage() == null ? "" : "：" + t.getMessage()));
                }
            }
            registration.add("注入自检：成功加载并通过 JVM 校验 " + ok + " 个目标类"
                    + (failed > 0 ? "，失败 " + failed + " 个" : "")
                    + "；本次共改写 " + pipeline.stats().transformed + " 个类、注入 "
                    + pipeline.stats().injectedMethods + " 个方法");
            profiler.end("8 注入自检");
        }

        Map<ModFormat, Integer> byFormat = new EnumMap<>(ModFormat.class);
        for (ModMetadata m : mods) {
            byFormat.merge(m.format(), 1, Integer::sum);
        }
        long totalBytes = 0;
        for (FastZip z : jars) {
            totalBytes += z.fileSize();
        }

        String summary = "加载完成：" + mods.size() + " 个模组（" + byFormat.size() + " 种平台）、"
                + index.totalClasses() + " 个类，共 " + plan.depth() + " 层；耗时 "
                + String.format(Locale.ROOT, "%.0f", profiler.totalMillis()) + " ms"
                + (plan.fatal() ? "，有 " + plan.fatalCount() + " 个需要处理的问题" : "，无阻塞问题");
        flushWarnings(ew, warnings, loggedWarnings);
        try {
            ew.finish(summary);
        } catch (Throwable ignored) {
            // 收尾失败不影响加载结果。
        }

        return new Session(cpu, pool, profiler, mods, plan, index, loader, pipeline,
                new ArrayList<>(warnings), new ArrayList<>(registration), byFormat,
                jars.size(), totalBytes, new ArrayList<>(jarPaths), ew);
    }

    /** 统计 jar 列表里有多少个是嵌套包（用于区分外层与内层元数据）。 */
    private static int countNested(List<FastZip> jars, List<Path> paths) {
        // jarPaths 的后半段是嵌套包：它们都在嵌套缓存目录下。
        int nested = 0;
        for (int i = 0; i < paths.size(); i++) {
            Path parent = paths.get(i).getParent();
            if (parent != null && parent.getFileName() != null
                    && parent.getFileName().toString().equals("nested-jars")) {
                nested++;
            }
        }
        return nested;
    }

    private static Map<ModFormat, PlatformAdapter> adapters() {
        Map<ModFormat, PlatformAdapter> m = new EnumMap<>(ModFormat.class);
        m.put(ModFormat.FABRIC, new FabricPlatformAdapter());
        m.put(ModFormat.FORGE, new ForgePlatformAdapter());
        m.put(ModFormat.NEOFORGE, new NeoForgePlatformAdapter());
        return m;
    }

    /** 列出目录下的模组包（.jar / .zip），并跳过明显的禁用文件与临时文件。 */
    public static List<Path> listJars(Path dir) throws IOException {
        List<Path> out = new ArrayList<>(64);
        if (dir == null || !Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.endsWith(".jar") && !name.endsWith(".zip")) {
                    continue;
                }
                if (name.endsWith(".jar.disabled") || name.startsWith(".")) {
                    continue;
                }
                out.add(p);
            }
        }
        return out;
    }

    private static long safeSize(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * 合并重复提示。
     *
     * <p>整合包里"同一个问题在 200 个模组上各出现一次"是常态。逐条打印会把真正重要的
     * 那一两条淹没掉，所以这里把完全相同的文本合并成一条，并标出出现次数 ——
     * 按最早出现顺序保留，信息量不减，噪声大幅下降。
     */
    public static List<String> groupWarnings(List<String> raw) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String w : raw) {
            counts.merge(w, 1, Integer::sum);
        }
        List<String> out = new ArrayList<>(counts.size());
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > 1) {
                out.add(e.getKey() + "（共 " + e.getValue() + " 处）");
            } else {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /**
     * 把新产生的提示滚进早期窗口。
     *
     * <p>用"已投递下标"做增量：这样窗口里出现的始终是最新的几条，
     * 而且几百条同质提示不会把窗口刷爆。
     */
    private static void flushWarnings(com.fusionml.earlywindow.EarlyWindow ew, List<String> warnings,
                                      java.util.concurrent.atomic.AtomicInteger cursor) {
        int size;
        synchronized (warnings) {
            size = warnings.size();
        }
        int from = cursor.get();
        if (size <= from) {
            return;
        }
        int to = Math.min(size, from + 6);
        for (int i = from; i < to; i++) {
            try {
                ew.log(warnings.get(i));
            } catch (Throwable ignored) {
                return;
            }
        }
        cursor.set(to);
    }

    /** 依赖的展示辅助（给界面用）。 */
    public static String describe(Dependency d) {
        return d.toString();
    }
}
