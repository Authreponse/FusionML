package com.fusionml.earlywindow;

public interface EarlyWindow extends AutoCloseable {

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

    void progress(double fraction, String detail);

    void log(String line);

    void finish(String summary);

    default boolean headless() {
        return false;
    }

    @Override
    void close();
}
