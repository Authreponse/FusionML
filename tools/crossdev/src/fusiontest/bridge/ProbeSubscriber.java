package fusiontest.bridge;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * 跨端驱动验证夹具：静态事件订阅类。
 *
 * <p>它验证的是"事件真的投递到了对方平台的处理器上"，而不只是"类被加载了"。
 */
@EventBusSubscriber(modid = ProbeMod.MOD_ID)
public final class ProbeSubscriber {

    private ProbeSubscriber() {
    }

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        System.out.println("[FusionML/跨端探针] 静态订阅收到 FMLCommonSetupEvent：" + ProbeMod.report());
    }
}
