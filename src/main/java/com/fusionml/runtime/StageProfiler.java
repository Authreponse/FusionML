package com.fusionml.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 阶段计时器。
 *
 * <p>不只是"打日志"：加载器靠它找出真正的瓶颈阶段。
 * 阶段耗时随机器差异极大（机械硬盘上 IO 阶段可能是 CPU 阶段的十倍），
 * 把每阶段实测耗时与并行度一起记下来，"针对全机型优化"才能落到数据上，而不是口号。
 */
public final class StageProfiler {

    private final Map<String, long[]> records = new LinkedHashMap<>();
    private final List<String> order = new ArrayList<>();
    private final long t0 = System.nanoTime();
    private long startedAt;

    public void begin() {
        startedAt = System.nanoTime();
    }

    public long end(String stage) {
        long now = System.nanoTime();
        long dur = now - startedAt;
        long[] rec = records.computeIfAbsent(stage, k -> {
            order.add(k);
            return new long[2];
        });
        rec[0] += dur;
        rec[1]++;
        startedAt = now;
        return dur;
    }

    public <T> T time(String stage, java.util.function.Supplier<T> body) {
        begin();
        try {
            return body.get();
        } finally {
            end(stage);
        }
    }

    public void run(String stage, Runnable body) {
        begin();
        try {
            body.run();
        } finally {
            end(stage);
        }
    }

    public double totalMillis() {
        return (System.nanoTime() - t0) / 1_000_000.0;
    }

    public double millis(String stage) {
        long[] r = records.get(stage);
        return r == null ? 0 : r[0] / 1_000_000.0;
    }

    public String report() {
        StringBuilder sb = new StringBuilder(512);
        double total = totalMillis();
        sb.append(String.format(Locale.ROOT, "%-24s %10s %8s %8s%n", "阶段", "耗时(ms)", "次数", "占比"));
        sb.append("-".repeat(54)).append(System.lineSeparator());
        for (String s : order) {
            long[] r = records.get(s);
            double ms = r[0] / 1_000_000.0;
            sb.append(String.format(Locale.ROOT, "%-24s %10.2f %8d %7.1f%%%n",
                    s, ms, r[1], total > 0 ? ms / total * 100 : 0));
        }
        sb.append("-".repeat(54)).append(System.lineSeparator());
        sb.append(String.format(Locale.ROOT, "%-24s %10.2f%n", "合计", total));
        return sb.toString();
    }
}
