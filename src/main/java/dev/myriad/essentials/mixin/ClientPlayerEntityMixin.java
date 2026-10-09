package dev.myriad.essentials.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.myriad.essentials.modules.movement.ElytraFly;
import dev.myriad.essentials.modules.movement.Velocity;
import dev.myriad.essentials.util.GlideHold;
import dev.myriad.essentials.modules.player.WallInteract;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class ClientPlayerEntityMixin {
	/** Velocity No Push: blocks don't push you out of them. */
	@Inject(method = "moveTowardsClosestSpace", at = @At("HEAD"), cancellable = true)
	private void essentials$noBlockPush(double x, double z, CallbackInfo ci) {
		if (Velocity.cancelsBlockPush()) ci.cancel();
	}

	/** Glide Hold: the server stopping the glide is undone on the client while it's held (see GlideHold). */
	@Inject(method = "onSyncedDataUpdated", at = @At("TAIL"))
	private void essentials$keepGliding(EntityDataAccessor<?> accessor, CallbackInfo ci) {
		LocalPlayer self = (LocalPlayer) (Object) this;
		// The raw gliding bit: Elytra Fly may report gliding through ground touches regardless.
		if (!accessor.equals(EntityAccessor.essentials$entityFlags()) || (self.getEntityData().get(EntityAccessor.essentials$entityFlags()) & 0x80) != 0) return;
		if (GlideHold.keepGliding()) self.startFallFlying();
	}

	/**
	 * Elytra Fly: while Recast has you on foot (Baritone walking you round something, mining, filling a hole), a jump held
	 * into the air doesn't open the elytra, as vanilla would.
	 */
	@WrapOperation(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;tryToStartFallFlying()Z"))
	private boolean essentials$noGlideOnFoot(LocalPlayer self, Operation<Boolean> original) {
		return !ElytraFly.onFoot() && original.call(self);
	}

	/** Wall Interact: the crosshair target can be an entity or container behind a block. */
	@ModifyReturnValue(method = "raycastHitResult", at = @At("RETURN"))
	private HitResult essentials$wallInteract(HitResult original, float partialTicks, Entity camera) {
		return WallInteract.retarget(original, camera, partialTicks);
	}
}
