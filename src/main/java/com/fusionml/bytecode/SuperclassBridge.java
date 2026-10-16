package com.fusionml.bytecode;

import java.io.ByteArrayOutputStream;

/**
 * 超类桥接：把子类 {@code <init>} 里"调用对方超类构造器"的指令改成调用
 * {@code java/lang/Object} 的，并把桥接带入的实例字段初始化为默认值。
 *
 * <h2>它解决的问题</h2>
 *
 * <p>对方平台（NeoForge）有些补丁是靠**换超类**实现的，而宿主模组的 Mixin 又打在
 * 同一批类上。两个选项都是硬崩，实测各撞一次：
 *
 * <ul>
 *   <li>用对方字节 → 宿主 Mixin 注入点匹配 0 个目标
 *       （{@code c2me} 的 {@code MixinWorld} 打在 {@code Level} 上 → {@code Scanned 0 target(s)}）；</li>
 *   <li>保留原版 → 对方按新超类使用它，校验期失败
 *       （{@code RegistryBuilder.create()} → {@code VerifyError: ... not assignable to ...}）。</li>
 * </ul>
 *
 * <p>桥接是第三条路：**让原版类自己长出对方超类的能力**。代价是要动
 * {@code <init>} 里的那一处超类构造器调用 —— 否则 JVM 会因为
 * "类声明的超类是 Object、却 invokespecial 到别的类"而拒绝加载
 * （{@code VerifyError: Bad &lt;init&gt; method call}）。
 *
 * <h2>改动有多小</h2>
 *
 * <p>只改 {@code <init>}，**不动任何其他方法一个字节** —— 这正是它能与宿主模组
 * 共存的原因：Mixin 的注入点是按原版方法的指令序列匹配的，那些方法原封不动。
 *
 * <p>具体两步：
 * <ol>
 *   <li>把 {@code invokespecial <对方超类>.<init>()V} 的常量池引用换成
 *       {@code invokespecial java/lang/Object.<init>()V}；</li>
 *   <li>在它之后插入"把桥接字段置默认值"的指令
 *       （引用类型 {@code aconst_null}，数值类型 {@code iconst_0}）。</li>
 * </ol>
 *
 * <h2>为什么必须重算 max_stack / max_locals</h2>
 *
 * <p>插入指令会改变栈深。这里只在"原来就已经有 {@code invokespecial} 之后"插入，
 * 且插入的是"压栈一个常量再消费掉"的定长序列，所以只需要把
 * {@code max_stack} 至少抬到所需深度，不涉及跳转偏移（不新增分支）。
 */
public final class SuperclassBridge {

    private SuperclassBridge() {
    }

    /**
     * 改写子类的所有 {@code <init>}，把对方超类构造器调用换成 Object 的，
     * 并补上桥接字段的默认值初始化。
     *
     * @param cf        目标类（已搬入对方超类的字段与方法）
     * @param internal  目标类的内部名
     * @param newSuper  被桥接掉的超类内部名
     * @param ctorPlan  形如 {@code "attachments:Ljava/util/Map;,x:I"} 的字段清单
     * @return 是否成功（false 表示调用方应放弃桥接）
     */
    public static boolean rewrite(ClassFile cf, String internal, String newSuper, String ctorPlan) {        try {
            int objectInit = cf.cpIndexForMethodref("java/lang/Object", "<init>", "()V");
            if (objectInit <= 0) {
                return false;
            }
            int patched = 0;
            for (ClassFile.Member m : cf.methods()) {
                if (!"<init>".equals(cf.utf8(m.nameIndex))) {
                    continue;
                }
                byte[] code = cf.codeOf(m);
                if (code == null) {
                    continue;
                }
                byte[] rebuilt = rewriteInit(cf, code, newSuper, objectInit, ctorPlan);
                if (rebuilt == null) {
                    continue;
                }
                cf.overrideCode(m, rebuilt);
                patched++;
            }
            return patched > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 重写一条 {@code <init>} 的字节码。
     *
     * @return 新字节码；不需要/不能改时返回 {@code null}
     */
    private static byte[] rewriteInit(ClassFile cf, byte[] code, String newSuper,
                                      int objectInit, String ctorPlan) {

        int target = -1;
        int p = 0;
        while (p < code.length) {
            int op = code[p] & 0xFF;
            if (op == 0xB7 && p + 2 < code.length) {
                int idx = ((code[p + 1] & 0xFF) << 8) | (code[p + 2] & 0xFF);
                if (cf.cpIndexIsMethodrefOf(idx, newSuper, "<init>", "()V")) {
                    target = p;
                    break;
                }
            }
            int len = ConstantPoolTransplant.instructionLengthPublic(code, p, op, code.length);
            if (len <= 0) {
                return null;                      
            }
            p += len;
        }
        if (target < 0) {
            return null;                          
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(code.length + 32);
        out.write(code, 0, target);

        out.write(0xB7);
        out.write((objectInit >>> 8) & 0xFF);
        out.write(objectInit & 0xFF);

        int inserted = 0;
        if (ctorPlan != null && !ctorPlan.isEmpty()) {
            for (String f : ctorPlan.split(",")) {
                int colon = f.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String name = f.substring(0, colon);
                String desc = f.substring(colon + 1);
                int fieldRef = cf.cpIndexForFieldref(cf.selfName(), name, desc);
                if (fieldRef <= 0) {
                    continue;                     
                }
                out.write(0x2A);                  
                switch (desc.charAt(0)) {
                    case 'L':
                    case '[':
                        out.write(0x01);          
                        break;
                    case 'J':
                        out.write(0x09);          
                        break;
                    case 'D':
                        out.write(0x0E);          
                        break;
                    case 'F':
                        out.write(0x0B);          
                        break;
                    default:
                        out.write(0x03);          
                        break;
                }
                out.write(0xB5);                  
                out.write((fieldRef >>> 8) & 0xFF);
                out.write(fieldRef & 0xFF);
                inserted++;
            }
        }
        out.write(code, target + 3, code.length - target - 3);
        if (inserted > 0) {

            cf.raiseMaxStack(2);
        }
        return out.toByteArray();
    }
}
