package com.fusionml.hud;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.Rectangle2D;
import java.util.HashMap;
import java.util.Map;

/**
 * AWT 版 HUD 后端：用于独立运行时的预览、截图与自动化验证。
 *
 * <p>字体按"能显示中日韩"的优先级挑选，并缓存已解析的字体对象 ——
 * 每帧都 new Font 会让 HUD 自己成为性能问题。
 */
public final class AwtHudBackend implements HudBackend {

    private static final String[] FONT_CANDIDATES = {
            "Microsoft YaHei UI", "Microsoft YaHei", "微软雅黑",
            "Noto Sans CJK SC", "Source Han Sans SC", "PingFang SC",
            "Meiryo", "Segoe UI", "SansSerif",
    };

    private final Graphics2D g;
    private final Map<Long, Font> fontCache = new HashMap<>(8);
    private final String fontFamily;

    public AwtHudBackend(Graphics2D g) {
        this.g = g;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        this.fontFamily = pickFamily();
    }

    /** 挑一个当前系统真正装了的、能显示中文的字体族。 */
    private static String pickFamily() {
        java.util.Set<String> available = new java.util.HashSet<>();
        for (String f : java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames()) {
            available.add(f);
        }
        for (String cand : FONT_CANDIDATES) {
            if (available.contains(cand)) {
                return cand;
            }
        }
        return Font.SANS_SERIF;
    }

    public String fontFamily() {
        return fontFamily;
    }

    private Font font(int size, boolean bold) {
        long key = ((long) size << 1) | (bold ? 1 : 0);
        Font f = fontCache.get(key);
        if (f == null) {
            f = new Font(fontFamily, bold ? Font.BOLD : Font.PLAIN, size);
            fontCache.put(key, f);
        }
        return f;
    }

    @Override
    public void fillRect(int x, int y, int w, int h, int argb) {
        g.setColor(new Color(argb, true));
        g.fillRect(x, y, w, h);
    }

    @Override
    public void strokeRect(int x, int y, int w, int h, int argb) {
        g.setColor(new Color(argb, true));
        g.setStroke(new BasicStroke(1f));
        g.drawRect(x, y, w - 1, h - 1);
    }

    @Override
    public void fillRoundRect(int x, int y, int w, int h, int radius, int argb) {
        g.setColor(new Color(argb, true));
        if (radius <= 0) {
            g.fillRect(x, y, w, h);
        } else {
            g.fillRoundRect(x, y, w, h, radius * 2, radius * 2);
        }
    }

    @Override
    public void line(int x1, int y1, int x2, int y2, int argb) {
        g.setColor(new Color(argb, true));
        g.setStroke(new BasicStroke(1f));
        g.drawLine(x1, y1, x2, y2);
    }

    @Override
    public void fillPolygon(int[] xs, int[] ys, int count, int argb) {
        g.setColor(new Color(argb, true));
        g.fillPolygon(xs, ys, count);
    }

    @Override
    public void text(String s, int x, int baselineY, int size, int argb, boolean bold) {
        if (s == null || s.isEmpty()) {
            return;
        }
        g.setFont(font(size, bold));
        g.setColor(new Color(argb, true));
        g.drawString(s, x, baselineY);
    }

    @Override
    public int textWidth(String s, int size, boolean bold) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        FontRenderContext frc = g.getFontRenderContext();
        Rectangle2D r = font(size, bold).getStringBounds(s, frc);
        return (int) Math.ceil(r.getWidth());
    }

    @Override
    public int fontHeight(int size) {
        return g.getFontMetrics(font(size, false)).getHeight();
    }

    @Override
    public void fillGradientRect(int x, int y, int w, int h, int argbLeft, int argbRight) {
        if (w <= 0 || h <= 0) {
            return;
        }
        java.awt.Paint old = g.getPaint();
        g.setPaint(new java.awt.GradientPaint(x, y, new Color(argbLeft, true),
                x + w, y, new Color(argbRight, true)));
        g.fillRect(x, y, w, h);
        g.setPaint(old);
    }
}
