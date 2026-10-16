package com.fusionml;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本加载器支持的目标版本，以及每个版本各自打包一份 jar 时写进 jar 里的"出厂设定"。
 *
 * <h2>为什么要分版本打包</h2>
 *
 * <p>同一个加载器要服务好几个游戏版本，而这几个版本之间有些设定<b>不可能一样</b>：
 * <ul>
 *   <li>需要的 Java 主版本不同（26.x 要 25，1.20.1 要 17）；</li>
 *   <li>游戏包是不是混淆的（26.x 不混淆，1.21.1/1.20.1 混淆）；</li>
 *   <li>对方平台（NeoForge / Forge）的补丁产物命名规律不同。</li>
 * </ul>
 *
 * <p>把这些写死在代码里，就意味着"要么全都对、要么全都错"。分版本打包则是：
 * 每个 jar 里带一份<b>默认值</b>，只有用户没显式指定时才生效 ——
 * 于是"下载对应版本的 jar"这件事本身就完成了大部分配置。
 *
 * <h2>优先级</h2>
 *
 * <ol>
 *   <li>命令行 / 系统属性（用户显式指定，最高）；</li>
 *   <li>jar 里打包的出厂设定（本类）；</li>
 *   <li>代码里的兜底默认值。</li>
 * </ol>
 *
 * <p>这个类<b>不改变任何行为逻辑</b>，只提供默认值 —— 判断"能不能装"的仍然是
 * {@code InstallVerify} / {@code PatchedBase} 那些实测过的判据。
 */
public final class FusionVersions {

    private FusionVersions() {
    }

    /** 一个目标版本的出厂设定。 */
    public record Target(String id, String displayName, String mcVersion, String platform,
                         int requiredJava, boolean deobfuscated, String note) {
    }

    /** 五个目标版本。顺序即"推荐顺序"（先最新）。 */
    private static final Map<String, Target> TARGETS = new LinkedHashMap<>();

    static {
        add(new Target("26.3", "26.3", "26.3", "Fabric / NeoForge", 25, true,
                "本机已实测：能进游戏主界面，三端模组同时加载"));
        add(new Target("26.2", "26.2", "26.2", "Fabric / NeoForge", 25, true,
                "与 26.3 同族（26.x 不混淆），但**本机没有实例，未实测**"));
        add(new Target("26.1.2", "26.1.2", "26.1.2", "Fabric", 25, true,
                "本机有实例；26.x 族同一套规则"));
        add(new Target("1.21.1", "1.21.1", "1.21.1", "Fabric / NeoForge", 21, false,
                "**混淆**版本：合并是按内部名搬运的，需要 remap 层才能用（见 README 第十四节）"));
        add(new Target("1.20.1", "1.20.1", "1.20.1", "Fabric / Forge", 17, false,
                "**混淆**版本：同上，且需要 Forge 侧的补丁产物规律"));
    }

    private static void add(Target t) {
        TARGETS.put(t.id(), t);
    }

    /** 全部目标版本（按推荐顺序）。 */
    public static java.util.List<Target> all() {
        return java.util.List.copyOf(TARGETS.values());
    }

    /** 按 id 取一个目标版本；不是已知目标返回 null。 */
    public static Target byId(String id) {
        return id == null ? null : TARGETS.get(id.trim());
    }

    /**
     * 由版本目录名推断它属于哪个目标版本。
     *
     * <p>版本目录名是用户自己起的（实测本机就有 {@code 26.3-Fabric 0.19.5}、
     * {@code 1.21.1-NeoForge_21.1.236}、{@code 1.21.1NeoForgeGreenMan优化整合包V2.0}
     * 这种五花八门的名字），所以这里<b>只做前缀匹配</b>，取最长匹配的那个。
     */
    public static Target inferFrom(String versionDirName) {
        if (versionDirName == null) {
            return null;
        }
        String v = versionDirName.trim();
        Target best = null;
        for (Target t : TARGETS.values()) {
            if (v.startsWith(t.mcVersion())) {
                if (best == null || t.mcVersion().length() > best.mcVersion().length()) {
                    best = t;
                }
            }
        }
        return best;
    }

    /**
     * 本 jar 是为哪个目标版本打的。
     *
     * <p>三个来源，按可靠性排序：
     * <ol>
     *   <li>系统属性 {@code fusion.packagedTarget}（启动脚本可显式指定）；</li>
     *   <li>jar 清单里的 {@code FusionML-Target}（分版本打包时写进去的）；</li>
     *   <li>安装器旁边的 {@code META-INF/fusionml/target.txt}
     *       （安装器 jar 单独打包时带的，因为它自己不一定在清单里写目标）。</li>
     * </ol>
     */
    public static String packagedTarget() {
        String v = System.getProperty("fusion.packagedTarget", "");
        if (!v.isBlank()) {
            return v.trim();
        }
        // 清单属性：用 Package 元数据读，读不到再退回自己解析 MANIFEST.MF。
        String fromManifest = readManifestTarget();
        if (fromManifest != null) {
            return fromManifest;
        }
        return readTargetFile();
    }

    /** 从本 jar 的 MANIFEST.MF 里读 {@code FusionML-Target}。 */
    private static String readManifestTarget() {
        try {
            java.security.CodeSource cs = FusionVersions.class.getProtectionDomain()
                    .getCodeSource();
            if (cs == null || cs.getLocation() == null) {
                return null;
            }
            java.nio.file.Path p = java.nio.file.Path.of(cs.getLocation().toURI());
            if (!java.nio.file.Files.isRegularFile(p)) {
                return null;
            }
            try (java.util.jar.JarFile jf = new java.util.jar.JarFile(p.toFile())) {
                java.util.jar.Manifest mf = jf.getManifest();
                if (mf == null) {
                    return null;
                }
                String t = mf.getMainAttributes().getValue("FusionML-Target");
                return t == null || t.isBlank() ? null : t.trim();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** 读安装器旁边带的 {@code META-INF/fusionml/target.txt}。 */
    private static String readTargetFile() {
        try (java.io.InputStream in = FusionVersions.class.getClassLoader()
                .getResourceAsStream("META-INF/fusionml/target.txt")) {
            if (in == null) {
                return null;
            }
            String t = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            return t.isEmpty() ? null : t;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 本 jar 的出厂 Java 要求；没有出厂设定返回 0。 */
    public static int packagedRequiredJava() {
        Target t = byId(packagedTarget());
        return t == null ? 0 : t.requiredJava();
    }

    /** 人读的一行说明。 */
    public static String describe() {
        String packaged = packagedTarget();
        StringBuilder sb = new StringBuilder();
        if (packaged != null) {
            Target t = byId(packaged);
            sb.append("本 jar 为 ").append(packaged).append(" 打包");
            if (t != null) {
                sb.append("（Java ").append(t.requiredJava())
                        .append("，").append(t.platform()).append("）");
            }
        } else {
            sb.append("通用 jar（未绑定到某个具体版本）");
        }
        return sb.toString();
    }

    /**
     * 直接运行本类时打印出厂设定。
     *
     * <p>这是给"验证分版本打包有没有生效"用的：{@code java -cp fusion-ml-1.20.1.jar
     * com.fusionml.FusionVersions} 应当回 {@code 1.20.1}。
     * 如果没有这个入口，就只能靠"启动游戏看它报什么"来间接判断，反馈太慢。
     */
    public static void main(String[] args) {
        String packaged = packagedTarget();
        System.out.println(packaged == null ? "generic" : packaged);
        Target t = byId(packaged);
        if (t != null) {
            System.out.println("Java " + t.requiredJava()
                    + " / " + t.platform()
                    + " / " + (t.deobfuscated() ? "不混淆" : "混淆"));
        }
    }
}
