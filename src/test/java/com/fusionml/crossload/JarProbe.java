package com.fusionml.crossload;

import com.fusionml.FusionVersions;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 验证"分版本 jar"的出厂设定在每个 jar 里都能被正确读出来。
 *
 * <p>为什么值得单测一遍：这些设定（目标版本、Java 要求）是<b>写在 jar 清单里</b>的，
 * 而清单是构建脚本拼出来的字符串 —— 拼错一个空格、属性名大小写不对，
 * 都只会表现为"读不到、悄悄回退到通用默认值"，而不会报错。
 * 那种失败很难发现（因为加载器照样能跑，只是用了错的 Java 要求）。
 *
 * <p>用法：{@code java -cp build\classes;build\test-classes com.fusionml.crossload.JarProbe}
 */
public final class JarProbe {

    private JarProbe() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 出厂设定表（FusionVersions）===");
        for (FusionVersions.Target t : FusionVersions.all()) {
            System.out.println("  " + t.id() + " | Java " + t.requiredJava()
                    + " | " + t.platform()
                    + " | " + (t.deobfuscated() ? "不混淆" : "混淆")
                    + " | " + t.displayName());
        }

        System.out.println();
        System.out.println("=== 各 jar 里读到的目标（用独立进程，避免本进程的清单干扰）===");
        String java = System.getProperty("fusion.javaExe",
                "C:\\PROGRA~1\\Zulu\\zulu-25\\bin\\java.exe");
        for (FusionVersions.Target t : FusionVersions.all()) {
            Path jar = Path.of("build", "fusion-ml-" + t.id() + ".jar");
            if (!Files.isRegularFile(jar)) {
                System.out.println("  " + t.id() + " → jar 不存在：" + jar);
                continue;
            }
            // 让 jar 自己打印它读到的出厂设定：这能验证"清单属性"真的写进去了，
            // 而不是只验证我们内存里的那张表。
            ProcessBuilder pb = new ProcessBuilder(java,
                    "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
                    "-cp", jar.toAbsolutePath().toString(),
                    "com.fusionml.FusionVersions");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            byte[] raw = p.getInputStream().readAllBytes();
            String out = new String(raw, "UTF-8").trim();
            int code = p.waitFor();
            System.out.println("  " + t.id() + " → " + (code == 0 ? out : "退出码 " + code + "：" + out));
        }
    }
}
