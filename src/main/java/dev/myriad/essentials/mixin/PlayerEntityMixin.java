package dev.myriad.essentials.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.myriad.essentials.modules.movement.Velocity;
import dev.myriad.essentials.modules.player.Reach;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Player.class)
public abstract class PlayerEntityMixin {
	private boolean essentials$isLocal() {
		return (Object) this == Minecraft.getInstance().player;
	}

	/** Velocity No Push: flowing water and lava don't push you. */
	@ModifyReturnValue(method = "isPushedByFluid", at = @At("RETURN"))
	private boolean essentials$noLiquidPush(boolean original) {
		return original && !(essentials$isLocal() && Velocity.cancelsLiquidPush());
	}

	@ModifyReturnValue(method = "blockInteractionRange", at = @At("RETURN"))
	private double essentials$blockReach(double original) {
		return essentials$isLocal() ? Reach.blockRange(original) : original;
	}

	@ModifyReturnValue(method = "entityInteractionRange", at = @At("RETURN"))
	private double essentials$entityReach(double original) {
		return essentials$isLocal() ? Reach.entityRange(original) : original;
	}
}
