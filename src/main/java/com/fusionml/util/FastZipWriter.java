package com.fusionml.util;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * 自研 ZIP 写入器（与 {@link FastZip} 对称）。
 *
 * <p>同样不依赖 {@code java.util.zip.ZipOutputStream}：压缩算法本身用 JDK 的
 * {@link Deflater}（这是平台能力），但 ZIP 容器格式的编排 —— 本地文件头、中央目录、
 * ZIP64 扩展字段、EOCD —— 全部自己写。这样读写两侧对格式的理解是一致的，
 * 也便于把测试夹具做成"真正会被自家读取器接受"的包。
 */
public final class FastZipWriter implements Closeable {

    private static final int METHOD_STORED = 0;
    private static final int METHOD_DEFLATED = 8;

    private static final class Central {
        String name;
        int method;
        long crc;
        long compressedSize;
        long size;
        long localOffset;
    }

    private final OutputStream out;
    private final List<Central> centrals = new ArrayList<>(64);
    private final byte[] buffer = new byte[8192];
    private long written;
    private boolean finished;

    public FastZipWriter(OutputStream out) {
        this.out = out;
    }

    /** 写入一个条目。{@code compress=false} 时按 STORED 存（适合已经压缩过的内容）。 */
    public void add(String name, byte[] data, boolean compress) throws IOException {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        Central c = new Central();
        c.name = name;
        c.size = data.length;
        CRC32 crc = new CRC32();
        crc.update(data);
        c.crc = crc.getValue();

        byte[] payload;
        if (compress && data.length > 64) {
            Deflater deflater = new Deflater(Deflater.BEST_SPEED, true);
            try {
                deflater.setInput(data);
                deflater.finish();
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(data.length / 2 + 64);
                while (!deflater.finished()) {
                    int n = deflater.deflate(buffer);
                    baos.write(buffer, 0, n);
                }
                payload = baos.toByteArray();
                c.method = METHOD_DEFLATED;
            } finally {
                deflater.end();
            }
        } else {
            payload = data;
            c.method = METHOD_STORED;
        }
        c.compressedSize = payload.length;
        c.localOffset = written;

        // 本地文件头
        writeInt(0x04034b50);
        writeShort(20);                              // version needed
        writeShort(0x0800);                          // 通用标志：文件名是 UTF-8
        writeShort(c.method);
        writeShort(0);                               // 修改时间
        writeShort(0);                               // 修改日期
        writeInt(c.crc);
        writeInt(c.compressedSize);
        writeInt(c.size);
        writeShort(nameBytes.length);
        writeShort(0);                               // 扩展字段长度
        out.write(nameBytes);
        out.write(payload);
        // 注意：本地文件头的 30 字节已经由上面的 writeInt/writeShort 逐个累加过了，
        // 这里只能补名字与数据的长度，否则中央目录偏移会凭空多出 30 字节/条目。
        written += (long) nameBytes.length + payload.length;
        centrals.add(c);
    }

    /**
     * 原样写入一个条目的<b>压缩数据</b>（不重新压缩）。
     *
     * <p>与 {@link com.fusionml.util.FastZip#readRaw} 对称，用于"整份搬运一个大包、
     * 只替换其中少数条目"的场景（例如把原版客户端包重建成"原版 + 对方平台补丁"）。
     * 逐个解压再压缩一个 40MB 的游戏包要十几秒，原样搬运是纯拷贝。</p>
     */
    public void addRaw(String name, int method, long crc, long size, byte[] compressed)
            throws IOException {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        Central c = new Central();
        c.name = name;
        c.method = method;
        c.crc = crc;
        c.size = size;
        c.compressedSize = compressed.length;
        c.localOffset = written;
        writeInt(0x04034b50);
        writeShort(20);
        writeShort(0x0800);
        writeShort(method);
        writeShort(0);
        writeShort(0);
        writeInt(c.crc);
        writeInt(c.compressedSize);
        writeInt(c.size);
        writeShort(nameBytes.length);
        writeShort(0);
        out.write(nameBytes);
        out.write(compressed);
        written += (long) nameBytes.length + compressed.length;
        centrals.add(c);
    }

    /** 写完中央目录与 EOCD。 */
    public void finish() throws IOException {
        if (finished) {
            return;
        }
        long cdStart = written;
        for (Central c : centrals) {
            byte[] nameBytes = c.name.getBytes(StandardCharsets.UTF_8);
            writeInt(0x02014b50);
            writeShort(20);                          // version made by
            writeShort(20);                          // version needed
            writeShort(0x0800);
            writeShort(c.method);
            writeShort(0);
            writeShort(0);
            writeInt(c.crc);
            writeInt(c.compressedSize);
            writeInt(c.size);
            writeShort(nameBytes.length);
            writeShort(0);                           // extra
            writeShort(0);                           // comment
            writeShort(0);                           // disk
            writeShort(0);                           // internal attrs
            writeInt(0);                             // external attrs
            writeInt(c.localOffset);
            out.write(nameBytes);
            // 同理：中央目录头 46 字节已由 writeInt/writeShort 累加，这里只补文件名长度。
            written += nameBytes.length;
        }
        long cdSize = written - cdStart;
        writeInt(0x06054b50);
        writeShort(0);
        writeShort(0);
        writeShort(centrals.size());
        writeShort(centrals.size());
        writeInt(cdSize);
        writeInt(cdStart);
        writeShort(0);
        out.flush();
        finished = true;
    }

    private void writeShort(int v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
        written += 2;
    }

    private void writeInt(long v) throws IOException {
        out.write((int) (v & 0xFF));
        out.write((int) ((v >>> 8) & 0xFF));
        out.write((int) ((v >>> 16) & 0xFF));
        out.write((int) ((v >>> 24) & 0xFF));
        written += 4;
    }

    @Override
    public void close() throws IOException {
        finish();
        out.close();
    }
}
