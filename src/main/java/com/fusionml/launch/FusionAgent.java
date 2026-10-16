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
    private static final AtomicLong changed = new AtomicLong();
    private static volatile boolean errorLogged;

    private FusionAgent() {
    }

    /** JVM 在 main 之前调用。 */
    public static void premain(String args, Instrumentation inst) {
        if (inst == null) {
            System.out.println("[FusionML] 代理未拿到 Instrumentation，注入将不可用。");
            return;
        }
        instrumentation = inst;
        inst.addTransformer(TRANSFORMER, false);
        System.out.println("[FusionML] 字节码代理已挂载（等待注入规则…）");
        // 前置分析：在平台运行时启动之前，用我们自己的并行引擎完成发现、索引与依赖分析，
        // 并把注入规则装配好。这样做的好处是既不影响平台自己的类加载语义
        // （平台仍以它的启动器为主类），又让"发现 + 索引"这段用上我们的并行实现。
        if (Boolean.parseBoolean(System.getProperty("fusion.preflight", "true"))) {
            try {
                Preflight.run();
            } catch (Throwable t) {
                System.out.println("[FusionML] 前置分析异常（不影响游戏启动）：" + t);
            }
        }
    }

    public static boolean available() {
        return instrumentation != null;
    }

    /** 装注入规则。装之前到达的类不会被改写。 */
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

    /** 逐类变换：未命中规则的类直接返回 null（表示"不改动"，这是最省的返回方式）。 */
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
            // 探针：确认变换器到底有没有被调用、以及有没有看到目标类。
            // 之前"HUD 注册了却没生效"就是卡在这里：分不清是规则没匹配上，
            // 还是变换器压根没被 JVM 调用。
            if (seen.get() <= 5 || className.endsWith("/Hud") || className.endsWith("/Gui")) {
                System.out.println("[FusionML/探针] 变换器看到类 #" + seen.get() + " " + className
                        + "（命中规则=" + p.hasHooksFor(className) + "）");
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
