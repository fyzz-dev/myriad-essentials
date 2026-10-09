package dev.myriad.essentials.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import dev.myriad.api.module.Modules;
import dev.myriad.essentials.modules.render.NoRender;
import dev.myriad.essentials.modules.render.Tooltips;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
public abstract class InGameHudMixin {
	@Inject(method = "extractVignette", at = @At("HEAD"), cancellable = true)
	private void essentials$vignette(GuiGraphicsExtractor context, Entity entity, CallbackInfo ci) {
		if (NoRender.hides(n -> n.vignette)) ci.cancel();
	}

	@Inject(method = "extractPortalOverlay", at = @At("HEAD"), cancellable = true)
	private void essentials$portal(GuiGraphicsExtractor context, float nauseaStrength, CallbackInfo ci) {
		if (NoRender.hides(n -> n.portal)) ci.cancel();
	}

	@Inject(method = "extractTextureOverlay", at = @At("HEAD"), cancellable = true)
	private void essentials$pumpkin(GuiGraphicsExtractor context, Identifier texture, float opacity, CallbackInfo ci) {
		if (texture.getPath().contains("pumpkinblur") && NoRender.hides(n -> n.pumpkin)) ci.cancel();
		if (texture.getPath().contains("powder_snow_outline") && NoRender.hides(n -> n.powderSnow)) ci.cancel();
	}

	@Inject(method = "extractItemHotbar", at = @At("TAIL"))
	private void essentials$hotbarShulkers(GuiGraphicsExtractor context, DeltaTracker tickCounter, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		Tooltips preview = Modules.active(Tooltips.class);
		if (preview == null || !preview.hotbarIcons() || mc.player == null) return;
		int x = context.guiWidth() / 2 - 90, y = context.guiHeight() - 19;
		for (int i = 0; i < 9; i++) {
			ItemStack stack = mc.player.getInventory().getItem(i);
			if (Tooltips.isShulker(stack)) Tooltips.drawIcon(context, stack, x + i * 20 + 2, y);
		}
	}

	@Inject(method = "extractEffects", at = @At("HEAD"), cancellable = true)
	private void essentials$statusEffects(GuiGraphicsExtractor context, DeltaTracker tickCounter, CallbackInfo ci) {
		if (NoRender.hides(n -> n.statusEffects)) ci.cancel();
	}

	/** The bar itself is {@link ExperienceBarMixin}; the level number is drawn here. */
	@WrapWithCondition(method = "extractHotbarAndDecorations", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/contextualbar/ContextualBar;extractExperienceLevel(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;I)V"))
	private boolean essentials$xpLevel(GuiGraphicsExtractor context, Font font, int level) {
		return !NoRender.hides(n -> n.xpBar);
	}

	@Inject(method = "extractSelectedItemName", at = @At("HEAD"), cancellable = true)
	private void essentials$itemName(GuiGraphicsExtractor context, CallbackInfo ci) {
		if (NoRender.hides(n -> n.itemName)) ci.cancel();
	}

	@Inject(method = "extractSpyglassOverlay", at = @At("HEAD"), cancellable = true)
	private void essentials$spyglass(GuiGraphicsExtractor context, float scale, CallbackInfo ci) {
		if (NoRender.hides(n -> n.spyglass)) ci.cancel();
	}

	@Inject(method = "extractScoreboardSidebar", at = @At("HEAD"), cancellable = true)
	private void essentials$scoreboard(GuiGraphicsExtractor context, DeltaTracker tickCounter, CallbackInfo ci) {
		if (NoRender.hides(n -> n.scoreboard)) ci.cancel();
	}
}
