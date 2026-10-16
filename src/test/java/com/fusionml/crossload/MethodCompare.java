package com.fusionml.crossload;

import com.fusionml.bytecode.ClassFile;
import com.fusionml.mc.McInstance;
import com.fusionml.util.FastZip;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 列出某个类在两份 jar 里的**同名方法**（按名字配对，不比描述符），
 * 用来判断"对方平台是改了签名，还是删了方法"。
 *
 * <p>起因：{@code RegistryDataLoader} 的 {@code load(...)} 在原版与补丁里描述符不同，
 * 而宿主平台的 Mixin 是按**原版描述符**去找注入点的。究竟是"参数类型变了"还是
 * "方法被搬到别处"，直接决定了能不能用一个类同时满足两边。
 *
 * <p>用法：{@code java -cp build\classes;build\test-classes com.fusionml.crossload.MethodCompare
 * net/minecraft/resources/RegistryDataLoader load}
 */
public final class MethodCompare {

    private MethodCompare() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("用法：MethodCompare <内部名> [方法名过滤]");
            return;
        }
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
        String internal = args[0].replace('.', '/');
        String filter = args.length > 1 ? args[1] : "";

        try (FastZip vz = FastZip.open(vanillaJar); FastZip pz = FastZip.open(patchedJar)) {
            FastZip.Entry ve = vz.entry(internal + ".class");
            FastZip.Entry pe = pz.entry(internal + ".class");
            if (ve == null || pe == null) {
                System.out.println("原版有=" + (ve != null) + "，补丁有=" + (pe != null));
                return;
            }
            ClassFile v = ClassFile.read(vz.read(ve));
            ClassFile p = ClassFile.read(pz.read(pe));
            System.out.println("=== 原版 ===");
            dump(v, filter);
            System.out.println("=== 补丁 ===");
            dump(p, filter);
        }
    }

    private static void dump(ClassFile cf, String filter) {
        for (ClassFile.Member m : cf.methods()) {
            String n = cf.utf8(m.nameIndex);
            if (n == null || (!filter.isEmpty() && !n.startsWith(filter))) {
                continue;
            }
            System.out.println("  " + n + cf.utf8(m.descIndex));
        }
    }
}
