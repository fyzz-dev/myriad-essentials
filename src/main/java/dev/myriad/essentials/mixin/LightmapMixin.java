package dev.myriad.essentials.mixin;

import dev.myriad.api.module.Modules;
import dev.myriad.essentials.modules.render.FullBright;
import dev.myriad.essentials.modules.render.NoRender;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Full Bright's gamma mode: the lightmap's brightness, past the video setting's maximum. No Render Darkness: no
 * pulsing dimness from the darkness effect.
 */
@Mixin(LightmapRenderStateExtractor.class)
public abstract class LightmapMixin {
	@Inject(method = "extract", at = @At("TAIL"))
	private void essentials$fullBright(LightmapRenderState state, float partialTicks, CallbackInfo ci) {
		if (!state.needsUpdate) return;
		if (NoRender.hides(n -> n.darkness)) {
			state.darknessEffectScale = 0;
			state.brightness = Minecraft.getInstance().options.gamma().get().floatValue();
		}
		FullBright fb = Modules.active(FullBright.class);
		if (fb != null && fb.gamma()) state.brightness = FullBright.GAMMA;
	}
}
