package dev.myriad.essentials.hud;

import dev.myriad.api.Myriad;
import dev.myriad.api.ui.hud.HudStyle;
import dev.myriad.api.ui.hud.TextHudPanel;

/** The server's ticks per second, averaged over the last ~20 seconds. */
public final class TpsPanel extends TextHudPanel {
	public TpsPanel() {
		super("TPS", "", HudStyle.Mode.TEXT);
	}

	@Override
	protected void lines(Lines out) {
		out.add("TPS", preview() ? "20.0" : String.format("%.1f", Myriad.server().tps()));
	}
}
