package com.fusionml.earlywindow;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * 早期窗口的选择与创建。
 *
 * <p>选择顺序：
 * <ol>
 *   <li>配置里显式指定（{@code -Dfusion.earlywindow=id或类全名}）——玩家说了算；</li>
 *   <li>{@link ServiceLoader} 发现的第三方实现，按 {@link EarlyWindowProvider#priority()} 升序，
 *       取第一个 {@code available()} 的；</li>
 *   <li>内置实现（图形版 → 控制台版）。</li>
 * </ol>
 *
 * <p>任何一步失败都不会中断加载：早期窗口是"锦上添花"，不该成为"启动不了"的原因。
 */
public final class EarlyWindows {

    public static final String CHOICE_PROPERTY = "fusion.earlywindow";

    private EarlyWindows() {
    }

    /** 空实现：明确关闭早期窗口时使用，避免上层到处写 null 判断。 */
    private static final EarlyWindow NOOP = new EarlyWindow() {
        @Override
        public String id() {
            return "off";
        }

        @Override
        public void open() {
        }

        @Override
        public void stage(String stage, double fraction) {
        }

        @Override
        public void progress(double fraction, String detail) {
        }

        @Override
        public void log(String line) {
        }

        @Override
        public void finish(String summary) {
        }

        @Override
        public boolean headless() {
            return true;
        }

        @Override
        public void close() {
        }
    };

    public static EarlyWindow none() {
        return NOOP;
    }

    public static List<EarlyWindowProvider> providers() {
        Set<String> seen = new LinkedHashSet<>();
        List<EarlyWindowProvider> out = new ArrayList<>();

        for (EarlyWindowProvider p : ServiceLoader.load(EarlyWindowProvider.class)) {
            if (seen.add(p.id())) {
                out.add(p);
            }
        }
        for (EarlyWindowProvider p : List.of(new SwingEarlyWindowProvider(),
                new ConsoleEarlyWindowProvider())) {
            if (seen.add(p.id())) {
                out.add(p);
            }
        }
        out.sort(Comparator.comparingInt(EarlyWindowProvider::priority));
        return out;
    }

    /**
     * 创建早期窗口。
     *
     * @param explicit 显式指定：可以是实现 id，也可以是类全名；null/空表示自动选择
     */
    public static EarlyWindow create(EarlyWindowConfig config, String explicit) {
        if (!config.enabled) {
            return NOOP;
        }
        String choice = explicit;
        if (choice == null || choice.isBlank()) {
            choice = System.getProperty(CHOICE_PROPERTY, "").trim();
        }
        if ("off".equalsIgnoreCase(choice) || "none".equalsIgnoreCase(choice)) {
            return NOOP;
        }
        if (!choice.isBlank()) {
            EarlyWindow direct = byName(config, choice);
            if (direct != null) {
                return direct;
            }
            System.out.println("指定的早期窗口实现不可用：" + choice + "，将回退到自动选择。"
                    + "（可用：" + ids() + "）");
        }
        for (EarlyWindowProvider p : providers()) {
            try {
                if (p.available()) {
                    EarlyWindow w = p.create(config);
                    if (w != null) {
                        return w;
                    }
                }
            } catch (Throwable t) {
                System.out.println("早期窗口实现 " + p.id() + " 创建失败，已跳过：" + t);
            }
        }
        return NOOP;
    }

    private static EarlyWindow byName(EarlyWindowConfig config, String choice) {
        for (EarlyWindowProvider p : providers()) {
            if (p.id().equalsIgnoreCase(choice)) {
                try {
                    if (p.available()) {
                        return p.create(config);
                    }
                } catch (Throwable t) {
                    System.out.println("早期窗口实现 " + p.id() + " 创建失败：" + t);
                }
                return null;
            }
        }

        try {
            Class<?> c = Class.forName(choice, true, EarlyWindows.class.getClassLoader());
            if (EarlyWindowProvider.class.isAssignableFrom(c)) {
                EarlyWindowProvider p = (EarlyWindowProvider) c.getDeclaredConstructor().newInstance();
                return p.create(config);
            }
            if (EarlyWindow.class.isAssignableFrom(c)) {
                for (Constructor<?> ctor : c.getDeclaredConstructors()) {
                    if (ctor.getParameterCount() == 1
                            && ctor.getParameterTypes()[0] == EarlyWindowConfig.class) {
                        ctor.setAccessible(true);
                        return (EarlyWindow) ctor.newInstance(config);
                    }
                }
                Constructor<?> ctor = c.getDeclaredConstructor();
                ctor.setAccessible(true);
                return (EarlyWindow) ctor.newInstance();
            }
            System.out.println("类 " + choice + " 既不是 EarlyWindow 也不是 EarlyWindowProvider");
        } catch (Throwable t) {

        }
        return null;
    }

    private static String ids() {
        StringBuilder sb = new StringBuilder();
        for (EarlyWindowProvider p : providers()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(p.id()).append(p.available() ? "" : "（当前环境不可用）");
        }
        return sb.toString();
    }

    public static String describeProviders() {
        StringBuilder sb = new StringBuilder();
        sb.append("可用早期窗口实现（按优先级）：").append(System.lineSeparator());
        for (EarlyWindowProvider p : providers()) {
            sb.append(String.format(Locale.ROOT, "  %-10s 优先级 %-5d %s%s%n",
                    p.id(), p.priority(), p.description(), p.available() ? "" : "  [当前环境不可用]"));
        }
        sb.append("自定义：实现 EarlyWindow 或 EarlyWindowProvider，")
                .append("用 -D").append(CHOICE_PROPERTY).append("=id或类全名 指定，")
                .append("或放入 META-INF/services 让加载器自动发现。");
        return sb.toString();
    }
}
