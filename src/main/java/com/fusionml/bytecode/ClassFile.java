package com.fusionml.bytecode;

import java.io.ByteArrayOutputStream;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 自研 class 文件读写器。
 *
 * <p>设计取舍（这是整个加载器最影响"快"的一环）：
 *
 * <ul>
 *   <li><b>零拷贝底座</b>：解析时保留原始 {@code byte[]}，并把常量池、字段区、
 *       方法区、属性区各自在原始数组里的<b>字节区间</b>记下来。没有改动时
 *       {@link #toBytes()} 直接返回原数组的引用 —— 一条字节都不用重新生成。</li>
 *   <li><b>只追加常量池</b>：JVM 允许常量池任意长，我们沿用原索引不动，
 *       新常量一律追加到尾部。这样任何既有索引（this_class、字段名、方法名）都保持有效，
 *       不用做全表重映射。</li>
 *   <li><b>UTF-8 原样搬运</b>：常量池字符串是"修改型 UTF-8"，重新编码容易出错也慢；
 *       复制时直接搬原始字节区间。只有新增字符串才编码。</li>
 * </ul>
 */
public final class ClassFile {

    public static final int MAGIC = 0xCAFEBABE;

    public static final int CP_UTF8 = 1;
    public static final int CP_INT = 3;
    public static final int CP_FLOAT = 4;
    public static final int CP_LONG = 5;
    public static final int CP_DOUBLE = 6;
    public static final int CP_CLASS = 7;
    public static final int CP_STRING = 8;
    public static final int CP_FIELDREF = 9;
    public static final int CP_METHODREF = 10;
    public static final int CP_IFACEMETHODREF = 11;
    public static final int CP_NAMEANDTYPE = 12;
    public static final int CP_METHODHANDLE = 15;
    public static final int CP_METHODTYPE = 16;
    public static final int CP_DYNAMIC = 17;
    public static final int CP_INVOKEDYNAMIC = 18;
    public static final int CP_MODULE = 19;
    public static final int CP_PACKAGE = 20;

    /** 一个常量池项。utf8 只在 tag==1 时有效。 */
    public static final class Cp {
        public final int tag;
        public final int a;
        public final int b;
        public final String utf8;
        /** 该项在常量池里的原始字节（含 tag），用于原样搬运数值型常量。 */
        final byte[] raw;

        Cp(int tag, int a, int b, String utf8) {
            this(tag, a, b, utf8, null);
        }

        Cp(int tag, int a, int b, String utf8, byte[] raw) {
            this.tag = tag;
            this.a = a;
            this.b = b;
            this.utf8 = utf8;
            this.raw = raw;
        }

        @Override
        public String toString() {
            return utf8 != null ? utf8 : ("tag" + tag + "(" + a + "," + b + ")");
        }
    }

    /** 属性的原始区间引用。 */
    public record Attr(int nameIndex, int offset, int length) {
    }

    /** 字段或方法。rawStart/rawEnd 覆盖 access_flags..属性区结束。 */
    public static final class Member {
        public final int index;
        public final boolean method;
        public final int access;
        public final int nameIndex;
        public final int descIndex;
        public final int rawStart;
        public final int rawEnd;
        public final Attr[] attrs;

        Member(int index, boolean method, int access, int nameIndex, int descIndex,
               int rawStart, int rawEnd, Attr[] attrs) {
            this.index = index;
            this.method = method;
            this.access = access;
            this.nameIndex = nameIndex;
            this.descIndex = descIndex;
            this.rawStart = rawStart;
            this.rawEnd = rawEnd;
            this.attrs = attrs;
        }
    }

    private final byte[] data;
    private final int minor;
    private final int major;
    private final Cp[] cp;
    private final int cpSlotCount;
    private final int cpRawStart;
    private final int cpRawEnd;
    private final int accessFlags;
    private final int thisClass;
    private final int superClass;
    private final int ifaceRawStart;
    private final int ifaceRawEnd;
    private final Member[] fields;
    private final Member[] methods;
    private final int fieldsRawStart;
    private final int fieldsRawEnd;
    private final int methodsRawStart;
    private final int methodsRawEnd;
    private final int classAttrRawStart;
    private final int classAttrRawEnd;
    private final Attr[] classAttrs;

    private final List<byte[]> extraCp = new ArrayList<>();
    private final Map<String, Integer> extraCpCache = new HashMap<>();
    private final Map<Integer, byte[]> methodOverride = new HashMap<>();
    /** 需要丢弃的类级属性名；null 表示原样搬运。 */
    private java.util.Set<String> droppedClassAttrs;

    private ClassFile(byte[] data, int minor, int major, Cp[] cp, int cpSlotCount, int cpRawStart,
                      int cpRawEnd, int accessFlags, int thisClass, int superClass,
                      int ifaceRawStart, int ifaceRawEnd, Member[] fields, Member[] methods,
                      int fieldsRawStart, int fieldsRawEnd, int methodsRawStart, int methodsRawEnd,
                      int classAttrRawStart, int classAttrRawEnd, Attr[] classAttrs) {
        this.data = data;
        this.minor = minor;
        this.major = major;
        this.cp = cp;
        this.cpSlotCount = cpSlotCount;
        this.cpRawStart = cpRawStart;
        this.cpRawEnd = cpRawEnd;
        this.accessFlags = accessFlags;
        this.thisClass = thisClass;
        this.superClass = superClass;
        this.ifaceRawStart = ifaceRawStart;
        this.ifaceRawEnd = ifaceRawEnd;
        this.fields = fields;
        this.methods = methods;
        this.fieldsRawStart = fieldsRawStart;
        this.fieldsRawEnd = fieldsRawEnd;
        this.methodsRawStart = methodsRawStart;
        this.methodsRawEnd = methodsRawEnd;
        this.classAttrRawStart = classAttrRawStart;
        this.classAttrRawEnd = classAttrRawEnd;
        this.classAttrs = classAttrs;
    }

    public static ClassFile read(byte[] data) {
        if (u4i(data, 0) != MAGIC) {
            throw new IllegalArgumentException("类文件魔数错误");
        }
        int p = 4;
        int minor = u2(data, p);
        int major = u2(data, p + 2);
        p += 4;
        int cpCount = u2(data, p);
        p += 2;
        int cpRawStart = p;
        Cp[] cp = new Cp[cpCount];
        int i = 1;
        while (i < cpCount) {
            int tag = data[p] & 0xFF;
            int entryStart = p;            // 含 tag，供"原样搬运"用
            p++;
            switch (tag) {
                case CP_UTF8 -> {
                    int len = u2(data, p);
                    p += 2;
                    cp[i] = new Cp(tag, p, len, decodeMutf8(data, p, len),
                            java.util.Arrays.copyOfRange(data, entryStart, p + len));
                    p += len;
                }
                case CP_INT, CP_FLOAT -> {
                    cp[i] = new Cp(tag, u4i(data, p), 0, null,
                            java.util.Arrays.copyOfRange(data, entryStart, p + 4));
                    p += 4;
                }
                case CP_LONG, CP_DOUBLE -> {
                    cp[i] = new Cp(tag, u4i(data, p), u4i(data, p + 4), null,
                            java.util.Arrays.copyOfRange(data, entryStart, p + 8));
                    p += 8;
                    i++;                       // 占两个槽位
                }
                case CP_CLASS, CP_STRING, CP_METHODTYPE, CP_MODULE, CP_PACKAGE -> {
                    cp[i] = new Cp(tag, u2(data, p), 0, null,
                            java.util.Arrays.copyOfRange(data, entryStart, p + 2));
                    p += 2;
                }
                case CP_FIELDREF, CP_METHODREF, CP_IFACEMETHODREF, CP_NAMEANDTYPE,
                     CP_DYNAMIC, CP_INVOKEDYNAMIC -> {
                    cp[i] = new Cp(tag, u2(data, p), u2(data, p + 2), null,
                            java.util.Arrays.copyOfRange(data, entryStart, p + 4));
                    p += 4;
                }
                case CP_METHODHANDLE -> {
                    cp[i] = new Cp(tag, data[p] & 0xFF, u2(data, p + 1), null,
                            java.util.Arrays.copyOfRange(data, entryStart, p + 3));
                    p += 3;
                }
                default -> throw new IllegalArgumentException("未知常量池 tag " + tag + " 于索引 " + i);
            }
            i++;
        }
        int cpRawEnd = p;

        int accessFlags = u2(data, p);
        int thisClass = u2(data, p + 2);
        int superClass = u2(data, p + 4);
        p += 6;

        int ifaceCount = u2(data, p);
        p += 2;
        int ifaceRawStart = p;
        p += ifaceCount * 2;
        int ifaceRawEnd = p;

        int fieldsCount = u2(data, p);
        p += 2;
        int fieldsRawStart = p;
        Member[] fields = new Member[fieldsCount];
        for (int k = 0; k < fieldsCount; k++) {
            int start = p;
            int access = u2(data, p);
            int nameIdx = u2(data, p + 2);
            int descIdx = u2(data, p + 4);
            int attrCount = u2(data, p + 6);
            p += 8;
            Attr[] attrs = new Attr[attrCount];
            for (int a = 0; a < attrCount; a++) {
                int nIdx = u2(data, p);
                int len = u4i(data, p + 2);
                attrs[a] = new Attr(nIdx, p + 6, len);
                p += 6 + len;
            }
            fields[k] = new Member(k, false, access, nameIdx, descIdx, start, p, attrs);
        }
        int fieldsRawEnd = p;

        int methodsCount = u2(data, p);
        p += 2;
        int methodsRawStart = p;
        Member[] methods = new Member[methodsCount];
        for (int k = 0; k < methodsCount; k++) {
            int start = p;
            int access = u2(data, p);
            int nameIdx = u2(data, p + 2);
            int descIdx = u2(data, p + 4);
            int attrCount = u2(data, p + 6);
            p += 8;
            Attr[] attrs = new Attr[attrCount];
            for (int a = 0; a < attrCount; a++) {
                int nIdx = u2(data, p);
                int len = u4i(data, p + 2);
                attrs[a] = new Attr(nIdx, p + 6, len);
                p += 6 + len;
            }
            methods[k] = new Member(k, true, access, nameIdx, descIdx, start, p, attrs);
        }
        int methodsRawEnd = p;

        int classAttrRawStart = p;
        int classAttrCount = u2(data, p);
        p += 2;
        Attr[] classAttrs = new Attr[classAttrCount];
        for (int a = 0; a < classAttrCount; a++) {
            int nIdx = u2(data, p);
            int len = u4i(data, p + 2);
            classAttrs[a] = new Attr(nIdx, p + 6, len);
            p += 6 + len;
        }
        int classAttrRawEnd = p;

        return new ClassFile(data, minor, major, cp, cpCount, cpRawStart, cpRawEnd, accessFlags,
                thisClass, superClass, ifaceRawStart, ifaceRawEnd, fields, methods,
                fieldsRawStart, fieldsRawEnd, methodsRawStart, methodsRawEnd,
                classAttrRawStart, classAttrRawEnd, classAttrs);
    }

    // ---------------------------------------------------------------- 查询

    public int major() {
        return major;
    }

    public int accessFlags() {
        return accessFlags;
    }

    /**
     * 本类是不是接口（{@code ACC_INTERFACE}）。
     *
     * <p>跨端那套"预热解析"要用它做一道安全闸：只预热<b>接口</b>，不预热普通类。
     * 原因是实测出来的 —— 模组平台的 Mixin 有一条硬规则"目标类一旦被加载就不能再注入"，
     * 而我们提前 {@code Class.forName} 一个普通类，就等于替模组把它的注入目标提前加载了。
     * 实测踩到的是 {@code yumi_mc_core} 的 {@code DeferredRegisterMixin}：
     * 它要往 {@code net.neoforged.neoforge.registries.DeferredRegister} 上注入，
     * 而我们预热时把这个类先加载了，于是启动直接崩在
     * {@code MixinTargetAlreadyLoadedException ... was loaded too early}。
     * 接口本身极少作为这种注入目标，只预热接口既解决了"解析对方类型"的需求，
     * 又不会替模组抢跑。
     */
    public boolean isInterface() {
        return (accessFlags & 0x0200) != 0;
    }

    public Cp cp(int index) {
        if (index <= 0 || index >= cp.length) {
            return null;
        }
        return cp[index];
    }

    public String utf8(int index) {
        Cp c = cp(index);
        return c == null ? null : c.utf8;
    }

    public String className(int index) {
        Cp c = cp(index);
        return c == null ? null : utf8(c.a);
    }

    /** 内部名，例如 {@code com/example/MyMod}。 */
    public String name() {
        return className(thisClass);
    }

    public String superName() {
        return superClass == 0 ? null : className(superClass);
    }

    /**
     * 本类直接实现的接口（内部名）。
     *
     * <p>接口表在各版本之间是"固定长度的一小段"，读它是零拷贝的：用记下来的区间直接取。
     * 跨端那套"补丁原版基座"的判断非常依赖这个方法 ——
     * 例如补丁后的 {@code net.minecraft.core.Registry} 比原版多实现
     * {@code IRegistryExtension}，正是靠它比出来的。
     */
    public String[] interfaces() {
        int n = (ifaceRawEnd - ifaceRawStart) / 2;
        String[] out = new String[n];
        for (int i = 0; i < n; i++) {
            out[i] = className(u2(data, ifaceRawStart + i * 2));
        }
        return out;
    }

    public String simpleName() {
        String n = name();
        int slash = n == null ? -1 : n.lastIndexOf('/');
        return slash < 0 ? n : n.substring(slash + 1);
    }

    public Member[] fields() {
        return fields;
    }

    public Member[] methods() {
        return methods;
    }

    public String attrName(Attr a) {
        return utf8(a.nameIndex());
    }

    /** 找到方法的 Code 属性，没有则返回 null。 */
    public Attr codeAttr(Member m) {
        for (Attr a : m.attrs) {
            if ("Code".equals(attrName(a))) {
                return a;
            }
        }
        return null;
    }

    public byte[] bytes() {
        return data;
    }

    /** 类自身的属性表。 */
    public Attr[] classAttributes() {
        return classAttrs;
    }

    /** 类级注解（{@code RuntimeVisibleAnnotations}）。 */
    public List<Annotation> classAnnotations() {
        return annotations(classAttrs);
    }

    /** 一条注解：类型描述符 + 元素值。 */
    public record Annotation(String desc, Map<String, Object> values) {
        public String str(String key, String def) {
            Object v = values.get(key);
            return v == null ? def : v.toString();
        }
    }

    /**
     * 读取某个成员（方法/字段）或某个属性上的 {@code RuntimeVisibleAnnotations}。
     *
     * <p>只解析加载器真正会用到的元素类型（字符串与整数），其余原样跳过 ——
     * 解析注解是为了拿注入规则，不是为了做一个通用反射框架。
     */
    public List<Annotation> annotations(Attr[] attrs) {
        List<Annotation> out = new ArrayList<>(2);
        for (Attr a : attrs) {
            if (!"RuntimeVisibleAnnotations".equals(utf8(a.nameIndex()))) {
                continue;
            }
            int p = a.offset();
            int count = u2(data, p);
            p += 2;
            for (int i = 0; i < count; i++) {
                int typeIndex = u2(data, p);
                p += 2;
                String desc = utf8(typeIndex);
                Map<String, Object> values = new java.util.LinkedHashMap<>(2);
                int pairs = u2(data, p);
                p += 2;
                for (int k = 0; k < pairs; k++) {
                    String name = utf8(u2(data, p));
                    p += 2;
                    int[] cursor = {p};
                    Object v = readElementValue(cursor);
                    p = cursor[0];
                    if (name != null) {
                        values.put(name, v);
                    }
                }
                out.add(new Annotation(desc, values));
            }
        }
        return out;
    }

    /** 读取一个 element_value；cursor[0] 会被更新到下一个元素的位置。 */
    private Object readElementValue(int[] cursor) {
        int p = cursor[0];
        int tag = data[p++] & 0xFF;
        switch (tag) {
            case 's' -> {
                String s = utf8(u2(data, p));
                cursor[0] = p + 2;
                return s;
            }
            case 'B', 'S', 'C', 'I', 'Z' -> {
                int idx = u2(data, p);
                cursor[0] = p + 2;
                Cp c = cp(idx);
                return c == null ? null : c.a;
            }
            case 'J' -> {
                int idx = u2(data, p);
                cursor[0] = p + 2;
                Cp c = cp(idx);
                return c == null ? null : ((long) c.a << 32) | (c.b & 0xFFFFFFFFL);
            }
            case 'D', 'F' -> {
                cursor[0] = p + 2;
                return null;
            }
            case 'e' -> {
                cursor[0] = p + 4;
                return null;
            }
            case 'c' -> {
                String s = utf8(u2(data, p));
                cursor[0] = p + 2;
                return s;
            }
            case '@' -> {
                // 嵌套注解：跳过类型与元素对
                int q = p + 2;
                int pairs = u2(data, q);
                q += 2;
                for (int i = 0; i < pairs; i++) {
                    q += 2;
                    int[] inner = {q};
                    readElementValue(inner);
                    q = inner[0];
                }
                cursor[0] = q;
                return null;
            }
            case '[' -> {
                int n = u2(data, p);
                int q = p + 2;
                List<Object> list = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    int[] inner = {q};
                    Object v = readElementValue(inner);
                    q = inner[0];
                    list.add(v);
                }
                cursor[0] = q;
                return list;
            }
            default -> {
                cursor[0] = p;
                return null;
            }
        }
    }

    // ---------------------------------------------------------------- 追加常量池

    public int addUtf8(String s) {
        String key = "U:" + s;
        Integer hit = extraCpCache.get(key);
        if (hit != null) {
            return hit;
        }
        ByteOut o = new ByteOut(s.length() + 3);
        o.u1(CP_UTF8);
        int startLen = o.size();
        o.u2(0);                                   // 长度占位
        int written = o.mutf8(s);
        byte[] raw = o.toByteArray();
        raw[startLen] = (byte) (written >>> 8);
        raw[startLen + 1] = (byte) written;
        int idx = allocate(raw);
        extraCpCache.put(key, idx);
        return idx;
    }

    public int addClass(String internalName) {
        String key = "C:" + internalName;
        Integer hit = extraCpCache.get(key);
        if (hit != null) {
            return hit;
        }
        int utf8Idx = addUtf8(internalName);
        ByteOut o = new ByteOut(3);
        o.u1(CP_CLASS).u2(utf8Idx);
        int idx = allocate(o.toByteArray());
        extraCpCache.put(key, idx);
        return idx;
    }

    public int addNameAndType(String name, String desc) {
        String key = "NT:" + name + ":" + desc;
        Integer hit = extraCpCache.get(key);
        if (hit != null) {
            return hit;
        }
        int n = addUtf8(name);
        int d = addUtf8(desc);
        ByteOut o = new ByteOut(5);
        o.u1(CP_NAMEANDTYPE).u2(n).u2(d);
        int idx = allocate(o.toByteArray());
        extraCpCache.put(key, idx);
        return idx;
    }

    public int addMethodref(String owner, String name, String desc) {
        String key = "M:" + owner + ":" + name + ":" + desc;
        Integer hit = extraCpCache.get(key);
        if (hit != null) {
            return hit;
        }
        int cls = addClass(owner);
        int nt = addNameAndType(name, desc);
        ByteOut o = new ByteOut(5);
        o.u1(CP_METHODREF).u2(cls).u2(nt);
        int idx = allocate(o.toByteArray());
        extraCpCache.put(key, idx);
        return idx;
    }

    /**
     * 追加项并返回它的常量池索引。
     *
     * <h2>这里必须按"槽位"而不是"项数"算</h2>
     *
     * <p>{@code long} 与 {@code double} 在常量池里<b>占两个槽位</b>（第二个是空洞）。
     * 一开始这里写的是 {@code cpSlotCount + extraCp.size()}，看起来自洽，
     * 实际上把"项数"当成了"槽位数" —— 只要导入的常量里有 long/double，
     * 后面所有项的索引就全部错位。
     *
     * <p>实测代价：拼出来的类里常量池最大索引是 1023，而我们写进字节码的索引是 1033，
     * 越界 10 个。表现为宿主平台的 ASM 在读这个类时抛
     * {@code ArrayIndexOutOfBoundsException: Index 940 out of bounds for length 841}，
     * 而 JVM 自己的 {@code defineClass} 与 {@code javap} 又能通过 —— 极难定位。
     *
     * <p>所以这里显式维护"下一个可用槽位"。
     */
    private int allocate(byte[] encoded) {
        int idx = cpSlotCount + extraCpSlots;
        extraCp.add(encoded);
        // long(5) / double(6) 占两个槽位
        int tag = encoded[0] & 0xFF;
        extraCpSlots += (tag == CP_LONG || tag == CP_DOUBLE) ? 2 : 1;
        return idx;
    }

    /** 追加项已经占用的槽位数（不是项数：long/double 算 2）。 */
    private int extraCpSlots;

    /** 原样追加一个常量池项（用于数值型常量：搬运时不需要理解它的内容）。 */
    int addRawCp(byte[] encoded) {
        String key = "R:" + java.util.Arrays.toString(encoded);
        Integer hit = extraCpCache.get(key);
        if (hit != null) {
            return hit;
        }
        int idx = allocate(java.util.Arrays.copyOf(encoded, encoded.length));
        extraCpCache.put(key, idx);
        return idx;
    }

    public int addInt(int v) {
        ByteOut o = new ByteOut(5);
        o.u1(CP_INT).u4(v);
        return addRawCp(o.toByteArray());
    }

    public int addFloat(float v) {
        ByteOut o = new ByteOut(5);
        o.u1(CP_FLOAT).u4(Float.floatToRawIntBits(v));
        return addRawCp(o.toByteArray());
    }

    public int addLong(long v) {
        ByteOut o = new ByteOut(9);
        o.u1(CP_LONG).u4((int) (v >>> 32)).u4((int) v);
        return addRawCp(o.toByteArray());
    }

    public int addDouble(double v) {
        ByteOut o = new ByteOut(9);
        long bits = Double.doubleToRawLongBits(v);
        o.u1(CP_DOUBLE).u4((int) (bits >>> 32)).u4((int) bits);
        return addRawCp(o.toByteArray());
    }

    public int addString(String s) {
        int key = addUtf8(s);
        ByteOut o = new ByteOut(3);
        o.u1(CP_STRING).u2(key);
        return addRawCp(o.toByteArray());
    }

    public int addFieldref(String owner, String name, String desc) {
        int cls = addClass(owner);
        int nt = addNameAndType(name, desc);
        ByteOut o = new ByteOut(5);
        o.u1(CP_FIELDREF).u2(cls).u2(nt);
        return addRawCp(o.toByteArray());
    }

    public int addInterfaceMethodref(String owner, String name, String desc) {
        int cls = addClass(owner);
        int nt = addNameAndType(name, desc);
        ByteOut o = new ByteOut(5);
        o.u1(CP_IFACEMETHODREF).u2(cls).u2(nt);
        return addRawCp(o.toByteArray());
    }

    public int addMethodType(String desc) {
        int d = addUtf8(desc);
        ByteOut o = new ByteOut(3);
        o.u1(CP_METHODTYPE).u2(d);
        return addRawCp(o.toByteArray());
    }

    /** 用新的属性区替换某个方法的属性区（用于改写 Code）。 */
    public void overrideMethodAttributes(int methodIndex, byte[] attributeBlob) {
        methodOverride.put(methodIndex, attributeBlob);
    }

    /**
     * 把本类常量池里的某一项导入到 {@code target}（递归导入它依赖的项）。
     *
     * <p>这是"方法级拼接"的底层原语：搬运一个方法的字节码时，
     * 它引用到的每一项常量都要先出现在目标类的常量池里，且拿到新下标。
     *
     * @return 导入后的新下标；无法导入返回 {@code null}
     */
    Integer transplantInto(ClassFile target, Cp c) {
        if (c == null) {
            return null;
        }
        switch (c.tag) {
            case CP_UTF8:
                return target.addUtf8(c.utf8);
            // 数值型常量（int/float/long/double）原样搬运：
            // 我们不需要理解它的值，只需要让目标类里有一份一模一样的项。
            case CP_INT:
            case CP_FLOAT:
            case CP_LONG:
            case CP_DOUBLE:
                return c.raw == null ? null : target.addRawCp(c.raw);
            case CP_CLASS: {
                String n = utf8(c.a);
                return n == null ? null : target.addClass(n);
            }
            case CP_STRING: {
                String s = utf8(c.a);
                return s == null ? null : target.addString(s);
            }
            case CP_NAMEANDTYPE: {
                String n = utf8(c.a);
                String d = utf8(c.b);
                return (n == null || d == null) ? null : target.addNameAndType(n, d);
            }
            case CP_FIELDREF:
            case CP_METHODREF:
            case CP_IFACEMETHODREF: {
                // 结构是 (class_index, name_and_type_index)。
                //
                // 注意这里**要解两层**：c.a 是"指向 Class 项的索引"，不是 UTF8 索引。
                // 一开始写成了 utf8(c.a)（等于 cp(c.a).utf8），而 Class 项的 utf8 字段是 null，
                // 于是整项导入失败。实测表现：拼接报
                //   "常量池项 1033（tag=10，内容 tag10(7,1032)）无法导入目标类"
                // —— 数字看着像越界，其实是解码层级错了。
                ClassFile.Cp cls = cp(c.a);
                Cp nt = cp(c.b);
                if (cls == null || nt == null) {
                    return null;
                }
                String o = utf8(cls.a);
                String n = utf8(nt.a);
                String d = utf8(nt.b);
                if (o == null || n == null || d == null) {
                    return null;
                }
                return switch (c.tag) {
                    case CP_FIELDREF -> target.addFieldref(o, n, d);
                    case CP_METHODREF -> target.addMethodref(o, n, d);
                    default -> target.addInterfaceMethodref(o, n, d);
                };
            }
            case CP_METHODTYPE: {
                String d = utf8(c.a);
                return d == null ? null : target.addMethodType(d);
            }
            // MethodHandle：结构是 (reference_kind u1, reference_index u2)。
            // invokedynamic 的 bootstrap 参数里会引用它（实测 tag15(6,280) = REF_invokeStatic）。
            case CP_METHODHANDLE: {
                int kind = c.a;
                Cp ref = cp(c.b);
                if (ref == null) {
                    return null;
                }
                Integer newRef = transplantInto(target, ref);
                if (newRef == null || newRef <= 0) {
                    return null;
                }
                ByteOut o = new ByteOut(4);
                o.u1(CP_METHODHANDLE).u1(kind).u2(newRef);
                return target.addRawCp(o.toByteArray());
            }
            // invokedynamic：结构是 (bootstrap_method_attr_index, name_and_type_index)。
            //
            // 支持它是必须的：对方的方法体里只要有 lambda / 字符串拼接，
            // 就带 invokedynamic。实测 AttachmentHolder.validateAttachmentType
            // 因此搬不进来，桥接卡住，报
            //   "常量池项 58（tag=18）无法导入目标类"。
            //
            // 注意 bootstrap_method_attr_index 指的是**类的 BootstrapMethods 属性**里的下标，
            // 而那个属性在我们的目标类里是原版的那份 —— 所以不能直接照抄下标，
            // 得把源类的对应 bootstrap 条目也追加到目标类，然后写新下标。
            case CP_INVOKEDYNAMIC: {
                Cp nt = cp(c.b);
                if (nt == null) {
                    return null;
                }
                String n = utf8(nt.a);
                String d = utf8(nt.b);
                if (n == null || d == null) {
                    return null;
                }
                return target.addInvokeDynamic(n, d, this, c.a);
            }
            default:
                // 方法句柄 / 动态调用 / 模块 / 包：结构更复杂，先不支持。
                // 返回 null 会让调用方放弃整次搬运 —— 这是刻意的保守选择。
                return null;
        }
    }

    /** 追加一个方法（常量池索引已在本类里就位），raw 覆盖 access_flags..属性区结束。 */
    public void addMethodRaw(byte[] raw) {
        addedMethods.add(raw);
    }

    /** 追加一个字段（raw 覆盖 access_flags..属性区结束）。 */
    public void addFieldRaw(byte[] raw) {
        addedFields.add(raw);
    }

    /**
     * 追加一个 {@code invokedynamic} 常量池项，并把它的 bootstrap 条目从源类搬过来。
     *
     * <h2>为什么不能照抄 bootstrap 下标</h2>
     *
     * <p>{@code invokedynamic} 的常量池项结构是
     * {@code (bootstrap_method_attr_index, name_and_type_index)}，而那个下标指的是
     * **类的 {@code BootstrapMethods} 属性**里的位置。目标类已经有自己的一份表，
     * 所以要把源类的第 {@code srcBootstrapIndex} 个条目**追加**到目标类的表里，
     * 再写新下标 —— 直接照抄下标会指到完全无关的引导方法上。
     *
     * <p>实测来源：{@code AttachmentHolder.validateAttachmentType} 里有字符串拼接
     * （{@code StringConcatFactory}），桥接时因此搬不进来。
     */
    public int addInvokeDynamic(String name, String desc, ClassFile source, int srcBootstrapIndex) {
        int bsIndex = appendBootstrapFrom(source, srcBootstrapIndex);
        if (bsIndex < 0) {
            return -1;
        }
        int nt = addNameAndType(name, desc);
        ByteOut o = new ByteOut(5);
        o.u1(CP_INVOKEDYNAMIC).u2(bsIndex).u2(nt);
        return addRawCp(o.toByteArray());
    }

    /** 从源类复制第 {@code srcIndex} 个 bootstrap 条目，返回它在目标类里的新下标。 */
    private int appendBootstrapFrom(ClassFile source, int srcIndex) {
        try {
            byte[] srcAttr = source.bootstrapMethodsRaw();
            if (srcAttr == null) {
                return -1;
            }
            int n = u2(srcAttr, 0);
            if (srcIndex < 0 || srcIndex >= n) {
                return -1;
            }
            // 逐条走过前 srcIndex 条，拿到目标条目的区间
            int p = 2;
            int entryStart = -1;
            int entryEnd = -1;
            for (int i = 0; i < n; i++) {
                int start = p;
                p += 2;                                   // bootstrap_method_ref
                int argc = u2(srcAttr, p);
                p += 2 + argc * 2;
                if (i == srcIndex) {
                    entryStart = start;
                    entryEnd = p;
                }
            }
            if (entryStart < 0) {
                return -1;
            }
            // 把这一条的字节导进目标类（其中的常量池索引要重写）
            int[] map = new int[source.cpSlotCount];
            java.util.Arrays.fill(map, -1);
            byte[] entry = java.util.Arrays.copyOfRange(srcAttr, entryStart, entryEnd);
            if (ConstantPoolTransplant.remapBootstrapEntry(source, this, entry, map) == null) {
                return -1;
            }
            if (bootstrapExtra == null) {
                bootstrapExtra = new ArrayList<>(4);
            }
            int newIndex = bootstrapExtra.size();
            bootstrapExtra.add(entry);
            return newIndex;
        } catch (Throwable t) {
            return -1;
        }
    }

    private List<byte[]> bootstrapExtra;

    /** 本类 {@code BootstrapMethods} 属性的数据区（没有则 null）。 */
    byte[] bootstrapMethodsRaw() {
        for (Attr a : classAttrs) {
            if ("BootstrapMethods".equals(utf8(a.nameIndex()))) {
                byte[] out = new byte[a.length()];
                System.arraycopy(data, a.offset(), out, 0, a.length());
                return out;
            }
        }
        return null;
    }

    /** 上一次成员导入失败的原因（诊断用）。 */
    public static String lastBridgeImportReason() {
        return ConstantPoolTransplant.lastReason;
    }

    private final List<byte[]> addedFields = new ArrayList<>();

    /** 追加一个接口（已存在则忽略）。 */
    public void addInterface(String internal) {
        if (!addedInterfaces.contains(internal)) {
            addedInterfaces.add(internal);
        }
    }

    private final List<String> addedInterfaces = new ArrayList<>();

    /**
     * 把一个成员从源类导入本类，返回**可直接追加**的原始字节（常量池已重写）。
     *
     * <p>供"超类桥接"使用：要把对方超类的字段与方法原样搬进原版类。
     */
    public byte[] importMemberForBridge(ClassFile target, Member member) {
        // 注意 receiver 是**源类**、参数是**目标类** —— 与 importMemberBody 一致。
        //
        // 踩过一次：这里写成了 `source.importMemberBody(this, member)`，
        // 于是语义整个反了（把目标当源、把源当目标），
        // 表现是"常量池索引 25966 超出映射表范围（长度 1596）" ——
        // 25966 来自 Level 的成员，而 1596 是 Level 的常量池大小，
        // 说明两边角色互换了。这种错误在字节码层不会报"参数错"，只会报一个看似无关的越界。
        return importMemberBody(target, member);
    }

    /**
     * 预扫一个方法，把它字节码里会**用到**的 ldc 常量先导入本类。
     *
     * <h2>为什么必须在改写之前做</h2>
     *
     * <p>{@code ldc}（0x12）的操作数是**单字节**常量池索引，最多到 255。
     * 而目标类（例如 {@code Level}）自己的常量池已经有一千多项，任何新导入的常量
     * 下标都会超过 255，于是 {@code ldc} 装不下 —— 报
     * {@code 操作码 0x12 无法确定长度 @code+130}，整个方法的搬运被放弃。
     *
     * <p>升格成 {@code ldc_w} 会改变指令长度、进而移动所有后续偏移
     * （StackMapTable、异常表、跳转偏移全部要跟着改），风险极高。
     *
     * <p>更稳的办法是**抢在改写之前**把这些常量导进来 —— 导入顺序不影响正确性，
     * 只要它们落在 255 以内就行。所以这里先做一遍预扫。
     *
     * @return 预扫是否成功（失败时调用方放弃本次导入）
     */
    public boolean preallocateLdcConstants(ClassFile source, Member member) {
        try {
            byte[] code = source.codeOf(member);
            if (code == null) {
                return true;                      // 没有 Code（抽象方法）：无需预扫
            }
            int[] map = new int[source.cpSlotCount];
            java.util.Arrays.fill(map, -1);
            int p = 0;
            while (p < code.length) {
                int op = code[p] & 0xFF;
                if (op == 0x12 && p + 1 < code.length) {
                    int idx = code[p + 1] & 0xFF;
                    if (!ConstantPoolTransplant.ensurePublic(source, this, idx, map)) {
                        return false;
                    }
                }
                int len = ConstantPoolTransplant.instructionLengthPublic(code, p, op, code.length);
                if (len <= 0) {
                    return false;
                }
                p += len;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private final List<byte[]> addedMethods = new ArrayList<>();

    /**
     * 从<b>另一个类</b>里把一个方法原样搬进来 —— 这是"方法级拼接"的核心。
     *
     * <h2>为什么必须做这件事</h2>
     *
     * <p>对方平台给原版类<b>加方法</b>（而不是改原有的），典型例子实测到的：
     *
     * <pre>
     * 原版 RegistryDataLoader.load(LoaderFactory, List, List, Executor)
     * 补丁 RegistryDataLoader.load(LoaderFactory, List, List, Executor, Z)   ← 多了第 5 个参数
     * </pre>
     *
     * <p>这时"整类换"与"整类不换"都不行：
     * <ul>
     *   <li>整类换 → 宿主平台的 Mixin 按<b>原版 4 参数签名</b>找注入点，永远找不到，崩在
     *       {@code Critical injection failure: @ModifyArg ... could not find any targets};</li>
     *   <li>整类不换 → 对方模组调不到那个 5 参数的重载，或对方的其他字节引用它时
     *       抛 {@code NoSuchMethodError}。</li>
     * </ul>
     *
     * <p>唯一两边都成立的做法是<b>两个签名都留</b>：以原版为基，把对方新增的那个重载拼进来。
     * 这正是参照项目 Forbric 走的路（它的合并报告：{@code spliced into merged classes:
     * methods=661 fields=138 interfaces=49}）。
     *
     * <h2>实现要点：常量池必须映射</h2>
     *
     * <p>方法的字节码里到处是常量池索引，而两个类的常量池布局完全不同。
     * 所以搬之前必须把源类常量池里"这个方法用到的那部分"逐项导入本类，
     * 并把字节码里的索引改写成新下标。这里按常量池标签分别处理：
     * 字符串/类/名字与类型/字段与方法引用/接口方法引用/方法句柄/方法类型/动态调用，
     * 其余标签（数值等）原样复制。
     *
     * @param source     方法所在的类
     * @param method     要搬的方法
     * @return 是否搬成功（失败的原因通常是"字节码里有本工具还解释不了的指令"）
     */
    public boolean spliceMethodFrom(ClassFile source, Member method) {
        try {
            byte[] body = source.importMemberBody(this, method);
            if (body == null) {
                return false;
            }
            addedMethods.add(body);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 把本类某个成员的**原始字节**（access_flags..属性区结束）导入到目标类，
     * 期间把其中的常量池索引逐个重写到目标类的常量池。
     *
     * @return 重写后的字节；失败返回 {@code null}
     */
    byte[] importMemberBody(ClassFile target, Member member) {
        // 先把"本成员用到的常量池项"按需导入目标类，建立 旧下标 → 新下标 的映射。
        int[] map = new int[cpSlotCount];
        java.util.Arrays.fill(map, -1);
        byte[] body = java.util.Arrays.copyOfRange(data, member.rawStart, member.rawEnd);
        // 注意：返回值可能是**新的数组**且比输入短 —— 丢弃 Code 的调试属性会变短，
        // 所以这里必须用返回值，不能假设是就地改写。
        return ConstantPoolTransplant.remapToNew(this, target, body, map);
    }

    /** 上一次拼接被放弃的原因（诊断用，见 {@link ConstantPoolTransplant#lastReason}）。 */
    public static String lastSpliceReason() {
        return ConstantPoolTransplant.lastReason;
    }

    /** 原始类文件字节（只读，供诊断工具定位属性与指令）。 */
    public byte[] rawBytes() {
        return data;
    }

    /** 供诊断工具读 u4（本包内已有同名方法，这里给外部用）。 */
    public static int u4iPublic(byte[] b, int p) {
        return u4i(b, p);
    }

    // ------------------------------------------------------- 超类桥接的支持方法

    /** 取某个方法现有的 Code 字节（没有 Code 返回 null）。 */
    public byte[] codeOf(Member m) {
        if (data == null) {
            return null;
        }
        for (Attr a : m.attrs) {
            if ("Code".equals(utf8(a.nameIndex()))) {
                int start = a.offset();
                int len = u4i(data, start + 4);
                byte[] out = new byte[len];
                System.arraycopy(data, start + 8, out, 0, len);
                return out;
            }
        }
        return null;
    }

    /** 用新的 Code 字节替换某个方法的 Code（保留 max_stack/max_locals 与异常表）。 */
    public void overrideCode(Member m, byte[] newCode) {
        if (data == null) {
            return;
        }
        for (Attr a : m.attrs) {
            if (!"Code".equals(utf8(a.nameIndex()))) {
                continue;
            }
            int start = a.offset();
            int end = start + a.length();
            int codeLen = u4i(data, start + 4);
            int codeStart = start + 8;
            int tailStart = codeStart + codeLen;             // 异常表 + 子属性
            ByteArrayOutputStream out = new ByteArrayOutputStream(
                    (tailStart - start) + newCode.length);
            // max_stack / max_locals：max_locals 原样，max_stack 抬到下限以上。
            //
            // 桥接会在 <init> 里插入 "aload_0; 常量; putfield"，那需要 2 个栈位。
            // 只抬 max_stack 是安全的（抬大不影响语义，抬小才会 VerifyError）。
            // 之前这里只是把下限记下来却没用上 —— 属于"写了没接线"，一并修掉。
            int oldMaxStack = u2(data, start);
            int newMaxStack = Math.max(oldMaxStack, maxStackFloor);
            out.write((newMaxStack >>> 8) & 0xFF);
            out.write(newMaxStack & 0xFF);
            out.write(data, start + 2, 2);                  // max_locals 原样
            // 新的 code_length
            out.write((newCode.length >>> 24) & 0xFF);
            out.write((newCode.length >>> 16) & 0xFF);
            out.write((newCode.length >>> 8) & 0xFF);
            out.write(newCode.length & 0xFF);
            out.write(newCode, 0, newCode.length);
            // 异常表与子属性原样（偏移没变，因为我们不增删分支）
            out.write(data, tailStart, end - tailStart);
            byte[] blob = out.toByteArray();
            // 整个方法属性区 = 属性数 + 这个 Code 属性
            ByteArrayOutputStream attrs = new ByteArrayOutputStream(blob.length + 16);
            int count = 0;
            for (Attr b : m.attrs) {
                if (b.nameIndex() == a.nameIndex() && b.offset() == a.offset()) {
                    attrs.write((a.nameIndex() >>> 8) & 0xFF);
                    attrs.write(a.nameIndex() & 0xFF);
                    int bl = blob.length;
                    attrs.write((bl >>> 24) & 0xFF);
                    attrs.write((bl >>> 16) & 0xFF);
                    attrs.write((bl >>> 8) & 0xFF);
                    attrs.write(bl & 0xFF);
                    attrs.write(blob, 0, bl);
                } else {
                    attrs.write((b.nameIndex() >>> 8) & 0xFF);
                    attrs.write(b.nameIndex() & 0xFF);
                    int bl = b.length();
                    attrs.write((bl >>> 24) & 0xFF);
                    attrs.write((bl >>> 16) & 0xFF);
                    attrs.write((bl >>> 8) & 0xFF);
                    attrs.write(bl & 0xFF);
                    attrs.write(data, b.offset(), bl);
                }
                count++;
            }
            byte[] body = attrs.toByteArray();
            ByteArrayOutputStream withCount = new ByteArrayOutputStream(body.length + 2);
            withCount.write((count >>> 8) & 0xFF);
            withCount.write(count & 0xFF);
            withCount.write(body, 0, body.length);
            overrideMethodAttributes(m.index, withCount.toByteArray());
            return;
        }
    }

    /** 把某个方法所在 Code 的 max_stack 抬到至少 {@code min}。 */
    public void raiseMaxStack(int min) {
        // 由于 overrideCode 保留原 max_stack，这里用"记录一个下限"的方式，
        // 在 overrideCode 拼接时读它。
        maxStackFloor = Math.max(maxStackFloor, min);
    }

    private int maxStackFloor;

    /**
     * 本类的 {@code invokedynamic} 里，有没有"把 {@code targetClass} 当 {@code ifaces} 之一"的转换。
     *
     * <p>场景：对方写 {@code LambdaMetafactory} 的接收者转换
     * {@code (Registry) -> IRegistryExtension}，即方法类型描述符里
     * 参数是本类、返回（或参数）是新接口。这种转换<b>在运行期检查</b>，
     * 类没实现接口就抛 {@code LambdaConversionException}。
     *
     * <p>判法：只看常量池里 {@code NameAndType} 的描述符里是否**同时**出现本类与新接口名。
     * 不做完整数据流分析 —— "同时出现"这个信号已经足够，因为这两者本来不该有交集，
     * 出现在同一个 invokedynamic 描述符里基本就意味着接收者转换。
     */
    public boolean lambdaTakesAsType(String targetClass, java.util.Set<String> ifaces) {
        String selfDesc = "L" + targetClass + ";";
        for (int i = 1; i < cp.length; i++) {
            Cp c = cp[i];
            if (c == null || c.tag != CP_INVOKEDYNAMIC) {
                continue;
            }
            Cp nt = cp(c.b);
            if (nt == null) {
                continue;
            }
            String desc = utf8(nt.b);
            if (desc == null || desc.indexOf(selfDesc) < 0) {
                continue;
            }
            for (String itf : ifaces) {
                if (desc.contains("L" + itf + ";")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 本类引用的所有方法（owner.name:desc），诊断用。 */
    public java.util.List<String> methodReferences() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int i = 1; i < cp.length; i++) {
            Cp c = cp[i];
            if (c == null || (c.tag != CP_METHODREF && c.tag != CP_IFACEMETHODREF)) {
                continue;
            }
            Cp cls = cp(c.a);
            Cp nt = cp(c.b);
            if (cls == null || nt == null) {
                continue;
            }
            out.add(utf8(cls.a) + "." + utf8(nt.a) + ":" + utf8(nt.b));
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** 本类自己的内部名（等值于 {@code this_class} 指向的类名）。 */
    public String thisName() {
        Cp c = cp(thisClass);
        if (c == null) {
            return null;
        }
        return utf8(c.a);
    }

    /** 本类自己的内部名（等价于 {@link #thisName()}，桥接代码用）。 */
    public String selfName() {
        return className(thisClass);
    }

    /** 找 {@code (owner,name,desc)} 形式的方法引用，返回它的常量池索引；没有返回 -1。 */
    public int cpIndexForMethodref(String owner, String name, String desc) {
        for (int i = 1; i < cp.length; i++) {
            Cp c = cp[i];
            if (c == null || c.tag != CP_METHODREF) {
                continue;
            }
            Cp cls = cp(c.a);
            Cp nt = cp(c.b);
            if (cls == null || nt == null) {
                continue;
            }
            if (owner.equals(utf8(cls.a)) && name.equals(utf8(nt.a)) && desc.equals(utf8(nt.b))) {
                return i;
            }
        }
        return -1;
    }

    /** 判断某个常量池索引是不是 {@code owner.name:desc} 的方法引用。 */
    public boolean cpIndexIsMethodrefOf(int idx, String owner, String name, String desc) {
        Cp c = cp(idx);
        if (c == null || c.tag != CP_METHODREF) {
            return false;
        }
        Cp cls = cp(c.a);
        Cp nt = cp(c.b);
        if (cls == null || nt == null) {
            return false;
        }
        return owner.equals(utf8(cls.a)) && name.equals(utf8(nt.a)) && desc.equals(utf8(nt.b));
    }

    /** 找 {@code owner.name:desc} 形式的字段引用，返回常量池索引；没有返回 -1。 */
    public int cpIndexForFieldref(String owner, String name, String desc) {
        for (int i = 1; i < cp.length; i++) {
            Cp c = cp[i];
            if (c == null || c.tag != CP_FIELDREF) {
                continue;
            }
            Cp cls = cp(c.a);
            Cp nt = cp(c.b);
            if (cls == null || nt == null) {
                continue;
            }
            if (owner.equals(utf8(cls.a)) && name.equals(utf8(nt.a)) && desc.equals(utf8(nt.b))) {
                return i;
            }
        }
        return -1;
    }

    /** 本类是否已经有某个类级属性。 */
    private boolean hasClassAttr(String name) {
        for (Attr a : classAttrs) {
            if (name.equals(utf8(a.nameIndex()))) {
                return true;
            }
        }
        return false;
    }

    /** 追加项实际占用的槽位数（按字节里的 tag 现算，用于与 extraCpSlots 对账）。 */
    private static int extraCpSlotsOf(List<byte[]> extra) {
        int n = 0;
        for (byte[] e : extra) {
            int tag = e.length == 0 ? 0 : (e[0] & 0xFF);
            n += (tag == CP_LONG || tag == CP_DOUBLE) ? 2 : 1;
        }
        return n;
    }

    /** 追加项的条数与槽位数（诊断用）。 */
    public int[] extraCpStats() {
        return new int[] {extraCp.size(), extraCpSlots, cpSlotCount};
    }

    /** 供自检使用：写入时的常量池计数（= 最大有效索引 + 1）。 */
    public int constantPoolCountForWrite() {
        return cpSlotCount + extraCpSlots;
    }

    /** 原始常量池的原始字节长度（用于核对是否与计数一致）。 */
    public int rawCpByteLength() {
        return cpRawEnd - cpRawStart;
    }

    /** 最近一次跨类导入的"源索引 → 目标索引"映射（诊断用）。 */
    private final java.util.Map<Integer, Integer> mappingDump = new java.util.LinkedHashMap<>();

    /** 记录一条映射（由 ConstantPoolTransplant 调用）。 */
    void recordMapping(int from, int to) {
        mappingDump.put(from, to);
    }

    /** 最近一次导入的索引映射（诊断用）。 */
    public java.util.Map<Integer, Integer> mappingDump() {
        return new java.util.LinkedHashMap<>(mappingDump);
    }

    /**
     * 本类的字节码里有没有 {@code new <target>}（构造这个类型的实例）。
     *
     * <p>用途是区分"对方拿实例去用"与"对方自己构造实例并当新超类用" ——
     * 后者要求我们必须跟对方的新超类类型，前者不需要（保留原版即可）。
     * 详见 {@code PatchedBase.foreignNeedsStrictSuperclassType}。
     *
     * <p>判法：扫所有方法的 Code，只看 {@code new}（0xBB）这一个操作码的目标。
     * 不做数据流分析 —— "谁 new 了它"这一个信号就够了。
     */
    public boolean newsClass(String target) {
        for (Member m : methods) {
            byte[] code = codeOf(m);
            if (code == null) {
                continue;
            }
            int p = 0;
            while (p < code.length) {
                int op = code[p] & 0xFF;
                if (op == 0xBB && p + 2 < code.length) {
                    int idx = ((code[p + 1] & 0xFF) << 8) | (code[p + 2] & 0xFF);
                    Cp c = cp(idx);
                    if (c != null && c.tag == CP_CLASS) {
                        String n = utf8(c.a);
                        if (target.equals(n)) {
                            return true;
                        }
                    }
                }
                int len = ConstantPoolTransplant.instructionLengthPublic(code, p, op, code.length);
                if (len <= 0) {
                    break;                        // 扫不动就停：这个方法的证据作废
                }
                p += len;
            }
        }
        return false;
    }

    /**
     * 常量池里有没有"对某些 owner 调用某些方法"。
     *
     * <p>与 {@link #referencesAnyMethodOf} 的区别：那个要求 owner **精确等于**给定类；
     * 这个允许 owner 是给定类**或其祖先**（用于"对方是否按新超类使用本类"的判断 ——
     * 字节码里的 owner 常常写的是父类，例如 {@code BaseMappedRegistry.setMaxId}）。
     */
    public boolean referencesAnyMethodOfDeep(java.util.Set<String> owners,
                                             java.util.Set<String> methodNames) {
        for (int i = 1; i < cp.length; i++) {
            Cp c = cp[i];
            if (c == null || (c.tag != CP_METHODREF && c.tag != CP_IFACEMETHODREF)) {
                continue;
            }
            Cp cls = cp(c.a);
            Cp nt = cp(c.b);
            if (cls == null || nt == null) {
                continue;
            }
            String owner = utf8(cls.a);
            String n = utf8(nt.a);
            if (owner != null && n != null && owners.contains(owner) && methodNames.contains(n)) {
                return true;
            }
        }
        return false;
    }

    /** 常量池里有没有对"任意 owner"调用这些方法名（只看名字，最宽松的一档）。 */
    public boolean referencesAnyMethodName(java.util.Set<String> methodNames) {
        for (int i = 1; i < cp.length; i++) {
            Cp c = cp[i];
            if (c == null || (c.tag != CP_METHODREF && c.tag != CP_IFACEMETHODREF)) {
                continue;
            }
            Cp nt = cp(c.b);
            if (nt == null) {
                continue;
            }
            String n = utf8(nt.a);
            if (n != null && methodNames.contains(n)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 本类的常量池里，有没有"调用某几个接口上的某个方法"的引用。
     *
     * <p>看的是 {@code Methodref}/{@code InterfaceMethodref} 的 **owner** 是否属于给定接口集合，
     * 且方法名+描述符命中。这是判断"某个新增接口是不是活契约"的依据：
     * 有调用点 → 对方模组真的会调它；没有 → 只是顺手加的便利接口。
     *
     * <p>为什么不只比方法名：{@code get}/{@code apply} 这种名字到处都有，
     * 只比名字会把便利接口误判成活契约（实测踩过）。
     */
    public boolean referencesAnyMethodOf(java.util.Set<String> owners,
                                         java.util.Set<String> nameAndDesc) {
        for (int i = 1; i < cp.length; i++) {
            Cp c = cp[i];
            if (c == null || (c.tag != CP_METHODREF && c.tag != CP_IFACEMETHODREF)) {
                continue;
            }
            Cp cls = cp(c.a);
            Cp nt = cp(c.b);
            if (cls == null || nt == null) {
                continue;
            }
            String owner = utf8(cls.a);
            if (owner == null || !owners.contains(owner)) {
                continue;
            }
            String n = utf8(nt.a);
            String d = utf8(nt.b);
            if (n != null && d != null && nameAndDesc.contains(n + d)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 扫描一段字节码/属性区，把其中的常量池索引重写到目标类。
     *
     * <p>实现搬到 {@link ConstantPoolTransplant} 里：那段逻辑要处理指令长度、变长指令、
     * 异常表与属性区，单独成类更容易看清、也更容易被自检覆盖。
     */

    public boolean modified() {
        return !extraCp.isEmpty() || !methodOverride.isEmpty() || !addedMethods.isEmpty()
                || !addedFields.isEmpty() || !addedInterfaces.isEmpty()
                || droppedClassAttrs != null;
    }

    /**
     * 丢弃一个类级属性（例如 {@code PermittedSubclasses}）。
     *
     * <p>非密封类型不允许携带 {@code PermittedSubclasses}，两者必须同时处理，
     * 否则 JVM 会以 {@code ClassFormatError} 拒绝这个类。
     */
    public void dropClassAttribute(String attrName) {
        if (droppedClassAttrs == null) {
            droppedClassAttrs = new java.util.LinkedHashSet<>(2);
        }
        droppedClassAttrs.add(attrName);
    }

    /**
     * 输出字节。没有改动时直接返回原始数组（连一次拷贝都不做），
     * 这是"未命中注入的类零开销"的关键。
     */
    public byte[] toBytes() {
        if (!modified()) {
            return data;
        }
        // 【必须在写 constant_pool_count **之前**做的事】
        //
        // 接口表与类属性区里有些名字要现分配常量池项（接口名、BootstrapMethods 属性名）。
        // 如果等到写那两处时才调 addClass/addUtf8，新项就落在**已经写死的计数之外**，
        // JVM 读到的就是越界索引 ——
        //   ClassFormatError: Attribute name has bad constant pool index 170
        // 实测就是这样崩的（NonNullList，185 轮排查）：
        // 索引 170 被分配、计数却是 170（最大合法索引 169）。
        //
        // 所以先在这里把要用的索引全部预分配好，后面只做纯写入。
        int[] ifaceIdx = new int[addedInterfaces.size()];
        for (int i = 0; i < addedInterfaces.size(); i++) {
            ifaceIdx[i] = addClass(addedInterfaces.get(i));
        }
        boolean needBootstrapName = bootstrapExtra != null && !hasClassAttr("BootstrapMethods");
        int bootstrapNameIdx = needBootstrapName ? addUtf8("BootstrapMethods") : 0;
        // 字段的 raw 字节是导入时就算好的，不需要新项；但方法覆盖（overrideCode）
        // 里可能引用新项 —— 那些在分配时就已经就位，这里不用管。

        ByteOut o = new ByteOut(data.length + 256);
        o.u4(MAGIC).u2(minor).u2(major);
        // constant_pool_count = 最大索引 + 1。追加项按**槽位**算（long/double 占 2），
        // 与 allocate() 保持同一套算术，否则索引与计数会对不上（踩过，见 allocate 的注释）。
        o.u2(cpSlotCount + extraCpSlots);
        // 自检：写出的项数（逐项写入）与声明的槽位数必须一致。
        //
        // 这是踩过 {@code ClassFormatError: Attribute name has bad constant pool index 170}
        // 之后加的 —— 那次的根因正是"分配的槽位数"与"实际写出的项数"不一致：
        // 声明了 170 项（最大索引 169），却有地方引用了 170。
        // 与其等到游戏启动期才崩，不如在这里直接发现。
        int declared = cpSlotCount + extraCpSlots;
        int actual = cpSlotCount + extraCpSlotsOf(extraCp);
        if (declared != actual) {
            throw new IllegalStateException("常量池计数与实际项数不一致：声明 " + declared
                    + "，实际 " + actual + "（追加项 " + extraCp.size()
                    + " 个，槽位 " + extraCpSlots + "）；这会让引用越界，"
                    + "表现为 ClassFormatError: bad constant pool index");
        }
        o.raw(data, cpRawStart, cpRawEnd - cpRawStart);
        for (byte[] e : extraCp) {
            o.raw(e, 0, e.length);
        }
        o.u2(accessFlags).u2(thisClass).u2(superClass);
        // 注意：ifaceRawStart / fieldsRawStart 记的是"计数之后"的位置，
        // 所以这两个计数必须显式写出来（类属性区记的是"计数之前"，自带计数）。
        o.u2((ifaceRawEnd - ifaceRawStart) / 2 + addedInterfaces.size());
        o.raw(data, ifaceRawStart, ifaceRawEnd - ifaceRawStart);
        for (int i = 0; i < addedInterfaces.size(); i++) {
            o.u2(ifaceIdx[i]);
        }
        o.u2(fields.length + addedFields.size());
        o.raw(data, fieldsRawStart, fieldsRawEnd - fieldsRawStart);
        for (byte[] extra : addedFields) {
            o.raw(extra, 0, extra.length);
        }
        o.u2(methods.length + addedMethods.size());
        for (Member m : methods) {
            byte[] override = methodOverride.get(m.index);
            if (override == null) {
                o.raw(data, m.rawStart, m.rawEnd - m.rawStart);
            } else {
                o.u2(m.access).u2(m.nameIndex).u2(m.descIndex);
                o.raw(override, 0, override.length);
            }
        }
        // 拼接进来的外来方法：字节已经在 importMemberBody 里重写过常量池索引。
        for (byte[] extra : addedMethods) {
            o.raw(extra, 0, extra.length);
        }        if (droppedClassAttrs == null && bootstrapExtra == null) {
            o.raw(data, classAttrRawStart, classAttrRawEnd - classAttrRawStart);
        } else {
            // 重建类属性区：原样搬运保留下来的属性，跳过的直接不写；
            // 如果搬进来了 invokedynamic 的 bootstrap 条目，还要**合并**进 BootstrapMethods。
            ByteOut body = new ByteOut(64);
            int kept = 0;
            boolean wroteBootstrap = false;
            for (Attr a : classAttrs) {
                String an = utf8(a.nameIndex());
                if (droppedClassAttrs != null && droppedClassAttrs.contains(an)) {
                    continue;
                }
                if ("BootstrapMethods".equals(an) && bootstrapExtra != null) {
                    // 合并：原有的条目 + 从源类搬来的条目
                    ByteOut merged = new ByteOut(64);
                    byte[] orig = new byte[a.length()];
                    System.arraycopy(data, a.offset(), orig, 0, a.length());
                    int n = u2(orig, 0);
                    merged.u2(n + bootstrapExtra.size());
                    merged.raw(orig, 2, orig.length - 2);
                    for (byte[] e : bootstrapExtra) {
                        merged.raw(e, 0, e.length);
                    }
                    byte[] mb = merged.toByteArray();
                    kept++;
                    body.u2(a.nameIndex()).u4(mb.length);
                    body.raw(mb, 0, mb.length);
                    wroteBootstrap = true;
                    continue;
                }
                kept++;
                body.u2(a.nameIndex()).u4(a.length());
                body.raw(data, a.offset(), a.length());
            }
            if (bootstrapExtra != null && !wroteBootstrap) {
                // 原版没有 BootstrapMethods：新建一个
                ByteOut bsm = new ByteOut(64);
                bsm.u2(bootstrapExtra.size());
                for (byte[] e : bootstrapExtra) {
                    bsm.raw(e, 0, e.length);
                }
                byte[] mb = bsm.toByteArray();
                kept++;
                body.u2(bootstrapNameIdx).u4(mb.length);
                body.raw(mb, 0, mb.length);
            }
            o.u2(kept);
            byte[] raw = body.toByteArray();
            o.raw(raw, 0, raw.length);
        }
        return o.toByteArray();
    }

    // ---------------------------------------------------------------- 低层读取

    static int u2(byte[] b, int p) {
        return ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
    }

    static int u4i(byte[] b, int p) {
        return ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16)
                | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
    }

    static long u4(byte[] b, int p) {
        return u4i(b, p) & 0xFFFFFFFFL;
    }

    /** 解码 class 文件使用的"修改型 UTF-8"。 */
    static String decodeMutf8(byte[] b, int off, int len) {
        char[] out = new char[len];
        int n = 0;
        int end = off + len;
        for (int i = off; i < end; ) {
            int c = b[i] & 0xFF;
            if (c < 0x80) {
                out[n++] = (char) c;
                i++;
            } else if ((c & 0xE0) == 0xC0) {
                out[n++] = (char) (((c & 0x1F) << 6) | (b[i + 1] & 0x3F));
                i += 2;
            } else {
                out[n++] = (char) (((c & 0x0F) << 12) | ((b[i + 1] & 0x3F) << 6) | (b[i + 2] & 0x3F));
                i += 3;
            }
        }
        return new String(out, 0, n);
    }
}
