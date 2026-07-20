package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
// MC 1.21.1 NeoForge: Iris shader integration excluded
// import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.gl.device.RenderDevice;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class MixinDefaultChunkRenderer extends ShaderChunkRenderer {

    public MixinDefaultChunkRenderer(RenderDevice device, ChunkVertexType vertexType) {
        super(device, vertexType);
    }

    // render() gained a trailing boolean (indexed rendering) in Sodium 0.8.x:
    //   0.6.x: (ChunkRenderMatrices, CommandList, ChunkRenderListIterable, TerrainRenderPass, CameraTransform)
    //   0.8.x: (..., CameraTransform, boolean)
    // Each variant is declared with a FULL descriptor + require = 0, so on any given Sodium
    // version the non-matching pair finds no target rather than crashing the descriptor check.
    @Unique private static final String RENDER_LEGACY = "render(Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderMatrices;Lnet/caffeinemc/mods/sodium/client/gl/device/CommandList;Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/ChunkRenderListIterable;Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;Lnet/caffeinemc/mods/sodium/client/render/viewport/CameraTransform;)V";
    @Unique private static final String RENDER_INDEXED = "render(Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderMatrices;Lnet/caffeinemc/mods/sodium/client/gl/device/CommandList;Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/ChunkRenderListIterable;Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;Lnet/caffeinemc/mods/sodium/client/render/viewport/CameraTransform;Z)V";
    @Unique private static final String SHADER_END = "Lnet/caffeinemc/mods/sodium/client/render/chunk/ShaderChunkRenderer;end(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)V";

    @Inject(method = RENDER_LEGACY, at = @At(value = "HEAD"), cancellable = true, require = 0)
    private void cancelThingie(ChunkRenderMatrices matrices, CommandList commandList, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform camera, CallbackInfo ci) {
        voxy$cancelIfDisabled(matrices, renderPass, camera, ci);
    }

    @Inject(method = RENDER_INDEXED, at = @At(value = "HEAD"), cancellable = true, require = 0)
    private void cancelThingieIndexed(ChunkRenderMatrices matrices, CommandList commandList, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform camera, boolean indexedRendering, CallbackInfo ci) {
        voxy$cancelIfDisabled(matrices, renderPass, camera, ci);
    }

    @Inject(method = RENDER_LEGACY, at = @At(value = "INVOKE", target = SHADER_END, shift = At.Shift.BEFORE), require = 0)
    private void injectRender(ChunkRenderMatrices matrices, CommandList commandList, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform camera, CallbackInfo ci) {
        this.doRender(matrices, renderPass, camera);
    }

    @Inject(method = RENDER_INDEXED, at = @At(value = "INVOKE", target = SHADER_END, shift = At.Shift.BEFORE), require = 0)
    private void injectRenderIndexed(ChunkRenderMatrices matrices, CommandList commandList, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform camera, boolean indexedRendering, CallbackInfo ci) {
        this.doRender(matrices, renderPass, camera);
    }

    @Unique
    private void voxy$cancelIfDisabled(ChunkRenderMatrices matrices, TerrainRenderPass renderPass, CameraTransform camera, CallbackInfo ci) {
        if (VoxyClient.disableSodiumChunkRender()) {
            super.begin(renderPass);
            this.doRender(matrices, renderPass, camera);
            super.end(renderPass);
            ci.cancel();
        }
    }

    @Unique
    private void doRender(ChunkRenderMatrices matrices, TerrainRenderPass renderPass, CameraTransform camera) {
        if (renderPass == DefaultTerrainRenderPasses.CUTOUT) {
            var renderer = ((IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer).getVoxyRenderSystem();
            if (renderer != null) {
                Viewport<?> viewport = null;
                // MC 1.21.1 NeoForge: Iris shader integration excluded - irisShaderPackEnabled() returns false
                if (false) {
                    viewport = renderer.getViewport();
                } else {
                    // Sodium 0.6.x: setupViewport no longer takes FogParameters
                    viewport = renderer.setupViewport(matrices, camera.x, camera.y, camera.z);
                }
                renderer.renderOpaque(viewport);
            }
        }
    }
}
