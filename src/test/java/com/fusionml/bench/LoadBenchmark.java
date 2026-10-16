package com.fusionml.bench;

import com.fusionml.runtime.CpuTopology;
import com.fusionml.runtime.FusionRuntime;
import com.fusionml.testkit.ModPackFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 加载基准测试。
 *
 * <p>方法学（这决定了数字是否可信）：
 * <ul>
 *   <li>同一份夹具、同一套代码，唯一变量是"工作线程数"，因此测的是<b>并行化收益</b>，
 *       而不是"我们的代码比别人的代码快"这种无法验证的说法；</li>
 *   <li>每种配置跑多轮取中位数，并且冷热交替，抵消文件系统页缓存带来的偏差；</li>
 *   <li>分别报告总耗时与各阶段耗时，让"哪一段是瓶颈"一目了然；</li>
 *   <li>注册阶段默认关闭（没有游戏本体时实例化必然失败），可单独用 {@code --register} 打开。</li>
 * </ul>
 */
public final class LoadBenchmark {

    public static void main(String[] args) throws Exception {
        Path modsDir = Path.of("build", "testmods");
        int count = 120;
        int classes = 40;
        int repeat = 3;
        List<Integer> workerSweep = new ArrayList<>();
        boolean keep = false;
        boolean fresh = false;
        boolean register = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--mods" -> modsDir = Path.of(args[++i]);
                case "--count" -> count = Integer.parseInt(args[++i]);
                case "--classes" -> classes = Integer.parseInt(args[++i]);
                case "--repeat" -> repeat = Integer.parseInt(args[++i]);
                case "--workers" -> workerSweep = parseList(args[++i]);
                case "--keep" -> keep = true;
                case "--fresh" -> fresh = true;
                case "--register" -> register = true;
                default -> {
                    System.out.println("未知参数：" + args[i]);
                    return;
                }
            }
        }

        System.out.println("======== FusionML 加载基准 ========");
        java.lang.management.OperatingSystemMXBean os = java.lang.management.ManagementFactory
                .getOperatingSystemMXBean();
        System.out.println("JVM            : " + System.getProperty("java.version")
                + " / " + os.getArch());
        System.out.println("逻辑处理器     : " + Runtime.getRuntime().availableProcessors());

        if (fresh || !Files.isDirectory(modsDir) || LoadBench.countJars(modsDir) == 0) {
            ModPackFactory.Spec spec = new ModPackFactory.Spec();
            spec.modCount = count;
            spec.minClasses = Math.max(4, classes / 3);
            spec.maxClasses = classes * 2;
            System.out.println("生成夹具       : " + ModPackFactory.describe(spec));
            long t0 = System.nanoTime();
            ModPackFactory.generate(modsDir, spec);
            double genMs = (System.nanoTime() - t0) / 1e6;
            long bytes = LoadBench.dirSize(modsDir);
            System.out.println(String.format(Locale.ROOT,
                    "夹具完成       : %d 个包 / %.1f MB / 耗时 %.0f ms",
                    LoadBench.countJars(modsDir), bytes / 1024.0 / 1024.0, genMs));
            keep = true;
        } else {
            System.out.println(String.format(Locale.ROOT, "复用已有夹具   : %d 个包 / %.1f MB",
                    LoadBench.countJars(modsDir), LoadBench.dirSize(modsDir) / 1024.0 / 1024.0));
        }

        CpuTopology.Info cpu = CpuTopology.detect();
        System.out.println("拓扑探测       : " + cpu);
        System.out.println("自动并行度     : " + cpu.computeWorkers());
        if (workerSweep.isEmpty()) {
            workerSweep = new ArrayList<>(Arrays.asList(1, 2, Math.min(4, Math.max(2, cpu.physicalCores() / 2)),
                    cpu.computeWorkers(), cpu.logicalCores()));
        }
        System.out.println();

        List<LoadBench.Outcome> outcomes = new ArrayList<>();
        // 冷热交替：先跑一轮"自动"让页缓存预热，避免 1 线程那轮白白吃亏。
        runOnce(modsDir, 0, register, false);
        for (int w : workerSweep) {
            double best = Double.MAX_VALUE;
            FusionRuntime.Session last = null;
            String stages = "";
            for (int r = 0; r < repeat; r++) {
                LoadBench.Run run = runOnce(modsDir, w, register, true);
                if (run.millis() < best) {
                    best = run.millis();
                    last = run.session();
                    stages = run.stageLine();
                }
                if (last != null) {
                    last.close();
                }
            }
            outcomes.add(new LoadBench.Outcome(w, best, stages));
            System.out.println(String.format(Locale.ROOT, "并行度 %-3d → 最佳 %.1f ms   %s",
                    (w == 0 ? cpu.computeWorkers() : w), best, stages));
        }

        System.out.println();
        System.out.println("======== 对比 ========");
        double base = outcomes.get(0).millis();
        for (LoadBench.Outcome o : outcomes) {
            int w = o.workers() == 0 ? cpu.computeWorkers() : o.workers();
            System.out.println(String.format(Locale.ROOT, "%-6d 线程  %9.1f ms  %5.2fx",
                    w, o.millis(), base / o.millis()));
        }

        // 最后完整跑一遍并打印完整报告，用来核对正确性（模组数、依赖、注入、报告格式）。
        System.out.println();
        System.out.println("======== 完整报告（自动并行度）========");
        try (FusionRuntime.Session s = load(modsDir, 0, register)) {
            System.out.println(s.report());
        }

        if (!keep) {
            System.out.println("（夹具目录保留在 " + modsDir.toAbsolutePath() + "，可复用；--keep 可强制保留）");
        }
    }

    private static List<Integer> parseList(String s) {
        List<Integer> out = new ArrayList<>();
        for (String part : s.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(Integer.parseInt(t));
            }
        }
        return out;
    }

    private static LoadBench.Run runOnce(Path dir, int workers, boolean register, boolean collect)
            throws Exception {
        long t0 = System.nanoTime();
        FusionRuntime.Session session = load(dir, workers, register);
        double ms = (System.nanoTime() - t0) / 1e6;
        String stages = String.format(Locale.ROOT,
                "扫描 %.1f / 元数据 %.1f / 依赖 %.1f / 索引 %.1fms",
                session.profiler.millis("2 扫描模组目录"),
                session.profiler.millis("3 解析三端元数据"),
                session.profiler.millis("4 依赖解析与分层"),
                session.profiler.millis("5 建立全量类索引"));
        if (!collect) {
            session.close();
        }
        return new LoadBench.Run(ms, session, stages);
    }

    private static FusionRuntime.Session load(Path dir, int workers, boolean register)
            throws Exception {
        FusionRuntime.Options o = new FusionRuntime.Options();
        o.modsDir(dir);
        o.workers(workers);
        o.registerMods(register);
        return FusionRuntime.load(o);
    }
}
