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

    /** 注入点。 */
    public enum Point {
        /** 方法体第一条指令之前。 */
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
    }

    /** 注入结果。 */
    public record Result(byte[] bytes, int injectedMethods, List<String> notes) {
        public boolean changed() {
            return injectedMethods > 0;
        }
    }

    private HookInjector() {
    }

    /**
     * 依据目标方法描述符推导出钩子应有的描述符。
     * 实例方法会多传第一个参数 {@code this}（类型就是目标类本身）。
     */
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
            int hookRef = cf.addMethodref(hook.hookOwner(), hook.hookName(), hook.hookDesc());
            for (ClassFile.Member m : cf.methods()) {
                if (!hook.targetMethod().equals(cf.utf8(m.nameIndex))) {
                    continue;
                }
                String desc = cf.utf8(m.descIndex);
                if (hook.targetDesc() != null && !hook.targetDesc().equals(desc)) {
                    continue;
                }
                if ((m.access & 0x0400) != 0 || (m.access & 0x0100) != 0) {
                    continue;                     // abstract / native 没有方法体
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
                // 例如实例方法 before(Target self, int a)，其期望描述符是 (Ltarget/Cls;I)V。
                // 不一致就跳过并说明，而不是生成一个运行期必然 VerifyError 的类。
                String expected = hookDesc(self, desc, isStatic);
                if (!expected.equals(hook.hookDesc())
                        && !Descriptors.acceptsParams(expected, hook.hookDesc())) {
                    notes.add("钩子签名不匹配，已跳过：" + self + "#" + hook.targetMethod() + desc
                            + " 期望 " + expected + " 实际 " + hook.hookDesc());
                    continue;
                }
                MethodCode mc = MethodCode.parse(classBytes, codeAttr.offset(), codeAttr.length());
                byte[] prefix = buildPrefix(cf, desc, isStatic, hookRef);
                int neededStack = (isStatic ? 0 : 1) + Descriptors.paramSlots(desc);
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
        // 少了这一步，一旦写出结构有问题的字节，JVM 会在 defineClass 时直接
        // ClassFormatError 崩掉整个游戏 —— 而报错信息指向的是游戏类，
        // 完全看不出是加载器写坏的（我们正是这么崩过一次：
        // ClassFormatError: Illegal class name "com.fusionml/hud/FusionMainPage"
        // in class file net/minecraft/client/gui/screens/TitleScreen）。
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

    /** 生成"压栈 → 调钩子"的字节码前缀。 */
    private static byte[] buildPrefix(ClassFile cf, String methodDesc, boolean isStatic, int hookRef) {
        ByteOut o = new ByteOut(16);
        int slot = 0;
        if (!isStatic) {
            o.u1(0x2A);                            // aload_0
            slot = 1;
        }
        for (String t : Descriptors.paramTypes(methodDesc)) {
            int opcode = switch (t.charAt(0)) {
                case 'L', '[' -> 0x19;             // aload
                case 'J' -> 0x16;                  // lload
                case 'F' -> 0x17;                  // fload
                case 'D' -> 0x18;                  // dload
                default -> 0x15;                   // iload
            };
            if (slot > 255) {
                throw new IllegalStateException("参数槽位超过 255，需要 wide 指令（当前未支持）");
            }
            o.u1(opcode).u1(slot);
            slot += Descriptors.slots(t);
        }
        o.u1(0xB8);                                // invokestatic
        o.u2(hookRef);
        return o.toByteArray();
    }
}
