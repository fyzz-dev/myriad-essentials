package dev.myriad.essentials.hud;

import dev.myriad.api.setting.EnumSetting;
import dev.myriad.api.ui.hud.HudStyle;
import dev.myriad.api.ui.hud.TextHudPanel;

/** How fast you're moving horizontally. */
public final class SpeedPanel extends TextHudPanel {
	public enum Unit {
		BLOCKS_PER_SECOND, KMH
	}

	private final EnumSetting<Unit> unit = sgGeneral.enumSetting("Unit", Unit.BLOCKS_PER_SECOND).build();

	public SpeedPanel() {
		super("Speed", "", HudStyle.Mode.TEXT);
	}

	@Override
	protected void lines(Lines out) {
		double bps = 0;
		if (!preview()) {
			// Covers vehicles and elytra flight too: what you ride carries you.
			var moving = mc.player.getRootVehicle();
			double dx = moving.getX() - moving.xo, dz = moving.getZ() - moving.zo;
			bps = Math.sqrt(dx * dx + dz * dz) * 20;
		}
		out.add("Speed", unit.get() == Unit.KMH ? String.format("%.1f km/h", bps * 3.6) : String.format("%.1f b/s", bps));
	}
}
