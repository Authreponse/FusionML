package com.fusionml.launch;

import com.fusionml.mc.McRules;
import com.fusionml.mc.McVersion;
import com.fusionml.util.FastZip;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 原生库解压。
 *
 * <p>LWJGL 这类库把 {@code .dll/.so/.dylib} 打包在 jar 里，必须在启动前解压到一个目录，
 * 再交给 {@code -Djava.library.path}。要点：
 * <ul>
 *   <li>只解出当前系统需要的那一类（{@code natives-windows} / {@code natives-linux} / {@code natives-macos}）；</li>
 *   <li>已经存在且大小一致就跳过 —— 每次启动重解几 MB 是纯浪费；</li>
 *   <li>用自研的 {@link FastZip} 读取，不引入任何额外依赖。</li>
 * </ul>
 */
public final class NativeExtractor {

    private NativeExtractor() {
    }

    /** 当前系统在 natives 映射里对应的分类键。 */
    public static String currentClassifier() {
        return switch (McRules.currentOsName()) {
            case "windows" -> "windows";
            case "osx" -> "macos";
            default -> "linux";
        };
    }

    /**
     * 解压全部适用的原生库。
     *
     * @return 本次新解压出的文件数（已存在的计入跳过）
     */
    public static int extract(McInstanceView view, Path nativesDir) throws IOException {
        Files.createDirectories(nativesDir);
        String classifier = currentClassifier();
        int extracted = 0;
        for (McVersion.Library lib : view.libraries()) {
            if (lib.natives().isEmpty() || !lib.natives().containsKey(classifier)) {
                continue;
            }
            Path jar = view.localPath(lib);
            if (jar == null || !Files.isRegularFile(jar)) {
                continue;
            }
            try (FastZip zip = FastZip.open(jar)) {
                for (FastZip.Entry e : zip.entries()) {
                    if (e.isDirectory()) {
                        continue;
                    }
                    String lower = e.name().toLowerCase(Locale.ROOT);
                    if (!isNativeFile(lower)) {
                        continue;
                    }
                    Path target = nativesDir.resolve(fileName(e.name()));
                    if (Files.isRegularFile(target) && Files.size(target) == e.size()) {
                        continue;
                    }
                    Files.write(target, zip.read(e));
                    extracted++;
                }
            } catch (IOException ex) {
                // 单个库解压失败不该让启动整体失败：先把能解的解出来，
                // 真缺了会在游戏启动时报出更具体的错误，比在这里吞掉一个笼统异常有用。
            }
        }
        return extracted;
    }

    private static boolean isNativeFile(String lowerName) {
        return lowerName.endsWith(".dll") || lowerName.endsWith(".so")
                || lowerName.endsWith(".dylib") || lowerName.endsWith(".jnilib");
    }

    private static String fileName(String entryName) {
        int slash = entryName.lastIndexOf('/');
        return slash < 0 ? entryName : entryName.substring(slash + 1);
    }

    /** 解压所需的最小视图，避免 NativeExtractor 依赖具体的 McInstance 实现。 */
    public interface McInstanceView {
        List<McVersion.Library> libraries();

        Path localPath(McVersion.Library lib);
    }
}
