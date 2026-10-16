package com.fusionml.earlywindow;

/**
 * 控制台版早期窗口：无图形环境（服务器、CI、纯命令行）下的降级实现。
 *
 * <p>它同样遵守 {@link EarlyWindow} 契约，因此上层代码不需要为"有没有界面"写两套逻辑。
 * 进度条用回车覆写同一行，日志行则正常换行输出，避免把进度条顶掉。
 */
public final class ConsoleEarlyWindow implements EarlyWindow {

    private final EarlyWindowConfig cfg;
    private final boolean ansi;
    private String stage = "";
    private int lastPrintedPercent = -1;
    private final long startNanos = System.nanoTime();

    public ConsoleEarlyWindow(EarlyWindowConfig cfg) {
        this.cfg = cfg;
        String term = System.getenv("TERM");
        this.ansi = term != null && !term.isBlank();
    }

    @Override
    public String id() {
        return "console";
    }

    @Override
    public void open() {
        System.out.println(cfg.title + " —— " + cfg.subtitle);
        if (!cfg.footer.isEmpty()) {
            System.out.println(cfg.footer);
        }
    }

    @Override
    public void stage(String stage, double fraction) {
        this.stage = stage;

        System.out.printf("%-24s %5.1f%%   已用 %.2fs%n", stage, fraction * 100,
                (System.nanoTime() - startNanos) / 1e9);
        lastPrintedPercent = -1;
    }

    @Override
    public void progress(double fraction, String detail) {
        int percent = (int) Math.round(fraction * 100);
        if (percent == lastPrintedPercent) {
            return;
        }
        lastPrintedPercent = percent;
        if (!ansi) {
            return;
        }
        int width = 28;
        int filled = (int) Math.round(fraction * width);
        StringBuilder bar = new StringBuilder(width + 8);
        for (int i = 0; i < width; i++) {
            bar.append(i < filled ? '=' : '.');
        }
        System.out.print("\r  [" + bar + "] " + percent + "%");
        if (percent >= 100) {
            System.out.println();
        }
    }

    @Override
    public void log(String line) {
        if (ansi) {
            System.out.print("\r" + " ".repeat(50) + "\r");
        }
        System.out.println("  · " + line);
    }

    @Override
    public void finish(String summary) {
        System.out.println(summary);
    }

    @Override
    public boolean headless() {
        return true;
    }

    @Override
    public void close() {

    }
}
