package sodiumrt.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sodiumrt.SodiumRaytracingAddon;

@Pseudo
@Mixin(targets = {
    "me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer",
    "net.caffeinemc.sodium.client.render.SodiumWorldRenderer"
}, remap = false)
public class SodiumVulkanPipelineMixin {

    @Inject(method = "loadWorld", at = @At("TAIL"), require = 0)
    private void onWorldLoad(CallbackInfo ci) {
        SodiumRaytracingAddon.LOGGER.info("[Sodium RT Addon] Initialized Vulkan Raytracing Pipeline on Sodium Render World load.");
    }
}
