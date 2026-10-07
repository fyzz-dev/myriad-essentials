package dev.myriad.essentials.hud;

import dev.myriad.api.Myriad;
import dev.myriad.api.render.Canvas;
import dev.myriad.api.render.FontFamily;
import dev.myriad.api.setting.BoolSetting;
import dev.myriad.api.ui.Rect;
import dev.myriad.api.ui.hud.HudPanel;
import dev.myriad.api.ui.hud.HudStyle;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** How many totems of undying you're carrying, off hand included, as the item with its count. */
public final class TotemsPanel extends HudPanel {
	/** Made on first draw: item stacks can't exist before the game has bound item components. */
	private ItemStack icon;

	private final BoolSetting hideNone = sgGeneral.bool("Hide At Zero").description("Draw nothing when you have no totems.").build();
	private final HudStyle style = new HudStyle(settings, HudStyle.Mode.TEXT, false);

	public TotemsPanel() {
		super("Totems", "");
	}

	private float size() {
		return 16 * scale.getFloat();
	}

	@Override
	public Rect preferredSize(Canvas c) {
		return new Rect(0, 0, size() + 1, size() + 1);
	}

	@Override
	public void render(Canvas c, float w, float h, float mx, float my) {
		int n = preview() ? 3 : Myriad.inventory().count(s -> s.is(Items.TOTEM_OF_UNDYING));
		if (n == 0 && hideNone.get()) return;
		float size = size();
		if (icon == null) icon = new ItemStack(Items.TOTEM_OF_UNDYING);
		c.item(icon, 0, 0, size, false);
		String text = String.valueOf(n);
		float ts = c.defaultFontSize() * scale.getFloat();
		float tw = c.textWidth(FontFamily.SANS_BOLD, ts, text);
		HudStyle.draw(c, FontFamily.SANS_BOLD, ts, text, size - tw, size - c.textHeight(FontFamily.SANS_BOLD, ts) + 1, n == 0 ? 0xFFFF5555 : style.color(0, 1), style.shadow());
	}
}
