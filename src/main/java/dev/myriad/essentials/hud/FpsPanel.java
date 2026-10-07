package dev.myriad.essentials.hud;

import dev.myriad.api.ui.hud.HudStyle;
import dev.myriad.api.ui.hud.TextHudPanel;

/** Frames per second. */
public final class FpsPanel extends TextHudPanel {
	public FpsPanel() {
		super("FPS", "", HudStyle.Mode.TEXT);
	}

	@Override
	protected void lines(Lines out) {
		out.add("FPS", String.valueOf(mc.getFps()));
	}
}
