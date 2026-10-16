package com.fusionml.earlywindow;

import java.awt.GraphicsEnvironment;

/** 内置的图形版早期窗口提供者。无图形环境时自动让位给控制台实现。 */
public final class SwingEarlyWindowProvider implements EarlyWindowProvider {

    @Override
    public String id() {
        return "swing";
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean available() {
        // 注意：判定"能不能开窗"必须用 isHeadless，而不是猜操作系统。
        return !GraphicsEnvironment.isHeadless();
    }

    @Override
    public EarlyWindow create(EarlyWindowConfig config) {
        return new SwingEarlyWindow(config);
    }

    @Override
    public String description() {
        return "自带图形窗口：自绘进度条 / 圆环、阶段文本、用时与预计剩余、日志尾行，外观完全由配置驱动";
    }
}
