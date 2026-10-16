package com.fusionml.hud;

/**
 * HUD 绘制后端。
 *
 * <p>把"HUD 长什么样"和"用什么画"彻底分开：
 * {@link HudRenderer} 只负责布局、配色、图形与文字内容；具体画线画字由后端实现。
 *
 * <p>这样同一套 HUD 可以：
 * <ul>
 *   <li>在游戏里用游戏自己的字体渲染器绘制（实现本接口即可，无需改动 HUD 代码）；</li>
 *   <li>在独立环境用 AWT 绘制（{@link AwtHudBackend}），用于预览、截图与自动化验证。</li>
 * </ul>
 * 也正因为有了独立后端，这套 HUD 才能在"没有游戏"的环境里被真正跑起来并截图验证，
 * 而不是只能靠"我觉得它应该能显示"。
 */
public interface HudBackend {

    void fillRect(int x, int y, int w, int h, int argb);

    void strokeRect(int x, int y, int w, int h, int argb);

    void fillRoundRect(int x, int y, int w, int h, int radius, int argb);

    void line(int x1, int y1, int x2, int y2, int argb);

    void fillPolygon(int[] xs, int[] ys, int count, int argb);

    default void fillGradientRect(int x, int y, int w, int h, int argbLeft, int argbRight) {
        fillRect(x, y, w, h, argbLeft);
    }

    void text(String s, int x, int baselineY, int size, int argb, boolean bold);

    int textWidth(String s, int size, boolean bold);

    int fontHeight(int size);
}
