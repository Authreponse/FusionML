package com.fusionml.bench;

import com.fusionml.meta.MetadataReader;
import com.fusionml.runtime.FusionRuntime;
import com.fusionml.util.FastZip;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** 基准测试用的小工具集合（与正式代码分开，避免测试工具污染主源码）。 */
public final class LoadBench {

    /** 一次测量的结果。 */
    public record Run(double millis, FusionRuntime.Session session, String stageLine) {
    }

    /** 一种并行度配置的最佳成绩。 */
    public record Outcome(int workers, double millis, String stages) {
    }

    private LoadBench() {
    }

    public static int countJars(Path dir) throws IOException {
        return FusionRuntime.listJars(dir).size();
    }

    public static long dirSize(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            long total = 0;
            for (Path p : s.toList()) {
                if (Files.isRegularFile(p)) {
                    total += Files.size(p);
                }
            }
            return total;
        }
    }

    /**
     * 交叉校验：用自研读取器重新打开每个 jar，确认元数据能被独立解析出来。
     * 这是"基准测试不能只看速度"的体现 —— 加速如果建立在错误结果上毫无意义。
     */
    public static String verify(Path dir, int expectedMods) throws IOException {
        List<Path> jars = FusionRuntime.listJars(dir);
        int mods = 0;
        int classes = 0;
        for (Path p : jars) {
            try (FastZip zip = FastZip.open(p)) {
                MetadataReader.Result r = MetadataReader.read(zip, 0);
                mods += r.mods().size();
                for (FastZip.Entry e : zip.entries()) {
                    if (e.isClass()) {
                        classes++;
                    }
                }
            }
        }
        return String.format(Locale.ROOT, "校验：%d 个包、%d 条元数据（期望 %d）、%d 个类文件",
                jars.size(), mods, expectedMods, classes);
    }
}
