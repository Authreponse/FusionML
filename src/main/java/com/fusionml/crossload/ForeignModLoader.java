package com.fusionml.crossload;

import com.fusionml.bytecode.ByteOut;
import com.fusionml.bytecode.ClassEmitter;
import com.fusionml.util.Toml;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 非宿主平台模组的入口点驱动。
 *
 * <p>宿主平台只认自己格式的模组：一个 Fabric 进程永远不会去读
 * {@code neoforge.mods.toml}，也不会去实例化带 {@code @Mod} 的类。
 * 于是整合包里那一端的模组就成了"文件在、但没人理"的摆设。
 * 这个类补的就是这一环，而且补得很克制：
 *
 * <ul>
 *   <li><b>用的是那一端自己的类。</b>事件总线、{@code @Mod}、{@code DeferredRegister}、
 *       {@code RegisterEvent}、生命周期事件全部取自对方平台的官方 jar
 *       （由 {@link ForeignRuntime} 从本机同版本实例里借来），
 *       所以被驱动起来的是真正的 NeoForge/Forge 语义，而不是我们仿的一套近似物。</li>
 *   <li><b>驱动逻辑是我们自己的。</b>对方平台的 {@code FMLModContainer} 与 ModLauncher 的
 *       模块层是绑死的：它用 {@code Class.forName(module, name)} 加载入口类，
 *       这会让模组类在<b>第二个类加载器</b>里被再定义一遍 —— 而跨端 Mixin 能生效的前提
 *       恰恰是"模组类与游戏类活在同一个加载器里"。两条路不能同时走，
 *       所以这里不复用它的容器，只借用它的 API。</li>
 *   <li><b>失败只降级，不连坐。</b>任何一步出错都只影响这一个模组，
 *       宿主平台自己的启动流程不受影响。</li>
 * </ul>
 */
public final class ForeignModLoader {

    /** 某一端的符号表：加一端只需要再加一组名字，驱动逻辑是同一份。 */
    private record Platform(String id, String display, String modAnnoDesc, String modAnnoClass,
                            String subscriberDesc, String modContainer, String iEventBus,
                            String busBuilder, String modLoadingContext, String iModInfo,
                            String modFileInfo, String modFile, String registerEvent,
                            String deferredWorkQueue, String lifecyclePackage, String gameData) {

        static Platform of(String id) {
            if ("forge".equals(id)) {
                return new Platform("forge", "Forge",
                        "Lnet/minecraftforge/fml/common/Mod;",
                        "net.minecraftforge.fml.common.Mod",
                        "Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber;",
                        "net.minecraftforge.fml.ModContainer",
                        "net.minecraftforge.eventbus.api.IEventBus",
                        "net.minecraftforge.eventbus.api.BusBuilder",
                        "net.minecraftforge.fml.ModLoadingContext",
                        "net.minecraftforge.forgespi.language.IModInfo",
                        "net.minecraftforge.forgespi.language.IModFileInfo",
                        "net.minecraftforge.forgespi.locating.IModFile",
                        "net.minecraftforge.registries.RegisterEvent",
                        "net.minecraftforge.fml.DeferredWorkQueue",
                        "net.minecraftforge.fml.event.lifecycle.",
                        "net.minecraftforge.registries.GameData");
            }
            return new Platform("neoforge", "NeoForge",
                    "Lnet/neoforged/fml/common/Mod;",
                    "net.neoforged.fml.common.Mod",
                    "Lnet/neoforged/fml/common/EventBusSubscriber;",
                    "net.neoforged.fml.ModContainer",
                    "net.neoforged.bus.api.IEventBus",
                    "net.neoforged.bus.api.BusBuilder",
                    "net.neoforged.fml.ModLoadingContext",
                    "net.neoforged.neoforgespi.language.IModInfo",
                    "net.neoforged.neoforgespi.language.IModFileInfo",
                    "net.neoforged.neoforgespi.locating.IModFile",
                    "net.neoforged.neoforge.registries.RegisterEvent",
                    "net.neoforged.fml.DeferredWorkQueue",
                    "net.neoforged.fml.event.lifecycle.",
                    "net.neoforged.neoforge.registries.GameData");
        }
    }

    /** 一个被驱动起来的跨端模组。 */
    public record Loaded(String modId, String name, String version, String entryClass,
                         Object container, Object bus, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    /** 驱动结果。 */
    public record Result(String platform, List<Loaded> mods, List<String> notes,
                         boolean runtimeUsable) {
        public long okCount() {
            return mods.stream().filter(Loaded::ok).count();
        }

        public String summary() {
            if (!runtimeUsable) {
                return "跨端 " + platform + "：运行时不可用，" + mods.size() + " 个模组未驱动";
            }
            return "跨端 " + platform + "：已驱动 " + okCount() + "/" + mods.size() + " 个模组";
        }
    }

    /** 已定义的容器类按加载器缓存：同一个加载器只能定义一次同名类。 */
    private static final Map<ClassLoader, Class<?>> CONTAINER_CLASS = new IdentityHashMap<>(2);
    /** 已经被驱动起来的模组容器。 */
    private static final List<Object> CONTAINERS = new ArrayList<>(16);
    /** 已经发过注册事件的注册表（按对象身份去重）。 */
    private static final Set<Object> FIRED = Collections.newSetFromMap(new IdentityHashMap<>(64));
    private static final List<String> NOTES = new ArrayList<>(8);

    private static String platformId = "neoforge";
    private static boolean installed;
    private static boolean registerFired;

    private ForeignModLoader() {
    }

    /** 装载跨端模组。由宿主平台"原版注册表填充之前"这个时刻调用。 */
    public static Result install(ClassLoader host, List<Path> jars, String platform,
                                 boolean client, Consumer<String> log) {
        Platform p = Platform.of(platform);
        platformId = p.id();
        // 先摆好对方平台的全局状态：补丁原版类（FeatureFlags 这类）会回调 FML，
        // 没有这一步，驱动到注册表事件时会以 ExceptionInInitializerError 收场，
        // 报错点离真正的原因很远。见 FmlEnvironment 的长注释。
        FmlEnvironment.standUp(host, line -> log.accept("[跨端] " + line));
        Class<?> modAnnoClass = Ref.cls(host, p.modAnnoClass());
        Class<?> containerClass = Ref.cls(host, p.modContainer());
        Class<?> busIface = Ref.cls(host, p.iEventBus());
        if (modAnnoClass == null || containerClass == null || busIface == null) {
            String why = "本机没有可用的 " + p.display() + " 运行时（缺 "
                    + (modAnnoClass == null ? p.modAnnoClass() + " " : "")
                    + (containerClass == null ? p.modContainer() + " " : "")
                    + (busIface == null ? p.iEventBus() : "") + "）";
            NOTES.add(why);
            log.accept("[跨端] " + why);
            return new Result(p.id(), List.of(), NOTES, false);
        }
        List<Loaded> loaded = new ArrayList<>(jars.size());
        try {
            Class<?> generated = CONTAINER_CLASS.get(host);
            if (generated == null) {
                generated = Ref.defineInto(containerClass, containerBytes(p));
                CONTAINER_CLASS.put(host, generated);
                log.accept("[跨端] 已在本进程生成 " + p.display() + " 的容器适配类："
                        + generated.getName() + "（自研字节码生成器，无 ASM）");
            }
            Class<?> iModInfo = Ref.cls(host, p.iModInfo());
            Class<?> modFileInfo = Ref.cls(host, p.modFileInfo());
            Class<?> modFile = Ref.cls(host, p.modFile());
            for (Path jar : jars) {
                try {
                    loaded.addAll(loadJar(host, jar, p, generated, iModInfo, modFileInfo, modFile,
                            client, log));
                } catch (Throwable t) {
                    NOTES.add(jar.getFileName() + " 装载失败：" + t);
                    log.accept("[跨端] " + jar.getFileName() + " 装载失败：" + t);
                }
            }
            installed = true;
        } catch (Throwable t) {
            NOTES.add("跨端驱动初始化失败：" + t);
            log.accept("[跨端] 驱动初始化失败：" + t);
        }
        return new Result(p.id(), loaded, NOTES, true);
    }

    /** 一个包里可能声明多个模组（NeoForge 允许一个 jar 带多个 [[mods]]）。 */
    private static List<Loaded> loadJar(ClassLoader host, Path jar, Platform p, Class<?> containerClass,
                                        Class<?> iModInfo, Class<?> modFileInfo, Class<?> modFile,
                                        boolean client, Consumer<String> log) {
        List<Loaded> out = new ArrayList<>(2);
        Map<String, List<ClassAnn.Ann>> anns = ClassAnn.scanJar(jar, null);
        // 先按注解把入口点归类：注解的 value 就是 modId（两端都是这个约定）。
        Map<String, List<String>> entryByMod = new LinkedHashMap<>();
        for (Map.Entry<String, List<ClassAnn.Ann>> e : anns.entrySet()) {
            for (ClassAnn.Ann a : e.getValue()) {
                if (!a.desc().equals(p.modAnnoDesc()) || !"TYPE".equals(a.elementType())) {
                    continue;
                }
                String modId = a.str("value");
                if (modId == null || modId.isBlank()) {
                    continue;
                }
                entryByMod.computeIfAbsent(modId, k -> new ArrayList<>(1)).add(a.className());
            }
        }
        for (Map<String, Object> mod : readModTables(jar)) {
            String modId = Toml.str(mod, "modId", "");
            if (modId.isBlank()) {
                continue;
            }
            String name = Toml.str(mod, "displayName", modId);
            String version = Toml.str(mod, "version", "?");
            List<String> entries = entryByMod.getOrDefault(modId, List.of());
            if (entries.isEmpty()) {
                out.add(new Loaded(modId, name, version, "?", null, null,
                        "包里没有找到带 " + p.modAnnoClass() + "(\"" + modId + "\") 的入口类"));
                continue;
            }
            List<String> sorted = new ArrayList<>(entries);
            Collections.sort(sorted);
            for (String entryClass : sorted) {
                out.add(createOne(host, jar, p, containerClass, iModInfo, modFileInfo, modFile,
                        modId, name, version, entryClass, client, log));
            }
        }
        return out;
    }

    private static Loaded createOne(ClassLoader host, Path jar, Platform p, Class<?> containerClass,
                                    Class<?> iModInfo, Class<?> modFileInfo, Class<?> modFile,
                                    String modId, String name, String version, String entryClass,
                                    boolean client, Consumer<String> log) {
        try {
            Object bus = newBus(p, host);
            Object info = buildModInfo(host, iModInfo, modFileInfo, modFile, modId, name, version, jar);
            Object container = Ref.construct(containerClass, info, bus);
            activateContainer(host, p, container);
            Class<?> entry = Ref.clsThrowing(host, entryClass);
            instantiate(entry, bus, container);
            int subscribers = registerSubscribers(host, jar, p, modId, client, bus, log);
            CONTAINERS.add(container);
            log.accept("[跨端] " + p.display() + " 模组 " + modId + " " + version + "（" + name
                    + "）入口点已构造：" + entryClass
                    + (subscribers > 0 ? "，静态订阅 " + subscribers + " 个" : ""));
            return new Loaded(modId, name, version, entryClass, container, bus, null);
        } catch (Throwable t) {
            String msg = Ref.describe(t);
            log.accept("[跨端] " + p.display() + " 模组 " + modId + " 构造失败：" + msg);
            if (Boolean.getBoolean("fusion.crossload.trace")) {
                t.printStackTrace();
            }
            return new Loaded(modId, name, version, entryClass, null, null, msg);
        }
    }

    /**
     * 入口类构造器形态：对方平台支持的几种我们都试一遍。
     * {@code (IEventBus, ModContainer)} / {@code (IEventBus)} / {@code (ModContainer)} / {@code ()}
     */
    private static void instantiate(Class<?> entry, Object bus, Object container) throws Exception {
        Object[][] shapes = {{bus, container}, {bus}, {container}, {}};
        Throwable last = null;
        for (Object[] shape : shapes) {
            try {
                Ref.construct(entry, shape);
                return;
            } catch (NoSuchMethodException e) {
                last = e;
            }
        }
        throw new NoSuchMethodException(entry.getName() + " 没有受支持的构造器形态（"
                + (last == null ? "" : last.getMessage()) + "）");
    }

    /** 建一个对方平台自己的事件总线（模组总线）。 */
    private static Object newBus(Platform p, ClassLoader host) throws Exception {
        Class<?> builder = Ref.cls(host, p.busBuilder());
        if (builder == null) {
            throw new ClassNotFoundException(p.busBuilder());
        }
        Object b = Ref.callStatic(builder, "builder", new Class<?>[]{});
        return Ref.callTyped(b, "build", new Class<?>[]{});
    }

    private static void activateContainer(ClassLoader host, Platform p, Object container) {
        Class<?> ctx = Ref.cls(host, p.modLoadingContext());
        if (ctx == null) {
            return;
        }
        try {
            Object c = Ref.callStatic(ctx, "get", new Class<?>[]{});
            Ref.callTyped(c, "setActiveContainer", new Class<?>[]{Ref.cls(host, p.modContainer())},
                    container);
        } catch (Throwable ignored) {
            // 拿不到活动容器不影响构造：只是模组自己去问"我在哪个模组里"时会拿到空。
        }
    }

    /**
     * 造一个 IModInfo。
     *
     * <p>它是接口，所以用动态代理实现 —— 比"生成一个实现类"更省，也不多占名字。
     * 模组真会读到的字段（modId、显示名、版本、所在文件）给真值；
     * 依赖表、特性标记、URL、配置这些是对方平台加载器的产物，给空值比编一个更像样。
     */
    private static Object buildModInfo(ClassLoader host, Class<?> iModInfo, Class<?> modFileInfo,
                                       Class<?> modFile, String modId, String name, String version,
                                       Path jar) {
        if (iModInfo == null) {
            return null;
        }
        Object file = modFile == null ? null
                : Ref.proxy(host, modFile, (proxy, m, args) -> switch (m.getName()) {
                    case "getId", "toString" ->
                            "fusionml.foreign." + sanitize(jar.getFileName().toString());
                    case "getFileName" -> jar.getFileName().toString();
                    case "getFilePath" -> jar;
                    case "getModInfos" -> List.of();
                    default -> null;
                });
        Object fileInfo = modFileInfo == null ? null
                : Ref.proxy(host, modFileInfo, (proxy, m, args) -> switch (m.getName()) {
                    case "getFile" -> file;
                    case "getId", "toString" -> sanitize(jar.getFileName().toString());
                    case "getMods" -> List.of();
                    default -> null;
                });
        Object artifact = artifactVersion(host, version);
        return Ref.proxy(host, iModInfo, (proxy, m, args) -> switch (m.getName()) {
            case "getModId", "toString" -> modId;
            case "getDisplayName" -> name == null ? modId : name;
            case "getNamespace" -> modId;
            case "getDescription" -> "由 FusionML 跨端驱动加载的 " + modId;
            case "getOwningFile" -> fileInfo;
            case "getVersion" -> artifact;
            case "getLogoBlur" -> Boolean.TRUE;
            case "getModProperties" -> new LinkedHashMap<String, Object>(0);
            case "getDependencies", "getForgeFeatures" -> List.of();
            default -> null;
        });
    }

    private static Object artifactVersion(ClassLoader host, String v) {
        Class<?> c = Ref.cls(host, "org.apache.maven.artifact.versioning.DefaultArtifactVersion");
        if (c != null) {
            try {
                return Ref.construct(c, v);
            } catch (Throwable ignored) {
                // 退回到下面的最小实现
            }
        }
        Class<?> iface = Ref.cls(host, "org.apache.maven.artifact.versioning.ArtifactVersion");
        if (iface == null) {
            return null;
        }
        return Ref.proxy(host, iface, (proxy, m, args) -> switch (m.getName()) {
            case "toString", "getVersionString" -> v;
            default -> null;
        });
    }

    /** 把 {@code @EventBusSubscriber} 标注的类挂到这条总线上（对方平台的语义）。 */
    private static int registerSubscribers(ClassLoader host, Path jar, Platform p, String modId,
                                           boolean client, Object bus, Consumer<String> log) {
        int n = 0;
        try {
            Map<String, List<ClassAnn.Ann>> anns = ClassAnn.scanJar(jar, null);
            for (Map.Entry<String, List<ClassAnn.Ann>> e : anns.entrySet()) {
                for (ClassAnn.Ann a : e.getValue()) {
                    if (!a.desc().equals(p.subscriberDesc()) || !"TYPE".equals(a.elementType())) {
                        continue;
                    }
                    String owner = a.str("modid");
                    if (owner != null && !owner.isBlank() && !owner.equals(modId)) {
                        continue;
                    }
                    List<String> sides = a.strList("value");
                    if (!sides.isEmpty()) {
                        boolean any = sides.size() > 1;
                        boolean want = client ? sides.contains("CLIENT")
                                : sides.contains("DEDICATED_SERVER");
                        if (!want && !any) {
                            continue;
                        }
                    }
                    Class<?> c = Ref.clsInit(host, e.getKey());
                    if (c == null) {
                        continue;
                    }
                    try {
                        Ref.callTyped(bus, "register", new Class<?>[]{Object.class}, c);
                        n++;
                    } catch (Throwable t) {
                        log.accept("[跨端] 静态订阅类 " + e.getKey() + " 挂载失败：" + t);
                    }
                }
            }
        } catch (Throwable t) {
            log.accept("[跨端] 扫描静态订阅类失败：" + t);
        }
        return n;
    }

    /** 读包里的 mods.toml 的 [[mods]] 表。 */
    private static List<Map<String, Object>> readModTables(Path jar) {
        for (String entry : new String[]{"META-INF/neoforge.mods.toml", "META-INF/mods.toml"}) {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                ZipEntry e = zip.getEntry(entry);
                if (e == null) {
                    continue;
                }
                String text;
                try (InputStream in = zip.getInputStream(e)) {
                    text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
                List<Map<String, Object>> mods = Toml.tables(Toml.parse(text), "mods");
                if (!mods.isEmpty()) {
                    return mods;
                }
            } catch (Throwable ignored) {
                // 试下一个入口
            }
        }
        return List.of();
    }

    // ------------------------------------------------------------------ 事件与生命周期

    /**
     * 注册事件：每一个内置注册表都要单独发一次。
     *
     * <p>时机是本驱动最讲究的地方。对方平台（NeoForge）的做法是：原版把内置注册表填满之后、
     * 冻结之前，逐个注册表发 {@code RegisterEvent}，模组的 {@code DeferredRegister}
     * 就在这时候把手里的条目真正写进注册表。我们把这个时机钉在
     * {@code MappedRegistry.freeze()} 的入口上 —— 那是"还能写、且原版已经写完"的唯一窗口。
     */
    public static boolean fireRegisterEvents(ClassLoader host, Consumer<String> log) {
        if (CONTAINERS.isEmpty() || registerFired) {
            return false;
        }
        registerFired = true;
        Platform p = Platform.of(platformId);
        Class<?> builtIn = Ref.cls(host, "net.minecraft.core.registries.BuiltInRegistries");
        Class<?> registryCls = Ref.cls(host, "net.minecraft.core.Registry");
        Class<?> registerEvent = Ref.cls(host, p.registerEvent());
        if (builtIn == null || registryCls == null || registerEvent == null) {
            String why = "注册表事件无法触发：缺 " + (registerEvent == null
                    ? p.registerEvent() : "内置注册表");
            NOTES.add(why);
            log.accept("[跨端] " + why);
            return false;
        }
        Constructor<?> ctor = null;
        for (Constructor<?> c : registerEvent.getDeclaredConstructors()) {
            if (c.getParameterCount() == 2) {
                ctor = c;
                break;
            }
        }
        if (ctor == null) {
            NOTES.add("注册表事件的构造器形状不认识，已跳过");
            return false;
        }
        ctor.setAccessible(true);
        Method accept = acceptEventMethod(host, p);
        if (accept == null) {
            NOTES.add("ModContainer 没有 acceptEvent，注册表事件无法投递");
            return false;
        }
        // 原版在自己的引导流程里会把内置注册表冻结。对方平台为此提供了
        // "解冻 → 写 → 再冻结"的官方入口（GameData.unfreezeData / freezeData），
        // 我们直接用它 —— 这比自己找注册表的内部字段去改要稳得多。
        boolean unfrozen = gameDataCall(host, p, "unfreezeData", log);
        Map<String, Object> registries = Ref.staticFieldsOfType(builtIn, registryCls);
        int fired = 0;
        int added = 0;
        int failed = 0;
        for (Map.Entry<String, Object> e : registries.entrySet()) {
            Object registry = e.getValue();
            if (!FIRED.add(registry)) {
                continue;
            }
            Object key;
            try {
                key = Ref.callTyped(registry, "key", new Class<?>[]{});
            } catch (Throwable t) {
                continue;
            }
            if (key == null) {
                continue;
            }
            try {
                Object event = ctor.newInstance(key, registry);
                int before = sizeOf(registry);
                for (Object container : CONTAINERS) {
                    try {
                        accept.invoke(container, event);
                    } catch (java.lang.reflect.InvocationTargetException ite) {
                        failed++;
                        log.accept("[跨端] " + modIdOf(container) + " 处理注册表 " + e.getKey()
                                + " 出错：" + Ref.describe(ite.getCause()));
                        if (Boolean.getBoolean("fusion.crossload.trace")) {
                            ite.getCause().printStackTrace();
                        }
                    }
                }
                added += Math.max(0, sizeOf(registry) - before);
                fired++;
            } catch (Throwable t) {
                log.accept("[跨端] 注册表 " + e.getKey() + " 的事件未发出：" + t);
            }
        }
        if (fired > 0) {
            log.accept("[跨端] 注册表事件：为 " + fired + " 个内置注册表逐个发出，模组新增条目 "
                    + added + " 个" + (failed > 0 ? "，失败 " + failed + " 次" : ""));
        }
        if (unfrozen) {
            gameDataCall(host, p, "freezeData", log);
        }
        return fired > 0;
    }

    /** 调用对方平台的 GameData 静态方法（解冻/冻结），没有这一端就跳过。 */
    private static boolean gameDataCall(ClassLoader host, Platform p, String name,
                                        Consumer<String> log) {
        Class<?> c = Ref.cls(host, p.gameData());
        if (c == null) {
            return false;
        }
        try {
            Ref.callStatic(c, name, new Class<?>[]{});
            return true;
        } catch (Throwable t) {
            log.accept("[跨端] GameData." + name + " 调用失败（继续按原顺序进行）：" + t);
            return false;
        }
    }

    private static Method acceptEventMethod(ClassLoader host, Platform p) {
        Class<?> container = Ref.cls(host, p.modContainer());
        if (container == null) {
            return null;
        }
        for (Method m : container.getMethods()) {
            if (m.getName().equals("acceptEvent") && m.getParameterCount() == 1) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    private static int sizeOf(Object registry) {
        try {
            Object n = Ref.callTyped(registry, "size", new Class<?>[]{});
            return n instanceof Integer i ? i : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String modIdOf(Object container) {
        try {
            return String.valueOf(Ref.callTyped(container, "getModId", new Class<?>[]{}));
        } catch (Throwable t) {
            return container.getClass().getSimpleName();
        }
    }

    /** 生命周期事件：模组用它做"注册之后、跑起来之前"的初始化。 */
    public static void fireLifecycle(ClassLoader host, boolean client, Consumer<String> log) {
        if (CONTAINERS.isEmpty()) {
            return;
        }
        Platform p = Platform.of(platformId);
        List<String> events = new ArrayList<>(3);
        events.add(p.lifecyclePackage() + "FMLCommonSetupEvent");
        if (client) {
            events.add(p.lifecyclePackage() + "FMLClientSetupEvent");
        }
        events.add(p.lifecyclePackage() + "FMLLoadCompleteEvent");
        Class<?> workQueueCls = Ref.cls(host, p.deferredWorkQueue());
        Method accept = acceptEventMethod(host, p);
        if (accept == null) {
            return;
        }
        for (String eventName : events) {
            Class<?> ec = Ref.cls(host, eventName);
            if (ec == null) {
                continue;
            }
            Constructor<?> ctor = null;
            for (Constructor<?> c : ec.getDeclaredConstructors()) {
                if (c.getParameterCount() == 2) {
                    ctor = c;
                    break;
                }
            }
            if (ctor == null) {
                continue;
            }
            ctor.setAccessible(true);
            int ok = 0;
            for (Object container : CONTAINERS) {
                try {
                    Object queue = workQueueCls == null ? null
                            : Ref.construct(workQueueCls, "FusionML 跨端 " + ec.getSimpleName());
                    Object event = ctor.newInstance(container, queue);
                    accept.invoke(container, event);
                    if (queue != null) {
                        Ref.callTyped(queue, "runTasks", new Class<?>[]{});
                    }
                    ok++;
                } catch (java.lang.reflect.InvocationTargetException ite) {
                    log.accept("[跨端] " + modIdOf(container) + " 的 " + ec.getSimpleName()
                            + " 处理器抛错：" + ite.getCause());
                } catch (Throwable t) {
                    log.accept("[跨端] " + modIdOf(container) + " 的 " + ec.getSimpleName()
                            + " 未投递：" + t);
                }
            }
            if (ok > 0) {
                log.accept("[跨端] 生命周期 " + ec.getSimpleName() + " → " + ok + " 个模组");
            }
        }
    }

    public static boolean installed() {
        return installed;
    }

    public static List<String> notes() {
        return NOTES;
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9_.]", "_");
    }

    /**
     * 生成 ModContainer 的适配子类。
     *
     * <p>为什么必须生成、而不是直接用对方平台的 {@code FMLModContainer}：那个类的构造器
     * 要求一个 ModLauncher 模块层，并且用 {@code Class.forName(module, name)} 去加载入口类 ——
     * 模组类会在第二个类加载器里被再定义一遍。而跨端 Mixin 能生效的原因恰恰是
     * "模组类与游戏类在同一个加载器里"，两条路不能同时走。
     * 所以我们只实现 {@code getEventBus()} 这一个抽象方法，让容器成为
     * "一根插在模组与它自己的总线之间的线"。
     *
     * <p>生成用的字节码来自本项目自己的生成器（{@link ClassEmitter}），没有 ASM。
     */
    private static byte[] containerBytes(Platform p) {
        String owner = p.modContainer().replace('.', '/');
        // 类名必须落在 ModContainer 所在的运行时包里：defineClass 走的是目标类的 lookup，
        // 而 Lookup.defineClass 只能定义"与本 lookup 同包"的类。
        String self = owner + "$FusionForeignContainer";
        ClassEmitter e = new ClassEmitter(self, owner);
        e.version(61);
        e.access(0x0031);                        // public final
        String busDesc = "L" + p.iEventBus().replace('.', '/') + ";";
        String infoDesc = "L" + p.iModInfo().replace('.', '/') + ";";
        e.addField("fusionBus", busDesc, 0x0012); // private final
        int ctorSuper = e.constMethodref(owner, "<init>", "(" + infoDesc + ")V");
        int busField = e.constFieldref(self, "fusionBus", busDesc);
        ByteOut ctor = new ByteOut(16);
        ctor.u1(0x2A);                                     // aload_0
        ctor.u1(0x2B);                                     // aload_1
        ctor.u1(0xB7).u2(ctorSuper);                       // invokespecial super(info)
        ctor.u1(0x2A);                                     // aload_0
        ctor.u1(0x2C);                                     // aload_2
        ctor.u1(0xB5).u2(busField);                        // putfield this.fusionBus
        ctor.u1(0xB1);                                     // return
        e.addMethod("<init>", "(" + infoDesc + busDesc + ")V", 0x0001, 2, 3, ctor.toByteArray());
        ByteOut get = new ByteOut(8);
        get.u1(0x2A);                                      // aload_0
        get.u1(0xB4).u2(busField);                         // getfield this.fusionBus
        get.u1(0xB0);                                      // areturn
        e.addMethod("getEventBus", "()" + busDesc, 0x0001, 1, 1, get.toByteArray());
        return e.build();
    }
}
