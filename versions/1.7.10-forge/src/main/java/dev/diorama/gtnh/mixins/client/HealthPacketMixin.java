package dev.diorama.gtnh.mixins.client;

import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.server.S06PacketUpdateHealth;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.diorama.gtnh.client.ClientProxy;

/** Observe the server health update after vanilla applies it, including short death transitions. */
@Mixin(NetHandlerPlayClient.class)
public abstract class HealthPacketMixin {

    @Inject(method = "handleUpdateHealth(Lnet/minecraft/network/play/server/S06PacketUpdateHealth;)V",
        at = @At("RETURN"), require = 0)
    private void diorama$healthApplied(S06PacketUpdateHealth packet, CallbackInfo ci) {
        ClientProxy.healthPacketApplied();
    }
}
