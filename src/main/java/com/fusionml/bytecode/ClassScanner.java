package com.fusionml.bytecode;

import java.util.ArrayList;
import java.util.List;

/**
 * 极轻量的类文件扫描器 —— 只回答"注册阶段需要知道的几件事"：
 * 类名、父类、接口、类级注解、以及可选的常量池中引用到的类。
 *
 * <p>与 {@link ClassFile} 的区别：{@code ClassFile} 会为每个常量池项建对象，
 * 方便改写；而注册阶段要扫描成千上万个类，一个都不需要改写。
 * 这里改用平行的基本类型数组承载常量池，堆压力降到最低 ——
 * 在 8 核机器上扫完 5000 个类，主要成本是解压而不是扫描。
 */
public final class ClassScanner {

    public record Info(String name, String superName, String[] interfaces, String[] annotations,
                       int major) {
        public boolean hasAnnotation(String desc) {
            for (String a : annotations) {
                if (a.equals(desc)) {
                    return true;
                }
            }
            return false;
        }
    }

    private ClassScanner() {
    }

    /** 最快路径：只要内部名。命中失败返回 null。 */
    public static String scanName(byte[] d) {
        if (d.length < 10 || ClassFile.u4i(d, 0) != ClassFile.MAGIC) {
            return null;
        }
        int p = 8;
        int cpCount = ClassFile.u2(d, p);
        p += 2;
        int[] utf8Off = new int[cpCount];
        int[] utf8Len = new int[cpCount];
        int[] classRef = new int[cpCount];
        int i = 1;
        while (i < cpCount) {
            int tag = d[p++] & 0xFF;
            switch (tag) {
                case ClassFile.CP_UTF8 -> {
                    int len = ClassFile.u2(d, p);
                    utf8Off[i] = p + 2;
                    utf8Len[i] = len;
                    p += 2 + len;
                }
                case ClassFile.CP_INT, ClassFile.CP_FLOAT -> p += 4;
                case ClassFile.CP_LONG, ClassFile.CP_DOUBLE -> {
                    p += 8;
                    i++;
                }
                case ClassFile.CP_CLASS, ClassFile.CP_STRING, ClassFile.CP_METHODTYPE,
                     ClassFile.CP_MODULE, ClassFile.CP_PACKAGE -> {
                    classRef[i] = ClassFile.u2(d, p);
                    p += 2;
                }
                case ClassFile.CP_FIELDREF, ClassFile.CP_METHODREF, ClassFile.CP_IFACEMETHODREF,
                     ClassFile.CP_NAMEANDTYPE, ClassFile.CP_DYNAMIC, ClassFile.CP_INVOKEDYNAMIC -> p += 4;
                case ClassFile.CP_METHODHANDLE -> p += 3;
                default -> {
                    return null;
                }
            }
            i++;
        }
        int thisClass = ClassFile.u2(d, p + 2);
        int utf8Index = classRef[thisClass];
        if (utf8Index <= 0) {
            return null;
        }
        return ClassFile.decodeMutf8(d, utf8Off[utf8Index], utf8Len[utf8Index]);
    }

    public static Info scan(byte[] d) {
        if (d.length < 10 || ClassFile.u4i(d, 0) != ClassFile.MAGIC) {
            return null;
        }
        int minorMajor = ClassFile.u2(d, 6);
        int p = 8;
        int cpCount = ClassFile.u2(d, p);
        p += 2;
        int[] tag = new int[cpCount];
        int[] fa = new int[cpCount];
        int[] fb = new int[cpCount];
        int[] utf8Off = new int[cpCount];
        int[] utf8Len = new int[cpCount];
        int i = 1;
        while (i < cpCount) {
            int t = d[p++] & 0xFF;
            tag[i] = t;
            switch (t) {
                case ClassFile.CP_UTF8 -> {
                    int len = ClassFile.u2(d, p);
                    utf8Off[i] = p + 2;
                    utf8Len[i] = len;
                    p += 2 + len;
                }
                case ClassFile.CP_INT, ClassFile.CP_FLOAT -> {
                    fa[i] = ClassFile.u4i(d, p);
                    p += 4;
                }
                case ClassFile.CP_LONG, ClassFile.CP_DOUBLE -> {
                    p += 8;
                    i++;
                }
                case ClassFile.CP_CLASS, ClassFile.CP_STRING, ClassFile.CP_METHODTYPE,
                     ClassFile.CP_MODULE, ClassFile.CP_PACKAGE -> {
                    fa[i] = ClassFile.u2(d, p);
                    p += 2;
                }
                case ClassFile.CP_FIELDREF, ClassFile.CP_METHODREF, ClassFile.CP_IFACEMETHODREF,
                     ClassFile.CP_NAMEANDTYPE, ClassFile.CP_DYNAMIC, ClassFile.CP_INVOKEDYNAMIC -> {
                    fa[i] = ClassFile.u2(d, p);
                    fb[i] = ClassFile.u2(d, p + 2);
                    p += 4;
                }
                case ClassFile.CP_METHODHANDLE -> p += 3;
                default -> {
                    return null;
                }
            }
            i++;
        }
        int access = ClassFile.u2(d, p);
        int thisClass = ClassFile.u2(d, p + 2);
        int superClass = ClassFile.u2(d, p + 4);
        p += 6;
        String name = classNameOf(d, thisClass, tag, fa, utf8Off, utf8Len);
        String superName = superClass == 0 ? null : classNameOf(d, superClass, tag, fa, utf8Off, utf8Len);
        int ifaceCount = ClassFile.u2(d, p);
        p += 2;
        String[] interfaces = new String[ifaceCount];
        for (int k = 0; k < ifaceCount; k++) {
            interfaces[k] = classNameOf(d, ClassFile.u2(d, p), tag, fa, utf8Off, utf8Len);
            p += 2;
        }
        int fieldCount = ClassFile.u2(d, p);
        p += 2;
        p = skipMembers(d, p, fieldCount);
        int methodCount = ClassFile.u2(d, p);
        p += 2;
        p = skipMembers(d, p, methodCount);
        int attrCount = ClassFile.u2(d, p);
        p += 2;
        List<String> annos = new ArrayList<>(2);
        for (int a = 0; a < attrCount; a++) {
            int nameIdx = ClassFile.u2(d, p);
            long len = ClassFile.u4(d, p + 2);
            int body = p + 6;
            String attrName = utf8(tag, utf8Off, utf8Len, d, nameIdx);
            if ("RuntimeVisibleAnnotations".equals(attrName)
                    || "RuntimeInvisibleAnnotations".equals(attrName)) {
                readAnnotations(d, body, (int) len, tag, fa, utf8Off, utf8Len, d, annos, false);
            }
            p = (int) (body + len);
        }
        return new Info(name, superName, interfaces, annos.toArray(new String[0]), minorMajor);
    }

    private static int skipMembers(byte[] d, int p, int count) {
        for (int k = 0; k < count; k++) {
            int attrCount = ClassFile.u2(d, p + 6);
            p += 8;
            for (int a = 0; a < attrCount; a++) {
                long len = ClassFile.u4(d, p + 2);
                p = (int) (p + 6 + len);
            }
        }
        return p;
    }

    private static void readAnnotations(byte[] d, int p, int len, int[] tag, int[] fa,
                                        int[] utf8Off, int[] utf8Len, byte[] raw, List<String> out,
                                        boolean nested) {
        int end = p + len;
        int count = ClassFile.u2(d, p);
        p += 2;
        for (int i = 0; i < count && p < end; i++) {
            int typeIndex = ClassFile.u2(d, p);
            p += 2;
            String desc = utf8(tag, utf8Off, utf8Len, raw, typeIndex);
            if (!nested && desc != null) {
                out.add(desc);
            }
            int pairs = ClassFile.u2(d, p);
            p += 2;
            for (int k = 0; k < pairs; k++) {
                p += 2;                              
                p = skipElementValue(d, p);
            }
        }
    }

    private static int skipElementValue(byte[] d, int p) {
        int t = d[p++] & 0xFF;
        switch (t) {
            case 'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 's', 'c' -> p += 2;
            case 'e' -> p += 4;
            case '@' -> p = skipAnnotation(d, p);
            case '[' -> {
                int n = ClassFile.u2(d, p);
                p += 2;
                for (int i = 0; i < n; i++) {
                    p = skipElementValue(d, p);
                }
            }
            default -> throw new IllegalArgumentException("未知注解元素类型 " + t);
        }
        return p;
    }

    private static int skipAnnotation(byte[] d, int p) {
        p += 2;                                      
        int pairs = ClassFile.u2(d, p);
        p += 2;
        for (int k = 0; k < pairs; k++) {
            p += 2;
            p = skipElementValue(d, p);
        }
        return p;
    }

    private static String classNameOf(byte[] d, int classIndex, int[] tag, int[] fa,
                                      int[] utf8Off, int[] utf8Len) {
        if (classIndex <= 0 || classIndex >= tag.length || tag[classIndex] != ClassFile.CP_CLASS) {
            return null;
        }
        return utf8(tag, utf8Off, utf8Len, d, fa[classIndex]);
    }

    private static String utf8(int[] tag, int[] utf8Off, int[] utf8Len, byte[] d, int index) {
        if (index <= 0 || index >= tag.length || tag[index] != ClassFile.CP_UTF8) {
            return null;
        }
        return ClassFile.decodeMutf8(d, utf8Off[index], utf8Len[index]);
    }
}
