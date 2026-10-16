package com.fusionml.crossload;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 跨端驱动用的反射工具。
 *
 * <p>跨端驱动<b>不能</b>在编译期引用另一端的任何类：编译这个加载器的人机器上不一定装着
 * NeoForge，而"为了编译一个不依赖对方的加载器、先去下载对方"是说不通的。
 * 所以另一端的一切都走反射，并且每一步都用"能不能找到类"来决定降级路径，
 * 而不是假设它一定在。
 *
 * <p>所有查找都显式带上目标的类加载器：宿主平台（Fabric 的 Knot / NeoForge 的
 * 变换类加载器）与系统类加载器是两套，用错一套会得到"类找不到"这种与事实相反的结论。
 */
final class Ref {

    private Ref() {
    }

    /** 用指定加载器加载类（找不到返回 null，不抛）。 */
    static Class<?> cls(ClassLoader loader, String name) {
        try {
            return Class.forName(name, false, loader);
        } catch (Throwable t) {
            return null;
        }
    }

    static Class<?> clsInit(ClassLoader loader, String name) {
        try {
            return Class.forName(name, true, loader);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 加载并初始化，失败时把原因带出来。
     *
     * <p>为什么不能简单返回 null：跨端模组的静态初始化里藏着它自己的依赖
     * （注册表、配置、别人的类）。一次失败如果是"类初始化异常"，
     * 真正有用的信息在 cause 链里，而不是在"类没找到"这个结论里 ——
     * 少写这一层，日志会把人指向完全错误的方向。
     */
    static Class<?> clsThrowing(ClassLoader loader, String name) {
        try {
            return Class.forName(name, true, loader);
        } catch (Throwable t) {
            throw new IllegalStateException(name + " 加载/初始化失败：" + describe(t), t);
        }
    }

    static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder(96);
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth < 4) {
            if (depth > 0) {
                sb.append(" ← ");
            }
            sb.append(cur.getClass().getSimpleName());
            if (cur.getMessage() != null) {
                sb.append("：").append(cur.getMessage());
            }
            cur = cur.getCause();
            depth++;
        }
        return sb.toString();
    }

    static Method method(Class<?> c, String name, Class<?>... params) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == params.length) {
                    if (accepts(m.getParameterTypes(), params)) {
                        m.setAccessible(true);
                        return m;
                    }
                }
            }
        }
        for (Method m : c.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == params.length) {
                if (accepts(m.getParameterTypes(), params)) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    private static boolean accepts(Class<?>[] formals, Class<?>[] actuals) {
        for (int i = 0; i < formals.length; i++) {
            Class<?> a = actuals[i];
            if (a == null) {
                continue;
            }
            if (!wrap(formals[i]).isAssignableFrom(wrap(a))) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> wrap(Class<?> c) {
        if (!c.isPrimitive()) {
            return c;
        }
        return switch (c.getName()) {
            case "int" -> Integer.class;
            case "long" -> Long.class;
            case "double" -> Double.class;
            case "float" -> Float.class;
            case "short" -> Short.class;
            case "byte" -> Byte.class;
            case "char" -> Character.class;
            case "boolean" -> Boolean.class;
            default -> c;
        };
    }

    /** 调用方法；失败抛出带上下文的 RuntimeException。 */
    static Object call(Object target, String name, Object... args) {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = args[i] == null ? null : args[i].getClass();
        }
        Method m = method(target.getClass(), name, types);
        if (m == null) {
            throw new IllegalStateException(target.getClass().getName() + " 没有可调用的 " + name);
        }
        try {
            return m.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static Object callTyped(Object target, String name, Class<?>[] types, Object... args) {
        Method m = method(target.getClass(), name, types);
        if (m == null) {
            throw new IllegalStateException(target.getClass().getName() + " 没有可调用的 " + name);
        }
        try {
            return m.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static Object callStatic(Class<?> owner, String name, Class<?>[] types, Object... args) {
        Method m = method(owner, name, types);
        if (m == null) {
            throw new IllegalStateException(owner.getName() + " 没有静态方法 " + name);
        }
        try {
            return m.invoke(null, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) {
            return r;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return new IllegalStateException(t.toString(), t);
    }

    static Object construct(Class<?> c, Object... args) throws Exception {
        Constructor<?> best = null;
        for (Constructor<?> ctor : c.getDeclaredConstructors()) {
            if (ctor.getParameterCount() != args.length) {
                continue;
            }
            Class<?>[] formals = ctor.getParameterTypes();
            boolean ok = true;
            for (int i = 0; i < formals.length; i++) {
                if (args[i] == null) {
                    if (formals[i].isPrimitive()) {
                        ok = false;
                        break;
                    }
                    continue;
                }
                if (!wrap(formals[i]).isAssignableFrom(wrap(args[i].getClass()))) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                best = ctor;
                break;
            }
        }
        if (best == null) {
            throw new NoSuchMethodException(c.getName() + " 没有匹配 " + args.length + " 个参数的构造器");
        }
        best.setAccessible(true);
        try {
            return best.newInstance(args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw rethrow(e.getCause());
        }
    }

    static Object get(Object target, String field) {
        try {
            Field f = field(target.getClass(), field);
            return f == null ? null : f.get(target);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static Field field(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {

            }
        }
        return null;
    }

    static Object staticField(Class<?> c, String name) {
        Field f = field(c, name);
        try {
            return f == null ? null : f.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    static Map<String, Object> staticFieldsOfType(Class<?> c, Class<?> type) {
        Map<String, Object> out = new LinkedHashMap<>(64);
        for (Field f : c.getFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || !type.isAssignableFrom(f.getType())) {
                continue;
            }
            try {
                Object v = f.get(null);
                if (v != null) {
                    out.put(f.getName(), v);
                }
            } catch (Throwable ignored) {

            }
        }
        return out;
    }

    static Object proxy(ClassLoader loader, Class<?> iface, InvocationHandler handler) {
        return Proxy.newProxyInstance(loader, new Class<?>[]{iface}, (p, m, a) -> {
            Object r = handler.invoke(p, m, a);
            return r != null ? r : emptyValue(m.getReturnType(), m.getName());
        });
    }

    static Object emptyValue(Class<?> type, String methodName) {
        if (type == Optional.class) {
            return Optional.empty();
        }
        if (type == List.class || type == java.util.Collection.class) {
            return new ArrayList<>(0);
        }
        if (type == Map.class) {
            return new LinkedHashMap<>(0);
        }
        if (type == java.util.Set.class) {
            return new java.util.LinkedHashSet<>(0);
        }
        if (type == boolean.class || type == Boolean.class) {
            return Boolean.FALSE;
        }
        if (type == int.class || type == Integer.class) {
            return 0;
        }
        if (type == long.class || type == Long.class) {
            return 0L;
        }
        if (type == String.class) {
            return "is".equals(methodName) ? "" : null;
        }
        return null;
    }

    /**
     * 把生成的字节定义成 {@code anchor} 所在加载器里的类。
     *
     * <p>为什么用 {@code MethodHandles.privateLookupIn}：{@code ClassLoader.defineClass} 是 protected 的，
     * 而我们想在<b>宿主平台那一侧的加载器</b>里定义这个类 —— 只有那样它才能看见
     * {@code net.neoforged.fml.ModContainer}（它由对方平台的加载器加载）。
     * 拿着目标类本身做 lookup，就一定落在"定义它的那个加载器 + 它的运行时包"里，
     * 于是生成的类名必须与目标类同包，这也正是 ModLauncher 这类工具的做法。
     */
    static Class<?> defineInto(Class<?> anchor, byte[] bytes) throws Throwable {
        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(anchor, MethodHandles.lookup());
        return lookup.defineClass(bytes);
    }
}
