package dev.myriad.essentials.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.myriad.essentials.modules.render.NoRender;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ScreenEffectRenderer.class)
public abstract class InGameOverlayRendererMixin {
	@Inject(method = "submitFire", at = @At("HEAD"), cancellable = true)
	private static void essentials$fire(PoseStack poseStack, SubmitNodeCollector collector, TextureAtlasSprite sprite, CallbackInfo ci) {
		if (NoRender.hides(n -> n.fire)) ci.cancel();
	}

	@Inject(method = "submitBlockSprite", at = @At("HEAD"), cancellable = true)
	private static void essentials$inWall(TextureAtlasSprite sprite, PoseStack poseStack, SubmitNodeCollector collector, int color, CallbackInfo ci) {
		if (NoRender.hides(n -> n.blockOverlay)) ci.cancel();
	}

	@Inject(method = "submitWater", at = @At("HEAD"), cancellable = true)
	private static void essentials$underwater(Minecraft minecraft, PoseStack poseStack, SubmitNodeCollector collector, CallbackInfo ci) {
		if (NoRender.hides(n -> n.liquidOverlay)) ci.cancel();
	}
}
