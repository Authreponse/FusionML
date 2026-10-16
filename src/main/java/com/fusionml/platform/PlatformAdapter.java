package com.fusionml.platform;

import com.fusionml.loader.ModClassIndex;
import com.fusionml.loader.ModClassLoader;
import com.fusionml.meta.ModFormat;
import com.fusionml.meta.ModMetadata;

import java.util.List;

/**
 * 平台原生适配器。
 *
 * <p>这是"三端原生"的落点：每个适配器按<b>自己那一端</b>的真实规则去定位主类、去注册。
 * 没有任何"把 A 端的调用翻译成 B 端的调用"的中间层 —— 那才是兼容层，而这里禁止兼容层。
 *
 * <p>三者的差异是本质性的，不是配置差异：
 * <ul>
 *   <li>Fabric 靠元数据里声明的入口点，主类无需任何注解；</li>
 *   <li>Forge 靠全包扫描 {@code @Mod} 注解，主类由注解标记；</li>
 *   <li>NeoForge 同样用注解，但注解所在包名与事件总线类型都换了一套。</li>
 * </ul>
 * 因此三个实现各有各的判定代码，而不是共享一个"通用模组加载器"。
 */
public interface PlatformAdapter {

    ModFormat format();

    /**
     * 这个模组在本端是否需要注册。
     *
     * <p>默认需要；纯库模组可以返回 false，表示"没有入口点很正常"。
     * 不区分这一点的话，200 个模组的整合包里会出现上百条"未定位到主类"，
     * 把真正的那几条失败彻底埋掉。
     */
    default boolean expectsRegistration(ModMetadata mod) {
        return true;
    }

    /**
     * 定位该模组在"本端"的主类。
     *
     * @param notes 输出参数：把定位过程中的说明写进来（例如"入口点是客户端专属，服务端跳过"）
     */
    List<String> locateMainClasses(ModMetadata mod, ModClassIndex.Result index, List<String> notes);

    /**
     * 执行本端的原生注册流程。
     *
     * @return 每个主类的注册结果描述（成功或失败原因）
     */
    List<String> register(ModMetadata mod, List<String> mainClasses, ModClassLoader loader,
                          boolean client, List<String> notes);
}
