package dev.myriad.essentials.hud;

import dev.myriad.api.setting.BoolSetting;
import dev.myriad.api.ui.hud.HudStyle;
import dev.myriad.api.ui.hud.TextHudPanel;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.Level;

/** Where you are, and the matching Nether or Overworld coordinates. */
public final class CoordinatesPanel extends TextHudPanel {
	private final BoolSetting otherDimension = sgGeneral.bool("Other Dimension").description("Show the matching Nether/Overworld coordinates.").defaultValue(true).build();
	private final BoolSetting decimals = sgGeneral.bool("Decimals").description("Show one decimal place.").build();

	public CoordinatesPanel() {
		super("Coordinates", "", HudStyle.Mode.TEXT);
	}

	@Override
	protected void lines(Lines out) {
		if (preview()) {
			out.add("XYZ", "0 0 0");
			return;
		}
		var p = mc.player;
		String f = decimals.get() ? "%.1f %.1f %.1f" : "%.0f %.0f %.0f";
		List<String> parts = new ArrayList<>(List.of("XYZ ", String.format(f, p.getX(), p.getY(), p.getZ())));
		if (otherDimension.get() && mc.level.dimension() != Level.END) {
			boolean nether = mc.level.dimension() == Level.NETHER;
			double k = nether ? 8 : 1 / 8.0;
			parts.addAll(List.of(nether ? " [OW " : " [Nether ", String.format(decimals.get() ? "%.1f %.1f" : "%.0f %.0f", p.getX() * k, p.getZ() * k), "]", ""));
		}
		out.parts(parts.toArray(String[]::new));
	}
}
