package dev.myriad.essentials.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.myriad.essentials.modules.render.NoRender;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
	/** No Render: drop the red hurt tint, keep the white creeper flash. */
	@ModifyReturnValue(method = "getOverlayCoords", at = @At("RETURN"))
	private static int essentials$noDamageTint(int original, LivingEntityRenderState state, float whiteOverlayProgress) {
		if (!NoRender.hides(n -> n.damageTint)) return original;
		return OverlayTexture.pack(OverlayTexture.u(whiteOverlayProgress), OverlayTexture.v(false));
	}
}
