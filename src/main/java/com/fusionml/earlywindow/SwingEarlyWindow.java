package com.fusionml.earlywindow;

import com.fusionml.hud.AwtHudBackend;
import com.fusionml.hud.HudBackend;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * 图形版早期加载窗口。
 *
 * <p>外观完全由 {@link EarlyWindowConfig} 驱动（标题、字标、配色、进度样式、日志行数、置顶、完成后延时关闭），
 * 因此玩家不改一行代码就能把它换成自己的样子；要连"长什么样"都换掉，则实现 {@link EarlyWindow} 或
 * {@link EarlyWindowProvider} 接口即可。
 *
 * <p>实现在任意线程调用都安全：所有 UI 变更统一投递到 EDT，避免"主线程直接改 Swing 组件"
 * 这种偶发崩溃。
 */
public final class SwingEarlyWindow implements EarlyWindow {

    private final EarlyWindowConfig cfg;
    private final long startNanos = System.nanoTime();
    private final Deque<String> logs = new ArrayDeque<>();

    private volatile JFrame frame;
    private volatile Canvas canvas;
    private Timer timer;
    private volatile double fraction;
    private volatile String stage = "";
    private volatile String detail = "";
    private volatile String summary = "";
    private volatile boolean finished;

    public SwingEarlyWindow(EarlyWindowConfig cfg) {
        this.cfg = cfg;
    }

    @Override
    public String id() {
        return "swing";
    }

    @Override
    public void open() {
        SwingUtilities.invokeLater(() -> {
            if (frame != null) {
                frame.toFront();
                return;
            }
            Canvas c = new Canvas();
            JFrame f = new JFrame(cfg.title);
            f.setUndecorated(true);
            f.setAlwaysOnTop(cfg.alwaysOnTop);
            f.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            f.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    // 允许玩家手动关掉"不想看"的加载窗口，不影响加载流程。
                    closeWindow();
                }
            });
            f.setContentPane(c);
            f.pack();
            f.setLocationRelativeTo(null);
            if (!cfg.iconPath.isBlank()) {
                try {
                    java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(
                            java.nio.file.Path.of(cfg.iconPath).toFile());
                    if (img != null) {
                        f.setIconImage(img);
                    }
                } catch (Exception ignored) {
                    // 图标读不到就用默认的，不影响功能。
                }
            }
            f.setVisible(true);
            frame = f;
            canvas = c;
            // 30fps 刷新：足够顺滑，又不会为了画进度条去抢加载线程的 CPU。
            timer = new Timer(33, e -> c.repaint());
            timer.start();
        });
    }

    @Override
    public void stage(String stage, double fraction) {
        this.stage = stage;
        this.fraction = fraction;
    }

    @Override
    public void progress(double fraction, String detail) {
        this.fraction = fraction;
        if (detail != null && !detail.isEmpty()) {
            this.detail = detail;
        }
        Canvas c = canvas;
        if (c != null && !c.isShowing()) {
            // 窗口已经显示出来时靠定时器刷新即可；未显示时主动促一次，保证进度不落后。
            SwingUtilities.invokeLater(c::repaint);
        }
    }

    @Override
    public void log(String line) {
        synchronized (logs) {
            logs.addLast(line);
            while (logs.size() > Math.max(1, cfg.logLines)) {
                logs.removeFirst();
            }
        }
    }

    @Override
    public void finish(String summary) {
        this.summary = summary;
        this.fraction = 1.0;
        this.finished = true;
        Canvas c = canvas;
        if (c != null) {
            SwingUtilities.invokeLater(c::repaint);
        }
        if (cfg.closeOnFinish) {
            new Thread(() -> {
                try {
                    Thread.sleep(Math.max(0, cfg.closeDelayMs));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                closeWindow();
            }, "fusion-ew-closer").start();
        }
    }

    @Override
    public void close() {
        closeWindow();
    }

    private void closeWindow() {
        Timer t = timer;
        if (t != null) {
            t.stop();
        }
        JFrame f = frame;
        frame = null;
        canvas = null;
        if (f != null) {
            SwingUtilities.invokeLater(f::dispose);
        }
    }

    /** 自绘画面。 */
    private final class Canvas extends JPanel {
        Canvas() {
            setPreferredSize(new Dimension(cfg.width, cfg.height));
            setBackground(new Color(cfg.background, true));
            setDoubleBuffered(true);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            HudBackend b = new AwtHudBackend(g2);
            try {
                draw(b);
            } finally {
                g2.dispose();
            }
        }

        private void draw(HudBackend b) {
            // 显式限定：JComponent 也有 paint(Graphics)，不加限定会解析到那个重载上去。
            SwingEarlyWindow.paint(b, cfg, snapshot());
        }
    }

    /** 一帧的绘制状态。把状态抽出来，绘制逻辑就能脱离窗口单独跑（截图、测试都用得上）。 */
    public record Frame(double fraction, String stage, String detail, String[] logs,
                        double elapsedSec, boolean finished, String summary) {
    }

    private Frame snapshot() {
        String[] lines;
        synchronized (logs) {
            lines = logs.toArray(new String[0]);
        }
        return new Frame(fraction, stage, detail, lines,
                (System.nanoTime() - startNanos) / 1e9, finished, summary);
    }

    /** 离屏渲染一帧，用于自动截图与外观核对。 */
    public static java.awt.image.BufferedImage renderPreview(EarlyWindowConfig cfg, Frame frame) {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(cfg.width, cfg.height,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            paint(new AwtHudBackend(g), cfg, frame);
        } finally {
            g.dispose();
        }
        return img;
    }

    /** 全部绘制逻辑集中在这里，只依赖 HudBackend 与配置，不依赖任何 Swing 组件。 */
    static void paint(HudBackend b, EarlyWindowConfig cfg, Frame f) {
        int w = cfg.width;
        int h = cfg.height;
        b.fillRect(0, 0, w, h, cfg.background);
        b.strokeRect(0, 0, w, h, 0x35FFFFFF);

        // 顶部品牌区
        b.fillRect(0, 0, w, 62, cfg.panel);
        b.text(cfg.logoText, 20, 40, 22, cfg.accent, true);
        int logoW = b.textWidth(cfg.logoText, 22, true);
        b.text(cfg.title, 20 + logoW + 12, 38, 13, cfg.dimColor, false);
        if (!cfg.subtitle.isEmpty()) {
            b.text(cfg.subtitle, 20, 56, 11, cfg.dimColor, false);
        }

        int barY = 92;
        String stageText = f.stage() == null || f.stage().isEmpty() ? "准备中…" : f.stage();
        b.text(stageText, 20, barY - 8, 14, cfg.textColor, true);
        String percentText = String.format(Locale.ROOT, "%.0f%%", f.fraction() * 100);
        b.text(percentText, w - 20 - b.textWidth(percentText, 14, true), barY - 8, 14,
                cfg.accent, true);

        int barW = w - 40;
        int barH = 12;
        b.fillRoundRect(20, barY + 4, barW, barH, 6, 0x40000000);
        int filled = (int) Math.max(0, Math.min(barW, barW * f.fraction()));
        if (filled > 0) {
            b.fillGradientRect(20, barY + 4, filled, barH, cfg.accent, 0xFF9BE8FF);
        }

        int infoY = barY + 40;
        if (cfg.showTiming) {
            String timing;
            if (f.finished()) {
                timing = String.format(Locale.ROOT, "已完成，用时 %.2f 秒", f.elapsedSec());
            } else if (f.fraction() > 0.03) {
                double total = f.elapsedSec() / f.fraction();
                timing = String.format(Locale.ROOT, "已用 %.1fs · 预计剩余 %.1fs",
                        f.elapsedSec(), Math.max(0, total - f.elapsedSec()));
            } else {
                timing = String.format(Locale.ROOT, "已用 %.1fs", f.elapsedSec());
            }
            b.text(timing, 20, infoY, 11, cfg.dimColor, false);
        }
        String detail = f.detail();
        if (detail != null && !detail.isEmpty() && !f.finished()) {
            b.text(clip(b, detail, 11, w - 40), 20, infoY + 16, 11, cfg.dimColor, false);
        }
        if (f.finished() && f.summary() != null && !f.summary().isEmpty()) {
            b.text(clip(b, f.summary(), 12, w - 40), 20, infoY + 16, 12, 0xFF7FE3A0, false);
        }

        if (cfg.showLog && f.logs() != null && f.logs().length > 0) {
            int count = Math.min(f.logs().length, Math.max(1, cfg.logLines));
            int first = f.logs().length - count;
            int ly = h - 26 - (count - 1) * 15;
            for (int i = first; i < f.logs().length; i++) {
                b.text(clip(b, f.logs()[i], 11, w - 40), 20, ly, 11, cfg.dimColor, false);
                ly += 15;
            }
        }
        if (!cfg.footer.isEmpty()) {
            b.text(cfg.footer, 20, h - 10, 10, cfg.dimColor, false);
        }
    }

    private static String clip(HudBackend b, String s, int size, int maxWidth) {
        if (s == null) {
            return "";
        }
        String out = s;
        while (b.textWidth(out, size, false) > maxWidth && out.length() > 8) {
            out = out.substring(0, out.length() - 6) + "…";
        }
        return out;
    }
}
