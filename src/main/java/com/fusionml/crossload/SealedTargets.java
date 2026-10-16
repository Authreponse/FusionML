package com.fusionml.crossload;

import com.fusionml.bytecode.ClassFile;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 原版"密封类型"的解除密封。
 *
 * <p>这是让另一端的模组能在宿主上跑起来的<b>必要修补</b>，原因很具体：
 *
 * <p>Minecraft 的不少类型是 {@code sealed} 的（{@code Holder}、{@code BlockBehaviour} 之类）。
 * 对方平台（NeoForge）是通过<b>给原版打补丁</b>——把这些类型的 {@code permits} 列表加上
 * 自己的实现类——来允许自己的 API 实现它们的。宿主是 Fabric 时，游戏类是没有那份补丁的，
 * 于是 JVM 会直接拒绝加载对方的类，报的是一句很容易被误读的话：
 *
 * <pre>IncompatibleClassChangeError: Failed listed permitted subclass check:
 * class DeferredHolder is not a permitted subclass of Holder</pre>
 *
 * <p>看起来像"模组写错了"，实际是"原版少了对方平台的补丁"。我们不去逐个维护
 * 对方的 permits 名单（那既不可控、也会随版本漂移），而是把那几个原版类型的
 * {@code sealed} 直接解除：效果等价（允许这些实现者存在），维护成本压到零。
 *
 * <p>要解除哪些类是<b>算出来的</b>，不是写死的：扫一遍跨端包（模组 + 对方平台的 API），
 * 找出它们直接继承/实现的原版类型，再看这些原版类型在原版 jar 里是不是密封的。
 * 也就是说：本机装载了什么跨端包，就只补它真正需要的那几条，没装跨端包时一个字节都不改。
 */
public final class SealedTargets {

    /**
     * "密封"在 JVMS 里不是一个访问标志，而是一个类属性。
     *
     * <p>这一点必须写清楚：{@code 0x1000} 在类访问标志里的含义是 {@code ACC_SYNTHETIC}
     * （{@code package-info} 这类合成类就带它），拿它当"密封位"会把一堆正常类误判成密封类。
     * 判据只有一条：类属性里有没有 {@code PermittedSubclasses}。
     */
    private static final String PERMITS_ATTR = "PermittedSubclasses";

    private SealedTargets() {
    }

    /**
     * 算出需要解除密封的原版类型（内部名）。
     *
     * @param foreignJars 跨端模组包 + 对方平台的运行时包
     * @param clientJar   本机正在使用的原版游戏 jar（判断"是不是密封的"）
     */
    public static List<String> compute(List<Path> foreignJars, Path clientJar,
                                       Consumer<String> log) {
        if (foreignJars == null || foreignJars.isEmpty() || clientJar == null) {
            return List.of();
        }
        // 第一步：跨端包里出现过的原版父类/接口。
        Set<String> candidates = new LinkedHashSet<>(64);
        for (Path jar : foreignJars) {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    if (e.isDirectory() || !e.getName().endsWith(".class")) {
                        continue;
                    }
                    byte[] bytes;
                    try (InputStream in = zip.getInputStream(e)) {
                        bytes = in.readAllBytes();
                    }
                    Header h;
                    try {
                        h = Header.read(bytes);
                    } catch (RuntimeException ex) {
                        continue;
                    }
                    if (h.superName != null && h.superName.startsWith("net/minecraft/")) {
                        candidates.add(h.superName);
                    }
                    for (String itf : h.interfaces) {
                        if (itf.startsWith("net/minecraft/")) {
                            candidates.add(itf);
                        }
                    }
                }
            } catch (Throwable t) {
                if (log != null) {
                    log.accept("扫描密封候选失败 " + jar.getFileName() + "：" + t);
                }
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        // 第二步：在这些候选里挑出"原版那边确实是密封的"。
        List<String> out = new ArrayList<>(8);
        try (ZipFile vanilla = new ZipFile(clientJar.toFile())) {
            for (String name : candidates) {
                ZipEntry e = vanilla.getEntry(name + ".class");
                if (e == null) {
                    continue;
                }
                byte[] bytes;
                try (InputStream in = vanilla.getInputStream(e)) {
                    bytes = in.readAllBytes();
                }
                try {
                    if (isSealed(bytes)) {
                        out.add(name);
                    }
                } catch (RuntimeException ex) {
                    // 解析不了就跳过：宁可少补一个，也不要改坏一个类
                }
            }
        } catch (Throwable t) {
            if (log != null) {
                log.accept("读取原版 jar 判密封失败：" + t);
            }
        }
        return out;
    }

    /**
     * 解除一个原版类的密封。
     *
     * @return 改写后的字节；不需要改时返回 null（调用方据此保持"零改动"）
     */
    public static byte[] strip(byte[] bytes) {
        if (bytes == null || bytes.length < 8) {
            return null;
        }
        ClassFile cf;
        try {
            cf = ClassFile.read(bytes);
        } catch (RuntimeException e) {
            return null;
        }
        if (!isSealed(cf)) {
            return null;
        }
        cf.dropClassAttribute(PERMITS_ATTR);
        byte[] out = cf.toBytes();
        // 自检：改写后的字节必须还能被解析，且不再是密封类型。
        // 少了这一步，写坏一个游戏类就是 ClassFormatError 直接崩启动。
        try {
            ClassFile check = ClassFile.read(out);
            if (isSealed(check) || !cf.name().equals(check.name())) {
                return null;
            }
        } catch (RuntimeException e) {
            return null;
        }
        return out;
    }

    /** 这个类是不是密封的（判据只有"有没有 PermittedSubclasses 属性"）。 */
    public static boolean isSealed(byte[] bytes) {
        try {
            return isSealed(ClassFile.read(bytes));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean isSealed(ClassFile cf) {
        for (ClassFile.Attr a : cf.classAttributes()) {
            if (PERMITS_ATTR.equals(cf.attrName(a))) {
                return true;
            }
        }
        return false;
    }

    /** 只读类头：常量池、访问标志、父类与接口。 */
    private static final class Header {
        final int access;
        final String self;
        final String superName;
        final String[] interfaces;

        Header(int access, String self, String superName, String[] interfaces) {
            this.access = access;
            this.self = self;
            this.superName = superName;
            this.interfaces = interfaces;
        }

        static Header read(byte[] b) {
            int p = 0;
            if (((b[0] & 0xFF) << 24 | (b[1] & 0xFF) << 16 | (b[2] & 0xFF) << 8
                    | (b[3] & 0xFF)) != (int) ClassFile.MAGIC) {
                throw new IllegalArgumentException("不是类文件");
            }
            p = 8;                                        // magic + minor + major
            int cpCount = u2(b, p);
            p += 2;
            String[] utf8 = new String[cpCount];
            int[] cls = new int[cpCount];
            for (int i = 1; i < cpCount; i++) {
                int tag = b[p++] & 0xFF;
                switch (tag) {
                    case 1 -> {
                        int len = u2(b, p);
                        p += 2;
                        utf8[i] = new String(b, p, len, java.nio.charset.StandardCharsets.UTF_8);
                        p += len;
                    }
                    case 7 -> {
                        cls[i] = u2(b, p);
                        p += 2;
                    }
                    case 8, 16, 19, 20 -> p += 2;
                    case 15 -> p += 3;
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> p += 4;
                    case 5, 6 -> {
                        p += 8;
                        i++;
                    }
                    default -> throw new IllegalArgumentException("未知常量池标签 " + tag);
                }
            }
            int access = u2(b, p);
            p += 2;
            String self = utf8[cls[u2(b, p)]];
            p += 2;
            int superIdx = u2(b, p);
            p += 2;
            String sup = superIdx == 0 ? null : utf8[cls[superIdx]];
            int n = u2(b, p);
            p += 2;
            String[] itfs = new String[n];
            for (int i = 0; i < n; i++) {
                itfs[i] = utf8[cls[u2(b, p)]];
                p += 2;
            }
            return new Header(access, self, sup, itfs);
        }

        private static int u2(byte[] b, int p) {
            return ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
        }
    }
}
