package com.fusionml.earlywindow;

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
