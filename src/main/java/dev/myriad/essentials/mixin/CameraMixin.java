package dev.myriad.essentials.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.myriad.essentials.modules.render.FreeLook;
import dev.myriad.essentials.modules.render.Zoom;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Camera.class)
public abstract class CameraMixin {
	@ModifyReturnValue(method = "calculateFov", at = @At("RETURN"))
	private float essentials$zoom(float fov) {
		return Zoom.apply(fov);
	}

	@Shadow
	private boolean detached;

	@Shadow
	protected abstract float getMaxZoom(float distance);

	/**
	 * Free Look's scroll distance, shortened as vanilla does to keep the camera out of blocks. Set on the move that backs
	 * the camera off rather than on its distance, so another client that sets that move's distance (Boze does) can't
	 * undo it.
	 */
	@WrapOperation(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;move(FFF)V"))
	private void essentials$freeLookDistance(Camera camera, float forwards, float up, float right, Operation<Void> original) {
		if (detached && FreeLook.active()) forwards = -getMaxZoom(FreeLook.distance());
		original.call(camera, forwards, up, right);
	}
}
