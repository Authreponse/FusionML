package com.fusionml.install;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.LinearGradientPaint;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JViewport;
import javax.swing.ScrollPaneConstants;
import javax.swing.UIManager;
import javax.swing.border.AbstractBorder;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.plaf.basic.BasicTextFieldUI;
import javax.swing.text.JTextComponent;

/**
 * 圆角界面自绘工具箱。
 *
 * <p>Swing 的默认组件是直角的，想要圆角只有一条路：<b>自己画</b>。
 * 这个类把"圆角"这件事收敛到一处，而不是散落在界面代码里 —— 理由是圆角最容易出的 bug
 * 恰恰是不一致：按钮是圆角、输入框是直角，或者圆角半径在缩放后跟别处对不上。
 *
 * <p>这里统一约定：<b>所有圆角都从同一个半径推导</b>（{@link #r}），
 * 并且所有形状都走 {@link #roundRect} 这一个出口，
 * 保证"卡片 14、按钮 10、输入框 9、小控件 7"这套比例在任何尺寸下都成立。
 *
 * <h2>为什么不用 setBorder 画圆角</h2>
 * 用 {@link AbstractBorder} 画圆角描边时，Swing 会先让组件用自己的背景色铺满一个<b>矩形</b>，
 * 于是圆角外面那四个小三角会留着背景色 —— 看起来就是"圆角没生效"或者"四个角脏"。
 * 本类的做法是反过来：<b>让组件不透明，但用圆角形状去裁剪/填充整块区域</b>
 * （见 {@link BgPainter} 与 {@link #paintRoundedBackground}），
 * 先把整块矩形用父容器底色涂掉，再画圆角，四个角就一定是干净的。
 */
final class RoundedUi {

    private RoundedUi() {
    }

    static final Color BG = new Color(0x14, 0x16, 0x1E);
    static final Color CARD = new Color(0x1E, 0x21, 0x2C);
    static final Color CARD_TOP = new Color(0x23, 0x27, 0x34);
    static final Color LINE = new Color(0x2E, 0x33, 0x44);
    static final Color LINE_HI = new Color(0x3D, 0x45, 0x5C);
    static final Color TEXT = new Color(0xE8, 0xEC, 0xF6);
    static final Color DIM = new Color(0x9A, 0xA3, 0xC4);
    static final Color ACCENT = new Color(0x5A, 0xC8, 0xFA);
    static final Color OK = new Color(0x54, 0xD9, 0x8C);
    static final Color WARN = new Color(0xF2, 0xC1, 0x4E);
    static final Color BAD = new Color(0xEF, 0x5B, 0x6B);

    static final Color FIELD = new Color(0x16, 0x19, 0x22);
    /** 禁用态统一用这个灰度，避免"看不清是不是点不动"。 */
    static final Color DISABLED_TEXT = new Color(0x5E, 0x66, 0x7E);
    static final Color BTN_SECONDARY = new Color(0x26, 0x2B, 0x38);
    static final Color BTN_SECONDARY_HOVER = new Color(0x33, 0x3A, 0x4C);
    static final Color BTN_SECONDARY_PRESS = new Color(0x1B, 0x1F, 0x2A);

    static final String FAMILY = pickFamily();

    private static String pickFamily() {
        String[] want = {"Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC",
                "Noto Sans CJK SC", "Source Han Sans SC", "SimHei"};
        java.util.List<String> have = java.util.Arrays.asList(
                java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getAvailableFontFamilyNames());
        for (String w : want) {

            if (have.contains(w) && canRenderCjk(w)) {
                return w;
            }
        }
        return Font.SANS_SERIF;
    }

    static Font font(int style, int size) {
        return new Font(FAMILY, style, size);
    }

    /**
     * 等宽字体（日志区用）。
     *
     * <p>这里必须<b>真的检查这个字体能不能画中文</b>，不能只看名字在不在。
     * 实测踩到：日志区最初选了 Cascadia Mono / Consolas 这类纯拉丁等宽字体，
     * 结果整块日志的中文全变成"豆腐块"（□）—— 字体存在、编译通过、不报错，
     * 只有真正出图才看得出来。所以候选字体要逐个用 {@link #canRenderCjk} 验过才采用。
     *
     * <p>第二个坑是勾/叉符号：{@link InstallVerify} 的输出里有 {@code ✔}（U+2714）和
     * {@code ✘}（U+2718），而 <b>Microsoft YaHei 并没有这两个字形</b> ——
     * 用雅黑渲染日志，中文正常但每行的勾叉仍会是豆腐块。
     * 所以这里用 {@link #canRenderReport} 同时验"中文 + 勾叉 + 箭头"，
     * 三个都过才采用（{@code Dialog} 是逻辑字体，会跨字体合成，通常能满足）。
     */
    static Font mono(int size) {
        java.util.List<String> have = java.util.Arrays.asList(
                java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getAvailableFontFamilyNames());

        for (String w : new String[]{"Sarasa Mono SC", "Sarasa Mono HC", "Microsoft YaHei Mono",
                "Noto Sans Mono CJK SC", "Source Han Mono SC", "Cascadia Mono", "Consolas",
                "JetBrains Mono", "Courier New"}) {
            if (have.contains(w) && canRenderReport(w)) {
                return new Font(w, Font.PLAIN, size);
            }
        }
        // 没有等宽又全能的：宁可牺牲"等宽"，也要保证中文和勾叉都能看。

        Font cjk = font(Font.PLAIN, size);
        if (canRenderReport(cjk.getFamily())) {
            return cjk;
        }

        return new Font(Font.DIALOG, Font.PLAIN, size);
    }

    /**
     * 报告文本需要的字符集：中文 + 勾叉 + 箭头。
     *
     * <p>{@link InstallVerify.Level#symbol()} 会输出 {@code ✔}/{@code !}/{@code ✘}，
     * 这些符号在很多中文字体里是缺的，必须单独验。
     */
    static boolean canRenderReport(String family) {
        Font f = new Font(family, Font.PLAIN, 12);
        for (char c : new char[]{'中', '文', '游', '戏', '：', '“', '…',
                '\u2714', '\u2718', '\u2192'}) {
            if (!f.canDisplay(c)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 这个字体能不能画出中文？
     *
     * <p>用 {@link Font#canDisplay(char)} 直接问字体本身，比维护一张"哪些字体有中文"的
     * 硬编码表可靠 —— 后者在别人的机器上必然过时。
     *
     * <p><b>注意</b>：这里直接 {@code new Font(family, ...)}，<b>不能</b>调 {@link #font}；
     * 因为 {@link #FAMILY} 的初始化会走到这个方法，而 {@code font()} 又读 {@code FAMILY}，
     * 那样就是静态初始化期的无限递归。
     */
    static boolean canRenderCjk(String family) {
        Font f = new Font(family, Font.PLAIN, 12);

        for (char c : new char[]{'中', '文', '游', '戏', '：', '“', '…'}) {
            if (!f.canDisplay(c)) {
                return false;
            }
        }
        return true;
    }

    static int r(int base, int height) {
        // 半径不得超过高度的一半，否则 RoundRectangle2D 的弧会互相吃掉。
        return Math.max(2, Math.min(base, Math.max(2, height / 2)));
    }

    /** 唯一的圆角形状出口：所有圆角都必须经过这里。 */
    static Shape roundRect(double x, double y, double w, double h, double radius) {
        double rr = Math.max(0, Math.min(radius, Math.min(w, h) / 2.0));
        return new RoundRectangle2D.Double(x, y, w, h, rr * 2, rr * 2);
    }

    /** 开抗锯齿。所有自绘都必须先调它，否则圆角会有明显台阶。 */
    static Graphics2D aa(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return g2;
    }

    /**
     * 把整块区域先铺成 {@code outside}，再涂圆角填充。
     *
     * <p>这就是"四个角干净"的关键：调用方传进来的 {@code outside} 必须是该组件所在容器的底色，
     * 于是圆角外的像素被涂成容器色，而不是组件自身的矩形背景色。
     */
    static void paintRoundedBackground(Graphics g, Component c, Color outside, Color fill,
                                       Color border, int baseRadius) {
        Graphics2D g2 = aa(g);
        int w = c.getWidth();
        int h = c.getHeight();
        if (w <= 0 || h <= 0) {
            g2.dispose();
            return;
        }
        if (outside != null) {
            g2.setColor(outside);
            g2.fillRect(0, 0, w, h);
        }
        int rad = r(baseRadius, h);
        if (fill != null) {
            g2.setColor(fill);
            g2.fill(roundRect(0, 0, w, h, rad));
        }
        if (border != null) {
            g2.setColor(border);

            g2.setStroke(new BasicStroke(1f));
            g2.draw(roundRect(0.5, 0.5, w - 1.0, h - 1.0, rad));
        }
        g2.dispose();
    }

    static Color outsideColor(Component c) {
        java.awt.Container p = c == null ? null : c.getParent();
        while (p != null) {
            if (p.isOpaque() && p.getBackground() != null) {
                return p.getBackground();
            }
            p = p.getParent();
        }
        return BG;
    }

    /**
     * 圆角卡片容器。
     *
     * <p>注意 {@code setOpaque(false)}：卡片自己负责画背景，
     * 如果让它 opaque，Swing 又会先铺一层矩形底，四个角重新变脏。
     */
    static JPanel card() {
        JPanel p = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                // 先让子组件背景先画（这里什么也不做，因为我们不透明）
                Graphics2D g2 = aa(g);
                int w = getWidth();
                int h = getHeight();
                int rad = r(14, h);
                Shape body = roundRect(0, 0, w, h, rad);

                g2.setPaint(new LinearGradientPaint(
                        new Point(0, 0), new Point(0, Math.max(1, h)),
                        new float[]{0f, 1f}, new Color[]{CARD_TOP, CARD}));
                g2.fill(body);

                g2.setColor(new Color(0xFF, 0xFF, 0xFF, 12));
                g2.fill(roundRect(1, 1, w - 2, Math.max(1, h / 3), rad));

                g2.setColor(LINE);
                g2.setStroke(new BasicStroke(1f));
                g2.draw(roundRect(0.5, 0.5, w - 1.0, h - 1.0, rad));
                g2.dispose();
            }
        };
        p.setOpaque(false);
        p.setLayout(new java.awt.BorderLayout(0, 10));

        p.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        return p;
    }

    static JLabel cardTitle(String text) {
        JLabel l = new JLabel(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = aa(g);
                int h = getHeight();
                int barH = Math.min(16, Math.max(10, h - 4));
                g2.setColor(ACCENT);
                g2.fill(roundRect(0, (h - barH) / 2.0, 3, barH, 1.5));
                g2.dispose();
                super.paintComponent(g);
            }
        };
        l.setFont(font(Font.BOLD, 14));
        l.setForeground(TEXT);
        l.setOpaque(false);
        l.setBorder(BorderFactory.createEmptyBorder(0, 11, 0, 0));
        l.setIconTextGap(9);
        return l;
    }

    static final class ButtonStyle {
        final Color base;
        final Color hover;
        final Color press;
        final Color fg;
        final Color border;
        final boolean primary;

        private ButtonStyle(Color base, Color hover, Color press, Color fg, Color border,
                            boolean primary) {
            this.base = base;
            this.hover = hover;
            this.press = press;
            this.fg = fg;
            this.border = border;
            this.primary = primary;
        }

        static ButtonStyle primary(Color accent) {
            return new ButtonStyle(accent, blend(accent, Color.WHITE, 0.18f),
                    blend(accent, Color.BLACK, 0.20f), new Color(0x0E, 0x10, 0x16), null, true);
        }

        static ButtonStyle secondary() {
            return new ButtonStyle(BTN_SECONDARY, BTN_SECONDARY_HOVER, BTN_SECONDARY_PRESS,
                    TEXT, LINE, false);
        }

        static ButtonStyle ghost() {
            return new ButtonStyle(new Color(0x1B, 0x1E, 0x28), new Color(0x25, 0x2A, 0x38),
                    new Color(0x14, 0x17, 0x1F), DIM, LINE, false);
        }
    }

    static Color blend(Color a, Color b, float t) {
        float u = 1f - t;
        return new Color(
                Math.round(a.getRed() * u + b.getRed() * t),
                Math.round(a.getGreen() * u + b.getGreen() * t),
                Math.round(a.getBlue() * u + b.getBlue() * t));
    }

    /**
     * 圆角按钮：自己画底、自己画底边线，带 hover / pressed / disabled 三态。
     *
     * <p>这里是"新 UI 无 bug"的重点之一：Swing 的 {@code JButton} 默认 UI
     * 会把 {@code setBackground} 的结果盖掉，所以必须整个接管 {@code paintComponent}，
     * 并且 {@code setContentAreaFilled(false)} + {@code setBorderPainted(false)}，
     * 否则默认 UI 会在我们画好的圆角上再糊一个矩形。
     */
    static javax.swing.JButton button(String text, ButtonStyle style, int fontSize,
                                      boolean bold) {
        javax.swing.JButton b = new javax.swing.JButton(text) {
            private boolean hover;
            private boolean press;

            {
                addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseEntered(MouseEvent e) {
                        hover = true;
                        if (isEnabled()) {
                            repaint();
                        }
                    }

                    @Override
                    public void mouseExited(MouseEvent e) {
                        hover = false;
                        press = false;
                        if (isEnabled()) {
                            repaint();
                        }
                    }

                    @Override
                    public void mousePressed(MouseEvent e) {
                        press = true;
                        if (isEnabled()) {
                            repaint();
                        }
                    }

                    @Override
                    public void mouseReleased(MouseEvent e) {
                        press = false;
                        if (isEnabled()) {
                            repaint();
                        }
                    }
                });
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = aa(g);
                int w = getWidth();
                int h = getHeight();
                int rad = r(style.primary ? 11 : 10, h);

                Color fill;
                Color fg;
                Color border = style.border;
                if (!isEnabled()) {
                    fill = blend(style.base, BG, 0.62f);
                    fg = DISABLED_TEXT;
                    border = blend(LINE, BG, 0.4f);
                } else if (press) {
                    fill = style.press;
                    fg = style.fg;
                } else if (hover) {
                    fill = style.hover;
                    fg = style.fg;
                } else {
                    fill = style.base;
                    fg = style.fg;
                }

                Shape body = roundRect(0, 0, w, h, rad);
                // 按下时整体下移 1px（用裁剪模拟，避免真的动 layout）
                if (press && isEnabled()) {
                    g2.translate(0, 1);
                }
                g2.setColor(fill);
                g2.fill(body);

                if (style.primary && isEnabled()) {
                    Area gloss = new Area(body);
                    gloss.intersect(new Area(new RoundRectangle2D.Double(
                            0, 0, w, h * 0.55, rad * 2, rad * 2)));
                    g2.setColor(new Color(0xFF, 0xFF, 0xFF, 46));
                    g2.fill(gloss);
                }
                if (border != null) {
                    g2.setColor(border);
                    g2.setStroke(new BasicStroke(1f));
                    g2.draw(roundRect(0.5, 0.5, w - 1.0, h - 1.0, rad));
                }
                // 焦点圈：键盘 Tab 走过来要看得见，否则"能点"但不知道焦点在哪
                if (isFocusOwner() && isEnabled()) {
                    g2.setColor(new Color(0xFF, 0xFF, 0xFF, 70));
                    g2.setStroke(new BasicStroke(1.4f));
                    g2.draw(roundRect(2.2, 2.2, w - 4.4, h - 4.4, rad - 1));
                }
                g2.dispose();

                Graphics2D gt = aa(g);
                if (press && isEnabled()) {
                    gt.translate(0, 1);
                }
                Font f = RoundedUi.font(bold ? Font.BOLD : Font.PLAIN, fontSize);
                gt.setFont(f);
                gt.setColor(fg);
                FontMetrics fm = gt.getFontMetrics();
                String label = getText();
                int tx = (w - fm.stringWidth(label)) / 2;
                int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
                gt.drawString(label, tx, ty);
                gt.dispose();
            }
        };
        b.setFont(font(bold ? Font.BOLD : Font.PLAIN, fontSize));
        b.setForeground(style.fg);
        b.setFocusPainted(false);
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setOpaque(false);
        b.setRolloverEnabled(true);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        int padY = style.primary ? 12 : 11;
        b.setBorder(BorderFactory.createEmptyBorder(padY, 18, padY, 18));
        b.setPreferredSize(null);
        if (style.primary) {
            b.setFont(font(Font.BOLD, fontSize));
        }
        return b;
    }

    /**
     * 圆角文本框。
     *
     * <p>做法：把 {@link JTextField} 的背景设成不透明但填透明色，
     * 由 {@link #paintRoundedBackground} 负责画圆角 + 描边，
     * 再让 {@link BasicTextFieldUI} 画文字。这样光标、选区、输入法全部保留原生行为，
     * 我们只换皮 —— 比自绘一整个编辑器安全得多。
     */
    static JTextField field(String text, int columns, boolean editable) {
        JTextField f = new JTextField(text, columns) {
            @Override
            protected void paintComponent(Graphics g) {
                paintRoundedBackground(g, this, outsideColor(this), FIELD,
                        isFocusOwner() && isEditable() ? ACCENT : LINE, 9);
                super.paintComponent(g);
            }
        };
        f.setUI(new BasicTextFieldUI());
        styleTextInput(f);
        f.setEditable(editable);
        if (!editable) {
            f.setCursor(Cursor.getDefaultCursor());
        }
        return f;
    }

    static void styleTextInput(JTextComponent f) {
        f.setFont(font(Font.PLAIN, 13));
        f.setForeground(TEXT);
        f.setCaretColor(ACCENT);
        f.setSelectionColor(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 90));
        f.setSelectedTextColor(TEXT);
        f.setOpaque(false);
        f.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        // 聚焦/失焦时重画描边，让焦点态看得见（否则用户不知道在哪个框里打字）
        f.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent e) {
                f.repaint();
            }

            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                f.repaint();
            }
        });
    }

    /**
     * 圆角下拉框（<b>自绘弹出列表</b>）。
     *
     * <p>为什么不用 {@link JComboBox} 自带的弹出列表：它的弹窗是 heavyweight popup，
     * 由操作系统画边框和背景，<b>圆角对它无效</b> —— 主界面是圆角、一展开却弹出个
     * 直角白框，非常突兀（这也是 Lead 特别点名的地方）。
     *
     * <p>所以这里改成：弹出的列表是<b>我们自己的 {@code JPopupMenu}（轻量级，Swing 自绘）</b>，
     * 由它承载一个圆角面板。选择、键盘上下、点击外部关闭、Escape 关闭这些行为
     * 都保留（用 {@code JPopupMenu} 就有了，不用自己造轮子）。
     */
    static JComboBox<String> combo() {
        JComboBox<String> box = new JComboBox<>() {
            @Override
            protected void paintComponent(Graphics g) {
                paintRoundedBackground(g, this, outsideColor(this), FIELD,
                        isPopupVisible() ? ACCENT : LINE, 9);
                super.paintComponent(g);
            }
        };
        box.setFont(font(Font.PLAIN, 13));
        box.setForeground(TEXT);
        box.setOpaque(false);
        box.setFocusable(true);
        box.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 2));
        box.setRenderer(new RoundedComboRenderer());
        box.setUI(new BasicComboBoxUI() {
            @Override
            protected javax.swing.JButton createArrowButton() {
                return new ArrowButton();
            }

            @Override
            public void paintCurrentValueBackground(Graphics g, Rectangle bounds,
                                                    boolean hasFocus) {
                // 背景已由外层 paintComponent 画好，这里必须什么都不做，
                // 否则默认 UI 会再把矩形底铺一遍，圆角立刻被盖掉。
            }
        });
        box.setBackground(FIELD);

        box.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                box.repaint();
            }

            @Override
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {
                box.repaint();
            }

            @Override
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {
                box.repaint();
            }
        });
        return box;
    }

    /**
     * 把下拉框的弹出列表换成"圆角 + 深色"的版本。
     *
     * <p>做法：拿到 UI 内部那个 {@link JPopupMenu}，给它套一个自绘圆角边框。
     * 轻量级 popup 是 Swing 自绘的，圆角才会生效（heavyweight 的不会）。
     *
     * <p><b>踩坑记录</b>：最初只用 {@code ui.getAccessibleChild(box, 0)} 取 popup，
     * 实测**返回 null**（本机 JDK 21 的 BasicComboBoxUI 走的是另一条路径），
     * 于是整个方法静默失效 —— 代码在、编译过、不报错，弹出列表其实还是方角。
     * 所以这里改用"多策略查找"，任何一条成功即可，并且把结果记进
     * {@link #popupStyled} 供自检读取（不能静默失败）。
     */
    static void styleComboPopup(JComboBox<String> box) {
        // 弹出是懒创建的：第一次展开前 popup 可能还不存在，所以两个时机都试。
        if (tryStylePopup(box)) {
            return;
        }
        box.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                tryStylePopup(box);
            }

            @Override
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {
            }

            @Override
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {
            }
        });
    }

    static boolean popupStyled(JComboBox<?> box) {
        Object v = box.getClientProperty("fusion.popupRounded");
        return Boolean.TRUE.equals(v);
    }

    /**
     * 试着给下拉框的弹窗套圆角。找到并套好返回 true。
     *
     * <p>查找顺序（由一个踩坑驱动，见 {@link #styleComboPopup} 的注释）：
     * <ol>
     *   <li>{@code getAccessibleChild} —— 标准做法，但实测可能返回 null；</li>
     *   <li>反射读 UI 的 {@code popup} 字段（BasicComboBoxUI 有这个字段）；</li>
     *   <li>从 UI 里找 {@code ComboPopup}（{@code getPopup()} 之类）。</li>
     * </ol>
     */
    private static boolean tryStylePopup(JComboBox<String> box) {
        Object popup = null;
        javax.swing.plaf.ComboBoxUI ui = box.getUI();

        // 策略 1：标准 accessible 接口（实测在 JDK21 上返回 null，但不能假设都没有）
        try {
            popup = ui.getAccessibleChild(box, 0);
        } catch (Throwable ignored) {

        }

        // 关键：BasicComboBoxUI.popup 的**声明类型是 ComboBoxPopup 接口**，不是 JPopupMenu！
        // 所以直接 instanceof JPopupMenu 会失败 —— 这正是本方法最初"看起来实现了、
        // 实际完全没生效"的原因。真正的弹出菜单在 ComboPopup 内部，得再取一层。
        if (popup == null) {
            popup = readField(ui, "popup");
        }
        if (popup == null) {
            popup = readField(ui, "popupMenu");
        }
        JPopupMenu menu = toPopupMenu(popup);
        if (menu == null) {
            return false;
        }
        applyRoundedPopup(menu);
        box.putClientProperty("fusion.popupRounded", Boolean.TRUE);
        return true;
    }

    /**
     * 把 {@code ComboPopup} / {@code JPopupMenu} / 别的什么，统一解成 {@link JPopupMenu}。
     *
     * <p>这是这个功能真正能生效的关键一步：Swing 的 {@code BasicComboPopup}
     * 自己**就是**一个 {@code JPopupMenu} 的子类（{@code BasicComboPopup extends JPopupMenu}），
     * 所以拿到 ComboPopup 实例后可以直接 instanceof JPopupMenu 判断；
     * 但即便拿到的不是，也还能从它内部再找一层 {@code popup} 字段。
     */
    private static JPopupMenu toPopupMenu(Object popup) {
        if (popup instanceof JPopupMenu m) {
            return m;
        }
        if (popup == null) {
            return null;
        }

        Object inner = readField(popup, "popup");
        if (inner instanceof JPopupMenu m) {
            return m;
        }
        return null;
    }

    private static Object readField(Object target, String name) {
        if (target == null) {
            return null;
        }
        for (Class<?> k = target.getClass(); k != null; k = k.getSuperclass()) {
            try {
                java.lang.reflect.Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {

            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static void applyRoundedPopup(JPopupMenu menu) {
        if (Boolean.TRUE.equals(menu.getClientProperty("fusion.popupRounded"))) {
            return;
        }
        menu.putClientProperty("fusion.popupRounded", Boolean.TRUE);
        menu.setOpaque(false);

        final int rad = 10;
        menu.setBorder(new javax.swing.border.AbstractBorder() {
            @Override
            public Insets getBorderInsets(Component c) {
                return new Insets(7, 6, 8, 6);
            }

            @Override
            public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
                Graphics2D g2 = aa(g);
                Shape body = roundRect(x + 0.5, y + 0.5, w - 1.0, h - 1.0, rad);
                g2.setColor(CARD);
                g2.fill(body);
                g2.setColor(LINE_HI);
                g2.setStroke(new BasicStroke(1f));
                g2.draw(body);
                g2.dispose();
            }
        });
        // 弹出菜单里的列表/滚动面板必须透明，才能露出我们画的圆角底。
        makeTransparentDeep(menu);
    }

    private static void makeTransparentDeep(Container c) {
        for (Component x : c.getComponents()) {
            if (x instanceof JComponent jc) {
                jc.setOpaque(false);
                if (jc instanceof javax.swing.JScrollPane sp) {
                    sp.setBorder(BorderFactory.createEmptyBorder());
                    sp.setViewportBorder(null);
                    sp.getViewport().setOpaque(false);
                    styleScrollBar(sp.getVerticalScrollBar());
                }
            }
            if (x instanceof Container inner && !(x instanceof JScrollBar)) {
                makeTransparentDeep(inner);
            }
        }
    }

    /** 下拉框右侧的小三角，自己画，保证和圆角风格一致。 */
    private static final class ArrowButton extends javax.swing.JButton {
        ArrowButton() {
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusable(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(24, 20));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = aa(g);
            int w = getWidth();
            int h = getHeight();
            int cx = w / 2;
            int cy = h / 2;
            Path2D p = new Path2D.Double();
            p.moveTo(cx - 4.5, cy - 2.0);
            p.lineTo(cx + 4.5, cy - 2.0);
            p.lineTo(cx, cy + 3.5);
            p.closePath();
            g2.setColor(isEnabled() ? DIM : DISABLED_TEXT);
            g2.fill(p);
            g2.dispose();
        }
    }

    private static final class RoundedComboRenderer extends javax.swing.DefaultListCellRenderer {

        private boolean rowSelected;

        @Override
        public Component getListCellRendererComponent(javax.swing.JList<?> list, Object value,
                                                      int index, boolean selected,
                                                      boolean focus) {
            JLabel l = (JLabel) super.getListCellRendererComponent(list, value, index, selected,
                    focus);

            // 而我们要的是圆角色块，所以必须自己记住这个标志（用 clientProperty 会残留上一次的值）。
            this.rowSelected = selected;
            l.setFont(font(Font.PLAIN, 13));
            l.setForeground(selected ? new Color(0x0E, 0x10, 0x16) : TEXT);
            l.setOpaque(false);
            l.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
            return l;
        }

        @Override
        public void paintComponent(Graphics g) {

            if (rowSelected) {
                Graphics2D g2 = aa(g);
                g2.setColor(ACCENT);
                g2.fill(roundRect(3, 1, getWidth() - 6, getHeight() - 2, 6));
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    static final class RoundedProgressBar extends JPanel {
        private int value;
        private int min;
        private int max = 100;
        private boolean indeterminate;
        private float phase;

        RoundedProgressBar(int min, int max) {
            this.min = min;
            this.max = Math.max(min + 1, max);
            setOpaque(false);
            setPreferredSize(new Dimension(120, 12));
            // 不确定态的动画只在可见时跑，避免离屏出图时白烧 CPU
            new javax.swing.Timer(40, e -> {
                if (indeterminate && isShowing()) {
                    phase = (phase + 0.035f) % 1f;
                    repaint();
                }
            }).start();
        }

        void setValue(int v) {
            int nv = Math.max(min, Math.min(max, v));
            if (nv != value) {
                value = nv;
                repaint();
            }
        }

        int getValue() {
            return value;
        }

        void setIndeterminate(boolean b) {
            if (indeterminate != b) {
                indeterminate = b;
                repaint();
            }
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = aa(g);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                g2.dispose();
                return;
            }
            int rad = r(7, h);
            Shape track = roundRect(0, 0, w, h, rad);
            g2.setColor(new Color(0x0E, 0x10, 0x16));
            g2.fill(track);
            g2.setColor(LINE);
            g2.setStroke(new BasicStroke(1f));
            g2.draw(roundRect(0.5, 0.5, w - 1.0, h - 1.0, rad));

            Shape old = g2.getClip();
            g2.clip(track);
            Color barColor = isEnabled() ? ACCENT : blend(ACCENT, BG, 0.6f);
            if (indeterminate) {
                int span = Math.max(40, w / 4);
                int x = (int) ((w + span) * phase) - span;
                g2.setPaint(new LinearGradientPaint(
                        new Point(Math.max(0, x), 0), new Point(Math.max(1, x + span), 0),
                        new float[]{0f, 0.5f, 1f},
                        new Color[]{new Color(barColor.getRed(), barColor.getGreen(),
                                barColor.getBlue(), 0),
                                barColor,
                                new Color(barColor.getRed(), barColor.getGreen(),
                                        barColor.getBlue(), 0)}));
                g2.fillRect(x, 0, span, h);
            } else {
                double pct = (value - min) / (double) (max - min);
                int fillW = (int) Math.round(w * pct);
                if (fillW > 0) {
                    g2.setPaint(new LinearGradientPaint(
                            new Point(0, 0), new Point(0, Math.max(1, h)),
                            new float[]{0f, 1f},
                            new Color[]{blend(barColor, Color.WHITE, 0.25f), barColor}));
                    g2.fillRect(0, 0, fillW, h);
                }
            }
            g2.setClip(old);
            g2.dispose();
        }
    }

    static void styleScrollBar(JScrollBar bar) {
        bar.setOpaque(false);
        bar.setPreferredSize(new Dimension(10, 10));
        bar.setUI(new BasicScrollBarUI() {
            @Override
            protected void configureScrollBarColors() {

            }

            @Override
            protected javax.swing.JButton createDecreaseButton(int orientation) {
                return zeroButton();
            }

            @Override
            protected javax.swing.JButton createIncreaseButton(int orientation) {
                return zeroButton();
            }

            private javax.swing.JButton zeroButton() {
                javax.swing.JButton b = new javax.swing.JButton();
                b.setPreferredSize(new Dimension(0, 0));
                b.setMinimumSize(new Dimension(0, 0));
                b.setMaximumSize(new Dimension(0, 0));
                b.setBorder(BorderFactory.createEmptyBorder());
                b.setOpaque(false);
                b.setContentAreaFilled(false);
                b.setFocusable(false);
                return b;
            }

            @Override
            protected void paintTrack(Graphics g, javax.swing.JComponent c, Rectangle r) {
                Graphics2D g2 = aa(g);
                g2.setColor(new Color(0x1A, 0x1D, 0x26));
                g2.fill(roundRect(r.x + 1, r.y, Math.max(1, r.width - 2), r.height, 4));
                g2.dispose();
            }

            @Override
            protected void paintThumb(Graphics g, javax.swing.JComponent c, Rectangle r) {
                if (r.height <= 0 || r.width <= 0 || !c.isEnabled()) {
                    return;
                }
                Graphics2D g2 = aa(g);
                g2.setColor(isThumbRollover() ? LINE_HI : LINE);
                g2.fill(roundRect(r.x + 1, r.y + 1, Math.max(1, r.width - 2),
                        Math.max(1, r.height - 2), 4));
                g2.dispose();
            }
        });
    }

    static JScrollPane scroll(Component view) {
        JScrollPane sp = new JScrollPane(view);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        styleScrollBar(sp.getVerticalScrollBar());
        styleScrollBar(sp.getHorizontalScrollBar());
        JViewport vp = sp.getViewport();
        vp.setScrollMode(JViewport.BLIT_SCROLL_MODE);
        return sp;
    }

    /**
     * 让"外层滚动"停在整行边界上，避免露出半行文字。
     *
     * <h3>要解决的问题</h3>
     * 日志区本身是可滚动的（有自己的滚动条），但当整个窗口高度不够时，
     * <b>外层</b>那一圈滚动会把日志卡从中间截断 —— 截断位置正好落在某一行文字上，
     * 用户看到的就是"一行字被横切成两半"，观感像渲染坏了。
     *
     * <p>严格说这不是布局 bug（滚动容器边界本来就会落在任意像素上），
     * 但它确实是真实的观感问题，而且很好修：只要让外层滚动的位置
     * <b>吸附到行高整数倍</b>，可见边界就永远落在两行之间，不会切到字形。
     *
     * <h3>做法</h3>
     * 自定义 {@link JViewport}，覆写 {@code setViewPosition}：
     * 把 y 吸附到 {@code lineHeight} 的整数倍。只吸附 y、不动 x
     * （横向滚动不开放，见 {@code HORIZONTAL_SCROLLBAR_NEVER}）。
     *
     * @param sp         要改造的滚动面板
     * @param lineHeight 行高（像素）；传 &lt;= 0 表示不吸附
     */
    static void snapToLines(JScrollPane sp, int lineHeight) {
        if (sp == null || lineHeight <= 0) {
            return;
        }
        JViewport old = sp.getViewport();
        JViewport snapping = new JViewport() {
            @Override
            public void setViewPosition(Point p) {
                int snapped = Math.round(p.y / (float) lineHeight) * lineHeight;
                super.setViewPosition(new Point(p.x, snapped));
            }
        };
        snapping.setOpaque(false);
        snapping.setScrollMode(old.getScrollMode());
        if (old.getView() != null) {
            snapping.setView(old.getView());
        }
        sp.setViewport(snapping);
    }

    /**
     * 按组件实际字体算出单行高度（像素）。
     *
     * <p>用组件自己的 {@link FontMetrics} 而不是写死 16px：换字体、改字号、
     * 或系统 DPI 不同，行高都会变；写死就会"吸附错位"——
     * 那比不吸附更糟（会稳定地切在半行上）。
     */
    static int lineHeightOf(JTextComponent c) {
        if (c == null) {
            return 0;
        }
        FontMetrics fm = c.getFontMetrics(c.getFont());
        return fm == null ? 0 : fm.getHeight();
    }

    static javax.swing.JCheckBox check(String text, boolean selected) {
        javax.swing.JCheckBox b = new javax.swing.JCheckBox(text, selected) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = aa(g);
                int h = getHeight();
                int box = 15;
                int y = (h - box) / 2;
                int rad = 5;
                Shape sq = roundRect(1, y, box, box, rad);
                if (isSelected()) {
                    g2.setColor(isEnabled() ? OK : blend(OK, BG, 0.6f));
                    g2.fill(sq);

                    g2.setColor(new Color(0x0E, 0x10, 0x16));
                    g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    Path2D p = new Path2D.Double();
                    p.moveTo(4.6, y + box * 0.52);
                    p.lineTo(7.0, y + box * 0.74);
                    p.lineTo(12.0, y + box * 0.28);
                    g2.draw(p);
                } else {
                    g2.setColor(FIELD);
                    g2.fill(sq);
                    g2.setColor(isEnabled() ? LINE_HI : LINE);
                    g2.setStroke(new BasicStroke(1f));
                    g2.draw(roundRect(1.5, y + 0.5, box - 1, box - 1, rad));
                }
                g2.dispose();

                Graphics2D gt = aa(g);
                gt.setFont(getFont());
                gt.setColor(isEnabled() ? getForeground() : DISABLED_TEXT);
                FontMetrics fm = gt.getFontMetrics();
                gt.drawString(getText(), box + 9, (h - fm.getHeight()) / 2 + fm.getAscent());
                gt.dispose();
            }

            @Override
            public Dimension getPreferredSize() {
                FontMetrics fm = getFontMetrics(getFont());
                return new Dimension(box + 9 + fm.stringWidth(getText()) + 4,
                        Math.max(22, fm.getHeight() + 4));
            }

            private static final int box = 15;
        };
        b.setOpaque(false);
        b.setFont(font(Font.PLAIN, 12));
        b.setForeground(DIM);
        b.setFocusPainted(false);
        b.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        b.setIcon(null);
        b.setIconTextGap(0);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setContentAreaFilled(false);
        return b;
    }

    /**
     * 把全局默认值调到深色圆角风格。
     *
     * <p>这一步不是装饰：默认 Look&amp;Feel（Metal/Nimbus）会给
     * {@code JLabel} 之类的组件强加浅色前景色，在深色底上就是"黑字黑底看不见"。
     * 基线截图里"游戏版本 / 三端模组"这些标签就是这个问题。
     * 在这里统一改掉，比在每个 JLabel 上逐个 setForeground 更不容易漏。
     */
    static void installDefaults() {
        UIManager.put("Label.foreground", TEXT);
        UIManager.put("Label.disabledForeground", DISABLED_TEXT);
        UIManager.put("Label.font", font(Font.PLAIN, 12));
        UIManager.put("Panel.background", BG);
        UIManager.put("OptionPane.background", CARD);
        UIManager.put("OptionPane.messageForeground", TEXT);
        UIManager.put("OptionPane.messageFont", font(Font.PLAIN, 13));
        UIManager.put("OptionPane.buttonFont", font(Font.PLAIN, 13));
        UIManager.put("Button.font", font(Font.PLAIN, 13));
        UIManager.put("ComboBox.background", FIELD);
        UIManager.put("ComboBox.foreground", TEXT);
        UIManager.put("ComboBox.selectionBackground", ACCENT);
        UIManager.put("ComboBox.selectionForeground", new Color(0x0E, 0x10, 0x16));
        UIManager.put("ComboBox.font", font(Font.PLAIN, 13));
        UIManager.put("TextField.background", FIELD);
        UIManager.put("TextField.foreground", TEXT);
        UIManager.put("TextField.caretForeground", ACCENT);
        UIManager.put("TextField.font", font(Font.PLAIN, 13));
        UIManager.put("CheckBox.background", BG);
        UIManager.put("CheckBox.foreground", TEXT);
        UIManager.put("CheckBox.font", font(Font.PLAIN, 12));
        UIManager.put("ScrollPane.background", BG);
        UIManager.put("Viewport.background", BG);
        UIManager.put("TextArea.background", new Color(0x12, 0x14, 0x1C));
        UIManager.put("TextArea.foreground", new Color(0xC8, 0xD2, 0xE8));

        UIManager.put("ComboBox.popup.background", CARD);
        UIManager.put("List.background", CARD);
        UIManager.put("List.foreground", TEXT);
        UIManager.put("List.selectionBackground", ACCENT);
        UIManager.put("List.selectionForeground", new Color(0x0E, 0x10, 0x16));
        UIManager.put("ScrollBar.background", BG);
    }

    static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    static final int WINDOW_RADIUS = 18;

    /**
     * 把整个窗口做成圆角。
     *
     * <p>这里必须用 <b>PERPIXEL_TRANSLUCENT + 自绘透明角</b>，而不是
     * {@code setShape()}：后者在 Windows 上会把标题栏一起切掉（窗口就没法拖了），
     * 而且和 {@code pack()} 的时序很容易打架。现在的做法是：
     * <ol>
     *   <li>窗口设为 {@code TRANSLUCENT}（支持逐像素透明）；</li>
     *   <li>内容面板画成一个圆角矩形，四个角<b>不画</b> —— 于是那四个角是透明的，
     *       底下露出桌面，看起来就是真正的圆角窗口；</li>
     *   <li>无边框（{@code setUndecorated(true)}），否则系统标题栏是方的，套不上。</li>
     * </ol>
     *
     * <p>无边框的代价是窗口不能拖动、没有关闭按钮，所以这里同时把
     * <b>拖动（任意空白处按住拖）+ 右上角关闭按钮</b>补上，功能不能少。
     *
     * <p><b>离屏出图时不要调用</b>：{@code TRANSLUCENT} 在无头/离屏渲染下拿不到
     * 桌面背景，四个角会画成黑色而不是透明，截图反而更难看。截图路径走
     * {@link #paintWindowChrome} 直接画到不透明底上。
     */
    static void makeWindowRounded(javax.swing.JFrame frame) {
        try {
            frame.setUndecorated(true);
            frame.setBackground(new Color(0, 0, 0, 0));
            // 有透明度支持才启用，否则退回普通窗口（宁可不圆，也不能变成黑窗）
            java.awt.GraphicsDevice gd = java.awt.GraphicsEnvironment
                    .getLocalGraphicsEnvironment().getDefaultScreenDevice();
            if (java.awt.GraphicsEnvironment.isHeadless()
                    || !gd.isWindowTranslucencySupported(
                            java.awt.GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)) {
                frame.setUndecorated(false);
                return;
            }
            installDragAndClose(frame);
        } catch (Throwable t) {

            try {
                frame.setUndecorated(false);
            } catch (Throwable ignored) {

            }
        }
    }

    /** 无边框窗口的拖动 + 关闭按钮（不能因为好看去掉基本操作）。 */
    private static void installDragAndClose(javax.swing.JFrame frame) {
        final Point[] dragFrom = {null};
        frame.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                dragFrom[0] = e.getPoint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragFrom[0] = null;
            }
        });
        frame.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragFrom[0] != null) {
                    Point p = e.getLocationOnScreen();
                    frame.setLocation(p.x - dragFrom[0].x, p.y - dragFrom[0].y);
                }
            }
        });
    }

    static JPanel windowChrome(java.util.function.BiConsumer<Graphics2D, Shape> extraPaint) {
        JPanel p = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = aa(g);
                int w = getWidth();
                int h = getHeight();
                Shape body = roundRect(0, 0, w, h, WINDOW_RADIUS);
                g2.setColor(BG);
                g2.fill(body);
                if (extraPaint != null) {
                    extraPaint.accept(g2, body);
                }

                g2.setColor(new Color(0x3A, 0x42, 0x58));
                g2.setStroke(new BasicStroke(1f));
                g2.draw(roundRect(0.5, 0.5, w - 1.0, h - 1.0, WINDOW_RADIUS));
                g2.dispose();
            }
        };
        p.setOpaque(false);
        return p;
    }

    /**
     * 自绘一个"最小化 / 关闭"按钮（无边框窗口的必备件）。
     *
     * @param kind {@code "close"} 画叉，其它画一条横线（最小化）
     */
    static javax.swing.JButton chromeButton(String kind) {
        boolean close = "close".equals(kind);
        javax.swing.JButton b = new javax.swing.JButton() {
            private boolean hover;

            {
                addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseEntered(MouseEvent e) {
                        hover = true;
                        repaint();
                    }

                    @Override
                    public void mouseExited(MouseEvent e) {
                        hover = false;
                        repaint();
                    }
                });
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = aa(g);
                int w = getWidth();
                int h = getHeight();
                if (hover) {
                    g2.setColor(close ? new Color(0x7A, 0x28, 0x33) : new Color(0x2E, 0x35, 0x46));
                    g2.fill(roundRect(0, 0, w, h, 7));
                }
                g2.setColor(hover && close ? Color.WHITE : DIM);
                g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int cx = w / 2;
                int cy = h / 2;
                int s = 4;
                if (close) {
                    g2.drawLine(cx - s, cy - s, cx + s, cy + s);
                    g2.drawLine(cx - s, cy + s, cx + s, cy - s);
                } else {
                    g2.drawLine(cx - s, cy, cx + s, cy);
                }
                g2.dispose();
            }
        };
        b.setPreferredSize(new Dimension(30, 26));
        b.setOpaque(false);
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusable(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setToolTipText(close ? "关闭" : "最小化");
        return b;
    }

    static JPanel titleBar(String text, javax.swing.JFrame frame) {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        JLabel title = new JLabel(text);
        title.setFont(font(Font.PLAIN, 12));
        title.setForeground(DIM);
        title.setBorder(BorderFactory.createEmptyBorder(0, 20, 0, 0));

        JPanel right = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0));
        right.setOpaque(false);
        javax.swing.JButton min = chromeButton("min");
        min.addActionListener(e -> frame.setState(java.awt.Frame.ICONIFIED));
        javax.swing.JButton close = chromeButton("close");
        close.addActionListener(e -> {
            frame.dispose();
            // 无边框窗口是唯一的窗口，关掉就该退出进程，否则会留下一个看不见的 JVM。
            System.exit(0);
        });
        right.add(min);
        right.add(close);
        right.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 8));

        bar.add(title, BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);

        final Point[] from = {null};
        bar.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                from[0] = e.getPoint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                from[0] = null;
            }
        });
        bar.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (from[0] != null) {
                    Point p = e.getLocationOnScreen();
                    frame.setLocation(p.x - from[0].x, p.y - from[0].y);
                }
            }
        });
        return bar;
    }

    static java.awt.image.BufferedImage roundWindowShot(
            java.awt.image.BufferedImage src, Color outside) {
        int w = src.getWidth();
        int h = src.getHeight();
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(
                w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        Graphics2D g = aa(out.createGraphics());
        g.setColor(outside);
        g.fillRect(0, 0, w, h);
        Shape clip = roundRect(0, 0, w, h, WINDOW_RADIUS);
        g.setClip(clip);
        g.drawImage(src, 0, 0, null);
        g.setClip(null);
        g.setColor(new Color(0x3A, 0x42, 0x58));
        g.setStroke(new BasicStroke(1f));
        g.draw(roundRect(0.5, 0.5, w - 1.0, h - 1.0, WINDOW_RADIUS));
        g.dispose();
        return out;
    }
}
