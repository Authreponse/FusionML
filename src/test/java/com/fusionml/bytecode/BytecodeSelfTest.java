package com.fusionml.bytecode;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.List;

/**
 * 字节码层自检。覆盖四件事：
 * <ol>
 *   <li>{@link ClassEmitter} 生成的类能被 JVM 正常加载并执行；</li>
 *   <li>{@link ClassScanner} 能正确读出类名、父类、接口、注解；</li>
 *   <li>{@link HookInjector} 对"带 StackMapTable 的真实 javac 产物"注入后，
 *       类仍然通过 JVM 校验且行为正确；</li>
 *   <li>未改动时 {@code toBytes()} 返回原数组（零拷贝承诺）。</li>
 * </ol>
 */
public final class BytecodeSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        testEmitterRoundTrip();
        testScanner();
        testZeroCopy();
        testHookInjectionOnRealClass();
        System.out.println();
        System.out.println("字节码层自检：" + passed + " 通过 / " + failed + " 失败");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [通过] " + what);
        } else {
            failed++;
            System.out.println("  [失败] " + what);
        }
    }

    // ------------------------------------------------------------ 1. 生成器

    private static void testEmitterRoundTrip() throws Exception {
        System.out.println("1) 类生成器 → JVM 加载执行");
        byte[] bytes = new ClassEmitter("fusiontest/Emitted", "java/lang/Object")
                .addAnnotation("Lfusiontest/Marker;")
                .addDefaultConstructor()
                .addEmptyStaticMethod("noop", "()V")
                .addConstantStaticMethod("answer", "()I", 42)
                .build();

        ClassLoader loader = new SingleClassLoader(bytes, "fusiontest.Emitted");
        Class<?> c = loader.loadClass("fusiontest.Emitted");
        Object instance = c.getDeclaredConstructor().newInstance();
        Method answer = c.getMethod("answer");
        check("生成的类可实例化", instance != null);
        check("生成的静态方法返回 42", Integer.valueOf(42).equals(answer.invoke(null)));
        check("生成的类名正确", "fusiontest.Emitted".equals(c.getName()));
    }

    // ------------------------------------------------------------ 2. 扫描器

    private static void testScanner() throws Exception {
        System.out.println("2) 注册扫描器");
        byte[] bytes = new ClassEmitter("fusiontest/Scanned", "java/lang/Object")
                .addAnnotation("Lfusiontest/Mod;")
                .addInterface("java/lang/Runnable")
                .addDefaultConstructor()
                .build();
        ClassScanner.Info info = ClassScanner.scan(bytes);
        check("读出名", "fusiontest/Scanned".equals(info.name()));
        check("读出父类", "java/lang/Object".equals(info.superName()));
        check("读出接口", info.interfaces().length == 1
                && "java/lang/Runnable".equals(info.interfaces()[0]));
        check("读出注解", info.hasAnnotation("Lfusiontest/Mod;"));
        check("scanName 快路径一致", "fusiontest/Scanned".equals(ClassScanner.scanName(bytes)));
    }

    // ------------------------------------------------------------ 3. 零拷贝

    private static void testZeroCopy() throws Exception {
        System.out.println("3) 未改动时零拷贝");
        byte[] original = readResource("fusiontest/Target.class");
        ClassFile cf = ClassFile.read(original);
        check("未改动标记", !cf.modified());
        check("toBytes 返回同一数组实例", cf.toBytes() == original);
    }

    // ------------------------------------------------------------ 4. 注入

    private static void testHookInjectionOnRealClass() throws Exception {
        System.out.println("4) 对真实 javac 产物做 HEAD 注入（含 StackMapTable 平移）");
        byte[] original = readResource("fusiontest/Target.class");
        ClassFile cf0 = ClassFile.read(original);
        boolean hasStackMap = false;
        for (ClassFile.Member m : cf0.methods()) {
            ClassFile.Attr code = cf0.codeAttr(m);
            if (code == null) {
                continue;
            }
            MethodCode mc = MethodCode.parse(original, code.offset(), code.length());
            for (MethodCode.Nested n : mc.attrs) {
                if ("StackMapTable".equals(cf0.utf8(n.nameIndex()))) {
                    hasStackMap = true;
                }
            }
        }
        check("靶子类确实带 StackMapTable（否则本次测试没有意义）", hasStackMap);

        String target = "fusiontest/Target";
        List<HookInjector.Hook> hooks = List.of(
                new HookInjector.Hook(target, "compute", null, "fusiontest/Hook", "onCompute",
                        "(" + Descriptors.toDescriptor(target) + "II)V", HookInjector.Point.HEAD),
                new HookInjector.Hook(target, "mix", null, "fusiontest/Hook", "onMix",
                        "(JD)V", HookInjector.Point.HEAD));

        HookInjector.Result result = HookInjector.inject(original, hooks);
        check("注入了 2 个方法", result.injectedMethods() == 2);
        // 落盘便于用 javap 反查结构（自检失败时这是最有效的排查手段）。
        dump(result.bytes(), "Target.class");

        // 注入后的 Target 与钩子类 Hook 必须在同一个加载器里定义：
        // 否则钩子描述符里的 Lfusiontest/Target; 会被解析到父加载器的旧 Target 上，
        // 造成"栈上是新 Target、方法签名要旧 Target"的校验冲突。
        java.util.Map<String, byte[]> defs = new java.util.HashMap<>();
        defs.put("fusiontest.Target", result.bytes());
        defs.put("fusiontest.Hook", readResource("fusiontest/Hook.class"));
        try (InjectionLoader loader = new InjectionLoader(defs)) {
            Class<?> targetClass = loader.loadClass("fusiontest.Target");
            Class<?> hookClass = loader.loadClass("fusiontest.Hook");
            Object instance = targetClass.getDeclaredConstructor().newInstance();

            Method compute = targetClass.getMethod("compute", int.class, int.class);
            Object value = compute.invoke(instance, 5, 3);
            // 期望：i=0..4，偶数加 b，奇数加 2b → 3+6+3+6+3 = 21
            check("注入后 compute 行为不变（期望 21，实际 " + value + "）",
                    Integer.valueOf(21).equals(value));
            Object hits = hookClass.getField("computeHits").get(null);
            check("钩子被调用（次数=" + hits + "）", Integer.valueOf(1).equals(hits));
            Object lastA = hookClass.getField("lastA").get(null);
            check("钩子拿到正确的参数 a=" + lastA, Integer.valueOf(5).equals(lastA));

            Method mix = targetClass.getMethod("mix", long.class, double.class);
            Object mixValue = mix.invoke(null, 100L, 2.5d);
            // 期望：100 + 0 + 2 + 5 + 7 = 114
            check("注入后 mix 行为不变（期望 114，实际 " + mixValue + "）",
                    Long.valueOf(114L).equals(mixValue));
            Object mixHits = hookClass.getField("mixHits").get(null);
            check("long/double 参数的钩子被调用（次数=" + mixHits + "）",
                    Integer.valueOf(1).equals(mixHits));
        }
    }

    // ------------------------------------------------------------ 工具

    private static byte[] readResource(String name) throws Exception {
        try (InputStream in = BytecodeSelfTest.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("找不到测试资源 " + name);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** 把字节写到 build/injected 下，方便用 javap 检查生成结果。 */
    private static void dump(byte[] bytes, String fileName) {
        try {
            java.nio.file.Path dir = java.nio.file.Path.of("build", "injected");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.write(dir.resolve(fileName), bytes);
        } catch (Exception e) {
            System.out.println("  （落盘失败：" + e.getMessage() + "）");
        }
    }

    /** 只定义单个类的加载器。 */
    private static final class SingleClassLoader extends ClassLoader {
        private final byte[] bytes;
        private final String name;

        SingleClassLoader(byte[] bytes, String name) {
            super(BytecodeSelfTest.class.getClassLoader());
            this.bytes = bytes;
            this.name = name;
        }

        @Override
        protected Class<?> loadClass(String n, boolean resolve) throws ClassNotFoundException {
            if (n.equals(name)) {
                Class<?> c = findLoadedClass(n);
                if (c == null) {
                    c = defineClass(n, bytes, 0, bytes.length);
                }
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            }
            return super.loadClass(n, resolve);
        }

        @Override
        protected Class<?> findClass(String n) throws ClassNotFoundException {
            if (n.equals(name)) {
                return defineClass(n, bytes, 0, bytes.length);
            }
            throw new ClassNotFoundException(n);
        }
    }

    /** 按给定字节定义指定类（子优先），其余全部委托父加载器。 */
    private static final class InjectionLoader extends ClassLoader implements AutoCloseable {
        private final java.util.Map<String, byte[]> defs;

        InjectionLoader(java.util.Map<String, byte[]> defs) {
            super(BytecodeSelfTest.class.getClassLoader());
            this.defs = defs;
        }

        @Override
        protected Class<?> loadClass(String n, boolean resolve) throws ClassNotFoundException {
            // 子优先：被测类必须用我们手里的字节，否则测的就是 classpath 上的原类。
            if (defs.containsKey(n)) {
                Class<?> c = findLoadedClass(n);
                if (c == null) {
                    byte[] bytes = defs.get(n);
                    c = defineClass(n, bytes, 0, bytes.length);
                }
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            }
            return super.loadClass(n, resolve);
        }

        @Override
        protected Class<?> findClass(String n) throws ClassNotFoundException {
            byte[] bytes = defs.get(n);
            if (bytes != null) {
                return defineClass(n, bytes, 0, bytes.length);
            }
            throw new ClassNotFoundException(n);
        }

        @Override
        public void close() {
        }
    }
}
