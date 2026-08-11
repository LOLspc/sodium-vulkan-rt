package sodiumrt.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Dummy class to hook into Sodium's backend renderer initialization
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer", remap = false)
public class SodiumVulkanPipelineMixin {

    @Inject(method = "loadWorld", at = @At("TAIL"))
    private void onWorldLoad(CallbackInfo ci) {
        System.out.println("[Sodium RT Addon] Initialized Vulkan Raytracing Pipeline on Sodium Render World load.");
    }
}
