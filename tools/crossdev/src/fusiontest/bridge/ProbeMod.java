package fusiontest.bridge;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 跨端驱动验证夹具：一个与真实 NeoForge 模组<b>完全同形</b>的入口点。
 *
 * <p>它做三件真实模组都会做的事：
 * <ol>
 *   <li>用 {@code @Mod} 声明自己（构造器形态取现代 NeoForge 常见的
 *       {@code (IEventBus, ModContainer)}）；</li>
 *   <li>建一个 {@code DeferredRegister.Items} 并挂到模组总线上，
 *       注册一个真实的物品 —— 条目是否真的进了原版注册表，日志里能看到；</li>
 *   <li>用一个 {@code @EventBusSubscriber} 静态订阅类接生命周期事件。</li>
 * </ol>
 *
 * <p>这三步全部走的是 NeoForge 自己的 API 类，所以这份日志证明的不是
 * "我们仿了一套 NeoForge 语义"，而是"对方的 API 在宿主进程里真的跑起来了"。
 */
@Mod(ProbeMod.MOD_ID)
public class ProbeMod {

    public static final String MOD_ID = "fusionbridgeprobe";
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    public static final DeferredItem<Item> PROBE_ITEM =
            ITEMS.registerSimpleItem("probe_item");

    public ProbeMod(IEventBus modBus, ModContainer container) {
        System.out.println("[FusionML/跨端探针] @Mod 构造器被调用："
                + container.getModId() + " / " + container.getModInfo().getVersion());
        ITEMS.register(modBus);
    }

    /** 给自检用：注册表里到底有没有这个条目。 */
    public static String report() {
        var key = PROBE_ITEM.getKey();
        Object found = BuiltInRegistries.ITEM.getValue(key);
        return "物品 " + key + " 在 ITEM 注册表中=" + (found != null)
                + "，ITEM 注册表条目数=" + BuiltInRegistries.ITEM.size();
    }
}
