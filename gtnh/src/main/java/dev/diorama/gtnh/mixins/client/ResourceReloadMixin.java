package dev.diorama.gtnh.mixins.client;

import java.util.List;

import net.minecraft.client.resources.IResourcePack;
import net.minecraft.client.resources.SimpleReloadableResourceManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.diorama.gtnh.client.ClientCaptureLifecycle;

/** Fail-closed HEAD/normal-RETURN fence, not an exception/finally wrapper. */
@Mixin(SimpleReloadableResourceManager.class)
public abstract class ResourceReloadMixin {

    @Inject(method = "reloadResources(Ljava/util/List;)V", at = @At("HEAD"), require = 1)
    private void diorama$begin(List<IResourcePack> packs, CallbackInfo ci) {
        ClientCaptureLifecycle.beginReload((SimpleReloadableResourceManager) (Object) this);
    }

    @Inject(
        method = "reloadResourcePack(Lnet/minecraft/client/resources/IResourcePack;)V",
        at = @At("HEAD"),
        require = 1)
    private void diorama$mutated(IResourcePack pack, CallbackInfo ci) {
        ClientCaptureLifecycle.resourcePackChanged((SimpleReloadableResourceManager) (Object) this);
    }

    @Inject(method = "reloadResources(Ljava/util/List;)V", at = @At("RETURN"), require = 1)
    private void diorama$end(List<IResourcePack> packs, CallbackInfo ci) {
        ClientCaptureLifecycle.endReload((SimpleReloadableResourceManager) (Object) this);
    }
}
