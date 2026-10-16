package com.fusionml.remap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 用<b>真实映射表 + 真实混淆 jar</b> 自检 {@link MappingTable}。
 *
 * <p>这个程序存在的意义是把"能不能换算"变成可复现的事实，而不是等到合并搬了 0 个类、
 * 游戏崩在启动期才发现。它刻意<b>不</b>把结论说得比证据更强：
 * <ul>
 *   <li>能证明：宿主混淆 jar 里的类名能被表换出 named 名，且换出的名字在反混淆覆盖层里
 *       抽得到对应项。</li>
 *   <li><b>不能</b>证明：换出的名字在完整游戏里全都存在 —— 本机没有完整 named 游戏包，
 *       覆盖层只有 1603 个类。端到端必须由接入方用真实启动验证。程序会把这条边界打印出来。</li>
 * </ul>
 *
 * <p>用法（参数全部可省略，省略时用本机已知路径）：
 * <pre>
 * java -cp build\classes;build\test-classes com.fusionml.remap.MappingSelfTest \
 *      &lt;1.21.1表&gt; &lt;1.21.1混淆jar&gt; &lt;1.21.1覆盖层jar&gt; &lt;1.20.1表&gt; &lt;1.20.1混淆jar&gt; &lt;1.20.1覆盖层jar&gt;
 * </pre>
 */
public final class MappingSelfTest {

    private static final String MAPPINGS_1211 =
            "D:\\我的世界\\服务器\\.minecraft\\libraries\\net\\neoforged\\neoform\\"
            + "1.21.1-20240808.144430\\neoform-1.21.1-20240808.144430-mappings-merged.txt";
    private static final String OBF_JAR_1211 =
            "D:\\我的世界\\服务器\\.minecraft\\versions\\1.21.1-NeoForge_21.1.249\\"
            + "1.21.1-NeoForge_21.1.249.jar";
    private static final String OVERLAY_JAR_1211 =
            "D:\\我的世界\\服务器\\.minecraft\\libraries\\net\\neoforged\\neoforge\\21.1.249\\"
            + "neoforge-21.1.249-client.jar";

    /*
     * 1.20.1 的真实数据是实测找到的，不是猜的：
     *   * slim jar = 混淆（7436 个类，7388 个顶层裸名，样例 eiu$5/ejo/ejt/ekh）—— 宿主的形态；
     *   * srg jar  = 同为 7436 个类，但全部 named（net/minecraft 7103 个，裸类 0）—— 完美的对照物。
     * 两者类数一一对应，所以 1.20.1 也能做"换出的 named 名真的在反混淆 jar 里找得到"的核对，
     * 而不只是查表自证。这比 1.21.1 的覆盖层（只有 1603 个类）证据更强。
     */
    private static final String MAPPINGS_1201 =
            "D:\\我的世界\\服务器\\.minecraft\\libraries\\de\\oceanlabs\\mcp\\mcp_config\\"
            + "1.20.1-20230612.114412\\mcp_config-1.20.1-20230612.114412-mappings-merged.txt";
    private static final String OBF_JAR_1201 =
            "D:\\我的世界\\服务器\\.minecraft\\libraries\\net\\minecraft\\client\\"
            + "1.20.1-20230612.114412\\client-1.20.1-20230612.114412-slim.jar";
    private static final String OVERLAY_JAR_1201 =
            "D:\\我的世界\\服务器\\.minecraft\\libraries\\net\\minecraft\\client\\"
            + "1.20.1-20230612.114412\\client-1.20.1-20230612.114412-srg.jar";

    private MappingSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        String m1211 = arg(args, 0, MAPPINGS_1211);
        String o1211 = arg(args, 1, OBF_JAR_1211);
        String v1211 = arg(args, 2, OVERLAY_JAR_1211);
        String m1201 = arg(args, 3, MAPPINGS_1201);
        String o1201 = arg(args, 4, OBF_JAR_1201);
        String v1201 = arg(args, 5, OVERLAY_JAR_1201);

        System.out.println("=== FusionML remap 自检（真实映射表 + 真实 jar） ===");
        System.out.println();

        boolean ok = true;
        ok &= checkVersion("1.21.1", Path.of(m1211), Path.of(o1211),
                Files.isRegularFile(Path.of(v1211)) ? Path.of(v1211) : null);

        System.out.println();
        if (Files.isRegularFile(Path.of(m1201))) {
            ok &= checkVersion("1.20.1", Path.of(m1201),
                    Files.isRegularFile(Path.of(o1201)) ? Path.of(o1201) : null,
                    Files.isRegularFile(Path.of(v1201)) ? Path.of(v1201) : null);
        } else {
            System.out.println("!! 1.20.1 映射表不存在，跳过：" + m1201);
        }

        System.out.println();
        System.out.println("=== 描述符换算检查（真实表，两个版本各测一遍） ===");
        if (Files.isRegularFile(Path.of(m1211))) {
            System.out.println("-- 1.21.1 表 --");
            ok &= descriptorChecks(Path.of(m1211));
        }
        if (Files.isRegularFile(Path.of(m1201))) {
            System.out.println("-- 1.20.1 表 --");
            ok &= descriptorChecks(Path.of(m1201));
        }

        System.out.println();
        System.out.println("=== 边界声明（必读） ===");
        System.out.println("本自检只覆盖\"映射表读取与名称换算\"。");
        System.out.println("宿主的完整 named 游戏包在本机不存在，因此：");
        System.out.println("  * 能证明：混淆名 -> named 名的换算按表正确执行，且抽样能在覆盖层里找到对应类；");
        System.out.println("  * 不能证明：换算结果在完整游戏里全量存在，也不能证明跨端拼接端到端可用。");
        System.out.println("端到端正确性需由合并流程接入后用真实启动验证（不在本自检范围）。");

        System.out.println();
        System.out.println(ok ? "[remap] 自检完成：所有断言通过" : "[remap] 自检失败：见上方 FAIL");
    }

    // ------------------------------------------------------------------

    private static boolean checkVersion(String label, Path table, Path obfJar, Path overlayJar)
            throws IOException {
        System.out.println("---------- " + label + " ----------");
        System.out.println("映射表：" + table);
        System.out.println("混淆 jar：" + (obfJar == null ? "(未提供)" : obfJar.toString()));
        System.out.println("覆盖层 jar：" + (overlayJar == null ? "(未提供)" : overlayJar.toString()));

        if (!Files.isRegularFile(table)) {
            System.out.println("FAIL 映射表不存在");
            return false;
        }
        long tableBytes = Files.size(table);

        // ---- 快路径：只解析类名 ----
        long t0 = System.nanoTime();
        MappingTable mt = MappingTable.open(table);
        long classNanos = System.nanoTime() - t0;

        System.out.println();
        System.out.println("[快路径] 仅解析类名："
                + fmtMs(classNanos) + " ms，类数 " + mt.classCount());
        System.out.println("         表大小 " + (tableBytes / 1024 / 1024) + " MB");
        System.out.println("         " + mt);

        String p1 = mt.classOf("a");
        String p2 = mt.classOf("aa");
        System.out.println("[快路径] 抽样 classOf(a)=" + p1 + "  classOf(aa)=" + p2);
        boolean ok = "com/mojang/math/Axis".equals(p1);
        if (!ok) {
            System.out.println("FAIL classOf(a) 结果与表原文不符，期望 com/mojang/math/Axis");
        }

        // ---- 成员表：首次查询才触发全量解析 ----
        System.out.println();
        long t1 = System.nanoTime();
        String f = mt.fieldOf("a", "a");
        long memberNanos = System.nanoTime() - t1;
        System.out.println("[惰性]   首次 fieldOf 触发的全量成员解析：" + fmtMs(memberNanos) + " ms");
        // 表原文：`a com/mojang/math/Axis` 之下是 `<TAB>a XN`，即 obf 字段名 a -> named XN
        // 但 1.20.1 的同一条右列是 SRG 名 f_252495_ —— 这是两版本真实差异，
        // 断言必须按"是否 SRG 形态"判定，不能硬编码 XN（否则会把正确行为判成失败）。
        System.out.println("         fieldOf(a, a) = " + f);
        boolean memberOk;
        if (f == null) {
            memberOk = false;
            System.out.println("FAIL fieldOf(a, a) 不应为 null");
        } else if (f.equals("XN")) {
            memberOk = true;
            System.out.println("         -> 可读 named 名形态（与 1.21.1 一致）");
        } else if (f.startsWith("f_") || f.startsWith("m_")) {
            memberOk = true;
            System.out.println("         -> SRG 中间名形态（1.20.1 表的真实特征，非错误）");
        } else {
            memberOk = false;
            System.out.println("FAIL fieldOf(a, a) 既不是 XN 也不是 SRG 形态：" + f);
        }

        // ---- 与真实混淆 jar 对照 ----
        if (obfJar != null && Files.isRegularFile(obfJar)) {
            List<String> obfClasses = listClasses(obfJar);
            System.out.println();
            System.out.println("[对照] 混淆 jar 类总数：" + obfClasses.size());

            Set<String> overlay = overlayJar != null ? new TreeSet<>(listClasses(overlayJar)) : null;
            if (overlay != null) {
                System.out.println("[对照] 覆盖层 jar 类总数：" + overlay.size()
                        + "（net/minecraft/ " + countPrefix(overlay, "net/minecraft/") + "）");
            }

            int mapped = 0;
            int absent = 0;
            int dupNamed = 0;
            Set<String> namedSeen = new LinkedHashSet<>();
            List<String> absentSample = new ArrayList<>();
            List<String> notNamedPkg = new ArrayList<>();

            long t2 = System.nanoTime();
            for (String obf : obfClasses) {
                String named = mt.classOf(obf);
                if (named == null) {
                    absent++;
                    if (absentSample.size() < 8) {
                        absentSample.add(obf);
                    }
                    continue;
                }
                mapped++;
                if (!namedSeen.add(named)) {
                    dupNamed++;
                }
                if (!named.startsWith("net/minecraft/") && !named.startsWith("com/mojang/")) {
                    if (notNamedPkg.size() < 8) {
                        notNamedPkg.add(obf + " -> " + named);
                    }
                }
            }
            long scanNanos = System.nanoTime() - t2;

            System.out.println();
            System.out.println("[类名命中] 查表 " + obfClasses.size() + " 个混淆类，耗时 "
                    + fmtMs(scanNanos) + " ms");
            System.out.println("           有 named 结果        : " + mapped + " ("
                    + pct(mapped, obfClasses.size()) + ")");
            System.out.println("           表里查不到(返回 null): " + absent + " ("
                    + pct(absent, obfClasses.size()) + ")");
            System.out.println("           named 名重复的类数   : " + dupNamed
                    + "（多对一说明表本身有歧义，正常应为 0）");
            System.out.println("           结果非 net/minecraft|com/mojang 的: " + notNamedPkg.size());
            for (String s : notNamedPkg) {
                System.out.println("             " + s);
            }
            if (!absentSample.isEmpty()) {
                System.out.println("           查不到样例：" + absentSample);
            }

            if (mapped != obfClasses.size()) {
                System.out.println("FAIL 有 " + absent + " 个混淆类在表里查不到");
                ok = false;
            }
            if (dupNamed != 0) {
                System.out.println("FAIL 存在多对一的类名映射");
                ok = false;
            }

            // ---- 覆盖层命中（这才是有外部证据的部分） ----
            if (overlay != null) {
                int vsOverlay = 0;
                int overlayMc = 0;
                List<String> missing = new ArrayList<>();
                // named -> obf 反查再正向验证，顺便检查反向表
                int revOk = 0;
                int revChecked = 0;
                for (String obf : obfClasses) {
                    String named = mt.classOf(obf);
                    if (named == null) {
                        continue;
                    }
                    if (overlay.contains(named)) {
                        vsOverlay++;
                        if (named.startsWith("net/minecraft/")) {
                            overlayMc++;
                        }
                    } else if (missing.size() < 8) {
                        missing.add(named);
                    }
                    if (revChecked < 2000) {
                        revChecked++;
                        if (obf.equals(mt.obfOf(named))) {
                            revOk++;
                        }
                    }
                }
                System.out.println();
                System.out.println("[三方对照] 换出的 named 名能在覆盖层 jar 里找到的："
                        + vsOverlay + " / " + obfClasses.size()
                        + " (" + pct(vsOverlay, obfClasses.size()) + ")");
                System.out.println("           其中 net/minecraft/**：" + overlayMc);
                boolean fullCoverage = vsOverlay == obfClasses.size();
                if (fullCoverage) {
                    System.out.println("           【这组对照是强证据】覆盖层类数 (" + overlay.size()
                            + ") 与混淆 jar 类数 (" + obfClasses.size()
                            + ") 一致，且每一个换出的 named 名都在其中存在 ——");
                    System.out.println("           即此版本的命名空间换算做到了全量可核对（不是抽样）。");
                } else {
                    System.out.println("           【这不是映射准确率】覆盖层只有 " + overlay.size()
                            + " 个类，是原版的 named 子集，不是完整游戏包。");
                    System.out.println("           绝大多数类在覆盖层里没有对应文件可核对，");
                    System.out.println("           所以这里只能证明\"抽到的这些对得上\"，不能证明全量正确。");
                    System.out.println("           覆盖层未覆盖样例：" + missing);
                }
                System.out.println("[反向表]   obfOf(classOf(x)) == x 校验：" + revOk + " / " + revChecked);

                if (vsOverlay == 0) {
                    System.out.println("FAIL 没有任何类名能在覆盖层里核对上");
                    ok = false;
                }
            }

            // ---- 成员名对照（区分两版本差异） ----
            System.out.println();
            memberReport(label, mt, table);
        } else {
            System.out.println();
            System.out.println("[对照] 未提供混淆 jar，跳过类名命中统计");
        }

        ok &= memberOk;
        return ok;
    }

    /**
     * 成员名报告：把 1.21.1 与 1.20.1 的差异如实打出来。
     *
     * <p>1.21.1 的成员右列是可读 named 名；1.20.1 混有 SRG 中间名（{@code f_}/{@code m_}）。
     * 这个统计直接数出来，不猜。
     */
    private static void memberReport(String label, MappingTable mt, Path table) throws IOException {
        // 直接从表里数右列形态 —— 用流式扫描，避免把 5 MB 读进内存两次
        int srgField = 0;
        int totalField = 0;
        int srgMethod = 0;
        int totalMethod = 0;
        try (var r = Files.newBufferedReader(table, StandardCharsets.UTF_8)) {
            String cur = null;
            String line;
            boolean first = true;
            while ((line = r.readLine()) != null) {
                int len = line.length();
                if (len == 0) {
                    continue;
                }
                if (line.charAt(0) != '\t') {
                    if (first) {
                        first = false;
                        continue;
                    }
                    int sp = line.indexOf(' ');
                    cur = sp < 0 ? null : line.substring(0, sp);
                    continue;
                }
                if (cur == null || (len > 1 && line.charAt(1) == '\t')) {
                    continue;
                }
                String body = line.substring(1);
                int sp = body.indexOf(' ');
                if (sp < 0) {
                    continue;
                }
                String rest = body.substring(sp + 1);
                int sp2 = rest.indexOf(' ');
                if (sp2 < 0) {
                    totalField++;
                    if (rest.startsWith("f_")) {
                        srgField++;
                    }
                } else {
                    totalMethod++;
                    String nm = rest.substring(sp2 + 1);
                    if (nm.startsWith("m_")) {
                        srgMethod++;
                    }
                }
            }
        }
        System.out.println("[成员右列形态] " + label
                + "：字段 " + totalField + " 条，其中 SRG(f_) " + srgField
                + " 条 (" + pct(srgField, totalField) + ")；"
                + "方法 " + totalMethod + " 条，其中 SRG(m_) " + srgMethod
                + " 条 (" + pct(srgMethod, totalMethod) + ")");
        if (srgField == 0 && srgMethod == 0) {
            System.out.println("             => 该版本成员右列是可读 named 名");
        } else {
            System.out.println("             => 该版本成员右列<b>混有 SRG 中间名</b>，"
                    + "fieldOf/methodOf 会返回中间名而非可读名");
        }

        // 抽样几条成员查询，打印真实返回值。
        // 用"表里第一个类 + 它的第一个字段/方法"而不是硬编码 geo —— geo 只存在于 1.21.1，
        // 1.20.1 的同一游戏类叫 fjm（实测），硬编码会让 1.20.1 的抽样永远打出 null。
        String ownerObf = null;
        String fieldObf = null;
        String methodObf = null;
        String methodDesc = null;
        try (var r = Files.newBufferedReader(table, StandardCharsets.UTF_8)) {
            String cur = null;
            String line;
            boolean first = true;
            while ((line = r.readLine()) != null && ownerObf == null) {
                int len = line.length();
                if (len == 0) {
                    continue;
                }
                if (line.charAt(0) != '\t') {
                    if (first) {
                        first = false;
                        continue;
                    }
                    int sp = line.indexOf(' ');
                    cur = sp < 0 ? null : line.substring(0, sp);
                    continue;
                }
                if (cur == null || (len > 1 && line.charAt(1) == '\t')) {
                    continue;
                }
                String body = line.substring(1);
                int sp = body.indexOf(' ');
                if (sp < 0) {
                    continue;
                }
                String left = body.substring(0, sp);
                String rest = body.substring(sp + 1);
                int sp2 = rest.indexOf(' ');
                if (sp2 < 0) {
                    if (fieldObf == null) {
                        ownerObf = cur;
                        fieldObf = left;
                    }
                } else if (methodObf == null) {
                    ownerObf = cur;
                    methodObf = left;
                    methodDesc = rest.substring(0, sp2);
                }
            }
        }
        if (ownerObf != null) {
            System.out.println("[成员抽样] 取表中首个类 " + ownerObf
                    + "（named=" + mt.classOf(ownerObf) + "）");
            if (fieldObf != null) {
                System.out.println("           fieldOf(" + ownerObf + ", " + fieldObf + ") = "
                        + mt.fieldOf(ownerObf, fieldObf));
            }
            if (methodObf != null) {
                System.out.println("           methodOf(" + ownerObf + ", " + methodObf + ", "
                        + methodDesc + ") = " + mt.methodOf(ownerObf, methodObf, methodDesc));
            }
        }
        String missing = mt.methodOf(ownerObf, "zzz_does_not_exist", "()V");
        System.out.println("[成员缺失] methodOf(" + ownerObf + ", zzz_does_not_exist, ()V)="
                + missing + "（预期 null —— 查不到必须能被调用方识别）");
    }

    // ------------------------------------------------------------------

    private static boolean descriptorChecks(Path table) throws IOException {
        MappingTable mt = MappingTable.open(table);
        boolean ok = true;

        // 用表里真实存在的映射来构造断言，不硬编码版本相关的混淆名。
        // （1.21.1 的 DimensionSpecialEffects 混淆名是 geo，1.20.1 是 fjm —— 硬编码会让
        //  其中一个版本永远查不到，这正是我第一次跑自检时踩的坑。）
        String obfBase = null;
        String namedBase = null;
        for (String cand : new String[]{"geo", "fjm", "a"}) {
            String v = mt.classOf(cand);
            if (v != null && v.startsWith("net/minecraft/")) {
                obfBase = cand;
                namedBase = v;
                break;
            }
        }
        if (obfBase == null) {
            // 退一步：用 com/mojang 的恒等映射也足以验描述符规则
            obfBase = "a";
            namedBase = mt.classOf("a");
        }
        System.out.println("描述符测试基准：" + obfBase + " -> " + namedBase);
        if (namedBase == null) {
            System.out.println("FAIL 基准类查不到，无法测描述符");
            return false;
        }

        final String o = obfBase;
        final String nn = namedBase;
        // 普通对象类型
        ok &= eq("(L" + o + ";II)V", mt.remapDesc("(L" + o + ";II)V"), "(L" + nn + ";II)V");
        // 数组
        ok &= eq("[L" + o + ";", mt.remapDesc("[L" + o + ";"), "[L" + nn + ";");
        // 多维数组 + 基本类型混合
        ok &= eq("([[L" + o + ";I)L" + o + ";", mt.remapDesc("([[L" + o + ";I)L" + o + ";"),
                "([[L" + nn + ";I)L" + nn + ";");
        // 内部类：优先用真实存在的内部类映射
        String innerObf = null;
        String innerNamed = null;
        for (String suf : new String[]{"$d", "$a"}) {
            String v = mt.classOf(o + suf);
            if (v != null) {
                innerObf = o + suf;
                innerNamed = v;
                break;
            }
        }
        if (innerObf != null) {
            ok &= eq("(L" + innerObf + ";)V", mt.remapDesc("(L" + innerObf + ";)V"),
                    "(L" + innerNamed + ";)V");
            System.out.println("  内部类：" + innerObf + " -> " + innerNamed);
        } else {
            System.out.println("  (该表未含内部类样例，跳过内部类断言)");
        }
        // 无对象类型：必须原样返回
        ok &= eq("(II)Z", mt.remapDesc("(II)Z"), "(II)Z");
        ok &= eq("V", mt.remapDesc("V"), "V");
        // null 传播
        ok &= eq("null", String.valueOf(mt.remapDesc(null)), "null");
        // 严格版：java/lang/String 不在映射表里 -> 必须判失败
        ok &= eq("null", String.valueOf(mt.remapDescStrict("(Ljava/lang/String;)V")), "null");
        // 非严格版：保留原名
        ok &= eq("(Ljava/lang/String;)V", mt.remapDesc("(Ljava/lang/String;)V"),
                "(Ljava/lang/String;)V");
        // 查不到时 classOf 返回 null（不是原名）
        ok &= eq("null", String.valueOf(mt.classOf("this_class_does_not_exist_zzz")), "null");
        // 恒等映射仍应命中（不是"查不到"）
        String glx = mt.classOf("a");
        System.out.println("classOf(a) = " + glx + "  (恒等映射示例，命中但名字不变)");

        return ok;
    }

    private static boolean eq(String what, String actual, String expected) {
        boolean pass = expected.equals(actual);
        System.out.println((pass ? "  ok   " : "  FAIL ") + what
                + (pass ? "" : "  期望=" + expected + " 实际=" + actual));
        return pass;
    }

    // ------------------------------------------------------------------

    private static List<String> listClasses(Path jar) throws IOException {
        List<String> out = new ArrayList<>(16384);
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (n.endsWith(".class") && !e.isDirectory()) {
                    out.add(n.substring(0, n.length() - 6));
                }
            }
        }
        return out;
    }

    private static int countPrefix(Set<String> s, String prefix) {
        int n = 0;
        for (String x : s) {
            if (x.startsWith(prefix)) {
                n++;
            }
        }
        return n;
    }

    private static String fmtMs(long nanos) {
        return String.format(Locale.ROOT, "%.1f", nanos / 1_000_000.0);
    }

    private static String pct(int a, int b) {
        if (b == 0) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%.2f%%", 100.0 * a / b);
    }

    private static String arg(String[] args, int i, String def) {
        return args.length > i && !args[i].isEmpty() ? args[i] : def;
    }
}
