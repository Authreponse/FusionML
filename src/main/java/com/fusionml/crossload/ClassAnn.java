package com.fusionml.crossload;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 类文件注解读取器（自研，不依赖 ASM）。
 *
 * <p>用途：在"还没有加载这个类"的前提下，从 jar 里的 .class 字节直接读出它带了哪些注解、
 * 注解的元素值是什么。跨端驱动要靠它做两件事：
 * <ol>
 *   <li>找出非宿主平台的模组入口点（NeoForge 的 {@code @Mod}），</li>
 *   <li>找出 {@code @EventBusSubscriber} 需要挂到事件总线上的类。</li>
 * </ol>
 *
 * <p>为什么不直接反射？反射必须先把类加载进来，而"加载一个我们还不确定要不要加载的类"
 * 本身就有代价与风险（静态初始化会真的跑起来）。先读字节、再决定加载谁，
 * 是加载器的常规做法，也更符合"发现"与"加载"分离的设计。
 *
 * <p>解析范围就是 JVMS 里 {@code RuntimeVisibleAnnotations} / {@code RuntimeInvisibleAnnotations}
 * 需要的那些常量池项与元素值类型（含数组、枚举、嵌套注解）。
 */
public final class ClassAnn {

    /**
     * 一条注解。
     *
     * @param desc        注解类型描述符，例如 {@code Lnet/neoforged/fml/common/Mod;}
     * @param elementType 注解出现的位置：TYPE / METHOD / FIELD
     * @param className   被注解的类的内部名（点号分隔的二元名）
     * @param memberName  方法名或字段名；类上的注解为 null
     * @param values      元素名 → 元素值（String / 数值 / Object[] / Map）
     */
    public record Ann(String desc, String elementType, String className, String memberName,
                      Map<String, Object> values) {

        /** 取字符串元素（不存在或类型不符时返回 null）。 */
        public String str(String key) {
            Object v = values.get(key);
            return v instanceof String s ? s : null;
        }

        /** 取数组元素里的字符串（单个字符串也接受）。 */
        public List<String> strList(String key) {
            Object v = values.get(key);
            List<String> out = new ArrayList<>(2);
            if (v instanceof String s) {
                out.add(s);
            } else if (v instanceof Object[] arr) {
                for (Object o : arr) {
                    if (o instanceof String s) {
                        out.add(s);
                    }
                }
            }
            return out;
        }
    }

    private ClassAnn() {
    }

    /** 扫描一个包，返回"类名 → 该类（含其成员）上的注解"。类名为点号形式。 */
    public static Map<String, List<Ann>> scanJar(Path jar, Consumer<String> warn) {
        Map<String, List<Ann>> out = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                if (e.isDirectory() || !name.endsWith(".class")) {
                    continue;
                }
                byte[] bytes;
                try (InputStream in = zip.getInputStream(e)) {
                    bytes = in.readAllBytes();
                }
                try {
                    List<Ann> anns = readAnnotations(bytes);
                    if (!anns.isEmpty()) {
                        out.put(anns.get(0).className(), anns);
                    }
                } catch (RuntimeException ex) {
                    if (warn != null) {
                        warn.accept("读取注解失败 " + name + "：" + ex.getMessage());
                    }
                }
            }
        } catch (IOException ex) {
            if (warn != null) {
                warn.accept("打开包失败 " + jar.getFileName() + "：" + ex.getMessage());
            }
        }
        return out;
    }

    /** 读取一个类文件的全部注解（类 / 字段 / 方法）。 */
    public static List<Ann> readAnnotations(byte[] b) {
        Reader r = new Reader(b);
        r.u4();                                  // magic
        r.u2();                                  // minor
        r.u2();                                  // major

        int cpCount = r.u2();
        String[] utf8 = new String[cpCount];
        int[] classNameIdx = new int[cpCount];
        for (int i = 1; i < cpCount; i++) {
            int tag = r.u1();
            switch (tag) {
                case 1 -> utf8[i] = r.utf8();
                case 7 -> classNameIdx[i] = r.u2();
                case 8, 16, 19, 20 -> r.u2();
                case 15 -> {
                    r.u1();
                    r.u2();
                }
                case 3, 4, 9, 10, 11, 12, 17, 18 -> r.u4();
                case 5, 6 -> {
                    r.u4();
                    r.u4();
                    i++;
                }
                default -> throw new IllegalStateException("未知常量池标签 " + tag);
            }
        }
        r.u2();                                  // access
        int thisClass = r.u2();
        r.u2();                                  // super
        int ifaces = r.u2();
        for (int i = 0; i < ifaces; i++) {
            r.u2();
        }
        String className = utf8[classNameIdx[thisClass]].replace('/', '.');
        List<Ann> anns = new ArrayList<>(2);

        int fields = r.u2();
        for (int i = 0; i < fields; i++) {
            r.u2();
            int nameIdx = r.u2();
            r.u2();
            readMemberAttributes(r, utf8, className, utf8[nameIdx], "FIELD", anns);
        }
        int methods = r.u2();
        for (int i = 0; i < methods; i++) {
            r.u2();
            int nameIdx = r.u2();
            r.u2();
            readMemberAttributes(r, utf8, className, utf8[nameIdx], "METHOD", anns);
        }
        readMemberAttributes(r, utf8, className, null, "TYPE", anns);
        return anns;
    }

    private static void readMemberAttributes(Reader r, String[] utf8, String className,
                                             String member, String elementType, List<Ann> out) {
        int attrs = r.u2();
        for (int i = 0; i < attrs; i++) {
            String attrName = utf8[r.u2()];
            int len = r.u4();
            if ("RuntimeVisibleAnnotations".equals(attrName)
                    || "RuntimeInvisibleAnnotations".equals(attrName)) {
                int count = r.u2();
                for (int a = 0; a < count; a++) {
                    String desc = utf8[r.u2()];
                    Map<String, Object> values = new LinkedHashMap<>(2);
                    int pairs = r.u2();
                    for (int p = 0; p < pairs; p++) {
                        String key = utf8[r.u2()];
                        values.put(key, elementValue(r, utf8));
                    }
                    out.add(new Ann(desc, elementType, className, member, values));
                }
            } else {
                r.skip(len);
            }
        }
    }

    /** 读一个 element_value（JVMS 4.7.16.1）。 */
    private static Object elementValue(Reader r, String[] utf8) {
        int tag = r.u1();
        return switch (tag) {
            case 'B', 'C', 'I', 'S', 'Z' -> r.u2const();
            case 'D' -> r.u2const();
            case 'F' -> r.u2const();
            case 'J' -> r.u2const();
            case 's' -> utf8[r.u2()];
            case 'e' -> {
                r.u2();                          // 枚举类型
                yield utf8[r.u2()];              // 常量名
            }
            case 'c' -> utf8[r.u2()];
            case '@' -> {
                Map<String, Object> nested = new LinkedHashMap<>(2);
                r.u2();                          // 注解类型
                int pairs = r.u2();
                for (int p = 0; p < pairs; p++) {
                    String key = utf8[r.u2()];
                    nested.put(key, elementValue(r, utf8));
                }
                yield nested;
            }
            case '[' -> {
                int n = r.u2();
                Object[] arr = new Object[n];
                for (int i = 0; i < n; i++) {
                    arr[i] = elementValue(r, utf8);
                }
                yield arr;
            }
            default -> throw new IllegalStateException("未知注解元素标签 " + (char) tag);
        };
    }

    /** 只读不解释的游标。 */
    private static final class Reader {
        private final byte[] b;
        private int p;

        /** 常量池下标（读出来还要到池里查真实值，所以先记为"待解"）。 */
        private final Map<Integer, Object> pending = new HashMap<>(8);

        Reader(byte[] b) {
            this.b = b;
        }

        int u1() {
            return b[p++] & 0xFF;
        }

        int u2() {
            return ((b[p++] & 0xFF) << 8) | (b[p++] & 0xFF);
        }

        int u4() {
            return ((b[p++] & 0xFF) << 24) | ((b[p++] & 0xFF) << 16)
                    | ((b[p++] & 0xFF) << 8) | (b[p++] & 0xFF);
        }

        /** 元素值里指向常量池的索引：当前只用于数值类型，统一按 int 返回。 */
        Object u2const() {
            return u2();
        }

        String utf8() {
            int len = u2();
            String s = new String(b, p, len, java.nio.charset.StandardCharsets.UTF_8);
            p += len;
            return s;
        }

        void skip(int n) {
            p += n;
        }
    }
}
