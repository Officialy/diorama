package dev.diorama.gtnh.mixins.client;

import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.IResourceManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.diorama.gtnh.client.ClientCaptureLifecycle;

/** Both public entries are fenced, including missing-image/GL changes before loadTextureAtlas. */
@Mixin(TextureMap.class)
public abstract class BlockAtlasMixin {

    @Inject(method = "loadTexture(Lnet/minecraft/client/resources/IResourceManager;)V", at = @At("HEAD"), require = 1)
    private void diorama$beginTexture(IResourceManager manager, CallbackInfo ci) {
        ClientCaptureLifecycle.beginAtlas((TextureMap) (Object) this);
    }

    @Inject(
        method = "loadTextureAtlas(Lnet/minecraft/client/resources/IResourceManager;)V",
        at = @At("HEAD"),
        require = 1)
    private void diorama$beginAtlas(IResourceManager manager, CallbackInfo ci) {
        ClientCaptureLifecycle.beginAtlas((TextureMap) (Object) this);
    }

    @Inject(
        method = "loadTextureAtlas(Lnet/minecraft/client/resources/IResourceManager;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraftforge/client/ForgeHooksClient;onTextureStitchedPost(Lnet/minecraft/client/renderer/texture/TextureMap;)V",
            shift = At.Shift.AFTER,
            remap = false),
        require = 1)
    private void diorama$post(IResourceManager manager, CallbackInfo ci) {
        ClientCaptureLifecycle.atlasPosted((TextureMap) (Object) this);
    }

    @Inject(
        method = "loadTextureAtlas(Lnet/minecraft/client/resources/IResourceManager;)V",
        at = @At("RETURN"),
        require = 1)
    private void diorama$endAtlas(IResourceManager manager, CallbackInfo ci) {
        ClientCaptureLifecycle.endAtlas((TextureMap) (Object) this);
    }

    @Inject(method = "loadTexture(Lnet/minecraft/client/resources/IResourceManager;)V", at = @At("RETURN"), require = 1)
    private void diorama$endTexture(IResourceManager manager, CallbackInfo ci) {
        ClientCaptureLifecycle.endAtlas((TextureMap) (Object) this);
    }
}
