package com.fusionml.install;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

/**
 * 图形安装器。
 *
 * <p>设计目标很具体：<b>双击就能用，一眼能看懂，出错能看懂为什么</b>。
 * 所以它做了三件在普通安装器里少见但这里很值得做的事：
 * <ol>
 *   <li><b>先体检再安装</b>：进目录就扫一遍"有哪些版本、三端模组各多少、Java 够不够"，
 *       因为这些正是这个加载器要解决的问题，也是用户最想确认的事；</li>
 *   <li><b>进度可见</b>：安装的每一步都映射到进度条与文字，不做那种"点了没反应"的界面；</li>
 *   <li><b>与命令行同一套逻辑</b>：所有动作都走 {@link FusionInstaller}，
 *       图形与命令行两条路径的行为必然一致，不会出现"界面装完和命令装完不一样"。</li>
 * </ol>
 *
 * <p>配色走深色系：和早期窗口、游戏内 HUD 是同一套观感，用户从安装到进游戏看到的是一致的东西。
 */
final class InstallerGui {

    private static final Color BG = new Color(0x14, 0x16, 0x1E);
    private static final Color CARD = new Color(0x1E, 0x21, 0x2C);
    private static final Color LINE = new Color(0x2E, 0x33, 0x44);
    private static final Color TEXT = new Color(0xE8, 0xEC, 0xF6);
    private static final Color DIM = new Color(0x9A, 0xA3, 0xC4);
    private static final Color ACCENT = new Color(0x5A, 0xC8, 0xFA);
    private static final Color OK = new Color(0x54, 0xD9, 0x8C);
    private static final Color WARN = new Color(0xF2, 0xC1, 0x4E);
    private static final Color BAD = new Color(0xEF, 0x5B, 0x6B);

    private InstallerGui() {
    }

    static void show(FusionInstaller.Options initial) {
        SwingUtilities.invokeLater(() -> build(initial, null));
    }

    /**
     * 生成界面预览图（不弹窗，直接把内容面板画进图片）。
     *
     * <p>用途很实际：安装界面的改动如果没有办法被复核，就只能靠"我改完了"这句话。
     * 有了这张图，界面的样子可以被反复确认，也能在无人在场时自检（例如命令行里跑一次）。
     */
    static void screenshot(FusionInstaller.Options initial, java.nio.file.Path out) {
        SwingUtilities.invokeLater(() -> {
            try {
                build(initial, out);
            } catch (Throwable t) {
                System.out.println("界面预览生成失败：" + t);
                System.exit(2);
            }
        });
    }

    private static void build(FusionInstaller.Options initial, java.nio.file.Path shot) {
        FusionInstaller.Options o = initial;
        JFrame frame = new JFrame("FusionML 安装器");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        JTextField gameDir = new JTextField(
                (o.gameDir != null ? o.gameDir : InstanceLayout.detectDefaultGameDir()).toString(), 40);
        gameDir.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 13));
        JTextField modsFrom = new JTextField(o.modsFrom == null ? "" : o.modsFrom.toString(), 40);
        JTextField inherit = new JTextField(o.inheritVersion, 14);
        // 版本下拉框：**扫完目录之后才填充**。
        //
        // 以前这里只有一个自由文本框，用户得自己把版本目录名一字不差地敲进去 ——
        // 敲错一个空格就找不到版本，而错误信息很晚才出现（启动计划阶段）。
        // 现在改成"选目录 → 体检 → 从探测到的版本里挑"，抄不错也漏不掉。
        JComboBox<String> versionBox = new JComboBox<>();
        versionBox.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 13));
        JCheckBox registerProfile = new JCheckBox("注册到启动器（写 launcher_profiles.json 与版本定义）", true);
        JCheckBox dryRun = new JCheckBox("预演：只看会做什么，不写任何文件", false);
        styleCheck(registerProfile);
        styleCheck(dryRun);

        JTextArea log = new JTextArea(10, 78);
        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        log.setBackground(new Color(0x12, 0x14, 0x1C));
        log.setForeground(new Color(0xC8, 0xD2, 0xE8));
        log.setBorder(new EmptyBorder(6, 8, 6, 8));

        JLabel status = new JLabel("准备就绪");
        status.setForeground(DIM);
        JProgressBar bar = new JProgressBar(0, 100);
        bar.setStringPainted(false);
        bar.setForeground(ACCENT);
        bar.setBackground(new Color(0x0E, 0x10, 0x16));
        bar.setBorderPainted(false);
        bar.setPreferredSize(new Dimension(100, 14));

        JPanel env = new JPanel();
        env.setOpaque(false);
        env.setLayout(new BoxLayout(env, BoxLayout.Y_AXIS));

        JButton install = primary("一键安装", OK);
        JButton uninstall = secondary("卸载");
        JButton preview = secondary("预览");
        JButton openDir = secondary("打开目录");
        JButton refresh = secondary("重新体检");
        JButton launch = secondary("启动游戏");

        Runnable scan = () -> {
            Path dir = Path.of(gameDir.getText().trim());
            status.setText("正在体检该目录…");
            status.setForeground(DIM);
            new Thread(() -> {
                InstallEnvironment.Report r;
                try {
                    r = InstallEnvironment.scan(dir, guessRequiredJava(dir));
                } catch (Throwable t) {
                    SwingUtilities.invokeLater(() -> {
                        status.setText("体检失败：" + t);
                        status.setForeground(BAD);
                    });
                    return;
                }
                SwingUtilities.invokeLater(() -> {
                    renderEnvironment(env, r);
                    // 把探测到的版本填进下拉框 —— 这是"选目录 → 选版本"的第二半。
                    //
                    // 保留用户已经选过的那个：重新体检不该把手选的结果冲掉。
                    String keep = (String) versionBox.getSelectedItem();
                    versionBox.removeAllItems();
                    for (InstallEnvironment.Version v : r.versions()) {
                        versionBox.addItem(v.id());
                    }
                    if (keep != null && r.versions().stream().anyMatch(v -> v.id().equals(keep))) {
                        versionBox.setSelectedItem(keep);
                    } else if (!inherit.getText().isBlank()
                            && r.versions().stream().anyMatch(v -> v.id().equals(inherit.getText().trim()))) {
                        versionBox.setSelectedItem(inherit.getText().trim());
                    } else {
                        // 默认选中"用户当前所在的那个版本"（选进来的若是版本目录，就是它自己）。
                        // 理由见预览路径里那段注释：默认选列表第一个会得到一个很蠢的结论。
                        String here = dir.getFileName() == null ? null : dir.getFileName().toString();
                        if (here != null && r.versions().stream().anyMatch(v -> v.id().equals(here))) {
                            versionBox.setSelectedItem(here);
                        }
                    }
                    // 选中的版本同步写回文本框：安装器其余部分仍然读文本字段，
                    // 这样"下拉框"和"命令行的 --inherit"走的是同一个值，不会出现两处不一致。
                    Object sel = versionBox.getSelectedItem();
                    if (sel != null) {
                        inherit.setText(String.valueOf(sel));
                    }
                    status.setText(r.installed()
                            ? "已检测到本加载器（安装时间 " + r.loaderAt() + "）"
                            : "未安装：点“一键安装”即可");
                    status.setForeground(r.installed() ? OK : WARN);

                    // ---- 双重验证：目录一道、版本一道 ----
                    //
                    // 顺序是刻意的（先目录、后版本）：版本列表来自目录体检，
                    // 目录都不对就谈不上选版本。两道的结论都写进日志区，
                    // 用户能看到"为什么不能装"，而不是只看到一句"失败"。
                    log.append(System.lineSeparator());
                    InstallVerify.Result dirCheck = InstallVerify.verifyDirectory(dir);
                    log.append(dirCheck.render());
                    Object chosen = versionBox.getSelectedItem();
                    if (chosen != null) {
                        InstallVerify.Result verCheck = InstallVerify.verifyVersion(
                                dir, String.valueOf(chosen), guessRequiredJava(dir));
                        log.append(verCheck.render());
                        if (!verCheck.canContinue()) {
                            log.append("   → 这个版本现在装了也**不能完整使用**（原因见上）；"
                                    + "安装本身可以继续，跨端功能会不可用。"
                                    + System.lineSeparator());
                        }
                    } else {
                        log.append("   （没有可选版本：先确认目录选对没有）"
                                + System.lineSeparator());
                    }
                    // 状态条也反映两道验证的合并结论：用户不用读日志就知道能不能装。
                    InstallVerify.Level overall = dirCheck.overall();
                    if (overall != InstallVerify.Level.FAIL && chosen != null) {
                        InstallVerify.Level vl = InstallVerify.verifyVersion(
                                dir, String.valueOf(chosen), guessRequiredJava(dir)).overall();
                        if (vl.ordinal() > overall.ordinal()) {
                            overall = vl;
                        }
                    }
                    if (overall == InstallVerify.Level.FAIL) {
                        status.setForeground(BAD);
                    } else if (overall == InstallVerify.Level.WARN) {
                        status.setForeground(WARN);
                    }
                });
            }, "fusion-installer-scan").start();
        };

        // 下拉框与文本框保持同步：安装器其余逻辑读的是 inherit 这个文本字段。
        versionBox.addActionListener(e -> {
            Object sel = versionBox.getSelectedItem();
            if (sel != null) {
                inherit.setText(String.valueOf(sel));
            }
        });
        install.addActionListener(e -> {
            FusionInstaller.Options opts = collect(o, gameDir, modsFrom, inherit, registerProfile, dryRun);
            runInstaller(frame, log, bar, status, "安装", opts, false, scan);
        });
        preview.addActionListener(e -> {
            FusionInstaller.Options opts = collect(o, gameDir, modsFrom, inherit, registerProfile, dryRun);
            opts.dryRun = true;
            runInstaller(frame, log, bar, status, "预览", opts, false, null);
        });
        uninstall.addActionListener(e -> {
            FusionInstaller.Options opts = collect(o, gameDir, modsFrom, inherit, registerProfile, dryRun);
            opts.uninstall = true;
            int answer = JOptionPane.showConfirmDialog(frame,
                    "将按安装清单精确删除本加载器写入的文件与目录。\n"
                            + "整合包自带的模组、配置、存档都不在清单里，不会被碰。\n\n确定卸载吗？",
                    "确认卸载", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (answer == JOptionPane.OK_OPTION) {
                runInstaller(frame, log, bar, status, "卸载", opts, true, scan);
            }
        });
        openDir.addActionListener(e -> {            try {
                java.awt.Desktop.getDesktop().open(Path.of(gameDir.getText().trim()).toFile());
            } catch (Throwable t) {
                log.append("打开目录失败：" + t + System.lineSeparator());
            }
        });
        refresh.addActionListener(e -> scan.run());
        launch.addActionListener(e -> launchGame(frame, log, Path.of(gameDir.getText().trim())));
        gameDir.addActionListener(e -> scan.run());

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG);
        root.add(header(), BorderLayout.NORTH);
        root.add(body(gameDir, modsFrom, inherit, versionBox, registerProfile, dryRun, env,
                install, uninstall, preview, openDir, refresh, launch, status, bar, log),
                BorderLayout.CENTER);

        frame.setContentPane(root);
        frame.pack();
        frame.setMinimumSize(new Dimension(900, 640));
        frame.setLocationRelativeTo(null);

        if (shot != null) {
            // 预览模式：把体检同步跑完（否则图上会是"正在体检"），再画图退出。
            //
            // 注意这里要**把扫描那套逻辑也走一遍**（填下拉框 + 双重验证 + 写日志），
            // 而不是只调 renderEnvironment —— 否则预览图上是看不到
            // "版本下拉框里有什么"和"双重验证结论"的，而这两样恰恰是这轮新增的东西。
            // 实测踩到：只渲染环境区时，图上下拉框是空的、日志区也是空的，
            // 看起来像"功能没做"，其实是预览路径没跟上。
            try {
                Path dir = Path.of(gameDir.getText().trim());
                InstallEnvironment.Report r = InstallEnvironment.scan(dir, guessRequiredJava(dir));
                renderEnvironment(env, r);

                // 1) 填版本下拉框
                versionBox.removeAllItems();
                for (InstallEnvironment.Version v : r.versions()) {
                    versionBox.addItem(v.id());
                }
                // 默认选中**用户当前所在的那个版本**，而不是列表第一个。
                //
                // 实测踩到：用户把"某一个版本目录"选进来时（很常见，整合包里就是这一层），
                // 版本列表取自它的同级目录，而同级里可能有几十个版本 ——
                // 默认选第一个（本机恰好有个叫 "1" 的目录）就会得到
                //   "第二道：版本校验 ✘ 在 …\26.3-Fabric 0.19.5 里找不到版本 1"
                // 一个"结论正确但默认值很蠢"的结果。用户明明就在那个版本目录里。
                if (versionBox.getItemCount() > 0) {
                    String here = dir.getFileName() == null ? null : dir.getFileName().toString();
                    int idx = -1;
                    for (int i = 0; i < versionBox.getItemCount(); i++) {
                        if (versionBox.getItemAt(i).equals(here)) {
                            idx = i;
                            break;
                        }
                    }
                    versionBox.setSelectedIndex(idx >= 0 ? idx : 0);
                    inherit.setText(String.valueOf(versionBox.getSelectedItem()));
                }

                // 2) 双重验证，结论写进日志区
                log.append(System.lineSeparator());
                InstallVerify.Result dirCheck = InstallVerify.verifyDirectory(dir);
                log.append(dirCheck.render());
                InstallVerify.Level overall = dirCheck.overall();
                if (versionBox.getItemCount() > 0) {
                    InstallVerify.Result verCheck = InstallVerify.verifyVersion(
                            dir, String.valueOf(versionBox.getSelectedItem()), guessRequiredJava(dir));
                    log.append(verCheck.render());
                    if (verCheck.overall().ordinal() > overall.ordinal()) {
                        overall = verCheck.overall();
                    }
                }
                status.setText(r.installed() ? "已检测到本加载器" : "未安装：点“一键安装”即可");
                status.setForeground(overall == InstallVerify.Level.FAIL ? BAD : (r.installed() ? OK : WARN));
            } catch (Throwable ignored) {
                // 预览图不因为体检失败而失败
            }
            root.setSize(frame.getWidth(), frame.getHeight());
            layoutAll(root);
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                    Math.max(1, frame.getWidth()), Math.max(1, frame.getHeight()),
                    java.awt.image.BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            root.printAll(g);
            g.dispose();
            try {
                javax.imageio.ImageIO.write(img, "png", shot.toFile());
                System.out.println("安装界面预览已写出：" + shot);
            } catch (Exception e) {
                System.out.println("写出预览图失败：" + e);
            }
            frame.dispose();
            System.exit(0);
            return;
        }
        frame.setVisible(true);

        log.append("FusionML 安装器已就绪。" + System.lineSeparator());
        // 说清楚"这个安装器是哪来的" —— 分版本打包之后，用户手上可能是五个安装器之一，
        // 不写出来的话，装完发现版本对不上会很难查。
        log.append(com.fusionml.FusionVersions.describe() + System.lineSeparator());
        log.append("默认游戏目录：" + InstanceLayout.detectDefaultGameDir() + System.lineSeparator());
        log.append("安装只做新增，不覆盖已有文件；卸载按清单精确还原。" + System.lineSeparator());
        scan.run();
    }

    // ------------------------------------------------------------------ 界面搭建

    private static JPanel header() {
        JPanel p = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setPaint(new GradientPaint(0, 0, new Color(0x1B, 0x2A, 0x46), getWidth(), getHeight(),
                        new Color(0x14, 0x16, 0x1E)));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(new Color(0x5A, 0xC8, 0xFA, 90));
                g2.fillRect(0, getHeight() - 2, getWidth(), 2);
                g2.dispose();
            }
        };
        p.setBorder(new EmptyBorder(18, 22, 16, 22));
        JLabel title = new JLabel("FusionML");
        title.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 26));
        title.setForeground(TEXT);
        JLabel sub = new JLabel("同进程三端加载器 · 安装器 v1.0.0");
        sub.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 13));
        sub.setForeground(ACCENT);
        JLabel note = new JLabel("把安装程序和游戏文件放进同一个目录，点一下就能用");
        note.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12));
        note.setForeground(DIM);
        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.add(title);
        left.add(Box.createVerticalStrut(4));
        left.add(sub);
        left.add(Box.createVerticalStrut(2));
        left.add(note);
        p.add(left, BorderLayout.WEST);
        return p;
    }

    private static JPanel body(JTextField gameDir, JTextField modsFrom, JTextField inherit,
                               JComboBox<String> versionBox,
                               JCheckBox registerProfile, JCheckBox dryRun, JPanel env,
                               JButton install, JButton uninstall, JButton preview, JButton openDir,
                               JButton refresh, JButton launch, JLabel status, JProgressBar bar,
                               JTextArea log) {
        JPanel wrap = new JPanel(new BorderLayout(0, 12));
        wrap.setBackground(BG);
        wrap.setBorder(new EmptyBorder(14, 22, 18, 22));

        JPanel top = new JPanel(new BorderLayout(0, 12));
        top.setOpaque(false);
        top.add(stepOne(gameDir, modsFrom, inherit, versionBox, registerProfile, dryRun,
                refresh, openDir), BorderLayout.NORTH);
        top.add(stepTwo(env), BorderLayout.CENTER);
        wrap.add(top, BorderLayout.NORTH);

        JPanel actions = new JPanel(new BorderLayout());
        actions.setOpaque(false);
        JPanel buttons = new JPanel(new GridLayout(1, 5, 8, 0));
        buttons.setOpaque(false);
        buttons.add(install);
        buttons.add(preview);
        buttons.add(uninstall);
        buttons.add(launch);
        actions.add(buttons, BorderLayout.CENTER);

        JPanel progress = new JPanel(new BorderLayout(0, 6));
        progress.setOpaque(false);
        progress.add(status, BorderLayout.NORTH);
        progress.add(bar, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout(0, 12));
        south.setOpaque(false);
        south.add(actions, BorderLayout.NORTH);
        south.add(progress, BorderLayout.SOUTH);
        wrap.add(south, BorderLayout.SOUTH);

        JScrollPane scroll = new JScrollPane(log);
        scroll.setBorder(null);
        JPanel logCard = card("③ 执行日志");
        logCard.add(scroll, BorderLayout.CENTER);
        logCard.setPreferredSize(new Dimension(100, 200));
        wrap.add(logCard, BorderLayout.CENTER);
        return wrap;
    }

    private static JPanel stepOne(JTextField gameDir, JTextField modsFrom, JTextField inherit,
                                  JComboBox<String> versionBox,
                                  JCheckBox registerProfile, JCheckBox dryRun,
                                  JButton refresh, JButton openDir) {
        JPanel card = card("① 选择游戏目录，再选择要安装到的版本");
        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.anchor = GridBagConstraints.WEST;

        addRow(form, c, 0, "游戏目录", gameDir, refresh);
        addRow(form, c, 1, "模组来源（可选，会拷入 mods/）", modsFrom, browse(gameDir, modsFrom, "选择模组目录"));
        // 第二重选择：目录里探测到哪些版本，就从哪些里挑。
        // 顺序是刻意的 —— 先目录、后版本，因为版本列表来自目录体检的结果。
        c.gridy = 2;
        c.gridx = 0;
        form.add(label("安装到版本"), c);
        c.gridx = 1;
        versionBox.setPreferredSize(new Dimension(260, 26));
        form.add(versionBox, c);
        c.gridx = 2;
        form.add(openDir, c);
        c.gridy = 3;
        c.gridx = 0;
        form.add(label("继承原版版本"), c);
        c.gridx = 1;
        inherit.setEditable(false);
        inherit.setBackground(new Color(0x1A, 0x1D, 0x26));
        form.add(inherit, c);
        c.gridy = 4;
        c.gridx = 1;
        JPanel checks = new JPanel(new GridLayout(1, 2));
        checks.setOpaque(false);
        checks.add(registerProfile);
        checks.add(dryRun);
        form.add(checks, c);
        card.add(form, BorderLayout.CENTER);
        return card;
    }

    private static JPanel stepTwo(JPanel env) {
        JPanel card = card("② 环境体检");
        JScrollPane scroll = new JScrollPane(env);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setPreferredSize(new Dimension(100, 150));
        card.add(scroll, BorderLayout.CENTER);
        return card;
    }

    private static void renderEnvironment(JPanel env, InstallEnvironment.Report r) {
        env.removeAll();
        StringBuilder ver = new StringBuilder();
        for (InstallEnvironment.Version v : r.versions()) {
            if (ver.length() > 0) {
                ver.append("、");
            }
            ver.append(v.id()).append('[').append(v.platform()).append(']');
            if (ver.length() > 220) {
                ver.append("…");
                break;
            }
        }
        env.add(envLine("游戏版本", ver.length() == 0 ? "未发现版本目录" : ver.toString(),
                ver.length() == 0 ? WARN : TEXT));
        env.add(envLine("三端模组", r.modsLine() + "（共 " + r.totalMods() + " 个，扫描 "
                        + r.modJars() + " 个包）",
                r.mixedEcosystems() ? OK : TEXT));
        if (r.mixedEcosystems()) {
            env.add(envLine("", "检测到多端模组混装 —— 正是本加载器的场景：同一个进程里一起加载",
                    ACCENT));
        }
        env.add(envLine("运行 Java", r.java().line(), r.java().major() >= 21 ? TEXT : WARN));
        env.add(envLine("加载器", r.installed()
                        ? "已安装（" + r.loaderAt() + "）" : "尚未安装",
                r.installed() ? OK : WARN));
        if (!r.unknownJars().isEmpty()) {
            env.add(envLine("未识别为模组的包", String.join("、", r.unknownJars())
                    + (r.unknownJars().size() >= 8 ? "…" : "") + "（前置库/资源包属正常）", DIM));
        }
        for (String note : r.notes()) {
            env.add(envLine("提示", note, WARN));
        }
        env.revalidate();
        env.repaint();
    }

    private static JPanel envLine(String key, String value, Color valueColor) {
        JPanel line = new JPanel(new BorderLayout(10, 0));
        line.setOpaque(false);
        line.setBorder(new EmptyBorder(2, 2, 2, 2));
        JLabel k = new JLabel(key);
        k.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 12));
        k.setForeground(DIM);
        k.setPreferredSize(new Dimension(96, 18));
        JLabel v = new JLabel(value);
        v.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12));
        v.setForeground(valueColor);
        line.add(k, BorderLayout.WEST);
        line.add(v, BorderLayout.CENTER);
        return line;
    }

    // ------------------------------------------------------------------ 动作

    private static void runInstaller(JFrame frame, JTextArea log, JProgressBar bar, JLabel status,
                                     String what, FusionInstaller.Options opts, boolean uninstall,
                                     Runnable after) {
        log.append(System.lineSeparator() + "===== " + what + " =====" + System.lineSeparator());
        bar.setValue(0);
        status.setForeground(DIM);
        status.setText(what + "中…");
        for (java.awt.Component c : new java.awt.Component[]{log, bar}) {
            c.setEnabled(false);
        }
        new Thread(() -> {
            int code;
            try {
                java.util.function.Consumer<String> sink = line -> {
                    SwingUtilities.invokeLater(() -> {
                        log.append(line + System.lineSeparator());
                        log.setCaretPosition(log.getDocument().getLength());
                        int pct = stagePercent(line);
                        if (pct >= 0) {
                            bar.setValue(pct);
                            status.setText(stageText(line));
                        }
                    });
                };
                code = uninstall ? FusionInstaller.uninstall(opts, sink)
                        : FusionInstaller.install(opts, sink);
            } catch (Throwable t) {
                code = -1;
                SwingUtilities.invokeLater(() -> log.append("失败：" + t + System.lineSeparator()));
            }
            final int result = code;
            SwingUtilities.invokeLater(() -> {
                bar.setValue(result == 0 ? 100 : 0);
                status.setText(result == 0 ? what + "完成" : what + "未完成（见日志）");
                status.setForeground(result == 0 ? OK : BAD);
                log.setEnabled(true);
                bar.setEnabled(true);
                if (after != null) {
                    after.run();
                }
            });
        }, "fusion-installer-work").start();
    }

    /**
     * 把安装输出的每一行映射成进度。
     *
     * <p>用"按关键字识别阶段"而不是让安装器上报百分比：安装器是命令行与图形共用的，
     * 让它关心界面进度会把两件事绑死。这里的关键字与 {@link FusionInstaller} 的输出一一对应，
     * 认不出来就返回 -1（进度条停在原地），不会出现乱跳。
     */
    private static int stagePercent(String line) {
        if (line.startsWith("游戏目录")) {
            return 5;
        }
        if (line.startsWith("目录结构")) {
            return 20;
        }
        if (line.contains("释放加载器")) {
            return 45;
        }
        if (line.contains("早期窗口") || line.startsWith("保留已有配置")) {
            return 60;
        }
        if (line.contains("启动器版本") || line.startsWith("已注册")
                || line.startsWith("启动器配置")) {
            return 78;
        }
        if (line.startsWith("已拷入") || line.contains("拷贝模组")) {
            return 90;
        }
        if (line.startsWith("安装完成")) {
            return 100;
        }
        if (line.startsWith("已删除") || line.startsWith("清单")) {
            return 80;
        }
        return -1;
    }

    private static String stageText(String line) {
        int cut = line.indexOf('：');
        return cut > 0 ? line.substring(0, cut) : line;
    }

    private static void launchGame(JFrame frame, JTextArea log, Path gameDir) {
        Path loaderJar = InstanceLayout.loaderJar(gameDir);
        if (!java.nio.file.Files.isRegularFile(loaderJar)) {
            JOptionPane.showMessageDialog(frame,
                    "这个目录里还没有安装本加载器，先点“一键安装”。", "无法启动",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        log.append("启动游戏：" + gameDir + System.lineSeparator());
        new Thread(() -> {
            try {
                List<String> cmd = new java.util.ArrayList<>();
                cmd.add(com.fusionml.launch.JavaFinder.currentJava().toString());
                cmd.add("-jar");
                cmd.add(loaderJar.toString());
                cmd.add("--mc-dir");
                cmd.add(gameDir.toString());
                cmd.add("--mc-launch");
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.directory(gameDir.toFile());
                pb.redirectErrorStream(true);
                Process p = pb.start();
                SwingUtilities.invokeLater(() -> log.append("已启动，游戏日志会持续输出到本窗口…"
                        + System.lineSeparator()));
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream(),
                                java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        String l = line;
                        SwingUtilities.invokeLater(() -> {
                            log.append(l + System.lineSeparator());
                            log.setCaretPosition(log.getDocument().getLength());
                        });
                    }
                }
                p.waitFor();
            } catch (Throwable t) {
                SwingUtilities.invokeLater(() -> log.append("启动失败：" + t + System.lineSeparator()));
            }
        }, "fusion-installer-launch").start();
    }

    // ------------------------------------------------------------------ 小工具

    private static FusionInstaller.Options collect(FusionInstaller.Options base, JTextField gameDir,
                                                   JTextField modsFrom, JTextField inherit,
                                                   JCheckBox profile, JCheckBox dryRun) {
        FusionInstaller.Options o = new FusionInstaller.Options();
        o.gameDir = Path.of(gameDir.getText().trim());
        o.modsFrom = modsFrom.getText().isBlank() ? null : Path.of(modsFrom.getText().trim());
        o.inheritVersion = inherit.getText().trim();
        o.noProfile = !profile.isSelected();
        o.dryRun = dryRun.isSelected();
        o.gui = true;
        return o;
    }

    /** 从版本目录名猜这份游戏需要的 Java：26.x 需要 25，1.21.x 需要 21，再老的要求更低。 */
    private static int guessRequiredJava(Path gameDir) {
        String all = String.join(" ", list(gameDir.resolve("versions"))).toLowerCase(java.util.Locale.ROOT);
        if (all.contains("26.")) {
            return 25;
        }
        if (all.contains("1.21") || all.contains("1.20.5") || all.contains("1.20.6")) {
            return 21;
        }
        return 17;
    }

    private static List<String> list(Path dir) {
        List<String> out = new java.util.ArrayList<>(4);
        if (dir == null || !java.nio.file.Files.isDirectory(dir)) {
            return out;
        }
        try (java.nio.file.DirectoryStream<Path> ds = java.nio.file.Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                out.add(p.getFileName().toString());
            }
        } catch (Exception ignored) {
            // 猜不出来就用最保守的默认值
        }
        return out;
    }

    private static void addRow(JPanel form, GridBagConstraints c, int row, String title,
                               JTextField field, JButton tail) {
        c.gridy = row;
        c.gridx = 0;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        form.add(label(title), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        form.add(field, c);
        c.gridx = 2;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        form.add(tail, c);
    }

    private static JButton browse(JTextField shown, JTextField target, String title) {
        JButton b = secondary("浏览…");
        b.addActionListener(e -> chooseDir(shown, target, title));
        return b;
    }

    private static void chooseDir(JTextField base, JTextField target, String title) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(title);
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        String seed = target.getText().isBlank() ? base.getText().trim() : target.getText().trim();
        if (!seed.isBlank()) {
            chooser.setCurrentDirectory(new java.io.File(seed));
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            target.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    /**
     * 递归布局一遍。
     *
     * <p>不弹窗就画图时，组件刚加完还没有被"真正布局"过，
     * 直接 printAll 会得到一张控件挤在角落的图。这里显式走一遍
     * doLayout，保证预览图和真实运行时长得一样。
     */
    private static void layoutAll(java.awt.Container c) {
        c.doLayout();
        for (java.awt.Component child : c.getComponents()) {
            if (child instanceof java.awt.Container inner) {
                layoutAll(inner);
            }
        }
    }

    private static JPanel card(String title) {
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setBackground(CARD);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LINE),
                new EmptyBorder(12, 14, 12, 14)));
        JLabel t = new JLabel(title);
        t.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 14));
        t.setForeground(TEXT);
        p.add(t, BorderLayout.NORTH);
        return p;
    }

    private static JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12));
        l.setForeground(DIM);
        return l;
    }

    private static void styleCheck(JCheckBox box) {
        box.setOpaque(false);
        box.setForeground(DIM);
        box.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12));
    }

    private static JButton primary(String text, Color color) {
        JButton b = new JButton(text);
        b.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 14));
        b.setBackground(color);
        b.setForeground(new Color(0x0E, 0x10, 0x16));
        b.setFocusPainted(false);
        b.setBorder(new EmptyBorder(10, 16, 10, 16));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setOpaque(true);
        return b;
    }

    private static JButton secondary(String text) {
        JButton b = new JButton(text);
        b.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 13));
        b.setBackground(new Color(0x26, 0x2B, 0x38));
        b.setForeground(TEXT);
        b.setFocusPainted(false);
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LINE), new EmptyBorder(9, 12, 9, 12)));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setOpaque(true);
        // 鼠标悬停高亮：安装器最容易让人觉得"卡住了"，一点即时反馈能明显减少这种错觉。
        b.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                b.setBackground(new Color(0x33, 0x3A, 0x4C));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                b.setBackground(new Color(0x26, 0x2B, 0x38));
            }
        });
        return b;
    }

}
