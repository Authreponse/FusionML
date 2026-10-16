package com.fusionml.hud;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

// 游戏内 HUD：帧率面板 + 模组列表面板。
//
// 由我们自己的注入引擎挂到游戏的渲染方法上，不依赖任何平台的模组 API。
//
// 关键约束：**本类不能用编译期引用去碰游戏类**。
// 我们的加载器 jar 要能在没有游戏的环境里独立运行（安装器、基准测试、自检都靠它），
// 一旦在这里 import 了 net.minecraft.*，整个 jar 就绑死在某个游戏版本上了。
// 所以对游戏 API 的访问全部走反射，并且把 Method/Field 缓存起来 —— HUD 每帧都要画，
// 每帧重新查找方法会成为实打实的开销。
public final class FusionHud {

    private static final FpsMonitor FPS = new FpsMonitor(180);
    private static final int GRAPH_SAMPLES = 90;
    private static final double[] SAMPLES = new double[GRAPH_SAMPLES];

    private static ModListModel modList;
    private static boolean modListVisible = true;
    private static volatile boolean failed;
    private static String failReason;
    /**
     * 早期窗口的句柄。
     *
     * <p>用途只有一个，但很重要：让它保持显示，直到游戏内的 HUD **真正画出第一帧**
     * 才关闭。这样"平台引导期间的黑屏"由早期窗口兜住，紧接着接上游戏内的画面，
     * 玩家全程看到的是一段连续的进度，而不是"窗口一关、黑屏几秒、游戏突然出现"。
     */
    private static volatile com.fusionml.earlywindow.EarlyWindow earlyWindow;

    // 反射缓存
    private static Method mFill;
    private static Method mText;
    private static Method mGetInstance;
    private static Method mGetWindow;
    private static Method mWindowWidth;
    private static Method mWindowHeight;
    private static Field fFont;
    private static Object cachedFont;

    private FusionHud() {
    }

    /** 由前置分析把模组列表交给 HUD 展示（与命令行、独立窗口用的是同一份模型）。 */
    public static void setModList(ModListModel model) {
        modList = model;
    }

    /** 交接：早期窗口的关闭时机交给游戏内页面决定。 */
    public static void handOverEarlyWindow(com.fusionml.earlywindow.EarlyWindow window) {
        earlyWindow = window;
    }

    /** 早期窗口是否已被关闭（看门线程据此停止兜底动作）。 */
    public static boolean earlyWindowClosed() {
        return earlyWindow == null;
    }

    /** 由看门线程标记"已由兜底逻辑关闭"。 */
    public static void markEarlyWindowClosed() {
        earlyWindow = null;
    }

    /** 游戏内 HUD 成功绘制一帧后调用。 */
    public static void notifyInGamePageDrawn() {
        closeEarlyWindowIfDrawn();
    }

    /** 游戏主循环入口的钩子：一执行就说明游戏窗口已经就绪。 */
    private static java.util.List<String> extraMixinConfigs = java.util.List.of();
    private static boolean mixinExtraDone;

    /** 由前置分析传入"非 Fabric 侧声明的 mixin 配置"，等平台把 Mixin 服务拉起来后再注册。 */
    public static void setExtraMixinConfigs(java.util.List<String> configs) {
        extraMixinConfigs = configs == null ? java.util.List.of() : configs;
    }

    /** 游戏主循环是否已经开始（供看门线程判断）。 */
    public static boolean isGameLoopStarted() {
        return gameLoopStarted;
    }

    private static volatile boolean gameLoopStarted;

    /**
     * 游戏主循环入口的钩子。
     *
     * <p>这里做两件事：标记"循环已开始"（供早期窗口看门线程用），
     * 以及把跨端 Mixin 配置登记进平台的服务里。
     */
    public static void onGameLoopStarted(Object minecraft) {
        System.out.println("[FusionML/探针] Minecraft.run() 钩子已触发（游戏主循环开始）");
        gameLoopStarted = true;
        // 跨类加载器的信号：必须用 JVM 级属性，不能用静态字段。
        //
        // 原因：FusionHud 会被**两份**类加载器各加载一次 ——
        // premain 阶段是系统加载器那份，游戏里的钩子跑的是平台类加载器那份。
        // 静态字段因此是"各一份"，前置阶段写进去的值钩子那边读不到。
        // 系统属性属于整个 JVM，任何一份都能读到，是这个场景唯一可靠的通道。
        System.setProperty("fusion.gameLoopStarted", "true");
        // 此刻平台的 Mixin 服务已经初始化（Knot 在启动时完成），
        // 正是把"另一端的配置"注册进同一个服务的时机。
        String fromProperty = System.getProperty("fusion.extraMixinConfigs", "");
        java.util.List<String> configs = extraMixinConfigs.isEmpty() && !fromProperty.isBlank()
                ? java.util.List.of(fromProperty.split(",")) : extraMixinConfigs;
        // 早注册路径（跨端桥挂在宿主平台 Mixin 引导方法的头部）如果已经成功，
        // 这里就不能再注册一遍 —— 配置只在"集合定型之前"加进去才作用于全部类，
        // 这个时间点再注册只会变成一条永远不生效的记录。
        String bridged = System.getProperty("fusion.bridgeConfigsVerified", "");
        if (!bridged.isBlank()) {
            System.out.println("[FusionML] 跨端 Mixin 配置已在宿主平台初始化处注册（生效 "
                    + bridged + " 条），此处不再重复");
            mixinExtraDone = true;
        }
        if (!mixinExtraDone && !configs.isEmpty()) {
            mixinExtraDone = true;
            try {
                // 兜底路径。说清楚它的性质：Mixin 的配置是在**阶段切换时定型**的，
                // 而游戏主循环开始时那些切换早就走完了，所以这个时机注册进来的配置
                // 基本不会再作用于任何类。它存在的意义是"万一早注册挂载点没生效，
                // 至少在这里留下一条能看出问题的记录"，而不是假装还能补救。
                var r = com.fusionml.mixin.FusionMixinRegistry.register(
                        FusionHud.class.getClassLoader(), configs, System.out::println);
                System.out.println("[FusionML] 跨端 Mixin 兜底注册（时机已偏晚，仅作记录）："
                        + r.summary());
                for (String line : r.notes()) {
                    System.out.println("[FusionML]   " + line);
                }
            } catch (Throwable t) {
                System.out.println("[FusionML] Mixin 配置注册未完成：" + t);
            }
        }
        // 窗口已经出来了，早期窗口的使命结束。留一小段时间让游戏画面先出来，避免空档。
        new Thread(() -> {
            try {
                Thread.sleep(1200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            closeEarlyWindowIfDrawn();
        }, "fusion-hud-handover").start();
    }

    /**
     * 注入到 {@code Hud.extractRenderState} 的钩子：游戏内有世界时每帧调用。
     *
     * <p>注意它**在主界面上不会被调用**（那时没有 HUD 可画）——
     * 这是刻意接受的：我们不再往 Gui/TitleScreen 上挂额外钩子。
     * 早先为"让主界面也显示"做过尝试（Gui 的 HEAD/RETURN 注入、
     * 每帧轮询键盘、自绘模组菜单），全部撤掉了 ——
     * 那些改动引入了闪烁与输入失灵，收益不值这个代价。
     */
    public static void onHudExtract(Object hud, Object extractor, Object deltaTracker) {
        render(extractor);
    }

    /**
     * 取 GUI 对象上的渲染提取器（备用路径，当前未挂钩子）。
     */
    private static Object guiExtractorOf(Object gui) {
        if (gui == null) {
            return null;
        }
        for (String field : new String[] {"guiRenderState", "guiGraphicsExtractor",
                "extractor", "guiGraphics"}) {
            try {
                java.lang.reflect.Field f = gui.getClass().getDeclaredField(field);
                f.setAccessible(true);
                Object v = f.get(gui);
                if (v != null) {
                    return v;
                }
            } catch (Throwable ignored) {
                // 换下一个候选
            }
        }
        return null;
    }

    private static void render(Object extractor) {
        if (extractor == null) {
            return;
        }
        FPS.tick();
        if (failed) {
            return;
        }
        try {
            draw(extractor);
        } catch (Throwable t) {
            // 只报一次：HUD 每帧都跑，出错就刷屏会淹掉真正的日志。
            failed = true;
            failReason = t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : "：" + t.getMessage());
            System.out.println("[FusionML] 游戏内 HUD 已停用（不影响游戏）：" + failReason);
        }
    }

    private static void draw(Object g) throws Exception {
        Class<?> gcls = g.getClass();
        if (mFill == null) {
            mFill = gcls.getMethod("fill", int.class, int.class, int.class, int.class, int.class);
            Class<?> fontClass = Class.forName("net.minecraft.client.gui.Font");
            mText = gcls.getMethod("text", fontClass, String.class, int.class, int.class,
                    int.class, boolean.class);
        }
        Object font = font();
        boolean shadow = true;

        int screenW = screenWidth();
        int screenH = screenHeight();

        drawFpsPanel(g, font, shadow);
        if (modListVisible && modList != null) {
            drawModList(g, font, shadow, screenW, screenH);
        }
        // 先画完一帧，再收早期窗口 —— 顺序反了就会出现"窗口没了、游戏画面还没出来"的空档。
        closeEarlyWindowIfDrawn();
    }

    private static void drawFpsPanel(Object g, Object font, boolean shadow) throws Exception {
        int x = 8;
        int y = 8;
        int w = 186;
        int h = 74;
        // 半透明深色底：保证在任何背景下都能看清，这是 HUD 可读性的底线。
        mFill.invoke(g, x, y, x + w, y + h, 0xC812121A);
        mFill.invoke(g, x, y, x + w, y + 1, 0x66FFFFFF);

        double fps = FPS.recentFps();
        int color = fps >= 55 ? 0xFF54D98C : (fps >= 30 ? 0xFFF2C14E : 0xFFEF5B6B);
        String fpsText = fps >= 100 ? String.valueOf(Math.round(fps))
                : String.format(Locale.ROOT, "%.1f", fps);
        mText.invoke(g, font, fpsText, x + 8, y + 14, color, shadow);
        mText.invoke(g, font, "FPS", x + 8 + width(font, fpsText) + 4, y + 14, 0xFF9AA3C4, shadow);

        String line2 = String.format(Locale.ROOT, "%.1fms  1%%低 %.0f  最差 %.0f",
                FPS.avgMs(), FPS.lowFps(0.01), FPS.worstMs());
        mText.invoke(g, font, line2, x + 8, y + 30, 0xFF9AA3C4, shadow);

        // 帧时间柱状图：用一串细矩形拼出来，比折线更适合"一眼看抖动"。
        FPS.copyRecentMs(SAMPLES);
        double max = 20;
        for (double v : SAMPLES) {
            max = Math.max(max, v);
        }
        int gx = x + 8;
        int gy = y + 60;
        int gw = w - 16;
        int gh = 10;
        mFill.invoke(g, gx, gy, gx + gw, gy + gh, 0x33000000);
        for (int i = 0; i < GRAPH_SAMPLES; i++) {
            int barH = (int) Math.min(gh - 1, SAMPLES[i] / max * (gh - 1));
            int bx = gx + i;
            mFill.invoke(g, bx, gy + gh - 1 - barH, bx + 1, gy + gh - 1, 0xFF7FD4FF);
        }
        mText.invoke(g, font, "峰值 " + String.format(Locale.ROOT, "%.0f", max) + "ms",
                gx, gy - 10, 0xFF9AA3C4, shadow);
    }

    private static void drawModList(Object g, Object font, boolean shadow, int screenW, int screenH)
            throws Exception {
        int w = 300;
        int rowH = 11;
        int maxRows = Math.min(24, Math.max(6, (screenH - 120) / rowH));
        List<ModListModel.Entry> rows = modList.query("", null, ModListModel.Sort.TIER);
        int shown = Math.min(rows.size(), maxRows);
        int h = 34 + shown * rowH + 6;
        int x = screenW - w - 8;
        int y = 8;
        mFill.invoke(g, x, y, x + w, y + h, 0xC812121A);
        mFill.invoke(g, x, y, x + w, y + 1, 0x66FFFFFF);

        ModListModel.Stats st = modList.stats();
        mText.invoke(g, font, "模组列表 " + st.total() + " 个", x + 6, y + 12, 0xFFF2F4FF, shadow);
        mText.invoke(g, font, st.formatLine(), x + 6, y + 24, 0xFF5AC8FA, shadow);

        for (int i = 0; i < shown; i++) {
            ModListModel.Entry e = rows.get(i);
            int ry = y + 34 + i * rowH;
            int statusColor = e.fatal ? 0xFFEF5B6B : (e.issues.isEmpty() ? 0xFF54D98C : 0xFFF2C14E);
            mFill.invoke(g, x + 4, ry + 2, x + 7, ry + 5, statusColor);
            mText.invoke(g, font, ModListModel.trim(e.name(), 24), x + 11, ry, 0xFFF2F4FF, shadow);
            String tail = e.format().displayName() + " L" + e.tier;
            mText.invoke(g, font, tail, x + w - 6 - width(font, tail), ry, 0xFF9AA3C4, shadow);
        }
    }

    // ------------------------------------------------------------------ 反射工具

    private static Object font() throws Exception {
        if (cachedFont != null) {
            return cachedFont;
        }
        if (mGetInstance == null) {
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
            mGetInstance = mcClass.getMethod("getInstance");
        }
        Object mc = mGetInstance.invoke(null);
        if (fFont == null) {
            fFont = mc.getClass().getDeclaredField("font");
            fFont.setAccessible(true);
        }
        cachedFont = fFont.get(mc);
        return cachedFont;
    }

    private static int width(Object font, String s) {
        try {
            Method m = font.getClass().getMethod("width", String.class);
            Object v = m.invoke(font, s);
            return v instanceof Number n ? n.intValue() : s.length() * 6;
        } catch (Throwable t) {
            return s.length() * 6;
        }
    }

    private static int screenWidth() {
        try {
            return windowDimension(true);
        } catch (Throwable t) {
            return 854;
        }
    }

    private static int screenHeight() {
        try {
            return windowDimension(false);
        } catch (Throwable t) {
            return 480;
        }
    }

    private static int windowDimension(boolean width) throws Exception {
        if (mGetInstance == null) {
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
            mGetInstance = mcClass.getMethod("getInstance");
        }
        Object mc = mGetInstance.invoke(null);
        if (mGetWindow == null) {
            mGetWindow = mc.getClass().getMethod("getWindow");
        }
        Object window = mGetWindow.invoke(mc);
        // 用缩放后的 GUI 坐标（HUD 的绘制坐标系），不是物理像素。
        if (width) {
            if (mWindowWidth == null) {
                mWindowWidth = window.getClass().getMethod("getGuiScaledWidth");
            }
            Object v = mWindowWidth.invoke(window);
            return v instanceof Number n ? n.intValue() : 854;
        }
        if (mWindowHeight == null) {
            mWindowHeight = window.getClass().getMethod("getGuiScaledHeight");
        }
        Object v = mWindowHeight.invoke(window);
        return v instanceof Number n ? n.intValue() : 480;
    }

    private static void closeEarlyWindowIfDrawn() {
        com.fusionml.earlywindow.EarlyWindow w = earlyWindow;
        if (w == null) {
            return;
        }
        earlyWindow = null;
        try {
            w.close();
        } catch (Throwable ignored) {
            // 关不掉也不该影响游戏
        }
    }
}
