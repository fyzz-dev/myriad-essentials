package dev.myriad.essentials.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.myriad.api.module.Modules;
import dev.myriad.essentials.modules.render.FullBright;
import dev.myriad.essentials.modules.render.NoRender;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
	private void essentials$hurtCam(CameraRenderState cameraState, PoseStack poseStack, CallbackInfo ci) {
		if (NoRender.hides(n -> n.hurtCam)) ci.cancel();
	}

	@Inject(method = "displayItemActivation", at = @At("HEAD"), cancellable = true)
	private void essentials$totem(ItemStack stack, CallbackInfo ci) {
		if (stack.is(Items.TOTEM_OF_UNDYING) && NoRender.hides(n -> n.totem)) ci.cancel();
	}

	/**
	 * Full Bright under a shader pack: Iris hands packs this as their {@code nightVision} uniform (which they brighten
	 * dark places by) without checking for the effect, while vanilla only asks with night vision on. So shader packs see
	 * full night vision and vanilla's look is unchanged.
	 */
	@Inject(method = "nightVisionScale", at = @At("HEAD"), cancellable = true)
	private static void essentials$fullBright(LivingEntity entity, float partialTicks, CallbackInfoReturnable<Float> cir) {
		if (Modules.active(FullBright.class) != null) cir.setReturnValue(1f);
	}

	/** No Render Nausea: the screen warp reads nausea's strength here; report none. */
	@WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getEffectBlendFactor(Lnet/minecraft/core/Holder;F)F"))
	private float essentials$nausea(LocalPlayer player, Holder<MobEffect> effect, float partialTicks, Operation<Float> original) {
		if (effect == MobEffects.NAUSEA && NoRender.hides(n -> n.nausea)) return 0;
		return original.call(player, effect, partialTicks);
	}

	/**
	 * No Render Portal Overlay: walking through a portal warps the screen the way nausea does, from the portal's own
	 * strength (the HUD only draws the purple overlay); report none.
	 */
	@ModifyExpressionValue(method = "renderLevel", at = {
		@At(value = "FIELD", target = "Lnet/minecraft/client/player/LocalPlayer;portalEffectIntensity:F", opcode = Opcodes.GETFIELD),
		@At(value = "FIELD", target = "Lnet/minecraft/client/player/LocalPlayer;oPortalEffectIntensity:F", opcode = Opcodes.GETFIELD)})
	private float essentials$portalWarp(float intensity) {
		return NoRender.hides(n -> n.portal) ? 0 : intensity;
	}
}
