package dev.diorama.gtnh.mixins.client;

import net.minecraft.block.Block;
import net.minecraft.world.chunk.Chunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.diorama.gtnh.client.TerrainPreviewScreen;

/** Observe accepted writes, never renderer updates or neighbour notifications. */
@Mixin(Chunk.class)
public abstract class ChunkMutationMixin {

    // This unmapped MCP name is already the production SRG name.
    @Inject(method = "func_150807_a(IIILnet/minecraft/block/Block;I)Z", at = @At("RETURN"), remap = false)
    private void diorama$block(int x, int y, int z, Block block, int meta, CallbackInfoReturnable<Boolean> cir) {
        Chunk chunk = (Chunk) (Object) this;
        if (chunk.worldObj.isRemote && cir.getReturnValueZ()) {
            TerrainPreviewScreen.blockChanged(chunk.worldObj, chunk.xPosition, chunk.zPosition, x, y, z);
        }
    }

    @Inject(method = "setBlockMetadata(IIII)Z", at = @At("RETURN"))
    private void diorama$metadata(int x, int y, int z, int meta, CallbackInfoReturnable<Boolean> cir) {
        Chunk chunk = (Chunk) (Object) this;
        if (chunk.worldObj.isRemote && cir.getReturnValueZ()) {
            TerrainPreviewScreen.blockChanged(chunk.worldObj, chunk.xPosition, chunk.zPosition, x, y, z);
        }
    }

    // Forge's client Load event is posted when the provider inserts an empty shell, before the
    // packet fills blocks, biomes and light. Full and partial packet paths both finish here.
    @Inject(method = "fillChunk([BIIZ)V", at = @At("RETURN"))
    private void diorama$filled(byte[] data, int sections, int extra, boolean full, CallbackInfo ci) {
        Chunk chunk = (Chunk) (Object) this;
        if (chunk.worldObj.isRemote) {
            TerrainPreviewScreen.chunkFilled(chunk.worldObj, chunk.xPosition, chunk.zPosition, full);
        }
    }

    @Inject(method = "onChunkUnload()V", at = @At("RETURN"))
    private void diorama$unloaded(CallbackInfo ci) {
        Chunk chunk = (Chunk) (Object) this;
        if (chunk.worldObj.isRemote)
            TerrainPreviewScreen.chunkUnloaded(chunk.worldObj, chunk.xPosition, chunk.zPosition);
    }
}
