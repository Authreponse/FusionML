package com.fusionml.install;

import com.fusionml.earlywindow.EarlyWindowConfig;
import com.fusionml.mc.McDownloader;
import com.fusionml.util.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * FusionML 安装器。
 *
 * <p>双击运行（有界面）或命令行运行（无界面）都可以，做同一件事：
 * <ol>
 *   <li>确定游戏目录（默认自动探测 {@code %APPDATA%\.minecraft}）；</li>
 *   <li>释放标准目录结构：{@code mods/ config/ lib/ libraries/ versions/ resourcepacks/
 *       shaderpacks/ saves/ logs/ crash-reports/ .fusionml/}；</li>
 *   <li>把加载器本体释放到 {@code lib/fusionml/fusion-ml.jar}；</li>
 *   <li>写出早期窗口配置模板（已存在则不动，绝不覆盖玩家的设置）；</li>
 *   <li>注册启动器配置（合并写入 {@code launcher_profiles.json}，不是覆盖）与版本 JSON；</li>
 *   <li>可选：把指定目录里的模组包拷进 {@code mods/}；</li>
 *   <li>写安装清单，之后可用 {@code --uninstall} 精确还原。</li>
 * </ol>
 *
 * <p>安全边界：安装过程<b>只新增</b>，不删除、不覆盖任何已有文件；卸载只删除清单里记录过的
 * 由本安装器创建的文件与空目录，玩家的 {@code mods/}、{@code saves/} 等数据一律不碰。
 */
public final class FusionInstaller {

    /** 内嵌的加载器 jar 在安装器 jar 内的位置。 */
    private static final String EMBEDDED_LOADER = "/META-INF/fusionml/fusion-ml.jar";

    /** 版本 id 前缀，用于生成 versions/ 下的目录与启动器配置项。 */
    private static final String VERSION_PREFIX = "fusionml";

    public static void main(String[] args) {
        Options o = new Options();
        try {
            if (!parse(args, o)) {
                return;
            }
        } catch (RuntimeException e) {
            System.out.println("参数错误：" + e.getMessage());
            printHelp();
            return;
        }

        if (o.installFull) {
            System.exit(doInstallFull(o));
        }
        if (o.fetchVersion != null) {
            System.exit(doFetch(o));
        }
        if (o.listVersions) {
            System.exit(doListVersions(o));
        }
        if (o.gui || (args.length == 0 && !java.awt.GraphicsEnvironment.isHeadless())) {
            InstallerGui.show(o);
            return;
        }
        if (o.shotGui != null) {
            InstallerGui.screenshot(o, o.shotGui);
            return;
        }
        try {
            int code = o.uninstall ? uninstall(o, System.out::println) : install(o, System.out::println);
            if (code != 0) {
                System.exit(code);
            }
        } catch (Throwable t) {
            System.out.println("安装失败：" + t);
            if (o.verbose) {
                t.printStackTrace(System.out);
            }
            System.exit(2);
        }
    }

    /**
     * 自动拉取一个版本的游戏文件。
     *
     * <p>这是"空目录也能装"的关键一步：对照 Fabric / Quilt 安装器的做法 ——
     * 它们不需要用户先用官方启动器装一遍，而是自己把服务端（或客户端）拉下来。
     *
     * @return 进程退出码（0 成功）
     */
    /**
     * 装一个**完整可启动的客户端实例**：连 Minecraft 本体、libraries、assets 一起下。
     *
     * <p>与 {@link #install} 的区别：那条路要求"目标目录里已经有一个装好的版本"，
     * 这条路是**从零到能启动**。对照的是 Fabric 官方安装器 —— 它也不要求你
     * 先用官方启动器装一遍。
     */
    private static int doInstallFull(Options o) {
        try {
            McDownloader dl = new McDownloader();
            Path dir = o.gameDir != null ? o.gameDir : Path.of("fusionml-instance");
            String version = o.fetchVersion == null || o.fetchVersion.isBlank()
                    ? "latest" : o.fetchVersion;

            System.out.println("=== 安装完整实例 ===");
            System.out.println("目标目录：" + dir.toAbsolutePath());
            System.out.println("Minecraft 版本：" + version);
            System.out.println();

            FullInstance.Result r = FullInstance.install(dir, version, !o.noAssets, dl,
                    System.out::println);
            System.out.println();
            System.out.println("已装出可启动实例。");

            // 接下来把加载器放进去，并写出"我们自己"的版本定义（继承原版 + javaagent）。
            o.gameDir = dir;
            if (o.inheritVersion == null || o.inheritVersion.isBlank()) {
                o.inheritVersion = version;
            }
            int code = install(o, System.out::println);
            if (code != 0) {
                return code;
            }

            // 用**原版版本 id** 作为继承名，这样平台（若之后装上）也一并继承。
            Path ownJson = FullInstance.writeOwnVersion(dir, VERSION_PREFIX + "-" + o.loaderVersion,
                    version, InstanceLayout.loaderJar(dir));
            System.out.println("已写出可启动版本定义：" + dir.relativize(ownJson));
            System.out.println();
            System.out.println("下一步：启动器里选版本 “" + VERSION_PREFIX + "-" + o.loaderVersion
                    + "”，或直接用 start-game.cmd。");
            return 0;
        } catch (Throwable t) {
            System.out.println("完整实例安装失败：" + t);
            if (o.verbose) {
                t.printStackTrace(System.out);
            }
            return 4;
        }
    }

    private static int doFetch(Options o) {
        try {
            McDownloader dl = new McDownloader();
            Path dir = o.fetchDir != null ? o.fetchDir
                    : (o.gameDir != null ? o.gameDir : Path.of("."));
            AutoFetch.Fetched f = AutoFetch.fetch(dl, o.fetchVersion, o.fetchSide, dir,
                    System.out::println);
            System.out.println();
            System.out.println("完成：" + f.info().id() + " 的" + o.fetchSide + " jar");
            System.out.println("  文件：" + f.jar().toAbsolutePath());
            System.out.println("  来源：" + (f.downloaded() ? "本次下载" : "复用本地文件"));
            System.out.println("  版本类型：" + f.info().type() + "，发布于 " + f.info().releaseTime());
            if (!o.fetchOnly) {

                System.out.println();
                System.out.println("紧接着执行安装…");
                o.gameDir = dir;
                if (o.inheritVersion == null || o.inheritVersion.isBlank()) {
                    o.inheritVersion = f.info().id();
                }
                return install(o, System.out::println);
            }
            return 0;
        } catch (Throwable t) {
            System.out.println("拉取失败：" + t.getMessage());
            if (o.verbose) {
                t.printStackTrace(System.out);
            }
            // 失败必须是非零退出码 —— 脚本里靠它判断，不能假装成功。
            return 3;
        }
    }

    /** 列出官方清单里可选的版本。 */
    private static int doListVersions(Options o) {
        try {
            List<String> list = AutoFetch.listVersions(new McDownloader(),
                    o.listLimit, o.includeSnapshots);
            for (String s : list) {
                System.out.println("  " + s);
            }
            System.out.println();
            System.out.println("共 " + list.size() + " 个"
                    + (o.includeSnapshots ? "" : "（正式版；加 --snapshots 也看快照）"));
            return 0;
        } catch (Throwable t) {
            System.out.println("读取版本清单失败：" + t.getMessage());
            return 3;
        }
    }

    public static final class Options {
        public Path gameDir;
        public Path modsFrom;
        public String inheritVersion = "";
        public String loaderVersion = "1.0.0";
        public boolean noProfile;
        public boolean uninstall;
        public boolean dryRun;
        public boolean verbose;
        public boolean gui;

        /** 要拉取的版本号；{@code latest} 表示最新正式版。非 null 时执行拉取。 */
        public String fetchVersion;

        public String fetchSide = "server";

        public Path fetchDir;

        public boolean fetchOnly;
        /** 列出官方清单里的可选版本。 */
        public boolean listVersions;
        /** 列出版本时是否包含快照。 */
        public boolean includeSnapshots;
        /** 列出版本的条数上限（0 = 全部）。 */
        public int listLimit = 30;

        public boolean serverInstall;
        /** 装一个完整可启动的客户端实例（连 Minecraft 本体一起下）。 */
        public boolean installFull;
        /** 跳过 assets 下载（约 1.4 GB）；只用于验证启动链路。 */
        public boolean noAssets;

        public Path shotGui;
        /** 写进启动脚本的版本 id；留空时由安装器从目录里自动判断。 */
        public String versionId = "";
    }

    /**
     * 执行安装。
     *
     * @param out 进度输出（GUI 模式会换成界面里的日志区）
     * @return 0 表示成功
     */
    public static int install(Options o, java.util.function.Consumer<String> out) throws IOException {
        Path gameDir = o.gameDir != null ? o.gameDir : InstanceLayout.detectDefaultGameDir();
        out.accept("游戏目录：" + gameDir.toAbsolutePath());
        if (o.dryRun) {
            out.accept("（预演模式：只显示将要执行的动作，不写任何文件）");
        }

        InstanceLayout.EnsureResult ensured = o.dryRun
                ? new InstanceLayout.EnsureResult(List.of("（预演）"), List.of(), gameDir)
                : InstanceLayout.ensure(gameDir);
        out.accept("目录结构：" + (o.dryRun ? "将补齐标准目录" : ensured.summary()));

        List<String> manifest = new ArrayList<>();
        manifest.add("gameDir=" + gameDir.toAbsolutePath());
        manifest.add("installedAt=" + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        manifest.add("loaderVersion=" + o.loaderVersion);
        for (String d : InstanceLayout.STANDARD_DIRS) {
            manifest.add("dir=" + d);
        }

        // 2) 释放加载器本体
        Path loaderTarget = InstanceLayout.loaderJar(gameDir);
        if (o.dryRun) {
            out.accept("将释放加载器：" + loaderTarget
                    + "（按目标版本 " + (o.inheritVersion.isBlank() ? "未指定" : o.inheritVersion)
                    + " 选取）");
        } else {
            long bytes = extractLoader(loaderTarget, o.inheritVersion);
            if (bytes > 0) {
                out.accept(String.format(Locale.ROOT, "已释放加载器：%s（%.0f KB）",
                        gameDir.relativize(loaderTarget), bytes / 1024.0));
                out.accept("  来源：" + LOADER_SOURCE.get());
                manifest.add("file=" + InstanceLayout.LOADER_JAR_RELATIVE);
            } else {
                out.accept("警告：没有找到内嵌的加载器 jar。开发模式下请先运行 build.ps1 生成 "
                        + "build/fusion-ml.jar，再重新打包安装器。");
            }
        }

        Path cfg = InstanceLayout.earlyWindowConfig(gameDir);
        if (Files.exists(cfg)) {
            out.accept("保留已有配置：" + gameDir.relativize(cfg));
        } else if (!o.dryRun) {
            Files.createDirectories(cfg.getParent());
            Files.writeString(cfg, EarlyWindowConfig.template(), StandardCharsets.UTF_8);
            out.accept("已写出配置模板：" + gameDir.relativize(cfg));
            manifest.add("file=config/fusion-earlywindow.properties");
        }

        // 4) 启动器配置与版本 JSON
        if (!o.noProfile) {
            String versionId = VERSION_PREFIX + "-" + o.loaderVersion;
            // inheritsFrom 要的是**玩家实例里的真实版本目录 id**（例如
            // "1.21.1-NeoForge_21.1.249"），不是目标版本（"1.21.1"）。
            // 这两者混用会让启动器找不到父版本 —— 表现是"启动器不识别这个版本、
            // 从启动器启动不加载 mod、早期窗口也没了"。
            String host = hostVersionId(gameDir, o.inheritVersion);
            if (o.dryRun) {
                out.accept("将注册启动器版本：" + versionId + "（继承 " + host + "）");
            } else {
                String note = registerProfile(gameDir, versionId, host, loaderTarget);
                out.accept(note);
                manifest.add("version=" + versionId);
                if (!host.isBlank()) {
                    manifest.add("file=versions/" + versionId + "/" + versionId + ".json");
                }
            }
        }

        if (o.modsFrom != null) {
            int copied = copyMods(o.modsFrom, gameDir.resolve("mods"), o.dryRun, out);
            out.accept("已拷入 " + copied + " 个模组包到 mods/");
        }

        //    图形安装器/启动器不是所有人都用，而"双击脚本就能进游戏"是所有人都能用的方式。
        if (!o.dryRun) {
            String versionId = o.versionId != null && !o.versionId.isBlank()
                    ? o.versionId : detectVersionId(gameDir);
            try {
                for (String script : writeLaunchers(gameDir, loaderTarget, versionId)) {
                    manifest.add("file=" + script);
                }
                out.accept("已生成启动脚本：start-game.cmd / start-server.cmd（版本 " + versionId + "）");
            } catch (IOException e) {
                out.accept("启动脚本生成失败（不影响安装）：" + e.getMessage());
            }

            if (o.serverInstall) {
                try {
                    for (String extra : writeServerExtras(gameDir, out)) {
                        manifest.add("file=" + extra);
                    }
                } catch (IOException e) {
                    out.accept("服务端附加文件生成失败（不影响安装）：" + e.getMessage());
                }
            }
        }

        // 4.5) 把加载器注入**玩家自己的版本定义** —— 这一步才是"从启动器启动就生效"的关键。
        //
        // 为什么必须做：PCL / HMCL 这类启动器启动的是**玩家实例本身**（例如 versions\26.3），
        // 而不是我们写的那个 fusionml-1.0.0。而且 PCL 把 Fabric/NeoForge 平台记在它自己的
        // 配置里（PCL\config.v1.yml 的 VersionFabric 字段），**不写进版本 json** ——
        // 所以我们的 fusionml-1.0.0 就算 inheritsFrom 指过去，继承到的也是一个
        // "纯原版"定义（实测 26.3.json 的 mainClass 是 net.minecraft.client.main.Main，
        // 没有任何 fabricloader 库），模组自然不加载、平台参数也全丢。
        //
        // 唯一可行的做法：往玩家实例的 <id>.json 的 arguments.jvm 里**追加** -javaagent。
        // 这样启动器启动这个实例时自动带上我们，且其它实例不受影响。
        // 原文件会先备份，卸载时精确还原（见 uninstall）。
        if (!o.noProfile) {
            String host = hostVersionId(gameDir, o.inheritVersion);
            if (!host.isBlank()) {
                try {
                    String note = injectIntoHostVersion(gameDir, host, loaderTarget, o.dryRun);
                    out.accept(note);
                    manifest.add("injected=" + host);
                    String pcl = markInPcl(gameDir, host, o.dryRun);
                    if (!pcl.isEmpty()) {
                        out.accept(pcl);
                        manifest.add("pclmark=" + host);
                    }
                } catch (IOException e) {
                    out.accept("注入宿主版本定义失败（从启动器启动将不生效）：" + e.getMessage());
                }
            } else {
                out.accept("⚠ 没能识别玩家实例的版本 id，无法注入宿主版本定义 —— "
                        + "从启动器启动不会带上加载器。");
            }
        }

        if (!o.dryRun) {
            InstanceLayout.writeManifest(gameDir, manifest);
            out.accept("安装完成。清单已写入 " + gameDir.relativize(InstanceLayout.manifest(gameDir)));
            out.accept("提示：启动器里选择版本 “" + VERSION_PREFIX + "-" + o.loaderVersion
                    + "”；没有启动器时，直接用 lib/fusionml/fusion-ml.jar 启动即可。");
        }
        return 0;
    }

    public static int uninstall(Options o, java.util.function.Consumer<String> out) throws IOException {
        Path gameDir = o.gameDir != null ? o.gameDir : InstanceLayout.detectDefaultGameDir();
        List<String> manifest = InstanceLayout.readManifest(gameDir);
        if (manifest.isEmpty()) {
            out.accept("没有找到安装清单，未做任何改动：" + gameDir.toAbsolutePath());
            return 1;
        }
        out.accept("从 " + gameDir.toAbsolutePath() + " 卸载 FusionML");
        // 安全前提：只删清单里记录过的相对路径，且必须落在游戏目录内。
        Path root = gameDir.toAbsolutePath().normalize();

        // 0) 先还原被注入的宿主版本定义。
        //    必须排在删文件之前 —— 清单里的 injected= 记录不参与删除循环，
        //    而备份文件 <id>.json.fusionml-backup 如果先被别的规则删掉就还原不了了。
        for (String line : manifest) {
            if (!line.startsWith("injected=")) {
                continue;
            }
            String host = line.substring("injected=".length()).trim();
            for (Path json : List.of(
                    root.resolve("versions").resolve(host).resolve(host + ".json"),
                    root.resolve(host + ".json"))) {
                Path backup = json.resolveSibling(host + ".json.fusionml-backup");
                if (!Files.isRegularFile(backup)) {
                    continue;
                }
                if (o.dryRun) {
                    out.accept("将还原宿主版本定义：" + root.relativize(json));
                    continue;
                }
                Files.copy(backup, json, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(backup);
                out.accept("已还原宿主版本定义：" + root.relativize(json)
                        + "（去掉注入的 -javaagent）");
            }
        }

        // 0.5) 还原 PCL 的实例标记（Info / Logo）。
        for (String line : manifest) {
            if (!line.startsWith("pclmark=")) {
                continue;
            }
            for (String name : List.of("config.v1.yml", "Setup.ini")) {
                Path f = root.resolve("PCL").resolve(name);
                Path backup = f.resolveSibling(name + ".fusionml-backup");
                if (!Files.isRegularFile(backup)) {
                    continue;
                }
                if (o.dryRun) {
                    out.accept("将还原 PCL 标记：" + name);
                    continue;
                }
                Files.copy(backup, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(backup);
                out.accept("已还原 PCL 标记：" + name);
            }
        }

        for (String line : manifest) {
            if (!line.startsWith("file=") && !line.startsWith("version=")) {
                continue;
            }
            String rel = line.startsWith("file=") ? line.substring(5) : "versions/" + line.substring(8);
            Path target = root.resolve(rel).normalize();
            if (!target.startsWith(root)) {
                out.accept("已跳过越界路径：" + rel);
                continue;
            }
            if (!Files.exists(target)) {
                continue;
            }
            if (o.dryRun) {
                out.accept("将删除：" + rel);
                continue;
            }
            if (Files.isDirectory(target)) {
                try (var s = Files.walk(target)) {
                    for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(p);
                    }
                }
            } else {
                Files.deleteIfExists(target);
            }
            out.accept("已删除：" + rel);
        }

        if (!o.dryRun) {
            // 注意必须用绝对化的 root 来拼路径：gameDir 可能是相对路径，
            // 混用相对/绝对会让 startsWith(root) 校验失败，导致空目录永远清不掉。
            removeIfEmpty(root.resolve("lib/fusionml"), root, out);
            removeIfEmpty(root.resolve("lib"), root, out);
            removeIfEmpty(root.resolve(InstanceLayout.STATE_DIR), root, out);
        }
        removeProfileEntry(root, manifest, o.dryRun, out);
        out.accept("安装清单保留在 .fusionml/install-manifest.txt，便于复查本次安装做过什么。");
        out.accept("卸载完成。mods/、saves/、config/ 等目录内容未被改动。");
        return 0;
    }

    private static void removeProfileEntry(Path root, List<String> manifest, boolean dryRun,
                                           java.util.function.Consumer<String> out) throws IOException {
        String versionId = null;
        for (String line : manifest) {
            if (line.startsWith("version=")) {
                versionId = line.substring("version=".length());
            }
        }
        if (versionId == null) {
            return;
        }
        Path profiles = root.resolve("launcher_profiles.json");
        if (!Files.isReadable(profiles)) {
            return;
        }
        Map<String, Object> data;
        try {
            data = Json.parseObject(Files.readAllBytes(profiles));
        } catch (RuntimeException e) {
            out.accept("launcher_profiles.json 解析失败，已跳过（未改动原文件）");
            return;
        }
        Object profilesNode = data.get("profiles");
        if (!(profilesNode instanceof Map<?, ?> pm)) {
            return;
        }
        Map<String, Object> copy = new LinkedHashMap<>(Json.obj(profilesNode));
        if (copy.remove(versionId) == null) {
            return;
        }
        if (dryRun) {
            out.accept("将从启动器配置中移除：" + versionId);
            return;
        }
        data.put("profiles", copy);
        Files.writeString(profiles, Json.write(data), StandardCharsets.UTF_8);
        out.accept("已从启动器配置中移除 " + versionId);
    }

    private static void removeIfEmpty(Path dir, Path root, java.util.function.Consumer<String> out)
            throws IOException {
        if (!Files.isDirectory(dir) || !dir.normalize().startsWith(root)) {
            return;
        }
        try (var s = Files.list(dir)) {
            if (s.findAny().isPresent()) {
                return;
            }
        }
        Files.deleteIfExists(dir);
        out.accept("已清理空目录：" + root.relativize(dir));
    }

    /**
     * 释放内嵌的加载器 jar。
     *
     * <p>优先从安装器 jar 内部读取（这样安装器被放到哪都能用）；
     * 开发模式下没有内嵌资源，则退回读 {@code build/fusion-ml.jar}。
     *
     * <h2>单一安装器要装多个版本</h2>
     *
     * <p>以前是"每个版本一个安装器 jar"，各自内嵌自己那份 loader，路径固定为
     * {@code /META-INF/fusionml/fusion-ml.jar}。现在合并成一个安装器，
     * 它必须<b>按用户选中的版本</b>去取对应的 loader —— 否则拿 26.3 的 loader
     * 去装 1.20.1 的实例，会因为 Java 主版本与平台适配不同而直接跑不起来。
     *
     * <p>所以查找顺序是：
     * <ol>
     *   <li>{@code /META-INF/fusionml/loaders/fusion-ml-<版本>.jar}（分版本内嵌）；</li>
     *   <li>{@code /META-INF/fusionml/fusion-ml.jar}（旧的单版本布局，向后兼容）；</li>
     *   <li>{@code build/fusion-ml-<版本>.jar} → {@code build/fusion-ml.jar}（开发模式）。</li>
     * </ol>
     *
     * @param target  写到哪里
     * @param version 用户选中的目标版本 id（可为 null，此时只走通用回退）
     * @return 写入的字节数；0 表示没找到来源
     */
    private static long extractLoader(Path target, String version) throws IOException {
        byte[] data = null;
        String used = null;
        if (version != null && !version.isBlank()) {
            String specific = "/META-INF/fusionml/loaders/fusion-ml-" + version.trim() + ".jar";
            try (InputStream in = FusionInstaller.class.getResourceAsStream(specific)) {
                if (in != null) {
                    data = in.readAllBytes();
                    used = "内嵌（按版本）" + specific;
                }
            }
        }
        if (data == null) {
            try (InputStream in = FusionInstaller.class.getResourceAsStream(EMBEDDED_LOADER)) {
                if (in != null) {
                    data = in.readAllBytes();
                    used = "内嵌（通用）" + EMBEDDED_LOADER;
                }
            }
        }
        if (data == null) {
            Path dev = null;
            if (version != null && !version.isBlank()) {
                Path v = Path.of("build", "fusion-ml-" + version.trim() + ".jar");
                if (Files.isReadable(v)) {
                    dev = v;
                }
            }
            if (dev == null) {
                Path g = Path.of("build", "fusion-ml.jar");
                if (Files.isReadable(g)) {
                    dev = g;
                }
            }
            if (dev != null) {
                data = Files.readAllBytes(dev);
                used = "开发目录 " + dev;
            }
        }
        if (data == null) {
            return 0;
        }
        Files.createDirectories(target.getParent());
        Files.write(target, data);
        LOADER_SOURCE.set(used == null ? "未知" : used);
        return data.length;
    }

    /** 上一次 {@link #extractLoader} 用的是哪份来源（日志里如实报出来）。 */
    private static final ThreadLocal<String> LOADER_SOURCE = new ThreadLocal<>();

    /**
     * 注册启动器版本。
     *
     * <p>{@code launcher_profiles.json} 是启动器的用户数据，必须<b>合并</b>而不是覆盖：
     * 这里读出原有 JSON，只往 profiles 里加一项，其余原样保留写回。
     * 另外写出 {@code versions/<id>/<id>.json}，让官方启动器能在版本列表里看到它。
     */
    /**
     * 生成双击即用的启动脚本（客户端 + 服务端各一份，Windows 与 Linux/macOS 各一份）。
     *
     * <p>为什么要有这一步：安装器把"加载器 + 游戏文件 + 模组"放进同一个目录之后，
     * 用户还需要一个明确的起点。启动器配置是给有启动器的人准备的，
     * 而脚本是给"我就要双击一下"的人准备的 —— 两种都要有。
     *
     * <p>脚本里刻意不写死 java 路径：不同机器装的 JDK 位置不同，
     * 加载器自己会去找满足版本要求的 Java（找不到时会在控制台说明原因）。
     * 需要指定时，改开头那一行的 JAVA 变量即可。
     */
    private static List<String> writeLaunchers(Path gameDir, Path loaderJar, String versionId)
            throws IOException {
        String relative = gameDir.relativize(loaderJar).toString().replace('\\', '/');
        String cmd = """
                @echo off
                rem FusionML 启动脚本（由安装器生成）。改下面这一行可以指定 Java 路径。
                setlocal
                set "HERE=%~dp0"
                set "JAVA=java"
                "%JAVA%" -Xmx4G -jar "%HERE%%LOADER%" --mc-dir "%HERE%." --mc-version "%VERSION%" %MODE%
                echo.
                pause
                """.replace("%LOADER%", relative)
                   .replace("%VERSION%", versionId)
                   .replace("%MODE%", "--mc-launch");
        String sh = """
                #!/bin/sh
                # FusionML 启动脚本（由安装器生成）。改下面这一行可以指定 Java 路径。
                HERE="$(cd "$(dirname "$0")" && pwd)"
                JAVA="${JAVA:-java}"
                exec "$JAVA" -Xmx4G -jar "$HERE/%LOADER%" --mc-dir "$HERE" --mc-version "%VERSION%" --mc-launch "$@"
                """.replace("%LOADER%", relative).replace("%VERSION%", versionId);

        List<String> written = new ArrayList<>(2);
        Path cmdFile = gameDir.resolve("start-game.cmd");
        Files.writeString(cmdFile, cmd, StandardCharsets.UTF_8);
        written.add("start-game.cmd");

        Path shFile = gameDir.resolve("start-game.sh");
        Files.writeString(shFile, sh, StandardCharsets.UTF_8);
        written.add("start-game.sh");

        written.addAll(writeServerScripts(gameDir, loaderJar));
        return written;
    }

    /**
     * 服务端的启动脚本 —— 与客户端刻意不同。
     *
     * <p>客户端那条路（{@code --mc-launch}）是我们自己的 main 去接管启动：客户端本来就要拼
     * classpath、要注入平台运行时，自己当启动器是合理的。服务端则相反：官方 {@code server.jar}
     * 是一个 <b>bundler</b>（{@code Main-Class: net.minecraft.bundler.Main}），它会自己解包
     * {@code META-INF/versions.list} 与 {@code META-INF/libraries.list} 描述的依赖，再用新的
     * classloader 调真正的入口 {@code net.minecraft.server.Main}。再去写第二个启动器只会多一层
     * 会过期的重复实现，而 Fabric 的安装器也正是让我们"包装它"而不是"替代它"。
     *
     * <p>所以这里生成的脚本仍然执行 {@code java -jar server.jar nogui}，
     * 只在前面设一个 {@code JAVA_TOOL_OPTIONS} 把加载器作为 javaagent 挂上去。
     * 该变量由 JVM 自己读取，因此不必改动命令行；实测 26.3 的 bundler 不 fork 子 JVM
     * （在原 JVM 内换 classloader），所以这个通道一定会被应用到。
     *
     * <p>没有 {@code server.jar} 时不生成：一个指向不存在文件的脚本比没有脚本更误导人。
     */
    private static List<String> writeServerScripts(Path gameDir, Path loaderJar) throws IOException {
        if (!Files.isRegularFile(gameDir.resolve(com.fusionml.mc.ServerFetch.SERVER_JAR_NAME))) {
            return List.of();
        }
        try {
            // Java 必须按**目标版本的要求**解析，而不是按"跑安装器的这个 JVM"。
            // 这两者常常不同：实测本机 PATH 与安装器自己都是 Java 21，
            // 而 26.3 需要 Java 25 —— 照安装器自己的版本写进脚本，服务端一起来就是
            // UnsupportedClassVersionError（class file version 69.0 > 65.0）。
            int required = com.fusionml.FusionVersions.packagedRequiredJava();
            Path javaExe = null;
            try {
                com.fusionml.launch.JavaFinder.Found f =
                        com.fusionml.launch.JavaFinder.find(required);
                if (f != null) {
                    javaExe = f.javaExe();
                }
            } catch (Throwable ignored) {
                // 探测失败就让脚本退回 PATH 上的 java（脚本里有注释说明怎么改）。
            }
            com.fusionml.mc.ServerFetch.Launchers l = com.fusionml.mc.ServerFetch
                    .writeServerLaunchers(gameDir, loaderJar, null, 4096, javaExe);
            List<String> names = new ArrayList<>();
            for (Path p : l.written()) {
                names.add(gameDir.relativize(p).toString());
            }
            return names;
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * 服务端安装时额外写的东西。
     *
     * <p>为什么单独一个方法：服务端与客户端有实质差别，把差异集中在一处比散在安装流程里好查。
     * 这里写的是：
     * <ul>
     *   <li>{@code eula.txt} —— 提示用户必须自己同意 EULA。
     *       <b>我们只写 {@code eula=false} 与说明，绝不替用户同意</b>：
     *       那是用户与 Mojang 之间的法律约定，代点同意是不对的。</li>
     *   <li>{@code server.properties} 的最小骨架（已存在就不动）。</li>
     * </ul>
     */
    private static List<String> writeServerExtras(Path gameDir,
                                                  java.util.function.Consumer<String> out)
            throws IOException {
        List<String> written = new ArrayList<>(2);

        Path eula = gameDir.resolve("eula.txt");
        if (!Files.exists(eula)) {
            Files.writeString(eula, """
                    # 由 FusionML 安装器生成。
                    #
                    # 运行 Minecraft 服务端即表示你同意 Mojang 的最终用户许可协议（EULA）：
                    #   https://aka.ms/MinecraftEULA
                    #
                    # 我们**不会替你同意** —— 那是你与 Mojang 之间的约定。
                    # 阅读上面的协议后，把下面的 false 改成 true 才能启动服务端。
                    eula=false
                    """, StandardCharsets.UTF_8);
            written.add("eula.txt");
            out.accept("已写出 eula.txt —— 启动服务端前需要你自己阅读 EULA 并改成 true");
        }

        Path props = gameDir.resolve("server.properties");
        if (!Files.exists(props)) {
            Files.writeString(props, """
                    # 由 FusionML 安装器生成的最小骨架；其余项由服务端首次启动时补齐。
                    motd=FusionML Server
                    server-port=25565
                    max-players=20
                    online-mode=true
                    """, StandardCharsets.UTF_8);
            written.add("server.properties");
        }
        return written;
    }

    /**
     * 猜一个版本 id 写进启动脚本。
     *
     * <p>顺序：目录本身就是版本目录 → 它自己；否则取 versions 下第一个带 json 的目录。
     * 猜不出来就留空，脚本里那一项会变成空字符串 —— 加载器会给出"找不到该版本"的明确报错，
     * 比写一个错 id 然后在别处炸掉要好。
     */
    private static String detectVersionId(Path gameDir) {
        String self = gameDir.getFileName() == null ? "" : gameDir.getFileName().toString();
        if (!self.isBlank() && Files.isRegularFile(gameDir.resolve(self + ".json"))) {
            return self;
        }
        Path versions = gameDir.resolve("versions");
        if (Files.isDirectory(versions)) {
            try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(versions)) {
                for (Path p : ds) {
                    String id = p.getFileName().toString();
                    if (Files.isRegularFile(p.resolve(id + ".json"))) {
                        return id;
                    }
                }
            } catch (IOException ignored) {

            }
        }
        return "";
    }

    /**
     * 写版本定义 + 注册到启动器。
     *
     * <h2>为什么 {@code inheritsFrom} 必须是"玩家自己的版本目录 id"</h2>
     *
     * <p>这里曾经把 {@code o.inheritVersion}（**目标版本**，如 {@code 1.21.1}）直接
     * 写进 {@code inheritsFrom}。同一个值被当成两件事用，结果是灾难性的：
     * <pre>
     * 玩家实例的版本目录 id = "1.21.1-NeoForge_21.1.249"
     * 写出的 inheritsFrom  = "1.21.1"        ← 这个版本**根本不存在**
     * </pre>
     * 表现（用户实测报的）：
     * <ul>
     *   <li>启动器**不识别**这个版本 —— 父版本找不到，整个继承链断了；</li>
     *   <li>从启动器启动**不加载 mod** —— 因为我们自己写了
     *       {@code mainClass=com.fusionml.FusionLauncher} 且 {@code libraries=[]}，
     *       等于完全绕开了平台的启动器（Fabric 的 {@code KnotClient}、
     *       NeoForge 的 {@code BootstrapLauncher}）；</li>
     *   <li>从启动器启动**早期窗口也没了** —— 同理，平台的 JVM 参数一个都没继承到。</li>
     * </ul>
     *
     * <p>正确做法是 **Fabric / Forge 安装器那一套**：{@code inheritsFrom} 指向玩家
     * 原有的那个版本，{@code mainClass} 与 {@code libraries} **都不写**
     * （让启动器从父版本继承平台自己的启动器与全部依赖），
     * 我们只**追加**自己需要的东西（javaagent 与我们的库）。
     *
     * @param versionId  要写出的版本 id（例如 {@code fusionml-1.0.0}）
     * @param hostVersion 玩家实例里的真实版本目录 id，用作 {@code inheritsFrom}；
     *                    为空则不写该字段并如实提示
     */
    /**
     * 找"玩家实例里的真实版本目录 id"，用作版本定义的 {@code inheritsFrom}。
     *
     * <p>与 {@link #detectVersionId} 的区别是<b>优先挑与目标版本匹配的那个</b>。
     * 玩家的 {@code versions/} 下通常躺着好几个版本（实测本机有 40+ 个目录，
     * 包括 {@code 1.20.1-Fabric 0.19.3}、{@code 26.3-Fabric 0.19.5} 等等）。
     * 如果随便挑一个，就会让 {@code fusionml-1.0.0} 去继承一个**根本不是目标版本**的实例 ——
     * 例如装 1.21.1 却继承了 26.3，启动器能用但装出来的东西完全不对。
     *
     * @param target 目标版本（如 {@code 1.21.1}）；为空则退化为"取第一个可用的"
     */
    private static String hostVersionId(Path gameDir, String target) {
        // 游戏目录自己就是版本目录（版本隔离布局）→ 就是它
        String self = gameDir.getFileName() == null ? "" : gameDir.getFileName().toString();
        if (!self.isBlank() && Files.isRegularFile(gameDir.resolve(self + ".json"))) {
            return self;
        }
        Path versions = gameDir.resolve("versions");
        if (!Files.isDirectory(versions)) {
            return "";
        }
        List<String> all = new ArrayList<>();
        try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(versions)) {
            for (Path p : ds) {
                String id = p.getFileName().toString();
                if (Files.isRegularFile(p.resolve(id + ".json"))) {
                    all.add(id);
                }
            }
        } catch (IOException ignored) {
            return "";
        }
        if (all.isEmpty()) {
            return "";
        }
        // 优先与目标版本匹配的（用 FusionVersions.inferFrom 归一化，能认
        // "1.21.1-NeoForge_21.1.249" → 目标 "1.21.1"）。
        if (target != null && !target.isBlank()) {
            com.fusionml.FusionVersions.Target want = com.fusionml.FusionVersions.byId(target);
            String wantMc = want == null ? target : want.mcVersion();
            List<String> matched = new ArrayList<>();
            for (String id : all) {
                com.fusionml.FusionVersions.Target t = com.fusionml.FusionVersions.inferFrom(id);
                if (t != null && t.mcVersion().equals(wantMc)) {
                    matched.add(id);
                }
            }
            if (!matched.isEmpty()) {
                // 同版本可能有多个（1.21.1-Fabric / 1.21.1-NeoForge）—— 取排序后第一个，
                // 保证同一台机器上多次安装结果一致。
                java.util.Collections.sort(matched);
                return matched.get(0);
            }
        }
        java.util.Collections.sort(all);
        return all.get(0);
    }

    /**
     * 把 {@code -javaagent} 注入玩家实例的版本定义。
     *
     * <h2>为什么是"注入宿主"而不是"新建一个版本"</h2>
     *
     * <p>启动器（PCL / HMCL / 官方）启动的是**玩家实例**。而模组平台（Fabric / NeoForge）
     * 是由启动器**自己**拼进 classpath 的（PCL 记在 {@code PCL/config.v1.yml}，
     * 不写进版本 json）。所以任何"新建一个继承版本"的方案都拿不到平台 ——
     * 继承出来的只是个纯原版定义，模组不加载、平台参数丢失。
     *
     * <p>只有改玩家实例自己的 json，才能让启动器在启动该实例时带上我们。
     *
     * <h2>三条安全约束</h2>
     * <ol>
     *   <li><b>先备份</b>：原文件整份拷成 {@code <id>.json.fusionml-backup}，
     *       卸载时用它精确还原。</li>
     *   <li><b>只追加，不覆盖</b>：往 {@code arguments.jvm} 里加项，不动任何既有项。</li>
     *   <li><b>幂等</b>：已经注入过就不重复加（否则重复安装会堆一串 javaagent）。</li>
     * </ol>
     *
     * <p><b>插入位置很关键</b>：必须插在 {@code -cp} / {@code ${classpath}} **之前**。
     * 实测该版本的 {@code jvm} 数组末尾正是 {@code ["-cp", "${classpath}"]}，
     * 若把 {@code -javaagent} 追加到最后，它会落在 classpath **之后**，
     * 某些启动器会把它当成 classpath 的一部分从而启动失败。
     */
    private static String injectIntoHostVersion(Path gameDir, String hostId, Path loaderJar,
                                                boolean dryRun) throws IOException {
        Path versionJson = gameDir.resolve("versions").resolve(hostId).resolve(hostId + ".json");
        if (!Files.isRegularFile(versionJson)) {
            // 版本隔离时，版本 json 就在游戏目录自己身上。
            Path alt = gameDir.resolve(hostId + ".json");
            if (Files.isRegularFile(alt)) {
                versionJson = alt;
            } else {
                return "未找到宿主版本定义（" + hostId + ".json），跳过注入";
            }
        }
        Map<String, Object> root;
        try {
            root = Json.parseObject(Files.readAllBytes(versionJson));
        } catch (RuntimeException e) {
            return "宿主版本定义解析失败（保持原文件不动）：" + e.getMessage();
        }
        Map<String, Object> arguments = Json.obj(root.get("arguments"));
        List<Object> jvm = new ArrayList<>();
        Object raw = arguments.get("jvm");
        if (raw instanceof List<?> l) {
            jvm.addAll(l);
        }
        String agent = "-javaagent:${game_directory}/" + gameDir.relativize(loaderJar)
                .toString().replace('\\', '/');
        // 幂等：已经有同一个 agent 就不再插。
        for (Object o : jvm) {
            if (agent.equals(o)) {
                return "宿主版本定义已注入过（" + hostId + "），跳过";
            }
        }
        // 插到 -cp 之前；找不到 -cp 就插到最前（比插到最后安全）。
        int at = jvm.size();
        for (int i = 0; i < jvm.size(); i++) {
            if ("-cp".equals(jvm.get(i)) || "-classpath".equals(jvm.get(i))) {
                at = i;
                break;
            }
        }
        jvm.add(at, agent);
        jvm.add(at + 1, "-Dfusion.gameDir=${game_directory}");
        jvm.add(at + 2, "-Dfusion.version=" + hostId);
        arguments.put("jvm", jvm);
        root.put("arguments", arguments);

        if (dryRun) {
            return "将注入宿主版本定义：" + hostId + ".json（在 -cp 之前加 -javaagent）";
        }
        // 备份只在第一次做：不要用"已注入过的文件"覆盖掉真正的原始备份。
        Path backup = versionJson.resolveSibling(hostId + ".json.fusionml-backup");
        if (!Files.isRegularFile(backup)) {
            Files.copy(versionJson, backup);
        }
        Files.write(versionJson, Json.write(root).getBytes(StandardCharsets.UTF_8));
        return "已注入宿主版本定义：" + hostId + ".json（备份在 " + backup.getFileName()
                + "，卸载时会还原）";
    }

    /**
     * 在 PCL 的实例概览里标出"这个实例装了 FusionML"。
     *
     * <h2>PCL 是靠什么显示实例状态的（实测得出）</h2>
     *
     * <p>PCL 不用版本 json 描述实例，而是自己的两个文件：
     * <ul>
     *   <li>{@code PCL/config.v1.yml} —— {@code VersionFabric} / {@code VersionNeoForge}
     *       决定它启动时拼哪个平台；{@code Logo} 是实例概览里的图标；{@code Info} 是说明文字。</li>
     *   <li>{@code PCL/Setup.ini} —— 同一份信息的另一处副本（{@code Info} / {@code Logo} /
     *       {@code VersionNeoForge}）。</li>
     * </ul>
     * 实测一个 NeoForge 实例的值：
     * <pre>
     * config.v1.yml: Logo: .../Blocks/NeoForge.png    Info: ''
     * Setup.ini:     Info:正式版 1.21.1, NeoForge 21.1.249
     *                Logo:pack://.../Blocks/NeoForge.png
     * </pre>
     * 也就是"装了加载器的实例"靠 {@code Logo} + {@code Info} 体现出来。
     *
     * <h2>本方法做什么</h2>
     *
     * <ol>
     *   <li>{@code Info} 追加 {@code , FusionML}（**保留原有内容**，不覆盖）；</li>
     *   <li>{@code Logo} 换成本实例平台对应的内置图标（原版 Grass 看不出装没装东西）；
     *       <b>已经非 Grass 的不动</b> —— 那说明玩家或其它加载器已经标过，不要抢。</li>
     * </ol>
     *
     * <p><b>平台字段（VersionFabric / VersionNeoForge）一律不碰</b>：那是"启动时装哪个
     * 平台"的开关，写错了会让玩家的整合包直接起不来。我们只在启动时以 javaagent 形式
     * 挂上去，不需要、也不应该改它。
     *
     * <p>两个文件都先备份成 {@code *.fusionml-backup}，卸载时还原。
     */
    private static String markInPcl(Path gameDir, String hostId, boolean dryRun) {
        Path pclDir = gameDir.resolve("PCL");
        if (!Files.isDirectory(pclDir)) {
            // 不是 PCL 实例：什么都不做，也不报错（别的启动器不需要这个）。
            return "";
        }
        StringBuilder done = new StringBuilder();
        try {
            done.append(markPclFile(pclDir.resolve("config.v1.yml"), false, dryRun));
            done.append(markPclFile(pclDir.resolve("Setup.ini"), true, dryRun));
        } catch (IOException e) {
            return "PCL 标记写入失败（不影响功能）：" + e.getMessage();
        }
        if (done.length() == 0) {
            return "";
        }
        return "已在 PCL 实例概览里标出加载器："
                + done.toString().replaceAll("；$", "");
    }

    /**
     * 改一个 PCL 配置文件的 Info 与 Logo。
     *
     * @param colonSpace true 表示写 {@code "Info: xxx"}（config.v1.yml 风格），
     *                   false 表示写 {@code "Info:xxx"}（Setup.ini 风格，无空格）
     */
    private static String markPclFile(Path file, boolean colonSpace, boolean dryRun)
            throws IOException {
        if (!Files.isRegularFile(file)) {
            return "";
        }
        String sep = colonSpace ? " " : "";
        List<String> lines = new ArrayList<>(
                Files.readAllLines(file, StandardCharsets.UTF_8));
        boolean changed = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("Info:")) {
                String cur = line.substring(5).trim();
                if (!cur.contains("FusionML")) {
                    // 保留原有文字（可能是 "正式版 1.21.1, NeoForge 21.1.249"），
                    // 只往后追加。注意冒号后**只加一个空格**：原来写成 "Info:" + ": "
                    // 会得到 "Info:: ..." 这种双冒号，PCL 会解析不出来。
                    String merged = cur.replace("''", "").replace("\"\"", "").trim();
                    merged = merged.isEmpty() ? "FusionML" : merged + ", FusionML";
                    lines.set(i, "Info:" + sep + merged);
                    changed = true;
                }
            } else if (line.startsWith("Logo:")) {
                // 只在它还是"原版"图标时替换。玩家或别的加载器已经标过的不要抢。
                //
                // 图标必须用 PCL **内置存在**的那几个（pack:// 是内嵌资源，外部加不进去）。
                // 实测本机 PCL 里出现过的是：Grass（原版）/ Fabric / NeoForge / Anvil /
                // RedstoneBlock。这里用 Anvil —— 它不属于任何单一下载器，
                // 拿来表示"另有一个加载器参与"最不容易引起误解；
                // 真正说明身份的是 Info 里那句 ", FusionML"。
                if (line.contains("Blocks/Grass.png")) {
                    lines.set(i, "Logo:" + sep
                            + "pack://application:,,,/Plain Craft Launcher 2;component"
                            + "/Images/Blocks/Anvil.png");
                    changed = true;
                }            }
        }
        if (!changed) {
            return "";
        }
        if (!dryRun) {
            Path backup = file.resolveSibling(file.getFileName() + ".fusionml-backup");
            if (!Files.isRegularFile(backup)) {
                Files.copy(file, backup);
            }
            Files.write(file, lines, StandardCharsets.UTF_8);
        }
        return file.getFileName() + "；";
    }

    private static String registerProfile(Path gameDir, String versionId, String hostVersion,
                                          Path loaderJar) throws IOException {
        StringBuilder note = new StringBuilder();
        Path versionsDir = gameDir.resolve("versions").resolve(versionId);
        Files.createDirectories(versionsDir);
        Map<String, Object> version = new LinkedHashMap<>();
        version.put("id", versionId);
        version.put("time", java.time.Instant.now().toString());
        version.put("releaseTime", java.time.Instant.now().toString());
        version.put("type", "release");
        if (!hostVersion.isBlank()) {
            version.put("inheritsFrom", hostVersion);
        }
        // mainClass / libraries 一律**不写**：这两个必须从父版本继承。
        // 写了就等于用我们自己的 main 顶掉平台启动器（KnotClient / BootstrapLauncher），
        // 后果是模组不加载、平台参数（含早期窗口）全部丢失。
        //
        // 我们只**追加**需要的东西：
        //   · jvm 参数：把加载器作为 javaagent 挂上去 + 保留早期窗口配置；
        //   · libraries：暂无（加载器本体走 -javaagent 的绝对路径）。
        Map<String, Object> arguments = new LinkedHashMap<>();
        List<String> jvm = new ArrayList<>();
        // ${library_directory} 是启动器提供的变量，但我们的加载器不在 libraries 里，
        // 所以直接写相对 game_directory 的路径。用 ${game_directory} 拼是启动器支持的写法。
        jvm.add("-javaagent:${game_directory}/" + gameDir.relativize(loaderJar)
                .toString().replace('\\', '/'));
        jvm.add("-Dfusion.gameDir=${game_directory}");
        jvm.add("-Dfusion.version=" + versionId);
        // 早期窗口的配置由安装器写在 config/ 下，这里的属性确保客户端一侧读到它。
        jvm.add("-Dfusion.client=true");
        arguments.put("jvm", jvm);
        version.put("arguments", arguments);
        version.put("_fusionLoaderJar", gameDir.relativize(loaderJar).toString());
        Path versionJson = versionsDir.resolve(versionId + ".json");
        Files.writeString(versionJson, Json.write(version), StandardCharsets.UTF_8);
        note.append("已写出版本定义：versions/").append(versionId).append('/').append(versionId)
                .append(".json");
        if (!hostVersion.isBlank()) {
            note.append("（继承 ").append(hostVersion)
                    .append("，平台启动器与依赖库都从它继承）");
        } else {
            note.append("；⚠ 没能识别玩家实例的版本 id，inheritsFrom 为空 —— "
                    + "启动器可能不认这个版本，建议显式指定 --inherit");
        }

        Path profiles = gameDir.resolve("launcher_profiles.json");
        Map<String, Object> root;
        if (Files.isReadable(profiles)) {
            try {
                root = Json.parseObject(Files.readAllBytes(profiles));
            } catch (RuntimeException e) {
                // 原文件读不动就不要动它，避免把人家的启动器配置写坏。
                note.append("；launcher_profiles.json 解析失败，已跳过注册（未改动原文件）");
                return note.toString();
            }
        } else {
            root = new LinkedHashMap<>();
            root.put("profiles", new LinkedHashMap<String, Object>());
            root.put("selectedProfile", versionId);
            root.put("clientToken", java.util.UUID.randomUUID().toString().replace("-", ""));
            note.append("；已新建 launcher_profiles.json");
        }
        Map<String, Object> profilesMap = Json.obj(root.get("profiles"));
        if (profilesMap.isEmpty()) {
            profilesMap = new LinkedHashMap<>();
        } else {
            profilesMap = new LinkedHashMap<>(profilesMap);
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", "FusionML " + versionId);
        entry.put("type", "custom");
        entry.put("created", java.time.Instant.now().toString());
        entry.put("lastUsed", java.time.Instant.now().toString());
        entry.put("icon", "Furnace");
        entry.put("lastVersionId", versionId);
        entry.put("gameDir", gameDir.toAbsolutePath().toString());
        profilesMap.put(versionId, entry);
        root.put("profiles", profilesMap);
        Files.writeString(profiles, Json.write(root), StandardCharsets.UTF_8);
        note.append("；已合并写入启动器配置");
        return note.toString();
    }

    private static int copyMods(Path from, Path to, boolean dryRun,
                                java.util.function.Consumer<String> out) throws IOException {
        if (!Files.isDirectory(from)) {
            out.accept("指定的模组来源不存在，已跳过：" + from);
            return 0;
        }
        Files.createDirectories(to);
        int n = 0;
        try (var s = Files.list(from)) {
            for (Path p : s.toList()) {
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!Files.isRegularFile(p) || (!name.endsWith(".jar") && !name.endsWith(".zip"))) {
                    continue;
                }
                Path dest = to.resolve(p.getFileName());
                if (Files.exists(dest)) {
                    out.accept("已存在，跳过：" + p.getFileName());
                    continue;
                }
                if (!dryRun) {
                    Files.copy(p, dest, StandardCopyOption.COPY_ATTRIBUTES);
                }
                n++;
            }
        }
        return n;
    }

    private static boolean parse(String[] args, Options o) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--target", "--game-dir" -> o.gameDir = Path.of(need(args, ++i, "目录"));
                case "--mods-from" -> o.modsFrom = Path.of(need(args, ++i, "目录"));
                case "--inherit" -> o.inheritVersion = need(args, ++i, "版本号");
                case "--loader-version" -> o.loaderVersion = need(args, ++i, "版本号");
                case "--no-profile" -> o.noProfile = true;
                case "--uninstall" -> o.uninstall = true;
                case "--dry-run" -> o.dryRun = true;
                case "--gui" -> o.gui = true;

                case "--fetch" -> o.fetchVersion = need(args, ++i, "版本号");
                case "--fetch-side" -> o.fetchSide = need(args, ++i, "server|client");
                case "--fetch-dir" -> o.fetchDir = Path.of(need(args, ++i, "目录"));
                case "--fetch-only" -> o.fetchOnly = true;
                case "--server" -> o.fetchSide = "server";
                case "--client" -> o.fetchSide = "client";
                case "--list-versions" -> o.listVersions = true;
                case "--snapshots" -> o.includeSnapshots = true;
                case "--list-limit" -> o.listLimit = Integer.parseInt(need(args, ++i, "条数"));
                case "--install-server" -> {
                    // Fabric 风格的一步到位：拉服务端 + 装好 + 写启动脚本。
                    o.fetchSide = "server";
                    o.serverInstall = true;
                }
                case "--install-full" -> {
                    // 一步装出**完整可启动的客户端实例**：连 Minecraft 本体、
                    // libraries、assets 一起下。装完那个目录是自包含的，直接能启动。
                    // 这也是"不需要先自己装一遍 Minecraft"的那条路（对照 Fabric 安装器）。
                    o.installFull = true;
                    if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        o.fetchVersion = args[++i];
                    }
                }
                case "--no-assets" -> o.noAssets = true;
                case "--shot-gui" -> o.shotGui = Path.of(need(args, ++i, "输出图片"));
                case "--verbose", "-v" -> o.verbose = true;
                case "--help", "-h" -> {
                    printHelp();
                    return false;
                }
                default -> throw new IllegalArgumentException("未知参数 " + args[i]);
            }
        }
        return true;
    }

    private static String need(String[] args, int i, String what) {
        if (i >= args.length) {
            throw new IllegalArgumentException("缺少取值：" + what);
        }
        return args[i];
    }

    private static void printHelp() {
        System.out.println("""
                FusionML 安装器

               用法：
                 java -jar fusion-installer.jar [选项]        （无参数且有界面时会打开图形安装器）

               选项：
                 --target <目录>        游戏目录，默认自动探测 .minecraft
                 --mods-from <目录>     把该目录里的 .jar/.zip 拷进 mods/
                 --inherit <版本号>     版本定义继承哪个原版版本（例如 1.21）
                 --loader-version <v>   加载器版本号，用于生成版本 id，默认 1.0.0
                 --no-profile          不写 launcher_profiles.json 与版本定义
                 --dry-run             预演：只打印将要做什么，不写任何文件
                 --uninstall           卸载（按安装清单精确还原）
                 --gui                 强制打开图形安装器
                 --verbose, -v         出错时打印详细堆栈
                 --help, -h            显示本帮助

               一键安装（自动拉取，不需要先用官方启动器装一遍）：
                 --install-server      拉服务端 + 装好 + 写启动脚本与 eula.txt（一步到位）
                 --fetch <版本号>      拉取指定版本；写 latest 表示最新正式版
                 --fetch-side <s>      server（默认）或 client
                 --fetch-dir <目录>    拉到哪个目录，默认与 --target 相同
                 --fetch-only          只拉取，不接着安装
                 --list-versions       列出官方清单里可选的版本
                 --snapshots           列版本时也包含快照
                 --list-limit <条数>   列版本的条数上限，默认 30（0 表示全部）

               示例：
                 java -jar fusion-installer.jar --install-server --target ./server --fetch 26.3
                 java -jar fusion-installer.jar --fetch latest --fetch-side client --fetch-only
                 java -jar fusion-installer.jar --list-versions --list-limit 10
               安装器只做“新增”：不删除、不覆盖任何已有文件；
               卸载只删除自己创建过的文件与因此变空的目录，mods/ 与 saves/ 等玩家数据永不触碰。
                """);
    }
}
