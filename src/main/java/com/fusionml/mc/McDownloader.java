package com.fusionml.mc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 带 SHA1 校验与缓存的下载器。
 *
 * <p>两条关键行为：
 * <ul>
 *   <li><b>已存在且校验通过就直接复用</b> —— 整合包用户往往已经下过一遍资源，
 *       重复下载几百 MB 是最没必要的浪费；</li>
 *   <li><b>先下到 .part 再改名</b> —— 中途断网或被强杀不会留下"看起来存在、其实是半截"的文件，
 *       否则下次启动会因为一个残缺的库报出一堆看不懂的错。</li>
 * </ul>
 *
 * <p>实现上刻意使用 {@link HttpURLConnection} 而不是 JDK 的 {@code java.net.http.HttpClient}：
 * 后者会在初始化时通过本机回环建立一个自唤醒连接，而在某些受限环境（防火墙策略、
 * 容器网络、沙箱）里这个回环连接会直接失败，表现为"什么网络请求都没发就报连接错误"。
 * {@code HttpURLConnection} 没有这个前置依赖，行为更可预期。
 */
public final class McDownloader {

    /** 下载进度回调。 */
    public interface Progress {
        void update(String what, long done, long total);

        Progress SILENT = (what, done, total) -> {
        };
    }

    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 120_000;

    private long downloaded;
    private long reused;
    private final String userAgent = "FusionML/1.0";

    public long bytesDownloaded() {
        return downloaded;
    }

    public long bytesReused() {
        return reused;
    }

    /**
     * 确保文件存在且内容正确。
     *
     * @param sha1 期望的 SHA1（十六进制）；为空则跳过校验
     * @return true 表示发生了网络下载，false 表示复用了本地文件
     */
    public boolean ensure(Path target, String url, String sha1, Progress progress)
            throws IOException {
        if (Files.isRegularFile(target)) {
            long size = Files.size(target);
            if (sha1 == null || sha1.isBlank() || sha1.equalsIgnoreCase(sha1Of(target))) {
                reused += size;
                progress.update(target.getFileName().toString(), size, size);
                return false;
            }
            // 校验不过就删掉重下，而不是留着这个坏文件继续报错。
            Files.delete(target);
        }
        if (url == null || url.isBlank()) {
            throw new IOException("缺少下载地址，无法获取 " + target);
        }
        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");
        long total = 0;
        try {
            HttpURLConnection conn = open(url);
            int code = conn.getResponseCode();
            if (code / 100 != 2) {
                conn.disconnect();
                throw new IOException("下载失败 HTTP " + code + "：" + url);
            }
            total = conn.getContentLengthLong();
            long written = 0;
            try (InputStream in = conn.getInputStream();
                 OutputStream out = Files.newOutputStream(part)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    written += n;
                    progress.update(target.getFileName().toString(), written, total);
                }
            }
            conn.disconnect();
            if (total > 0 && written != total) {
                Files.deleteIfExists(part);
                throw new IOException("下载不完整：" + target.getFileName()
                        + "（期望 " + total + " 字节，收到 " + written + " 字节）");
            }
        } catch (IOException e) {
            Files.deleteIfExists(part);
            throw e;
        }
        if (sha1 != null && !sha1.isBlank()) {
            String actual = sha1Of(part);
            if (!sha1.equalsIgnoreCase(actual)) {
                Files.deleteIfExists(part);
                throw new IOException("SHA1 校验失败：" + target.getFileName()
                        + "（期望 " + sha1 + "，实际 " + actual + "）");
            }
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        downloaded += Files.size(target);
        return true;
    }

    /** 直接取到内存（用于版本索引这类小文件）。 */
    public byte[] fetch(String url) throws IOException {
        HttpURLConnection conn = open(url);
        try {
            int code = conn.getResponseCode();
            if (code / 100 != 2) {
                throw new IOException("HTTP " + code + "：" + url);
            }
            try (InputStream in = conn.getInputStream()) {
                return in.readAllBytes();
            }
        } finally {
            conn.disconnect();
        }
    }

    private HttpURLConnection open(String url) throws IOException {
        URL u = URI.create(url).toURL();
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", userAgent);
        return conn;
    }

    public static String sha1Of(Path file) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        byte[] buf = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }
}
