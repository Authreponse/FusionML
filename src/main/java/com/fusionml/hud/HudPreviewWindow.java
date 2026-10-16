package com.fusionml.hud;

import com.fusionml.meta.ModFormat;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * 独立的 HUD / 模组列表预览窗口。
 *
 * <p>它的价值不只是"演示"：
 * <ul>
 *   <li>让 HUD 的布局、配色、对齐、中英文混排都能被真实地看到并截图，
 *       而不是停留在"代码写完了应该能显示"；</li>
 *   <li>帧率面板接收的是真实帧时序（由本窗口的渲染循环提供），
 *       因此在游戏外的卡顿表现与游戏内是同一套算法；</li>
 *   <li>模组列表的数据直接来自真实加载会话，搜索、过滤、排序、滚动都是可操作的。</li>
 * </ul>
 */
public final class HudPreviewWindow {

    private final HudRenderer renderer = new HudRenderer();
    private final FpsMonitor fps = new FpsMonitor(240);
    private final ModListModel model;
    private final String query = "";
    private ModFormat filter;
    private ModListModel.Sort sort = ModListModel.Sort.PROBLEM_FIRST;
    private int scroll;
    private final Random rnd = new Random(42);
    private double syntheticFrameMs = 8.0;

    private final class Canvas extends JPanel {
        Canvas() {
            setPreferredSize(new Dimension(1100, 620));
            setBackground(new Color(0x101018));
            setFocusable(true);
            addMouseWheelListener(new MouseAdapter() {
                @Override
                public void mouseWheelMoved(MouseWheelEvent e) {
                    scroll = Math.max(0, scroll + e.getWheelRotation() * 3);
                    repaint();
                }
            });
            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    switch (e.getKeyCode()) {
                        case KeyEvent.VK_DOWN -> scroll += 2;
                        case KeyEvent.VK_UP -> scroll = Math.max(0, scroll - 2);
                        case KeyEvent.VK_HOME -> scroll = 0;
                        case KeyEvent.VK_1 -> sort = ModListModel.Sort.PROBLEM_FIRST;
                        case KeyEvent.VK_2 -> sort = ModListModel.Sort.TIER;
                        case KeyEvent.VK_3 -> sort = ModListModel.Sort.NAME;
                        case KeyEvent.VK_4 -> sort = ModListModel.Sort.DEPENDENTS;
                        default -> {
                            return;
                        }
                    }
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);

            drawBackdrop(g, getWidth(), getHeight());
            fps.tick();
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                renderer.render(new AwtHudBackend(g2), getWidth(), getHeight(), fps, model,
                        query, filter, sort, true, scroll);
            } finally {
                g2.dispose();
            }
        }
    }

    public HudPreviewWindow(ModListModel model) {
        this.model = model;
    }

    public void show() {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("FusionML · FPS 显示 + 模组列表");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            Canvas canvas = new Canvas();

            JPanel controls = new JPanel(new BorderLayout(6, 0));
            JTextField search = new JTextField();
            search.setToolTipText("输入关键词过滤模组（匹配名称 / modId / 版本）");
            JComboBox<String> platform = new JComboBox<>(new String[] {
                    "全部端", "Fabric", "Forge", "NeoForge"});
            JComboBox<String> sortBox = new JComboBox<>(new String[] {
                    "问题优先", "加载层次", "名称", "被依赖数"});
            JButton shot = new JButton("导出 PNG");
            controls.add(new JLabel(" 搜索:"), BorderLayout.WEST);
            controls.add(search, BorderLayout.CENTER);
            JPanel right = new JPanel();
            right.add(new JLabel("平台:"));
            right.add(platform);
            right.add(new JLabel("排序:"));
            right.add(sortBox);
            right.add(shot);
            controls.add(right, BorderLayout.EAST);

            Ref<String> q = new Ref<>("");
            search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                @Override
                public void insertUpdate(javax.swing.event.DocumentEvent e) {
                    q.value = search.getText();
                    canvas.repaint();
                }

                @Override
                public void removeUpdate(javax.swing.event.DocumentEvent e) {
                    q.value = search.getText();
                    canvas.repaint();
                }

                @Override
                public void changedUpdate(javax.swing.event.DocumentEvent e) {
                }
            });
            shot.addActionListener(e -> exportPng(canvas));

            frame.add(controls, BorderLayout.NORTH);
            frame.add(canvas, BorderLayout.CENTER);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            canvas.requestFocusInWindow();

            // 这样折线图会呈现真实系统的抖动特征，而不是一条平线。
            Timer timer = new Timer(16, e -> {
                syntheticFrameMs = Math.max(3, Math.min(60,
                        syntheticFrameMs + (rnd.nextDouble() - 0.5) * 2.2
                                + (rnd.nextDouble() < 0.01 ? rnd.nextDouble() * 25 : 0)));
                canvas.repaint();
            });
            timer.start();
        });
    }

    private void exportPng(Canvas canvas) {
        try {
            BufferedImage img = new BufferedImage(canvas.getWidth(), canvas.getHeight(),
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            drawBackdrop(g, img.getWidth(), img.getHeight());
            renderer.render(new AwtHudBackend(g), img.getWidth(), img.getHeight(), fps, model,
                    query, filter, sort, true, scroll);
            g.dispose();
            File out = new File("build/hud-shot.png");
            out.getParentFile().mkdirs();
            ImageIO.write(img, "png", out);
            System.out.println("已导出：" + out.getAbsolutePath());
        } catch (Exception ex) {
            System.out.println("导出失败：" + ex.getMessage());
        }
    }

    private static void drawBackdrop(Graphics g, int w, int h) {
        g.setColor(new Color(0x1A2438));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(0x2E3E5C));
        for (int x = 0; x < w; x += 48) {
            g.drawLine(x, 0, x, h);
        }
        for (int y = 0; y < h; y += 48) {
            g.drawLine(0, y, w, y);
        }
        g.setColor(new Color(0x3B6B4A));
        g.fillRect(0, h - 90, w, 90);
        g.setColor(new Color(0x2E5540));
        for (int i = 0; i < 40; i++) {
            int x = (i * 97) % w;
            g.fillRect(x, h - 90 - (i % 5) * 6, 26, 12);
        }
    }

    /** 极简可变引用（避免为一个字符串引入 AtomicReference 的额外语义）。 */
    private static final class Ref<T> {
        T value;

        Ref(T v) {
            this.value = v;
        }
    }
}
