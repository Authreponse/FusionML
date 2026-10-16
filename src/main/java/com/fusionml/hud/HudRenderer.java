package com.fusionml.hud;

import com.fusionml.meta.ModFormat;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Locale;

/**
 * HUD 渲染器：帧率面板 + 模组列表面板。
 *
 * <p>全部布局与图形由本类自己计算并绘制，只通过 {@link HudBackend} 调用"画点线面字"这些原语，
 * 因此同一份代码既能在独立环境里跑（{@link AwtHudBackend}），也能在游戏内按同一套外观渲染。
 *
 * <p>配色与信息密度的取舍：面板一律半透明深色底 + 细描边，保证在明亮或杂乱背景下都能看清；
 * 帧率数字大、次要信息小，让玩家在余光里也能扫到关键数字。
 */
public final class HudRenderer {

    private static final int PANEL_BG = 0xC812121A;
    private static final int PANEL_BORDER = 0x66404A6E;
    private static final int TEXT_MAIN = 0xFFF2F4FF;
    private static final int TEXT_DIM = 0xFF9AA3C4;
    private static final int ACCENT_OK = 0xFF54D98C;
    private static final int ACCENT_WARN = 0xFFF2C14E;
    private static final int ACCENT_BAD = 0xFFEF5B6B;
    private static final int ACCENT_INFO = 0xFF5AC8FA;
    private static final int GRAPH_FILL = 0x665AC8FA;
    private static final int GRAPH_LINE = 0xFF7FD4FF;
    private static final int GRID = 0x33FFFFFF;
    private static final int ROW_ALT = 0x14FFFFFF;

    private static final int FPS_PANEL_W = 268;
    private static final int FPS_PANEL_H = 96;
    private static final int GRAPH_SAMPLES = 120;

    private final double[] frameSamples = new double[GRAPH_SAMPLES];
    private final int[] polyX = new int[GRAPH_SAMPLES + 2];
    private final int[] polyY = new int[GRAPH_SAMPLES + 2];

    public void render(HudBackend b, int width, int height, FpsMonitor fps, ModListModel model,
                       String query, ModFormat filter, ModListModel.Sort sort,
                       boolean showModList, int scroll) {
        drawFpsPanel(b, 10, 10, fps);
        if (showModList && model != null) {
            drawModList(b, width, height, model, query, filter, sort, scroll);
        }
    }

    public BufferedImage toImage(int width, int height, FpsMonitor fps, ModListModel model,
                                 String query, ModFormat filter, ModListModel.Sort sort,
                                 boolean showModList, int scroll) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            AwtHudBackend b = new AwtHudBackend(g);
            render(b, width, height, fps, model, query, filter, sort, showModList, scroll);
        } finally {
            g.dispose();
        }
        return img;
    }

    private void drawFpsPanel(HudBackend b, int x, int y, FpsMonitor fps) {
        b.fillRoundRect(x, y, FPS_PANEL_W, FPS_PANEL_H, 6, PANEL_BG);
        b.strokeRect(x, y, FPS_PANEL_W, FPS_PANEL_H, PANEL_BORDER);

        double f = fps.recentFps();
        int color = f >= 55 ? ACCENT_OK : (f >= 30 ? ACCENT_WARN : ACCENT_BAD);
        String fpsText = formatFps(f);
        b.text(fpsText, x + 12, y + 34, 28, color, true);
        int w = b.textWidth(fpsText, 28, true);
        b.text("FPS", x + 12 + w + 8, y + 34, 13, TEXT_DIM, false);

        String line2 = String.format(Locale.ROOT, "帧 %.1fms  ·  1%%低 %.0f  最差 %.0fms",
                fps.avgMs(), fps.lowFps(0.01), fps.worstMs());
        b.text(line2, x + 12, y + 52, 12, TEXT_DIM, false);

        drawFrameGraph(b, x + 12, y + 60, FPS_PANEL_W - 24, 26, fps);
    }

    private void drawFrameGraph(HudBackend b, int x, int y, int w, int h, FpsMonitor fps) {
        b.fillRect(x, y, w, h, 0x33000000);
        b.line(x, y + (int) (h * 0.5), x + w, y + (int) (h * 0.5), GRID);

        if (fps.sampleCount() == 0) {
            b.text("等待采样…", x + 4, y + h - 6, 11, TEXT_DIM, false);
            return;
        }
        fps.copyRecentMs(frameSamples);
        // 纵轴下限取 20ms 而不是 33ms：高帧率下也要能看清细微波动，
        // 否则一条 7ms 的线会被压在图表底部，看上去像"一条没有信息的直线"。
        double max = 20;
        double peak = 0;
        for (double v : frameSamples) {
            max = Math.max(max, v);
            peak = Math.max(peak, v);
        }
        int n = frameSamples.length;
        int count = 0;
        polyX[count] = x;
        polyY[count] = y + h;
        count++;
        for (int i = 0; i < n; i++) {
            int px = x + (int) ((long) i * (w - 1) / (n - 1));
            int py = y + h - (int) Math.min(h - 1, frameSamples[i] / max * (h - 1));
            polyX[count] = px;
            polyY[count] = py;
            count++;
        }
        polyX[count] = x + w;
        polyY[count] = y + h;
        count++;
        b.fillPolygon(polyX, polyY, count, GRAPH_FILL);
        // 折线单独描一遍，避免小块背景上看不清走势。
        for (int i = 0; i < n - 1; i++) {
            b.line(polyX[i + 1], polyY[i + 1], polyX[i + 2], polyY[i + 2], GRAPH_LINE);
        }
        // 标签显示"窗口内真实峰值"，纵轴刻度另外标注，避免把刻度当成实测值来读。
        b.text(String.format(Locale.ROOT, "峰值 %.1fms  纵轴 %.0fms", peak, max),
                x + 3, y + 10, 10, TEXT_DIM, false);
    }

    private static String formatFps(double f) {
        if (f >= 100) {
            return String.valueOf(Math.round(f));
        }
        return String.format(Locale.ROOT, "%.1f", f);
    }

    private void drawModList(HudBackend b, int screenW, int screenH, ModListModel model,
                             String query, ModFormat filter, ModListModel.Sort sort, int scroll) {
        int w = Math.min(560, Math.max(380, screenW / 3));
        int rowH = 22;
        int listTopPad = 84;                     // 从"搜索行"下方开始，避免与首行重叠
        int footerH = 26;
        List<ModListModel.Entry> rows = model.query(query, filter, sort);

        int wanted = listTopPad + Math.min(rows.size(), 18) * rowH + footerH;
        int h = Math.min(screenH - 40, Math.max(180, wanted));
        int x = screenW - w - 10;
        int y = 10;
        b.fillRoundRect(x, y, w, h, 6, PANEL_BG);
        b.strokeRect(x, y, w, h, PANEL_BORDER);

        ModListModel.Stats st = model.stats();
        b.text("模组列表", x + 12, y + 24, 16, TEXT_MAIN, true);
        b.text(st.total() + " 个", x + 12 + b.textWidth("模组列表", 16, true) + 8, y + 24, 14,
                ACCENT_INFO, true);
        b.text(st.formatLine(), x + w - 12 - b.textWidth(st.formatLine(), 11, false), y + 24,
                11, TEXT_DIM, false);

        String subtitle = String.format(Locale.ROOT, "类 %d · 依赖 %d · %d 层%s",
                st.totalClasses(), st.totalDependencies(), st.tiers(),
                st.problemMods() > 0 ? " · 有问题 " + st.problemMods() : "");
        b.text(subtitle, x + 12, y + 42, 11,
                st.problemMods() > 0 ? ACCENT_BAD : TEXT_DIM, false);
        b.line(x + 8, y + 50, x + w - 8, y + 50, GRID);

        String searchLine = (query == null || query.isEmpty() ? "搜索：无   " : "搜索：" + query + "   ")
                + (filter == null ? "全部端" : filter.displayName())
                + "  排序：" + sortLabel(sort);
        b.text(searchLine, x + 12, y + 66, 11, TEXT_DIM, false);

        int listTop = y + listTopPad;
        int visible = Math.max(1, (h - listTopPad - footerH) / rowH);
        int maxScroll = Math.max(0, rows.size() - visible);
        int start = Math.max(0, Math.min(scroll, maxScroll));

        for (int i = 0; i < visible && start + i < rows.size(); i++) {
            ModListModel.Entry e = rows.get(start + i);
            int ry = listTop + i * rowH;
            if (i % 2 == 1) {
                b.fillRect(x + 8, ry - 12, w - 16, rowH - 2, ROW_ALT);
            }
            int statusColor = e.fatal ? ACCENT_BAD : (e.issues.isEmpty() ? ACCENT_OK : ACCENT_WARN);
            b.fillRect(x + 12, ry - 5, 4, 4, statusColor);

            String name = ModListModel.trim(e.name(), 26);
            b.text(name, x + 22, ry, 12, e.fatal ? ACCENT_BAD : TEXT_MAIN, false);
            int nameW = b.textWidth(name, 12, false);

            String ver = ModListModel.trim(e.version(), 9);
            b.text(ver, x + 22 + nameW + 8, ry, 11, TEXT_DIM, false);

            int rightX = x + w - 12;
            String tier = "L" + e.tier;
            b.text(tier, rightX - b.textWidth(tier, 11, false), ry, 11, TEXT_DIM, false);
            String plat = e.format().displayName();
            int platW = b.textWidth(plat, 11, false);
            b.text(plat, rightX - b.textWidth(tier, 11, false) - 10 - platW, ry, 11,
                    platformColor(e.format()), false);
        }

        if (rows.isEmpty()) {
            b.text("没有匹配的模组", x + 12, listTop + 12, 12, TEXT_DIM, false);
        }
        if (maxScroll > 0) {
            int barH = Math.max(12, (int) ((long) visible * h / Math.max(1, rows.size())));
            int barY = listTop - 12 + (int) ((long) start * (h - listTopPad - footerH - barH)
                    / Math.max(1, maxScroll));
            b.fillRect(x + w - 6, barY, 3, barH, 0x55FFFFFF);
            b.text("滚轮翻页 · 共 " + rows.size() + " 行", x + 12, y + h - 8, 10, TEXT_DIM, false);
        }
    }

    private static String sortLabel(ModListModel.Sort sort) {
        return switch (sort) {
            case PROBLEM_FIRST -> "问题优先";
            case TIER -> "加载层次";
            case NAME -> "名称";
            case DEPENDENTS -> "被依赖数";
        };
    }

    private static int platformColor(ModFormat f) {
        return switch (f) {
            case FABRIC -> 0xFFB9A5FF;
            case FORGE -> 0xFF8FB8FF;
            case NEOFORGE -> 0xFFF0A868;
        };
    }
}
