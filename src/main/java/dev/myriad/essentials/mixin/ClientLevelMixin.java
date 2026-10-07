package dev.myriad.essentials.mixin;

import dev.myriad.essentials.modules.render.NoRender;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
	/** No Render weather also stops rain splashing on the ground. */
	@Inject(method = "tickWeatherEffects", at = @At("HEAD"), cancellable = true)
	private void essentials$noWeatherParticles(CallbackInfo ci) {
		if (NoRender.hides(n -> n.weather)) ci.cancel();
	}
}
