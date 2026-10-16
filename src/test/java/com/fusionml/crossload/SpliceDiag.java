package com.fusionml.crossload;

import com.fusionml.bytecode.ClassFile;
import com.fusionml.mc.McInstance;
import com.fusionml.util.FastZip;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 定点排查"拼接出来的方法为什么索引不对"。
 *
 * <p>现象：{@code javap} 报 {@code Error: Bad CP index: 1033}。
 * 说明我们改写常量池索引时，在某个位置写进了不该写的值 ——
 * 通常是指令边界算错，把操作数当成了下一条指令。
 *
 * <p>做法：把源类那个方法的 Code 逐条打印（偏移 + 操作码 + 操作数），
 * 再把拼好的目标类里同名方法的 Code 也打印一遍，两边对着看是哪一步开始错位。
 */
public final class SpliceDiag {

    private SpliceDiag() {
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
        Path patchedJar = PatchedBase.locate(instance, "neoforge", PatchedBase.mcKeyOf(versionId));
        String internal = args.length > 0 ? args[0].replace('.', '/')
                : "net/minecraft/resources/RegistryDataLoader";
        String methodName = args.length > 1 ? args[1] : "load";

        try (FastZip vz = FastZip.open(vanillaJar); FastZip pz = FastZip.open(patchedJar)) {
            byte[] pb = pz.read(pz.entry(internal + ".class"));
            ClassFile p = ClassFile.read(pb);
            for (ClassFile.Member m : p.methods()) {
                String n = p.utf8(m.nameIndex);
                String d = p.utf8(m.descIndex);
                if (n == null || !n.equals(methodName) || !d.contains("Ljava/util/List;)")) {
                    continue;
                }
                System.out.println("=== 源方法 " + n + d);
                dumpCode(p, m);
            }
        }
    }

    /** 打印方法的 Code：偏移、操作码、以及前若干字节。 */
    private static void dumpCode(ClassFile cf, ClassFile.Member m) {
        for (ClassFile.Attr a : m.attrs) {
            if (!"Code".equals(cf.utf8(a.nameIndex()))) {
                continue;
            }
            byte[] all = cf.rawBytes();
            int start = a.offset();
            int codeLen = ClassFile.u4iPublic(all, start + 4);
            int codeStart = start + 8;
            System.out.println("  code_length=" + codeLen);
            int p = codeStart;
            int end = codeStart + codeLen;
            int shown = 0;
            while (p < end && shown < 60) {
                int op = all[p] & 0xFF;
                int len = opLen(op, all, p, end);
                StringBuilder sb = new StringBuilder();
                sb.append(String.format("  %4d: %02x", p - codeStart, op));
                for (int i = 1; i < len && p + i < end; i++) {
                    sb.append(String.format(" %02x", all[p + i] & 0xFF));
                }
                // 带双字节常量池索引的指令，把索引解出来看看
                Integer cpIdx = cpIndex(op, all, p);
                if (cpIdx != null) {
                    sb.append("   cp#").append(cpIdx).append(" = ").append(cpIdx > 0
                            && cpIdx < 4096 ? String.valueOf(cf.cp(cpIdx)) : "?");
                }
                System.out.println(sb);
                p += len;
                shown++;
            }
        }
    }

    private static Integer cpIndex(int op, byte[] b, int p) {
        switch (op) {
            case 0x12:
                return b[p + 1] & 0xFF;
            case 0x13: case 0xB2: case 0xB3: case 0xB4: case 0xB5:
            case 0xB6: case 0xB7: case 0xB8: case 0xBB: case 0xBD:
            case 0xC0: case 0xC1: case 0xB9: case 0xBA:
                return ((b[p + 1] & 0xFF) << 8) | (b[p + 2] & 0xFF);
            default:
                return null;
        }
    }

    /** 与 ConstantPoolTransplant 保持一致的指令长度判断（仅用于显示）。 */
    private static int opLen(int op, byte[] b, int p, int end) {
        switch (op) {
            case 0x12: return 2;
            case 0x13: case 0x11: case 0x14: case 0xB2: case 0xB3: case 0xB4: case 0xB5:
            case 0xB6: case 0xB7: case 0xB8: case 0xBB: case 0xBD: case 0xC0: case 0xC1:
            case 0xC6: case 0xC7: case 0x84: case 0xA9:
                return 3;
            case 0xB9: case 0xBA: return 5;
            case 0x10: case 0x15: case 0x16: case 0x17: case 0x18: case 0x19:
            case 0x36: case 0x37: case 0x38: case 0x39: case 0x3A: case 0xBC:
                return 2;
            case 0xC5: return 4;
            case 0xC8: case 0xC9: return 5;
            case 0xC4: return 4;
            default: return 1;
        }
    }
}
