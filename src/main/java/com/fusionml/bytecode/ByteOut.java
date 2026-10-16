package com.fusionml.bytecode;

import java.util.Arrays;

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

    public ByteOut self(int from, int count) {
        ensure(count);
        System.arraycopy(buf, from, buf, len, count);
        len += count;
        return this;
    }

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
