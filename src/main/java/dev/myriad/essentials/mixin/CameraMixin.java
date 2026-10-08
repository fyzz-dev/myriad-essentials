package dev.myriad.essentials.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.myriad.essentials.modules.render.FreeLook;
import dev.myriad.essentials.modules.render.Zoom;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(Camera.class)
public abstract class CameraMixin {
	@ModifyReturnValue(method = "calculateFov", at = @At("RETURN"))
	private float essentials$zoom(float fov) {
		return Zoom.apply(fov);
	}

	/** Free Look's scroll distance, before vanilla shortens it to keep the camera out of blocks. */
	@ModifyArg(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;getMaxZoom(F)F"))
	private float essentials$freeLookDistance(float distance) {
		return FreeLook.distance(distance);
	}
}
