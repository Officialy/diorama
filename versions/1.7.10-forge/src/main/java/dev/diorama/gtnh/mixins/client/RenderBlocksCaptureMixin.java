package dev.diorama.gtnh.mixins.client;

import net.minecraft.block.Block;
import net.minecraft.client.renderer.RenderBlocks;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.diorama.gtnh.client.LegacyGeometryCapture;

/** Observe an actual legacy world-render pass, not a second execution of the block renderer. */
@Mixin(RenderBlocks.class)
public abstract class RenderBlocksCaptureMixin {

    @Inject(method = "renderBlockByRenderType(Lnet/minecraft/block/Block;III)Z", at = @At("HEAD"), require = 0)
    private void diorama$begin(Block block, int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
        LegacyGeometryCapture.begin(block, ((RenderBlocks) (Object) this).blockAccess, x, y, z);
    }

    @Inject(method = "renderBlockByRenderType(Lnet/minecraft/block/Block;III)Z", at = @At("RETURN"), require = 0)
    private void diorama$end(Block block, int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
        LegacyGeometryCapture.end(block, x, y, z, cir.getReturnValueZ());
    }
}
