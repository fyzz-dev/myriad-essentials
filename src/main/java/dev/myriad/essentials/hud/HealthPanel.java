package dev.myriad.essentials.hud;

import dev.myriad.api.ui.hud.HudStyle;
import dev.myriad.api.ui.hud.TextHudPanel;

/** Your health, absorption included. */
public final class HealthPanel extends TextHudPanel {
	public HealthPanel() {
		super("HP", "", HudStyle.Mode.TEXT);
	}

	@Override
	protected void lines(Lines out) {
		float hp = preview() ? 20 : mc.player.getHealth() + mc.player.getAbsorptionAmount();
		out.add("HP", String.format("%.1f", hp));
	}
}
