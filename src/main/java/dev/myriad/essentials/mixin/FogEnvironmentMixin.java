package dev.myriad.essentials.mixin;

import dev.myriad.essentials.modules.render.NoRender;
import net.minecraft.client.renderer.fog.environment.BlindnessFogEnvironment;
import net.minecraft.client.renderer.fog.environment.DarknessFogEnvironment;
import net.minecraft.client.renderer.fog.environment.MobEffectFogEnvironment;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No Render Blindness and Darkness: their fog (and sky darkening) doesn't apply. */
@Mixin(MobEffectFogEnvironment.class)
public abstract class FogEnvironmentMixin {
	@Inject(method = "isApplicable", at = @At("HEAD"), cancellable = true)
	private void essentials$effectFog(FogType fogType, Entity entity, CallbackInfoReturnable<Boolean> cir) {
		Object self = this;
		if (self instanceof BlindnessFogEnvironment && NoRender.hides(n -> n.blindness)
			|| self instanceof DarknessFogEnvironment && NoRender.hides(n -> n.darkness)) cir.setReturnValue(false);
	}
}
