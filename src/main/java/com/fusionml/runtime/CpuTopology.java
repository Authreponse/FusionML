package com.fusionml.runtime;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 自研 CPU 拓扑探测。
 *
 * <p>为什么要自己探测：JDK 只给出 {@code Runtime.availableProcessors()}（逻辑核数），
 * 而这台机器是 4 物理核 / 8 逻辑核。超线程带来的两个逻辑核共享同一个物理执行单元，
 * 对"解析 JSON / 扫字节码 / 加载类"这类 CPU 密集流水线来说，把工作线程数定成 8
 * 反而会因共享 L1/L2 与执行端口而变慢。所以我们要拿到物理核数、缓存层级，
 * 用它来算初始并行度，再交给运行期反馈控制器微调。
 *
 * <p>探测顺序：
 * <ol>
 *   <li>系统属性 {@code fusion.cores} / 环境变量 {@code FUSION_CORES} （用户可硬覆盖）</li>
 *   <li>Linux: {@code /proc/cpuinfo}</li>
 *   <li>macOS: {@code sysctl -n hw.physicalcpu} / {@code hw.l3cachesize}</li>
 *   <li>Windows: 注册表 {@code HKLM\HARDWARE\DESCRIPTION\System\CentralProcessor}
 *       （每个物理插槽一个子键，拿到主频）+ 父进程 CIM 异步补全核心数</li>
 *   <li>退化启发式：逻辑核数 / 2（超线程）并夹逼到 [2, 16]</li>
 * </ol>
 *
 * <p>整条链路要么极快（文件读取），要么异步（子进程），保证不阻塞加载主路径。
 */
public final class CpuTopology {

    public static final class Info {
        private final int physicalCores;
        private final int logicalCores;
        private final long l2Bytes;
        private final long l3Bytes;
        private final int maxFreqMhz;
        private final String source;

        Info(int physicalCores, int logicalCores, long l2Bytes, long l3Bytes, int maxFreqMhz, String source) {
            this.physicalCores = Math.max(1, physicalCores);
            this.logicalCores = Math.max(1, logicalCores);
            this.l2Bytes = Math.max(0L, l2Bytes);
            this.l3Bytes = Math.max(0L, l3Bytes);
            this.maxFreqMhz = Math.max(0, maxFreqMhz);
            this.source = source;
        }

        public int physicalCores() { return physicalCores; }

        public int logicalCores() { return logicalCores; }

        public long l2Bytes() { return l2Bytes; }

        public long l3Bytes() { return l3Bytes; }

        public int maxFreqMhz() { return maxFreqMhz; }

        public String source() { return source; }

        /** 是否开启超线程（逻辑核多于物理核）。 */
        public boolean smt() { return logicalCores > physicalCores; }

        /**
         * 计算 CPU 密集型阶段的工作线程数。
         *
         * <p>规则：有超线程时用"物理核 + 逻辑核的 1/4"，因为纯 CPU 流水线在物理核数附近
         * 达到峰值，稍微多一点点可以掩盖偶发停顿（GC、缺页、调度抖动）。
         */
        public int computeWorkers() {
            int w;
            if (smt()) {
                w = physicalCores + Math.max(1, (logicalCores - physicalCores) / 4);
            } else {
                w = logicalCores;
            }
            return clamp(w, 2, 64);
        }

        /**
         * 计算 IO 密集型阶段（读 jar、解压）的工作线程数。
         * IO 会阻塞，所以开到逻辑核数的 2 倍能提高吞吐；但受限于 SSD/NVMe 队列深度，
         * 上限压到 24 以免把元数据缓存的局部性打散。
         */
        public int ioWorkers() {
            return clamp(logicalCores() * 2, 4, 24);
        }

        /**
         * 单块工作集超过 L2 就会掉到 L3，超过 L3 就会打到内存。
         * 调度器用它来决定并行分块大小：让每块处理的字节数尽量塞进 L2。
         */
        public int cacheAwareChunk(long bytesPerElement, int totalElements, int workers) {
            long perThread = l2Bytes > 0 ? l2Bytes / 2 : 256L * 1024L;
            int chunk = (int) Math.max(1, perThread / Math.max(1, bytesPerElement));
            int evenSplit = Math.max(1, totalElements / Math.max(1, workers * 4));
            return Math.max(1, Math.min(chunk, Math.max(evenSplit, 1)));
        }

        @Override
        public String toString() {
            return "物理核=" + physicalCores + " 逻辑核=" + logicalCores
                    + " L2=" + (l2Bytes / 1024) + "KB L3=" + (l3Bytes / 1024 / 1024) + "MB"
                    + " 主频=" + maxFreqMhz + "MHz 来源=" + source;
        }
    }

    static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static volatile Info cached;

    private CpuTopology() {
    }

    /** 同步探测（带缓存）。首次调用可能读文件或起子进程，通常 <5ms。 */
    public static Info detect() {
        Info local = cached;
        if (local != null) {
            return local;
        }
        synchronized (CpuTopology.class) {
            if (cached == null) {
                cached = probe();
            }
            return cached;
        }
    }

    /**
     * 异步探测，永不阻塞调用方。主加载线程可以先用启发式值开工，
     * 等真实拓扑回来后再交给反馈控制器修正。
     */
    public static CompletableFuture<Info> detectAsync() {
        Info local = cached;
        if (local != null) {
            return CompletableFuture.completedFuture(local);
        }
        return CompletableFuture.supplyAsync(CpuTopology::detect);
    }

    private static Info probe() {
        int logical = Math.max(1, Runtime.getRuntime().availableProcessors());

        int override = intProperty("fusion.cores", "FUSION_CORES");
        if (override > 0) {
            return new Info(override, logical, 0, 0, 0, "外部指定");
        }

        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        Info info = null;
        try {
            if (os.contains("linux")) {
                info = probeLinux(logical);
            } else if (os.contains("mac") || os.contains("darwin")) {
                info = probeMac(logical);
            } else if (os.contains("win")) {
                info = probeWindows(logical);
            }
        } catch (Throwable ignored) {
            // 探测失败绝不能让加载器起不来，直接退到启发式。
        }
        if (info != null) {
            return info;
        }
        // 启发式：市面上绝大多数 x86/ARM 桌面与移动芯片都是 2 路超线程。
        int physical = logical > 4 ? logical / 2 : logical;
        return new Info(physical, logical, 0, 0, 0, "启发式(逻辑核/2)");
    }

    private static int intProperty(String prop, String env) {
        String v = System.getProperty(prop);
        if (v == null) {
            v = System.getenv(env);
        }
        if (v == null) {
            return -1;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static Info probeLinux(int logical) throws Exception {
        Path cpuinfo = Path.of("/proc/cpuinfo");
        if (!Files.isReadable(cpuinfo)) {
            return null;
        }
        int physical = -1;
        int siblings = -1;
        int coresPerSocket = -1;
        int freqMhz = 0;
        for (String line : Files.readAllLines(cpuinfo, StandardCharsets.UTF_8)) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            String val = line.substring(colon + 1).trim();
            switch (key) {
                case "physical id" -> physical = Math.max(physical, parseInt(val, -1));
                case "siblings" -> siblings = Math.max(siblings, parseInt(val, -1));
                case "cpu cores" -> coresPerSocket = Math.max(coresPerSocket, parseInt(val, -1));
                case "cpu MHz" -> freqMhz = Math.max(freqMhz, (int) parseDouble(val));
                default -> { }
            }
        }
        if (coresPerSocket > 0) {
            int sockets = physical + 1;
            int phys = sockets * coresPerSocket;
            long l3 = readLongFile("/sys/devices/system/cpu/cpu0/cache/index3/size") * 1024L;
            long l2 = readLongFile("/sys/devices/system/cpu/cpu0/cache/index2/size") * 1024L;
            return new Info(phys, siblings > 0 ? Math.max(siblings * sockets, logical) : logical,
                    l2, l3, freqMhz, "Linux /proc/cpuinfo");
        }
        return null;
    }

    private static long readLongFile(String path) {
        try {
            String s = Files.readString(Path.of(path), StandardCharsets.UTF_8).trim();
            StringBuilder digits = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (Character.isDigit(c)) {
                    digits.append(c);
                } else if (digits.length() > 0) {
                    break;
                }
            }
            return digits.length() == 0 ? 0L : Long.parseLong(digits.toString());
        } catch (Throwable e) {
            return 0L;
        }
    }

    private static Info probeMac(int logical) {
        int phys = (int) sysctlLong("hw.physicalcpu");
        long l3 = sysctlLong("hw.l3cachesize");
        long l2 = sysctlLong("hw.l2cachesize");
        int freq = (int) (sysctlLong("hw.cpufrequency") / 1_000_000L);
        if (phys <= 0) {
            return null;
        }
        return new Info(phys, logical, l2, l3, freq, "macOS sysctl");
    }

    private static long sysctlLong(String key) {
        try {
            String out = exec(1500, "sysctl", "-n", key);
            return out.isEmpty() ? 0L : Long.parseLong(out.trim());
        } catch (Throwable e) {
            return 0L;
        }
    }

    /**
     * Windows 探测。
     *
     * <p>这里有一条用血换来的经验：<b>不要在启动路径上调用 PowerShell</b>。
     * {@code Get-CimInstance} 拿到的是最准的核心数，但 PowerShell 自身冷启动在这类机器上要
     * 4 秒左右 —— 那会让"加载 200 个模组只用 120ms"变成一个笑话。
     *
     * <p>所以顺序改成：
     * <ol>
     *   <li>注册表 {@code reg.exe} 取每个物理插槽的主频与插槽数（两次调用，几十毫秒，且必然存在）；</li>
     *   <li>{@code wmic} 取物理核数（可用时约 50ms，给 400ms 上限，超时或不存在就放弃）；</li>
     *   <li>都拿不到就用启发式（逻辑核 / 2）；</li>
     *   <li>只有显式设置 {@code -Dfusion.probe=deep} 时才走 PowerShell 那条精确但慢的路径。</li>
     * </ol>
     * 整个探测还有一道总时间预算兜底，任何一步都不允许把启动拖长。
     */
    private static Info probeWindows(int logical) {
        long budgetStart = System.nanoTime();
        int freq = 0;
        int sockets = 0;
        try {
            String out = exec(1200, "reg", "query",
                    "HKLM\\HARDWARE\\DESCRIPTION\\System\\CentralProcessor");
            for (String line : out.split("\\R")) {
                String t = line.trim();
                if (t.regionMatches(true, 0, "HKEY_LOCAL_MACHINE", 0, 18)) {
                    sockets++;
                }
            }
            if (sockets <= 0) {
                sockets = 1;
            }
            String first = exec(800, "reg", "query",
                    "HKLM\\HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0", "/v", "~MHz");
            freq = extractHexOrDec(first);
        } catch (Throwable ignored) {

        }

        long l3 = 0;
        long l2 = 0;
        int cores = -1;
        boolean deep = "deep".equalsIgnoreCase(System.getProperty("fusion.probe", ""));
        if (withinBudget(budgetStart, 800)) {
            cores = probeWmic(logical);
        }
        if (deep && withinBudget(budgetStart, 6000)) {
            int[] cim = probeCim();
            if (cim != null) {
                cores = cim[0] > 0 ? cim[0] : cores;
                l3 = cim[1] > 0 ? cim[1] : l3;
                l2 = cim[2] > 0 ? cim[2] : l2;
            }
        }

        if (cores > 0) {
            return new Info(cores, logical, l2, l3, freq,
                    "Windows 注册表 + wmic（探测 " + millisSince(budgetStart) + "ms）");
        }
        int phys = logical > 4 ? logical / 2 : logical;
        return new Info(phys, logical, l2, l3, freq,
                "Windows 注册表 + 启发式（探测 " + millisSince(budgetStart) + "ms）");
    }

    private static int probeWmic(int logical) {
        try {
            String out = exec(400, "wmic", "cpu", "get", "NumberOfCores", "/value");
            int best = -1;
            for (String line : out.split("\\R")) {
                String t = line.trim();
                int eq = t.indexOf('=');
                if (eq > 0) {
                    int v = (int) parseDouble(t.substring(eq + 1).trim());
                    if (v > 0) {
                        best = best < 0 ? v : best + v;
                    }
                }
            }
            return best;
        } catch (Throwable e) {
            return -1;
        }
    }

    private static int[] probeCim() {
        try {
            String ps = exec(4000, "powershell", "-NoProfile", "-NonInteractive", "-Command",
                    "(Get-CimInstance Win32_Processor | Measure-Object -Property NumberOfCores -Sum).Sum;"
                            + "(Get-CimInstance Win32_Processor | Measure-Object -Property L3CacheSize -Sum).Sum;"
                            + "(Get-CimInstance Win32_Processor | Measure-Object -Property L2CacheSize -Sum).Sum");
            List<String> nums = new ArrayList<>();
            for (String line : ps.split("\\R")) {
                String t = line.trim();
                if (!t.isEmpty()) {
                    nums.add(t);
                }
            }
            int[] out = new int[3];
            if (nums.size() >= 1) {
                out[0] = (int) parseDouble(nums.get(0));
            }
            if (nums.size() >= 2) {
                out[1] = (int) (parseDouble(nums.get(1)) * 1024L);
            }
            if (nums.size() >= 3) {
                out[2] = (int) (parseDouble(nums.get(2)) * 1024L);
            }
            return out;
        } catch (Throwable e) {
            return null;
        }
    }

    private static boolean withinBudget(long startNanos, long budgetMs) {
        return (System.nanoTime() - startNanos) / 1_000_000L < budgetMs;
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static int extractHexOrDec(String text) {
        int idx = text.toLowerCase(java.util.Locale.ROOT).lastIndexOf("0x");
        try {
            if (idx >= 0) {
                String hex = text.substring(idx + 2).trim().split("\\s+")[0];
                return Integer.parseInt(hex, 16);
            }
            for (String line : text.split("\\R")) {
                String[] parts = line.trim().split("\\s{2,}");
                if (parts.length >= 3) {
                    return Integer.parseInt(parts[parts.length - 1].trim());
                }
            }
        } catch (Throwable ignored) {

        }
        return 0;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable e) {
            return def;
        }
    }

    private static double parseDouble(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Throwable e) {
            return 0d;
        }
    }

    private static String exec(long timeoutMs, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder(256);
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly();
            return "";
        }
        return sb.toString();
    }
}
