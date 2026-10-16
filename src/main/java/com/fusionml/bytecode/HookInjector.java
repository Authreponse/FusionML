package com.fusionml.bytecode;

import java.util.ArrayList;
import java.util.List;

/**
 * 方法钩子注入器（自研，不依赖 Mixin / ASM）。
 *
 * <p>原理：在目标方法体最前面插入一段"栈中立"的代码 ——
 * {@code [aload_0] [加载各参数] invokestatic 钩子}。钩子返回值是 void，
 * 所以这段代码对操作数栈是"零净效果"，同时完全不碰局部变量表。
 *
 * <p>由此得到一个很漂亮的结论：<b>原有 StackMapTable 的每一帧内容依旧成立</b>，
 * 只需要把偏移量整体平移，而不需要做整段数据流分析来重算栈图。
 * 这就是"在不写验证器的前提下，依然保证注入后的类能通过 JVM 校验"的原因。
 *
 * <p>已知边界（明确写出来，而不是假装没有）：
 * <ul>
 *   <li>不支持构造器 {@code <init>} 与静态初始化块 {@code <clinit>} —— 那时的 {@code this}
 *       还处于"未初始化"状态，传给静态钩子会直接触发 VerifyError，所以显式跳过。</li>
 *   <li>目前实现 HEAD 注入点；RETURN 注入点需要在每条 return 前插入并逐条修正分支偏移量，
 *       属于后续扩展（接口已按此预留）。</li>
 * </ul>
 */
public final class HookInjector {

    public enum Point {

        HEAD
    }

    /**
     * 一条注入规则。
     *
     * @param targetClass  内部名；null 表示"当前正在处理的这个类"
     * @param targetMethod 目标方法名
     * @param targetDesc   目标方法描述符；null 表示匹配任意重载
     * @param hookOwner    钩子类内部名
     * @param hookName     钩子方法名
     * @param hookDesc     钩子方法描述符（由 {@link #hookDesc} 生成）
     * @param point        注入点
     */
    public record Hook(String targetClass, String targetMethod, String targetDesc,
                       String hookOwner, String hookName, String hookDesc, Point point) {

        public boolean dispatch() {
            return DISPATCH_OWNER.equals(hookOwner);
        }

        public String dispatchKey() {
            return DISPATCH_PROPERTY + hookName;
        }
    }

    /** 派发模式的哨兵"钩子类"名：它不是真实类，只是标记要走 JDK 派发路径。 */
    public static final String DISPATCH_OWNER = "<dispatch>";

    /**
     * 经 {@code java.lang.System} 派发的钩子目标。
     *
     * <h2>为什么需要它</h2>
     *
     * <p>注入的 {@code invokestatic} 是<b>符号引用</b>，JVM 用<b>被改写类的定义加载器</b>去解析它。
     * 服务端上这个加载器是官方 {@code server.jar}（一个 bundler）自己新建的
     * {@code URLClassLoader}，而它刻意取 {@code getClass().getClassLoader().getParent()}
     * 当父加载器 —— 也就是 <b>PlatformClassLoader</b>，<b>跳过 AppClassLoader</b>。
     * 实测（javap 反汇编确认）：
     * <pre>
     * 105: getClass()
     * 108: getClassLoader()
     * 111: getParent()                       ← 故意取父加载器
     * 136: URLClassLoader.&lt;init&gt;([URL; ClassLoader)
     * </pre>
     * 后果：<b>我们自己写的任何类</b>（无论在 agent jar 里、还是被
     * {@code appendToSystemClassLoaderSearch} 追加到 App CL）它都看不见 ——
     * 因为它的父子链根本不经过 App CL。所以"让 bundler 的加载器解析我们的类"这条路
     * 已被实证走不通（见 HookDispatch 注释里的实测记录）。
     *
     * <h2>本常量解决的问题</h2>
     *
     * <p>不去解析我们的类，改成只解析 <b>{@code java.lang.System}</b> ——
     * 它由 bootstrap 加载器定义，任何加载器都必然看得见。真正的调用目标以
     * {@code java.util.function.Consumer} 实例的形式<b>运行时</b>从系统属性里取，
     * 对象引用跨加载器传递是合法的（公共祖先 {@code Object}），不涉及符号解析。
     *
     * <p>取不到（客户端之外的场景、或没人注册）时静默跳过：这条派发只用于
     * "可选增强"，绝不能因为它让宿主方法抛异常。
     */
    public static final String DISPATCH_PROPERTY = "fusion.hookDispatch.";

    /**
     * 注册一个派发目标。
     *
     * <p>值必须实现 {@code java.util.function.Consumer<Object>}。用 JDK 自带的接口
     * （而不是我们自己定义的）是必须的：它由 bootstrap 加载，宿主侧的加载器
     * 与我们的加载器看到的是同一个类型，{@code checkcast} 才成立。
     *
     * <p>同时会装入 {@link Noop} 作为兜底，这样即使调用方之后忘了注册（或注册发生在
     * 注入之后），注入的前缀依然不会因为拿到 null 而抛 NPE。
     *
     * @return 实际使用的注册键（{@link #DISPATCH_PROPERTY} + 名称）
     */
    public static String registerDispatch(String name, java.util.function.Consumer<Object> target) {
        String key = DISPATCH_PROPERTY + name;
        System.getProperties().put(key, target == null ? NOOP : target);
        return key;
    }

    /**
     * 预先装入兜底值，让注入的前缀永远不会取到 null。
     *
     * <p>注入端无法表达"判空跳过"（会引入分支 → 需要栈图帧，见
     * {@link #buildDispatchPrefix}），所以"没注册"必须由一个真实对象承担。
     * 在服务端路径上，{@code CrossLoad} 可能压根不可见 —— 那就装入 {@link Noop}，
     * 让钩子变成一次无害的空调用，而不是 NPE。
     */
    public static void ensureDispatchDefault(String name) {
        System.getProperties().putIfAbsent(DISPATCH_PROPERTY + name, NOOP);
    }

    static final java.util.function.Consumer<Object> NOOP = new Noop();

    /**
     * 经 {@code java.lang.System} 派发时使用的消费者描述符。
     *
     * <p>用 JDK 自带的 {@code java.util.function.Consumer} 而不是我们自己定义的接口，
     * 是因为它由 bootstrap 加载器定义：服务端 bundler 的加载器与我们的加载器
     * 看到的是<b>同一个</b> {@code Consumer} 类型，因此系统属性里存的那个对象
     * 可以被 {@code checkcast} 成它、并正常 `invokeinterface`。
     * 换成我们自己的接口就会在 checkcast 处失败（两个加载器各有一份，不兼容）。
     */
    public static final String CONSUMER_DESC = "Ljava/util/function/Consumer;";

    public record Result(byte[] bytes, int injectedMethods, List<String> notes) {
        public boolean changed() {
            return injectedMethods > 0;
        }
    }

    private HookInjector() {
    }

    public static String hookDesc(String targetClass, String methodDesc, boolean isStatic) {
        List<String> params = new ArrayList<>(Descriptors.paramTypes(methodDesc));
        if (!isStatic) {
            params.add(0, targetClass);
        }
        return Descriptors.methodDesc(params, "V");
    }

    /**
     * 对单个类执行注入。
     *
     * @param classBytes 原始类字节
     * @param hooks      注入规则集合（只会应用 targetClass 匹配的那些）
     */
    public static Result inject(byte[] classBytes, List<Hook> hooks) {
        List<String> notes = new ArrayList<>(2);
        ClassFile cf;
        try {
            cf = ClassFile.read(classBytes);
        } catch (RuntimeException e) {
            return new Result(classBytes, 0, List.of("类文件解析失败：" + e.getMessage()));
        }
        String self = cf.name();
        int injected = 0;
        for (Hook hook : hooks) {
            if (hook.targetClass() != null && !hook.targetClass().equals(self)) {
                continue;
            }
            if (hook.point() != Point.HEAD) {
                notes.add("暂不支持的注入点 " + hook.point() + "：" + self + "#" + hook.targetMethod());
                continue;
            }
            int hookRef = hook.dispatch() ? -1
                    : cf.addMethodref(hook.hookOwner(), hook.hookName(), hook.hookDesc());
            int propRef = hook.dispatch() ? cf.addString(hook.dispatchKey()) : -1;
            for (ClassFile.Member m : cf.methods()) {
                if (!hook.targetMethod().equals(cf.utf8(m.nameIndex))) {
                    continue;
                }
                String desc = cf.utf8(m.descIndex);
                if (hook.targetDesc() != null && !hook.targetDesc().equals(desc)) {
                    continue;
                }
                if ((m.access & 0x0400) != 0 || (m.access & 0x0100) != 0) {
                    continue;                     
                }
                if ("<init>".equals(hook.targetMethod()) || "<clinit>".equals(hook.targetMethod())) {
                    notes.add("跳过构造器（this 未初始化，无法传入静态钩子）：" + self + "#" + hook.targetMethod());
                    continue;
                }
                ClassFile.Attr codeAttr = cf.codeAttr(m);
                if (codeAttr == null) {
                    continue;
                }
                boolean isStatic = (m.access & 0x0008) != 0;
                // 安全网：钩子签名必须与"目标方法实际会压入栈的参数序列"完全一致。
                // 不一致就跳过并说明，而不是生成一个运行期必然 VerifyError 的类。

                if (!hook.dispatch()) {
                    String expected = hookDesc(self, desc, isStatic);
                    if (!expected.equals(hook.hookDesc())
                            && !Descriptors.acceptsParams(expected, hook.hookDesc())) {
                        notes.add("钩子签名不匹配，已跳过：" + self + "#" + hook.targetMethod() + desc
                                + " 期望 " + expected + " 实际 " + hook.hookDesc());
                        continue;
                    }
                }
                MethodCode mc = MethodCode.parse(classBytes, codeAttr.offset(), codeAttr.length());
                byte[] prefix = hook.dispatch()
                        ? buildDispatchPrefix(cf, desc, isStatic, propRef)
                        : buildPrefix(cf, desc, isStatic, hookRef);
                int neededStack = hook.dispatch()
                        ? 3
                        : (isStatic ? 0 : 1) + Descriptors.paramSlots(desc);
                mc.maxStack = Math.max(mc.maxStack, neededStack);
                mc.prepend(prefix, cf::utf8, notes);
                List<MethodCode.Nested> others = new ArrayList<>(m.attrs.length);
                for (ClassFile.Attr a : m.attrs) {
                    if (a != codeAttr) {
                        byte[] info = new byte[a.length()];
                        System.arraycopy(classBytes, a.offset(), info, 0, a.length());
                        others.add(new MethodCode.Nested(a.nameIndex(), info));
                    }
                }
                int codeNameIndex = codeAttr.nameIndex();
                cf.overrideMethodAttributes(m.index, mc.encodeAttributeBlob(codeNameIndex, others));
                injected++;
            }
        }
        if (injected == 0) {
            return new Result(classBytes, 0, notes);
        }
        byte[] out = cf.toBytes();
        // 改写结果必须自检：把生成的字节重新解析一遍，确认"这个类还是它自己"。
        // ClassFormatError 崩掉整个游戏 —— 而报错信息指向的是游戏类，
        // 完全看不出是加载器写坏的（我们正是这么崩过一次：
        // ClassFormatError: Illegal class name "com.fusionml/hud/FusionMainPage"
        // 自检不过就宁可放弃这次注入：少一个 HUD 远好过崩游戏。
        try {
            ClassFile check = ClassFile.read(out);
            String expect = ClassFile.read(classBytes).name();
            if (expect == null || !expect.equals(check.name())) {
                notes.add("改写自检失败（类名不符：期望 " + expect + " 实际 " + check.name()
                        + "），已放弃本次注入：" + expect);
                return new Result(classBytes, 0, notes);
            }
        } catch (RuntimeException e) {
            notes.add("改写自检失败（生成的字节能被重新解析失败：" + e.getMessage()
                    + "），已放弃本次注入：" + cf.name());
            return new Result(classBytes, 0, notes);
        }
        return new Result(out, injected, notes);
    }

    private static byte[] buildPrefix(ClassFile cf, String methodDesc, boolean isStatic, int hookRef) {
        ByteOut o = new ByteOut(16);
        int slot = 0;
        if (!isStatic) {
            o.u1(0x2A);                            
            slot = 1;
        }
        for (String t : Descriptors.paramTypes(methodDesc)) {
            int opcode = switch (t.charAt(0)) {
                case 'L', '[' -> 0x19;             
                case 'J' -> 0x16;                  
                case 'F' -> 0x17;                  
                case 'D' -> 0x18;                  
                default -> 0x15;                   
            };
            if (slot > 255) {
                throw new IllegalStateException("参数槽位超过 255，需要 wide 指令（当前未支持）");
            }
            o.u1(opcode).u1(slot);
            slot += Descriptors.slots(t);
        }
        o.u1(0xB8);                                
        o.u2(hookRef);
        return o.toByteArray();
    }

    /**
     * 生成"经 {@code System} 派发"的字节码前缀。
     *
     * <p>与 {@link #buildPrefix} 的差别只在最后：不 {@code invokestatic} 到我们的类，
     * 而是走一条<b>只引用 JDK 类型</b>的路径取出运行时对象再调它：
     *
     * <pre>
     *   [self]                                     ← aload_0（静态方法则 aconst_null）
     *   invokestatic  java/lang/System.getProperties()Ljava/util/Properties;
     *   ldc           "&lt;注册键&gt;"
     *   invokevirtual java/util/Properties.getOrDefault(Object,Object)Object
     *   checkcast     java/util/function/Consumer
     *   swap                                       ← 让 self 在栈顶，Consumer 在下
     *   invokeinterface Consumer.accept(Ljava/lang/Object;)V
     * </pre>
     *
     * <p><b>为什么全程无分支</b>：本注入器的正确性论证是"前缀栈中立、无分支，
     * 所以原有 StackMapTable 每帧依然成立，只需整体平移偏移量"。
     * 只要引入任何跳转，跳转目标处就需要一个新栈图帧，该论证立刻失效 ——
     * 实测确实如此：早期版本用 {@code dup/ifnull/goto} 判空，立刻
     * {@code VerifyError: Expecting a stackmap frame at branch target}。
     * 因此"没注册"这件事改由 {@link #Noop} 兜底对象承担，而不是靠分支跳过。
     *
     * <p>注意这里刻意<b>不</b>做"捕获异常"：{@code Consumer} 的实现方自己负责不抛。
     * 在宿主方法头插入 try/catch 需要额外的异常表与栈图帧，代价远大于收益。
     */
    private static byte[] buildDispatchPrefix(ClassFile cf, String methodDesc, boolean isStatic,
                                              int propRef) {
        ByteOut o = new ByteOut(32);
        if (isStatic) {

            // 只关心时机的钩子足够用，而且比编造一个假对象安全。
            o.u1(0x01);                            
        } else {
            o.u1(0x2A);                            
        }
        int getProperties = cf.addMethodref("java/lang/System", "getProperties",
                "()Ljava/util/Properties;");
        int acceptRef = cf.addInterfaceMethodref("java/util/function/Consumer", "accept",
                "(Ljava/lang/Object;)V");
        int propsGet = cf.addMethodref("java/util/Properties", "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;");
        o.u1(0xB8).u2(getProperties);              

        // 大类的索引很容易超过 255 —— MinecraftServer 实测就有 4001 项。
        // 超过时必须换 ldc_w（0x13，u2 索引），否则要么编码错位、要么只能放弃注入。
        // 实测教训：早先这里直接抛异常"跳过注入"，结果服务端主类 MinecraftServer
        // 的钩子被静默跳过 —— 服务端照样能启动，只是跨端入口点永不生效，
        // 日志里只有一行容易被忽略的"变换失败，已跳过"。
        // 所以"前缀栈中立且无分支"这个不变量不受影响，不需要重算栈图。
        o.u1(propRef > 255 ? 0x13 : 0x12);
        if (propRef > 255) {
            o.u2(propRef);                         
        } else {
            o.u1(propRef);                         
        }
        o.u1(0xB6).u2(propsGet);                   

                                                   //    registerDispatch 保证预先装入

        o.u1(0xC0).u2(cf.addClass("java/util/function/Consumer"));   
        o.u1(0x5F);                                
        // 到这里栈是 [c, self]，正好是 accept(Object) 需要的顺序。
        o.u1(0xB9).u2(acceptRef).u1(0x02).u1(0x00); 
        return o.toByteArray();
    }

    /**
     * "什么都没注册"时用的兜底消费者。
     *
     * <p>它存在的意义是<b>让派发前缀可以是纯直线代码</b>：一旦允许 "{@code get} 返回 null
     * 就跳过"，就得多一条 {@code ifnull} 分支，而分支目标在 JVM 校验里需要一个
     * StackMapTable 帧。本注入器的整个正确性论证建立在"前缀是栈中立且无分支，
     * 因此原有栈图只需整体平移"之上 —— 加分支会直接推翻它。
     * 实测确认过：加 {@code ifnull} 后立刻
     * {@code VerifyError: Expecting a stackmap frame at branch target}。
     *
     * <p>这个类是 bootstrap 可见的 {@code Consumer} 的一个实现，且<b>不引用任何
     * FusionML 的其它类</b> —— 因为它在宿主（可能由 bundler 的加载器加载）里执行，
     * 引用我们的类会再次触发 {@code NoClassDefFoundError}。
     */
    public static final class Noop implements java.util.function.Consumer<Object> {
        @Override
        public void accept(Object ignored) {
        }
    }
}
