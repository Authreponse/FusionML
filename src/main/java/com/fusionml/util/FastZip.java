package com.fusionml.util;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * 自研 ZIP 只读索引器。
 *
 * <p>为什么不直接用 {@code java.util.zip.ZipFile}：它内部对本机句柄有共享同步，
 * 多个线程读同一个 jar 时会互相排队，"并行解压一个 300MB 的模组包"根本并行不起来。
 * 这里改成：
 * <ul>
 *   <li>自己解析中央目录（Central Directory），拿到每个条目的压缩方式/大小/本地头偏移；</li>
 *   <li>读取条目时用 {@link FileChannel#read(ByteBuffer, long)} 做<b>定位读</b>，
 *       完全不持有共享锁，任意多线程可以同时读同一个 jar 的不同条目；</li>
 *   <li>只读前 N 字节时（识别 {@code fabric.mod.json} / {@code mods.toml} 等元数据）
 *       走 {@link #readPrefix} 快速路径，不为整个大文件分配内存；</li>
 *   <li>Inflater 按线程复用（ThreadLocal + reset），避免每个条目都新建本机资源。</li>
 * </ul>
 *
 * <p>支持 ZIP64（大包 &gt;4GB 或 &gt;65535 条目）。
 */
public final class FastZip implements Closeable {

    private static final int SIG_EOCD = 0x06054b50;
    private static final int SIG_EOCD64 = 0x06064b50;
    private static final int SIG_EOCD64_LOCATOR = 0x07064b50;
    private static final int SIG_CENTRAL = 0x02014b50;
    private static final int SIG_LOCAL = 0x04034b50;
    private static final int METHOD_STORED = 0;
    private static final int METHOD_DEFLATED = 8;

    /** 中央目录里的一个条目。 */
    public record Entry(String name, int method, long compressedSize, long size,
                        long crc, long localHeaderOffset) {
        public boolean isDirectory() {
            return name.endsWith("/");
        }

        public boolean isClass() {
            return name.endsWith(".class");
        }
    }

    /**
     * ZIP 里存的是"裸 deflate"数据（没有 zlib 头），所以必须用 nowrap=true 的 Inflater。
     * 用默认构造器会得到 "incorrect header check" —— 这是最容易踩且最难从现象反推的一个坑。
     * 按线程复用以省掉重复分配本机资源。
     */
    private static final ThreadLocal<Inflater> INFLATER =
            ThreadLocal.withInitial(() -> new Inflater(true));

    private final Path path;
    private final FileChannel channel;
    /** 内存中的包（嵌套 jar 用）：非 null 时 channel 为 null。 */
    private final byte[] memory;
    private final List<Entry> entries;
    private final Map<String, Entry> byName;
    private final List<String> duplicateNames;
    private final long fileSize;

    private FastZip(Path path, FileChannel channel, byte[] memory, List<Entry> entries,
                    long fileSize, List<String> duplicateNames) {
        this.path = path;
        this.channel = channel;
        this.memory = memory;
        this.entries = entries;
        this.fileSize = fileSize;
        this.duplicateNames = duplicateNames;
        this.byName = new HashMap<>(Math.max(16, entries.size() * 2));
        for (Entry e : entries) {
            byName.put(e.name(), e);
        }
    }

    public static FastZip open(Path path) throws IOException {
        FileChannel ch = FileChannel.open(path, StandardOpenOption.READ);
        try {
            long size = ch.size();
            long eocdOffset = findEocd(ch, size);
            if (eocdOffset < 0) {
                throw new IOException("不是有效的 ZIP 文件（找不到中央目录结束记录）: " + path);
            }
            ByteBuffer eocd = readAt(ch, eocdOffset, 22);
            eocd.order(ByteOrder.LITTLE_ENDIAN);
            eocd.getInt();                        // 签名
            eocd.getShort();                      // 磁盘号
            eocd.getShort();                      // 中央目录起始磁盘
            int entriesOnDisk = eocd.getShort() & 0xFFFF;
            int totalEntries = eocd.getShort() & 0xFFFF;
            long cdSize = eocd.getInt() & 0xFFFFFFFFL;
            long cdOffset = eocd.getInt() & 0xFFFFFFFFL;

            // ZIP64：字段被写成全 1 时，去 ZIP64 定位器里找真值。
            boolean zip64 = totalEntries == 0xFFFF || cdOffset == 0xFFFFFFFFL || cdSize == 0xFFFFFFFFL;
            if (zip64 && eocdOffset >= 20) {
                ByteBuffer loc = readAt(ch, eocdOffset - 20, 20);
                loc.order(ByteOrder.LITTLE_ENDIAN);
                if (loc.getInt() == SIG_EOCD64_LOCATOR) {
                    loc.getInt();
                    long eocd64Offset = loc.getLong();
                    ByteBuffer e64 = readAt(ch, eocd64Offset, 56);
                    e64.order(ByteOrder.LITTLE_ENDIAN);
                    if (e64.getInt() == SIG_EOCD64) {
                        e64.getLong();                       // 记录长度
                        e64.getShort();
                        e64.getShort();
                        e64.getInt();
                        e64.getInt();
                        totalEntries = (int) e64.getLong();
                        entriesOnDisk = (int) e64.getLong();
                        cdSize = e64.getLong();
                        cdOffset = e64.getLong();
                    }
                }
            }
            if (totalEntries < 0 || totalEntries > 4_000_000) {
                throw new IOException("中央目录条目数异常: " + totalEntries);
            }
            if (entriesOnDisk != totalEntries) {
                // 分卷包不支持，但单卷包里这两个值一般相等；不等也按 totalEntries 读。
                totalEntries = Math.max(totalEntries, entriesOnDisk);
            }
            List<Entry> list = parseCentralDirectory(ch, cdOffset, cdSize, totalEntries);
            // 同名条目在真实 jar 里几乎一定是打包事故，但它会让"取到哪一个"变成隐性的随机行为。
            // 这里显式记下来交给上层提示用户，而不是默默取最后一个。
            List<String> dups = null;
            Map<String, Integer> seen = new HashMap<>(list.size() * 2);
            for (Entry e : list) {
                if (seen.merge(e.name(), 1, Integer::sum) == 2) {
                    if (dups == null) {
                        dups = new ArrayList<>(2);
                    }
                    dups.add(e.name());
                }
            }
            return new FastZip(path, ch, null, list, size, dups == null ? List.of() : dups);
        } catch (IOException e) {
            ch.close();
            throw e;
        }
    }

    /**
     * 打开一个内存中的 ZIP（用于嵌套 jar，即 {@code META-INF/jars/*.jar} 这类）。
     *
     * <p>嵌套 jar 的内容本来就已经在内存里（它只是外层包的一个条目），
     * 所以不需要再落盘、也不需要文件通道 —— 直接在这段字节上解析中央目录即可。
     * 这是支持 Jar-in-Jar 的最小代价路径。
     */
    public static FastZip open(byte[] data, String label) throws IOException {
        long size = data.length;
        int eocdOffset = findEocdIn(data);
        if (eocdOffset < 0) {
            throw new IOException("不是有效的 ZIP（找不到中央目录结束记录）：" + label);
        }
        int p = eocdOffset + 4 + 2 + 2 + 2;
        int totalEntries = leShort(data, p);
        int q = eocdOffset + 4 + 2 + 2 + 2 + 2 + 2;
        long cdSize = leInt(data, q) & 0xFFFFFFFFL;
        long cdOffset = leInt(data, q + 4) & 0xFFFFFFFFL;
        List<Entry> list = parseCentralDirectoryInMemory(data, (int) cdOffset, (int) cdSize,
                totalEntries);
        List<String> dups = null;
        Map<String, Integer> seen = new HashMap<>(list.size() * 2);
        for (Entry e : list) {
            if (seen.merge(e.name(), 1, Integer::sum) == 2) {
                if (dups == null) {
                    dups = new ArrayList<>(2);
                }
                dups.add(e.name());
            }
        }
        return new FastZip(Path.of(label), null, data, list, size,
                dups == null ? List.of() : dups);
    }

    private static int findEocdIn(byte[] d) {
        int from = Math.max(0, d.length - 66_000);
        for (int i = d.length - 22; i >= from; i--) {
            if (leInt(d, i) == SIG_EOCD) {
                int commentLen = leShort(d, i + 20);
                if (i + 22 + commentLen <= d.length) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 内存版中央目录解析（与文件版同构，只是数据源不同）。 */
    private static List<Entry> parseCentralDirectoryInMemory(byte[] cd, int cdOffset, int cdSize,
                                                             int count) throws IOException {
        List<Entry> out = new ArrayList<>(count);
        int p = cdOffset;
        for (int i = 0; i < count; i++) {
            if (p + 46 > cd.length || leInt(cd, p) != SIG_CENTRAL) {
                break;
            }
            int method = leShort(cd, p + 10);
            long crc = leInt(cd, p + 16) & 0xFFFFFFFFL;
            long compSize = leInt(cd, p + 20) & 0xFFFFFFFFL;
            long size = leInt(cd, p + 24) & 0xFFFFFFFFL;
            int nameLen = leShort(cd, p + 28);
            int extraLen = leShort(cd, p + 30);
            int commentLen = leShort(cd, p + 32);
            long localOffset = leInt(cd, p + 42) & 0xFFFFFFFFL;
            int nameOff = p + 46;
            int extraOff = nameOff + nameLen;
            if (extraOff + extraLen > cd.length) {
                throw new EOFException("中央目录条目的扩展字段越界：第 " + i + " 条");
            }
            if (extraLen > 0 && (size == 0xFFFFFFFFL || compSize == 0xFFFFFFFFL
                    || localOffset == 0xFFFFFFFFL)) {
                long[] z = parseZip64Extra(cd, extraOff, extraLen);
                if (z != null) {
                    if (size == 0xFFFFFFFFL && z.length > 0) size = z[0];
                    if (compSize == 0xFFFFFFFFL && z.length > 1) compSize = z[1];
                    if (localOffset == 0xFFFFFFFFL && z.length > 2) localOffset = z[2];
                }
            }
            String name = new String(cd, nameOff, nameLen, StandardCharsets.UTF_8);
            out.add(new Entry(name, method, compSize, size, crc, localOffset));
            p = extraOff + extraLen + commentLen;
        }
        return out;
    }

    private static long findEocd(FileChannel ch, long size) throws IOException {
        int maxTail = (int) Math.min(size, 66_000L);   // 22 + 最大注释 65535
        if (maxTail < 22) {
            return -1;
        }
        ByteBuffer tail = readAt(ch, size - maxTail, maxTail);
        tail.order(ByteOrder.LITTLE_ENDIAN);
        byte[] buf = new byte[maxTail];
        tail.get(buf);
        // 从后往前找签名；必须校验注释长度自洽，避免误命中文件内容里的假签名。
        for (int i = maxTail - 22; i >= 0; i--) {
            if ((buf[i] & 0xFF) == 0x50 && (buf[i + 1] & 0xFF) == 0x4B
                    && (buf[i + 2] & 0xFF) == 0x05 && (buf[i + 3] & 0xFF) == 0x06) {
                int commentLen = (buf[i + 20] & 0xFF) | ((buf[i + 21] & 0xFF) << 8);
                if (i + 22 + commentLen <= maxTail) {
                    return size - maxTail + i;
                }
            }
        }
        return -1;
    }

    private static List<Entry> parseCentralDirectory(FileChannel ch, long cdOffset, long cdSize, int count)
            throws IOException {
        List<Entry> out = new ArrayList<>(count);
        if (cdSize > 0 && cdSize <= (64L << 20)) {
            // 快路径：一次系统调用把整个中央目录读进堆，再纯内存遍历。
            // 典型模组包的中央目录只有几十 KB ~ 几 MB，这一步几乎不花时间。
            ByteBuffer buf = readAt(ch, cdOffset, (int) cdSize);
            buf.order(ByteOrder.LITTLE_ENDIAN);
            byte[] cd = new byte[(int) cdSize];
            buf.get(cd);
            int p = 0;
            for (int i = 0; i < count; i++) {
                if (p + 46 > cd.length) {
                    throw new EOFException("中央目录被截断，第 " + i + " 条");
                }
                if (leInt(cd, p) != SIG_CENTRAL) {
                    // 中央目录可能带数字签名等附加块，遇到非条目标志就停止。
                    break;
                }
                int method = leShort(cd, p + 10);
                long crc = leInt(cd, p + 16) & 0xFFFFFFFFL;
                long compSize = leInt(cd, p + 20) & 0xFFFFFFFFL;
                long size = leInt(cd, p + 24) & 0xFFFFFFFFL;
                int nameLen = leShort(cd, p + 28);
                int extraLen = leShort(cd, p + 30);
                int commentLen = leShort(cd, p + 32);
                long localOffset = leInt(cd, p + 42) & 0xFFFFFFFFL;
                int nameOff = p + 46;
                int extraOff = nameOff + nameLen;
                if (extraOff + extraLen > cd.length) {
                    throw new EOFException("中央目录条目的扩展字段越界: 第 " + i + " 条");
                }
                if (extraLen > 0 && (size == 0xFFFFFFFFL || compSize == 0xFFFFFFFFL
                        || localOffset == 0xFFFFFFFFL)) {
                    long[] z = parseZip64Extra(cd, extraOff, extraLen);
                    if (z != null) {
                        if (size == 0xFFFFFFFFL && z.length > 0) size = z[0];
                        if (compSize == 0xFFFFFFFFL && z.length > 1) compSize = z[1];
                        if (localOffset == 0xFFFFFFFFL && z.length > 2) localOffset = z[2];
                    }
                }
                String name = new String(cd, nameOff, nameLen, StandardCharsets.UTF_8);
                out.add(new Entry(name, method, compSize, size, crc, localOffset));
                p = extraOff + extraLen + commentLen;
            }
            return out;
        }
        // 慢路径（超大中央目录，罕见）：逐条定位读。
        long pos = cdOffset;
        for (int i = 0; i < count; i++) {
            ByteBuffer head = readAt(ch, pos, 46);
            head.order(ByteOrder.LITTLE_ENDIAN);
            if (head.getInt() != SIG_CENTRAL) {
                break;
            }
            head.getShort();
            head.getShort();
            head.getShort();
            int method = head.getShort() & 0xFFFF;
            head.getShort();
            head.getShort();
            head.getInt();
            long compSize = head.getInt() & 0xFFFFFFFFL;
            long size = head.getInt() & 0xFFFFFFFFL;
            int nameLen = head.getShort() & 0xFFFF;
            int extraLen = head.getShort() & 0xFFFF;
            int commentLen = head.getShort() & 0xFFFF;
            head.getShort();
            head.getShort();
            head.getInt();
            long localOffset = head.getInt() & 0xFFFFFFFFL;
            byte[] nameBytes = readAt(ch, pos + 46, nameLen).array();
            String name = new String(nameBytes, StandardCharsets.UTF_8);
            out.add(new Entry(name, method, compSize, size, 0, localOffset));
            pos += 46L + nameLen + extraLen + commentLen;
        }
        return out;
    }

    /** 解析 ZIP64 扩展字段（id=0x0001），返回 [size, compSize, localOffset]（按实际存在的前缀）。 */
    private static long[] parseZip64Extra(byte[] cd, int off, int len) {
        int p = off;
        int end = off + len;
        while (p + 4 <= end) {
            int id = (cd[p] & 0xFF) | ((cd[p + 1] & 0xFF) << 8);
            int flen = (cd[p + 2] & 0xFF) | ((cd[p + 3] & 0xFF) << 8);
            p += 4;
            if (id == 0x0001) {
                int q = p;
                long[] vals = new long[3];
                int n = 0;
                while (n < 3 && q + 8 <= p + flen) {
                    vals[n++] = leLong(cd, q);
                    q += 8;
                }
                long[] trimmed = new long[n];
                System.arraycopy(vals, 0, trimmed, 0, n);
                return trimmed;
            }
            p += flen;
        }
        return null;
    }

    private static int leInt(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16)
                | ((b[o + 3] & 0xFF) << 24);
    }

    private static int leShort(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static long leLong(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8) | ((b[off + 2] & 0xFFL) << 16)
                | ((b[off + 3] & 0xFFL) << 24) | ((b[off + 4] & 0xFFL) << 32)
                | ((b[off + 5] & 0xFFL) << 40) | ((b[off + 6] & 0xFFL) << 48)
                | ((b[off + 7] & 0xFFL) << 56);
    }

    private static ByteBuffer readAt(FileChannel ch, long offset, int len) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(len);
        long pos = offset;
        while (buf.hasRemaining()) {
            int n = ch.read(buf, pos);
            if (n < 0) {
                throw new EOFException("读取越界 @ " + pos);
            }
            pos += n;
        }
        buf.flip();
        return buf;
    }

    public Path path() {
        return path;
    }

    public long fileSize() {
        return fileSize;
    }

    public List<Entry> entries() {
        return entries;
    }

    public Entry entry(String name) {
        return byName.get(name);
    }

    /** 这个包里有重名的条目名（正常情况下应为空）。 */
    public List<String> duplicateNames() {
        return duplicateNames;
    }

    /** 数据区在文件中的绝对偏移：跳过本地文件头（30 字节固定 + 文件名 + 扩展字段）。 */
    private long dataOffset(Entry e) throws IOException {
        if (memory != null) {
            int off = (int) e.localHeaderOffset();
            if (off + 30 > memory.length || leInt(memory, off) != SIG_LOCAL) {
                throw new IOException("内存包里的本地文件头无效：" + e.name());
            }
            int nameLen = leShort(memory, off + 26);
            int extraLen = leShort(memory, off + 28);
            return off + 30L + nameLen + extraLen;
        }
        ByteBuffer head = readAt(channel, e.localHeaderOffset(), 30);
        head.order(ByteOrder.LITTLE_ENDIAN);
        if (head.getInt() != SIG_LOCAL) {
            throw new IOException("本地文件头签名错误: " + e.name());
        }
        head.getShort();
        head.getShort();
        head.getShort();
        head.getShort();
        head.getShort();
        head.getInt();
        head.getInt();
        head.getInt();
        int nameLen = head.getShort() & 0xFFFF;
        int extraLen = head.getShort() & 0xFFFF;
        return e.localHeaderOffset() + 30L + nameLen + extraLen;
    }

    /** 读取整个条目。线程安全：不持有任何共享锁。 */
    public byte[] read(Entry e) throws IOException {
        if (e.size() > Integer.MAX_VALUE - 8) {
            throw new IOException("条目过大: " + e.name() + " = " + e.size());
        }
        int size = (int) e.size();
        byte[] out = new byte[size];
        readInto(e, out, 0, size);
        return out;
    }

    /**
     * 原样读出条目的<b>压缩数据</b>（不解压）。
     *
     * <p>给"整份搬运一个包"用：把 40MB 的游戏 jar 重建成"原版 + 少量覆盖"时，
     * 逐个解压再压缩要十几秒，而原样搬压缩数据就是纯内存拷贝，一秒级。
     * 配合 {@link FastZipWriter#addRaw} 使用。</p>
     */
    public byte[] readRaw(Entry e) throws IOException {
        if (e.compressedSize() > Integer.MAX_VALUE - 8) {
            throw new IOException("条目过大: " + e.name());
        }
        int len = (int) e.compressedSize();
        byte[] out = new byte[len];
        if (memory != null) {
            int off = (int) dataOffset(e);
            System.arraycopy(memory, off, out, 0, Math.min(len, memory.length - off));
            return out;
        }
        long off = dataOffset(e);
        ByteBuffer buf = readAt(channel, off, len);
        buf.get(out, 0, Math.min(len, buf.remaining()));
        return out;
    }

    /**
     * 只解出前 {@code prefix} 字节。识别元数据文件时用这个，避免为整个大文件分配内存，
     * 也不会白解压后面的内容。
     */
    public byte[] readPrefix(Entry e, int prefix) throws IOException {
        int want = (int) Math.min(prefix, e.size());
        byte[] out = new byte[want];
        int got = readInto(e, out, 0, want);
        if (got == want) {
            return out;
        }
        byte[] trimmed = new byte[got];
        System.arraycopy(out, 0, trimmed, 0, got);
        return trimmed;
    }

    /** 把条目的 [targetOff, targetOff+len) 解到 dst 的 dstOff 处，返回实际写入字节数。 */
    private int readInto(Entry e, byte[] dst, int dstOff, int len) throws IOException {
        if (len <= 0) {
            return 0;
        }
        if (memory != null) {
            return readIntoMemory(e, dst, dstOff, len);
        }
        long off = dataOffset(e);
        if (e.method() == METHOD_STORED) {
            int todo = (int) Math.min(len, e.compressedSize());
            ByteBuffer buf = readAt(channel, off, todo);
            buf.get(dst, dstOff, todo);
            return todo;
        }
        if (e.method() != METHOD_DEFLATED) {
            throw new IOException("不支持的压缩方式 " + e.method() + " : " + e.name());
        }
        Inflater inf = INFLATER.get();
        inf.reset();
        // 压缩数据分块读入，避免一次性为大条目分配压缩缓冲；解够 len 字节就收工。
        int inChunk = (int) Math.min(1 << 16, Math.max(64L, e.compressedSize()));
        byte[] in = new byte[inChunk];
        long remainingComp = e.compressedSize();
        long readPos = off;
        int written = 0;
        try {
            while (written < len && remainingComp > 0) {
                int toRead = (int) Math.min(in.length, remainingComp);
                ByteBuffer buf = readAt(channel, readPos, toRead);
                buf.get(in, 0, toRead);
                readPos += toRead;
                remainingComp -= toRead;
                inf.setInput(in, 0, toRead);
                while (written < len) {
                    int n = inf.inflate(dst, dstOff + written, len - written);
                    written += n;
                    if (n == 0) {
                        if (inf.finished() || inf.needsInput() || inf.needsDictionary()) {
                            break;
                        }
                    }
                }
                if (inf.finished()) {
                    break;
                }
            }
        } catch (DataFormatException ex) {
            throw new IOException("解压失败: " + e.name(), ex);
        } finally {
            inf.reset();
        }
        return written;
    }

    @Override
    public void close() throws IOException {
        if (channel != null) {
            channel.close();
        }
    }

    /** 内存包的读取路径：数据已经在数组里，不需要任何系统调用。 */
    private int readIntoMemory(Entry e, byte[] dst, int dstOff, int len) throws IOException {
        int off = (int) dataOffset(e);
        if (e.method() == METHOD_STORED) {
            int todo = Math.min(len, (int) Math.min(e.compressedSize(), memory.length - off));
            if (todo <= 0) {
                return 0;
            }
            System.arraycopy(memory, off, dst, dstOff, todo);
            return todo;
        }
        if (e.method() != METHOD_DEFLATED) {
            throw new IOException("不支持的压缩方式 " + e.method() + "：" + e.name());
        }
        Inflater inf = INFLATER.get();
        inf.reset();
        int compLen = (int) Math.min(e.compressedSize(), memory.length - off);
        inf.setInput(memory, off, compLen);
        int written = 0;
        try {
            while (written < len) {
                int n = inf.inflate(dst, dstOff + written, len - written);
                written += n;
                if (n == 0 && (inf.finished() || inf.needsInput() || inf.needsDictionary())) {
                    break;
                }
            }
        } catch (DataFormatException ex) {
            throw new IOException("解压失败：" + e.name(), ex);
        } finally {
            inf.reset();
        }
        return written;
    }

    /** 便捷方法：直接把整个 jar 作为字节数组打开（用于内存缓存热模组包）。 */
    public static byte[] readAllBytes(Path p) throws IOException {
        return Files.readAllBytes(p);
    }
}
