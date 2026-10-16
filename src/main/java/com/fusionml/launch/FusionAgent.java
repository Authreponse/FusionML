package com.fusionml.launch;

import com.fusionml.loader.TransformPipeline;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 以 Java 代理（javaagent）方式挂载的字节码变换入口。
 *
 * <p>为什么必须用代理：注入要在"类被定义之前"发生。游戏的类由它自己的类加载器加载，
 * 我们如果没有代理，就只能等类定义完再想办法，而类一旦定义就无法再改写其方法体
 * （只能重转换，且限制更多）。代理给了我们定义时刻的字节码，这是最干净的位置。
 *
 * <p>启动流程是"两段式"：代理在 {@code premain} 里先把自己挂上去（此时注入规则还是空的），
 * 等 {@link FusionClientMain} 把模组解析完、规则就绪后调用
 * {@link #installPipeline(TransformPipeline)} 把规则装进来。在那之前到达的类会被跳过 ——
 * 这是刻意的：宁可漏掉极少数早到的类，也不要一边加载模组一边改写游戏类，那种竞态极难排查。
 */
public final class FusionAgent {

    private static volatile Instrumentation instrumentation;
    private static volatile TransformPipeline pipeline;
    /**
     * 变换器的强引用。
     *
     * <p>必须用字段持有：{@code Instrumentation.addTransformer} 只保存弱引用，
     * 内联 {@code addTransformer(new Transformer(), false)} 的话，它会在某次 GC 之后被回收，
     * 之后所有类都不再经过变换 —— 而且**没有任何报错**，表现就是"规则注册了却完全不生效"。
     * 这个坑我们踩过一次（探针证明变换器从第一次 GC 起就再也没被调用）。
     */
    private static final ClassFileTransformer TRANSFORMER = new Transformer();
    private static final AtomicLong seen = new AtomicLong();
    /** 已经打印过"命中规则"的类：避免同一个类被反复请求时刷屏。 */
    private static final java.util.Set<String> reportedClasses =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final AtomicLong changed = new AtomicLong();
    private static volatile boolean errorLogged;

    private FusionAgent() {
    }

    public static void premain(String args, Instrumentation inst) {
        if (inst == null) {
            System.out.println("[FusionML] 代理未拿到 Instrumentation，注入将不可用。");
            return;
        }
        instrumentation = inst;
        inst.addTransformer(TRANSFORMER, false);
        System.out.println("[FusionML] 字节码代理已挂载（等待注入规则…）");
        if (args != null && args.contains("clprobe")) {
            ClProbe.premain(args, inst);
        }
        exposeLoaderJarToSystemClassLoader(inst);
        applyAgentArgs(args);
        // 前置分析：在平台运行时启动之前，用我们自己的并行引擎完成发现、索引与依赖分析，
        // 并把注入规则装配好。这样做的好处是既不影响平台自己的类加载语义
        // （平台仍以它的启动器为主类），又让"发现 + 索引"这段用上我们的并行实现。

        // 注意：这里刻意**不加** && client 之类的判断。服务端也要做前置分析 ——
        // 服务端上模组的发现、依赖与 Mixin 配置同样需要它，只是注入目标不同（见 Preflight）。
        if (Boolean.parseBoolean(System.getProperty("fusion.preflight", "true"))) {
            try {
                Preflight.run();
            } catch (Throwable t) {
                System.out.println("[FusionML] 前置分析异常（不影响游戏启动）：" + t);
            }
        }
    }

    /**
     * 把加载器 jar 暴露给 <b>system classloader</b>。
     *
     * <h2>先说结论：这一步对官方 bundler 服务端<u>无效</u></h2>
     *
     * <p>曾经的想法是：我们注入的钩子是一条 {@code invokestatic}，常量池里的 Methodref 指向
     * {@code com/fusionml/...}；JVM 用<b>被改写类的定义加载器</b>解析它，而服务端那个加载器
     * 看不见我们 —— 那就把 jar 追加进 system classloader，靠着
     * {@code URLClassLoader} 先委派 parent 的规则解决。实测<b>证伪了</b>这个想法。
     *
     * <p>原因是官方 {@code server.jar} 是 bundler，它建加载器时取的是
     * {@code getClass().getClassLoader().getParent()} —— <b>PlatformClassLoader</b>，
     * <b>有意跳过 AppClassLoader</b>（反汇编证据见 {@code build/bundler-disasm.txt}）。
     * 我们追加的正是 App CL，而它根本不在 bundler 加载器的父子链上，所以永远用不上：
     * <pre>
     * append 后 system CL 确实能给出 CrossLoad 资源  →  仍然 ClassNotFoundException
     * 栈：java.base/java.net.URLClassLoader.findClass      ← 直接 findClass，没有委派到 App CL
     * </pre>
     * 试过的其它两条路同样不通：让钩子指向我们自己的"桥类"（仍是我们的类，一样看不见）；
     * 反射往 platform 层追加（要 {@code --add-opens} 动 java.base，而服务端命令行是用户的，
     * 不能要求用户改）。**最终解法是绕开"解析我们的类"这件事本身** —— 见
     * {@code HookInjector} 的派发模式：钩子只引用 bootstrap 定义的 {@code java.lang.System}，
     * 真正的目标从系统属性里以对象形式取。
     *
     * <h2>那为什么还留着这一步</h2>
     *
     * <p>它对<b>其它</b>加载器布局仍然有意义：非 bundler 启动路径、以及将来某些平台
     * 把游戏类挂在 App CL 或其后代上的情形。留着它成本几乎为零，
     * 而删掉会让那些情形重新踩坑。**但它不是服务端能跑起来的原因** ——
     * 若看到服务端 {@code NoClassDefFoundError}，不要以为是这步没做够。
     *
     * <h2>两条必须遵守的边界</h2>
     * <ul>
     *   <li><b>整个方法绝不能抛异常</b>。它是 agent 挂载路径上的一步，一旦抛出去，
     *       连客户端都会起不来 —— 用"增强"的失败去换"游戏起不来"是绝对不划算的。</li>
     *   <li>对<b>同一路径重复调用会抛 IllegalArgumentException</b>，
     *       所以这里既记了"已加过"的标志，也把异常吞掉。</li>
     * </ul>
     */
    private static void exposeLoaderJarToSystemClassLoader(Instrumentation inst) {
        try {
            java.net.URL url = FusionAgent.class.getProtectionDomain()
                    .getCodeSource().getLocation();
            if (url == null) {
                System.out.println("[FusionML] 无法定位加载器自身位置，跳过 classpath 注册"
                        + "（服务端可能因此解析不到注入钩子的目标类）");
                return;
            }
            java.nio.file.Path path = java.nio.file.Path.of(url.toURI());
            if (!java.nio.file.Files.isRegularFile(path)) {
                // 开发期跑 build\classes 时它是目录，而目录本来就在 system classpath 上，

                System.out.println("[FusionML] 加载器以目录形式运行（" + path
                        + "），已在 classpath 上，无需追加");
                return;
            }
            if (jarExposed.compareAndSet(false, true)) {
                inst.appendToSystemClassLoaderSearch(new java.util.jar.JarFile(path.toFile()));
                System.out.println("[FusionML] 已把加载器 jar 加入 system classpath："
                        + path.getFileName());
            }
        } catch (Throwable t) {

            // 但必须留下痕迹：服务端若因此报 NoClassDefFoundError，这行日志就是线索。
            System.out.println("[FusionML] 追加加载器 jar 到 system classpath 未成功（不影响启动）："
                    + t);
        }
    }

    /** 保证 {@code appendToSystemClassLoaderSearch} 只被调用一次（重复调用会抛异常）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean jarExposed =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * 解析 javaagent 的 agent 参数，决定这次是客户端还是服务端。
     *
     * <p><b>为什么必须有这一步。</b>走官方 {@code server.jar}（它是一个 bundler）启动时，
     * 命令行里没有任何地方能写 {@code -Dfusion.client=false}，而 {@code Preflight} 恰恰靠这个
     * 系统属性决定"要不要注册 HUD / 要不要建窗口"。少了它，服务端会按客户端处理：
     * 去注册 {@code net/minecraft/client/**} 的钩子，还会尝试建图形窗口 ——
     * 在无头服务器上表现为一堆看起来与"服务端"无关的报错。
     *
     * <p>agent 参数是我们唯一能拿到这个信息的通道，因为 {@code -javaagent:xxx.jar=server}
     * 里的 {@code =server} 会原样传到这里。实测（本机 26.3）确认送达。
     *
     * <p><b>args 为空时什么都不做</b>：客户端路径（{@code LaunchPlan} 里显式传
     * {@code -Dfusion.client=...}）已经实测能进主界面，绝不能因为这次改动影响到它。
     * 未知取值同样不猜 —— 保持现状比猜错一个运行侧安全得多。
     */
    private static void applyAgentArgs(String args) {
        if (args == null || args.isBlank()) {
            return;
        }
        // 逗号与分号都当分隔符：写 agent 参数的人两者都会用，而这里容错的代价是零。
        boolean sawServer = false;
        boolean sawClient = false;
        for (String raw : args.split("[,;]")) {
            String a = raw.trim().toLowerCase(java.util.Locale.ROOT);
            if (a.isEmpty()) {
                continue;
            }
            if (a.equals("server") || a.equals("side=server")) {
                sawServer = true;
            } else if (a.equals("client") || a.equals("side=client")) {
                sawClient = true;
            }
        }
        if (sawServer == sawClient) {

            if (sawServer) {
                System.out.println("[FusionML] 代理参数同时指定了 server 与 client，"
                        + "无法判断运行侧，保持原有设置");
            }
            return;
        }

        if (sawServer) {
            System.setProperty("fusion.client", "false");
            System.out.println("[FusionML] 代理参数指定：服务端模式（fusion.client=false）");
        }
    }

    public static boolean available() {
        return instrumentation != null;
    }

    public static void installPipeline(TransformPipeline p) {
        pipeline = p;
        System.out.println("[FusionML] 注入规则已装载：共 " + p.hookRuleCount() + " 条");
    }

    public static long seenClasses() {
        return seen.get();
    }

    public static long changedClasses() {
        return changed.get();
    }

    private static final class Transformer implements ClassFileTransformer {
        /**
         * 老式签名（无 Module）。
         *
         * <p><b>这个覆写是必须的，不是兼容性点缀。</b>在这个 JDK 上
         * {@code ClassFileTransformer} 的两个 transform 都是 default 方法：
         * 带 Module 的那个默认转发给不带 Module 的，而不带 Module 的默认是<b>空操作</b>。
         * 只覆写带 Module 的那个，调用会被转发到我们没覆写的空实现上 ——
         * 结果就是"代理挂上了、规则注册了、却一个类都不经过变换，而且完全不报错"。
         * 我们正是踩了这个坑（探针证明变换器一次都没被调用）。
         */
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain domain, byte[] classfileBuffer) {
            return transformClass(className, classfileBuffer);
        }

        @Override
        public byte[] transform(Module module, ClassLoader loader, String className,
                                Class<?> classBeingRedefined, ProtectionDomain domain,
                                byte[] classfileBuffer) {
            return transformClass(className, classfileBuffer);
        }

        private byte[] transformClass(String className, byte[] classfileBuffer) {
            TransformPipeline p = pipeline;
            if (p == null || classfileBuffer == null || className == null) {
                return null;
            }
            seen.incrementAndGet();

            // 只打**第一次**看到某个命中规则的类。注意不能写成"每次命中就打" ——

            boolean hit = p.hasHooksFor(className);
            if (seen.get() <= 5
                    || className.endsWith("/Hud") || className.endsWith("/Gui")
                    || (hit && reportedClasses.add(className))) {
                System.out.println("[FusionML/探针] 变换器看到类 #" + seen.get() + " " + className
                        + "（命中规则=" + hit + "）");
            }
            if (!p.hasHooksFor(className)) {
                // 先查表再动手：绝大多数类在这里就返回，零解析零拷贝。
                return null;
            }
            try {
                byte[] out = p.apply(className, classfileBuffer);
                if (out != classfileBuffer) {
                    changed.incrementAndGet();
                }
                return out == classfileBuffer ? null : out;
            } catch (Throwable t) {
                // 单个类变换失败绝不能把游戏带崩：跳过它并只报一次，避免刷屏。
                if (!errorLogged) {
                    errorLogged = true;
                    System.out.println("[FusionML] 变换 " + className + " 失败，已跳过："
                            + t.getClass().getSimpleName()
                            + (t.getMessage() == null ? "" : "：" + t.getMessage()));
                }
                return null;
            }
        }
    }
}
