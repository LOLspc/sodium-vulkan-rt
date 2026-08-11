package sodiumrt.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sodiumrt.SodiumRaytracingAddon;
import sodiumrt.client.SodiumRaytracingAddonClient;

@Pseudo
@Mixin(targets = {
    "me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildResult",
    "me.jellysquid.mods.sodium.client.render.chunk.data.ChunkRenderData",
    "me.jellysquid.mods.sodium.client.render.chunk.RenderSection",
    "net.caffeinemc.sodium.client.render.chunk.compile.ChunkBuildResult",
    "net.caffeinemc.sodium.client.render.chunk.compile.ChunkBuildOutput",
    "net.caffeinemc.sodium.client.render.chunk.data.ChunkRenderData",
    "net.caffeinemc.sodium.client.render.chunk.RenderSection",
    "net.caffeinemc.sodium.client.render.chunk.region.RenderRegion"
}, remap = false)
public class ChunkMeshVulkanBufferMixin {

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void onChunkMeshGenerated(CallbackInfo ci) {
        long meshHandle = System.identityHashCode(this) & 0xFFFFFFFFL;
        SodiumRaytracingAddonClient.registerBlasAddress(meshHandle);
        SodiumRaytracingAddon.LOGGER.info("[Sodium RT Addon] Chunk mesh detected - Registered BLAS (Total: " + SodiumRaytracingAddonClient.getBlasCount() + ")");
    }
}
