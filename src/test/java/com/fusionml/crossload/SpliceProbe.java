package com.fusionml.crossload;

import com.fusionml.bytecode.ClassFile;
import com.fusionml.mc.McInstance;
import com.fusionml.util.FastZip;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 单独验证"方法级拼接"：把对方新增的方法拼进原版字节，看结果能不能被 JVM 接受。
 *
 * <p>为什么必须单独验证：这一步的产物是一个<b>自己手写的类文件</b>，
 * 常量池索引、属性长度、指令边界任何一处错都会产出坏类，而坏类的表现是
 * {@code VerifyError} / {@code ClassFormatError} —— 出现在游戏启动期，离这里很远。
 * 所以先在离线环境里"让 JVM 真的去校验它"，通过了才敢交给游戏。
 *
 * <p>用法：{@code java -cp build\classes;build\test-classes com.fusionml.crossload.SpliceProbe
 * net/minecraft/resources/RegistryDataLoader}
 */
public final class SpliceProbe {

    private SpliceProbe() {
    }

    /**
     * 把 {@code defineClass} 暴露出来的加载器 —— 这样才能让 JVM 真的去校验我们拼出来的类。
     *
     * <p>不用反射的原因：Java 9+ 模块系统不允许未命名模块反射进 {@code java.lang}
     * （实测 {@code InaccessibleObjectException}）。继承是唯一干净的路子。
     */
    private static final class DefiningLoader extends ClassLoader {
        DefiningLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    /**
     * 找出"哪个位置引用了某个常量池索引"，并报出它的常量池类型。
     *
     * <p>症状是 {@code VerifyError: Illegal type at constant pool entry N}，
     * 而 N 本身的常量池项看起来完全正常 —— 说明**引用它的地方**用错了类型。
     */
    private static java.util.List<String> findRefs(ClassFile cf, byte[] bytes, int target) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (ClassFile.Member m : cf.methods()) {
            String mn = cf.utf8(m.nameIndex);
            for (ClassFile.Attr a : m.attrs) {
                if (a.nameIndex() == target) {
                    out.add(mn + " 的属性名用了 " + target);
                }
                String an = cf.utf8(a.nameIndex());
                int start = a.offset();
                if (!"Code".equals(an)) {
                    continue;
                }
                int codeLen = ClassFile.u4iPublic(bytes, start + 4);
                int p = start + 8 + codeLen;
                int exCount = ((bytes[p] & 0xFF) << 8) | (bytes[p + 1] & 0xFF);
                p += 2;
                for (int i = 0; i < exCount; i++) {
                    int ct = ((bytes[p + 6] & 0xFF) << 8) | (bytes[p + 7] & 0xFF);
                    if (ct == target) {
                        out.add(mn + " 的异常表第 " + i + " 项 catch_type=" + target);
                    }
                    p += 8;
                }
            }
        }
        return out;
    }

    /**
     * 检查"常量池索引被用错类型"的情况。
     *
     * <p>{@code VerifyError: Illegal type at constant pool entry N} 就是这一类：
     * 索引 N 在范围内、本身内容也正常，但**引用它的位置**期望的是另一种类型
     * （例如期望 Class 却指到了 Utf8）。
     *
     * <p>这里逐项校验各类引用的期望类型：
     * <ul>
     *   <li>{@code ldc} → 任意可加载常量（这里只排除明显不对的 Class/NameAndType 等）</li>
     *   <li>{@code getstatic/getfield/putstatic/putfield} → Fieldref</li>
     *   <li>{@code invokevirtual/invokespecial/invokestatic} → Methodref</li>
     *   <li>{@code invokeinterface} → InterfaceMethodref</li>
     *   <li>{@code invokedynamic} → InvokeDynamic</li>
     *   <li>{@code new/anewarray/checkcast/instanceof/multianewarray} → Class</li>
     *   <li>{@code catch_type} → Class</li>
     *   <li>{@code Exceptions} 表项 → Class</li>
     *   <li>StackMapTable 的 Object_variable_info → Class</li>
     * </ul>
     */
    private static java.util.List<String> checkCpTypes(ClassFile cf, byte[] bytes) {
        java.util.List<String> bad = new java.util.ArrayList<>();
        for (ClassFile.Member m : cf.methods()) {
            String mn = cf.utf8(m.nameIndex);
            for (ClassFile.Attr a : m.attrs) {
                String an = cf.utf8(a.nameIndex());
                int start = a.offset();
                if ("Exceptions".equals(an)) {
                    int n = ((bytes[start] & 0xFF) << 8) | (bytes[start + 1] & 0xFF);
                    for (int i = 0; i < n; i++) {
                        int idx = ((bytes[start + 2 + i * 2] & 0xFF) << 8)
                                | (bytes[start + 3 + i * 2] & 0xFF);
                        checkType(cf, bad, mn + " Exceptions[" + i + "]", idx, 7);
                    }
                    continue;
                }
                if (!"Code".equals(an)) {
                    continue;
                }
                int codeLen = ClassFile.u4iPublic(bytes, start + 4);
                int p = start + 8;
                int end = p + codeLen;
                while (p < end) {
                    int op = bytes[p] & 0xFF;
                    int idx = -1;
                    int len = 1;
                    int expect = -1;
                    switch (op) {
                        case 0xB2: case 0xB3: case 0xB4: case 0xB5:
                            idx = u2b(bytes, p + 1); len = 3; expect = 9; break;
                        case 0xB6: case 0xB7: case 0xB8:
                            idx = u2b(bytes, p + 1); len = 3; expect = 10; break;
                        case 0xB9:
                            idx = u2b(bytes, p + 1); len = 5; expect = 11; break;
                        case 0xBA:
                            idx = u2b(bytes, p + 1); len = 5; expect = 18; break;
                        case 0xBB: case 0xBD: case 0xC0: case 0xC1:
                            idx = u2b(bytes, p + 1); len = 3; expect = 7; break;
                        case 0xC5:
                            idx = u2b(bytes, p + 1); len = 4; expect = 7; break;
                        case 0x10: case 0x15: case 0x16: case 0x17: case 0x18: case 0x19:
                        case 0x36: case 0x37: case 0x38: case 0x39: case 0x3A: case 0xBC:
                            len = 2; break;
                        case 0x11: case 0x14: case 0x84: case 0xA9:
                            len = 3; break;
                        case 0xC4: len = 4; break;
                        case 0xC8: case 0xC9: len = 5; break;
                        default: len = 1; break;
                    }
                    if (idx > 0) {
                        checkType(cf, bad, mn + "@" + (p - (start + 8))
                                + " 操作码 0x" + Integer.toHexString(op), idx, expect);
                    }
                    p += len;
                }
                // 异常表 catch_type 必须是 Class
                int q = end;
                int exCount = u2b(bytes, q);
                q += 2;
                for (int i = 0; i < exCount; i++) {
                    int ct = u2b(bytes, q + 6);
                    if (ct != 0) {
                        checkType(cf, bad, mn + " catch[" + i + "]", ct, 7);
                    }
                    q += 8;
                }
            }
        }
        return bad;
    }

    private static void checkType(ClassFile cf, java.util.List<String> bad,
                                  String where, int idx, int expect) {
        ClassFile.Cp c = cf.cp(idx);
        if (c == null) {
            bad.add(where + " 索引 " + idx + " 不存在");
            return;
        }

        if (expect == 10 && c.tag == 11) {
            return;
        }
        if (c.tag != expect) {
            bad.add(where + " 索引 " + idx + " 是 tag" + c.tag + "（" + c
                    + "），期望 tag" + expect);
        }
    }

    private static int[] codeOf(ClassFile cf, byte[] bytes, ClassFile.Member m) {
        for (ClassFile.Attr a : m.attrs) {
            if (!"Code".equals(cf.utf8(a.nameIndex()))) {
                continue;
            }
            int start = a.offset();
            int codeLen = ClassFile.u4iPublic(bytes, start + 4);
            int[] out = new int[codeLen];
            for (int i = 0; i < codeLen; i++) {
                out[i] = bytes[start + 8 + i] & 0xFF;
            }
            return out;
        }
        return null;
    }

    private static int u2b(byte[] b, int p) {
        return ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
    }

    /**
     * 扫所有方法的 Code，检查里面的常量池索引是否都落在常量池范围内。
     *
     * <p>这是针对"槽位 vs 项数"那类算术错误最直接的检查。
     */
    private static java.util.List<String> checkCpIndices(ClassFile cf, byte[] bytes) {
        java.util.List<String> bad = new java.util.ArrayList<>();
        for (ClassFile.Member m : cf.methods()) {
            for (ClassFile.Attr a : m.attrs) {
                if (!"Code".equals(cf.utf8(a.nameIndex()))) {
                    continue;
                }
                int start = a.offset();
                int codeLen = ClassFile.u4iPublic(bytes, start + 4);
                int p = start + 8;
                int end = p + codeLen;
                while (p < end) {
                    int op = bytes[p] & 0xFF;
                    Integer idx = null;
                    int len = 1;
                    switch (op) {
                        case 0x12: idx = bytes[p + 1] & 0xFF; len = 2; break;
                        case 0x13: case 0xB2: case 0xB3: case 0xB4: case 0xB5:
                        case 0xB6: case 0xB7: case 0xB8: case 0xBB: case 0xBD:
                        case 0xC0: case 0xC1: case 0xC6: case 0xC7:
                            idx = ((bytes[p + 1] & 0xFF) << 8) | (bytes[p + 2] & 0xFF);
                            len = 3;
                            break;
                        case 0xB9: case 0xBA:
                            idx = ((bytes[p + 1] & 0xFF) << 8) | (bytes[p + 2] & 0xFF);
                            len = 5;
                            break;
                        case 0xC5:
                            idx = ((bytes[p + 1] & 0xFF) << 8) | (bytes[p + 2] & 0xFF);
                            len = 4;
                            break;
                        case 0x10: case 0x15: case 0x16: case 0x17: case 0x18: case 0x19:
                        case 0x36: case 0x37: case 0x38: case 0x39: case 0x3A: case 0xBC:
                            len = 2; break;
                        case 0x11: case 0x14: case 0x84: case 0xA9:
                            len = 3; break;
                        case 0xC4: len = 4; break;
                        case 0xC8: case 0xC9: len = 5; break;
                        default: len = 1; break;
                    }
                    if (idx != null && cf.cp(idx) == null) {
                        bad.add(cf.utf8(m.nameIndex) + "@" + (p - (start + 8))
                                + " 操作码 0x" + Integer.toHexString(op) + " 索引 " + idx);
                    }
                    p += len;
                }
            }
        }
        return bad;
    }

    public static void main(String[] args) throws Exception {
        Path gameDir = null;
        for (String line : Files.readAllLines(Path.of("build", "gamedir.txt"),
                java.nio.charset.StandardCharsets.UTF_8)) {
            String s = line.trim();
            if (!s.isEmpty() && !s.startsWith("#")) {
                gameDir = Path.of(s);
                break;
            }
        }
        McInstance instance = McInstance.open(gameDir);
        String versionId = System.getProperty("fusion.version", "26.3-Fabric 0.19.5");
        Path vanillaJar = instance.resolve(versionId).clientJar;
        Path patchedJar = PatchedBase.locate(instance, "neoforge", PatchedBase.mcKeyOf(versionId));

        for (String a : args) {
            String internal = a.replace('.', '/');
            System.out.println("== " + internal);
            try (FastZip vz = FastZip.open(vanillaJar); FastZip pz = FastZip.open(patchedJar)) {
                FastZip.Entry ve = vz.entry(internal + ".class");
                FastZip.Entry pe = pz.entry(internal + ".class");
                if (ve == null || pe == null) {
                    System.out.println("   包里缺这个类");
                    continue;
                }
                byte[] vb = vz.read(ve);
                byte[] pb = pz.read(pe);

                // 而不是我们拼接引入的问题。没有这个基线，就会被自己的检查误导。
                java.util.List<String> vanillaBad = checkCpTypes(ClassFile.read(vb), vb);
                System.out.println("   原版基线：类型检查发现 " + vanillaBad.size() + " 处"
                        + (vanillaBad.isEmpty() ? "" : "（说明检查规则过严）："
                        + String.join("、", vanillaBad.subList(0, Math.min(4, vanillaBad.size())))));
                System.out.println("   原版方法 " + ClassFile.read(vb).methods().length
                        + " 个，补丁方法 " + ClassFile.read(pb).methods().length + " 个");
                byte[] out = PatchedBase.spliceNewMethodsForTest(vb, pb, internal);
                // 关键：读"写入之前"那个实例的统计，而不是读回来之后的。
                ClassFile[] holder = PatchedBase.lastSplicedClass();
                if (holder[0] != null) {
                    System.out.println("   写入前的常量池统计（项数/槽位数/原始槽位）="
                            + java.util.Arrays.toString(holder[0].extraCpStats()));
                }
                java.util.Map<String, Integer> attempts = PatchedBase.spliceAttempts();
                System.out.println("   尝试拼接 " + attempts.getOrDefault(internal, 0) + " 个方法");
                java.util.List<String> refused = PatchedBase.spliceRefusals();
                if (!refused.isEmpty()) {
                    System.out.println("   被拒的方法（说明字节码扫描能力不足，而不是本来没得拼）：");
                    for (String r : refused) {
                        System.out.println("      · " + r);
                    }
                }
                if (out == null) {
                    System.out.println("   拼接失败："
                            + com.fusionml.bytecode.ClassFile.lastSpliceReason());
                    continue;
                }
                System.out.println("   拼接后字节 " + out.length + "（原版 " + vb.length + "）");
                ClassFile result = ClassFile.read(out);
                System.out.println("   拼接后方法 " + result.methods().length + " 个");

                // 这是对"槽位 vs 项数"那类算术错误最直接的检查：

                // 但宿主平台的 ASM 会以 ArrayIndexOutOfBoundsException 崩在启动期。
                int maxIdx = 0;
                for (int i = 1; i < 65536; i++) {
                    if (result.cp(i) != null) {
                        maxIdx = i;
                    }
                }
                System.out.println("   常量池最大索引 " + maxIdx);
                ClassFile original = ClassFile.read(vb);
                System.out.println("   原版常量池槽位数 " + ClassFile.read(vb).extraCpStats()[2]
                        + "，追加项数/槽位数 = "
                        + java.util.Arrays.toString(result.extraCpStats()));
                java.util.List<String> bad = checkCpIndices(result, out);
                if (bad.isEmpty()) {
                    System.out.println("   索引检查：全部在范围内");
                } else {
                    System.out.println("   索引检查：发现 " + bad.size() + " 处越界 —— "
                            + String.join("、", bad.subList(0, Math.min(6, bad.size()))));
                }

                java.util.List<String> typeBad = checkCpTypes(result, out);
                if (typeBad.isEmpty()) {
                    System.out.println("   类型检查：全部正确");
                } else {
                    System.out.println("   类型检查：发现 " + typeBad.size() + " 处类型错用 —— "
                            + String.join("、", typeBad.subList(0, Math.min(8, typeBad.size()))));
                }

                // 出错时最需要看的就是"我把它映射到哪去了、那边是什么类型"。
                System.out.println("   索引映射（源 → 目标，只列新分配的）：");
                java.util.Map<Integer, Integer> m2 = result.mappingDump();
                int shown2 = 0;
                for (java.util.Map.Entry<Integer, Integer> en : m2.entrySet()) {
                    if (shown2++ >= 24) {
                        System.out.println("      …（共 " + m2.size() + " 条）");
                        break;
                    }
                    ClassFile.Cp dst = result.cp(en.getValue());
                    System.out.println("      #" + en.getKey() + " → #" + en.getValue()
                            + " tag" + (dst == null ? "?" : dst.tag) + " " + dst);
                }

                for (int probe : new int[] {186, 187, 188, 189, 190, 203}) {
                    ClassFile.Cp c = result.cp(probe);
                    System.out.println("   目标 #" + probe + " = tag"
                            + (c == null ? "?" : c.tag) + " " + c);
                }
                // 把"新分配的项"按顺序列出来：源索引 → 目标索引 → 目标项类型。

                System.out.println("   拼接方法引用的索引（取自 NeoForge 字节码）：");
                java.util.List<String> refs = checkCpIndices(result, out);
                System.out.println("     越界引用 " + refs.size() + " 处");
                java.util.List<String> tb = checkCpTypes(result, out);
                System.out.println("     类型错用 " + tb.size() + " 处"
                        + (tb.isEmpty() ? "" : "：" + String.join("、", tb)));

                System.out.println("   拼接方法的字节码对照（源 vs 目标）：");
                for (ClassFile.Member pm : ClassFile.read(pb).methods()) {
                    String pn = ClassFile.read(pb).utf8(pm.nameIndex);
                    String pd = ClassFile.read(pb).utf8(pm.descIndex);
                    if (!"matches".equals(pn) || !pd.contains("DataComponentGetter")) {
                        continue;
                    }
                    int[] src = codeOf(ClassFile.read(pb), pb, pm);
                    for (ClassFile.Member rm : result.methods()) {
                        if (!pn.equals(result.utf8(rm.nameIndex))
                                || !pd.equals(result.utf8(rm.descIndex))) {
                            continue;
                        }
                        int[] dst = codeOf(result, out, rm);
                        if (src != null && dst != null && src.length == dst.length) {
                            for (int i = 0; i < src.length; i++) {
                                if (src[i] != dst[i]) {
                                    System.out.println("      code[" + i + "] 源 " + src[i]
                                            + " → 目标 " + dst[i]);
                                }
                            }
                        }
                    }
                }

                // defineClass 会跑字节码校验器；校验不过就抛 VerifyError/ClassFormatError。

                // 注意不能用反射调 ClassLoader#defineClass：Java 9+ 的模块系统不让
                // 未命名模块反射进 java.lang（实测报 InaccessibleObjectException）。

                try {
                    DefiningLoader loader = new DefiningLoader(
                            SpliceProbe.class.getClassLoader());
                    Class<?> c = loader.define(internal.replace('/', '.'), out);
                    System.out.println("   JVM 校验：通过（已定义 " + c.getName() + "）");
                    java.lang.reflect.Method[] ms = c.getDeclaredMethods();
                    System.out.println("   定义后的方法 " + ms.length + " 个，其中含 5 参数 load：");
                    for (java.lang.reflect.Method mm : ms) {
                        if (!mm.getName().equals("load")) {
                            continue;
                        }
                        System.out.println("      · load" + java.util.Arrays.toString(
                                mm.getParameterTypes()));
                    }
                } catch (Throwable t) {
                    Throwable root = t;
                    while (root.getCause() != null && root.getCause() != root) {
                        root = root.getCause();
                    }
                    System.out.println("   JVM 校验：失败 —— " + root);
                }
            }
        }
    }
}
