package dev.myriad.essentials.mixin;

import dev.myriad.essentials.util.GlideHold;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientCommonPacketListenerMixin {
	/** Glide Hold: answers to the server's pings may be held back. Only on the game thread, where they're answered. */
	@Inject(method = "handlePing", at = @At("HEAD"), cancellable = true)
	private void essentials$holdPing(ClientboundPingPacket packet, CallbackInfo ci) {
		if (Minecraft.getInstance().isSameThread() && GlideHold.holdAnswer(packet.getId())) ci.cancel();
	}
}
