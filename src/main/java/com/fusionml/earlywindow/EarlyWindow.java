package com.fusionml.earlywindow;

/**
 * 早期加载窗口。
 *
 * <p>它存在的意义：模组加载发生在游戏主窗口出现之前，这段时间玩家看到的只有黑屏或桌面。
 * 一个能显示"正在做什么、做到哪一步、还要多久"的窗口，是启动体验里最直接的一环。
 *
 * <p>本接口刻意保持极小（打开、阶段、进度、日志、结束、关闭），
 * 这样无论是自带实现、玩家自己写的实现，还是把游戏主窗口接管过来的实现，都能套进来。
 */
public interface EarlyWindow extends AutoCloseable {

    /** 实现的标识，用于命令行按名字选择（例如 swing、console）。 */
    String id();

    /** 显示窗口。实现必须容忍被重复调用。 */
    void open();

    /**
     * 切换到某个阶段。
     *
     * @param stage    阶段名（已是可读文本）
     * @param fraction 进入该阶段时的总体进度，0.0~1.0
     */
    void stage(String stage, double fraction);

    /** 更新总体进度（不改变阶段名）。 */
    void progress(double fraction, String detail);

    /** 追加一行日志。实现可以只保留最近若干行。 */
    void log(String line);

    /** 加载结束。{@code summary} 是给玩家看的一句话结论。 */
    void finish(String summary);

    /** 该实现是否属于"没有界面"的类型（无头环境下的降级实现）。 */
    default boolean headless() {
        return false;
    }

    @Override
    void close();
}
