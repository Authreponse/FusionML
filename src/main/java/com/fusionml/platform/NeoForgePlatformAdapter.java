package com.fusionml.platform;

import com.fusionml.loader.ModClassIndex;
import com.fusionml.meta.ModFormat;
import com.fusionml.meta.ModMetadata;

import java.util.List;

/**
 * NeoForge 端原生适配器。
 *
 * <p>NeoForge 与 Forge 的分歧不只是改名：注解在 {@code net.neoforged.fml.common.Mod}，
 * 事件总线在 {@code net.neoforged.bus.api.IEventBus}，注入构造器的组合也不同。
 * 这里覆写注入类型表，而不是复用 Forge 的 —— 否则 NeoForge 模组会因为找不到
 * Forge 的总线类型而静默降级，那正是兼容层思想会造成的隐蔽问题。
 */
public final class NeoForgePlatformAdapter extends ForgePlatformAdapter {

    private static final String[][] NEO_INJECTION_TYPES = {
            {"net.neoforged.bus.api.IEventBus", "net.neoforged.fml.ModContainer"},
            {"net.neoforged.bus.api.IEventBus"},
    };

    @Override
    public ModFormat format() {
        return ModFormat.NEOFORGE;
    }

    @Override
    public List<String> locateMainClasses(ModMetadata mod, ModClassIndex.Result index,
                                          List<String> notes) {
        return locate(mod, index, ModFormat.NEOFORGE, notes);
    }

    @Override
    String[][] injectionTypes() {
        return NEO_INJECTION_TYPES;
    }
}
