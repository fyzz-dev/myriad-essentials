package dev.myriad.essentials.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.myriad.essentials.modules.render.NoRender;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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

	/** No Render Nausea: the screen warp reads nausea's strength here; report none. */
	@WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getEffectBlendFactor(Lnet/minecraft/core/Holder;F)F"))
	private float essentials$nausea(LocalPlayer player, Holder<MobEffect> effect, float partialTicks, Operation<Float> original) {
		if (effect == MobEffects.NAUSEA && NoRender.hides(n -> n.nausea)) return 0;
		return original.call(player, effect, partialTicks);
	}
}
