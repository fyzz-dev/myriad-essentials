package dev.myriad.essentials.mixin;

import dev.myriad.essentials.modules.render.NoRender;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No Render Enchantment Glint: items report no glint, so it's drawn nowhere (held, dropped, worn, in screens). */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
	@Inject(method = "hasFoil", at = @At("HEAD"), cancellable = true)
	private void essentials$noGlint(CallbackInfoReturnable<Boolean> cir) {
		if (NoRender.hides(n -> n.glint)) cir.setReturnValue(false);
	}
}
