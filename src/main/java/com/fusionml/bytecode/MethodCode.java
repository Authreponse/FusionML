package com.fusionml.bytecode;

import java.util.ArrayList;
import java.util.List;

/**
 * 方法体的 Code 属性。除了常规解析，这里最关键的能力是
 * {@link #shiftOffsets(int)}：在方法体开头插入字节码后，把内部所有绝对偏移量平移。
 *
 * <p>处理各属性的策略：
 * <ul>
 *   <li><b>LineNumberTable</b>：start_pc 直接加 delta。</li>
 *   <li><b>LocalVariableTable / LocalVariableTypeTable</b>：起始为 0 的条目表示"覆盖整个方法"，
 *       保持 0 并加长 length；其余按 start_pc 平移。</li>
 *   <li><b>StackMapTable</b>：帧偏移是"相对上一个帧"的增量编码，所以把第一个帧的
 *       offset_delta 加上 delta 就够了 —— 后面所有帧自动跟着平移。这是增量编码给的红利，
 *       省掉了重算栈图的全部代价。</li>
 *   <li>其余属性（含 RuntimeVisibleTypeAnnotations 这类我们不解析的）：原样搬运。</li>
 * </ul>
 */
public final class MethodCode {

    public record ExEntry(int startPc, int endPc, int handlerPc, int catchType) {
    }

    /** 一个内嵌属性的名字索引与原始字节。 */
    public record Nested(int nameIndex, byte[] info) {
    }

    public int maxStack;
    public int maxLocals;
    public byte[] code;
    public ExEntry[] exceptionTable;
    public final List<Nested> attrs = new ArrayList<>();

    public static MethodCode parse(byte[] data, int off, int len) {
        MethodCode mc = new MethodCode();
        mc.maxStack = ClassFile.u2(data, off);
        mc.maxLocals = ClassFile.u2(data, off + 2);
        int p = off + 4;
        int codeLen = ClassFile.u4i(data, p);
        p += 4;
        mc.code = new byte[codeLen];
        System.arraycopy(data, p, mc.code, 0, codeLen);
        p += codeLen;
        int exCount = ClassFile.u2(data, p);
        p += 2;
        mc.exceptionTable = new ExEntry[exCount];
        for (int i = 0; i < exCount; i++) {
            mc.exceptionTable[i] = new ExEntry(ClassFile.u2(data, p), ClassFile.u2(data, p + 2),
                    ClassFile.u2(data, p + 4), ClassFile.u2(data, p + 6));
            p += 8;
        }
        int attrCount = ClassFile.u2(data, p);
        p += 2;
        for (int i = 0; i < attrCount; i++) {
            int nameIndex = ClassFile.u2(data, p);
            int alen = ClassFile.u4i(data, p + 2);
            p += 6;
            byte[] info = new byte[alen];
            System.arraycopy(data, p, info, 0, alen);
            p += alen;
            mc.attrs.add(new Nested(nameIndex, info));
        }
        if (p != off + len) {
            throw new IllegalArgumentException("Code 属性长度不自洽: 期望 " + len + " 实际 " + (p - off));
        }
        return mc;
    }

    /**
     * 在方法体开头插入字节码，并平移全部偏移量。
     *
     * @param attrNameResolver 把属性名索引翻译成属性名（通常传 {@code ClassFile::utf8}）
     * @param notes            需要向调用方报告的"做了哪些取舍"
     */
    public void prepend(byte[] prefix, java.util.function.IntFunction<String> attrNameResolver,
                        java.util.List<String> notes) {
        if (prefix.length == 0) {
            return;
        }
        int delta = prefix.length;
        byte[] merged = new byte[prefix.length + code.length];
        System.arraycopy(prefix, 0, merged, 0, prefix.length);
        System.arraycopy(code, 0, merged, prefix.length, code.length);
        code = merged;
        for (int i = 0; i < exceptionTable.length; i++) {
            ExEntry e = exceptionTable[i];
            exceptionTable[i] = new ExEntry(e.startPc() + delta, e.endPc() + delta,
                    e.handlerPc() + delta, e.catchType());
        }
        List<Nested> shifted = new ArrayList<>(attrs.size());
        for (Nested a : attrs) {
            String name = attrNameResolver.apply(a.nameIndex());
            byte[] info = shiftAttr(name, a.info(), delta, notes);
            if (info != null) {
                shifted.add(new Nested(a.nameIndex(), info));
            }
        }
        attrs.clear();
        attrs.addAll(shifted);
    }

    private byte[] shiftAttr(String name, byte[] info, int delta, java.util.List<String> notes) {
        if (name == null) {
            return info;
        }
        switch (name) {
            case "LineNumberTable": {
                int count = ClassFile.u2(info, 0);
                for (int i = 0; i < count; i++) {
                    int off = 2 + i * 4;
                    putU2(info, off, ClassFile.u2(info, off) + delta);
                }
                return info;
            }
            case "LocalVariableTable":
            case "LocalVariableTypeTable": {
                int count = ClassFile.u2(info, 0);
                for (int i = 0; i < count; i++) {
                    int off = 2 + i * 10;
                    int startPc = ClassFile.u2(info, off);
                    int length = ClassFile.u2(info, off + 2);
                    if (startPc == 0) {
                        putU2(info, off + 2, length + delta);
                    } else {
                        putU2(info, off, startPc + delta);
                    }
                }
                return info;
            }
            case "StackMapTable":
                return shiftStackMapTable(info, delta);
            case "RuntimeVisibleTypeAnnotations":
            case "RuntimeInvisibleTypeAnnotations":
                // 方法体内的类型注解带绝对字节码偏移，注入后这些偏移必然失效。
                // 留着 = 输出错误的调试信息；移除 = 只是少了一类工具元数据。这里选择移除并明确告知。
                notes.add("已移除方法内类型注解属性 " + name + "（注入会让其中的字节码偏移失效）");
                return null;
            default:
                return info;
        }
    }

    /**
     * 重新编码 StackMapTable。
     *
     * <p>StackMapTable 的坑点：帧标签只占 1 字节，而且 {@code same_frame}(0~63) 与
     * {@code same_locals_1_stack_item_frame}(64~127) 把 offset_delta <b>直接编码在标签里</b>。
     * 因此偏移量一旦变大就可能超出短编码的表示范围，必须升级成扩展形式
     * （251 / 247），并把帧体的验证类型信息原样搬过去。
     *
     * <p>又因为除第一帧外，所有帧的偏移都是"相对上一帧"的增量，
     * 只有第一帧需要加 delta，其余原样搬运。
     */
    private static byte[] shiftStackMapTable(byte[] info, int delta) {
        int count = ClassFile.u2(info, 0);
        if (count == 0) {
            return info;
        }
        ByteOut out = new ByteOut(info.length + 8);
        out.u2(count);
        int p = 2;
        for (int i = 0; i < count; i++) {
            int tag = info[p++] & 0xFF;
            int oldDelta;
            if (tag <= 63) {
                oldDelta = tag;
            } else if (tag <= 127) {
                oldDelta = tag - 64;
            } else {
                oldDelta = ClassFile.u2(info, p);
                p += 2;
            }
            int newDelta = i == 0 ? oldDelta + delta : oldDelta;
            if (tag <= 63) {
                if (newDelta <= 63) {
                    out.u1(newDelta);
                } else {
                    out.u1(251).u2(newDelta);             // same_frame_extended
                }
            } else if (tag <= 127) {
                int vti = vtiSize(info, p);
                if (newDelta <= 63) {
                    out.u1(64 + newDelta).raw(info, p, vti);
                } else {
                    out.u1(247).u2(newDelta).raw(info, p, vti);   // same_locals_1_stack_item_extended
                }
                p += vti;
            } else if (tag == 247) {
                int vti = vtiSize(info, p);
                out.u1(247).u2(newDelta).raw(info, p, vti);
                p += vti;
            } else if (tag >= 248 && tag <= 251) {
                out.u1(tag).u2(newDelta);                 // chop / same_extended，无帧体
            } else if (tag >= 252 && tag <= 254) {
                int k = tag - 251;
                int start = p;
                for (int j = 0; j < k; j++) {
                    p += vtiSize(info, p);
                }
                out.u1(tag).u2(newDelta).raw(info, start, p - start);
            } else if (tag == 255) {
                int start = p;
                int nLocals = ClassFile.u2(info, p);
                p += 2;
                for (int j = 0; j < nLocals; j++) {
                    p += vtiSize(info, p);
                }
                int nStack = ClassFile.u2(info, p);
                p += 2;
                for (int j = 0; j < nStack; j++) {
                    p += vtiSize(info, p);
                }
                out.u1(255).u2(newDelta).raw(info, start, p - start);
            } else {
                throw new IllegalStateException("未知的 StackMapTable 帧标签 " + tag);
            }
        }
        return out.toByteArray();
    }

    /** verification_type_info 的字节长度：0~6 是 1 字节，7/8 是 3 字节。 */
    private static int vtiSize(byte[] b, int p) {
        int tag = b[p] & 0xFF;
        return (tag == 7 || tag == 8) ? 3 : 1;
    }

    private static void putU2(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 8);
        b[off + 1] = (byte) v;
    }

    /** 组装成完整的属性区（含 attribute_count）。 */
    public byte[] encodeAttributeBlob(int codeNameIndex, List<Nested> otherAttrs) {
        ByteOut o = new ByteOut(code.length + 64 + otherAttrs.size() * 32);
        o.u2(otherAttrs.size() + 1);
        for (Nested n : otherAttrs) {
            o.u2(n.nameIndex()).u4(n.info().length).raw(n.info(), 0, n.info().length);
        }
        o.u2(codeNameIndex);
        int lenPos = o.size();
        o.u4(0);
        int bodyStart = o.size();
        o.u2(maxStack).u2(maxLocals);
        o.u4(code.length).raw(code, 0, code.length);
        o.u2(exceptionTable.length);
        for (ExEntry e : exceptionTable) {
            o.u2(e.startPc()).u2(e.endPc()).u2(e.handlerPc()).u2(e.catchType());
        }
        o.u2(attrs.size());
        for (Nested n : attrs) {
            o.u2(n.nameIndex()).u4(n.info().length).raw(n.info(), 0, n.info().length);
        }
        byte[] out = o.toByteArray();
        int bodyLen = out.length - bodyStart;
        out[lenPos] = (byte) (bodyLen >>> 24);
        out[lenPos + 1] = (byte) (bodyLen >>> 16);
        out[lenPos + 2] = (byte) (bodyLen >>> 8);
        out[lenPos + 3] = (byte) bodyLen;
        return out;
    }
}
