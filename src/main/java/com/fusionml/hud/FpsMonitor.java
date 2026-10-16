package com.fusionml.hud;

import java.util.Arrays;

/**
 * 帧率采样器。
 *
 * <p>只做一件事：把每一帧的实际耗时记下来，然后回答"现在多流畅、最卡的时候有多卡"。
 * 比起"每秒计一次帧数"的粗糙做法，这里保留帧耗时的完整分布，因此能算出
 * <b>1% low</b>（最差 1% 帧的平均帧率）—— 玩家感受到的卡顿几乎总是由它决定，
 * 而不是由平均帧率决定。
 *
 * <p>绘制路径零分配：环形缓冲 + 基本类型数组。避免"HUD 自身因为 GC 抖动而影响帧率"这种自伤。
 */
public final class FpsMonitor {

    private static final int DEFAULT_CAPACITY = 240;

    private final long[] frameNanos;
    private int cursor;
    private int size;
    private long lastNanos;
    private double smoothedFps = 60;
    private long totalFrames;

    public FpsMonitor() {
        this(DEFAULT_CAPACITY);
    }

    public FpsMonitor(int capacity) {
        this.frameNanos = new long[Math.max(8, capacity)];
        this.lastNanos = System.nanoTime();
    }

    /** 用当前时间打一帧。 */
    public void tick() {
        frame(System.nanoTime());
    }

    /** 记录一帧结束的绝对时间。 */
    public void frame(long nowNanos) {
        long delta = nowNanos - lastNanos;
        lastNanos = nowNanos;
        if (delta <= 0 || delta > 5_000_000_000L) {
            // 暂停、断点、系统挂起产生的超大间隙不是"一帧"，计入会污染统计。
            return;
        }
        frameNanos[cursor] = delta;
        cursor = (cursor + 1) % frameNanos.length;
        if (size < frameNanos.length) {
            size++;
        }
        totalFrames++;
        double instant = 1_000_000_000.0 / delta;
        // 指数平滑：HUD 上的数字不该逐帧乱跳，玩家要看的是趋势。
        smoothedFps += (instant - smoothedFps) * 0.1;
    }

    public int sampleCount() {
        return size;
    }

    public long totalFrames() {
        return totalFrames;
    }

    /** 平滑后的当前帧率。 */
    public double fps() {
        return size == 0 ? 0 : smoothedFps;
    }

    /** 由最近约一秒的帧耗时累加得到的帧率（不依赖墙钟）。 */
    public double recentFps() {
        if (size == 0) {
            return 0;
        }
        long sum = 0;
        int n = 0;
        for (int i = 0; i < size; i++) {
            long v = frameNanos[(cursor - 1 - i + frameNanos.length * 2) % frameNanos.length];
            sum += v;
            n++;
            if (sum >= 1_000_000_000L) {
                break;
            }
        }
        return n == 0 || sum == 0 ? 0 : n * 1_000_000_000.0 / sum;
    }

    /** 平均帧耗时（毫秒）。 */
    public double avgMs() {
        if (size == 0) {
            return 0;
        }
        long sum = 0;
        for (int i = 0; i < size; i++) {
            sum += frameNanos[i];
        }
        return sum / 1_000_000.0 / size;
    }

    /**
     * "最差 p 比例"帧的平均帧率，即常说的 1% low / 0.1% low。
     *
     * @param fraction 0.01 表示最差 1% 的帧
     */
    public double lowFps(double fraction) {
        if (size == 0) {
            return 0;
        }
        int worst = Math.max(1, (int) Math.round(size * fraction));
        long[] copy = Arrays.copyOf(frameNanos, size);
        Arrays.sort(copy);
        long sum = 0;
        for (int i = 0; i < worst; i++) {
            sum += copy[size - 1 - i];
        }
        return sum == 0 ? 0 : worst * 1_000_000_000.0 / sum;
    }

    /** 最差单帧耗时（毫秒）。 */
    public double worstMs() {
        long worst = 0;
        for (int i = 0; i < size; i++) {
            worst = Math.max(worst, frameNanos[i]);
        }
        return worst / 1_000_000.0;
    }

    /** 按时间顺序取出最近的帧耗时（毫秒）写入调用方数组，用于画折线图。 */
    public void copyRecentMs(double[] out) {
        int n = Math.min(out.length, size);
        for (int i = 0; i < n; i++) {
            long v = frameNanos[(cursor - n + i + frameNanos.length * 2) % frameNanos.length];
            out[i] = v / 1_000_000.0;
        }
        for (int i = n; i < out.length; i++) {
            out[i] = 0;
        }
    }

    public void reset() {
        cursor = 0;
        size = 0;
        lastNanos = System.nanoTime();
        smoothedFps = 60;
        totalFrames = 0;
    }
}
