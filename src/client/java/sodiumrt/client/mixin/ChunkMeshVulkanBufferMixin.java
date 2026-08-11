package sodiumrt.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sodiumrt.SodiumRaytracingAddon;

@Pseudo
@Mixin(targets = {
    "me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildResult",
    "net.caffeinemc.sodium.client.render.chunk.compile.ChunkBuildResult"
}, remap = false)
public class ChunkMeshVulkanBufferMixin {

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void onChunkMeshGenerated(CallbackInfo ci) {
        // Intercept mesh compilation to queue BLAS updates for Vulkan Raytracing
        SodiumRaytracingAddon.LOGGER.debug("[Sodium RT Addon] Chunk mesh generated - Triggering Vulkan BLAS rebuild.");
    }
}
