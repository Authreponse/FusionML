package com.fusionml.crossload;

import com.fusionml.launch.CrossPlatformBridge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 对方平台（FML）的<b>全局状态</b>。
 *
 * <h2>为什么必须有</h2>
 *
 * <p>换用对方平台的补丁原版字节之后（见 {@link PatchedBase}），原版类里会带上
 * 对方平台的回调。举一个立刻就会撞上的例子：补丁后的
 * {@code net.minecraft.world.flag.FeatureFlags} 在静态初始化时会调用
 * {@code net.neoforged.neoforge.common.util.flag.FeatureFlagLoader#loadModdedFlags}，
 * 后者第一件事就是：
 *
 * <pre>
 * FMLLoader.getCurrent().getLoadingModList().getModFiles()
 * </pre>
 *
 * <p>而在宿主平台（Fabric）里没有人运行过 FML 的引导，{@code FMLLoader.getCurrent()}
 * 直接抛 {@code IllegalStateException: There is no current FML Loader} ——
 * 它出现在 {@code FeatureFlags.<clinit>} 里，会变成 {@code ExceptionInInitializerError}，
 * 之后这个类永久不可用。表现就是"注册物品时莫名其妙炸了"。
 *
 * <h2>做法：只把它真正需要的那部分状态摆好</h2>
 *
 * <p>这里刻意<b>不</b>调用 FML 自己的引导（{@code FMLLoader.create}）：那一步会建立
 * 它自己的模块层与变换类加载器，和宿主平台的类加载语义正面冲突，模组类会被第二份
 * 加载器再定义一次，跨端 Mixin 也就失效了。我们只做对方平台这些回调真正依赖的最小集合：
 *
 * <ol>
 *   <li>{@code FMLLoader.current} —— 一个真实构造出来的 {@code FMLLoader} 实例
 *       （构造器只填字段，不做发现与加载）；</li>
 *   <li>它的 {@code loadingModList} —— 用对方平台自己的 {@code LoadingModList.of(...)}
 *       建出来（先为空表：本进程里"FML 看得见的模组"由我们驱动的那几个组成，
 *       这一步只是让"取列表"这个动作不炸）；</li>
 *   <li>{@code ModList.INSTANCE} —— 同样用对方平台的 {@code ModList.of(...)} 建，
 *       让 {@code ModList.get()} 不是 {@code null}。</li>
 * </ol>
 *
 * <p>这些状态都是"用对方平台的官方 API 建的真实对象"，不是我们仿的一套近似物，
 * 所以依赖它们的模组拿到的是真的 FML 对象。
 */
public final class FmlEnvironment {

    public static final String PROP_MODE = "fusion.fmlState";

    public static final String PROP_DETAIL = "fusion.fmlState.detail";

    private static volatile boolean up;
    private static volatile String detail = "未建立";

    private FmlEnvironment() {
    }

    public static boolean up() {
        return up;
    }

    public static String detail() {
        return detail;
    }

    private static volatile Object loader;

    /**
     * 摆好 FML 的最小全局状态。可以重复调用，后到的调用直接返回。
     *
     * @param host 宿主平台的类加载器（对方平台的类要在它里面）
     */
    public static boolean standUp(ClassLoader host, Consumer<String> log) {
        if (up) {
            return true;
        }
        String mode = System.getProperty(PROP_MODE, "auto").trim();
        if ("off".equalsIgnoreCase(mode) || "false".equalsIgnoreCase(mode)) {
            detail = "已按 " + PROP_MODE + "=off 关闭";
            System.setProperty(PROP_DETAIL, detail);
            return false;
        }
        try {
            Class<?> fmlLoader = Class.forName("net.neoforged.fml.loading.FMLLoader",
                    true, host);
            Object existing = fmlLoader.getMethod("getCurrentOrNull").invoke(null);
            if (existing != null) {
                up = true;
                loader = existing;
                detail = "由对方平台自己建立（无需我们补）";
                System.setProperty(PROP_DETAIL, detail);
                return true;
            }
            List<String> done = new ArrayList<>(4);

            //    真正的发现/加载在 create(...) 里，那一步我们必须避开（会和宿主平台抢类加载）。
            Class<?> distClass = Class.forName("net.neoforged.api.distmarker.Dist", true, host);
            Object dist = enumConstant(distClass,
                    Boolean.parseBoolean(System.getProperty("fusion.client", "true"))
                            ? "CLIENT" : "DEDICATED_SERVER");
            Path gameDir = Path.of(System.getProperty("fusion.gameDir", "."));
            String[] programArgs = {
                    "--fml.neoForgeVersion=" + neoForgeVersion(),
                    "--fml.mcVersion=" + mcVersion(),
                    "--fml.neoFormVersion=" + mcVersion(),
            };
            Constructor<?> ctor = fmlLoader.getDeclaredConstructor(ClassLoader.class,
                    String[].class, distClass, boolean.class, Path.class);
            ctor.setAccessible(true);
            Object instance = ctor.newInstance(host, programArgs, dist, true, gameDir);
            done.add("FMLLoader 实例");

            Field current = fmlLoader.getDeclaredField("current");
            current.setAccessible(true);
            Object ref = current.get(null);
            if (ref instanceof AtomicReference<?>) {

                @SuppressWarnings("unchecked")
                AtomicReference<Object> ar = (AtomicReference<Object>) ref;
                ar.set(instance);
                done.add("FMLLoader.current");
            }

            Class<?> loadingModList = Class.forName("net.neoforged.fml.loading.LoadingModList",
                    true, host);
            Object empty = loadingModList.getMethod("of", List.class, List.class, List.class,
                            List.class, List.class, Map.class)
                    .invoke(null, List.of(), List.of(), List.of(), List.of(), List.of(),
                            Map.of());
            Field field = fmlLoader.getDeclaredField("loadingModList");
            field.setAccessible(true);
            field.set(instance, empty);
            done.add("LoadingModList(空表)");
            // 4) ModList.get() 不能是 null —— 否则对方模组读"某模组装没装"就直接 NPE。
            try {
                Class<?> modList = Class.forName("net.neoforged.fml.ModList", true, host);
                Object ml = modList.getMethod("of", List.class, List.class)
                        .invoke(null, List.of(), List.of());
                Field inst = modList.getDeclaredField("INSTANCE");
                inst.setAccessible(true);
                inst.set(null, ml);
                done.add("ModList（空表）");
            } catch (Throwable t) {
                done.add("ModList 未建立（" + t.getClass().getSimpleName() + "）");
            }
            loader = instance;
            up = true;
            detail = String.join("、", done);
            System.setProperty(PROP_DETAIL, detail);
            log.accept("已建立对方平台的全局状态：" + detail);
            return true;
        } catch (Throwable t) {
            // 建立不起来不是致命错误：只是"依赖 FML 全局状态的模组会失败"，
            // 如实报出来比让它在半路抛 NoClassDefFoundError 好得多。
            detail = "建立失败：" + t;
            System.setProperty(PROP_DETAIL, detail);
            log.accept("对方平台的全局状态建立失败（相关跨端模组会失败）：" + t);
            return false;
        }
    }

    public static Object currentLoader() {
        return loader;
    }

    private static Object enumConstant(Class<?> type, String name) {
        for (Object o : type.getEnumConstants()) {
            if (name.equals(String.valueOf(o))) {
                return o;
            }
        }
        Object[] all = type.getEnumConstants();
        return all.length > 0 ? all[0] : null;
    }

    /**
     * 对方平台的版本号：从借来的运行时 jar 名（{@code neoforge-<版本>-universal.jar}）
     * 或补丁原版 jar 名里取；取不到就给一个明显是占位的值，而不是编一个看起来像真的。
     */
    private static String neoForgeVersion() {
        for (Path p : CrossPlatformBridge.foreignRuntime()) {
            String n = p.getFileName().toString();
            if (n.startsWith("neoforge-") && n.endsWith("-universal.jar")) {
                return n.substring("neoforge-".length(), n.length() - "-universal.jar".length());
            }
        }
        String base = System.getProperty(PatchedBase.PROP_RESOLVED, "");
        if (!base.isBlank()) {
            String n = Path.of(base).getFileName().toString();
            String prefix = "minecraft-client-patched-";
            if (n.startsWith(prefix) && n.endsWith(".jar")) {
                return n.substring(prefix.length(), n.length() - ".jar".length());
            }
        }
        return "0.0.0";
    }

    private static String mcVersion() {
        return PatchedBase.mcKeyOf(System.getProperty("fusion.version", ""));
    }
}
