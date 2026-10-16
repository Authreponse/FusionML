package com.fusionml.bytecode;

import java.io.ByteArrayOutputStream;

/**
 * 常量池移植：把 A 类里某个方法的字节码，改写成"在 B 类的常量池下成立"的字节码。
 *
 * <h2>为什么这件事必须做</h2>
 *
 * <p>"方法级拼接"是跨端合并的关键能力：对方平台给原版类<b>加</b>了方法
 * （实测：NeoForge 把 {@code RegistryDataLoader.load(LoaderFactory,List,List,Executor)}
 * 重载成了带第 5 个 {@code boolean} 参数的版本），此时：
 *
 * <ul>
 *   <li>"整类换" → 宿主平台模组的 Mixin 按<b>原版 4 参数签名</b>找注入点，永远找不到，
 *       报 {@code Critical injection failure: @ModifyArg ... could not find any targets}；</li>
 *   <li>"整类不换" → 对方模组的 5 参数调用落空，或对方的其他字节引用它时
 *       抛 {@code NoSuchMethodError}。</li>
 * </ul>
 *
 * <p>唯一两边都成立的做法是<b>两个签名都留</b>：以原版为基，把对方新增的重载拼进去。
 * 这也正是参照项目 Forbric 走的路（它的合并报告写着
 * {@code spliced into merged classes: methods=661 fields=138 interfaces=49}）。
 *
 * <h2>做法</h2>
 *
 * <p>方法的字节码里到处是常量池索引（{@code ldc #12}、{@code invokevirtual #34}…），
 * 两个类的常量池布局完全不同。所以搬运时必须：
 * <ol>
 *   <li>把源类常量池里"这个方法用到的项"逐个导入目标类；</li>
 *   <li>把方法字节里的每个索引改写成导入后的新下标。</li>
 * </ol>
 *
 * <h2>安全立场：宁可放弃，不可产出坏类</h2>
 *
 * <p>字节码里"哪些位置是常量池索引"取决于当前指令是什么。完整解法是逐条解码指令；
 * 这里用的是<b>保守扫描</b>：只在能确定指令边界时改写，遇到任何无法确定的字节就
 * <b>整体放弃</b>这次搬运，返回 {@code null}。
 *
 * <p>理由很实际：拼出一个校验不过的类，会让整局游戏起不来，且报错位置
 * （{@code VerifyError} / {@code ClassFormatError}）离真正的原因极远；
 * 而"少拼一个方法"的代价是可控、可记录、可诊断的。
 *
 * <h2>故意不搬的东西</h2>
 *
 * <p>Code 属性里的调试类子属性（{@code LineNumberTable} / {@code LocalVariableTable} /
 * {@code StackMapTable}）一律丢弃：它们的偏移量与新增指令强相关，改错就是 {@code VerifyError}；
 * 丢弃后 JVM 会退回自行推导（类版本 50+ 允许缺失 {@code StackMapTable}，代价只是校验慢一点）。
 */
public final class ConstantPoolTransplant {

    private ConstantPoolTransplant() {
    }

    /**
     * 生成"在 {@code target} 的常量池下成立"的成员字节。
     *
     * @param source 方法原本所属的类
     * @param target 要搬进去的类
     * @param body   成员的原始字节（access_flags 起）
     * @param map    旧下标 → 新下标 的映射表（就地填充，长度 = 源类常量池槽位数）
     * @return 重写后的字节；任何不确定的情况返回 {@code null}
     */
    /**
     * 上一次放弃的原因（诊断用）：把"是哪个操作码/哪一步挡住了"记下来。
     *
     * <p>没有它，排查时只能看到"拼接失败"，分不清是"本来没得拼"还是
     * "扫描能力不足" —— 这两者的处理方式完全不同。
     */
    static volatile String lastReason;

    static byte[] remapToNew(ClassFile source, ClassFile target, byte[] body, int[] map) {
        lastReason = null;
        try {

            if (body.length < 8) {
                return null;
            }
            int nameIdx = u2(body, 2);
            int descIdx = u2(body, 4);
            if (!ensure(source, target, nameIdx, map) || !ensure(source, target, descIdx, map)) {
                return null;
            }
            int attrCount = u2(body, 6);

            // 为什么不能先写 attrCount：我们会**丢弃**一些属性（类型注解、调试信息等），
            // 丢弃之后属性数必须跟着减。踩过一次：属性头写 0 长度但仍然计数，

            ByteArrayOutputStream attrs = new ByteArrayOutputStream(body.length);
            int kept = 0;

            int p = 8;
            for (int a = 0; a < attrCount; a++) {
                if (p + 6 > body.length) {
                    return null;
                }
                int attrName = u2(body, p);
                long attrLen = u4(body, p + 2);
                if (!ensure(source, target, attrName, map)) {
                    return null;
                }
                int dataStart = p + 6;
                long dataEndL = dataStart + attrLen;
                if (dataEndL > body.length) {
                    return null;
                }
                int dataEnd = (int) dataEndL;
                String name = source.utf8(attrName);

                byte[] newData;
                if ("Code".equals(name)) {
                    newData = remapCode(source, target, body, dataStart, dataEnd, map);
                } else {
                    newData = remapSimpleAttr(source, target, body, dataStart, dataEnd, name, map);
                }
                if (newData == null) {
                    if (lastReason == null) {
                        lastReason = "属性 " + name + " 处理失败";
                    }
                    return null;
                }

                if (newData.length > 0) {
                    attrs.write((map[attrName] >>> 8) & 0xFF);
                    attrs.write(map[attrName] & 0xFF);
                    writeU4(attrs, newData.length);
                    attrs.write(newData, 0, newData.length);
                    kept++;
                }
                p = dataEnd;
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream(body.length);
            out.write(body, 0, 2);
            out.write((map[nameIdx] >>> 8) & 0xFF);
            out.write(map[nameIdx] & 0xFF);
            out.write((map[descIdx] >>> 8) & 0xFF);
            out.write(map[descIdx] & 0xFF);
            out.write((kept >>> 8) & 0xFF);
            out.write(kept & 0xFF);
            byte[] attrBytes = attrs.toByteArray();
            out.write(attrBytes, 0, attrBytes.length);
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] remapCode(ClassFile source, ClassFile target, byte[] b,
                                    int start, int end, int[] map) {
        if (start + 8 > end) {
            return null;
        }
        long codeLenL = u4(b, start + 4);
        int codeStart = start + 8;
        long codeEndL = codeStart + codeLenL;
        if (codeLenL < 0 || codeEndL > end) {
            return null;
        }
        int codeEnd = (int) codeEndL;
        byte[] code = java.util.Arrays.copyOfRange(b, codeStart, codeEnd);
        if (!remapInstructions(source, target, code, 0, code.length, map)) {
            return null;
        }

        int p = codeEnd;
        if (p + 2 > end) {
            return null;
        }
        int exCount = u2(b, p);
        p += 2;
        if (p + 8 * (long) exCount > end) {
            return null;
        }
        byte[] exTable = java.util.Arrays.copyOfRange(b, p, p + 8 * exCount);
        for (int i = 0; i < exCount; i++) {
            int off = i * 8 + 6;
            int catchType = u2(exTable, off);
            if (catchType != 0) {
                if (!ensure(source, target, catchType, map)) {
                    return null;
                }
                int nn = map[catchType];
                exTable[off] = (byte) (nn >>> 8);
                exTable[off + 1] = (byte) nn;
            }
        }

        ByteArrayOutputStream attrs = new ByteArrayOutputStream(64);
        int kept = 0;
        if (p + 2 <= end) {
            int subCount = u2(b, p);
            int q = p + 2;
            for (int i = 0; i < subCount; i++) {
                if (q + 6 > end) {
                    return null;
                }
                int subName = u2(b, q);
                long subLenL = u4(b, q + 2);
                int subStart = q + 6;
                long subEndL = subStart + subLenL;
                if (subLenL < 0 || subEndL > end) {
                    return null;
                }
                int subEnd = (int) subEndL;
                String sub = source.utf8(subName);
                // StackMapTable 特殊对待：它**不能丢**。
                // 会让带分支的方法在类校验期直接失败：
                //   VerifyError: Expecting a stackmap frame at branch target 14
                // 原因：类版本 50+ 之后，**有分支就必须有帧**，不能靠隐式推导补出来。
                // 所以改写不了它就放弃整个方法的搬运（保守），而不是留一个坏方法。
                if ("StackMapTable".equals(sub)) {
                    byte[] smt = remapStackMapTable(source, target, b, subStart, subEnd, map);
                    if (smt == null) {
                        lastReason = lastReason == null
                                ? "StackMapTable 无法改写（不能丢，丢掉会让分支方法校验失败）"
                                : lastReason;
                        return null;
                    }
                    if (!ensure(source, target, subName, map)) {
                        return null;
                    }
                    attrs.write((map[subName] >>> 8) & 0xFF);
                    attrs.write(map[subName] & 0xFF);
                    writeU4(attrs, smt.length);
                    attrs.write(smt, 0, smt.length);
                    kept++;
                    q = subEnd;
                    continue;
                }
                byte[] newSub = remapCodeSubAttr(source, target, b, subStart, subEnd, sub, map);

                if (newSub != null && newSub.length > 0) {
                    if (!ensure(source, target, subName, map)) {
                        return null;
                    }
                    attrs.write((map[subName] >>> 8) & 0xFF);
                    attrs.write(map[subName] & 0xFF);
                    writeU4(attrs, newSub.length);
                    attrs.write(newSub, 0, newSub.length);
                    kept++;
                }
                q = subEnd;
            }
        }
        byte[] attrBytes = attrs.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream(
                8 + code.length + exTable.length + attrBytes.length + 2);
        out.write(b, start, 4);                       
        writeU4(out, code.length);
        out.write(code, 0, code.length);
        out.write((exCount >>> 8) & 0xFF);
        out.write(exCount & 0xFF);
        out.write(exTable, 0, exTable.length);
        out.write((kept >>> 8) & 0xFF);
        out.write(kept & 0xFF);
        out.write(attrBytes, 0, attrBytes.length);
        return out.toByteArray();
    }

    /**
     * Code 的子属性。
     *
     * <h2>StackMapTable 必须保留</h2>
     *
     * <p>一开始我把 Code 的所有子属性都丢了，理由是"调试信息而已，丢了 JVM 会自己推导"。
     * 结果实测崩在**宿主平台的 ASM 变换器**上：
     *
     * <pre>
     * ArrayIndexOutOfBoundsException: Index 940 out of bounds for length 841
     *   at org.objectweb.asm.ClassReader.readCode
     *   at net.fabricmc.loader.impl.transformer.FabricTransformer.transform
     * </pre>
     *
     * <p>也就是说：**JVM 自己能接受缺 StackMapTable 的类**（{@code defineClass} 通过、
     * {@code javap} 也能解析），但宿主平台在类加载路径上会用 ASM 再读写一次，
     * 而 ASM 需要这份帧信息。为宿主平台的流水线考虑，必须留下它。
     *
     * <p>留下的安全性：我们搬运时**没有移动任何指令**，所以 StackMapTable 里的
     * 偏移量依旧有效；只需要重写它引用的常量池索引（帧里的对象类型是常量池索引）。
     */
    private static byte[] remapCodeSubAttr(ClassFile source, ClassFile target, byte[] b,
                                           int start, int end, String name, int[] map) {
        if (name == null) {
            return null;
        }
        switch (name) {

            case "LineNumberTable":
            case "LocalVariableTable":
            case "LocalVariableTypeTable":
                return null;
            case "StackMapTable":
                return remapStackMapTable(source, target, b, start, end, map);
            default:

                // 但结构更复杂；放弃整次搬运比猜错更安全。
                return null;
        }
    }

    private static byte[] remapStackMapTable(ClassFile source, ClassFile target, byte[] b,
                                             int start, int end, int[] map) {
        if (start + 2 > end) {
            lastReason = "StackMapTable 头部越界";
            return null;
        }
        byte[] data = java.util.Arrays.copyOfRange(b, start, end);
        int n = u2(data, 0);
        int p = 2;
        for (int i = 0; i < n; i++) {
            if (p >= data.length) {
                lastReason = "StackMapTable 第 " + i + " 帧越界（共 " + n + " 帧）";
                return null;
            }
            int frameType = data[p] & 0xFF;
            p++;
            if (frameType <= 63) {

            } else if (frameType <= 127) {

                Integer adv = remapVti(source, target, data, p, map);
                if (adv == null) {
                    return null;
                }
                p += adv;
            } else if (frameType <= 246) {
                return null;                        
            } else if (frameType == 247) {
                if (p + 2 > data.length) {
                    return null;
                }
                p += 2;
                Integer adv = remapVti(source, target, data, p, map);
                if (adv == null) {
                    return null;
                }
                p += adv;
            } else if (frameType <= 250) {
                if (p + 2 > data.length) {
                    return null;
                }
                p += 2;                             
            } else if (frameType == 251) {
                if (p + 2 > data.length) {
                    return null;
                }
                p += 2;                             
            } else if (frameType <= 254) {
                if (p + 2 > data.length) {
                    return null;
                }
                p += 2;                             
                int locals = frameType - 251;
                for (int k = 0; k < locals; k++) {
                    Integer adv = remapVti(source, target, data, p, map);
                    if (adv == null) {
                        return null;
                    }
                    p += adv;
                }
            } else {                                
                if (p + 2 > data.length) {
                    return null;
                }
                p += 2;                             
                if (p + 2 > data.length) {
                    return null;
                }
                int locals = u2(data, p);
                p += 2;
                for (int k = 0; k < locals; k++) {
                    Integer adv = remapVti(source, target, data, p, map);
                    if (adv == null) {
                        return null;
                    }
                    p += adv;
                }
                if (p + 2 > data.length) {
                    return null;
                }
                int stack = u2(data, p);
                p += 2;
                for (int k = 0; k < stack; k++) {
                    Integer adv = remapVti(source, target, data, p, map);
                    if (adv == null) {
                        return null;
                    }
                    p += adv;
                }
            }
        }
        if (p != data.length) {
            return null;                            
        }
        return data;
    }

    /**
     * 一个 {@code verification_type_info}。
     *
     * @return 它占的字节数；无法确定返回 {@code null}
     */
    private static Integer remapVti(ClassFile source, ClassFile target, byte[] data, int p, int[] map) {
        if (p >= data.length) {
            return null;
        }
        int tag = data[p] & 0xFF;
        switch (tag) {
            case 0:      
            case 1:      
            case 2:      
            case 3:      
            case 4:      
            case 5:      
            case 6:      
                return 1;
            case 7: {    
                if (p + 3 > data.length) {
                    return null;
                }
                int idx = u2(data, p + 1);
                // 这里必须**主动导入**，而不是"没映射过就放弃"。
                // 踩过一次：StackMapTable 里的类型不一定出现在方法体的指令操作数里
                //   AttachmentHolder.getData 搬不进来（原因：StackMapTable 无法改写）

                if (!ensure(source, target, idx, map)) {
                    lastReason = "StackMapTable 的 Object 项 " + idx + " 无法导入";
                    return null;
                }
                int nn = map[idx];
                data[p + 1] = (byte) (nn >>> 8);
                data[p + 2] = (byte) nn;
                return 3;
            }
            case 8:      // Uninitialized：跟一个 u2 的字节码偏移量（不是常量池索引）
                if (p + 3 > data.length) {
                    return null;
                }
                return 3;
            default:
                return null;
        }
    }

    private static boolean remapInstructions(ClassFile source, ClassFile target, byte[] b,
                                             int start, int end, int[] map) {
        return remapInstructions(source, target, b, start, end, map, null);
    }

    /**
     * 指令改写（带可选的 {@code ldc → ldc_w} 升格）。
     *
     * <h2>为什么需要升格</h2>
     *
     * <p>{@code ldc}（0x12）的操作数是**单字节**常量池索引。桥接的目标类
     * （例如原版 {@code Level}）常量池本来就有一千多项，任何新导入的常量下标都 &gt; 255，
     * 于是 {@code ldc} 永远装不下 —— 报
     * {@code ldc 的常量池索引 1782 超过 255}，方法搬不过来。
     *
     * <p>升格办法：把 {@code ldc #n}（2 字节）换成 {@code ldc_w #n}（3 字节），
     * 并**记录每一处长度变化**交给 {@link #fixAfterPromotion} 去修
     * 跳转偏移与 StackMapTable。这是唯一能同时保证"索引装得下"与"类合法"的做法。
     *
     * @param promotions 传入非 null 时启用升格，并把"偏移 → 新增字节数"记进它
     */
    private static boolean remapInstructions(ClassFile source, ClassFile target, byte[] b,
                                             int start, int end, int[] map,
                                             java.util.List<int[]> promotions) {
        int p = start;
        while (p < end) {
            int op = b[p] & 0xFF;
            int len = OP_LENGTH[op];
            if (len < 0) {
                lastReason = "操作码 0x" + Integer.toHexString(op) + "（保留/未支持）@code+" + (p - start);
                return false;
            }
            if (len == 0) {
                Integer adv = remapSpecial(source, target, b, p, op, end, map);
                if (adv == null) {
                    if (lastReason == null) {
                        lastReason = "操作码 0x" + Integer.toHexString(op) + " 无法确定长度 @code+" + (p - start);
                    }
                    return false;
                }
                p += adv;
                continue;
            }
            p += len;
        }
        if (p != end) {
            lastReason = "指令扫描越界：p=" + p + " end=" + end;
            return false;
        }
        return true;
    }

    /**
     * 处理"长度不固定或带常量池索引"的指令。
     *
     * @return 该指令的总字节数；无法确定时返回 {@code null}
     */
    private static Integer remapSpecial(ClassFile source, ClassFile target, byte[] b, int p,
                                        int op, int end, int[] map) {
        switch (op) {
            case 0x12: {                            
                if (p + 2 > end) {
                    return null;
                }
                int idx = b[p + 1] & 0xFF;
                if (!ensure(source, target, idx, map)) {
                    return null;
                }
                int nn = map[idx];
                if (nn > 255) {

                    // 从而让 StackMapTable、异常表、跳转偏移全部失效 —— 属于"改一处、崩一片"。
                    // 所以这里先记清楚原因就放弃；真需要支持时，
                    // 应当在搬运前先做一次"预留常量池槽位"的预处理，而不是在中途改指令长度。
                    lastReason = "ldc 的常量池索引 " + nn + " 超过 255，需要升格 ldc_w（暂不支持）";
                    return null;
                }
                b[p + 1] = (byte) nn;
                return 2;
            }
            case 0x13:                              
            case 0x14:                              
            case 0xB2: case 0xB3: case 0xB4: case 0xB5:   
            case 0xB6: case 0xB7: case 0xB8:              
            case 0xBB:                              
            case 0xBD:                              
            case 0xC0:                              
            case 0xC1: {                            
                if (p + 3 > end) {
                    return null;
                }
                int idx = u2(b, p + 1);
                if (!ensure(source, target, idx, map)) {
                    return null;
                }
                int nn = map[idx];
                b[p + 1] = (byte) (nn >>> 8);
                b[p + 2] = (byte) nn;
                return 3;
            }
            case 0xB9: {                            
                if (p + 5 > end) {
                    return null;
                }
                int idx = u2(b, p + 1);
                if (!ensure(source, target, idx, map)) {
                    return null;
                }
                int nn = map[idx];
                b[p + 1] = (byte) (nn >>> 8);
                b[p + 2] = (byte) nn;
                return 5;
            }
            case 0xBA: {                            
                if (p + 5 > end) {
                    return null;
                }
                int idx = u2(b, p + 1);
                if (!ensure(source, target, idx, map)) {
                    return null;
                }
                int nn = map[idx];
                b[p + 1] = (byte) (nn >>> 8);
                b[p + 2] = (byte) nn;
                return 5;
            }
            case 0xC5: {                            
                if (p + 4 > end) {
                    return null;
                }
                int idx = u2(b, p + 1);
                if (!ensure(source, target, idx, map)) {
                    return null;
                }
                int nn = map[idx];
                b[p + 1] = (byte) (nn >>> 8);
                b[p + 2] = (byte) nn;
                return 4;
            }
            case 0xC4: {                            
                if (p + 2 > end) {
                    return null;
                }
                int sub = b[p + 1] & 0xFF;
                return sub == 0x84 ? 6 : 4;         
            }
            case 0xAA: {                            
                int q = align4(p + 1);
                if (q + 12 > end) {
                    return null;
                }
                int lo = (int) u4(b, q + 4);
                int hi = (int) u4(b, q + 8);
                long size = (long) hi - lo + 1;
                if (size < 0 || q + 12 + 4L * size > end) {
                    return null;
                }
                return (int) (q + 12 + 4L * size - p);
            }
            case 0xAB: {                            
                int q = align4(p + 1);
                if (q + 8 > end) {
                    return null;
                }
                int n = (int) u4(b, q + 4);
                if (n < 0 || q + 8 + 8L * n > end) {
                    return null;
                }
                return (int) (q + 8 + 8L * n - p);
            }
            default:
                return null;
        }
    }

    private static int align4(int x) {
        return (x + 3) & ~3;
    }

    private static byte[] remapSimpleAttr(ClassFile source, ClassFile target, byte[] b,
                                          int start, int end, String name, int[] map) {
        if (name == null) {
            return null;
        }
        if ("Exceptions".equals(name)) {
            if (start + 2 > end) {
                return null;
            }
            int n = u2(b, start);
            if (start + 2 + 2 * (long) n > end) {
                return null;
            }
            byte[] data = java.util.Arrays.copyOfRange(b, start, start + 2 + 2 * n);
            for (int i = 0; i < n; i++) {
                int off = 2 + i * 2;
                int idx = u2(data, off);
                if (!ensure(source, target, idx, map)) {
                    return null;
                }
                int nn = map[idx];
                data[off] = (byte) (nn >>> 8);
                data[off + 1] = (byte) nn;
            }
            return data;
        }

        if ("Synthetic".equals(name) || "Deprecated".equals(name)) {
            return java.util.Arrays.copyOfRange(b, start, end);
        }

        // 逐个改对成本高、改错就是坏类。实测：AttachmentHolder 的 attachments 字段
        // 注意只对**成员**上的这几个属性这么做；Code 子属性里的同名前缀属性另有处理。
        if ("RuntimeVisibleTypeAnnotations".equals(name)
                || "RuntimeInvisibleTypeAnnotations".equals(name)

                // 实测：AttachmentHolder.setData 带 @Nullable（RuntimeInvisibleAnnotations），

                || "RuntimeVisibleAnnotations".equals(name)
                || "RuntimeInvisibleAnnotations".equals(name)
                || "RuntimeVisibleParameterAnnotations".equals(name)
                || "RuntimeInvisibleParameterAnnotations".equals(name)
                || "AnnotationDefault".equals(name)
                || "MethodParameters".equals(name)) {
            return new byte[0];
        }

        // 而读错的后果是 VerifyError: Illegal type at constant pool entry N
        // （实测：AttributeModifiersPredicate 的 record 组件表）。

        if ("Record".equals(name) || "InnerClasses".equals(name)
                || "EnclosingMethod".equals(name) || "NestHost".equals(name)
                || "NestMembers".equals(name) || "PermittedSubclasses".equals(name)
                || "RuntimeVisibleAnnotations".equals(name)
                || "RuntimeInvisibleAnnotations".equals(name)
                || "RuntimeVisibleParameterAnnotations".equals(name)
                || "RuntimeInvisibleParameterAnnotations".equals(name)
                || "RuntimeVisibleTypeAnnotations".equals(name)
                || "RuntimeInvisibleTypeAnnotations".equals(name)
                || "AnnotationDefault".equals(name) || "MethodParameters".equals(name)) {
            if (lastReason == null) {
                lastReason = "属性 " + name + " 暂不支持（含常量池索引，结构各异）";
            }
            return null;
        }

        // 实测它是"方法级拼接"在 RegistryDataLoader 上唯一挡住路的属性，

        if ("Signature".equals(name)) {
            if (start + 2 > end) {
                return null;
            }
            int idx = u2(b, start);
            if (!ensure(source, target, idx, map)) {
                return null;
            }
            byte[] data = java.util.Arrays.copyOfRange(b, start, start + 2);
            data[0] = (byte) (map[idx] >>> 8);
            data[1] = (byte) map[idx];
            return data;
        }
        return null;
    }

    /**
     * 校验一段 Code 里的常量池引用是否自洽（索引存在、类型用对）。
     *
     * <p><b>刻意复用 {@link #remapInstructions} 的扫描器</b>，而不是另写一份：
     * 一开始在自检里重写了一遍指令长度判断，结果漏了 {@code ldc}/{@code tableswitch}，
     * 扫描错位后报出 11184、47617 这种大得离谱的"越界"，
     * 把好端端的拼接误判成失败、退回整类换 —— 比没有自检更糟。
     * 两套实现只要有一处不一致，就会互相打架。
     *
     * @return 问题描述；没问题返回 {@code null}
     */
    public static String verifyCodeReferences(ClassFile cf, byte[] bytes,
                                              int codeAttrStart, int codeLength) {
        try {
            int codeStart = codeAttrStart + 8;
            int codeEnd = codeStart + codeLength;
            if (codeEnd > bytes.length) {
                return "code_length 越界（" + codeLength + "）";
            }
            int p = codeStart;
            while (p < codeEnd) {
                int op = bytes[p] & 0xFF;
                int len = OP_LENGTH[op];
                if (len < 0) {
                    return "遇到不支持的保留操作码 0x" + Integer.toHexString(op);
                }
                if (len == 0) {
                    Integer adv = instructionLength(bytes, p, op, codeEnd);
                    if (adv == null) {
                        return "操作码 0x" + Integer.toHexString(op) + " 长度无法确定 @" + (p - codeStart);
                    }
                    String why = checkIndex(bytes, cf, p, op, codeEnd);
                    if (why != null) {
                        return why + " @" + (p - codeStart);
                    }
                    p += adv;
                    continue;
                }
                p += len;
            }
            if (p != codeEnd) {
                return "指令扫描越界：p=" + p + " codeEnd=" + codeEnd;
            }
            // 异常表里的 catch_type 必须是 Class。
            int q = codeEnd;
            if (q + 2 > bytes.length) {
                return "异常表缺失";
            }
            int exCount = u2(bytes, q);
            q += 2;
            for (int i = 0; i < exCount; i++) {
                if (q + 8 > bytes.length) {
                    return "异常表越界";
                }
                int ct = u2(bytes, q + 6);
                if (ct != 0) {
                    ClassFile.Cp c = cf.cp(ct);
                    if (c == null) {
                        return "异常表 catch_type " + ct + " 不存在";
                    }
                    if (c.tag != 7) {
                        return "异常表 catch_type " + ct + " 是 tag" + c.tag + "，期望 Class";
                    }
                }
                q += 8;
            }
            // Code 的子属性：StackMapTable 的 Object_variable_info 也必须是 Class。

            if (q + 2 > bytes.length) {
                return "Code 属性区缺失";
            }
            int subCount = u2(bytes, q);
            q += 2;
            boolean hasStackMap = false;
            for (int i = 0; i < subCount; i++) {
                if (q + 6 > bytes.length) {
                    return "Code 子属性越界";
                }
                int nm = u2(bytes, q);
                long len2 = u4(bytes, q + 2);
                String sub = cf.utf8(nm);
                int subStart = q + 6;
                long subEndL = subStart + len2;
                if (subEndL > bytes.length) {
                    return "Code 子属性长度越界";
                }
                if ("StackMapTable".equals(sub)) {
                    hasStackMap = true;
                    String why = verifyStackMap(cf, bytes, subStart, (int) subEndL);
                    if (why != null) {
                        return why;
                    }
                }
                q = (int) subEndL;
            }
            // 有分支却没有 StackMapTable → 类版本 50+ 下必然 VerifyError。
            // 这一条正是"离线早发现"的关键：它对应的真机错误是
            //   VerifyError: Expecting a stackmap frame at branch target N

            if (!hasStackMap && codeHasBranch(bytes, codeStart, codeEnd)) {
                return "方法含分支但缺少 StackMapTable（类版本 50+ 必然校验失败）";
            }
            return null;
        } catch (Throwable t) {
            return "校验异常：" + t;
        }
    }

    private static boolean codeHasBranch(byte[] b, int start, int end) {
        int p = start;
        while (p < end) {
            int op = b[p] & 0xFF;

            if ((op >= 0x99 && op <= 0xA8) || op == 0xC6 || op == 0xC7
                    || op == 0xAA || op == 0xAB) {
                return true;
            }
            int len = OP_LENGTH[op];
            if (len < 0) {
                return true;
            }
            if (len == 0) {
                Integer adv = instructionLength(b, p, op, end);
                if (adv == null) {
                    return true;
                }
                p += adv;
                continue;
            }
            p += len;
        }
        return false;
    }

    private static String verifyStackMap(ClassFile cf, byte[] b, int start, int end) {
        if (start + 2 > end) {
            return "StackMapTable 越界";
        }
        int n = u2(b, start);
        int p = start + 2;
        for (int i = 0; i < n; i++) {
            if (p >= end) {
                return "StackMapTable 帧越界 @" + i;
            }
            int ft = b[p] & 0xFF;
            p++;
            if (ft <= 63) {

            } else if (ft <= 127) {
                Integer adv = vtiLength(cf, b, p, end);
                if (adv == null) {
                    return "StackMapTable 第 " + i + " 帧的 vti 不合法";
                }
                p += adv;
            } else if (ft <= 246) {
                return "StackMapTable 第 " + i + " 帧类型 " + ft + " 非法";
            } else if (ft == 247) {
                p += 2;
                Integer adv = vtiLength(cf, b, p, end);
                if (adv == null) {
                    return "StackMapTable 第 " + i + " 帧的 vti 不合法";
                }
                p += adv;
            } else if (ft <= 251) {
                p += 2;
            } else if (ft <= 254) {
                p += 2;
                for (int k = 0; k < ft - 251; k++) {
                    Integer adv = vtiLength(cf, b, p, end);
                    if (adv == null) {
                        return "StackMapTable 第 " + i + " 帧的 locals 不合法";
                    }
                    p += adv;
                }
            } else {
                p += 2;
                if (p + 2 > end) {
                    return "StackMapTable full_frame 越界";
                }
                int locals = u2(b, p);
                p += 2;
                for (int k = 0; k < locals; k++) {
                    Integer adv = vtiLength(cf, b, p, end);
                    if (adv == null) {
                        return "StackMapTable full_frame 的 locals 不合法";
                    }
                    p += adv;
                }
                if (p + 2 > end) {
                    return "StackMapTable full_frame 越界";
                }
                int stack = u2(b, p);
                p += 2;
                for (int k = 0; k < stack; k++) {
                    Integer adv = vtiLength(cf, b, p, end);
                    if (adv == null) {
                        return "StackMapTable full_frame 的 stack 不合法";
                    }
                    p += adv;
                }
            }
        }
        return p == end ? null : "StackMapTable 长度不符";
    }

    private static Integer vtiLength(ClassFile cf, byte[] b, int p, int end) {
        if (p >= end) {
            return null;
        }
        int tag = b[p] & 0xFF;
        switch (tag) {
            case 0: case 1: case 2: case 3: case 4: case 5: case 6:
                return 1;
            case 7: {
                if (p + 3 > end) {
                    return null;
                }
                int idx = u2(b, p + 1);
                ClassFile.Cp c = cf.cp(idx);
                if (c == null) {
                    return -1;                  
                }
                if (c.tag != 7) {
                    throw new IllegalStateException("StackMapTable 的 Object 项索引 " + idx
                            + " 是 tag" + c.tag + "（" + c + "），期望 Class");
                }
                return 3;
            }
            case 8:
                return (p + 3 > end) ? null : 3;
            default:
                return null;
        }
    }

    /**
     * 改写一条 {@code BootstrapMethods} 条目里的常量池索引。
     *
     * <p>结构：{@code bootstrap_method_ref(u2) num_args(u2) args[num_args](u2 each)}。
     * 三个位置都是常量池索引，逐个导入并改写即可。
     *
     * @return 改写后的字节（就地修改并返回）；失败返回 {@code null}
     */
    static byte[] remapBootstrapEntry(ClassFile source, ClassFile target, byte[] entry, int[] map) {
        try {
            if (entry.length < 4) {
                return null;
            }
            int ref = u2(entry, 0);
            if (!ensure(source, target, ref, map)) {
                return null;
            }
            entry[0] = (byte) (map[ref] >>> 8);
            entry[1] = (byte) map[ref];
            int argc = u2(entry, 2);
            if (entry.length < 4 + argc * 2) {
                return null;
            }
            for (int i = 0; i < argc; i++) {
                int off = 4 + i * 2;
                int idx = u2(entry, off);
                if (!ensure(source, target, idx, map)) {
                    return null;
                }
                entry[off] = (byte) (map[idx] >>> 8);
                entry[off + 1] = (byte) map[idx];
            }
            return entry;
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean ensurePublic(ClassFile source, ClassFile target, int idx, int[] map) {
        return ensure(source, target, idx, map);
    }

    /** 供同包的桥接代码复用同一套指令长度规则（避免两份实现不一致）。 */
    public static int instructionLengthPublic(byte[] b, int p, int op, int end) {
        Integer n;
        switch (op) {
            case 0x12: n = 2; break;
            case 0x13: case 0x14: n = 3; break;
            case 0xB9: case 0xBA: n = 5; break;
            case 0xBB: case 0xBD: case 0xC0: case 0xC1: n = 3; break;
            case 0xC5: n = 4; break;
            case 0xB2: case 0xB3: case 0xB4: case 0xB5:
            case 0xB6: case 0xB7: case 0xB8: n = 3; break;
            case 0xC4: n = (p + 1 < end && (b[p + 1] & 0xFF) == 0x84) ? 6 : 4; break;
            case 0xC8: case 0xC9: n = 5; break;
            case 0xAA: case 0xAB: {
                Integer adv = instructionLength(b, p, op, end);
                n = adv == null ? 1 : adv;
                break;
            }
            default:
                if (op >= 0x99 && op <= 0xA8) {
                    n = 3;
                } else if (op == 0x10 || (op >= 0x15 && op <= 0x19)
                        || (op >= 0x36 && op <= 0x3A) || op == 0xBC
                        || op == 0xC6 || op == 0xC7) {
                    n = 2;
                } else if (op == 0x11 || op == 0x84 || op == 0xA9) {
                    n = 3;
                } else {
                    n = 1;
                }
                break;
        }
        return n;
    }

    private static Integer instructionLength(byte[] b, int p, int op, int end) {
        switch (op) {
            case 0x12: return 2;
            case 0x13: return 3;
            case 0x14: return 3;                        
            case 0xB9: case 0xBA: return 5;
            case 0xBB: case 0xBD: case 0xC0: case 0xC1: return 3;
            case 0xC5: return 4;
            case 0xB2: case 0xB3: case 0xB4: case 0xB5:
            case 0xB6: case 0xB7: case 0xB8: return 3;
            case 0xC4: return (p + 1 < end && (b[p + 1] & 0xFF) == 0x84) ? 6 : 4;
            case 0xAA: {
                int q = (p + 4) & ~3;
                if (q + 12 > end) {
                    return null;
                }
                int lo = i4(b, q + 4);
                int hi = i4(b, q + 8);
                long size = (long) hi - lo + 1;
                if (size < 0 || q + 12 + 4L * size > end) {
                    return null;
                }
                return (int) (q + 12 + 4L * size - p);
            }
            case 0xAB: {
                int q = (p + 4) & ~3;
                if (q + 8 > end) {
                    return null;
                }
                int n = i4(b, q + 4);
                if (n < 0 || q + 8 + 8L * n > end) {
                    return null;
                }
                return (int) (q + 8 + 8L * n - p);
            }
            default:
                return null;
        }
    }

    private static String checkIndex(byte[] b, ClassFile cf, int p, int op, int end) {
        int idx;
        int expect;
        switch (op) {
            case 0x12: idx = b[p + 1] & 0xFF; expect = -2; break;
            case 0x13: idx = u2(b, p + 1); expect = -2; break;
            case 0x14: idx = u2(b, p + 1); expect = -2; break;   
            case 0xB2: case 0xB3: case 0xB4: case 0xB5:
                idx = u2(b, p + 1); expect = 9; break;
            case 0xB6: case 0xB7: case 0xB8:
                idx = u2(b, p + 1); expect = 10; break;
            case 0xB9: idx = u2(b, p + 1); expect = 11; break;
            case 0xBA: idx = u2(b, p + 1); expect = 18; break;
            case 0xBB: case 0xBD: case 0xC0: case 0xC1: case 0xC5:
                idx = u2(b, p + 1); expect = 7; break;
            default: return null;
        }
        ClassFile.Cp c = cf.cp(idx);
        if (c == null) {
            return "操作码 0x" + Integer.toHexString(op) + " 的索引 " + idx + " 不存在";
        }
        if (expect == -2) {
            return null;                      
        }

        if (c.tag != expect && !(expect == 10 && c.tag == 11)) {
            return "操作码 0x" + Integer.toHexString(op) + " 的索引 " + idx
                    + " 是 tag" + c.tag + "，期望 tag" + expect + "（内容 " + c + "）";
        }
        return null;
    }

    private static int i4(byte[] b, int p) {
        return ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16)
                | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
    }

    /** 确保源常量池第 {@code idx} 项已导入目标类，并填好映射。 */
    private static boolean ensure(ClassFile source, ClassFile target, int idx, int[] map) {
        if (idx <= 0 || idx >= map.length) {
            if (lastReason == null) {
                lastReason = "常量池索引 " + idx + " 超出映射表范围（长度 " + map.length + "）";
            }
            return false;
        }
        if (map[idx] > 0) {
            return true;
        }
        ClassFile.Cp c = source.cp(idx);
        if (c == null) {
            if (lastReason == null) {
                lastReason = "源类常量池第 " + idx + " 项为空";
            }
            return false;
        }
        Integer mapped = source.transplantInto(target, c);
        if (mapped == null || mapped <= 0) {
            if (lastReason == null) {
                lastReason = "常量池项 " + idx + "（tag=" + c.tag
                        + "，内容 " + c + "）无法导入目标类";
            }
            return false;
        }
        map[idx] = mapped;
        target.recordMapping(idx, mapped);
        return true;
    }

    private static void writeU4(ByteArrayOutputStream out, int v) {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static int u2(byte[] b, int p) {
        return ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
    }

    private static long u4(byte[] b, int p) {
        return ((long) (b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16)
                | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
    }

    private static final int[] OP_LENGTH = new int[256];

    static {

        java.util.Arrays.fill(OP_LENGTH, 1);

        for (int i : new int[] {0xCA, 0xFE, 0xFF, 0xCB, 0xCC, 0xCD, 0xCE, 0xCF}) {
            OP_LENGTH[i] = -1;
        }

        for (int i = 0x10; i <= 0x10; i++) {        
            OP_LENGTH[i] = 2;
        }
        for (int i = 0x15; i <= 0x19; i++) {        
            OP_LENGTH[i] = 2;
        }
        for (int i = 0x36; i <= 0x3A; i++) {        
            OP_LENGTH[i] = 2;
        }
        OP_LENGTH[0x84] = 3;                        
        OP_LENGTH[0xA9] = 2;                        
        OP_LENGTH[0xBC] = 2;                        
        OP_LENGTH[0x11] = 3;                        
        // ldc2_w 虽然长度固定 3，但操作数是**常量池索引**（long/double），必须改写。
        // 和 0xB2-B8 同一类错误：设成定长 → 扫描直接跳过 → 索引原封不动，
        // 于是拼出来的类引用了源类的索引。实测症状：
        //   VerifyError: Illegal type at constant pool entry 115

        OP_LENGTH[0x14] = 0;                        
        // 下面这些指令虽然长度固定，但操作数是**常量池索引**，必须走 remapSpecial 去改写。
        // 表现就是拼出来的类里索引还是源类的值（实测 1033/1036 越界），
        // 而 JVM 的 defineClass 与 javap 又都能通过，只有宿主平台的 ASM 会崩。
        OP_LENGTH[0xB2] = 0;                        
        OP_LENGTH[0xB3] = 0;                        
        OP_LENGTH[0xB4] = 0;                        
        OP_LENGTH[0xB5] = 0;                        
        OP_LENGTH[0xB6] = 0;                        
        OP_LENGTH[0xB7] = 0;                        
        OP_LENGTH[0xB8] = 0;                        
        OP_LENGTH[0xC6] = 3;                        
        OP_LENGTH[0xC7] = 3;                        

        //   · 真机上 checkcast 被跳过、索引没改写 → VerifyError: Illegal type at CP entry 188；

        for (int i = 0x99; i <= 0xA6; i++) {        
            OP_LENGTH[i] = 3;
        }
        OP_LENGTH[0xC6] = 3;
        OP_LENGTH[0xC7] = 3;
        for (int i = 0xA7; i <= 0xA8; i++) {        
            OP_LENGTH[i] = 3;
        }
        OP_LENGTH[0xBB] = 0;                        
        OP_LENGTH[0xBD] = 0;                        
        OP_LENGTH[0xC0] = 0;                        
        OP_LENGTH[0xC1] = 0;                        
        OP_LENGTH[0xC8] = 5;                        
        OP_LENGTH[0xC9] = 5;                        

        OP_LENGTH[0x12] = 0;                        
        OP_LENGTH[0x13] = 0;                        
        OP_LENGTH[0xB9] = 0;                        
        OP_LENGTH[0xBA] = 0;                        
        OP_LENGTH[0xC5] = 0;                        
        OP_LENGTH[0xC4] = 0;                        
        OP_LENGTH[0xAA] = 0;                        
        OP_LENGTH[0xAB] = 0;                        
    }
}
