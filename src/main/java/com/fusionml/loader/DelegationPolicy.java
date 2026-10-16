package com.fusionml.loader;

import java.util.List;

// 类委派表：决定一个类"交给父加载器"还是"由我们自己定义"。
// 三端运行时（Fabric Loader、NeoForge/Forge 的 ModLauncher）都想独占"定义游戏类"这件事；
// 而同一份补丁基底在同一个加载器命名空间里只能有一份。所以必须显式划清：
//   * 加载器侧（平台运行时代码、ASM、Mixin、日志库…）→ 交给父加载器，全进程只有一份；
//     放两份的后果很隐蔽：同一个类被两个加载器各定义一次，表现为这些库自己类之间的
//     ClassCastException（我们在接入 Fabric 时正好撞过一次 log4j 的实例）。
//   * 游戏与模组 → 由我们自己 defineClass，这样字节码变换（含未来自有 Mixin 服务）
// 判定用包前缀而不是硬编码类列表：换游戏版本、换平台版本都不需要改这段代码。
public final class DelegationPolicy {

    /** 必须由父加载器提供的包前缀。 */
    private static final List<String> PARENT_FIRST = List.of(

            "java.", "javax.", "jdk.", "sun.", "com.sun.", "org.w3c.dom.", "org.xml.sax.",
            // 字节码与 Mixin 基础设施：它们是"加载器的工具"，不是游戏的
            "org.objectweb.asm.", "org.spongepowered.asm.", "org.spongepowered.tools.",
            "net.fabricmc.loader.", "net.fabricmc.mappingio.", "net.fabricmc.accesswidener.",
            "net.fabricmc.tinyremapper.", "net.fabricmc.mixin.",
            // 日志与通用库（平台引导阶段就要用，必须单份）
            "org.apache.logging.log4j.", "org.slf4j.", "com.google.gson.", "com.google.common.",
            "com.google.j2objc.", "org.apache.commons.lang3.", "org.apache.commons.io.",
            "it.unimi.dsi.fastutil.", "com.mojang.blaze3d.", "com.mojang.jtracy.",
            "com.mojang.logging.", "com.mojang.brigadier.", "com.mojang.datafixers.",
            "com.mojang.serialization.", "com.mojang.authlib.", "com.mojang.realmsclient.",
            // 平台运行时（NeoForge / Forge / ModLauncher 栈）
            "net.neoforged.", "net.minecraftforge.", "cpw.mods.", "net.minecraftforgeapi.");

    private static final List<String> SELF_FIRST = List.of(
            "net.minecraft.", "net.minecraftforge.client.", "com.mojang.math.");

    private DelegationPolicy() {
    }

    /**
     * 是否交给父加载器。
     *
     * <p>顺序上"先判必须自留的，再判必须委派的"，最后默认自留：
     * 游戏与模组代码是绝大多数，默认自留既省判断也让变换点唯一。
     */
    public static boolean parentFirst(String className) {
        for (String prefix : SELF_FIRST) {
            if (className.startsWith(prefix)) {
                return false;
            }
        }
        for (String prefix : PARENT_FIRST) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public static String describe() {
        return "委派给父加载器的包：" + PARENT_FIRST.size() + " 组前缀；"
                + "强制自留的包：" + SELF_FIRST.size() + " 组前缀；其余默认自留";
    }
}
