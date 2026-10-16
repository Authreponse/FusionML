package com.fusionml.launch;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ServerSide {

    /** 官方 server.jar 里带的自述文件，字段与客户端版本 json 同源。 */
    private static final String VERSION_JSON = "version.json";

    private ServerSide() {
    }

    /**
     * 从游戏目录里的 {@code server.jar} 读出 Minecraft 版本号。
     *
     * <p>读不到就返回 {@code "?"}：这里只是日志与平台判定用的标签，
     * 为了它让整个服务端起不来是本末倒置。
     */
    public static String versionOf(Path gameDir) {
        Path jar = gameDir.resolve("server.jar");
        if (!Files.isRegularFile(jar)) {
            return "?";
        }
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry e = zip.getEntry(VERSION_JSON);
            if (e == null) {
                return "?";
            }
            String text;
            try (InputStream in = zip.getInputStream(e)) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            String id = jsonString(text, "id");
            return id.isBlank() ? "?" : id;
        } catch (Exception ex) {
            return "?";
        }
    }

    private static String jsonString(String text, String key) {
        int i = text.indexOf('"' + key + '"');
        if (i < 0) {
            return "";
        }
        i = text.indexOf(':', i + key.length() + 2);
        if (i < 0) {
            return "";
        }
        int open = text.indexOf('"', i + 1);
        if (open < 0) {
            return "";
        }
        int close = text.indexOf('"', open + 1);
        if (close < 0) {
            return "";
        }
        return text.substring(open + 1, close);
    }

    /**
     * 判断当前进程是不是由 {@code server.jar} 拉起来的。
     *
     * <p>依据是真实观测到的命令行，而不是猜系统属性：
     * bundler 会把 {@code sun.java.command} 设成 {@code server.jar nogui}。
     */
    public static boolean looksLikeServerJar() {
        String cmd = System.getProperty("sun.java.command", "");
        return cmd.contains("server.jar");
    }
}
