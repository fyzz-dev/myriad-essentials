package dev.myriad.essentials.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.myriad.essentials.modules.movement.ElytraFly;
import dev.myriad.essentials.modules.movement.ElytraTweaks;
import dev.myriad.essentials.modules.render.ViewModel;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Elytra Fly: the local player keeps gliding through ground touches, and Silent's flight physics use the spoofed
 * rotation. Elytra Tweaks: rocket boost. View Model: your swing speed.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	private boolean essentials$spoofs() {
		return (Object) this == Minecraft.getInstance().player && ElytraFly.spoofing();
	}

	@ModifyReturnValue(method = "isFallFlying", at = @At("RETURN"))
	private boolean essentials$holdGlide(boolean original) {
		return original || (Object) this == Minecraft.getInstance().player && ElytraFly.holdsGlide();
	}

	@ModifyExpressionValue(method = "updateFallFlyingMovement", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getLookAngle()Lnet/minecraft/world/phys/Vec3;"))
	private Vec3 essentials$glideLook(Vec3 original) {
		return essentials$spoofs() ? Vec3.directionFromRotation(ElytraFly.spoofPitch(), ElytraFly.spoofYaw()) : original;
	}

	@ModifyExpressionValue(method = "updateFallFlyingMovement", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getXRot()F"))
	private float essentials$glidePitch(float original) {
		return essentials$spoofs() ? ElytraFly.spoofPitch() : original;
	}

	@ModifyExpressionValue(method = "jumpFromGround", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getYRot()F"))
	private float essentials$jumpYaw(float original) {
		return essentials$spoofs() ? ElytraFly.spoofYaw() : original;
	}

	/** Elytra Tweaks' Rocket Boost: the glide's movement this tick (and the velocity carried into the next). */
	@ModifyArg(method = "travelFallFlying", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"), index = 1)
	private Vec3 essentials$rocketBoost(Vec3 movement) {
		if ((Object) this != Minecraft.getInstance().player) return movement;
		Vec3 boosted = ElytraTweaks.glideMovement(movement);
		if (boosted != movement) ((LivingEntity) (Object) this).setDeltaMovement(boosted);
		return boosted;
	}

	@ModifyReturnValue(method = "getCurrentSwingDuration", at = @At("RETURN"))
	private int essentials$swingSpeed(int ticks) {
		return (Object) this == Minecraft.getInstance().player ? ViewModel.swingDuration(ticks) : ticks;
	}
}
