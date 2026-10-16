package com.fusionml.meta;

import java.util.Locale;

/**
 * 三种原生模组格式。
 *
 * <p>强调一次设计立场：本加载器不是"用一种格式去模拟另外两种"，
 * 而是为每一种格式各写一套原生读取与原生运行时契约。
 * 三者可以共存于同一个加载会话，也可以共存于同一个 jar（现实中的"多端同包"模组）。
 */
public enum ModFormat {
    /** Fabric：{@code fabric.mod.json} + 入口点 + Mixin 配置。 */
    FABRIC("fabric.mod.json", "Fabric", "Lnet/fabricmc/loader/api/ModInitializer;"),
    /** Forge：{@code META-INF/mods.toml} + {@code @Mod} + FML 事件总线。 */
    FORGE("META-INF/mods.toml", "Forge", "Lnet/minecraftforge/fml/common/Mod;"),
    /** NeoForge：{@code META-INF/neoforge.mods.toml} + {@code @Mod} + NeoForge 事件总线。 */
    NEOFORGE("META-INF/neoforge.mods.toml", "NeoForge", "Lnet/neoforged/fml/common/Mod;");

    private final String metadataPath;
    private final String displayName;
    private final String modAnnotationDesc;

    ModFormat(String metadataPath, String displayName, String modAnnotationDesc) {
        this.metadataPath = metadataPath;
        this.displayName = displayName;
        this.modAnnotationDesc = modAnnotationDesc;
    }

    public String metadataPath() {
        return metadataPath;
    }

    public String displayName() {
        return displayName;
    }

    /**
     * 该类格式用来标注"这是一个模组主类"的注解描述符。
     * Fabric 没有这个注解（它靠入口点声明），所以那里不会用到。
     */
    public String modAnnotationDesc() {
        return modAnnotationDesc;
    }

    public static ModFormat ofDisplayName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (ModFormat f : values()) {
            if (f.displayName.toLowerCase(Locale.ROOT).equals(n) || f.name().toLowerCase(Locale.ROOT).equals(n)) {
                return f;
            }
        }
        return null;
    }
}
