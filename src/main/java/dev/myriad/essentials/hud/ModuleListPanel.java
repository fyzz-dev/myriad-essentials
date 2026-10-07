package dev.myriad.essentials.hud;

import dev.myriad.api.Myriad;
import dev.myriad.api.module.Module;
import dev.myriad.api.render.Animated;
import dev.myriad.api.render.Bezier;
import dev.myriad.api.render.Canvas;
import dev.myriad.api.render.FontFamily;
import dev.myriad.api.setting.BoolSetting;
import dev.myriad.api.setting.EnumSetting;
import dev.myriad.api.ui.Rect;
import dev.myriad.api.ui.hud.HudPanel;
import dev.myriad.api.ui.hud.HudStyle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The classic "array list": enabled modules, longest first, sliding in and out. */
public final class ModuleListPanel extends HudPanel {
	public enum Sort {
		WIDTH, ALPHABETICAL
	}

	private final EnumSetting<Sort> sort = sgGeneral.enumSetting("Sort", Sort.WIDTH).build();
	private final BoolSetting info = sgGeneral.bool("Show Info").description("Show each module's status text (mode, target, …).").defaultValue(true).build();
	private final BoolSetting background = sgGeneral.bool("Background").defaultValue(true).build();

	private final HudStyle style = new HudStyle(settings, HudStyle.Mode.GRADIENT, true);

	private final Map<Module, Animated> slide = new HashMap<>();

	public ModuleListPanel() {
		super("Module List", "\uf03a");
	}

	private String info(Module m) {
		String i = info.get() ? m.hudInfo() : null;
		return i == null || i.isEmpty() ? null : " " + i;
	}

	private record Row(Module module, String info, float width, float t) {
	}

	private List<Row> rows(Canvas c) {
		float s = fontSize(c);
		List<Row> rows = new ArrayList<>();
		for (Module m : Myriad.modules()) {
			boolean show = m.isEnabled() && m.visibleInList.get();
			Animated a = slide.computeIfAbsent(m, k -> new Animated(show ? 1 : 0));
			a.animateTo(show ? 1 : 0, 220, Bezier.EASE_OUT_QUINT);
			float t = a.get();
			if (t <= 0.01f) continue;
			String i = info(m);
			float width = c.textWidth(FontFamily.SANS, s, m.name()) + (i == null ? 0 : c.textWidth(FontFamily.SANS, s, i));
			rows.add(new Row(m, i, width, t));
		}
		rows.sort(sort.get() == Sort.WIDTH ? Comparator.comparingDouble((Row r) -> -r.width) : Comparator.comparing(r -> r.module.name()));
		return rows;
	}

	@Override
	public Rect preferredSize(Canvas c) {
		float s = fontSize(c);
		float lh = c.textHeight(FontFamily.SANS, s) + 2;
		float w = 40, h = 0;
		for (Row r : rows(c)) {
			w = Math.max(w, r.width + 4);
			h += lh * r.t;
		}
		return new Rect(0, 0, w, Math.max(lh, h));
	}

	@Override
	public void render(Canvas c, float w, float h, float mx, float my) {
		float s = fontSize(c);
		float lh = c.textHeight(FontFamily.SANS, s) + 2;
		boolean right = alignRight();
		List<Row> rows = rows(c);
		float y = 0;
		for (int i = 0; i < rows.size(); i++) {
			Row r = rows.get(i);
			float rw = r.width + 4;
			float x = right ? w - rw * r.t : -rw * (1 - r.t);
			int color = style.color(i, rows.size(), r.module);
			c.push();
			c.alpha(r.t);
			if (background.get()) {
				c.rect(x, y, rw, lh, 0x66000000);
				c.rect(right ? x + rw - 1 : x, y, 1, lh, color);
			}
			float tx = x + 2 + HudStyle.draw(c, FontFamily.SANS, s, r.module.name(), x + 2, y + 1, color, style.shadow());
			if (r.info != null) HudStyle.draw(c, FontFamily.SANS, s, r.info, tx, y + 1, style.label(), style.shadow());
			c.pop();
			y += lh * r.t;
		}
	}
}
