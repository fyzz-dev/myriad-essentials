package dev.myriad.essentials.modules.render;

import dev.myriad.api.event.Subscribe;
import dev.myriad.api.event.events.Render3DEvent;
import dev.myriad.api.module.Categories;
import dev.myriad.api.module.Module;
import dev.myriad.api.module.Modules;
import dev.myriad.api.render.Renderer3D;
import dev.myriad.api.setting.BoolSetting;
import dev.myriad.api.setting.DoubleSetting;
import dev.myriad.api.setting.EnumSetting;
import dev.myriad.api.util.ColorUtil;
import dev.myriad.api.util.Entities;
import dev.myriad.api.render.EntityGroups;
import dev.myriad.api.render.EntityGroups.Group;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/**
 * Lines from your crosshair to entities, by the same groups as ESP (players, friends, monsters, crystals, pearls, …),
 * and to the storage Storage is highlighting, in its colours.
 */
public class Tracers extends Module {
	public enum Target {
		FEET, BODY, HEAD
	}

	private final EnumSetting<Target> target = sgGeneral.enumSetting("Target", Target.BODY).description("Which part of the entity the line ends at.").build();
	private final DoubleSetting range = sgGeneral.doubleSetting("Range").description("0 = no limit.").defaultValue(128).range(0, 512).decimals(0).build();
	private final DoubleSetting lineWidth = sgGeneral.doubleSetting("Line Width").defaultValue(1.5).range(0.5, 5).decimals(1).build();
	private final BoolSetting fadeDistance = sgGeneral.bool("Fade With Distance").description("Far lines are fainter.").defaultValue(true).build();
	private final BoolSetting storage = sgGeneral.bool("Storage").description("Also draw lines to what Storage highlights (needs Storage on).").build();
	private final EntityGroups targets = new EntityGroups(settings, Set.of(Group.PLAYERS, Group.FRIENDS));

	public Tracers() {
		super(Categories.RENDER, "Tracers", "Draws lines to entities and storage.");
	}

	@Subscribe(inGame = true)
	private void onRender(Render3DEvent e) {
		Renderer3D.lineWidth(lineWidth.getFloat());
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (entity == mc.player) continue;
			int c = targets.color(entity);
			if (c == 0) continue;
			AABB box = Entities.lerpedBox(entity, e.tickDelta());
			draw(switch (target.get()) {
				case FEET -> new Vec3(box.getCenter().x, box.minY, box.getCenter().z);
				case BODY -> box.getCenter();
				case HEAD -> new Vec3(box.getCenter().x, box.maxY - 0.1, box.getCenter().z);
			}, c);
		}
		Storage s = Modules.get(Storage.class);
		if (storage.get() && s != null) s.forEachShown(e.tickDelta(), (box, color) -> draw(box.getCenter(), color));
	}

	private void draw(Vec3 to, int color) {
		double dist = mc.player.position().distanceTo(to);
		if (range.get() > 0 && dist > range.get()) return;
		if (fadeDistance.get()) {
			double max = range.get() > 0 ? range.get() : 256;
			color = ColorUtil.withAlpha(color, (int) (ColorUtil.alpha(color) * Math.clamp(1 - dist / max * 0.75, 0.25, 1)));
		}
		Renderer3D.tracer(to, color);
	}
}
