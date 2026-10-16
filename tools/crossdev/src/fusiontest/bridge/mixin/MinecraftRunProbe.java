package fusiontest.bridge.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 跨端验证夹具：一个用<b>官方 Mixin 注解</b>写的混入类，格式与真实 NeoForge 模组完全一致。
 *
 * <p>它的唯一作用是把"跨端模组在同一个进程里真的生效了"变成一行看得见的日志。
 * 这不是我们自己的注入引擎（那是 {@code com.fusionml.bytecode}），
 * 而是宿主平台内嵌的那份官方 Mixin —— 也就是说：这条日志一旦出现，
 * 就证明那个放在 mods 目录里的非宿主平台模组包，其类已经进了同一个类加载器、
 * 其 Mixin 配置也已经进了同一个 Mixin 服务。
 */
@Mixin(targets = "net.minecraft.client.Minecraft")
public class MinecraftRunProbe {

    @Inject(method = "run", at = @At("HEAD"))
    private void fusionml$crossPlatformProbe(CallbackInfo ci) {
        System.out.println("[FusionML/跨端探针] NeoForge 侧模组的 Mixin 已在同一进程内生效");
    }
}
