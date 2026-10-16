package com.fusionml.mc;

import com.fusionml.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 命令行拉取 Minecraft <b>服务端</b> jar。
 *
 * <h2>为什么要有这个类</h2>
 *
 * <p>FusionML 原来的前提是"用户已经用官方启动器把版本装好了"。这在客户端整合包里通常成立，
 * 但在服务端上根本不成立 —— 一台新买的 VPS 上除了一个 JDK 什么都没有。
 * Fabric 官方安装器的做法是<b>自己把服务端 jar 拉下来</b>（fabricmc.net/use/server 那个页面
 * 背后就是这件事），这里补上同样的能力。
 *
 * <h2>两级解析</h2>
 *
 * <p>Mojang 的公开数据分两级：
 * <ol>
 *   <li>{@code version_manifest_v2.json} —— 所有版本的列表，每个版本带一个元数据地址；</li>
 *   <li>该版本自己的 json —— 里面有 {@code downloads.server.url} 与 {@code downloads.server.sha1}。</li>
 * </ol>
 *
 * <h2>但还有一条更短的路，而且它才是本机的现实</h2>
 *
 * <p>实测本机的 {@code 26.3-Fabric 0.19.5.json} 里 <b>本来就写着 server 节点</b>：
 * <pre>
 * downloads.server = {"sha1":"33680f5f...","size":62294556,
 *                     "url":"https://piston-data.mojang.com/v1/objects/33680f5f.../server.jar"}
 * </pre>
 * 所以本地已有的版本定义本身就是一份"权威且离线可用"的下载清单。
 * 本类因此实现<b>两条来源并优先本地</b>：
 * <ol>
 *   <li>{@code local}：从游戏目录里已有版本的 json 取 —— 不发任何清单请求，快且离线可用；</li>
 *   <li>{@code manifest}：走官方清单两级解析 —— 版本 json 不存在、或里面没有 server 节点时用。</li>
 * </ol>
 * 两条路都拿到 URL + SHA1，交给 {@link McDownloader} 下载并校验，
 * 所以"从哪知道的地址"不影响"下下来的东西对不对"。
 *
 * <h2>网络不可用时</h2>
 *
 * <p><b>明确失败，绝不假装成功。</b>一个"装完了但少文件"的目录比一次清晰的失败难查得多：
 * 用户会在启动时看到一堆与真正原因无关的报错。
 */
public final class ServerFetch {

    /** Mojang 官方版本清单（两级解析的第一级）。 */
    public static final String MANIFEST_URL =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    /** 服务端 jar 在服务端目录里的习惯文件名（官方启动器/Fabric 安装器都这么叫）。 */
    public static final String SERVER_JAR_NAME = "server.jar";

    private ServerFetch() {
    }

    public interface Log {
        void line(String s);
    }

    /** 地址是从哪来的 —— 报告里要如实写出来，否则事后没法解释"它凭什么知道这个 URL"。 */
    public enum Source {
        /** 从游戏目录里已有的版本 json 读到的。 */
        LOCAL_VERSION_JSON("本地版本定义"),

        OFFICIAL_MANIFEST("官方版本清单");

        private final String text;

        Source(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    public record ServerJar(Source source, String versionId, String url, String sha1, long size,
                            String javaComponent, int javaMajor) {
        public String sizeText() {
            if (size <= 0) {
                return "大小未知";
            }
            return String.format(java.util.Locale.ROOT, "%.1f MB", size / 1024.0 / 1024.0);
        }
    }

    public record Result(ServerJar jar, Path file, boolean downloaded, long elapsedMs) {
    }

    /**
     * 找到某个版本的服务端 jar 下载信息。
     *
     * <p>顺序刻意是<b>先本地后清单</b>：本地版本定义已经落盘，读它是同步的、不依赖网络，
     * 而且它写的就是官方给这个版本的原话。只有本地给不出答案时才值得发网络请求。
     *
     * @param instance  游戏目录；为 {@code null} 时直接走官方清单
     * @param versionId 版本 id（例如 {@code 26.3-Fabric 0.19.5}）
     * @param officialId 官方清单里对应的版本号；为空时从 versionId 前缀推断
     *                   （{@code "26.3-Fabric 0.19.5"} → {@code "26.3"}）
     */
    public static ServerJar resolve(McDownloader dl, McInstance instance, String versionId,
                                    String officialId, Log log) throws IOException {
        ServerJar local = fromLocalVersionJson(instance, versionId, log);
        if (local != null) {
            return local;
        }
        String id = officialId == null || officialId.isBlank()
                ? mcVersionFromId(versionId) : officialId;
        log.line("本地版本定义里没有服务端下载信息，改走官方清单（版本 " + id + "）");
        return fromManifest(dl, id, log);
    }

    /**
     * 来源 A：本地已有的版本 json。
     *
     * <p>这里读的是**版本目录里那份 json**，而不是客户端 jar 内部的 {@code version.json}：
     * 后者是被打进 jar 的副本，而前者才是启动器实际用来解析版本的那一份。
     */
    static ServerJar fromLocalVersionJson(McInstance instance, String versionId, Log log)
            throws IOException {
        if (instance == null || versionId == null || versionId.isBlank()) {
            return null;
        }
        Path json = instance.versionJson(versionId);
        if (!Files.isRegularFile(json)) {
            return null;
        }
        McVersion v;
        try {
            v = McVersion.parse(Files.readAllBytes(json));
        } catch (RuntimeException e) {
            log.line("本地版本定义解析失败，将改走官方清单：" + e);
            return null;
        }
        if (!v.hasServerJar()) {
            log.line("本地版本定义 " + versionId + " 里没有 downloads.server（该版本可能不提供独立服务端）");
            return null;
        }
        log.line("地址来源：本地版本定义 " + json);
        return new ServerJar(Source.LOCAL_VERSION_JSON, v.id, v.serverUrl, v.serverSha1,
                v.serverSize, v.javaComponent, v.javaMajor);
    }

    /**
     * 来源 B：官方清单两级解析。
     *
     * <p>第一级找版本条目，第二级取 {@code downloads.server}。
     */
    static ServerJar fromManifest(McDownloader dl, String versionId, Log log) throws IOException {
        log.line("查询官方版本清单：" + MANIFEST_URL);
        Map<String, Object> root = parse(dl.fetch(MANIFEST_URL), "版本清单");

        String id = versionId;
        Map<String, Object> latest = Json.obj(root.get("latest"));
        if ("latest".equalsIgnoreCase(id) || "latest-release".equalsIgnoreCase(id)) {
            id = Json.str(latest, "release", "");
            log.line("latest → 最新正式版 " + id);
        } else if ("latest-snapshot".equalsIgnoreCase(id)) {
            id = Json.str(latest, "snapshot", "");
            log.line("latest-snapshot → 最新快照 " + id);
        }
        if (id == null || id.isBlank()) {
            throw new IOException("官方清单里没有可用的版本号（latest 节点缺失）");
        }

        String url = null;
        for (Object o : Json.arr(root.get("versions"))) {
            Map<String, Object> vm = Json.obj(o);
            if (id.equals(Json.str(vm, "id", ""))) {
                url = Json.str(vm, "url", "");
                break;
            }
        }
        if (url == null || url.isBlank()) {
            throw new IOException("官方清单里找不到版本 " + id
                    + "。可用 --list-mc-versions 看看清单里都有什么。");
        }
        log.line("清单命中版本 " + id);

        McVersion v = McVersion.parse(dl.fetch(url));
        if (!v.hasServerJar()) {
            throw new IOException("版本 " + id + " 没有服务端 jar 可下载"
                    + "（有些很老的版本确实不提供独立服务端）。");
        }
        log.line("地址来源：官方清单 " + url);
        return new ServerJar(Source.OFFICIAL_MANIFEST, v.id, v.serverUrl, v.serverSha1,
                v.serverSize, v.javaComponent, v.javaMajor);
    }

    public static Result fetch(McDownloader dl, McInstance instance, String versionId,
                               String officialId, Path targetDir, Log log) throws IOException {
        ServerJar jar = resolve(dl, instance, versionId, officialId, log);
        Files.createDirectories(targetDir);
        Path target = targetDir.resolve(SERVER_JAR_NAME);
        log.line("下载服务端 jar：" + jar.sizeText() + " → " + target);
        log.line("SHA1 " + jar.sha1());

        long t0 = System.nanoTime();
        boolean downloaded;
        try {
            final long totalExpected = jar.size();
            downloaded = dl.ensure(target, jar.url(), jar.sha1(),
                    new ProgressPrinter(log, totalExpected));
        } catch (IOException e) {
            // 失败信息必须包含"下一步能做什么"。只说"下载失败"等于把排查工作推给用户。
            throw new IOException("下载服务端 jar 失败：" + e.getMessage()
                    + "\n  可能原因：没有网络、被防火墙/代理拦截、或该版本已被官方下架。"
                    + "\n  可以手动把 jar 放到 " + target + " 再重跑 —— 内容一致时会自动跳过下载。", e);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        long actual = Files.size(target);
        log.line((downloaded ? "下载完成" : "本地已有且 SHA1 校验通过，直接复用")
                + "（" + ms + " ms，" + actual + " 字节）");
        if (jar.size() > 0 && actual != jar.size()) {

            // 不当成失败（SHA1 才是权威），但必须说出来，否则以后对不上账。
            log.line("注意：实际大小 " + actual + " 与清单声明的 " + jar.size()
                    + " 不一致（SHA1 已通过，以 SHA1 为准）");
        }
        return new Result(jar, target, downloaded, ms);
    }

    /**
     * 按每 10% 报一次的进度打印器。
     *
     * <p>用一个有状态的类而不是 lambda：{@code McDownloader} 的回调是被反复调用的，
     * 想去重就得记住上一次报到哪个档位，而 lambda 捕获的局部变量改不了。
     * 顺便对"总长度未知"（分块传输）也给出字节级反馈，不至于全程一片死寂。
     */
    private static final class ProgressPrinter implements McDownloader.Progress {
        private final Log log;
        private final long expected;
        private int lastDecile = -1;

        ProgressPrinter(Log log, long expected) {
            this.log = log;
            this.expected = expected;
        }

        @Override
        public void update(String what, long done, long total) {
            long span = total > 0 ? total : expected;
            if (span <= 0) {

                if (done / (8L << 20) != (done - 1) / (8L << 20) && done > 0) {
                    log.line("  已下载 " + (done >> 20) + " MB（总长度未知）");
                }
                return;
            }
            int decile = (int) Math.min(10, done * 10 / span);
            if (decile != lastDecile) {
                lastDecile = decile;
                log.line("  进度 " + (decile * 10) + "%（" + (done >> 20) + "/"
                        + (span >> 20) + " MB）");
            }
        }
    }

    // 4) 启动脚本（对照 Fabric：不自己写 main，而是"包着官方 server.jar 启动"）

    public record Launchers(List<Path> written, List<String> notes) {
    }

    /**
     * 生成服务端启动脚本 —— <b>Fabric 形态</b>：仍然交给官方 {@code server.jar} 启动，
     * 我们的加载器以 javaagent 挂上去。
     *
     * <h2>为什么不是"自己写一个 main"</h2>
     *
     * <p>26.3 的官方 {@code server.jar} 是一个 <b>bundler</b>：
     * <pre>
     * META-INF/MANIFEST.MF : Main-Class: net.minecraft.bundler.Main, Bundler-Format: 1.0
     * META-INF/main-class  : net.minecraft.server.Main
     * META-INF/versions.list / libraries.list : 完整依赖清单
     * </pre>
     * 它自己会把 versions/libraries 解包出来、拼好类路径，再调真实主类。
     * 我们用 {@code --mc-launch} 自己搭一套类路径去启动，等于<b>绕开官方启动器并重新实现它</b>：
     * 依赖清单的解析规则、原生库的摆放、参数传递，任何一处与官方不一致都会变成难查的启动失败。
     * 既然官方已经把这套东西做成一个自洽的 jar，正确做法是<b>包着它启动</b>，而不是替代它。
     *
     * <h2>agent 是怎么传进去的</h2>
     *
     * <p>用环境变量 {@code JAVA_TOOL_OPTIONS}：JVM 会在启动时自动读取它并应用其中的选项，
     * 所以不需要改 {@code server.jar}、也不需要用户改命令行。
     * 实测（本机 26.3）该变量确实生效，且 {@code premain} 收到 {@code args=[server]}。
     *
     * <p>另外实测确认：26.3 的 bundler <b>不 fork 子 JVM</b>（解包后在原 JVM 内换 classloader
     * 调 {@code net.minecraft.server.Main}），所以这个变量不依赖"子进程继承环境"这一环，更稳。
     *
     * <h2>两个只有实跑才会暴露的坑</h2>
     *
     * <p><b>Java 版本。</b>脚本里写 {@code JAVA=java} 是错的：PATH 上那个 java 未必满足游戏要求。
     * 本机实测 PATH 上是 Java 21，而 26.3 的 bundler 是 class file 69（Java 25），
     * 直接以 {@code UnsupportedClassVersionError} 收场 —— 报错说的是
     * "bundler/Main 是更新的 Java 编译的"，完全没提脚本选错了 java。
     * 所以这里把<b>解析好的 java.exe 绝对路径</b>写进脚本，并在注释里说明怎么改。
     *
     * <p><b>工作目录。</b>只给 {@code -jar server.jar} 时，加载器无从知道服务端目录在哪，
     * 会退化成用当前工作目录 —— 实测表现为"模组目录指向了调用者所在目录"，
     * 于是服务端明明有 mods/ 却一个模组都不加载。所以脚本里显式 {@code cd /d "%~dp0"}。
     *
     * @param serverDir  服务端 jar 所在目录（脚本放在这里）
     * @param loaderJar  加载器 jar 的绝对路径；为 {@code null} 时脚本里留占位注释
     * @param jarName    服务端 jar 的文件名（默认 {@code server.jar}）
     * @param maxMemoryMb 最大堆内存，单位 <b>MB</b>（写进脚本时会转成 {@code -Xmx<n>m}）
     * @param javaExe    写进脚本的 Java 可执行文件；为 {@code null} 时退回 {@code java}
     */
    public static Launchers writeServerLaunchers(Path serverDir, Path loaderJar, String jarName,
                                                 int maxMemoryMb, Path javaExe) throws IOException {
        Files.createDirectories(serverDir);
        String jar = jarName == null || jarName.isBlank() ? SERVER_JAR_NAME : jarName;

        // 报错点离原因极远。这里直接把明显不合理的值顶到 512 MB，并在脚本注释里写明。
        int memMb = maxMemoryMb <= 0 ? 4096 : Math.max(512, maxMemoryMb);
        // 脚本里刻意用 %~dp0（脚本自身所在目录）而不是写死的绝对路径：

        // 绝不写成 `%HERE%lib\...` —— 后者有个隐蔽的坑：cmd 解析变量名时允许字母/数字/下划线，

        // 这种"静默失效"比直接报错难查得多，所以干脆不用中间变量拼接。
        List<Path> written = new ArrayList<>(2);
        List<String> notes = new ArrayList<>(2);

        // 一律用字符串拼接而不是 String.format / 占位符替换：

        StringBuilder sb = new StringBuilder(1024);
        String nl = System.lineSeparator();
        sb.append("@echo off").append(nl);
        sb.append("rem FusionML 服务端启动脚本（对照 Fabric 安装器的做法生成）。").append(nl);
        sb.append("rem 关键点：仍然由官方 server.jar（它是一个 bundler）负责解包与启动，").append(nl);
        sb.append("rem 我们的加载器只是以 javaagent 的形式挂上去 —— 不替代官方启动器。").append(nl);
        sb.append("setlocal").append(nl);
        sb.append(nl);
        sb.append("rem 工作目录必须是脚本所在目录：加载器靠它推断游戏目录，").append(nl);
        sb.append("rem 否则会把调用者所在目录当成服务端目录，从而一个模组都不加载。").append(nl);
        sb.append("cd /d \"%~dp0\"").append(nl);
        sb.append(nl);
        sb.append("rem Java 路径。这里写入的是安装时解析出的、满足本版本要求的运行时；").append(nl);
        sb.append("rem 想改用别的 java，直接改这一行即可。").append(nl);
        if (javaExe != null) {
            sb.append("set \"JAVA=").append(javaExe.toAbsolutePath()).append("\"").append(nl);
        } else {
            sb.append("rem （未能自动定位到满足要求的 Java，退回 PATH 上的 java；").append(nl);
            sb.append("rem   若启动时报 UnsupportedClassVersionError，就是这一行需要改。）").append(nl);
            sb.append("set \"JAVA=java\"").append(nl);
        }
        sb.append(nl);
        sb.append("rem JAVA_TOOL_OPTIONS 会被 JVM 自动读取并应用，因此 agent 不必写进命令行。").append(nl);
        sb.append("rem \"=server\" 是传给 premain 的 agent 参数，加载器据此进入服务端模式。").append(nl);
        sb.append("rem").append(nl);
        sb.append("rem 路径上的引号是**必须的**，不是保险起见：实测路径含空格时不写引号，").append(nl);
        sb.append("rem JVM 会直接 \"Error: Could not create the Java Virtual Machine.\" 起不来，").append(nl);
        sb.append("rem 而那条报错完全不提 agent，极难定位。写法必须是 -javaagent:\"<路径>\"=server，").append(nl);
        sb.append("rem 即引号只包住路径、不包住 =server。").append(nl);
        sb.append("rem").append(nl);
        sb.append("rem 注意 \"%~dp0lib\\...\" 不能写成 \"%HERE%lib\\...\"：cmd 会把 HERELib 当变量名。")
                .append(nl);
        sb.append("set JAVA_TOOL_OPTIONS=-javaagent:\"%~dp0").append(SERVER_LOADER_REL).append("\"=server")
                .append(nl);
        sb.append(nl);
        sb.append("\"%JAVA%\" -Xmx").append(memMb).append("m -jar \"%~dp0")
                .append(jar).append("\" nogui").append(nl);
        sb.append("echo.").append(nl);
        sb.append("echo 服务端已退出（退出码 %ERRORLEVEL%）。").append(nl);
        sb.append("pause").append(nl);
        String cmd = sb.toString();

        StringBuilder shb = new StringBuilder(512);
        shb.append("#!/bin/sh").append("\n");
        shb.append("# FusionML 服务端启动脚本（由安装器生成）。").append("\n");
        shb.append("# 与 .cmd 同理：官方 server.jar 负责启动，加载器以 javaagent 挂上去。").append("\n");
        shb.append("HERE=\"$(cd \"$(dirname \"$0\")\" && pwd)\"").append("\n");
        shb.append("# 工作目录必须是脚本所在目录：加载器靠它推断游戏目录。").append("\n");
        shb.append("cd \"$HERE\" || exit 1").append("\n");
        // .sh 里**绝不能**写安装机上解析出的 Java 绝对路径：

        // 而那串东西在 Linux 上是无意义的文件名，脚本会以 "not found" 失败。

        shb.append("# 用 PATH 上的 java；要指定别的，运行前 export JAVA=/path/to/java 即可。").append("\n");
        shb.append("# （这里刻意不写死路径：安装机与运行可能是不同系统。）").append("\n");
        shb.append("JAVA=\"${JAVA:-java}\"").append("\n");
        shb.append("# 引号同样必须：目录名可能含空格。").append("\n");
        shb.append("# 必须用绝对路径 —— JAVA_TOOL_OPTIONS 里的相对路径按 JVM 工作目录解析。").append("\n");
        shb.append("JAVA_TOOL_OPTIONS=\"-javaagent:$HERE/").append(SERVER_LOADER_REL_SH).append("=server\"")
                .append("\n");
        shb.append("export JAVA_TOOL_OPTIONS").append("\n");
        shb.append("exec \"$JAVA\" -Xmx").append(memMb).append("m -jar \"$HERE/")
                .append(jar).append("\" nogui \"$@\"").append("\n");
        String sh = shb.toString();

        // .sh 不得含 Windows 路径：实测这条能抓到"安装机路径漏进 Linux 脚本"这类事故。
        if (sh.contains(":\\")) {
            throw new IllegalStateException("生成的 start-server.sh 里出现了 Windows 路径"
                    + "（安装机路径不应写进 .sh），拒绝写出坏脚本。内容如下：\n" + sh);
        }

        // 检查项都来自实际踩过的坑（见上面的注释）。
        checkGenerated(cmd, "start-server.cmd", new String[] {
                "%~dp0" + SERVER_LOADER_REL, "%~dp0" + jar,
                "-Xmx" + memMb + "m", "-javaagent:\"", "cd /d \"%~dp0\"",
        });
        checkGenerated(sh, "start-server.sh", new String[] {
                "/" + SERVER_LOADER_REL_SH, "/" + jar, "-Xmx" + memMb + "m", "cd \"$HERE\"",
        });

        Path cmdFile = serverDir.resolve("start-server.cmd");
        Files.writeString(cmdFile, cmd, java.nio.charset.StandardCharsets.UTF_8);
        written.add(cmdFile);
        Path shFile = serverDir.resolve("start-server.sh");
        Files.writeString(shFile, sh, java.nio.charset.StandardCharsets.UTF_8);
        written.add(shFile);

        if (loaderJar == null) {
            notes.add("没有定位到加载器 jar：脚本里的 agent 路径是默认值 lib/fusionml/"
                    + "fusion-ml.jar，请确认它确实存在，否则服务端会以普通原版方式启动"
                    + "（不会有任何 FusionML 日志 —— 这是最容易误判为“加载器没生效”的情形）。");
        } else {
            notes.add("加载器 jar：" + loaderJar.toAbsolutePath());
        }
        notes.add("脚本用 JAVA_TOOL_OPTIONS 传 -javaagent（带 =server 参数），"
                + "官方 server.jar 仍是真正的启动入口。");
        return new Launchers(written, notes);
    }

    /**
     * 旧签名的兼容重载：自动解析一个满足要求的 Java 写进脚本。
     *
     * <p>保留它是为了"调用方不必先自己找 Java"这一常见用法；但<b>能传就传</b> ——
     * 调用方通常知道目标版本要求哪一代 Java，直接传进来比这里再猜一次准。
     */
    public static Launchers writeServerLaunchers(Path serverDir, Path loaderJar, String jarName,
                                                 int maxMemoryMb) throws IOException {
        return writeServerLaunchers(serverDir, loaderJar, jarName, maxMemoryMb,
                resolveJavaForCurrent());
    }

    /**
     * 尽力找一个能满足当前运行环境的 Java。
     *
     * <p>拿不到版本要求时（例如调用方没解析过版本 json），退而求其次：
     * 在当前 JVM 与常见安装目录里挑一个版本最高的。这个猜测至少比写死 PATH 上的 java 强 ——
     * PATH 上往往是更老的版本，而"太老"正是会直接崩掉的那一侧。
     */
    private static Path resolveJavaForCurrent() {
        try {
            com.fusionml.launch.JavaFinder.Found f =
                    com.fusionml.launch.JavaFinder.find(com.fusionml.launch.JavaFinder.currentMajor());
            if (f != null) {
                return f.javaExe();
            }
        } catch (Throwable ignored) {
            // 探测失败就退回 null，脚本里会写 java 并附上说明注释。
        }
        return null;
    }

    /** 加载器 jar 在服务端目录里的相对路径（Windows 形式）。 */
    private static final String SERVER_LOADER_REL = "lib\\fusionml\\fusion-ml.jar";

    private static final String SERVER_LOADER_REL_SH = "lib/fusionml/fusion-ml.jar";

    /**
     * 生成后自检：脚本里必须真的含有这些片段。
     *
     * <p>存在的理由是"模板占位符静默不替换"这一类 bug —— 它最坏的后果不是报错，
     * 而是写出一个<b>能跑但没挂上加载器</b>的脚本：用户看到服务端正常启动，
     * 以为一切正常，直到发现所有模组都没生效。宁可在生成阶段直接抛异常。
     */
    private static void checkGenerated(String content, String name, String[] required) {
        for (String r : required) {
            if (!content.contains(r)) {
                throw new IllegalStateException("生成的 " + name + " 缺少必需片段 " + r
                        + "（模板占位符可能没有被正确替换）。内容如下：\n" + content);
            }
        }
        if (content.contains("%LOADER%") || content.contains("%JAR%") || content.contains("%MEM%")
                || content.contains("MEMm")) {
            throw new IllegalStateException("生成的 " + name
                    + " 里残留了未替换的占位符，拒绝写出坏脚本。内容如下：\n" + content);
        }
    }

    /**
     * 列出官方清单里的版本。
     *
     * <p>刻意**不抛异常**：这是一个"我想看看有什么可选"的查询命令，
     * 拿不到网络时把原因作为一行结果返回，比让整条命令以堆栈收场有用得多。
     */
    public static List<String> listVersions(McDownloader dl, int limit, boolean snapshots) {
        List<String> out = new ArrayList<>();
        try {
            Map<String, Object> root = parse(dl.fetch(MANIFEST_URL), "版本清单");
            out.add("最新正式版：" + Json.str(Json.obj(root.get("latest")), "release", "?")
                    + "    最新快照：" + Json.str(Json.obj(root.get("latest")), "snapshot", "?"));
            out.add("");
            for (Object o : Json.arr(root.get("versions"))) {
                Map<String, Object> vm = Json.obj(o);
                String type = Json.str(vm, "type", "");
                if (!snapshots && !"release".equals(type)) {
                    continue;
                }
                out.add("  " + Json.str(vm, "id", "?") + "  [" + type + "]  "
                        + Json.str(vm, "releaseTime", ""));
                if (limit > 0 && out.size() - 2 >= limit) {
                    break;
                }
            }
        } catch (Throwable t) {

            out.add("读取版本清单失败：" + t);
            out.add("（这不影响在已有游戏目录上安装：加上 --use-local-version-json 之类的本地来源即可）");
        }
        return out;
    }

    /**
     * 从启动器版本名里截出官方版本号。
     *
     * <p>本机的版本目录名形如 {@code 26.3-Fabric 0.19.5}，官方清单里叫 {@code 26.3}。
     * 截取规则：取第一个 {@code -} 之前的部分。取不到就原样返回，
     * 让清单那边给出"找不到这个版本"的可读错误 —— 不猜。
     */
    public static String mcVersionFromId(String versionId) {
        if (versionId == null) {
            return "";
        }
        String id = versionId.trim();
        int dash = id.indexOf('-');
        if (dash > 0 && !id.startsWith("latest")) {
            return id.substring(0, dash).trim();
        }
        return id;
    }

    private static Map<String, Object> parse(byte[] data, String what) throws IOException {
        try {
            return Json.parseObject(data);
        } catch (RuntimeException e) {
            throw new IOException(what + "解析失败（返回的可能不是 JSON，例如代理的错误页）：" + e, e);
        }
    }
}
