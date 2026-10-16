package com.fusionml.install;

import com.fusionml.util.FastZip;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 安装器的<b>双重验证</b>：先验目录，再验版本。
 *
 * <h2>为什么要两道</h2>
 *
 * <p>安装器要往一个目录里写文件，而"用户选的目录"和"用户以为的目录"经常不是一回事 ——
 * 差一级（选了 {@code .minecraft} 而不是 {@code .minecraft/versions/xxx}）、
 * 选了个空目录、或者选了个别的软件的目录。这些如果只靠"写下去试试"，失败会很晚、
 * 报错也很难懂（下一个阶段才炸）。
 *
 * <p>所以拆成两道，各自给出<b>看得见的结论</b>：
 * <ol>
 *   <li><b>目录校验</b>：这到底是不是一个 Minecraft 游戏目录？依据是
 *       {@code versions/} 与 {@code mods/} 的存在，而不是"目录存在就算"。</li>
 *   <li><b>版本校验</b>：在这份游戏里，用户挑的那个版本<b>能不能被本加载器接管</b>？
 *       依据是"版本目录是否完整"+"命名空间是否不混淆"+"Java 是否够用"。</li>
 * </ol>
 *
 * <p>第二道是关键：即使目录对了，某个具体版本也可能<b>装不了</b>。例如 1.21.1/1.20.1
 * 的游戏包是混淆的，而合并是按内部名搬运的 —— 这种版本现在还不能用，
 * 必须在这里说清楚，而不是装完启动才报一堆看不懂的错。
 */
public final class InstallVerify {

    private InstallVerify() {
    }

    /** 一道验证的结论。 */
    public enum Level {
        /** 通过。 */
        OK,
        /** 通过但有需要注意的地方（例如可以用，但某个功能不可用）。 */
        WARN,
        /** 不通过：不要继续。 */
        FAIL;

        public String symbol() {
            return switch (this) {
                case OK -> "✔";
                case WARN -> "!";
                case FAIL -> "✘";
            };
        }

        public String label() {
            return switch (this) {
                case OK -> "通过";
                case WARN -> "注意";
                case FAIL -> "不通过";
            };
        }
    }

    /** 一条检查项。 */
    public record Item(String name, Level level, String detail) {
    }

    /** 一道验证的完整结果。 */
    public record Result(String title, Level overall, java.util.List<Item> items) {
        /** 能否继续安装。 */
        public boolean canContinue() {
            return overall != Level.FAIL;
        }

        public String render() {
            StringBuilder sb = new StringBuilder();
            sb.append(title).append("：").append(overall.symbol())
                    .append(' ').append(overall.label()).append(System.lineSeparator());
            for (Item i : items) {
                sb.append("   ").append(i.level().symbol()).append(' ')
                        .append(i.name()).append(" —— ").append(i.detail())
                        .append(System.lineSeparator());
            }
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------ 第一道：目录

    /**
     * 第一道验证：这到底是不是一个可用的 Minecraft 游戏目录。
     *
     * <p>判据有意分档，而不是"有目录就通过"：
     * <ul>
     *   <li>能找到 {@code versions/} 或本身就是某个版本目录 → 这是游戏目录；</li>
     *   <li>同时还有 {@code mods/} → 这是个装过模组的整合包（本加载器的目标场景）；</li>
     *   <li>只有版本、没有 {@code mods/} → 能用，但没有跨端模组可加载（会提示）；</li>
     *   <li>两样都没有 → 不通过，直接告诉用户"选错了"。</li>
     * </ul>
     */
    public static Result verifyDirectory(Path gameDir) {
        java.util.List<Item> items = new java.util.ArrayList<>(5);
        Level overall = Level.OK;

        if (gameDir == null) {
            items.add(new Item("游戏目录", Level.FAIL, "没有指定目录"));
            return new Result("第一道：目录校验", Level.FAIL, items);
        }
        if (!Files.isDirectory(gameDir)) {
            items.add(new Item("游戏目录", Level.FAIL, "目录不存在：" + gameDir));
            return new Result("第一道：目录校验", Level.FAIL, items);
        }
        items.add(new Item("游戏目录", Level.OK, gameDir.toString()));

        // 是"游戏的根"还是"某一个版本目录"？两种都允许，但要认出来并说明。
        Path versionsDir = gameDir.resolve("versions");
        Path parent = gameDir.getParent();
        boolean isVersionDir = parent != null && parent.getFileName() != null
                && "versions".equalsIgnoreCase(parent.getFileName().toString());
        boolean hasVersions = Files.isDirectory(versionsDir) || isVersionDir;
        if (hasVersions) {
            items.add(new Item("目录形态", Level.OK, isVersionDir && !Files.isDirectory(versionsDir)
                    ? "这是某一个版本目录（版本列表取同级目录）"
                    : "标准的 .minecraft 布局（有 versions/）"));
        } else {
            items.add(new Item("目录形态", Level.FAIL,
                    "既不是 .minecraft 根目录（没有 versions/），也不是某个版本目录 —— 选错目录了"));
            overall = Level.FAIL;
        }

        Path modsDir = gameDir.resolve("mods");
        if (Files.isDirectory(modsDir)) {
            int n = countJars(modsDir);
            if (n > 0) {
                items.add(new Item("模组目录", Level.OK, "mods/ 里有 " + n + " 个模组包"));
            } else {
                items.add(new Item("模组目录", Level.WARN, "mods/ 存在但是空的 —— 装完也没有模组可加载"));
                overall = worse(overall, Level.WARN);
            }
        } else {
            items.add(new Item("模组目录", Level.WARN,
                    "没有 mods/ 目录（安装器会创建它，但里面没有模组就没有跨端可加载）"));
            overall = worse(overall, Level.WARN);
        }

        // 可写性：安装器要往这里写文件，先确认而不是写到一半才发现。
        if (Files.isWritable(gameDir)) {
            items.add(new Item("写入权限", Level.OK, "可写"));
        } else {
            items.add(new Item("写入权限", Level.FAIL, "目录不可写（可能需要管理员权限）"));
            overall = Level.FAIL;
        }

        return new Result("第一道：目录校验", overall, items);
    }

    // ------------------------------------------------------------------ 第二道：版本

    /**
     * 第二道验证：用户挑的这个版本能不能被本加载器接管。
     *
     * <p>这是<b>真正决定"装完能不能用"</b>的一道，判据都对应实测过的失败：
     * <ul>
     *   <li>版本目录不完整（缺 json 或 jar）→ 起不来；</li>
     *   <li><b>游戏包是混淆的</b> → 合并按内部名搬运，一个都匹配不上。
     *       实测 1.21.1 的 8269 个类里 {@code net/minecraft/} 下只有 26 个，
     *       其余是 {@code chn.class} 这种混淆名 —— 这类版本目前装了也用不了，
     *       必须明确说出来（而不是装完崩在启动期）。</li>
     *   <li>Java 版本不够 → 起不来（26.x 要 Java 25）。</li>
     * </ul>
     */
    public static Result verifyVersion(Path gameDir, String versionId, int requiredJava) {
        java.util.List<Item> items = new java.util.ArrayList<>(6);
        Level overall = Level.OK;

        if (versionId == null || versionId.isBlank()) {
            items.add(new Item("版本", Level.FAIL, "没有选择版本"));
            return new Result("第二道：版本校验", Level.FAIL, items);
        }
        items.add(new Item("目标版本", Level.OK, versionId));

        Path versionDir = resolveVersionDir(gameDir, versionId);
        if (versionDir == null) {
            items.add(new Item("版本目录", Level.FAIL, "在 " + gameDir + " 里找不到版本 " + versionId));
            return new Result("第二道：版本校验", Level.FAIL, items);
        }
        items.add(new Item("版本目录", Level.OK, versionDir.toString()));

        // 完整性：json 与 jar 缺一不可。
        Path json = versionDir.resolve(versionId + ".json");
        Path jar = versionDir.resolve(versionId + ".jar");
        boolean hasJson = Files.isRegularFile(json);
        boolean hasJar = Files.isRegularFile(jar);
        if (hasJson && hasJar) {
            items.add(new Item("版本完整性", Level.OK, "json 与 jar 都在"));
        } else {
            items.add(new Item("版本完整性", Level.FAIL,
                    (hasJson ? "" : "缺 " + versionId + ".json；")
                            + (hasJar ? "" : "缺 " + versionId + ".jar")
                            + " —— 版本没装完整，先用启动器跑一次让它补齐"));
            overall = Level.FAIL;
        }

        // 继承链：版本 json 可能继承另一个版本（例如 Fabric 版本继承原版）。
        if (hasJson) {
            String inherits = readInheritsFrom(json);
            if (inherits != null) {
                Path base = resolveVersionDir(gameDir, inherits);
                boolean baseOk = base != null
                        && (Files.isRegularFile(base.resolve(inherits + ".json"))
                        || Files.isRegularFile(base.resolve(inherits + ".jar")));
                items.add(new Item("继承链", baseOk ? Level.OK : Level.FAIL,
                        "继承自 " + inherits + (baseOk ? "（已就绪）" : "（父版本缺失，装了也起不来）")));
                if (!baseOk) {
                    overall = Level.FAIL;
                }
            } else {
                items.add(new Item("继承链", Level.OK, "不需要继承（自包含版本）"));
            }
        }

        // **最关键的判据**：游戏包的命名空间。
        if (hasJar) {
            Namespace ns = probeNamespace(jar);
            switch (ns) {
                case DEOBFUSCATED -> items.add(new Item("命名空间", Level.OK,
                        "不混淆（真名）—— 合并可以按内部名搬运，这个版本能装"));
                case OBFUSCATED -> {
                    items.add(new Item("命名空间", Level.FAIL,
                            "**混淆** —— 合并是按内部名搬运的，混淆版本一个都匹配不上。"
                                    + "这个版本需要先补 remap 层（见 README 第十四节），"
                                    + "**本次安装会装上但跨端功能不可用**"));
                    overall = Level.FAIL;
                }
                case UNKNOWN -> {
                    items.add(new Item("命名空间", Level.WARN,
                            "读不出游戏包内容，无法判断混淆状态 —— 装完请留意启动日志"));
                    overall = worse(overall, Level.WARN);
                }
            }
        }

        // Java：26.x 要 25，选错直接启动失败。
        if (requiredJava > 0) {
            int actual = currentJavaMajor();
            if (actual >= requiredJava) {
                items.add(new Item("Java", Level.OK, "本机 Java " + actual + " ≥ 要求 " + requiredJava));
            } else {
                items.add(new Item("Java", Level.WARN,
                        "本机 Java " + actual + " 低于该版本常见的 " + requiredJava
                                + "（启动时以启动器实际使用的 Java 为准）"));
                overall = worse(overall, Level.WARN);
            }
        }

        return new Result("第二道：版本校验", overall, items);
    }

    // ------------------------------------------------------------------ 内部

    /** 命名空间探测结果。 */
    public enum Namespace {
        /** 真名：可以直接合并。 */
        DEOBFUSCATED,
        /** 混淆：需要 remap 层。 */
        OBFUSCATED,
        /** 读不出来。 */
        UNKNOWN
    }

    /**
     * 探测一个游戏包的命名空间。
     *
     * <p>判据与 {@code PatchedBase.isDeobfuscated} 保持一致（都认
     * {@code net/minecraft/core/Registry.class} 这个锚点），但这里<b>不依赖加载器主 jar</b> ——
     * 安装器必须能独立工作，所以自己用 FastZip 读一遍。
     */
    public static Namespace probeNamespace(Path gameJar) {
        if (gameJar == null || !Files.isRegularFile(gameJar)) {
            return Namespace.UNKNOWN;
        }
        try (FastZip z = FastZip.open(gameJar)) {
            if (z.entry("net/minecraft/core/Registry.class") != null) {
                return Namespace.DEOBFUSCATED;
            }
            // 再看一眼是不是"顶层裸类"布局（混淆的典型特征）。
            int mc = 0;
            int bare = 0;
            int total = 0;
            for (FastZip.Entry e : z.entries()) {
                if (!e.isClass() || e.isDirectory()) {
                    continue;
                }
                total++;
                String n = e.name();
                if (n.startsWith("net/minecraft/")) {
                    mc++;
                } else if (n.indexOf('/') < 0) {
                    bare++;
                }
                if (total > 4000) {
                    break;                        // 够判断了就停，不必读完
                }
            }
            if (bare > mc) {
                return Namespace.OBFUSCATED;
            }
            return Namespace.UNKNOWN;
        } catch (Throwable t) {
            return Namespace.UNKNOWN;
        }
    }

    /** 在 {@code .minecraft} 或"某一个版本目录"两种形态下都能找到版本目录。 */
    public static Path resolveVersionDir(Path gameDir, String versionId) {
        if (gameDir == null || versionId == null) {
            return null;
        }
        Path direct = gameDir.resolve("versions").resolve(versionId);
        if (Files.isDirectory(direct)) {
            return direct;
        }
        // 用户可能直接把版本目录选进来了，这时版本目录就是它自己。
        if (gameDir.getFileName() != null && gameDir.getFileName().toString().equals(versionId)
                && Files.isDirectory(gameDir)) {
            return gameDir;
        }
        Path sibling = gameDir.resolve(versionId);
        if (Files.isDirectory(sibling)) {
            return sibling;
        }
        return null;
    }

    private static String readInheritsFrom(Path versionJson) {
        try {
            java.util.Map<String, Object> root =
                    com.fusionml.util.Json.parseObject(Files.readAllBytes(versionJson));
            String v = com.fusionml.util.Json.str(root, "inheritsFrom", "");
            return v == null || v.isBlank() ? null : v;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int countJars(Path dir) {
        int n = 0;
        try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.jar")) {
            for (Path ignored : ds) {
                n++;
            }
        } catch (Throwable ignored) {
            // 读不了就当 0
        }
        return n;
    }

    /** 本机正在跑的 Java 主版本。 */
    public static int currentJavaMajor() {
        String v = System.getProperty("java.version", "");
        try {
            if (v.startsWith("1.")) {
                return Integer.parseInt(v.substring(2, 3));
            }
            int dot = v.indexOf('.');
            return Integer.parseInt(dot < 0 ? v : v.substring(0, dot));
        } catch (Throwable t) {
            return 0;
        }
    }

    private static Level worse(Level a, Level b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
