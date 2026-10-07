package dev.myriad.essentials.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import dev.myriad.essentials.modules.render.NoRender;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class WorldRendererMixin {
	@Inject(method = "addWeatherPass", at = @At("HEAD"), cancellable = true)
	private void essentials$noWeather(FrameGraphBuilder frame, GpuBufferSlice fog, CallbackInfo ci) {
		if (NoRender.hides(n -> n.weather)) ci.cancel();
	}
}
