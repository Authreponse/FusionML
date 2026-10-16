package com.fusionml.platform;

import com.fusionml.loader.ModClassIndex;
import com.fusionml.loader.ModClassLoader;
import com.fusionml.meta.ModFormat;
import com.fusionml.meta.ModMetadata;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

/**
 * Forge 端原生适配器。
 *
 * <p>Forge 的模组契约：主类由 {@code @Mod} 注解标记，注解值就是 modId；
 * FML 通过<b>构造器注入</b>把事件总线交给模组（{@code (IEventBus)} 或 {@code (IEventBus, ModContainer)}）。
 * 本适配器按这套规则工作：先找带参构造器，找不到再退到无参构造器。
 * 主类定位走注解扫描结果（见 {@link com.fusionml.loader.ModClassIndex}）。
 */
public class ForgePlatformAdapter implements PlatformAdapter {

    /** FML 注入的构造器参数类型候选（按 Forge 的真实注入顺序）。 */
    private static final String[][] INJECTION_TYPES = {
            {"net.minecraftforge.eventbus.api.IEventBus", "net.minecraftforge.fml.ModContainer"},
            {"net.minecraftforge.eventbus.api.IEventBus"},
    };

    @Override
    public ModFormat format() {
        return ModFormat.FORGE;
    }

    @Override
    public List<String> locateMainClasses(ModMetadata mod, ModClassIndex.Result index,
                                          List<String> notes) {
        return locate(mod, index, ModFormat.FORGE, notes);
    }

    /** Forge 与 NeoForge 的定位规则同形（都扫注解），但注解描述符不同，所以分开调用。 */
    static List<String> locate(ModMetadata mod, ModClassIndex.Result index, ModFormat format,
                               List<String> notes) {
        List<ModClassIndex.Loc> locs = index.modClasses().get(format.name());
        if (locs == null || locs.isEmpty()) {
            notes.add(mod.jarName() + "：没有扫描到带 " + format.displayName()
                    + " @Mod 注解的主类（若确认是模组包，请检查是否被重复打包覆盖）");
            return List.of();
        }
        List<String> out = new ArrayList<>(locs.size());
        for (ModClassIndex.Loc loc : locs) {
            if (loc.jarIndex() != mod.jarIndex()) {
                continue;
            }
            String internal = loc.entryName().substring(0, loc.entryName().length() - ".class".length());
            out.add(ModClassIndex.dotted(internal));
        }
        return out;
    }

    @Override
    public List<String> register(ModMetadata mod, List<String> mainClasses, ModClassLoader loader,
                                 boolean client, List<String> notes) {
        List<String> results = new ArrayList<>(2);
        for (String className : mainClasses) {
            try {
                Class<?> c = Class.forName(className, true, loader);
                Constructor<?> chosen = pickConstructor(c);
                Object instance;
                if (chosen.getParameterCount() == 0) {
                    instance = chosen.newInstance();
                    results.add(className + " → 已用无参构造器实例化（未匹配到事件总线注入构造器）");
                } else {
                    instance = chosen.newInstance(injectionArgs(chosen, loader, notes, mod));
                    results.add(className + " → 已按 " + chosen.getParameterCount()
                            + " 参构造器注入事件总线并实例化");
                }
                if (instance == null) {
                    results.add(className + " → 实例化返回 null");
                }
            } catch (ClassNotFoundException e) {
                results.add(className + " → 找不到主类");
            } catch (NoClassDefFoundError e) {
                results.add(className + " → 缺少运行期类型（" + e.getMessage() + "）");
            } catch (ReflectiveOperationException | RuntimeException e) {
                results.add(className + " → 注册失败：" + FabricPlatformAdapter.rootCause(e));
            } catch (Throwable t) {
                results.add(className + " → 初始化异常：" + FabricPlatformAdapter.rootCause(t));
            }
        }
        return results;
    }

    /** 返回注入参数所需的类型序列（供子类/NeoForge 覆写类型名）。 */
    String[][] injectionTypes() {
        return INJECTION_TYPES;
    }

    private Constructor<?> pickConstructor(Class<?> c) {
        Constructor<?>[] ctors = c.getDeclaredConstructors();
        for (String[] types : injectionTypes()) {
            for (Constructor<?> ctor : ctors) {
                if (ctor.getParameterCount() != types.length) {
                    continue;
                }
                Class<?>[] params = ctor.getParameterTypes();
                boolean ok = true;
                for (int i = 0; i < params.length; i++) {
                    if (!params[i].getName().equals(types[i])
                            && !implementsName(params[i], types[i])) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    ctor.setAccessible(true);
                    return ctor;
                }
            }
        }
        for (Constructor<?> ctor : ctors) {
            if (ctor.getParameterCount() == 0) {
                ctor.setAccessible(true);
                return ctor;
            }
        }
        // 兜底：返回参数最少的一个，让错误信息更具体。
        Constructor<?> best = ctors[0];
        for (Constructor<?> ctor : ctors) {
            if (ctor.getParameterCount() < best.getParameterCount()) {
                best = ctor;
            }
        }
        best.setAccessible(true);
        return best;
    }

    private static boolean implementsName(Class<?> c, String targetName) {
        for (Class<?> i : c.getInterfaces()) {
            if (i.getName().equals(targetName)) {
                return true;
            }
        }
        return false;
    }

    private static Object[] injectionArgs(Constructor<?> ctor, ModClassLoader loader,
                                          List<String> notes, ModMetadata mod) {
        Class<?>[] params = ctor.getParameterTypes();
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            args[i] = BusHolder.get(params[i]);
            if (args[i] == null) {
                notes.add(mod.jarName() + "：运行环境未提供 " + params[i].getName()
                        + " 的实现，已用空值占位（类结构已确认正确）");
            }
        }
        return args;
    }

    /**
     * 事件总线持有者。真实部署时由平台层把 MCP/官方总线实例注册进来；
     * 在独立运行（没有游戏）时保持为空，避免用假实现掩盖问题。
     */
    public static final class BusHolder {
        private static final java.util.Map<String, Object> INSTANCES = new java.util.concurrent.ConcurrentHashMap<>();

        private BusHolder() {
        }

        public static void register(Class<?> type, Object instance) {
            INSTANCES.put(type.getName(), instance);
        }

        static Object get(Class<?> type) {
            return INSTANCES.get(type.getName());
        }
    }
}
