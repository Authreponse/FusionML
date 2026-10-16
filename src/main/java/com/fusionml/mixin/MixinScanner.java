package com.fusionml.mixin;

import com.fusionml.bytecode.ClassFile;
import com.fusionml.bytecode.HookInjector;
import com.fusionml.loader.TransformPipeline;
import com.fusionml.util.FastZip;
import com.fusionml.util.Json;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 自研注入格式的扫描器：读 {@code fusion.mixins.json}，把 {@code @FusionMixin} 类里的
 * {@code @FusionHead} 方法编译成字节码注入规则。
 *
 * <p>为什么用"配置文件列出类名"而不是"扫描整包找注解"：
 * 前者只需为少数几个类解压并解析注解，后者要为包里每个类做一次全量扫描。
 * 这也是 Mixin 自己采用配置清单的原因 —— 我们沿用这个思路，但格式与实现完全自研。
 */
public final class MixinScanner {

    /** 本加载器原生注入配置的固定路径。 */
    public static final String CONFIG_PATH = "fusion.mixins.json";

    private MixinScanner() {
    }

    /**
     * @return 登记的注入规则条数
     */
    public static int collect(List<FastZip> jars, TransformPipeline pipeline, List<String> notes) {
        int rules = 0;
        for (FastZip zip : jars) {
            FastZip.Entry cfg = zip.entry(CONFIG_PATH);
            if (cfg == null) {
                continue;
            }
            Map<String, Object> root;
            try {
                root = Json.parseObject(zip.read(cfg));
            } catch (IOException | RuntimeException e) {
                notes.add(zip.path().getFileName() + "：" + CONFIG_PATH + " 解析失败 —— " + e.getMessage());
                continue;
            }
            String pkg = Json.str(root, "package", "");
            List<String> classNames = new ArrayList<>(4);
            for (String key : List.of("mixins", "client", "server")) {
                for (Object o : Json.arr(root.get(key))) {
                    classNames.add(o.toString());
                }
            }
            for (String className : classNames) {
                rules += collectOne(zip, pkg, className, pipeline, notes);
            }
        }
        return rules;
    }

    private static int collectOne(FastZip zip, String pkg, String simpleName,
                                  TransformPipeline pipeline, List<String> notes) {
        String entryName = (pkg.isEmpty() ? "" : pkg.replace('.', '/') + "/") + simpleName + ".class";
        FastZip.Entry entry = zip.entry(entryName);
        if (entry == null) {
            notes.add(zip.path().getFileName() + "：配置里列出的注入类 " + entryName + " 不存在");
            return 0;
        }
        byte[] bytes;
        try {
            bytes = zip.read(entry);
        } catch (IOException e) {
            notes.add(zip.path().getFileName() + "：读取 " + entryName + " 失败 —— " + e.getMessage());
            return 0;
        }
        ClassFile cf;
        try {
            cf = ClassFile.read(bytes);
        } catch (RuntimeException e) {
            notes.add(entryName + " 解析失败 —— " + e.getMessage());
            return 0;
        }
        ClassFile.Annotation mixinAnno = null;
        for (ClassFile.Annotation a : cf.classAnnotations()) {
            if ("Lcom/fusionml/mixin/FusionMixin;".equals(a.desc())) {
                mixinAnno = a;
                break;
            }
        }
        if (mixinAnno == null) {
            notes.add(cf.name() + " 在 " + CONFIG_PATH + " 里登记了，但类上没有 @FusionMixin，已跳过");
            return 0;
        }
        String target = mixinAnno.str("target", "");
        if (target.isBlank()) {
            notes.add(cf.name() + " 的 @FusionMixin 没有写 target，已跳过");
            return 0;
        }
        // 统一规范化：内部名里不可能出现 '.'（内部类用 '$'，数组用 '['），
        // 所以无论写的是 com.example.Foo 还是 com/example/Foo，把所有 '.' 换成 '/' 都正确。
        // 之前用"含斜杠就当成内部名、不再替换"的启发式，会漏掉 com/example.Foo 这种混写。
        String targetInternal = target.replace('.', '/');
        String mixinOwner = cf.name();
        int rules = 0;
        for (ClassFile.Member m : cf.methods()) {
            if (!"<init>".equals(cf.utf8(m.nameIndex)) && (m.access & 0x0008) != 0) {
                // 只接受静态钩子：实例钩子需要额外的对象生命周期管理，超出当前实现范围，
                // 直接明确跳过并说明，比"悄悄不生效"好得多。
                boolean headFound = false;
                for (ClassFile.Annotation a : cf.annotations(m.attrs)) {
                    if ("Lcom/fusionml/mixin/FusionHead;".equals(a.desc())) {
                        headFound = true;
                        String targetMethod = a.str("method", "");
                        String targetDesc = a.str("desc", "");
                        if (targetMethod.isBlank()) {
                            notes.add(mixinOwner + "#" + cf.utf8(m.nameIndex) + " 的 @FusionHead 缺少 method");
                            continue;
                        }
                        String hookDesc = cf.utf8(m.descIndex);
                        pipeline.addHook(new HookInjector.Hook(targetInternal, targetMethod,
                                targetDesc.isBlank() ? null : targetDesc,
                                mixinOwner, cf.utf8(m.nameIndex), hookDesc,
                                HookInjector.Point.HEAD));
                        rules++;
                    }
                }
                if (!headFound) {
                    notes.add(mixinOwner + "#" + cf.utf8(m.nameIndex)
                            + " 没有可识别的注入注解（当前原生支持 @FusionHead）");
                }
            } else if ((m.access & 0x0008) == 0 && !"<init>".equals(cf.utf8(m.nameIndex))
                    && !cf.annotations(m.attrs).isEmpty()) {
                notes.add(mixinOwner + "#" + cf.utf8(m.nameIndex)
                        + " 是实例方法钩子，当前只支持静态钩子，已跳过");
            }
        }
        return rules;
    }

}
