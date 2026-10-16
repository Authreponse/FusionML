package com.fusionml.bytecode;

import java.util.ArrayList;
import java.util.List;

/**
 * 自研类文件生成器。用途有两个：
 * <ol>
 *   <li>测试夹具：批量造出"像模组里编译出来的类"，用来压测扫描/加载/注册流水线；</li>
 *   <li>运行期生成桥接类：把三方平台的入口点适配到本加载器的内部契约。</li>
 * </ol>
 *
 * <p>刻意只生成"直线型"方法体（无分支），这样按 JVM 规范就不需要 StackMapTable 属性
 * —— 生成器因此可以保持精简，同时输出仍是完全合法的现代版本类文件。
 */
public final class ClassEmitter {

    /** 一条注解：类型描述符 + 元素值（当前支持字符串与整数）。 */
    public record Anno(String desc, java.util.Map<String, Object> values) {
        public static Anno of(String desc) {
            return new Anno(desc, java.util.Map.of());
        }
    }

    private final String internalName;
    private final String superName;
    private final List<String> interfaces = new ArrayList<>(2);
    private final List<byte[]> methods = new ArrayList<>(4);
    private final List<Anno> annotations = new ArrayList<>(1);
    private final List<byte[]> fields = new ArrayList<>(2);
    private int version = 61;                       // Java 17
    private int access = 0x0021;                    // ACC_PUBLIC | ACC_SUPER
    private final ConstPool cp = new ConstPool();

    public ClassEmitter(String internalName, String superName) {
        this.internalName = internalName;
        this.superName = superName;
    }

    public ClassEmitter version(int v) {
        this.version = v;
        return this;
    }

    public ClassEmitter access(int flags) {
        this.access = flags;
        return this;
    }

    public ClassEmitter addInterface(String iface) {
        interfaces.add(iface);
        return this;
    }

    /** 加一个类级注解，例如 {@code Lcom/example/Mod;}。 */
    public ClassEmitter addAnnotation(String desc) {
        annotations.add(Anno.of(desc));
        return this;
    }

    /** 加一个带元素值的类级注解，例如 {@code @FusionMixin(target = "...")}。 */
    public ClassEmitter addAnnotation(String desc, java.util.Map<String, Object> values) {
        annotations.add(new Anno(desc, values));
        return this;
    }

    public ClassEmitter addField(String name, String desc, int flags) {
        ByteOut o = new ByteOut(16);
        o.u2(flags).u2(cp.utf8(name)).u2(cp.utf8(desc)).u2(0);
        fields.add(o.toByteArray());
        return this;
    }

    // ---------------------------------------------------------------- 常量池入口
    //
    // 生成"运行期桥接类"时，方法体里除了压栈/返回，还需要引用字段与方法
    // （例如"把构造器参数存进字段"、"读字段作为返回值"）。
    // 这几个方法把常量池下标暴露出来，调用方自己拼字节码即可 ——
    // 生成器本身不必知道桥接类长什么样，职责保持单一。

    /** 字段引用常量（putfield/getfield 用）。 */
    public int constFieldref(String owner, String name, String desc) {
        return cp.fieldref(owner, name, desc);
    }

    /** 方法引用常量（invokevirtual / invokespecial 用）。 */
    public int constMethodref(String owner, String name, String desc) {
        return cp.methodref(owner, name, desc);
    }

    /** 类常量。 */
    public int constClass(String internalName) {
        return cp.classRef(internalName);
    }

    /** 字符串常量（ldc / ldc_w 用）。 */
    public int constString(String value) {
        return cp.string(value);
    }

    /** 默认构造器（调用 super()）。 */
    public ClassEmitter addDefaultConstructor() {
        int ref = cp.methodref(superName, "<init>", "()V");
        byte[] code = {
                (byte) 0x2A,                        // aload_0
                (byte) 0xB7, (byte) (ref >>> 8), (byte) ref,   // invokespecial <init>
                (byte) 0xB1                         // return
        };
        return addMethod("<init>", "()V", 0x0001, 1, 1, code);
    }

    /** 静态 void 方法，方法体只有 return。 */
    public ClassEmitter addEmptyStaticMethod(String name, String desc) {
        return addMethod(name, desc, 0x0009, 0, Descriptors.paramSlots(desc),
                new byte[] {(byte) 0xB1}, List.of());
    }

    /** 静态 void 方法 + 方法级注解（用于生成钩子类）。 */
    public ClassEmitter addAnnotatedEmptyStaticMethod(String name, String desc, Anno... annos) {
        return addMethod(name, desc, 0x0009, 0, Descriptors.paramSlots(desc),
                new byte[] {(byte) 0xB1}, List.of(annos));
    }

    /** 静态方法，方法体是 {@code return <常量>}（返回 int/boolean/char 之类）。 */
    public ClassEmitter addConstantStaticMethod(String name, String desc, int value) {
        byte[] body;
        byte[] bodyWithConst;
        if (value >= -1 && value <= 5) {
            // iconst_N（-1..5）
            int op = value == -1 ? 0x02 : 0x03 + value;
            bodyWithConst = new byte[] {(byte) op, (byte) 0xAC};
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            bodyWithConst = new byte[] {(byte) 0x10, (byte) value, (byte) 0xAC};
        } else {
            int idx = cp.integer(value);
            bodyWithConst = new byte[] {
                    (byte) 0x13, (byte) (idx >>> 8), (byte) idx,   // ldc_w
                    (byte) 0xAC                                     // ireturn
            };
        }
        body = bodyWithConst;
        String ret = Descriptors.returnType(desc);
        if (!"I".equals(ret) && !"Z".equals(ret) && !"S".equals(ret) && !"B".equals(ret)
                && !"C".equals(ret)) {
            throw new IllegalArgumentException("addConstantStaticMethod 仅支持 int 系返回类型");
        }
        return addMethod(name, desc, 0x0009, 1, Descriptors.paramSlots(desc), body, List.of());
    }

    /**
     * 实例方法，方法体是 {@code return <常量>}。
     *
     * <p>加载器的注入测试必须有"实例方法"作为靶子：实例方法的钩子签名要把 {@code this}
     * 一起传进去，槽位计算与静态方法不同，只有真正生成实例方法才能覆盖到这条路径。
     */
    public ClassEmitter addInstanceConstantMethod(String name, String desc, int value) {
        byte[] body;
        if (value >= -1 && value <= 5) {
            int op = value == -1 ? 0x02 : 0x03 + value;
            body = new byte[] {(byte) op, (byte) 0xAC};
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            body = new byte[] {(byte) 0x10, (byte) value, (byte) 0xAC};
        } else {
            int idx = cp.integer(value);
            body = new byte[] {(byte) 0x13, (byte) (idx >>> 8), (byte) idx, (byte) 0xAC};
        }
        String ret = Descriptors.returnType(desc);
        if (!"I".equals(ret) && !"Z".equals(ret) && !"S".equals(ret) && !"B".equals(ret)
                && !"C".equals(ret)) {
            throw new IllegalArgumentException("addInstanceConstantMethod 仅支持 int 系返回类型");
        }
        int maxLocals = 1 + Descriptors.paramSlots(desc);
        return addMethod(name, desc, 0x0001, 1, maxLocals, body, List.of());
    }

    public ClassEmitter addMethod(String name, String desc, int flags, int maxStack, int maxLocals,
                                 byte[] code) {
        return addMethod(name, desc, flags, maxStack, maxLocals, code, List.of());
    }

    public ClassEmitter addMethod(String name, String desc, int flags, int maxStack, int maxLocals,
                                 byte[] code, List<Anno> methodAnnotations) {
        int nameIdx = cp.utf8(name);
        int descIdx = cp.utf8(desc);
        ByteOut codeAttr = new ByteOut(code.length + 24);
        int codeName = cp.utf8("Code");
        codeAttr.u2(codeName).u4(code.length + 12);
        codeAttr.u2(maxStack).u2(maxLocals);
        codeAttr.u4(code.length).raw(code, 0, code.length);
        codeAttr.u2(0);                              // exception_table_length
        codeAttr.u2(0);                              // attributes_count
        byte[] blob = codeAttr.toByteArray();
        byte[] annoAttr = encodeAnnotations(methodAnnotations);
        int attrCount = annoAttr == null ? 1 : 2;
        ByteOut m = new ByteOut(blob.length + 8 + (annoAttr == null ? 0 : annoAttr.length));
        m.u2(flags).u2(nameIdx).u2(descIdx).u2(attrCount).raw(blob, 0, blob.length);
        if (annoAttr != null) {
            m.raw(annoAttr, 0, annoAttr.length);
        }
        methods.add(m.toByteArray());
        return this;
    }

    /** 编码 RuntimeVisibleAnnotations 属性（含属性名索引与长度前缀）。返回 null 表示没有注解。 */
    private byte[] encodeAnnotations(List<Anno> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        ByteOut body = new ByteOut(16 + list.size() * 16);
        body.u2(list.size());
        for (Anno a : list) {
            body.u2(cp.utf8(a.desc()));
            java.util.Map<String, Object> values = a.values();
            body.u2(values.size());
            for (java.util.Map.Entry<String, Object> e : values.entrySet()) {
                body.u2(cp.utf8(e.getKey()));
                Object v = e.getValue();
                if (v instanceof Number n) {
                    body.u1('I').u2(cp.integer(n.intValue()));
                } else {
                    body.u1('s').u2(cp.utf8(String.valueOf(v)));
                }
            }
        }
        byte[] b = body.toByteArray();
        ByteOut attr = new ByteOut(b.length + 8);
        attr.u2(cp.utf8("RuntimeVisibleAnnotations")).u4(b.length).raw(b, 0, b.length);
        return attr.toByteArray();
    }

    public byte[] build() {
        // 关键顺序：先把这个类直接引用到的所有常量登记进池（常量池一旦序列化，计数就固定了），
        // 再序列化池、再写主体。字段/方法在 add* 阶段就已经登记完毕。
        int thisIdx = cp.classRef(internalName);
        int superIdx = cp.classRef(superName);
        int[] ifaceIdx = new int[interfaces.size()];
        for (int i = 0; i < interfaces.size(); i++) {
            ifaceIdx[i] = cp.classRef(interfaces.get(i));
        }
        // 注解属性必须在常量池序列化之前编码好（编码过程会往池里加常量）。
        byte[] classAnnoAttr = encodeAnnotations(annotations);

        ByteOut o = new ByteOut(1024);
        o.u4(ClassFile.MAGIC).u2(0).u2(version);
        byte[] pool = cp.build();
        o.raw(pool, 0, pool.length);
        o.u2(access).u2(thisIdx).u2(superIdx);
        o.u2(interfaces.size());
        for (int i : ifaceIdx) {
            o.u2(i);
        }
        o.u2(fields.size());
        for (byte[] f : fields) {
            o.raw(f, 0, f.length);
        }
        o.u2(methods.size());
        for (byte[] m : methods) {
            o.raw(m, 0, m.length);
        }
        if (classAnnoAttr == null) {
            o.u2(0);
        } else {
            o.u2(1);
            o.raw(classAnnoAttr, 0, classAnnoAttr.length);
        }
        return o.toByteArray();
    }

    /** 极简常量池：只支持生成器自己需要的那几种项，用 Map 去重。 */
    private static final class ConstPool {
        private final ByteOut poolBytes = new ByteOut(512);
        private final java.util.Map<String, Integer> index = new java.util.HashMap<>();
        private int next = 1;

        int utf8(String s) {
            String key = "U" + s;
            Integer hit = index.get(key);
            if (hit != null) {
                return hit;
            }
            int idx = next++;
            index.put(key, idx);
            poolBytes.u1(ClassFile.CP_UTF8);
            int pos = poolBytes.size();
            poolBytes.u2(0);
            int len = poolBytes.mutf8(s);
            byte[] raw = poolBytes.rawBuffer();
            raw[pos] = (byte) (len >>> 8);
            raw[pos + 1] = (byte) len;
            return idx;
        }

        int classRef(String internalName) {
            String key = "C" + internalName;
            Integer hit = index.get(key);
            if (hit != null) {
                return hit;
            }
            int utf = utf8(internalName);
            int idx = next++;
            index.put(key, idx);
            poolBytes.u1(ClassFile.CP_CLASS).u2(utf);
            return idx;
        }

        int integer(int v) {
            String key = "I" + v;
            Integer hit = index.get(key);
            if (hit != null) {
                return hit;
            }
            int idx = next++;
            index.put(key, idx);
            poolBytes.u1(ClassFile.CP_INT).u4(v);
            return idx;
        }

        int methodref(String owner, String name, String desc) {
            String key = "M" + owner + name + desc;
            Integer hit = index.get(key);
            if (hit != null) {
                return hit;
            }
            int c = classRef(owner);
            int nt = nameAndType(name, desc);
            int idx = next++;
            index.put(key, idx);
            poolBytes.u1(ClassFile.CP_METHODREF).u2(c).u2(nt);
            return idx;
        }

        int fieldref(String owner, String name, String desc) {
            String key = "F" + owner + name + desc;
            Integer hit = index.get(key);
            if (hit != null) {
                return hit;
            }
            int c = classRef(owner);
            int nt = nameAndType(name, desc);
            int idx = next++;
            index.put(key, idx);
            poolBytes.u1(ClassFile.CP_FIELDREF).u2(c).u2(nt);
            return idx;
        }

        int string(String value) {
            String key = "S" + value;
            Integer hit = index.get(key);
            if (hit != null) {
                return hit;
            }
            int u = utf8(value);
            int idx = next++;
            index.put(key, idx);
            poolBytes.u1(ClassFile.CP_STRING).u2(u);
            return idx;
        }

        private int nameAndType(String name, String desc) {
            String key = "N" + name + desc;
            Integer hit = index.get(key);
            if (hit != null) {
                return hit;
            }
            int n = utf8(name);
            int d = utf8(desc);
            int idx = next++;
            index.put(key, idx);
            poolBytes.u1(ClassFile.CP_NAMEANDTYPE).u2(n).u2(d);
            return idx;
        }

        byte[] build() {
            ByteOut o = new ByteOut(poolBytes.size() + 2);
            o.u2(next);
            o.raw(poolBytes.rawBuffer(), 0, poolBytes.size());
            return o.toByteArray();
        }
    }
}
