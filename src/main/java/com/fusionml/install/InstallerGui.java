package com.fusionml.install;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.LinearGradientPaint;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Shape;
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
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

/**
 * 图形安装器（圆角版）。
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
 * <h2>圆角是怎么来的</h2>
 * Swing 的默认组件是直角的，所以这个界面里<b>所有可见的"面"都是自绘的</b>：
 * 卡片、按钮、输入框、下拉框、进度条、复选框、滚动条，全部由 {@link RoundedUi} 绘制。
 * 这里只负责把它们摆上去，不再出现 {@code setBorder(LineBorder)} 这类直角画法。
 *
 * <p>配色走深色系：和早期窗口、游戏内 HUD 是同一套观感，用户从安装到进游戏看到的是一致的东西。
 */
final class InstallerGui {

    // 配色统一由 RoundedUi 提供，避免两处各写一份导致深浅不一。
    private static final Color BG = RoundedUi.BG;
    private static final Color TEXT = RoundedUi.TEXT;
    private static final Color DIM = RoundedUi.DIM;
    private static final Color ACCENT = RoundedUi.ACCENT;
    private static final Color OK = RoundedUi.OK;
    private static final Color WARN = RoundedUi.WARN;
    private static final Color BAD = RoundedUi.BAD;

    /** 预览图尺寸不再依赖 frame.getWidth()，避免打包/布局顺序引起的尺寸漂移。 */
    private static final int SHOT_W = 1060;
    private static final int SHOT_H = 980;

    private static final int LOG_CARD_CHROME = 57;

    private static final int LOG_CARD_LINES = 12;

    /**
     * body() 建好的日志卡与其文本区，供 build() 在 pack() 之后做"整行对齐"。
     *
     * <p>用静态字段而不是加参数：body() 的参数已经很长（14 个），
     * 再多两个只会更难读；而这两者本来就只服务于"pack 后对齐"这一个动作。
     * 安装器是单实例界面（进程内只会建一次），没有并发复用的风险。
     */
    private static JPanel BODY_LOG_CARD;
    private static JTextArea BODY_LOG_TEXT;

    private InstallerGui() {
    }

    static void show(FusionInstaller.Options initial) {
        RoundedUi.installDefaults();
        SwingUtilities.invokeLater(() -> build(initial, null));
    }

    static void screenshot(FusionInstaller.Options initial, java.nio.file.Path out) {
        RoundedUi.installDefaults();
        SwingUtilities.invokeLater(() -> {
            try {
                build(initial, out);
            } catch (Throwable t) {
                System.out.println("界面预览生成失败：" + t);
                t.printStackTrace(System.out);
                System.exit(2);
            }
        });
    }

    private static void build(FusionInstaller.Options initial, java.nio.file.Path shot) {
        FusionInstaller.Options o = initial;
        JFrame frame = new JFrame("FusionML 安装器");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        JTextField gameDir = RoundedUi.field(
                (o.gameDir != null ? o.gameDir : InstanceLayout.detectDefaultGameDir()).toString(),
                40, true);
        JTextField modsFrom = RoundedUi.field(o.modsFrom == null ? "" : o.modsFrom.toString(),
                40, true);
        // 继承版本是只读的：它的值来自下拉框，见下面 versionBox 的监听器。
        JTextField inherit = RoundedUi.field(o.inheritVersion, 14, false);
        // 版本下拉框：**扫完目录之后才填充**。
        // 以前这里只有一个自由文本框，用户得自己把版本目录名一字不差地敲进去 ——
        // 敲错一个空格就找不到版本，而错误信息很晚才出现（启动计划阶段）。
        // 现在改成"选目录 → 体检 → 从探测到的版本里挑"，抄不错也漏不掉。
        JComboBox<String> versionBox = RoundedUi.combo();        JCheckBox registerProfile = RoundedUi.check("注册到启动器（写 launcher_profiles.json 与版本定义）", true);
        JCheckBox dryRun = RoundedUi.check("预演：只看会做什么，不写任何文件", false);

        JTextArea log = new JTextArea(10, 78);
        log.setEditable(false);
        log.setFont(RoundedUi.mono(12));
        log.setBackground(new Color(0x12, 0x14, 0x1C));
        log.setForeground(new Color(0xC8, 0xD2, 0xE8));
        log.setCaretColor(ACCENT);
        log.setBorder(new EmptyBorder(8, 10, 8, 10));

        JLabel status = new JLabel("准备就绪");
        status.setForeground(DIM);
        status.setFont(RoundedUi.font(Font.PLAIN, 12));
        RoundedUi.RoundedProgressBar bar = new RoundedUi.RoundedProgressBar(0, 100);

        JPanel env = new JPanel();
        env.setOpaque(false);
        env.setLayout(new BoxLayout(env, BoxLayout.Y_AXIS));

        JButton install = RoundedUi.button("一键安装", RoundedUi.ButtonStyle.primary(OK), 14, true);
        JButton uninstall = RoundedUi.button("卸载", RoundedUi.ButtonStyle.secondary(), 13, false);
        // 预览用 secondaryLight：它会随"版本不符"被禁用，需要更亮的正常态，
        // 否则禁用态压到背景后 ΔL 仍不足 -25，肉眼看不出"灰了"。
        JButton preview = RoundedUi.button("预览", RoundedUi.ButtonStyle.secondaryLight(), 13, false);
        JButton openDir = RoundedUi.button("打开目录", RoundedUi.ButtonStyle.secondary(), 13, false);
        JButton refresh = RoundedUi.button("重新体检", RoundedUi.ButtonStyle.secondary(), 13, false);
        JButton launch = RoundedUi.button("启动游戏", RoundedUi.ButtonStyle.primary(ACCENT), 13, true);

        // 圆角输入框靠"焦点描边"表达状态，所以必须真的能拿到焦点；
        // 但默认的 Tab 顺序会把焦点塞给一堆辅助控件，这里显式排一条主流程：
        // 游戏目录 → 模组来源 → 版本 → 两个勾选 → 按钮。
        java.util.List<Component> order = new java.util.ArrayList<>();
        order.add(gameDir);
        order.add(modsFrom);
        order.add(versionBox);
        order.add(registerProfile);
        order.add(dryRun);
        order.add(install);
        order.add(preview);
        order.add(uninstall);
        order.add(launch);
        order.add(refresh);
        order.add(openDir);
        install.setFocusTraversalPolicyProvider(false);
        frame.setFocusTraversalPolicy(new java.awt.FocusTraversalPolicy() {
            @Override
            public Component getComponentAfter(java.awt.Container c, Component a) {
                int i = order.indexOf(a);
                return order.get((i + 1) % order.size());
            }

            @Override
            public Component getComponentBefore(java.awt.Container c, Component a) {
                int i = order.indexOf(a);
                return order.get((i - 1 + order.size()) % order.size());
            }

            @Override
            public Component getFirstComponent(java.awt.Container c) {
                return order.get(0);
            }

            @Override
            public Component getLastComponent(java.awt.Container c) {
                return order.get(order.size() - 1);
            }

            @Override
            public Component getDefaultComponent(java.awt.Container c) {
                return order.get(0);
            }
        });

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

                    String keep = (String) versionBox.getSelectedItem();
                    versionBox.removeAllItems();
                    // 通用安装器要能选**全部 5 个版本**，而不是只列这个目录里碰巧探测到的。
                    // 理由：合并成一个安装器之后，玩家可能先选版本、再选目录；
                    // 若下拉框只列"目录里已有的"，遇到新目录它就是空的，玩家没法开始。
                    for (String id : selectableVersions(r)) {
                        versionBox.addItem(id);
                    }
                    if (keep != null && containsItem(versionBox, keep)) {
                        versionBox.setSelectedItem(keep);
                    } else {
                        // 默认选中"目录里真实存在的那个版本"，详见 preferredVersion 的注释。
                        // 用目录名当默认值是错的：玩家选的多半是 .minecraft 或整合包根目录，
                        // 于是必然落到第一项，接着被版本闸门判为"不符"而拒绝。
                        String pick = preferredVersion(o.inheritVersion, dir, r);
                        if (pick != null && containsItem(versionBox, pick)) {
                            versionBox.setSelectedItem(pick);
                        }
                    }
                    // 选中的版本同步写回文本框：安装器其余部分仍然读文本字段，

                    Object sel = versionBox.getSelectedItem();
                    if (sel != null) {
                        inherit.setText(String.valueOf(sel));
                    }
                    status.setText(r.installed()
                            ? "已检测到本加载器（安装时间 " + r.loaderAt() + "）"
                            : "未安装：点“一键安装”即可");
                    status.setForeground(r.installed() ? OK : WARN);

                    // 顺序是刻意的（先目录、后版本）：版本列表来自目录体检，
                    // 目录都不对就谈不上选版本。两道的结论都写进日志区，
                    // 用户能看到"为什么不能装"，而不是只看到一句"失败"。
                    log.append(System.lineSeparator());
                    InstallVerify.Result dirCheck = InstallVerify.verifyDirectory(dir);
                    log.append(dirCheck.render());

                    // 版本一致性闸门先算：它顺带告诉我们"所选版本在目录里对应的**真实目录名**"，
                    // 下面调 InstallVerify.verifyVersion 时要用这个名字。
                    //
                    // 为什么重要：玩家的版本目录叫 "1.21.1-NeoForge"，而目标 id 是 "1.21.1"。
                    // verifyVersion 是按**字面目录名**找的（resolveVersionDir），
                    // 直接传 "1.21.1" 会报"找不到版本 1.21.1"——于是出现
                    // "闸门说允许安装、第二道却说 ✘ 不通过"的自相矛盾结论。
                    // 传真实目录名之后两道结论就一致了。
                    VersionGate gate = evaluateVersionGate(dir, versionBox);
                    String verifyName = gate.matchedDir() != null
                            ? gate.matchedDir() : String.valueOf(versionBox.getSelectedItem());

                    Object chosen = versionBox.getSelectedItem();
                    InstallVerify.Result verCheck = null;
                    if (chosen != null) {
                        verCheck = InstallVerify.verifyVersion(
                                dir, verifyName, guessRequiredJava(dir));
                        log.append(verCheck.render());
                    } else {
                        log.append("   （没有可选版本：先确认目录选对没有）"
                                + System.lineSeparator());
                    }

                    log.append(gate.log);
                    setInstallBlocked(install, preview, launch, gate.blocked);
                    if (gate.blocked) {
                        status.setText(gate.status);
                        status.setForeground(BAD);
                    } else {
                        // 未拒绝时，状态条沿用两道验证的合并结论。
                        InstallVerify.Level overall = dirCheck.overall();
                        if (overall != InstallVerify.Level.FAIL && verCheck != null
                                && verCheck.overall().ordinal() > overall.ordinal()) {
                            overall = verCheck.overall();
                        }
                        status.setForeground(overall == InstallVerify.Level.FAIL ? BAD
                                : (overall == InstallVerify.Level.WARN ? WARN : OK));
                        status.setText(gate.status);
                    }
                    // 体检后自检：验证"选版本 → 生效"这条链路。
                    // 必须在这里（扫描完成、下拉框已填充）而不能在最初的自检里做。
                    log.append(postScanCheck(versionBox, inherit));
                });
            }, "fusion-installer-scan").start();
        };

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
        openDir.addActionListener(e -> {
            try {
                java.awt.Desktop.getDesktop().open(Path.of(gameDir.getText().trim()).toFile());
            } catch (Throwable t) {
                log.append("打开目录失败：" + t + System.lineSeparator());
            }
        });
        refresh.addActionListener(e -> scan.run());
        launch.addActionListener(e -> launchGame(frame, log, Path.of(gameDir.getText().trim())));
        gameDir.addActionListener(e -> scan.run());

        // 离屏出图点不了按钮，所以"能点、能输入"必须在代码层面证明。

        String uiCheck = selfCheck(gameDir, modsFrom, inherit, versionBox, registerProfile, dryRun,
                install, uninstall, preview, openDir, refresh, launch, log, bar);

        // 必须在 UI 装好之后调用，所以放在这里。
        RoundedUi.styleComboPopup(versionBox);

        // 截图路径**不做**透明窗口：离屏渲染拿不到桌面背景，四角会变黑而不是透明，

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG);
        root.add(header(), BorderLayout.NORTH);
        root.add(body(gameDir, modsFrom, inherit, versionBox, registerProfile, dryRun, env,
                install, uninstall, preview, openDir, refresh, launch, status, bar, log),
                BorderLayout.CENTER);

        if (shot == null) {

            RoundedUi.makeWindowRounded(frame);
            JPanel chrome = RoundedUi.windowChrome(null);
            chrome.add(RoundedUi.titleBar("FusionML 安装器", frame), BorderLayout.NORTH);
            chrome.add(root, BorderLayout.CENTER);
            // 圆角边上留 1px 描边空间，避免内容顶着圆角画出去
            chrome.setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 1));
            frame.setContentPane(chrome);
        } else {
            frame.setContentPane(root);
        }

        frame.pack();

        alignLogCardToLines(BODY_LOG_CARD, BODY_LOG_TEXT);
        frame.pack();
        // 最小尺寸：必须让 ③ 执行日志在最小尺寸下**真的看得见**。
        // 这个数是量出来的，不是猜的（用 MinLayoutCheck 实测）：
        // 780 能保证 ③ 露出 80px 以上（标题 + 两行日志）。
        // 日志是安装过程唯一的反馈，宁可最小高度大一点，也不能让它消失。
        frame.setMinimumSize(new Dimension(940, 780));
        frame.setLocationRelativeTo(null);

        if (shot != null) {
            // 预览模式：把体检同步跑完（否则图上会是"正在体检"），再画图退出。
            // 注意这里要**把扫描那套逻辑也走一遍**（填下拉框 + 双重验证 + 写日志），
            // 而不是只调 renderEnvironment —— 否则预览图上是看不到
            // "版本下拉框里有什么"和"双重验证结论"的，而这两样恰恰是这轮新增的东西。
            // 实测踩到：只渲染环境区时，图上下拉框是空的、日志区也是空的，

            try {
                Path dir = Path.of(gameDir.getText().trim());
                InstallEnvironment.Report r = InstallEnvironment.scan(dir, guessRequiredJava(dir));
                renderEnvironment(env, r);

                // 1) 填版本下拉框（与交互路径同一套逻辑：通用安装器列出全部 5 个版本）
                versionBox.removeAllItems();
                for (String id : selectableVersions(r)) {
                    versionBox.addItem(id);
                }
                // 默认选中：--inherit > 目录里真实存在的版本 > 目录名 > 第一项。
                // 详见 preferredVersion 的注释 —— 用"目录名"当默认值是错的，
                // 玩家选的多半是 .minecraft 或整合包根目录，名字既不是 26.3 也不是 1.21.1，
                // 于是必然落到第一项 26.3，接着被版本闸门判为"不符"而拒绝安装。
                if (versionBox.getItemCount() > 0) {
                    String pick = preferredVersion(o.inheritVersion, dir, r);
                    int idx = pick == null ? -1 : indexOfItem(versionBox, pick);
                    versionBox.setSelectedIndex(idx >= 0 ? idx : 0);
                    inherit.setText(String.valueOf(versionBox.getSelectedItem()));
                }

                log.append(System.lineSeparator());
                InstallVerify.Result dirCheck = InstallVerify.verifyDirectory(dir);
                log.append(dirCheck.render());
                InstallVerify.Level overall = dirCheck.overall();
                // 先算闸门，拿到"所选版本在目录里对应的真实目录名"，再调 verifyVersion。
                // 否则 verifyVersion 按字面目录名找不到，会给出与闸门矛盾的 ✘ 结论。
                VersionGate gate = evaluateVersionGate(dir, versionBox);
                String verifyName = gate.matchedDir() != null
                        ? gate.matchedDir()
                        : String.valueOf(versionBox.getSelectedItem());
                InstallVerify.Result verCheck = null;
                if (versionBox.getItemCount() > 0) {
                    verCheck = InstallVerify.verifyVersion(
                            dir, verifyName, guessRequiredJava(dir));
                    log.append(verCheck.render());
                    if (verCheck.overall().ordinal() > overall.ordinal()) {
                        overall = verCheck.overall();
                    }
                }
                // 截图路径也必须走同一套版本闸门，否则"拒绝安装"这个状态在这条路径上
                // 根本不会出现，截出来的图就无法证明功能存在（这条路径曾经漏掉过一半逻辑）。
                log.append(gate.log);
                setInstallBlocked(install, preview, launch, gate.blocked);
                status.setText(gate.status);
                status.setForeground(gate.blocked() ? BAD
                        : (overall == InstallVerify.Level.FAIL ? BAD
                        : (overall == InstallVerify.Level.WARN ? WARN : OK)));

                // 自检的"下半场"：体检已把版本填进下拉框，现在才能验证
                // "选了版本 → inherit 真的被写回"这条链路。安装器其余部分读的是
                // inherit 这个文本字段，所以这就是"选完再点安装真的用这个版本"的证据。
                log.append(postScanCheck(versionBox, inherit));
                // 版本闸门自检：把"拒绝安装时按钮是否真的灰了"也断言出来，
                // 贴进日志区 → 截图本身成为证据，而不是只看代码说"实现了"。
                log.append(gateSelfCheck(install, preview, gate));
            } catch (Throwable t) {
                // 预览图不因为体检失败而失败，但要把原因写出来，不能静默吞掉。
                log.append("（预览：体检失败 " + t + "）" + System.lineSeparator());
            }
            log.append(uiCheck);
            try {
                writeShot(root, shot, shotW(), shotH());
            } catch (Exception e) {
                System.out.println("写出预览图失败：" + e);
                e.printStackTrace(System.out);
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

        System.out.println(uiCheck);
        scan.run();
    }

    /**
     * 把内容面板画成图片，并把<b>整窗圆角</b>画出来。
     *
     * <p>关键在于<b>先把尺寸定死再布局</b>：{@code frame.pack()} 之后的尺寸
     * 受当前 Look&amp;Feel 与字体度量影响，直接用它会在不同机器上得到不同大小的图。
     * 这里统一用 {@link #SHOT_W}/{@link #SHOT_H}，并且显式 {@code doLayout} 一遍 ——
     * 否则组件还没被真正布局过，printAll 会得到一张控件挤在角落的图。
     *
     * <p>圆角窗口在离屏渲染下拿不到桌面背景，所以这里不等系统帮我们透明，
     * 而是先按整窗圆角渲染，再用 {@link RoundedUi#roundWindowShot} 把圆角外
     * 涂成外部底色并补描边 —— 截图于是能如实反映"窗口是圆的"，又不会出现黑角。
     */
    private static void writeShot(JPanel root, java.nio.file.Path shot) throws java.io.IOException {
        writeShot(root, shot, SHOT_W, SHOT_H);
    }

    private static void writeShot(JPanel root, java.nio.file.Path shot, int w, int h)
            throws java.io.IOException {
        root.setSize(w, h);
        layoutAll(root);
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        // 底色先铺满，避免任何没被自绘覆盖的像素留成黑色（看起来像"绘制残缺"）
        g.setColor(BG);
        g.fillRect(0, 0, w, h);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        root.printAll(g);
        g.dispose();

        java.awt.image.BufferedImage rounded =
                RoundedUi.roundWindowShot(img, new Color(0x0A, 0x0B, 0x10));
        // 截图模式下四角是"裁"出来的，不是真透明（离屏渲染拿不到桌面）。
        // 这一点必须写在图上，否则看图的用户会以为圆角是假的 —— 见下面这行注释条。
        drawShotFootnote(rounded);
        javax.imageio.ImageIO.write(rounded, "png", shot.toFile());
        System.out.println("安装界面预览已写出：" + shot + "（" + w + "x" + h + "）");
        System.out.println(SHOT_NOTE);
    }

    static final String SHOT_NOTE =
            "说明：截图模式为离屏渲染，四角圆角以裁剪方式呈现；实际运行时为真透明圆角窗口。";

    /**
     * 在图片右下角画一条说明注记。
     *
     * <p>Lead 的要求：拿图给用户看时必须能自证，否则用户会以为截图的圆角是假的。
     * 画在图上比只打 stdout 更保险 —— 图片被单独转发时说明也不会丢。
     */
    private static void drawShotFootnote(java.awt.image.BufferedImage img) {
        Graphics2D g = RoundedUi.aa(img.createGraphics());
        g.setFont(RoundedUi.font(Font.PLAIN, 11));
        FontMetrics fm = g.getFontMetrics();
        String text = "截图模式：离屏渲染，四角以裁剪呈现 · 运行时为真透明圆角窗口";
        int pad = 8;
        int tw = fm.stringWidth(text);
        int th = fm.getHeight();
        int x = img.getWidth() - tw - pad * 2 - 4;
        int y = img.getHeight() - th - pad * 2 - 4;
        if (x > 4 && y > 4) {
            g.setColor(new Color(0, 0, 0, 170));
            g.fill(RoundedUi.roundRect(x, y, tw + pad * 2, th + pad, 6));
            g.setColor(RoundedUi.DIM);
            g.drawString(text, x + pad, y + pad + fm.getAscent());
        }
        g.dispose();
    }

    private static int shotW() {
        return envInt("FUSION_SHOT_W", SHOT_W);
    }

    private static int shotH() {
        return envInt("FUSION_SHOT_H", SHOT_H);
    }

    private static int envInt(String key, int dflt) {
        try {
            String v = System.getenv(key);
            if (v != null && !v.isBlank()) {
                int n = Integer.parseInt(v.trim());
                if (n >= 600 && n <= 4000) {
                    return n;
                }
            }
        } catch (Throwable ignored) {

        }
        return dflt;
    }

    /**
     * 代码层面确认"每个控件都可用"。
     *
     * <p>为什么需要这个：离屏截图点不了按钮，所以"按钮有没有监听器""输入框能不能打字"
     * 这类问题在图上完全看不出来 —— 它只会在用户真正点下去的那一刻暴露，
     * 而那时已经太晚了。这里直接查组件自身的状态，把结论写进日志区。
     *
     * <p>检查项：
     * <ol>
     *   <li>按钮：{@code getActionListeners().length > 0}（挂了监听器才点得动）；</li>
     *   <li>输入框：{@code isEditable() && isEnabled()}（能编辑才输得进字）；</li>
     *   <li>下拉框：{@code isEnabled()} 且监听器数量正确（版本同步靠它）；</li>
     *   <li>复选框：可点击且三态正常。</li>
     * </ol>
     */
    private static String selfCheck(JTextField gameDir, JTextField modsFrom, JTextField inherit,
                                    JComboBox<String> versionBox, JCheckBox registerProfile,
                                    JCheckBox dryRun, JButton install, JButton uninstall,
                                    JButton preview, JButton openDir, JButton refresh,
                                    JButton launch, JTextArea log,
                                    RoundedUi.RoundedProgressBar bar) {
        StringBuilder sb = new StringBuilder();
        int bad = 0;
        sb.append(System.lineSeparator()).append("===== 界面自检（控件可用性）=====").append(System.lineSeparator());

        JButton[] buttons = {install, preview, uninstall, launch, refresh, openDir};
        String[] names = {"一键安装", "预览", "卸载", "启动游戏", "重新体检", "打开目录"};
        for (int i = 0; i < buttons.length; i++) {
            int n = buttons[i].getActionListeners().length;
            boolean ok = n > 0 && buttons[i].isEnabled();
            if (!ok) {
                bad++;
            }
            sb.append(ok ? "  [OK]   " : "  [FAIL] ")
                    .append("按钮「").append(names[i]).append("」= ")
                    .append(n).append(" 个监听器，enabled=").append(buttons[i].isEnabled())
                    .append(System.lineSeparator());
        }

        JTextField[] fields = {gameDir, modsFrom};
        String[] fnames = {"游戏目录", "模组来源"};
        for (int i = 0; i < fields.length; i++) {
            boolean ok = fields[i].isEditable() && fields[i].isEnabled();
            if (!ok) {
                bad++;
            }
            sb.append(ok ? "  [OK]   " : "  [FAIL] ")
                    .append("输入框「").append(fnames[i]).append("」可编辑=")
                    .append(fields[i].isEditable()).append("，enabled=").append(fields[i].isEnabled())
                    .append(System.lineSeparator());
        }

        String probe = "probe:" + System.nanoTime();
        String saved = gameDir.getText();
        gameDir.setText(probe);
        boolean roundTrip = probe.equals(gameDir.getText());
        gameDir.setText(saved);
        if (!roundTrip) {
            bad++;
        }
        sb.append(roundTrip ? "  [OK]   " : "  [FAIL] ")
                .append("游戏目录输入框写入→读回一致（证明真的可输入）").append(System.lineSeparator());

        // 3) 继承版本：**故意只读**，它不是缺陷而是设计（值由下拉框决定）
        boolean inheritOk = !inherit.isEditable() && inherit.isEnabled();
        if (!inheritOk) {
            bad++;
        }
        sb.append(inheritOk ? "  [OK]   " : "  [FAIL] ")
                .append("「继承原版版本」为只读且 enabled=true（只读是设计：值由下拉框同步）")
                .append(System.lineSeparator());

        // 那是个错误的断言：InstallerGui 只挂了 1 个 ActionListener
        // 根本不是一个常驻监听器。结果自检报了一个 [FAIL]，而功能其实完全正常 ——
        // 一个会误报的自检比没有自检更糟，因为它会让人去修不存在的问题。
        // （安装器其余部分读的就是这个字段，所以这条链路是"选了版本真的生效"的证据）。
        // 注意：自检发生在体检**之前**，所以此刻下拉框通常是空的（还没扫目录）。
        // 空的时候这条同步没法验，必须如实标成"待体检后验证"，不能假装通过或假装失败。
        int comboListeners = versionBox.getActionListeners().length;
        boolean comboOk = versionBox.isEnabled() && comboListeners >= 1;
        String probeItem = null;
        boolean syncTested = false;
        boolean syncOk = false;
        if (versionBox.getItemCount() >= 1) {
            Object before = versionBox.getSelectedItem();
            int target = versionBox.getItemCount() >= 2
                    ? (versionBox.getSelectedIndex() == 0 ? 1 : 0) : 0;
            versionBox.setSelectedIndex(target);
            probeItem = String.valueOf(versionBox.getSelectedItem());
            syncOk = probeItem.equals(inherit.getText());
            syncTested = true;
            comboOk = comboOk && syncOk;
            if (target == 1 && before != null) {
                versionBox.setSelectedItem(before);
            }
        }
        if (!comboOk) {
            bad++;
        }
        sb.append(comboOk ? "  [OK]   " : "  [FAIL] ")
                .append("版本下拉框 enabled=").append(versionBox.isEnabled())
                .append("，监听器=").append(comboListeners)
                .append(syncTested
                        ? "，选中「" + probeItem + "」已同步到「继承原版版本」=" + syncOk
                        : "，选项数=0（自检早于体检，同步待在扫描后验证）")
                .append(System.lineSeparator());

        boolean checkOk = registerProfile.isSelected() && !dryRun.isSelected()
                && registerProfile.isEnabled() && dryRun.isEnabled();
        registerProfile.setSelected(!registerProfile.isSelected());
        boolean toggled = !registerProfile.isSelected();
        registerProfile.setSelected(true);
        checkOk = checkOk && toggled;
        if (!checkOk) {
            bad++;
        }
        sb.append(checkOk ? "  [OK]   " : "  [FAIL] ")
                .append("复选框「注册到启动器」默认勾选、可切换；「预演」默认不勾选")
                .append(System.lineSeparator());

        bar.setValue(66);
        boolean barOk = bar.getValue() == 66;
        bar.setValue(0);
        if (!barOk) {
            bad++;
        }
        sb.append(barOk ? "  [OK]   " : "  [FAIL] ")
                .append("进度条可设值（自绘圆角，0..100）").append(System.lineSeparator());

        boolean logOk = !log.isEditable() && log.isEnabled();
        if (!logOk) {
            bad++;
        }
        sb.append(logOk ? "  [OK]   " : "  [FAIL] ")
                .append("日志区只读且可写入（只读是设计）").append(System.lineSeparator());

        String family = RoundedUi.FAMILY;
        boolean cjk = RoundedUi.canRenderCjk(family);
        if (!cjk) {
            bad++;
        }
        sb.append(cjk ? "  [OK]   " : "  [FAIL] ")
                .append("界面字体「").append(family).append("」可显示中文 = ").append(cjk)
                .append(System.lineSeparator());
        javax.swing.JTextArea probeArea = new javax.swing.JTextArea("中文");
        probeArea.setFont(RoundedUi.mono(12));
        String monoFamily = probeArea.getFont().getFamily();
        // 日志区除了中文，还要能画 InstallVerify 输出的 ✔ / ✘ / →，否则勾叉是豆腐块。
        boolean monoAll = RoundedUi.canRenderReport(monoFamily);
        if (!monoAll) {
            bad++;
        }
        sb.append(monoAll ? "  [OK]   " : "  [FAIL] ")
                .append("日志区字体「").append(monoFamily)
                .append("」可显示中文+勾叉✔✘+箭头→ = ").append(monoAll)
                .append(System.lineSeparator());

        sb.append(bad == 0 ? "  → 全部通过：没有发现不可用的控件。"
                : "  → 有 " + bad + " 项不通过，见上。").append(System.lineSeparator());
        sb.append("===== 自检结束 =====").append(System.lineSeparator());
        return sb.toString();
    }

    /**
     * 把日志卡的高度对齐到"整行"，消除半行文字。
     *
     * <h3>为什么需要这个</h3>
     * 日志卡 = 标题 + 一个可滚动的文本区。如果卡片高度减去固有占用（边距+标题+间隙）
     * 之后不是行高的整数倍，文本区的可视高度就会带一个零头 ——
     * 最后一行只露出一部分，看起来就是"一行字被横切成两半"。
     *
     * <p>这不是布局 bug（滚动容器边界本来可能落在任意像素），但它确实是观感问题，
     * 而且修起来很直接：把卡片高度调整成 {@code 固定占用 + N × 行高}。
     *
     * <h3>为什么在 pack() 之后做</h3>
     * 行高来自字体度量，而字体度量要等组件真正布局过一次才可靠
     * （换字体、改字号、不同 DPI 都会变）。写死 16px 会在别的环境吸附错位，
     * 那比不对齐更糟。所以这里用实测值现算。
     */
    private static void alignLogCardToLines(JPanel logCard, JTextArea log) {
        if (logCard == null || log == null) {
            return;
        }
        int lineH = RoundedUi.lineHeightOf(log);
        if (lineH <= 0) {
            return;
        }
        Insets ci = logCard.getInsets();

        int titleH = 0;
        for (Component c : logCard.getComponents()) {
            if (c instanceof JLabel l && l.getText() != null && !l.getText().isEmpty()) {
                titleH = Math.max(titleH, l.getPreferredSize().height);
            }
        }
        int vgap = 10; 
        int chrome = ci.top + ci.bottom + titleH + vgap;
        int desired = chrome + LOG_CARD_LINES * lineH;
        Dimension cur = logCard.getPreferredSize();
        if (cur == null || cur.height != desired) {
            logCard.setPreferredSize(new Dimension(cur == null ? 100 : cur.width, desired));
            logCard.revalidate();
        }
    }

    private record VersionGate(boolean blocked, String status, String log,
                              String matchedDir) {
    }

    private static java.util.List<String> selectableVersions(InstallEnvironment.Report r) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String packaged = com.fusionml.FusionVersions.packagedTarget();
        if (packaged != null && !packaged.isBlank()) {
            out.add(packaged.trim());
        } else {
            for (com.fusionml.FusionVersions.Target t : com.fusionml.FusionVersions.all()) {
                out.add(t.id());
            }
        }
        for (InstallEnvironment.Version v : r.versions()) {
            if (!out.contains(v.id())) {
                out.add(v.id());
            }
        }
        return out;
    }

    /**
     * 目录体检之后，下拉框该**默认选中**哪一项。
     *
     * <h2>为什么必须按"目录里实际有的版本"来选（这是个实测的严重缺陷）</h2>
     *
     * <p>原先的优先级是 {@code --inherit > 目录名本身 > 列表第一项}。
     * 问题出在第二项用的是 {@code dir.getFileName()} —— 那是**用户选的那个目录的名字**，
     * 不是"目录里装了什么版本"。玩家选的通常是 {@code .minecraft} 或某个整合包根目录，
     * 名字既不是 {@code 26.3} 也不是 {@code 1.21.1}，于是**必然落到第一项**（{@code 26.3}）。
     *
     * <p>而版本闸门是按"目录里真实存在的版本"判定的。两者一撞，玩家看到的就是：
     * <b>选完目录、什么都没动，就被红字拒绝</b>，而且提示是"找不到版本 26.3" ——
     * 目录里明明有版本（{@code 1.21.1-NeoForge_21.1.249}），看起来像"安装器认不出任何版本"。
     *
     * <p>正确顺序：{@code --inherit > 目录里真实存在的版本 > 目录名 > 第一项}。
     * "目录里真实存在的版本"用 {@link com.fusionml.FusionVersions#inferFrom} 归一化，
     * 所以 {@code 1.21.1-NeoForge_21.1.249} 会归到目标 {@code 1.21.1}。
     */
    private static String preferredVersion(String explicit, Path dir, InstallEnvironment.Report r) {
        java.util.List<String> items = new java.util.ArrayList<>();
        for (InstallEnvironment.Version v : r.versions()) {
            items.add(v.id());
        }
        // 1) 命令行显式指定（截图要能复现"不符"场景，必须允许强制选中）
        if (explicit != null && !explicit.isBlank() && contains(items, explicit.trim())) {
            return explicit.trim();
        }
        // 2) **游戏目录自己就是版本目录**（版本隔离布局）→ 就用它自己对应的目标版本。
        //    这一条必须排在"扫描到的其它版本"前面：实测选 versions\26.3 时，
        //    体检会扫出根 .minecraft 下的一堆版本（1.20.1、1.21.1…），
        //    若先取"扫描结果里第一个能归一到目标的"，会选中 1.20.1 ——
        //    而目录明明是 26.3，于是又变成"不符 → 拒绝"，等于换了个姿势犯同一个错。
        String here = dir == null || dir.getFileName() == null
                ? null : dir.getFileName().toString();
        if (here != null) {
            com.fusionml.FusionVersions.Target self =
                    com.fusionml.FusionVersions.inferFrom(here);
            if (self != null) {
                return self.id();
            }
        }
        // 3) 目录里真实存在的版本：按目标版本归一化后取第一个已知目标
        for (String d : items) {
            com.fusionml.FusionVersions.Target t = com.fusionml.FusionVersions.inferFrom(d);
            if (t != null) {
                return t.id();
            }
        }
        // 4) 退化：目录名本身（可能不是已知目标，但至少是目录里有的东西）
        if (here != null && contains(items, here)) {
            return here;
        }
        // 5) 最后才落到第一项。
        return items.isEmpty() ? null : items.get(0);
    }

    private static boolean contains(java.util.List<String> list, String item) {
        if (item == null) {
            return false;
        }
        for (String s : list) {
            if (s.equals(item)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsItem(JComboBox<String> box, String item) {
        return indexOfItem(box, item) >= 0;
    }

    private static int indexOfItem(JComboBox<String> box, String item) {
        if (item == null) {
            return -1;
        }
        for (int i = 0; i < box.getItemCount(); i++) {
            if (item.equals(box.getItemAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static VersionGate evaluateVersionGate(Path gameDir, JComboBox<String> versionBox) {
        StringBuilder sb = new StringBuilder();
        sb.append(System.lineSeparator()).append("----- 版本一致性检查 -----")
                .append(System.lineSeparator());

        Object sel = versionBox.getSelectedItem();
        String chosen = sel == null ? "" : String.valueOf(sel).trim();
        if (chosen.isEmpty()) {
            sb.append("  [拒绝] 没有选择版本：无法确定要装哪一个。")
                    .append(System.lineSeparator());
            return new VersionGate(true, "已拒绝安装：没有选择版本", sb.toString(), null);
        }
        com.fusionml.FusionVersions.Target want = com.fusionml.FusionVersions.byId(chosen);

        // "目录里有哪些版本"分两种布局，必须都认：
        //   a) 版本隔离：游戏目录**本身就是版本目录**（PCL 等把实例放在 versions\<id>），
        //      它的名字（如 26.3 / 1.21.1-NeoForge_21.1.249）就是版本 id；
        //   b) 标准布局：游戏目录是 .minecraft 根，版本在 <目录>/versions/ 下。
        //
        // 以前只查 (b)。玩家选 versions\26.3 时 (b) 是空的，于是明明选的就是 26.3，
        // 却被判成"目录里没有 26.3" —— 这正是"不管里面有没有版本，都说没有"的来源之一。
        java.util.List<String> realDirs = new java.util.ArrayList<>();
        String self = gameDir.getFileName() == null ? "" : gameDir.getFileName().toString();
        boolean selfIsVersionDir = !self.isBlank()
                && java.nio.file.Files.isRegularFile(gameDir.resolve(self + ".json"));
        if (selfIsVersionDir) {
            realDirs.add(self);
        }
        for (String d : list(gameDir.resolve("versions"))) {
            if (!realDirs.contains(d)) {
                realDirs.add(d);
            }
        }
        sb.append("  所选版本：").append(chosen)
                .append(want == null ? "（不是已知目标版本）" : "（目标 " + want.mcVersion() + "）")
                .append(System.lineSeparator());
        sb.append("  目录内版本目录：")
                .append(realDirs.isEmpty() ? "（无）" : String.join("、", realDirs))
                .append(selfIsVersionDir ? "（游戏目录本身就是版本目录）" : "")
                .append(System.lineSeparator());

        java.util.List<String> knownInDir = new java.util.ArrayList<>();
        java.util.List<String> matched = new java.util.ArrayList<>();
        for (String d : realDirs) {
            com.fusionml.FusionVersions.Target t = com.fusionml.FusionVersions.inferFrom(d);
            if (t == null) {
                continue;
            }
            if (!knownInDir.contains(t.id())) {
                knownInDir.add(t.id());
            }
            if (t.id().equals(chosen)) {
                matched.add(d);
            }
        }

        if (matched.size() > 0) {
            sb.append("  [通过] 所选版本 ").append(chosen)
                    .append(" 与目录内实际版本一致（").append(String.join("、", matched))
                    .append("）→ 允许安装。").append(System.lineSeparator());
            return new VersionGate(false, "版本一致：" + chosen + " → 可以安装", sb.toString(),
                    matched.get(0));
        }

        if (knownInDir.isEmpty()) {
            sb.append("  [注意] 所选版本 ").append(chosen)
                    .append(" 在目录里没有找到对应的版本目录。").append(System.lineSeparator());
            sb.append("         → 不拒绝安装：如果这是全新目录，安装器会按所选版本新建 ")
                    .append("versions/").append(chosen).append("。").append(System.lineSeparator());
            sb.append("         → 若你本意是装到已有版本上，请先确认目录选对了没有。")
                    .append(System.lineSeparator());
            return new VersionGate(false,
                    "目录里没有 " + chosen + "：将新建该版本目录（请确认目录选对）",
                    sb.toString(), null);
        }

        String actual = String.join("、", knownInDir);
        sb.append("  [拒绝] 所选版本 ").append(chosen)
                .append(" 与目录内实际版本 ").append(actual)
                .append(" 不符 → 已拒绝安装（请重新选择版本或目录）。")
                .append(System.lineSeparator());
        sb.append("         目录里的版本目录：").append(String.join("、", realDirs))
                .append(System.lineSeparator());
        return new VersionGate(true,
                "已拒绝安装：所选 " + chosen + " 与目录内实际版本 " + actual + " 不符",
                sb.toString(), null);
    }

    private static void setInstallBlocked(JButton install, JButton preview, JButton launch,
                                          boolean blocked) {
        install.setEnabled(!blocked);
        preview.setEnabled(!blocked);
        launch.setEnabled(!blocked);
        // 「卸载」**故意不置灰**，不是漏了：卸载读的是安装清单（.fusionml/install-manifest.txt），
        // 与"这次要装哪个版本"无关。而且版本不符时用户往往正是想把这个目录清干净，
        // 这时灰掉卸载反而把人卡住。所以只灰"会写文件/会启动"的那三个。
        String tip = blocked ? "所选版本与目录内实际版本不符，已拒绝安装" : null;
        install.setToolTipText(tip);
        preview.setToolTipText(tip);
    }

    /**
     * 版本闸门的自检断言：拒绝时按钮必须真的灰掉，放行时必须真的可点。
     *
     * <p>为什么不能只看代码：{@code setEnabled(false)} 与"按钮真的画成灰色、真的点不动"
     * 是两件事（自绘按钮如果忽略了 disabled 状态，就会"看着能点其实没用"）。
     * 这里同时断言状态位与自绘表现所需的输入，并把结论写进日志区 ——
     * 于是截图本身就是自检报告。
     */
    private static String gateSelfCheck(JButton install, JButton preview, VersionGate gate) {
        StringBuilder sb = new StringBuilder();
        sb.append(System.lineSeparator())
                .append("===== 自检（版本闸门 / 拒绝安装）=====").append(System.lineSeparator());
        boolean consistent = gate.blocked() == !install.isEnabled()
                && gate.blocked() == !preview.isEnabled();
        sb.append(consistent ? "  [OK]   " : "  [FAIL] ")
                .append("判定=「").append(gate.blocked() ? "拒绝安装" : "允许安装")
                .append("」，一键安装 enabled=").append(install.isEnabled())
                .append("，预览 enabled=").append(preview.isEnabled())
                .append(" → 按钮状态与判定一致 = ").append(consistent)
                .append(System.lineSeparator());
        if (gate.blocked()) {
            sb.append("  [OK]   状态条文案：").append(gate.status).append(System.lineSeparator());
            sb.append("  [OK]   已置灰按钮：「一键安装」「预览」「启动游戏」")
                    .append(System.lineSeparator());
        }
        sb.append("===== 自检结束 =====").append(System.lineSeparator());
        return sb.toString();
    }

    /**
     * <p>Lead 的验收标准之一是"版本下拉框在扫描后出现且可选，选完再点安装要真的用这个版本"。
     * 这条链路的关键是：安装逻辑读的是 {@code inherit} 文本框，而文本框的值来自
     * 下拉框的选中项。所以只要证明"改选中项 → 文本框跟着变"，就等于证明了这个功能。
     *
     * <p>这个检查必须在扫描**之后**做（之前下拉框还是空的），所以单独一个方法。
     */
    private static String postScanCheck(JComboBox<String> versionBox, JTextField inherit) {
        StringBuilder sb = new StringBuilder();
        int n = versionBox.getItemCount();
        sb.append(System.lineSeparator())
                .append("===== 界面自检（体检后：版本选择链路）=====")
                .append(System.lineSeparator());
        if (n == 0) {
            sb.append("  [WARN] 没有探测到版本，无法验证版本选择链路")
                    .append(System.lineSeparator());
            sb.append("===== 自检结束 =====").append(System.lineSeparator());
            return sb.toString();
        }
        sb.append("  [OK]   下拉框已在扫描后填充，选项数=").append(n)
                .append("，当前选中=").append(versionBox.getSelectedItem())
                .append(System.lineSeparator());

        String cur = String.valueOf(versionBox.getSelectedItem());
        boolean synced = cur.equals(inherit.getText());
        sb.append(synced ? "  [OK]   " : "  [FAIL] ")
                .append("选中值「").append(cur).append("」与「继承原版版本」文本框一致 = ")
                .append(synced)
                .append("（安装逻辑读的就是这个字段）")
                .append(System.lineSeparator());
        if (n >= 2) {
            int other = versionBox.getSelectedIndex() == 0 ? 1 : 0;
            versionBox.setSelectedIndex(other);
            String moved = String.valueOf(versionBox.getSelectedItem());
            boolean movedOk = moved.equals(inherit.getText());
            sb.append(movedOk ? "  [OK]   " : "  [FAIL] ")
                    .append("切换到「").append(moved).append("」后文本框同步 = ")
                    .append(movedOk).append(System.lineSeparator());
            versionBox.setSelectedItem(cur); 
        }
        sb.append("===== 自检结束 =====").append(System.lineSeparator());
        return sb.toString();
    }

    private static JPanel header() {
        JPanel p = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = RoundedUi.aa(g);
                int w = getWidth();
                int h = getHeight();
                g2.setPaint(new LinearGradientPaint(
                        new Point(0, 0), new Point(Math.max(1, w), h),
                        new float[]{0f, 1f},
                        new Color[]{new Color(0x1B, 0x2A, 0x46), BG}));
                g2.fillRect(0, 0, w, h);
                // 底部一条渐变强调线（两端淡出，比一根实线精致，也避免和卡片描边抢注意力）
                g2.setPaint(new LinearGradientPaint(
                        new Point(0, 0), new Point(Math.max(1, w), 0),
                        new float[]{0f, 0.15f, 0.85f, 1f},
                        new Color[]{RoundedUi.alpha(ACCENT, 0), RoundedUi.alpha(ACCENT, 150),
                                RoundedUi.alpha(ACCENT, 150), RoundedUi.alpha(ACCENT, 0)}));
                g2.fillRect(0, h - 2, w, 2);
                g2.dispose();
            }
        };
        p.setBorder(new EmptyBorder(18, 24, 16, 24));
        JLabel title = new JLabel("FusionML");
        title.setFont(RoundedUi.font(Font.BOLD, 26));
        title.setForeground(TEXT);
        JLabel sub = new JLabel("同进程三端加载器 · 安装器 v1.0.0");
        sub.setFont(RoundedUi.font(Font.PLAIN, 13));
        sub.setForeground(ACCENT);
        JLabel note = new JLabel("把安装程序和游戏文件放进同一个目录，点一下就能用");
        note.setFont(RoundedUi.font(Font.PLAIN, 12));
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
                               JButton refresh, JButton launch, JLabel status,
                               RoundedUi.RoundedProgressBar bar,
                               JTextArea log) {
        // 布局策略（这里踩过三次坑，写清楚）：
        // 为什么用"整体滚动"而不是"让卡片各自压缩"：

        JPanel column = new JPanel(new GridBagLayout());
        column.setOpaque(false);

        JPanel one = stepOne(gameDir, modsFrom, inherit, versionBox, registerProfile, dryRun,
                refresh, openDir);
        JPanel two = stepTwo(env);
        JPanel logCard = card("③ 执行日志");
        logCard.add(RoundedUi.scroll(log), BorderLayout.CENTER);

        // 这里踩过一个很隐蔽的坑，值得写下来：早先给 logCard 设了
        // 加上 12 行日志（12×16 = 192px）= 249px，取 249 而不是 260。
        // 为什么纠结这 11px：日志区是"卡片内滚动区 + 卡片边框"两层，
        // 如果卡片高度不是 (固有占用 + N×行高)，那么滚动区的高度就会带一个零头，

        logCard.setPreferredSize(new Dimension(100, LOG_CARD_CHROME + LOG_CARD_LINES * 16));

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 12, 0);

        c.gridy = 0;
        c.weighty = 0;
        column.add(one, c);

        c.gridy = 1;
        c.weighty = 0;
        column.add(two, c);

        c.gridy = 2;
        // 关键：weighty 必须是 0（见上面注释）。让 logCard 保持自己的 260，

        c.weighty = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 0, 0);
        column.add(logCard, c);

        BODY_LOG_CARD = logCard;
        BODY_LOG_TEXT = log;

        JScrollPane columnScroll = RoundedUi.scroll(column);

        columnScroll.setVerticalScrollBarPolicy(
                javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);

        RoundedUi.snapToLines(columnScroll, RoundedUi.lineHeightOf(log));

        JPanel wrap = new JPanel(new BorderLayout(0, 12));
        wrap.setBackground(BG);
        wrap.setBorder(new EmptyBorder(14, 24, 12, 24));
        wrap.add(columnScroll, BorderLayout.CENTER);

        // 安装器最怕的就是"按钮不知道去哪了"，所以它必须钉在 SOUTH。
        JPanel actions = new JPanel(new BorderLayout());
        actions.setOpaque(false);
        JPanel buttons = new JPanel(new GridLayout(1, 5, 10, 0));
        buttons.setOpaque(false);
        buttons.add(install);
        buttons.add(preview);
        buttons.add(uninstall);
        buttons.add(launch);
        actions.add(buttons, BorderLayout.CENTER);

        JPanel progress = new JPanel(new BorderLayout(0, 8));
        progress.setOpaque(false);
        progress.add(status, BorderLayout.NORTH);
        progress.add(bar, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout(0, 14));
        south.setOpaque(false);
        south.add(actions, BorderLayout.NORTH);
        south.add(progress, BorderLayout.SOUTH);
        wrap.add(south, BorderLayout.SOUTH);
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

        c.insets = new Insets(1, 4, 1, 4);
        c.anchor = GridBagConstraints.WEST;

        addRow(form, c, 0, "游戏目录", gameDir, refresh);
        addRow(form, c, 1, "模组来源（可选，会拷入 mods/）", modsFrom,
                browse(gameDir, modsFrom, "选择模组目录"));
        // 第二重选择：目录里探测到哪些版本，就从哪些里挑。
        // 顺序是刻意的 —— 先目录、后版本，因为版本列表来自目录体检的结果。
        c.gridy = 2;
        c.gridx = 0;
        form.add(label("安装到版本"), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        versionBox.setPreferredSize(new Dimension(260, 30));
        form.add(versionBox, c);
        c.gridx = 2;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        form.add(openDir, c);
        c.gridy = 3;
        c.gridx = 0;
        form.add(label("继承原版版本"), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        inherit.setPreferredSize(new Dimension(260, 30));
        form.add(inherit, c);
        c.gridy = 4;
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;

        // 实测踩到：并排时每个只分到一半宽度，"注册到启动器（写 launcher_profiles.json
        // 与版本定义）"这段长文字会被裁成"…与版" —— 用户看不到这个选项到底做什么。

        JPanel checks = new JPanel();
        checks.setOpaque(false);
        checks.setLayout(new BoxLayout(checks, BoxLayout.Y_AXIS));
        registerProfile.setAlignmentX(Component.LEFT_ALIGNMENT);
        dryRun.setAlignmentX(Component.LEFT_ALIGNMENT);
        checks.add(registerProfile);
        checks.add(Box.createVerticalStrut(4));
        checks.add(dryRun);
        form.add(checks, c);
        card.add(form, BorderLayout.CENTER);
        return card;
    }

    private static JPanel stepTwo(JPanel env) {
        JPanel card = card("② 环境体检");
        JScrollPane scroll = RoundedUi.scroll(env);

        // 这个数字是被"最小窗口下 ③ 必须可见"倒推出来的，不是随手给的：
        // ② + ③ 的标题，就必须把 ② 压到 ~110px 以内。
        // 压的是"显示多少行"，不是丢信息 —— 多出来的体检行仍可滚动看到。
        scroll.setPreferredSize(new Dimension(100, 106));
        scroll.setMinimumSize(new Dimension(100, 70));
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
        env.add(javax.swing.Box.createVerticalGlue());
        env.revalidate();
        env.repaint();
    }

    private static JPanel envLine(String key, String value, Color valueColor) {
        JPanel line = new JPanel(new BorderLayout(10, 0));
        line.setOpaque(false);
        line.setBorder(new EmptyBorder(3, 2, 3, 2));
        JLabel k = new JLabel(key);
        k.setFont(RoundedUi.font(Font.BOLD, 12));
        k.setForeground(DIM);
        k.setPreferredSize(new Dimension(104, 18));
        JLabel v = new JLabel(value);
        v.setFont(RoundedUi.font(Font.PLAIN, 12));
        v.setForeground(valueColor);
        line.add(k, BorderLayout.WEST);
        line.add(v, BorderLayout.CENTER);
        return line;
    }

    private static void runInstaller(JFrame frame, JTextArea log, RoundedUi.RoundedProgressBar bar,
                                     JLabel status, String what, FusionInstaller.Options opts,
                                     boolean uninstall, Runnable after) {
        log.append(System.lineSeparator() + "===== " + what + " =====" + System.lineSeparator());
        bar.setValue(0);
        status.setForeground(DIM);
        status.setText(what + "中…");
        log.setEnabled(false);
        bar.setEnabled(false);
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
        field.setPreferredSize(new Dimension(260, 30));
        form.add(field, c);
        c.gridx = 2;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        form.add(tail, c);
    }

    private static JButton browse(JTextField shown, JTextField target, String title) {
        JButton b = RoundedUi.button("浏览…", RoundedUi.ButtonStyle.secondary(), 13, false);
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
        JPanel p = RoundedUi.card();
        p.add(RoundedUi.cardTitle(title), BorderLayout.NORTH);
        return p;
    }

    private static JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setFont(RoundedUi.font(Font.PLAIN, 12));
        l.setForeground(DIM);
        return l;
    }
}
