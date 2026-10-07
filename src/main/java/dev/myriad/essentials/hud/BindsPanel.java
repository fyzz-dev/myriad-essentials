package dev.myriad.essentials.hud;

import dev.myriad.api.Myriad;
import dev.myriad.api.module.Module;
import dev.myriad.api.setting.BoolSetting;
import dev.myriad.api.ui.hud.HudStyle;
import dev.myriad.api.ui.hud.TextHudPanel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Every module with a keybind and its key; enabled ones in the highlight colour. */
public final class BindsPanel extends TextHudPanel {
	private final BoolSetting onlyEnabled = sgGeneral.bool("Only Enabled").description("Hide modules that are off.").build();

	public BindsPanel() {
		super("Binds", "", HudStyle.Mode.TEXT);
	}

	@Override
	protected void lines(Lines out) {
		List<Module> bound = new ArrayList<>();
		for (Module m : Myriad.modules()) if (m.keybind.get().isSet() && (m.isEnabled() || !onlyEnabled.get())) bound.add(m);
		bound.sort(Comparator.comparing(Module::name));
		for (Module m : bound) {
			String key = " [" + m.keybind.get().displayName() + "]";
			// Labels draw in the label colour and values in the style's: an enabled module's name is a value.
			if (m.isEnabled()) out.parts("", m.name(), key, "");
			else out.parts(m.name() + key, "");
		}
		if (bound.isEmpty() && preview()) out.parts("", "Elytra Fly", " [G]", "");
	}
}
