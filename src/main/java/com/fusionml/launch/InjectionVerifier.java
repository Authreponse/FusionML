package com.fusionml.launch;

import com.fusionml.bytecode.ClassEmitter;
import com.fusionml.bytecode.ClassFile;
import com.fusionml.bytecode.Descriptors;
import com.fusionml.bytecode.HookInjector;
import com.fusionml.util.FastZip;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 对<b>真实游戏类</b>做一次注入验证。
 *
 * <p>这是整个接入工作中最有说服力的一步：从真实的客户端 jar 里取出游戏类，
 * 对它注入一段钩子调用，然后把改写后的类交给 JVM 加载并强制校验。
 * 只要 JVM 不报 {@code VerifyError}，就说明我们的字节码改写对这份游戏的字节码是成立的，
 * 而不是"只在自己的测试类上成立"。
 *
 * <p>选靶子的策略刻意偏保守但覆盖面够：优先挑带 {@code StackMapTable} 的方法
 * （那是栈图平移最容易出错的地方），参数只接受空参或纯 int，避免验证时因为
 * 类型解析失败而误判。
 */
public final class InjectionVerifier {

    public record Result(int scanned, int injected, int verified, List<String> details) {
        public String summary() {
            return "扫描 " + scanned + " 个游戏类，改写 " + injected + " 个，"
                    + "JVM 校验通过 " + verified + " 个";
        }
    }

    private static final int MAX_SCAN = 600;
    private static final int TARGET_SAMPLES = 3;

    private InjectionVerifier() {
    }

    /**
     * @param clientJar 真实客户端 jar
     * @param parent    定义改写类时要用的父加载器（需要能看到完整游戏类路径）
     */
    public static Result run(Path clientJar, ClassLoader parent) throws IOException {
        List<String> details = new ArrayList<>();
        List<byte[]> targets = new ArrayList<>();
        List<String> targetNames = new ArrayList<>();
        int[] scanned = {0};
        int[] injected = {0};

        try (FastZip zip = FastZip.open(clientJar)) {
            for (FastZip.Entry e : zip.entries()) {
                if (targets.size() >= TARGET_SAMPLES || scanned[0] >= MAX_SCAN) {
                    break;
                }
                if (!e.isClass() || !e.name().startsWith("net/minecraft/")) {
                    continue;
                }
                scanned[0]++;
                byte[] bytes;
                try {
                    bytes = zip.read(e);
                } catch (IOException ex) {
                    continue;
                }
                String internal = e.name().substring(0, e.name().length() - ".class".length());
                if (internal.contains("$")) {
                    continue;                       
                }
                HookInjector.Hook hook = buildHookFor(bytes, internal, targets.size() / 2);
                if (hook == null) {
                    continue;
                }
                HookInjector.Result r = HookInjector.inject(bytes, List.of(hook));
                if (!r.changed()) {
                    continue;
                }
                injected[0]++;
                // 生成配套的钩子类：它必须与目标类定义在同一个加载器里，
                // 否则目标类里那条 invokestatic 会解析到另一个加载器的同名类上而校验失败。
                byte[] hookClass = new ClassEmitter(hook.hookOwner(), "java/lang/Object")
                        .addEmptyStaticMethod(hook.hookName(), hook.hookDesc())
                        .build();
                targets.add(r.bytes());
                targetNames.add(internal);
                targets.add(hookClass);
                targetNames.add(hook.hookOwner());
            }
        }

        if (targets.isEmpty()) {
            return new Result(scanned[0], 0, 0,
                    List.of("没有找到可注入的取样方法（可能这个版本的方法体结构不匹配取样条件）"));
        }

        int verified = 0;
        DefiningLoader loader = new DefiningLoader(parent, targetNames, targets);
        for (int i = 0; i < targetNames.size(); i += 2) {
            String target = targetNames.get(i);
            try {
                Class<?> c = loader.loadClass(target.replace('/', '.'));
                // 关键：触发链接与校验，但不触发静态初始化（避免真的去跑游戏代码）。
                c.getDeclaredMethods();
                verified++;
                details.add("✓ " + target.replace('/', '.') + " 改写后通过 JVM 校验");
            } catch (Throwable t) {
                details.add("✗ " + target.replace('/', '.') + " 校验失败："
                        + t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : "：" + firstLine(t.getMessage())));
            }
        }
        return new Result(scanned[0], injected[0], verified, details);
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    /**
     * 从类字节里挑一个适合做注入靶子的方法，并据此推导出钩子。
     *
     * <p>条件：有方法体、不是构造器/静态初始化块、带 StackMapTable（最能考验偏移平移）、
     * 参数只含空参或 int、并且方法描述符能推出一个 void 钩子。
     */
    private static HookInjector.Hook buildHookFor(byte[] bytes, String internalName, int index) {
        ClassFile cf;
        try {
            cf = ClassFile.read(bytes);
        } catch (RuntimeException e) {
            return null;
        }
        if ((cf.accessFlags() & 0x0200) != 0) {
            return null;                            
        }
        for (ClassFile.Member m : cf.methods()) {
            String name = cf.utf8(m.nameIndex);
            if ("<init>".equals(name) || "<clinit>".equals(name)) {
                continue;
            }
            if ((m.access & 0x0400) != 0 || (m.access & 0x0100) != 0) {
                continue;                           
            }
            ClassFile.Attr code = cf.codeAttr(m);
            if (code == null) {
                continue;
            }
            String desc = cf.utf8(m.descIndex);
            if (!simpleParams(desc)) {
                continue;
            }
            boolean hasStackMap = false;
            try {
                com.fusionml.bytecode.MethodCode mc = com.fusionml.bytecode.MethodCode.parse(
                        bytes, code.offset(), code.length());
                for (com.fusionml.bytecode.MethodCode.Nested n : mc.attrs) {
                    if ("StackMapTable".equals(cf.utf8(n.nameIndex()))) {
                        hasStackMap = true;
                        break;
                    }
                }
                if (!hasStackMap) {
                    continue;                       
                }
            } catch (RuntimeException e) {
                continue;
            }
            boolean isStatic = (m.access & 0x0008) != 0;
            String hookDesc = HookInjector.hookDesc(internalName, desc, isStatic);
            return new HookInjector.Hook(internalName, name, desc,

                    // 于是第一个靶子的 invokestatic 会解析到签名不匹配的类上，校验必然失败。
                    "com/fusionml/verify/GenHook" + index, "onEnter", hookDesc,
                    HookInjector.Point.HEAD);
        }
        return null;
    }

    /** 参数只允许空参或纯 int（避免校验阶段因类型解析问题产生误判）。 */
    private static boolean simpleParams(String methodDesc) {
        try {
            for (String t : Descriptors.paramTypes(methodDesc)) {
                if (!"I".equals(t) && !"Z".equals(t) && !"S".equals(t) && !"B".equals(t)
                        && !"C".equals(t)) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 只负责按名字定义我们准备好的那些类，其余全部委托父加载器（即真实游戏类路径）。 */
    private static final class DefiningLoader extends ClassLoader {
        private final java.util.Map<String, byte[]> byName = new java.util.LinkedHashMap<>();

        DefiningLoader(ClassLoader parent, List<String> names, List<byte[]> datas) {
            super(parent);
            for (int i = 0; i < names.size() && i < datas.size(); i++) {
                byName.put(names.get(i).replace('/', '.'), datas.get(i));
            }
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            byte[] data = byName.get(name);
            if (data != null) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    c = defineClass(name, data, 0, data.length);
                }
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            }
            return super.loadClass(name, resolve);
        }
    }
}
