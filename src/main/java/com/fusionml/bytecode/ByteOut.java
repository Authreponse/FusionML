package com.fusionml.bytecode;

import java.util.Arrays;

/** 可增长的大端字节输出流。JVM class 文件全部使用大端序。 */
public final class ByteOut {
    private byte[] buf;
    private int len;

    public ByteOut(int capacity) {
        this.buf = new byte[Math.max(16, capacity)];
    }

    public ByteOut u1(int v) {
        ensure(1);
        buf[len++] = (byte) v;
        return this;
    }

    public ByteOut u2(int v) {
        ensure(2);
        buf[len++] = (byte) (v >>> 8);
        buf[len++] = (byte) v;
        return this;
    }

    public ByteOut u4(long v) {
        ensure(4);
        buf[len++] = (byte) (v >>> 24);
        buf[len++] = (byte) (v >>> 16);
        buf[len++] = (byte) (v >>> 8);
        buf[len++] = (byte) v;
        return this;
    }

    public ByteOut raw(byte[] src, int off, int count) {
        ensure(count);
        System.arraycopy(src, off, buf, len, count);
        len += count;
        return this;
    }

    /** 直接写入本流已产生的 [from,len) 区间（用于把已写好的常量池项拼回去）。 */
    public ByteOut self(int from, int count) {
        ensure(count);
        System.arraycopy(buf, from, buf, len, count);
        len += count;
        return this;
    }

    /** 写入修改型 UTF-8（class 文件常量池的字符串编码），返回本次写入的长度。 */
    public int mutf8(String s) {
        int start = len;
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c >= 0x0001 && c <= 0x007F) {
                u1(c);
            } else if (c == 0x0000 || (c >= 0x0080 && c <= 0x07FF)) {
                u2(0xC000 | c);
            } else if (c >= 0x0800) {
                // 修改型 UTF-8 对基本平面之外的字符按 CESU-8 用代理对拆成两个三字节序列。
                u1(0xE0 | (c >> 12));
                u1(0x80 | ((c >> 6) & 0x3F));
                u1(0x80 | (c & 0x3F));
            }
        }
        return len - start;
    }

    private void ensure(int extra) {
        if (len + extra <= buf.length) {
            return;
        }
        int cap = buf.length;
        while (cap < len + extra) {
            cap = cap < 1024 ? cap * 2 : cap + (cap >> 1);
        }
        buf = Arrays.copyOf(buf, cap);
    }

    public int size() {
        return len;
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(buf, len);
    }

    public byte[] rawBuffer() {
        return buf;
    }
}
