package dev.myriad.essentials.hud;

import dev.myriad.api.setting.BoolSetting;
import dev.myriad.api.ui.hud.HudStyle;
import dev.myriad.api.ui.hud.TextHudPanel;
import net.minecraft.util.Mth;

/** Which way you're facing, with the axis it runs along ("North -Z"). */
public final class DirectionPanel extends TextHudPanel {
	private static final String[] CARDINAL = {"South", "West", "North", "East"};
	private static final String[] CARDINAL_AXIS = {"+Z", "-X", "-Z", "+X"};
	private static final String[] EIGHT = {"South", "South West", "West", "North West", "North", "North East", "East", "South East"};
	private static final String[] EIGHT_AXIS = {"+Z", "-X +Z", "-X", "-X -Z", "-Z", "+X -Z", "+X", "+X +Z"};

	private final BoolSetting interCardinal = sgGeneral.bool("Intercardinal").description("Include North East, South West and so on.").build();
	private final BoolSetting axis = sgGeneral.bool("Axis").description("Show the coordinate direction (-Z, +X…).").defaultValue(true).build();

	public DirectionPanel() {
		super("Direction", "", HudStyle.Mode.TEXT);
	}

	@Override
	protected void lines(Lines out) {
		float yaw = preview() ? 180 : Mth.wrapDegrees(mc.player.getYRot());
		// Yaw 0 faces south (+Z) and turns clockwise through west.
		float turned = (yaw + 360) % 360;
		String name, ax;
		if (interCardinal.get()) {
			int i = Math.round(turned / 45f) % 8;
			name = EIGHT[i];
			ax = EIGHT_AXIS[i];
		} else {
			int i = Math.round(turned / 90f) % 4;
			name = CARDINAL[i];
			ax = CARDINAL_AXIS[i];
		}
		out.add(name, axis.get() ? ax : "");
	}
}
