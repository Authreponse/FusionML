package com.fusionml.earlywindow;

/**
 * 早期窗口的扩展点（SPI）。
 *
 * <p>要换成自己的早期窗口，三条路任选其一：
 * <ol>
 *   <li>实现本接口，并在自己的 jar 里放
 *       {@code META-INF/services/com.fusionml.earlywindow.EarlyWindowProvider}
 *       写上实现类全名，加载器会自动发现（按 {@link #priority()} 排序）。</li>
 *   <li>只实现 {@link EarlyWindow}，用 {@code -Dfusion.earlywindow=类全名} 直接指定。</li>
 *   <li>编程方式：创建加载会话时传入一个现成的 {@code EarlyWindow} 实例。</li>
 * </ol>
 */
public interface EarlyWindowProvider {

    String id();

    default int priority() {
        return 500;
    }

    default boolean available() {
        return true;
    }

    EarlyWindow create(EarlyWindowConfig config);

    default String description() {
        return id();
    }
}
