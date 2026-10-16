package com.fusionml.earlywindow;

/** 内置的控制台早期窗口提供者，永远可用（服务器与 CI 的兜底）。 */
public final class ConsoleEarlyWindowProvider implements EarlyWindowProvider {

    @Override
    public String id() {
        return "console";
    }

    @Override
    public int priority() {
        return 1000;
    }

    @Override
    public EarlyWindow create(EarlyWindowConfig config) {
        return new ConsoleEarlyWindow(config);
    }

    @Override
    public String description() {
        return "控制台进度：按行输出阶段耗时，并尝试用回车覆写进度条（无界面环境首选）";
    }
}
