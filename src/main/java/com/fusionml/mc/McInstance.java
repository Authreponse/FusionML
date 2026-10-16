package com.fusionml.mc;

import com.fusionml.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 一个现成的 Minecraft 游戏目录（官方启动器 / PCL / HMCL / Prism 生成的都行）。
 *
 * <p>它只做两件事，但这两件事是"能不能启动游戏"的地基：
 * <ol>
 *   <li><b>发现版本</b>：扫描 {@code versions/&lt;id&gt;/&lt;id&gt;.json}；</li>
 *   <li><b>解析继承链</b>：{@code inheritsFrom} 会把版本定义拆成父子多层
 *       （Forge/Fabric/NeoForge 装在原版之上时都是这个结构），
 *       必须合并成一份"有效版本"才能算出正确的类路径与主类。</li>
 * </ol>
 *
 * <p>合并规则按启动器规范来：
 * <ul>
 *   <li>{@code mainClass} / {@code assetIndex} / {@code javaVersion}：子版本覆盖父版本；</li>
 *   <li>{@code libraries}：父在前子在后，同一个 group:artifact 以子版本为准
 *       （否则子版本想换某个库的版本会换不掉）；</li>
 *   <li>{@code arguments}：子版本在前、父版本在后（参数顺序会影响 JVM 行为）；</li>
 *   <li>客户端 jar：子版本目录里有就用子版本的，没有就顺着链往上找。</li>
 * </ul>
 */
public final class McInstance {

    /** 合并后的有效版本。 */
    public static final class Effective {
        public final String id;
        public final String mainClass;
        public final int javaMajor;
        public final List<McVersion.Library> libraries = new ArrayList<>();
        public final List<String> jvmArgs = new ArrayList<>();
        public final List<String> gameArgs = new ArrayList<>();
        public final String assetIndexId;
        public final Path clientJar;
        public final List<String> chain = new ArrayList<>();
        public final Map<String, Boolean> features = McCache.defaultFeatures();

        Effective(String id, String mainClass, int javaMajor, String assetIndexId, Path clientJar) {
            this.id = id;
            this.mainClass = mainClass;
            this.javaMajor = javaMajor;
            this.assetIndexId = assetIndexId;
            this.clientJar = clientJar;
        }

        public List<McVersion.Library> applicableLibraries() {
            List<McVersion.Library> out = new ArrayList<>(libraries.size());
            for (McVersion.Library lib : libraries) {
                if (McRules.allows(lib.rules(), features)) {
                    out.add(lib);
                }
            }
            return out;
        }

        @Override
        public String toString() {
            return id + "（继承链 " + String.join(" → ", chain) + "，Java " + javaMajor
                    + "，库 " + libraries.size() + " 条）";
        }
    }

    private final Path root;
    /** 游戏运行目录：存档、日志、配置、mods 真正读写的地方（版本隔离时就是版本目录本身）。 */
    private final Path gameDir;

    public McInstance(Path root, Path gameDir) {
        this.root = root.toAbsolutePath().normalize();
        this.gameDir = gameDir.toAbsolutePath().normalize();
    }

    public McInstance(Path root) {
        this(root, root);
    }

    /**
     * 自动判断你给的是"游戏根目录"还是"某个版本目录"。
     *
     * <p>这两种都有人习惯：官方启动器用户会指向 {@code .minecraft}，
     * 而版本隔离（PCL 等）用户往往会直接指向 {@code versions/&lt;id&gt;}。
     * 判断依据是目录结构本身，不猜、不问：
     * <ul>
     *   <li>目录里有 {@code versions/} → 它就是根目录，游戏目录也是它；</li>
     *   <li>目录的父目录名叫 {@code versions} → 它是版本目录，根目录再往上一层，
     *       而<b>游戏目录就是它自己</b>（版本隔离布局）。</li>
     * </ul>
     */
    public static McInstance open(Path dir) {
        Path d = dir.toAbsolutePath().normalize();
        Path parent = d.getParent();
        String name = d.getFileName() == null ? "" : d.getFileName().toString();
        // 先判断"它自己是不是一个版本目录"，判据要够强：
        //   a) 里面就有 <目录名>.json（版本定义），或

        // 实测有些游戏目录里会自己长出一个 versions 子目录（模组或游戏写的），
        // 那样就会把版本目录误判成根目录。
        boolean selfIsVersionDir = Files.isRegularFile(d.resolve(name + ".json"))
                || (parent != null && "versions".equalsIgnoreCase(
                        parent.getFileName() == null ? "" : parent.getFileName().toString()));
        if (selfIsVersionDir && parent != null && parent.getParent() != null) {
            return new McInstance(parent.getParent(), d);
        }
        if (Files.isDirectory(d.resolve("versions"))) {
            return new McInstance(d, d);
        }
        // 兜底：当成根目录处理，后续校验会给出可读错误。
        return new McInstance(d, d);
    }

    public Path root() {
        return root;
    }

    public Path gameDir() {
        return gameDir;
    }

    public Path versionsDir() {
        return root.resolve("versions");
    }

    public Path librariesDir() {
        return root.resolve("libraries");
    }

    public Path assetsDir() {
        return root.resolve("assets");
    }

    /** 是否处于版本隔离布局（游戏目录 ≠ 根目录）。 */
    public boolean isolated() {
        return !root.equals(gameDir);
    }

    public boolean valid() {
        return Files.isDirectory(versionsDir()) || Files.isDirectory(librariesDir());
    }

    /** 列出全部版本 id（按目录名排序）。 */
    public List<String> versionIds() throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(versionsDir())) {
            return out;
        }
        try (Stream<Path> s = Files.list(versionsDir())) {
            for (Path p : s.toList()) {
                if (Files.isDirectory(p)) {
                    String name = p.getFileName().toString();
                    if (Files.isRegularFile(p.resolve(name + ".json"))) {
                        out.add(name);
                    }
                }
            }
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    public Path versionJson(String id) {
        return versionsDir().resolve(id).resolve(id + ".json");
    }

    /**
     * 解析版本继承链，产出一份合并后的有效版本。
     *
     * <p>带环检测：{@code inheritsFrom} 被人为写环的情况虽然少见，但一旦成环就会无限递归，
     * 所以这里显式拒绝并给出可读错误。
     */
    public Effective resolve(String id) throws IOException {
        List<Map<String, Object>> chainDefs = new ArrayList<>();
        List<String> chainIds = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String current = id;
        while (current != null && !current.isBlank()) {
            if (!seen.add(current)) {
                throw new IOException("版本继承链成环：" + String.join(" → ", chainIds) + " → " + current);
            }
            Path json = versionJson(current);
            if (!Files.isRegularFile(json)) {
                throw new IOException("找不到版本定义：" + json
                        + "（用 --mc-list 查看该目录下有哪些版本）");
            }
            Map<String, Object> def = Json.parseObject(Files.readAllBytes(json));
            chainDefs.add(def);
            chainIds.add(current);
            current = Json.str(def, "inheritsFrom", null);
        }

        // 从最顶层（最靠近原版）往子版本合并。
        Map<String, Object> merged = new LinkedHashMap<>();
        Map<String, McVersion.Library> libsByKey = new LinkedHashMap<>();
        List<String> jvmArgs = new ArrayList<>();
        List<String> gameArgs = new ArrayList<>();
        String clientJarOwner = null;

        for (int i = chainDefs.size() - 1; i >= 0; i--) {
            Map<String, Object> def = chainDefs.get(i);
            String defId = chainIds.get(i);
            // 标量：子版本覆盖父版本（越往后越"子"，所以直接覆盖）。
            for (String key : List.of("mainClass", "assets", "assetIndex", "javaVersion",
                    "type", "logging", "complianceLevel", "minimumLauncherVersion")) {
                if (def.containsKey(key)) {
                    merged.put(key, def.get(key));
                }
            }

            for (Object o : Json.arr(def.get("libraries"))) {
                Map<String, Object> lib = Json.obj(o);
                McVersion.Library parsed = parseLibrary(lib);
                if (parsed == null) {
                    continue;
                }
                libsByKey.put(libraryKey(parsed.name()), parsed);
            }
            // 参数：子版本在前，父版本在后，所以倒序插入时用"父的插在最前"。
            Object jvm = def.get("arguments") instanceof Map<?, ?> am
                    ? Json.obj(am).get("jvm") : null;
            List<String> defJvm = com.fusionml.launch.GameArguments.resolve(jvm,
                    placeholderPreview(), McCache.defaultFeatures());
            jvmArgs.addAll(0, defJvm);
            Object game = def.get("arguments") instanceof Map<?, ?> am2
                    ? Json.obj(am2).get("game") : null;
            List<String> defGame = com.fusionml.launch.GameArguments.resolve(game,
                    placeholderPreview(), McCache.defaultFeatures());
            gameArgs.addAll(0, defGame);
            if (def.get("minecraftArguments") instanceof String legacy) {
                List<String> legacyArgs = new ArrayList<>(List.of(legacy.split("\\s+")));
                gameArgs.addAll(0, legacyArgs);
            }

            Path jar = versionsDir().resolve(defId).resolve(defId + ".jar");
            if (clientJarOwner == null && Files.isRegularFile(jar)) {
                clientJarOwner = defId;
            }
        }

        String mainClass = Json.str(merged, "mainClass", "net.minecraft.client.main.Main");
        int javaMajor = (int) num(Json.obj(merged.get("javaVersion")).get("majorVersion"));
        String assetIndex = Json.str(Json.obj(merged.get("assetIndex")), "id", "");
        Path jar = clientJarOwner == null ? null : versionsDir().resolve(clientJarOwner)
                .resolve(clientJarOwner + ".jar");

        Effective eff = new Effective(id, mainClass, javaMajor, assetIndex, jar);
        eff.libraries.addAll(libsByKey.values());
        eff.jvmArgs.addAll(jvmArgs);
        eff.gameArgs.addAll(gameArgs);
        eff.chain.addAll(chainIds);
        return eff;
    }

    private static Map<String, String> placeholderPreview() {
        return Map.of();
    }

    /**
     * 库的去重键：{@code group:artifact:classifier}。
     *
     * <p>这里必须带上 classifier，只按 {@code group:artifact} 去重是个严重错误：
     * 像 {@code com.mojang:jtracy} 这种库，主包和四个平台的 natives 包是同一个
     * group:artifact、只是 classifier 不同。按 group:artifact 去重会让**主包被 natives 包覆盖**，
     * 结果类路径上只有 natives，游戏一加载就 NoClassDefFoundError（我们就是这么撞上的）。
     * 带上 classifier 后，同 classifier 的版本升级仍然能正确覆盖。
     */
    private static String libraryKey(String mavenName) {
        String[] parts = mavenName.split(":");
        if (parts.length < 2) {
            return mavenName;
        }
        String classifier = parts.length >= 4 ? parts[3] : "";
        return parts[0] + ":" + parts[1] + ":" + classifier;
    }

    private static McVersion.Library parseLibrary(Map<String, Object> lib) {
        Map<String, Object> downloadsNode = Json.obj(lib.get("downloads"));
        Map<String, Object> art = Json.obj(downloadsNode.get("artifact"));
        Map<String, String> natives = new LinkedHashMap<>();
        if (lib.get("natives") instanceof Map<?, ?> nm) {
            String archBits = "x86".equals(McRules.currentOsArch()) ? "32" : "64";
            for (Map.Entry<String, Object> e : Json.obj(nm).entrySet()) {
                natives.put(e.getKey(), String.valueOf(e.getValue()).replace("${arch}", archBits));
            }
        }
        String name = Json.str(lib, "name", "");
        if (name.isEmpty()) {
            return null;
        }
        // 有些库在本地提供了 jar，但版本定义里没有 downloads.artifact（PCL 这类启动器
        // 会把 artifact 段精简掉）。这种不能直接跳过 —— 路径可以由 Maven 坐标推出来，

        return new McVersion.Library(name,
                Json.str(art, "path", mavenPathOf(name)),
                Json.str(art, "url", ""),
                Json.str(art, "sha1", ""),
                natives,
                lib.get("rules"));
    }

    public static String mavenPathOf(String mavenName) {
        String[] parts = mavenName.split(":");
        if (parts.length < 3) {
            return mavenName;
        }
        String classifier = parts.length >= 4 ? "-" + parts[3] : "";
        return parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/"
                + parts[1] + "-" + parts[2] + classifier + ".jar";
    }

    public Path localLibraryPath(McVersion.Library lib) {
        String path = lib.path() == null || lib.path().isBlank()
                ? mavenPathOf(lib.name()) : lib.path();
        return librariesDir().resolve(path);
    }

    public record LibraryStatus(int present, int missing, List<String> missingNames) {
        public String summary() {
            if (missing == 0) {
                return "本地库齐全（" + present + " 个）";
            }
            return "本地已有 " + present + " 个，缺 " + missing + " 个：" + missingNames;
        }
    }

    /** 检查某个版本在当前系统上需要的库是否都已在本地。 */
    public LibraryStatus libraryStatus(Effective eff) {
        int present = 0;
        List<String> missing = new ArrayList<>();
        for (McVersion.Library lib : eff.applicableLibraries()) {
            if (Files.isRegularFile(localLibraryPath(lib))) {
                present++;
            } else if (missing.size() < 8) {
                missing.add(lib.name());
            }
        }
        return new LibraryStatus(present, missing.size(), missing);
    }

    public boolean hasAssets() {
        return Files.isDirectory(assetsDir().resolve("indexes"))
                && Files.isDirectory(assetsDir().resolve("objects"));
    }

    private static double num(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        return o == null ? 0 : Double.parseDouble(o.toString());
    }

    /** 便于展示：把版本按"是否像我们的目标"排序，最新的排前面。 */
    public List<String> versionsMatching(String keyword) {
        List<String> out = new ArrayList<>();
        try {
            for (String id : versionIds()) {
                if (keyword == null || keyword.isBlank()
                        || id.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))) {
                    out.add(id);
                }
            }
        } catch (IOException ignored) {

        }
        return out;
    }
}
