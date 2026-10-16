package com.fusionml.launch;

import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

/**
 * 诊断探针：打印运行时的类加载器拓扑。
 *
 * <p>只有在 agent 参数含 {@code clprobe} 时才执行，正常启动路径零开销。
 *
 * <p>存在的价值是一条反直觉且极易被重新质疑的结论：官方 {@code server.jar} 是
 * bundler，它新建 {@code URLClassLoader} 时取的是
 * {@code getClass().getClassLoader().getParent()} —— 即 <b>PlatformClassLoader</b>，
 * <b>有意跳过 AppClassLoader</b>。因此
 * {@code Instrumentation.appendToSystemClassLoaderSearch}（只作用于 App CL）
 * 对服务端<b>无效</b>：实测追加成功、随后仍然 {@code ClassNotFoundException}，
 * 栈里是 {@code URLClassLoader.findClass} 被直接调用。
 *
 * <p>没有这个探针，下一个人看到"我们明明往 system CL 追加了 jar，为什么还是找不到类"
 * 时只能从头再摸一遍。改这里的打印前先想清楚它为什么存在。
 */
public final class ClProbe {
    private ClProbe() {
    }

    public static void main(String[] args) throws Exception {

    }

    public static void premain(String a, Instrumentation inst) {
        try {
            ClassLoader sys = ClassLoader.getSystemClassLoader();
            System.out.println("[CLPROBE] system CL = " + sys);
            ClassLoader mine = ClProbe.class.getClassLoader();
            System.out.println("[CLPROBE] our CL     = " + mine);
            System.out.println("[CLPROBE] ours==sys  = " + (mine == sys));
            URL u = ClProbe.class.getProtectionDomain().getCodeSource().getLocation();
            System.out.println("[CLPROBE] our location = " + u);
            System.out.println("[CLPROBE] is file      = " + Files.isRegularFile(Path.of(u.toURI())));
            try {
                System.out.println("[CLPROBE] sysCL.getResource(ClProbe) BEFORE = "
                        + sys.getResource("com/fusionml/launch/ClProbe.class"));
            } catch (Throwable t) {
                System.out.println("[CLPROBE] before resource check failed: " + t);
            }
            inst.appendToSystemClassLoaderSearch(new JarFile(Path.of(u.toURI()).toFile()));
            System.out.println("[CLPROBE] append done");
            System.out.println("[CLPROBE] sysCL.getResource(ClProbe) AFTER  = "
                    + sys.getResource("com/fusionml/launch/ClProbe.class"));
            System.out.println("[CLPROBE] sysCL.getResource(CrossLoad) AFTER = "
                    + sys.getResource("com/fusionml/crossload/CrossLoad.class"));
            try {
                Class<?> c = Class.forName("com.fusionml.crossload.CrossLoad", false, sys);
                System.out.println("[CLPROBE] sysCL load CrossLoad OK = " + c);
            } catch (Throwable t) {
                System.out.println("[CLPROBE] sysCL load CrossLoad FAIL = " + t);
            }
            // Probe E: replicate EXACTLY what net.minecraft.bundler.Main does, per javap:

            ClassLoader bundlerLoader = ClProbe.class.getClassLoader();   // bundler's own CL
            ClassLoader bundlerParent = bundlerLoader == null ? null : bundlerLoader.getParent();
            System.out.println("[CLPROBE] bundlerLoader       = " + bundlerLoader);
            System.out.println("[CLPROBE] bundlerParent (real)= " + bundlerParent);
            System.out.println("[CLPROBE] platform CL         = " + ClassLoader.getPlatformClassLoader());
            System.out.println("[CLPROBE] bundlerParent==platform = "
                    + (bundlerParent == ClassLoader.getPlatformClassLoader()));
            System.out.println("[CLPROBE] bundlerParent==system   = "
                    + (bundlerParent == ClassLoader.getSystemClassLoader()));
            for (ClassLoader p = bundlerParent; p != null; p = p.getParent()) {
                System.out.println("[CLPROBE]   parent chain: " + p
                        + "  resource(CrossLoad)="
                        + p.getResource("com/fusionml/crossload/CrossLoad.class"));
            }
            try {
                Class<?> c = Class.forName("com.fusionml.crossload.CrossLoad", false, bundlerParent);
                System.out.println("[CLPROBE] bundlerParent load CrossLoad OK = " + c);
            } catch (Throwable t) {
                System.out.println("[CLPROBE] bundlerParent load CrossLoad FAIL = " + t);
            }

            // Simulate the bundler: a fresh URLClassLoader whose parent is the system CL.
            URL[] none = new URL[0];
            ClassLoader child = new java.net.URLClassLoader(none, sys);
            System.out.println("[CLPROBE] child(p=sys) = " + child);
            try {
                Class<?> c = Class.forName("com.fusionml.crossload.CrossLoad", false, child);
                System.out.println("[CLPROBE] child load CrossLoad OK = " + c + " CL=" + c.getClassLoader());
            } catch (Throwable t) {
                System.out.println("[CLPROBE] child load CrossLoad FAIL = " + t);
            }

            new Thread(() -> {
                for (int attempt = 0; attempt < 240; attempt++) {
                    try {
                        Class<?> mc = Class.forName("net.minecraft.server.MinecraftServer");
                        ClassLoader def = mc.getClassLoader();
                        System.out.println("[CLPROBE] MinecraftServer CL = " + def);
                        for (ClassLoader p = def; p != null; p = p.getParent()) {
                            System.out.println("[CLPROBE]   chain: " + p);
                            try {
                                System.out.println("[CLPROBE]     getResource(CrossLoad) = "
                                        + p.getResource("com/fusionml/crossload/CrossLoad.class"));
                            } catch (Throwable t) {
                                System.out.println("[CLPROBE]     getResource threw " + t);
                            }
                        }
                        try {
                            Class<?> c = Class.forName("com.fusionml.crossload.CrossLoad", false, def);
                            System.out.println("[CLPROBE] MC-CL load CrossLoad OK = " + c
                                    + " CL=" + c.getClassLoader());
                        } catch (Throwable t) {
                            System.out.println("[CLPROBE] MC-CL load CrossLoad FAIL = " + t);
                        }
                        return;
                    } catch (Throwable t) {
                        try {
                            Thread.sleep(250);
                        } catch (InterruptedException ie) {
                            return;
                        }
                    }
                }
                System.out.println("[CLPROBE] MC loader probe: MinecraftServer never loaded");
            }, "clprobe-mc").start();
        } catch (Throwable t) {
            System.out.println("[CLPROBE] fatal: " + t);
            t.printStackTrace(System.out);
        }
    }
}
