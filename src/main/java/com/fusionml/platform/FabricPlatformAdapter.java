package com.fusionml.platform;

import com.fusionml.loader.ModClassIndex;
import com.fusionml.loader.ModClassLoader;
import com.fusionml.meta.ModFormat;
import com.fusionml.meta.ModMetadata;

import java.util.ArrayList;
import java.util.List;

/**
 * Fabric 端原生适配器。
 *
 * <p>Fabric 的模组契约：{@code fabric.mod.json} 里用 {@code entrypoints} 声明入口类，
 * 类本身不需要注解，只要实现 {@code ModInitializer}（或客户端/服务端的对应接口）。
 * 因此这里完全按"入口点列表 + 侧过滤"工作，不扫描注解。
 */
public final class FabricPlatformAdapter implements PlatformAdapter {

    /** Fabric 的初始器接口（存在时才调用，缺失则给出明确提示而不是抛 NoClassDefFoundError）。 */
    private static final String[] INITIALIZERS = {
            "net.fabricmc.api.ModInitializer",
            "net.fabricmc.api.ClientModInitializer",
            "net.fabricmc.api.DedicatedServerModInitializer"
    };

    /** 入口点分类 → 对应的初始化方法名。 */
    private static final String[][] ENTRYPOINT_KINDS = {
            {"main", "onInitialize"},
            {"client", "onInitializeClient"},
            {"server", "onInitializeServer"},
    };

    @Override
    public ModFormat format() {
        return ModFormat.FABRIC;
    }

    @Override
    public boolean expectsRegistration(ModMetadata mod) {
        // Fabric 模组靠元数据里的 entrypoints 声明入口；一个都没声明就是纯库模组
        // （fabric-language-kotlin、Cloth Config、Fabric API 的各个子模块都是这样）。
        return !mod.entrypoints.isEmpty();
    }

    @Override
    public List<String> locateMainClasses(ModMetadata mod, ModClassIndex.Result index,
                                          List<String> notes) {
        List<String> out = new ArrayList<>(2);
        List<String> main = mod.entrypoints.get("main");
        if (main != null) {
            for (String c : main) {
                if (!out.contains(c)) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    @Override
    public List<String> register(ModMetadata mod, List<String> mainClasses, ModClassLoader loader,
                                 boolean client, List<String> notes) {
        List<String> results = new ArrayList<>(2);
        for (String className : mainClasses) {
            Object instance = instantiate(className, loader, results);
            if (instance == null) {
                continue;
            }
            boolean called = false;
            for (int i = 0; i < INITIALIZERS.length; i++) {
                String ifaceName = INITIALIZERS[i];
                String methodName = ENTRYPOINT_KINDS[i][1];
                if (i == 1 && !client) {
                    continue;
                }
                if (i == 2 && client) {
                    continue;
                }
                try {
                    Class<?> iface = Class.forName(ifaceName, false, loader);
                    if (iface.isInstance(instance)) {
                        iface.getMethod(methodName).invoke(instance);
                        results.add(className + " → 已调用 " + ifaceName + "#" + methodName + "()");
                        called = true;
                    }
                } catch (ClassNotFoundException e) {
                    // 该接口不在当前运行环境里 —— 明确说明，而不是让玩家看到一坨堆栈。
                    // 这里刻意不带模组名：几百个模组会得到同一条消息，让上层去合并计数，
                    // 否则报告会被同一条提示刷屏，真正的问题反而被淹掉。
                    notes.add("运行环境未提供 " + ifaceName
                            + "，已跳过入口点调用（入口点类已正常实例化，说明类加载、依赖解析与三端适配都是通的）");
                } catch (ReflectiveOperationException e) {
                    results.add(className + " → 调用失败：" + rootCause(e));
                }
            }
            if (!called) {
                results.add(className + " → 已实例化；未匹配到可调用的 Fabric 初始化接口");
            }
        }
        return results;
    }

    private static Object instantiate(String className, ModClassLoader loader, List<String> results) {
        try {
            Class<?> c = Class.forName(className, true, loader);
            return c.getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException e) {
            results.add(className + " → 找不到类：请确认入口点类名与实际文件一致");
        } catch (NoClassDefFoundError e) {
            results.add(className + " → 依赖的类缺失（" + e.getMessage() + "）：通常是缺少前置模组或游戏本体的 API");
        } catch (ReflectiveOperationException e) {
            results.add(className + " → 实例化失败：" + rootCause(e));
        } catch (Throwable t) {
            results.add(className + " → 初始化异常：" + rootCause(t));
        }
        return null;
    }

    static String rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return cur.getClass().getSimpleName() + (msg == null ? "" : "：" + msg);
    }
}
