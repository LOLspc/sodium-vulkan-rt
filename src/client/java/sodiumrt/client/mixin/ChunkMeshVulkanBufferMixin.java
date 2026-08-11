package sodiumrt.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildResult", remap = false)
public class ChunkMeshVulkanBufferMixin {

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onChunkMeshGenerated(CallbackInfo ci) {
        // Dynamic BLAS acceleration structure update trigger when sodium compiles a chunk section mesh
    }
}
