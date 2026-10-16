package com.fusionml.crossload;

import com.fusionml.bytecode.ClassFile;
import com.fusionml.mc.McInstance;
import com.fusionml.util.FastZip;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 查"原版自己的内部类有没有调用被保留类的接口新增方法"。
 *
 * <p>起因：{@code Holder} 被保留原版，而 {@code Holder$Reference} 调
 * {@code Holder.getKey()}（对方通过新增接口提供的 default 方法）→
 * {@code NoSuchMethodError}，启动即崩。
 *
 * <p>这个工具把 {@code Holder$*} 里的方法引用全列出来，看判据为什么没命中。
 */
public final class InnerCallProbe {

    private InnerCallProbe() {
    }

    public static void main(String[] args) throws Exception {
        Path gameDir = null;
        for (String line : Files.readAllLines(Path.of("build", "gamedir.txt"),
                java.nio.charset.StandardCharsets.UTF_8)) {
            String s = line.trim();
            if (!s.isEmpty() && !s.startsWith("#")) {
                gameDir = Path.of(s);
                break;
            }
        }
        McInstance instance = McInstance.open(gameDir);
        String versionId = System.getProperty("fusion.version", "26.3-Fabric 0.19.5");
        Path vanillaJar = instance.resolve(versionId).clientJar;
        String target = args.length > 0 ? args[0].replace('.', '/') : "net/minecraft/core/Holder";
        System.out.println("原版包：" + vanillaJar.getFileName());
        System.out.println("目标类：" + target);

        String prefix = target + "$";
        try (FastZip z = FastZip.open(vanillaJar)) {
            for (FastZip.Entry e : z.entries()) {
                if (!e.isClass() || e.isDirectory() || !e.name().startsWith(prefix)) {
                    continue;
                }
                ClassFile cf = ClassFile.read(z.read(e));
                System.out.println("== " + e.name() + "（thisName=" + cf.thisName() + "）");

                for (String line : cf.methodReferences()) {
                    System.out.println("     " + line);
                }
            }
        }
    }
}
