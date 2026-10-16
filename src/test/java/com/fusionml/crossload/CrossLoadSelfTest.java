package com.fusionml.crossload;

import com.fusionml.mc.McInstance;
import com.fusionml.mc.McVersion;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 跨端驱动的离线自检：<b>不启动游戏</b>，直接在一个普通 JVM 里把驱动链跑一遍。
 *
 * <p>为什么值得单独做这样一个工具：真正的验证要花两分钟启动客户端并人工读日志，
 * 而这条链路上有十几处"能不能找到类、构造器形状对不对、注册表窗口对不对"的细节点。
 * 把游戏类（版本 jar）与对方平台的运行时（NeoForge 的 API/总线）放进同一个类加载器，
 * 就可以在秒级拿到"驱动成功/失败以及失败在哪一步"的结论。
 *
 * <p>它验证的东西和游戏里是一致的：用的是同一份驱动代码、同一个探针模组包。
 * 唯一被替换掉的是"时刻" —— 这里手工按同样顺序调用入口点驱动、注册事件、生命周期，
 * 因为离线没有注入进去的挂载点。
 */
public final class CrossLoadSelfTest {

    public static void main(String[] args) throws Exception {
        int failures = 0;
        Path gameDir = Path.of(Files.readString(Path.of("build/gamedir.txt"),
                StandardCharsets.UTF_8).trim());
        Path probe = Path.of("build/crossdev/fusionbridgeprobe-1.0.0.jar");
        if (!Files.isRegularFile(probe)) {
            System.out.println("[自检] 探针包不存在：" + probe + "（先跑 tools\\crossdev\\build-probe.cmd）");
            System.exit(2);
        }
        String versionId = gameDir.getFileName().toString();
        String mcKey = versionId.split("[-_ ]")[0];
        McInstance instance = McInstance.open(instanceRoot(gameDir));
        McInstance.Effective eff = instance.resolve(versionId);

        // 同一份驱动代码在两条路径上读到的东西必须一致，否则自检通过也说明不了任何事。
        System.setProperty("fusion.version", versionId);
        System.setProperty("fusion.gameDir", gameDir.toAbsolutePath().toString());
        System.setProperty("fusion.client", "true");

        // 类路径＝游戏类（版本 jar + 它的库）＋ 对方平台的运行时。
        // 这正是游戏里跨端包最终所处的那个"同一个加载器"。
        List<URL> urls = new ArrayList<>();

        // 这就是本自检能复现/验证注册表那条链路的前提 —— 少了它，

        // 顺序不能反：合并时要判断"对方新增的接口是不是对方模组硬依赖的"

        // 于是 273 个类会被整类换掉，宿主平台的 @Overwrite Mixin 就会崩。
        // 这个顺序在真机路径上也是对的（CrossPlatformBridge 先解析运行时再合并）。
        ForeignRuntime.Found runtime = ForeignRuntime.resolve(instance, "neoforge", mcKey,
                line -> System.out.println("[自检] " + line));
        for (Path jar : runtime.jars()) {
            urls.add(jar.toUri().toURL());
        }
        // FML 的全局状态要用到"借来的运行时都在哪"这份清单（版本号也从里面推）。
        StringBuilder runtimeProp = new StringBuilder();
        for (Path jar : runtime.jars()) {
            if (runtimeProp.length() > 0) {
                runtimeProp.append(java.io.File.pathSeparator);
            }
            runtimeProp.append(jar.toAbsolutePath());
        }
        System.setProperty("fusion.foreignRuntimeJars", runtimeProp.toString());

        Path patchedBase = PatchedBase.locate(instance, "neoforge", mcKey);
        if (patchedBase != null) {
            patchedBase = PatchedBase.prepare(patchedBase, eff.clientJar,
                    instance.gameDir().resolve(".fusionml").resolve("base"),
                    PatchedBase.scope(),
                    PatchedBase.hostMixinTargets(List.of(probe), patchedBase,
                            line -> System.out.println("[自检] " + line)),
                    line -> System.out.println("[自检] " + line));
            urls.add(patchedBase.toUri().toURL());
        }
        System.out.println("[自检] 补丁原版基座：" + (patchedBase == null
                ? "未找到（注册表 API 相关断言会失败，这是预期内的）" : patchedBase));
        if (eff.clientJar != null) {
            urls.add(eff.clientJar.toUri().toURL());
        }
        for (McVersion.Library lib : eff.applicableLibraries()) {
            Path p = instance.localLibraryPath(lib);
            if (p != null && Files.isRegularFile(p)) {
                urls.add(p.toUri().toURL());
            }
        }
        // 模组包本身也要在这个加载器里 —— 游戏里是靠"加入宿主平台类路径"做到的，
        // 这里等价地把它放进同一个加载器。
        urls.add(probe.toUri().toURL());
        System.out.println("[自检] 运行时：" + runtime.summary());
        System.out.println("[自检] 类路径 " + urls.size() + " 项");

        // 游戏里这一步由 javaagent 的变换管线完成，这里等价地用类加载器做。
        List<Path> scan = new ArrayList<>(runtime.jars());
        scan.add(probe);
        List<String> sealed = SealedTargets.compute(scan, eff.clientJar,
                line -> System.out.println("[自检] " + line));
        System.out.println("[自检] 需解除密封的原版类型 " + sealed.size() + " 个："
                + sealed.stream().map(s -> s.substring(s.lastIndexOf('/') + 1)).toList());

        try (URLClassLoader loader = new Unsealing(urls.toArray(new URL[0]),
                CrossLoadSelfTest.class.getClassLoader(),
                new java.util.HashSet<>(sealed))) {

            // 否则"定义 Registry 时解析 IRegistryExtension"会循环失败。
            for (String t : PatchedBase.warmUpTypes()) {
                try {
                    Class.forName(t.replace('/', '.'), false, loader);
                } catch (Throwable e) {
                    System.out.println("[自检] 预热 " + t + " 失败：" + e);
                }
            }

            // 跑不动（它要游戏版本号、数据修复器等启动期上下文），所以只把驱动真正依赖的

            Class<?> bootstrap = Class.forName("net.minecraft.server.Bootstrap", true, loader);
            java.lang.reflect.Field flag = bootstrap.getDeclaredField("isBootstrapped");
            flag.setAccessible(true);
            flag.setBoolean(null, true);
            Class<?> builtIn = Class.forName("net.minecraft.core.registries.BuiltInRegistries",
                    true, loader);
            Object root = builtIn.getField("REGISTRY").get(null);
            root.getClass().getMethod("freeze").invoke(root);
            System.out.println("[自检] 已模拟原版引导完成的状态（注册表就绪、条目已绑定）");
            ForeignModLoader.Result r = ForeignModLoader.install(loader, List.of(probe),
                    "neoforge", true, line -> System.out.println("[自检] " + line));
            System.out.println("[自检] " + r.summary());
            for (ForeignModLoader.Loaded m : r.mods()) {
                System.out.println("[自检]   · " + m.modId() + " " + m.version()
                        + (m.ok() ? " 入口 " + m.entryClass() : " 失败：" + m.error()));
            }
            if (r.okCount() == 0) {
                failures++;
                System.out.println("[自检] 失败：没有一个跨端入口点被构造起来");
            }
            boolean fired = ForeignModLoader.fireRegisterEvents(loader,
                    line -> System.out.println("[自检] " + line));
            if (!fired) {
                failures++;
                System.out.println("[自检] 失败：注册表事件没有发出去");
            }

            try {
                Class<?> mod = Class.forName("fusiontest.bridge.ProbeMod", true, loader);
                Object report = mod.getMethod("report").invoke(null);
                System.out.println("[自检] 模组自述：" + report);
                if (!String.valueOf(report).contains("在 ITEM 注册表中=true")) {
                    failures++;
                    System.out.println("[自检] 失败：模组的物品没有进入原版注册表");
                }
            } catch (Throwable t) {
                failures++;
                System.out.println("[自检] 失败：读模组状态出错 " + t);
            }
            ForeignModLoader.fireLifecycle(loader, true,
                    line -> System.out.println("[自检] " + line));
            for (String note : ForeignModLoader.notes()) {
                System.out.println("[自检] 注：" + note);
            }
        }
        System.out.println(failures == 0 ? "[自检] 全部通过" : "[自检] 失败 " + failures + " 项");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** 从版本目录往上找到 .minecraft（versions 的父目录）。 */
    private static Path instanceRoot(Path gameDir) {
        Path p = gameDir.toAbsolutePath();
        while (p != null && !"versions".equals(String.valueOf(p.getFileName()))) {
            p = p.getParent();
        }
        return p == null ? gameDir.getParent() : p.getParent();
    }

    /** 离线版"解除密封"：与游戏里变换管线做的事情一致，只是位置换成了类加载器。 */
    private static final class Unsealing extends URLClassLoader {
        private final java.util.Set<String> targets;

        Unsealing(URL[] urls, ClassLoader parent, java.util.Set<String> targets) {
            super(urls, parent);
            this.targets = targets;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            URL url = findResource(name.replace('.', '/') + ".class");
            if (url == null) {
                throw new ClassNotFoundException(name);
            }
            try (var in = url.openStream()) {
                byte[] bytes = in.readAllBytes();
                if (targets.contains(name.replace('.', '/'))) {
                    byte[] out = SealedTargets.strip(bytes);
                    if (out != null) {
                        bytes = out;
                    }
                }
                return defineClass(name, bytes, 0, bytes.length);
            } catch (java.io.IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }
    }
}
