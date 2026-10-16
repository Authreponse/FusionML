package com.fusionml.hud;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

// 主加载页面：加载器接管游戏主界面。

// 做法不是"画在原生主界面上"，而是**把原生主界面收起来**，改由我们整屏渲染：
// 原生 TitleScreen 一旦出现就把它置空（在下一 tick 做，避免在渲染中改屏幕导致重入），

// 和 FusionHud 一样，这里对游戏 API 的访问全部走反射，避免把加载器 jar 绑死在某个游戏版本上。
public final class FusionMainPage {

    private static final String TITLE_SCREEN = "net.minecraft.client.gui.screens.TitleScreen";

    private static final int BG = 0xF0101018;
    private static final int PANEL = 0xFF181826;
    private static final int ACCENT = 0xFF5AC8FA;
    private static final int TEXT = 0xFFF2F4FF;
    private static final int DIM = 0xFF9AA3C4;
    private static final int OK = 0xFF7FE3A0;

    private static boolean active = true;
    private static boolean userDismissed;
    private static boolean failed;
    private static String failReason;
    private static boolean announcedFirstFrame;
    private static boolean announcedSuppress;
    private static long shownAt = System.currentTimeMillis();
    private static ModListModel modList;
    private static StatusLine status = StatusLine::current;

    // 反射缓存
    private static Method mGetInstance;
    private static Method mSetScreen;
    private static Method mExecute;
    private static Method mGetWindow;
    private static Method mWindowHandle;
    private static Field fScreen;
    private static Method mFill;
    private static Method mText;

    private FusionMainPage() {
    }

    public static void onTitleExtract(Object screen, Object extractor, int mouseX, int mouseY,
                                      float partialTick) {
        if (!active || failed || userDismissed) {
            return;
        }
        try {
            Object font = font(extractor);
            int[] size = screenSize();
            draw(extractor, font, size[0], size[1]);
            if (!announcedFirstFrame) {
                announcedFirstFrame = true;
                System.out.println("[FusionML] 主加载页面已接管游戏主界面（" + size[0] + "x" + size[1]
                        + "，左下角显示加载器名称）");
            }

            FusionHud.notifyInGamePageDrawn();
        } catch (Throwable t) {
            failed = true;
            failReason = t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : "：" + t.getMessage());
            System.out.println("[FusionML] 主加载页面已停用（不影响游戏）：" + failReason);
        }
    }

    private static Object font(Object extractor) throws Exception {
        Object mc = minecraft();
        Field f = Class.forName("net.minecraft.client.Minecraft").getField("font");
        Object font = f.get(mc);
        if (font == null) {
            throw new IllegalStateException("字体尚未就绪");
        }
        return font;
    }

    private static int[] screenSize() {
        try {
            Object mc = minecraft();
            Object win = Class.forName("net.minecraft.client.Minecraft")
                    .getMethod("getWindow").invoke(mc);
            Class<?> winClass = Class.forName("com.mojang.blaze3d.platform.Window");
            int w = (Integer) winClass.getMethod("getGuiScaledWidth").invoke(win);
            int h = (Integer) winClass.getMethod("getGuiScaledHeight").invoke(win);
            return new int[] {w, h};
        } catch (Throwable t) {
            return new int[] {1280, 720};
        }
    }

    public interface StatusLine {
        static String current() {
            return "";
        }

        String text();
    }

    public static void setModList(ModListModel model) {
        modList = model;
    }

    public static void setStatus(StatusLine line) {
        status = line;
    }

    public static void tick(Object minecraft, Object extractor, Object font,
                            int screenW, int screenH) {
        if (!active || failed) {
            return;
        }
        try {
            suppressVanillaTitleScreen(minecraft);
            if (userDismissed) {
                return;
            }
            draw(extractor, font, screenW, screenH);
            if (!announcedFirstFrame) {
                announcedFirstFrame = true;

                System.out.println("[FusionML] 主加载页面已接管游戏主界面（"
                        + screenW + "x" + screenH + "，左下角显示加载器名称）");
            }
        } catch (Throwable t) {
            failed = true;
            failReason = t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : "：" + t.getMessage());
            System.out.println("[FusionML] 主加载页面已停用（不影响游戏）：" + failReason);
        }
    }

    /**
     * 收起原生主界面，让加载器接管。
     *
     * <p>置空屏幕走 {@code Minecraft.execute}，也就是排到主线程下一个任务 ——
     * 直接在渲染回调里改当前屏幕是重入操作，很容易把界面状态搞乱。
     */
    private static void suppressVanillaTitleScreen(Object mc) throws Exception {
        if (userDismissed) {
            return;
        }
        if (fScreen == null) {
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
            fScreen = mcClass.getField("screen");
            mExecute = mcClass.getMethod("execute", Runnable.class);
            mSetScreen = mcClass.getMethod("setScreen",
                    Class.forName("net.minecraft.client.gui.screens.Screen"));
        }
        Object screen = fScreen.get(mc);
        if (screen != null && TITLE_SCREEN.equals(screen.getClass().getName())) {
            mExecute.invoke(mc, (Runnable) () -> {
                try {
                    mSetScreen.invoke(mc, (Object) null);
                    if (!announcedSuppress) {
                        announcedSuppress = true;
                        System.out.println("[FusionML] 已收起原生主界面，改由加载器接管");
                    }
                } catch (Throwable ignored) {
                    // 置空失败就保持原样，页面照样显示在它上面
                }
            });
        }
    }

    private static void draw(Object g, Object font, int w, int h) throws Exception {
        Class<?> gcls = g.getClass();
        if (mFill == null) {
            mFill = gcls.getMethod("fill", int.class, int.class, int.class, int.class, int.class);
            mText = gcls.getMethod("text", Class.forName("net.minecraft.client.gui.Font"),
                    String.class, int.class, int.class, int.class, boolean.class);
        }

        mFill.invoke(g, 0, 0, w, h, BG);

        mFill.invoke(g, 0, 0, w, 3, ACCENT);

        int cx = w / 2;
        int y = Math.max(60, h / 2 - 150);

        String logo = "FusionML";
        int logoW = width(font, logo);
        mText.invoke(g, font, logo, cx - logoW / 2, y, ACCENT, true);
        int lineW = Math.max(60, logoW / 2);
        mFill.invoke(g, cx - logoW / 2 - lineW - 12, y + 4, cx - logoW / 2 - 12, y + 5, 0x66FFFFFF);
        mFill.invoke(g, cx + logoW / 2 + 12, y + 4, cx + logoW / 2 + 12 + lineW, y + 5, 0x66FFFFFF);
        y += 22;

        String tagline = "三端原生模组加载器 · 并行发现 · 自研注入";
        mText.invoke(g, font, tagline, cx - width(font, tagline) / 2, y, DIM, false);
        y += 30;

        int panelW = Math.min(560, w - 80);
        int panelX = cx - panelW / 2;
        int panelH = 150;
        mFill.invoke(g, panelX, y, panelX + panelW, y + panelH, PANEL);
        mFill.invoke(g, panelX, y, panelX + panelW, y + 1, 0x66FFFFFF);

        String statusText = status == null ? "" : status.text();
        mText.invoke(g, font, statusText.isEmpty() ? "准备中…" : statusText,
                panelX + 16, y + 24, TEXT, true);

        ModListModel.Stats st = modList == null ? null : modList.stats();
        if (st != null) {
            mText.invoke(g, font, "模组 " + st.total() + " 个（" + st.formatLine() + "）",
                    panelX + 16, y + 46, ACCENT, false);
            mText.invoke(g, font, "类 " + st.totalClasses() + " 个 · 依赖 "
                    + st.totalDependencies() + " 条 · 加载层次 " + st.tiers() + " 层",
                    panelX + 16, y + 62, DIM, false);
        }
        mText.invoke(g, font, "加载器：FusionML 1.0.0  ·  运行 Java "
                + System.getProperty("java.version"),
                panelX + 16, y + 84, DIM, false);
        mText.invoke(g, font, "平台运行时由官方实现提供，发现 / 索引 / 注入由 FusionML 完成",
                panelX + 16, y + 100, DIM, false);

        int barY = y + panelH - 26;
        int barW = panelW - 32;
        mFill.invoke(g, panelX + 16, barY, panelX + 16 + barW, barY + 6, 0x40000000);
        double phase = (System.currentTimeMillis() - shownAt) % 2600 / 2600.0;
        int segW = Math.max(40, barW / 4);
        int segX = panelX + 16 + (int) ((barW - segW) * (phase < 0.5 ? phase * 2 : (1 - phase) * 2));
        mFill.invoke(g, segX, barY, segX + segW, barY + 6, ACCENT);
        y += panelH + 26;

        int btnW = 220;
        int btnH = 30;
        int btnX = cx - btnW / 2;
        int btnY = y;
        boolean hover = isHover(mcWindowHandle(), btnX, btnY, btnW, btnH, w, h);
        mFill.invoke(g, btnX, btnY, btnX + btnW, btnY + btnH, hover ? 0xFF2A6E8C : 0xFF1E3A4A);
        String btn = "进入游戏";
        mText.invoke(g, font, btn, cx - width(font, btn) / 2, btnY + 11, TEXT, true);

        if (hover && isMouseDown()) {
            dismiss();
        }

        // 左下角：加载器名称（用户明确要求的）
        String mine = "FusionML 加载器";
        mText.invoke(g, font, mine, 12, h - 18, ACCENT, true);
        String ver = "v1.0.0";
        mText.invoke(g, font, ver, 12 + width(font, mine) + 8, h - 18, DIM, false);

        String hint = "由 FusionML 接管主界面";
        mText.invoke(g, font, hint, w - 12 - width(font, hint), h - 18, DIM, false);
    }

    public static void dismiss() {
        userDismissed = true;
        System.out.println("[FusionML] 用户选择进入游戏，恢复原生主界面");
        try {
            Object mc = minecraft();
            if (mSetScreen != null) {
                Class<?> titleClass = Class.forName(TITLE_SCREEN);
                Object title = titleClass.getDeclaredConstructor().newInstance();
                mSetScreen.invoke(mc, title);
            }
        } catch (Throwable t) {
            System.out.println("[FusionML] 恢复原生主界面失败：" + t);
        }
    }

    private static Object minecraft() throws Exception {
        if (mGetInstance == null) {
            mGetInstance = Class.forName("net.minecraft.client.Minecraft").getMethod("getInstance");
        }
        return mGetInstance.invoke(null);
    }

    private static long mcWindowHandle() {
        try {
            if (mGetWindow == null) {
                Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
                mGetWindow = mcClass.getMethod("getWindow");
                Class<?> winClass = Class.forName("com.mojang.blaze3d.platform.Window");
                mWindowHandle = winClass.getMethod("getWindow");
            }
            Object win = mGetWindow.invoke(minecraft());
            return (Long) mWindowHandle.invoke(win);
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static boolean isHover(long handle, int bx, int by, int bw, int bh, int sw, int sh) {
        if (handle == 0) {
            return false;
        }
        try {
            Class<?> glfw = Class.forName("org.lwjgl.glfw.GLFW");
            double[] xs = new double[1];
            double[] ys = new double[1];
            glfw.getMethod("glfwGetCursorPos", long.class, double[].class, double[].class)
                    .invoke(null, handle, xs, ys);

            int[] ww = new int[1];
            int[] wh = new int[1];
            glfw.getMethod("glfwGetWindowSize", long.class, int[].class, int[].class)
                    .invoke(null, handle, ww, wh);
            if (ww[0] == 0 || wh[0] == 0) {
                return false;
            }
            double gx = xs[0] * sw / ww[0];
            double gy = ys[0] * sh / wh[0];
            return gx >= bx && gx <= bx + bw && gy >= by && gy <= by + bh;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isMouseDown() {
        long handle = mcWindowHandle();
        if (handle == 0) {
            return false;
        }
        try {
            Class<?> glfw = Class.forName("org.lwjgl.glfw.GLFW");
            Method getMouseButton = glfw.getMethod("glfwGetMouseButton", long.class, int.class);
            int left = glfw.getField("GLFW_MOUSE_BUTTON_LEFT").getInt(null);
            return (Integer) getMouseButton.invoke(null, handle, left) == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    private static int width(Object font, String text) throws Exception {
        Method m = font.getClass().getMethod("width", String.class);
        return (Integer) m.invoke(font, text);
    }
}
