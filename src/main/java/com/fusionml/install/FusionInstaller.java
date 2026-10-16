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
            if (o.dryRun) {
                out.accept("将注册启动器版本：" + versionId);
            } else {
                String note = registerProfile(gameDir, versionId, o.inheritVersion, loaderTarget);
                out.accept(note);
                manifest.add("version=" + versionId);
                if (!o.inheritVersion.isBlank()) {
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

    private static String registerProfile(Path gameDir, String versionId, String inherit,
                                          Path loaderJar) throws IOException {
        StringBuilder note = new StringBuilder();
        Path versionsDir = gameDir.resolve("versions").resolve(versionId);
        Files.createDirectories(versionsDir);
        Map<String, Object> version = new LinkedHashMap<>();
        version.put("id", versionId);
        version.put("time", java.time.Instant.now().toString());
        version.put("releaseTime", java.time.Instant.now().toString());
        version.put("type", "release");
        if (!inherit.isBlank()) {
            version.put("inheritsFrom", inherit);
        }
        version.put("mainClass", "com.fusionml.FusionLauncher");
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("game", List.of("--gameDir", "${game_directory}", "--mods",
                "${game_directory}/mods"));
        version.put("arguments", arguments);
        version.put("libraries", List.of());
        version.put("_fusionLoaderJar", gameDir.relativize(loaderJar).toString());
        Path versionJson = versionsDir.resolve(versionId + ".json");
        Files.writeString(versionJson, Json.write(version), StandardCharsets.UTF_8);
        note.append("已写出版本定义：versions/").append(versionId).append('/').append(versionId)
                .append(".json");
        if (!inherit.isBlank()) {
            note.append("（继承 ").append(inherit).append("）");
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
