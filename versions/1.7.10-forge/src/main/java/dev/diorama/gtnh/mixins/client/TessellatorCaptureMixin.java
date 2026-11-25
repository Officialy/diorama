package dev.diorama.gtnh.mixins.client;

import net.minecraft.client.renderer.Tessellator;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.diorama.gtnh.client.LegacyGeometryCapture;

/** Passive optional hook: no cancellation, buffer modification or extra drawing. */
@Mixin(Tessellator.class)
public abstract class TessellatorCaptureMixin {

    @Inject(method = "addVertexWithUV(DDDDD)V", at = @At("HEAD"), require = 0)
    private void diorama$vertex(double x, double y, double z, double u, double v, CallbackInfo ci) {
        LegacyGeometryCapture.vertex(x, y, z, u, v);
    }
}
