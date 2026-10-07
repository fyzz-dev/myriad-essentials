package dev.myriad.essentials.mixin;

import dev.myriad.essentials.modules.render.NoRender;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class AbstractBlockStateMixin {
	/** No Render: no random model offsets (they reveal block positions). */
	@Inject(method = "getOffset", at = @At("HEAD"), cancellable = true)
	private void essentials$noOffset(BlockPos pos, CallbackInfoReturnable<Vec3> cir) {
		if (NoRender.hides(n -> n.textureRotations)) cir.setReturnValue(Vec3.ZERO);
	}
}
