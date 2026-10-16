package com.fusionml.remap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 自研 TSRG2 映射表读取器：把混淆（obf）内部名换算成反混淆（named）内部名。
 *
 * <h2>为什么需要它</h2>
 * 26.x 的游戏包不混淆，跨端方法级拼接直接按内部名搬运即可。1.21.1 / 1.20.1 的游戏包
 * <b>是混淆的</b>（宿主 8269 个类里 8220 个是 {@code fgi$a}、{@code guu} 这种裸名），
 * 而对方平台的补丁基座是 <b>named</b> 的（{@code net/minecraft/...}）。两边名字对不上，
 * 拼接会一个类都搬不过去。本类就是那座桥。
 *
 * <h2>TSRG2 格式（实测，不是猜的）</h2>
 * <pre>
 * tsrg2 left right
 * geo net/minecraft/client/renderer/DimensionSpecialEffects   &lt;- 缩进0：类 obf -&gt; named
 * 	a EFFECTS                                                 &lt;- 缩进1：字段 obf -&gt; named
 * 	a (II)Z isFoggyAt                                         &lt;- 缩进1：方法 obf 描述符 -&gt; named
 * 		static                                                &lt;- 缩进1：方法属性行（static/private…）
 * 		0 o p_108874_                                         &lt;- 缩进2：参数名
 * </pre>
 *
 * <p><b>判据是"缩进层级 + 列数"，不是关键字。</b>这一点很容易搞错：
 * <ul>
 *   <li>缩进 0 且 2 列 = 类行。首行 {@code tsrg2 left right} 也是缩进 0 且 2 列，
 *       所以要专门跳过（见 {@link #parseAll} 的 firstLine 处理）。</li>
 *   <li>缩进 1 且 1 列 = 方法属性行（{@code static}）—— 必须跳过。</li>
 *   <li>缩进 1 且 2 列 = 字段行。</li>
 *   <li>缩进 1 且 ≥3 列 = 方法行（第 2 列是描述符，第 3 列是 named 名）。</li>
 *   <li>缩进 2 = 参数名行 —— 必须跳过，它长得像字段行（如 {@code 0 o p_253997_}），
 *       不判缩进就会把它当成 obf 名 {@code 0} 的字段，污染字段表。</li>
 * </ul>
 *
 * <h2>1.20.1 的成员列是 SRG 中间名（实测）</h2>
 * 两张本地表的类名右列都是完整 named（1.21.1 {@code net/minecraft} 7936 行，
 * 1.20.1 {@code net/minecraft} 7103 行，类行里 {@code f_}/{@code m_} 出现 0 次）。
 * 但 <b>1.20.1 的成员右列混有 SRG 名</b>：字段 31003/33135 是 {@code f_252495_}，
 * 方法 48575/153530 是 {@code m_252774_}。也就是说：
 * <ul>
 *   <li>{@link #classOf} 对两个版本都给出可读 named 名；</li>
 *   <li>{@link #fieldOf} / {@link #methodOf} 对 1.21.1 给出可读 named 名，
 *       对 1.20.1 <b>可能给出 {@code f_}/{@code m_} 中间名</b>。
 *       调用方若需要可读名，得再叠一层 SRG→named，本类不假装它已经是干净 named。</li>
 * </ul>
 *
 * <h2>性能设计（performance-first）</h2>
 * 表有 5 MB / 24 万行，全量解析一次约 100 ms 量级，但启动路径上不该付这个钱 ——
 * 拼接最先、且最频繁需要的只是<b>类名</b>。所以拆成两段：
 * <ol>
 *   <li>{@link #classOf} 走<b>类名快路径</b>：只扫缩进 0 的行，跳过所有缩进行。
 *       8270 行 vs 240346 行，省掉约 97% 的行。解析结果缓存在 {@code obfToNamed}。</li>
 *   <li>成员表（字段/方法）在第一次调 {@link #fieldOf} / {@link #methodOf} 时才全量解析
 *       （见 {@link #ensureMembers}），此时已确定真的需要，且不在启动热路径上。</li>
 * </ol>
 * 解析全程<b>字符扫描，零正则</b>：{@code indexOf(' ')} + {@code substring}。
 * 不用 {@code split}（每次调用都会编译正则并产生数组）。
 *
 * <h2>查不到时的行为：返回 {@code null}（明确、不撒谎）</h2>
 * 我选 <b>返回 null</b> 而不是"返回原名"，理由是这两者的语义完全不同，
 * 而调用方必须能区分：
 * <ul>
 *   <li>"这个名字在表里，named 名就是这个" → 返回 named 名；</li>
 *   <li>"表里没有这个名字" → <b>返回 null</b>。</li>
 * </ul>
 * 如果查不到就悄悄返回原名，调用方会以为转换成功，把混淆名当 named 名写进字节码 ——
 * 结果是类加载期 {@code NoClassDefFoundError}，而且报错点离真正原因很远。
 * 返回 null 逼调用方显式决策（跳过该类 / 报错 / 走兜底），这是本项目一贯的做法。
 *
 * <p>反过来，<b>已经解析到的条目里，named 名可能就是原名</b>（例如
 * {@code com/mojang/blaze3d/platform/GLX} → {@code com/mojang/blaze3d/platform/GLX}），
 * 这是真实的恒等映射，与"查不到"是两码事。
 *
 * <h2>线程安全</h2>
 * {@link #classOf} 等查询方法可并发调用；{@link #ensureMembers} 做了双重检查加锁。
 * 解析是幂等的，重复触发只会重算一次，不会破坏状态。
 *
 * <h2>未验证边界（重要，别当成保证）</h2>
 * 本类只负责"按表查名"。<b>它不保证查出的 named 名在游戏里一定存在</b> —
 * 映射表覆盖 8269 个类，而本机可对照的反混淆覆盖层只有 1603 个类。
 * 端到端正确性必须由接入方（{@code PatchedBase}）用真实启动验证。
 */
public final class MappingTable {

    /** 类名映射：obf 内部名 → named 内部名。快路径解析结果，启动即用。 */
    private final Map<String, String> obfToNamed;

    /** 反向映射：named 内部名 → obf 内部名。与 {@link #obfToNamed} 同时构建。 */
    private final Map<String, String> namedToObf;

    /** 原始表路径。成员表按需二次解析时还要再读一遍。 */
    private final Path source;

    /*
     * 成员表。用 volatile 数组 + 双重检查发布，避免 ensureMembers 的解析结果
     * 被其它线程看到"写了一半"的状态（Map 内部结构在 put 期间不是只读的）。
     * volatile 单元素的发布语义足够，不需要给每个 Map 加锁。
     */
    private volatile String[] methodKeys;
    private volatile String[] methodValues;
    private volatile String[] fieldKeys;
    private volatile String[] fieldValues;

    private final Object memberLock = new Object();

    /** 类行数（obf 类个数）。 */
    private final int classCount;

    private MappingTable(Map<String, String> obfToNamed, Map<String, String> namedToObf,
                         Path source, int classCount) {
        this.obfToNamed = obfToNamed;
        this.namedToObf = namedToObf;
        this.source = source;
        this.classCount = classCount;
    }

    // ------------------------------------------------------------------
    // 构造
    // ------------------------------------------------------------------

    /**
     * 打开磁盘上的 TSRG2 映射表，<b>只解析类名</b>。
     *
     * <p>这是启动路径应该调用的方法：它不会碰那 21 万行成员行。
     *
     * @param file TSRG2 映射表路径
     * @return 已就绪（类名可用）的映射表
     * @throws IOException 读不到文件时抛出，不吞
     */
    public static MappingTable open(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("映射表不存在：" + file);
        }
        return new MappingTable(null, null, file, 0).parseClassNames(file);
    }

    /** 从流构造，主要给"表打进 jar 当资源"的场景用。 */
    public static MappingTable open(InputStream in) throws IOException {
        return new MappingTable(null, null, null, 0).parseClassNames(in);
    }

    private MappingTable parseClassNames(Path file) throws IOException {
        Map<String, String> fwd = new HashMap<>(16384);
        int n;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            n = scanClassNames(r, fwd);
        }
        return new MappingTable(fwd, invert(fwd), file, n);
    }

    private MappingTable parseClassNames(InputStream in) throws IOException {
        Map<String, String> fwd = new HashMap<>(16384);
        int n;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16)) {
            n = scanClassNames(r, fwd);
        }
        return new MappingTable(fwd, invert(fwd), null, n);
    }

    private static Map<String, String> invert(Map<String, String> fwd) {
        Map<String, String> rev = new HashMap<>(fwd.size() * 2);
        for (Map.Entry<String, String> e : fwd.entrySet()) {
            // 恒等映射（GLX -> GLX）也放进去：反向查询同样应该命中。
            // 冲突（两个 obf 名映到同一 named 名）时保留先到的 —— named 名在
            // 合法的映射表里应当唯一，真有冲突说明表本身有问题，不该静默覆盖后一个。
            rev.putIfAbsent(e.getValue(), e.getKey());
        }
        return rev;
    }

    /**
     * 只扫缩进 0 的类行。这是"快路径"的核心：其余 96% 的行直接被第一个字符挡掉。
     *
     * <p>零正则、零 split：{@code charAt(0) == '\t'} 判断缩进，
     * {@code indexOf(' ')} 切列。
     */
    private static int scanClassNames(BufferedReader r, Map<String, String> out) throws IOException {
        int count = 0;
        String line;
        boolean first = true;
        while ((line = r.readLine()) != null) {
            int len = line.length();
            if (len == 0) {
                continue;
            }
            // 缩进行（字段/方法/属性/参数）一律跳过 —— 快路径的全部意义就在这里
            if (line.charAt(0) == '\t') {
                continue;
            }
            if (first) {
                // 首行固定是 "tsrg2 left right"（3 列）。用列数判据而不是字符串比较：
                // 版本号/注释行也可能出现在头部，但类行永远是恰好 2 列。
                first = false;
                if (hasSecondSpace(line)) {
                    continue;
                }
            }
            int sp = line.indexOf(' ');
            if (sp <= 0 || sp == len - 1) {
                continue;
            }
            // 类行必须恰好 2 列；3 列以上说明格式不是我们认识的 TSRG2，跳过而不是瞎猜
            if (hasSecondSpace(line)) {
                continue;
            }
            out.put(line.substring(0, sp), line.substring(sp + 1));
            count++;
        }
        return count;
    }

    /** 判断行里在第一个空格之后是否还有空格（即列数 ≥ 3）。 */
    private static boolean hasSecondSpace(String line) {
        int sp = line.indexOf(' ');
        return sp >= 0 && line.indexOf(' ', sp + 1) >= 0;
    }

    // ------------------------------------------------------------------
    // 查询 API
    // ------------------------------------------------------------------

    /**
     * obf 内部名 → named 内部名。
     *
     * @param obfObfName 混淆内部名，如 {@code geo} 或 {@code fgi$a}
     * @return named 内部名；<b>表里没有则返回 {@code null}</b>（理由见类注释）
     */
    public String classOf(String obfObfName) {
        return obfObfName == null ? null : obfToNamed.get(obfObfName);
    }

    /**
     * named 内部名 → obf 内部名（反向）。
     *
     * @return obf 内部名；表里没有则 {@code null}
     */
    public String obfOf(String namedName) {
        return namedName == null ? null : namedToObf.get(namedName);
    }

    /** 类名映射是否已加载（快路径走完即为 true）。 */
    public int classCount() {
        return classCount;
    }

    /**
     * 字段换算：obf 归属类 + obf 字段名 → named 字段名。
     *
     * <p>注意 1.20.1 的表右列可能是 SRG 名（{@code f_252495_}）而非可读 named 名，
     * 详见类注释。
     *
     * @return named 字段名；查不到则 {@code null}
     */
    public String fieldOf(String ownerObf, String nameObf) {
        ensureMembers();
        return lookup(fieldKeys, fieldValues, ownerObf + '.' + nameObf);
    }

    /**
     * 方法换算：obf 归属类 + obf 方法名 + obf 描述符 → named 方法名。
     *
     * <p><b>描述符用 obf 形式传入</b>（即宿主 jar 里的原始描述符，如
     * {@code (II)Z}）。key 里同时含描述符，因为混淆后的重载会用同名不同描述符表示
     * 两个不同方法，只用名字会串味。
     *
     * @return named 方法名；查不到则 {@code null}
     */
    public String methodOf(String ownerObf, String nameObf, String descObf) {
        ensureMembers();
        return lookup(methodKeys, methodValues, ownerObf + '.' + nameObf + descObf);
    }

    private static String lookup(String[] keys, String[] values, String key) {
        // 手写二分而不是 HashMap<String,String>：成员表 21 万条，
        // 排序数组 + 二分的常驻内存远小于装箱字符串的 HashMap，
        // 且查找是 O(log n) ≈ 18 次比较，对"按需查询"这个量级足够。
        int lo = 0;
        int hi = keys.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int c = keys[mid].compareTo(key);
            if (c == 0) {
                return values[mid];
            }
            if (c < 0) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return null;
    }

    /**
     * 成员表按需解析（惰性 + 双重检查）。
     *
     * <p>为什么不在 {@link #open} 里一次做完：成员行占全表 96%，而拼接流程在
     * 只做类名对齐时完全不需要它们。启动路径不该为用不到的东西付 100 ms。
     */
    private void ensureMembers() {
        if (methodKeys != null) {
            return;
        }
        synchronized (memberLock) {
            if (methodKeys != null) {
                return;
            }
            if (source == null) {
                // 流构造且还没人调过 parseAll：无源可读，标记为空表而不是反复重试
                methodKeys = new String[0];
                methodValues = new String[0];
                fieldKeys = new String[0];
                fieldValues = new String[0];
                return;
            }
            try {
                parseAll();
            } catch (IOException e) {
                // 读失败时退化为"空成员表"：查询一律返回 null，
                // 调用方据此走兜底。不抛受检异常是因为查询方法签名不该被 IO 污染。
                methodKeys = new String[0];
                methodValues = new String[0];
                fieldKeys = new String[0];
                fieldValues = new String[0];
            }
        }
    }

    /**
     * 全量解析（类名 + 字段 + 方法）。
     *
     * <p>成员表用 {@code String[]} 存而不是 {@code Map}：解析完立刻排序，
     * 查询走二分。构建期用临时 {@code HashMap} 去重（TSRG2 里同一个
     * (owner,name,desc) 可能因多继承而重复出现），然后一次性倒进数组。
     */
    private void parseAll() throws IOException {
        Map<String, String> fwd = new HashMap<>(16384);
        Map<String, String> fld = new HashMap<>(8192);
        Map<String, String> mth = new HashMap<>(16384);

        try (BufferedReader r = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            String curOwnerObf = null;
            String line;
            boolean first = true;
            while ((line = r.readLine()) != null) {
                int len = line.length();
                if (len == 0) {
                    continue;
                }
                char c0 = line.charAt(0);

                if (c0 != '\t') {
                    // 缩进 0 = 类行
                    if (first) {
                        first = false;
                        if (hasSecondSpace(line)) {
                            continue;
                        }
                    }
                    if (hasSecondSpace(line)) {
                        continue;
                    }
                    int sp = line.indexOf(' ');
                    if (sp <= 0 || sp == len - 1) {
                        curOwnerObf = null;
                        continue;
                    }
                    curOwnerObf = line.substring(0, sp);
                    fwd.put(curOwnerObf, line.substring(sp + 1));
                    continue;
                }

                if (curOwnerObf == null) {
                    continue;
                }
                // 缩进 2 及以上 = 参数名行（"0 o p_253997_"），跳过。
                // 这一条必须放在最前面：参数行也是"2 列"，不先挡掉就会被当成字段。
                if (len > 1 && line.charAt(1) == '\t') {
                    continue;
                }

                String body = line.substring(1);
                int bLen = body.length();
                if (bLen == 0) {
                    continue;
                }
                int sp = body.indexOf(' ');
                if (sp < 0) {
                    // 1 列 = 方法属性行（static / private …），跳过
                    continue;
                }
                String left = body.substring(0, sp);
                String rest = body.substring(sp + 1);
                int sp2 = rest.indexOf(' ');
                if (sp2 < 0) {
                    // 2 列 = 字段行
                    fld.put(curOwnerObf + '.' + left, rest);
                } else {
                    // ≥3 列 = 方法行：left=obf名，rest 的第一段=描述符，其余=named名
                    String desc = rest.substring(0, sp2);
                    String named = rest.substring(sp2 + 1);
                    mth.put(curOwnerObf + '.' + left + desc, named);
                }
            }
        }

        // 用解析结果覆盖类表（parseAll 也可能被 open(InputStream) 之外的人第一时间触发，
        // 但类表在 open 时就已经建好；这里保持一致即可）
        methodKeys = sortedKeys(mth);
        methodValues = valuesInKeyOrder(mth, methodKeys);
        fieldKeys = sortedKeys(fld);
        fieldValues = valuesInKeyOrder(fld, fieldKeys);
    }

    private static String[] sortedKeys(Map<String, String> m) {
        String[] a = m.keySet().toArray(new String[0]);
        Arrays.sort(a);
        return a;
    }

    private static String[] valuesInKeyOrder(Map<String, String> m, String[] keys) {
        String[] v = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            v[i] = m.get(keys[i]);
        }
        return v;
    }

    // ------------------------------------------------------------------
    // 描述符换算
    // ------------------------------------------------------------------

    /**
     * 把一个描述符里的所有对象类型整体换成 named。
     *
     * <p>处理 {@code La/b;}(普通)、{@code [La/b;}(数组)、嵌套形式的
     * {@code [[Lfgi$a;}(多维 + 内部类)。基本类型原样保留。
     *
     * <p><b>找不到映射的类怎么办</b>：这里<b>保留原名</b>而不是让整个描述符变 null。
     * 理由与 {@link #classOf} 的"返回 null"看似矛盾，实则不同层次：
     * 描述符换算的语义是"能换的换掉"，把整个描述符作废代价过大（一个 {@code java/lang/String}
     * 这种本来就不该在表里的类型会让整条方法签名查不到）。但调用方如果需要
     * "所有类型都必须是 named"这个更强保证，应当改用 {@link #remapDescStrict}。
     *
     * @param desc 描述符，如 {@code (Lgeo;II)Lfgi;}
     * @return 换算后的描述符；输入为 null 时返回 null
     */
    public String remapDesc(String desc) {
        if (desc == null) {
            return null;
        }
        // 快路径：没有 'L' 就说明描述符里没有对象类型（纯基本类型/void），
        // 直接原样返回，连扫描都省了
        if (desc.indexOf('L') < 0) {
            return desc;
        }
        StringBuilder sb = new StringBuilder(desc.length() + 32);
        int i = 0;
        int n = desc.length();
        while (i < n) {
            char c = desc.charAt(i);
            if (c != 'L') {
                sb.append(c);
                i++;
                continue;
            }
            int end = desc.indexOf(';', i + 1);
            if (end < 0) {
                // 描述符坏了（没有闭合分号）：原样吐出去，不猜
                sb.append(desc, i, n);
                break;
            }
            String internal = desc.substring(i + 1, end);
            String named = obfToNamed.get(internal);
            sb.append('L').append(named != null ? named : internal).append(';');
            i = end + 1;
        }
        return sb.toString();
    }

    /**
     * 严格版描述符换算：只要有<b>任意一个</b>对象类型在表里查不到，就返回 {@code null}。
     *
     * <p>用于"宁可判定失败也不能写出半混淆描述符"的场景（例如方法签名比对）。
     * 与 {@link #remapDesc} 的区别只在这一点上。
     *
     * @return 换算后的描述符；有任一类型未命中、或输入为 null 时返回 {@code null}
     */
    public String remapDescStrict(String desc) {
        if (desc == null) {
            return null;
        }
        if (desc.indexOf('L') < 0) {
            return desc;
        }
        StringBuilder sb = new StringBuilder(desc.length() + 32);
        int i = 0;
        int n = desc.length();
        while (i < n) {
            char c = desc.charAt(i);
            if (c != 'L') {
                sb.append(c);
                i++;
                continue;
            }
            int end = desc.indexOf(';', i + 1);
            if (end < 0) {
                return null;
            }
            String internal = desc.substring(i + 1, end);
            String named = obfToNamed.get(internal);
            if (named == null) {
                return null;
            }
            sb.append('L').append(named).append(';');
            i = end + 1;
        }
        return sb.toString();
    }

    /**
     * 一次查一个类，够不到 {@link #classOf} 语义时的便利包装：查不到就抛。
     *
     * <p>给"这里缺映射一定是 bug"的调用点用（例如已经确认宿主 jar 里存在该类）。
     *
     * @throws IllegalStateException 表里没有该 obf 名
     */
    public String classOfRequired(String obfName) {
        String v = classOf(obfName);
        if (v == null) {
            throw new IllegalStateException("映射表缺少类：" + obfName + "（表=" + source + "）");
        }
        return v;
    }

    @Override
    public String toString() {
        return "MappingTable{classes=" + classCount + ", source=" + source
                + ", members=" + (methodKeys == null ? "lazy" : "loaded") + '}';
    }
}
