package com.fusionml.crossload;

import com.fusionml.bytecode.ClassFile;
import com.fusionml.util.FastZip;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 看一个类"完整的样子"：字段、方法、常量池引用。
 *
 * <p>用途：做超类桥接（把对方超类的能力**组合进**原版类，而不是继承）之前，
 * 必须先看清两件事：
 * <ol>
 *   <li>对方超类（如 {@code AttachmentHolder}）到底带进来哪些字段与方法；</li>
 *   <li>对方的子类（如补丁版 {@code Level}）的 {@code <init>} 里，
 *       对超类构造器的 {@code invokespecial} 与字段初始化是怎么写的。</li>
 * </ol>
 * 不看就动手改字节，等于闭着眼睛改。
 *
 * <p>用法：{@code java -cp build\classes;build\test-classes com.fusionml.crossload.ClassShape
 * <内部名> [jar路径]}
 */
public final class ClassShape {

    private ClassShape() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("用法：ClassShape <内部名> [jar]");
            return;
        }
        String internal = args[0].replace('.', '/');
        Path jar = args.length > 1 ? Path.of(args[1]) : null;
        if (jar == null) {
            // 默认找 NeoForge 的 universal 包（对方超类住在这里）。
            Path libs = Path.of("D:\\我的世界\\服务器\\.minecraft\\libraries\\net\\neoforged\\neoforge");
            try (var s = Files.walk(libs, 3)) {
                jar = s.filter(p -> p.getFileName().toString().startsWith("neoforge-26.3"))
                        .filter(p -> p.getFileName().toString().endsWith("universal.jar"))
                        .findFirst().orElse(null);
            }
        }
        System.out.println("jar：" + jar);
        try (FastZip z = FastZip.open(jar)) {
            FastZip.Entry e = z.entry(internal + ".class");
            if (e == null) {
                System.out.println("包里没有 " + internal);
                return;
            }
            ClassFile cf = ClassFile.read(z.read(e));
            System.out.println("== " + internal);

            //（实测：attachments 的 nameIndex 读成 25966，而该 CP 只有 ~292 项）。
            int maxIdx = 0;
            for (int i = 1; i < 65536; i++) {
                if (cf.cp(i) != null) {
                    maxIdx = i;
                }
            }
            System.out.println("   常量池最大索引：" + maxIdx + "（javap 报告值应与之一致）");
            System.out.println("   超类：" + cf.superName());
            System.out.println("   接口：" + String.join("、", cf.interfaces()));
            System.out.println("   字段 " + cf.fields().length + " 个：");
            for (ClassFile.Member m : cf.fields()) {
                System.out.println("      " + access(m.access) + " " + cf.utf8(m.nameIndex)
                        + " : " + cf.utf8(m.descIndex));
            }
            System.out.println("   方法 " + cf.methods().length + " 个：");
            for (ClassFile.Member m : cf.methods()) {
                System.out.println("      " + access(m.access) + " " + cf.utf8(m.nameIndex)
                        + cf.utf8(m.descIndex));
            }
        }
    }

    private static String access(int a) {
        StringBuilder sb = new StringBuilder();
        if ((a & 0x0001) != 0) {
            sb.append("public ");
        }
        if ((a & 0x0002) != 0) {
            sb.append("private ");
        }
        if ((a & 0x0004) != 0) {
            sb.append("protected ");
        }
        if ((a & 0x0008) != 0) {
            sb.append("static ");
        }
        if ((a & 0x0010) != 0) {
            sb.append("final ");
        }
        if ((a & 0x0400) != 0) {
            sb.append("abstract ");
        }
        return sb.toString().trim();
    }
}
