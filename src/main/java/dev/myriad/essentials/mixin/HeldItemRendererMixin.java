package dev.myriad.essentials.mixin;

import dev.myriad.api.module.Modules;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.myriad.essentials.modules.render.ViewModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ItemInHandRenderer.class)
public abstract class HeldItemRendererMixin {
	@Shadow
	private ItemStack mainHandItem;
	@Shadow
	private ItemStack offHandItem;
	@Shadow
	private float mainHandHeight;
	@Shadow
	private float oMainHandHeight;
	@Shadow
	private float offHandHeight;
	@Shadow
	private float oOffHandHeight;
	@Shadow
	@Final
	private Minecraft minecraft;

	/** View Model No Switch Animation: no dip when switching items; show the new item straight away. */
	@Inject(method = "tick", at = @At("TAIL"))
	private void essentials$noSwitchAnimation(CallbackInfo ci) {
		if (!ViewModel.noSwitchAnimation() || minecraft.player == null) return;
		mainHandItem = minecraft.player.getMainHandItem();
		offHandItem = minecraft.player.getOffhandItem();
		mainHandHeight = oMainHandHeight = 1;
		offHandHeight = oOffHandHeight = 1;
	}

	private static final String RENDER_ITEM = "submitHandsWithItems";

	/** The first two rotations in submitHandsWithItems are the camera sway (pitch, then yaw). */
	@ModifyExpressionValue(method = RENDER_ITEM, at = @At(value = "INVOKE", target = "Lcom/mojang/math/Axis;rotationDegrees(F)Lorg/joml/Quaternionf;", ordinal = 0))
	private Quaternionf essentials$noPitchSway(Quaternionf original) {
		ViewModel vm = Modules.active(ViewModel.class);
		return vm != null && vm.noSway.get() ? new Quaternionf() : original;
	}

	@ModifyExpressionValue(method = RENDER_ITEM, at = @At(value = "INVOKE", target = "Lcom/mojang/math/Axis;rotationDegrees(F)Lorg/joml/Quaternionf;", ordinal = 1))
	private Quaternionf essentials$noYawSway(Quaternionf original) {
		ViewModel vm = Modules.active(ViewModel.class);
		return vm != null && vm.noSway.get() ? new Quaternionf() : original;
	}

	@WrapOperation(method = RENDER_ITEM, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;submitArmWithItem(Lnet/minecraft/client/player/AbstractClientPlayer;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V"))
	private void essentials$viewModel(ItemInHandRenderer renderer, AbstractClientPlayer player, float tickDelta, float pitch, InteractionHand hand, float swing,
									  ItemStack item, float equip, PoseStack matrices, SubmitNodeCollector consumers, int light, Operation<Void> original) {
		ViewModel vm = Modules.active(ViewModel.class);
		if (vm == null) {
			original.call(renderer, player, tickDelta, pitch, hand, swing, item, equip, matrices, consumers, light);
			return;
		}
		boolean main = hand == InteractionHand.MAIN_HAND;
		if (main ? vm.hideMain.get() : vm.hideOff.get()) return;
		HumanoidArm arm = main ? player.getMainArm() : player.getMainArm().getOpposite();
		float mirror = arm == HumanoidArm.RIGHT ? 1 : -1;
		matrices.pushPose();
		boolean own = !main && vm.separateOffhand.get();
		matrices.translate((own ? vm.offX.get() : vm.x.get()) * mirror, own ? vm.offY.get() : vm.y.get(), own ? vm.offZ.get() : vm.z.get());
		matrices.mulPose(Axis.YP.rotationDegrees(vm.rotY.get() * mirror));
		matrices.mulPose(Axis.XP.rotationDegrees(vm.rotX.get()));
		matrices.mulPose(Axis.ZP.rotationDegrees(vm.rotZ.get() * mirror));
		float s = main ? vm.scale.getFloat() : vm.offhandScale.getFloat();
		if (s != 1) {
			// Scale around where the item sits rather than the camera, so it grows in place.
			float px = 0.56f * mirror, py = -0.52f, pz = -0.72f;
			matrices.translate(px, py, pz);
			matrices.scale(s, s, s);
			matrices.translate(-px, -py, -pz);
		}
		int l = vm.brighten.get() ? LightCoordsUtil.FULL_BRIGHT : light;
		original.call(renderer, player, tickDelta, pitch, hand, swing, item, equip, matrices, consumers, l);
		matrices.popPose();
	}
}
