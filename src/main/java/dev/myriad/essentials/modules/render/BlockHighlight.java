package dev.myriad.essentials.modules.render;

import dev.myriad.api.event.Priority;
import dev.myriad.api.event.Subscribe;
import dev.myriad.api.event.events.HighlightEvent;
import dev.myriad.api.event.events.Render3DEvent;
import dev.myriad.api.module.Categories;
import dev.myriad.api.module.Module;
import dev.myriad.api.render.BoxStyle;
import dev.myriad.api.render.HighlightSettings;
import dev.myriad.api.setting.ColorSetting;
import dev.myriad.api.setting.EnumSetting;
import dev.myriad.api.setting.SettingColor;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Replaces vanilla's thin black outline around the block you're looking at: a shader outline (with glow, a solid or
 * dotted fill and a gradient, like ESP's) or a box, either following the block's real shape. Always just the one block
 * (half a double chest, one end of a bed), outlined on its own even where Blocks or Storage highlight its neighbours.
 */
public class BlockHighlight extends Module {
	public enum Mode {
		OUTLINE, BOX
	}

	private final EnumSetting<Mode> mode = sgGeneral.enumSetting("Mode", Mode.OUTLINE).description("Outline: a shader outline around the block's shape. Box: a box with fill and lines.").build();
	private final ColorSetting color = sgGeneral.color("Color").defaultValue(SettingColor.role(SettingColor.Mode.ACCENT)).build();
	private final HighlightSettings outline = new HighlightSettings(settings.group("Outline"), () -> mode.get() == Mode.OUTLINE, false);
	private final BoxStyle box = new BoxStyle(settings.group("Box"), () -> mode.get() == Mode.BOX, false);

	public BlockHighlight() {
		super(Categories.RENDER, "Block Highlight", "Your own look for the block you're looking at, in place of vanilla's outline.");
		// Vanilla's outline only while this is off.
		LevelRenderEvents.BEFORE_BLOCK_OUTLINE.register((context, state) -> !isEnabled());
	}

	/** The block under the crosshair, or null. */
	private BlockPos target() {
		if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return null;
		return mc.level.getBlockState(hit.getBlockPos()).isAir() ? null : hit.getBlockPos();
	}

	/** Low priority: after other modules' highlights, so this one is always its own outline, drawn over theirs. */
	@Subscribe(inGame = true, priority = Priority.LOW)
	private void onHighlight(HighlightEvent.Shapes e) {
		if (mode.get() != Mode.OUTLINE) return;
		BlockPos pos = target();
		if (pos != null) e.block(pos, outline.style(), color.argb());
	}

	@Subscribe(inGame = true)
	private void onRender(Render3DEvent e) {
		if (mode.get() != Mode.BOX) return;
		BlockPos pos = target();
		if (pos == null) return;
		int c = color.argb();
		e.shapes().lineWidth(box.lineWidth.getFloat());
		e.shapes().blockShape(pos, box.fill(c, 1), box.line(c, 1), box.shape.get(), box.throughWalls.get());
	}
}
