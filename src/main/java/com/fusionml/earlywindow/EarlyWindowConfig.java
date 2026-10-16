package com.fusionml.earlywindow;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * 早期窗口的可配置项。
 *
 * <p>取值优先级从高到低：
 * <ol>
 *   <li>系统属性 {@code -Dfusion.ew.<键>=值}（临时覆盖，适合调试）</li>
 *   <li>配置文件 {@code config/fusion-earlywindow.properties}（适合长期自定义）</li>
 *   <li>内置默认值</li>
 * </ol>
 *
 * <p>除具名项之外，任何 {@code fusion.ew.custom.<键>} 都会被原样收进 {@link #custom()}，
 * 交给自定义实现自己解释。于是"自定义窗口想加什么选项"完全不需要改动加载器本身。
 */
public final class EarlyWindowConfig {

    public static final String CONFIG_PATH = "config/fusion-earlywindow.properties";

    public enum ProgressStyle {
        BAR,
        RING,
        PERCENT
    }

    public String title = "FusionML";
    public String subtitle = "正在加载模组";
    public String logoText = "FusionML";
    public String footer = "";
    public int width = 520;
    public int height = 300;
    public int background = 0xF0101018;
    public int panel = 0xFF181826;
    public int accent = 0xFF5AC8FA;
    public int textColor = 0xFFF2F4FF;
    public int dimColor = 0xFF9AA3C4;
    public ProgressStyle progressStyle = ProgressStyle.BAR;
    public boolean showLog = true;
    public int logLines = 6;
    public boolean showTiming = true;
    public boolean alwaysOnTop = true;
    public String iconPath = "";
    public boolean closeOnFinish = true;
    public int closeDelayMs = 900;
    public boolean enabled = true;
    public final Map<String, String> custom = new LinkedHashMap<>();

    public static EarlyWindowConfig load() {
        EarlyWindowConfig c = new EarlyWindowConfig();
        apply(c, readConfigFile());
        apply(c, System.getProperties());
        return c;
    }

    private static Properties readConfigFile() {
        Properties p = new Properties();
        Path path = Path.of(CONFIG_PATH);
        if (Files.isReadable(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                p.load(in);
            } catch (IOException ignored) {
                // 配置文件读不了不该影响启动，静默退回默认值。
            }
        }
        return p;
    }

    private static void apply(EarlyWindowConfig c, Properties p) {
        c.title = str(p, c.title, "fusion.ew.title", "title");
        c.subtitle = str(p, c.subtitle, "fusion.ew.subtitle", "subtitle");
        c.logoText = str(p, c.logoText, "fusion.ew.logo", "logo");
        c.footer = str(p, c.footer, "fusion.ew.footer", "footer");
        c.width = intOr(p, c.width, "fusion.ew.width", "width");
        c.height = intOr(p, c.height, "fusion.ew.height", "height");
        c.background = color(p, c.background, "fusion.ew.background", "background");
        c.panel = color(p, c.panel, "fusion.ew.panel", "panel");
        c.accent = color(p, c.accent, "fusion.ew.accent", "accent");
        c.textColor = color(p, c.textColor, "fusion.ew.text", "text");
        c.dimColor = color(p, c.dimColor, "fusion.ew.dim", "dim");
        c.showLog = bool(p, c.showLog, "fusion.ew.log", "log");
        c.logLines = intOr(p, c.logLines, "fusion.ew.logLines", "logLines");
        c.showTiming = bool(p, c.showTiming, "fusion.ew.timing", "timing");
        c.alwaysOnTop = bool(p, c.alwaysOnTop, "fusion.ew.alwaysOnTop", "alwaysOnTop");
        c.iconPath = str(p, c.iconPath, "fusion.ew.icon", "icon");
        c.closeOnFinish = bool(p, c.closeOnFinish, "fusion.ew.closeOnFinish", "closeOnFinish");
        c.closeDelayMs = intOr(p, c.closeDelayMs, "fusion.ew.closeDelayMs", "closeDelayMs");
        c.enabled = bool(p, c.enabled, "fusion.ew.enabled", "enabled");
        String style = str(p, c.progressStyle.name(), "fusion.ew.progressStyle", "progressStyle");
        try {
            c.progressStyle = ProgressStyle.valueOf(style.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            c.progressStyle = ProgressStyle.BAR;
        }
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith("custom.")) {
                c.custom.put(key.substring("custom.".length()), p.getProperty(key));
            } else if (key.startsWith("fusion.ew.custom.")) {
                c.custom.put(key.substring("fusion.ew.custom.".length()), p.getProperty(key));
            }
        }
    }

    private static String str(Properties p, String def, String sysKey, String fileKey) {
        String v = p.getProperty(sysKey);
        if (v == null) {
            v = p.getProperty(fileKey);
        }
        return v == null ? def : v;
    }

    private static int intOr(Properties p, int def, String sysKey, String fileKey) {
        String v = str(p, null, sysKey, fileKey);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean bool(Properties p, boolean def, String sysKey, String fileKey) {
        String v = str(p, null, sysKey, fileKey);
        if (v == null || v.isBlank()) {
            return def;
        }
        String t = v.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(t) || "1".equals(t) || "yes".equals(t) || "on".equals(t)) {
            return true;
        }
        if ("false".equals(t) || "0".equals(t) || "no".equals(t) || "off".equals(t)) {
            return false;
        }
        return def;
    }

    /** 解析 #RRGGBB / #AARRGGBB / 0xRRGGBB；解析失败时保留原值。 */
    private static int color(Properties p, int def, String sysKey, String fileKey) {
        String v = str(p, null, sysKey, fileKey);
        if (v == null || v.isBlank()) {
            return def;
        }
        String s = v.trim().replace("#", "").replace("0x", "").replace("0X", "");
        try {
            long raw = Long.parseLong(s, 16);
            if (s.length() <= 6) {
                return (int) (0xFF000000L | raw);
            }
            return (int) raw;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static String template() {
        return """
                # FusionML 早期加载窗口配置
                # 改完保存，下次启动生效。命令行 -Dfusion.ew.<键>=值 优先级更高。

                # 标题栏文字
                title=FusionML
                # 标题下方的小字
                subtitle=正在加载模组
                # 左上角大字标，想换成自己的名字就改这里
                logo=FusionML
                # 底部一行说明，可留空
                footer=

                width=520
                height=260

                # 颜色：#RRGGBB 或 #AARRGGBB
                background=#F0101018
                panel=#FF181826
                accent=#FF5AC8FA
                text=#FFF2F4FF
                dim=#FF9AA3C4

                # 进度样式：BAR（横条）/ RING（圆环）/ PERCENT（只有数字）
                progressStyle=BAR

                # 是否显示日志尾行与已用时/预计剩余
                log=true
                logLines=6
                timing=true

                # 是否置顶；加载完成后是否自动关闭
                alwaysOnTop=true
                closeOnFinish=true
                closeDelayMs=900

                # 自定义实现可以读取这里任意 custom.* 选项
                # custom.backgroundImage=assets/my-splash.png
                """;
    }
}
