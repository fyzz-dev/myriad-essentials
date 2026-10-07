package dev.myriad.essentials.hud;

import dev.myriad.api.ui.hud.HudStyle;
import dev.myriad.api.ui.hud.TextHudPanel;
import net.minecraft.world.effect.MobEffectInstance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Your potion effects and how long each has left, longest first. */
public final class EffectsPanel extends TextHudPanel {
	private static final String[] LEVELS = {"", " II", " III", " IV", " V", " VI", " VII", " VIII", " IX", " X"};

	public EffectsPanel() {
		super("Effects", "", HudStyle.Mode.TEXT);
	}

	@Override
	protected void lines(Lines out) {
		if (preview()) {
			out.add("Speed II", "1:30");
			out.add("Fire Resistance", "7:59");
			return;
		}
		List<MobEffectInstance> effects = new ArrayList<>(mc.player.getActiveEffects());
		effects.sort(Comparator.comparingInt((MobEffectInstance e) -> e.isInfiniteDuration() ? Integer.MAX_VALUE : e.getDuration()).reversed());
		for (MobEffectInstance e : effects) {
			int amp = e.getAmplifier();
			String name = e.getEffect().value().getDisplayName().getString() + (amp < LEVELS.length ? LEVELS[amp] : " " + (amp + 1));
			out.add(name, e.isInfiniteDuration() ? "∞" : time(e.getDuration()));
		}
	}

	private static String time(int ticks) {
		int s = ticks / 20;
		return s / 60 + ":" + String.format("%02d", s % 60);
	}
}
