package dev.myriad.essentials.modules.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.myriad.api.event.Subscribe;
import dev.myriad.api.event.events.EntityRenderEvent;
import dev.myriad.api.event.events.Render2DEvent;
import dev.myriad.api.event.events.Render3DEvent;
import dev.myriad.api.module.Categories;
import dev.myriad.api.module.Module;
import dev.myriad.api.render.Canvas;
import dev.myriad.api.render.ModelShapes;
import dev.myriad.api.render.Projection;
import dev.myriad.api.render.RenderStates;
import dev.myriad.api.render.Renderer3D;
import dev.myriad.api.render.ShapeBuilder;
import dev.myriad.api.setting.BoolSetting;
import dev.myriad.api.setting.DoubleSetting;
import dev.myriad.api.setting.EnumSetting;
import dev.myriad.api.util.ColorUtil;
import dev.myriad.api.util.Entities;
import dev.myriad.api.render.BoxStyle;
import dev.myriad.api.render.EntityGroups;
import dev.myriad.api.render.EntityGroups.Group;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/**
 * Highlights entities through walls, by group (players, friends, monsters, animals, crystals, items, pearls, vehicles,
 * …) or by entity type, each group in its own theme colour:
 * <ul>
 * <li>Hitbox: a 3D box.</li>
 * <li>Box 2D: a screen-space rectangle, optionally with a health bar.</li>
 * <li>Model: a wireframe and fill of the entity's model; anything without a model falls back to its box.</li>
 * </ul>
 * Storage draws containers with the same style options.
 */
public class ESP extends Module {
	public enum Mode {
		HITBOX, BOX_2D, MODEL
	}

	private static final long FADE_MS = 200;

	private final EnumSetting<Mode> mode = sgGeneral.enumSetting("Mode", Mode.HITBOX).description("Hitbox: 3D boxes. Box 2D: screen rectangles. Model: the model's wireframe.").build();
	private final DoubleSetting range = sgGeneral.doubleSetting("Range").description("0 = no limit.").defaultValue(0).range(0, 256).decimals(0).build();
	private final BoolSetting fade = sgGeneral.bool("Fade In").description("Fade entities in as they appear.").defaultValue(true).build();
	private final BoolSetting healthBar = sgGeneral.bool("Health Bar").description("A health bar beside each box.").visible(() -> mode.get() == Mode.BOX_2D).build();
	private final BoxStyle style = new BoxStyle(sgGeneral);
	private final EntityGroups targets = new EntityGroups(settings, Set.of(Group.PLAYERS, Group.FRIENDS, Group.MONSTERS, Group.CRYSTALS, Group.PEARLS));

	/** When each entity id was first drawn, for the fade-in; ids not drawn this frame are dropped. */
	private final Int2LongOpenHashMap appeared = new Int2LongOpenHashMap();
	private final IntOpenHashSet seen = new IntOpenHashSet();

	public ESP() {
		super(Categories.RENDER, "ESP", "Highlights players, mobs, crystals and more through walls.");
	}

	@Override
	protected void onEnable() {
		appeared.clear();
	}

	/** The colour to draw {@code e} in, or 0 to skip it. */
	private int colorFor(Entity e) {
		if (range.get() > 0 && e != mc.player && mc.player.distanceToSqr(e) > range.get() * range.get()) return 0;
		return targets.color(e);
	}

	private float fadeFor(Entity e, long now) {
		seen.add(e.getId());
		if (!fade.get()) return 1;
		long since = appeared.get(e.getId());
		if (since == 0) appeared.put(e.getId(), since = now);
		return Mth.clamp((now - since) / (float) FADE_MS, 0, 1);
	}

	/** The fade-in of an entity already seen this frame (see {@link #fadeFor}). */
	private float fadeOf(Entity e) {
		if (!fade.get()) return 1;
		long since = appeared.get(e.getId());
		return since == 0 ? 0 : Mth.clamp((System.currentTimeMillis() - since) / (float) FADE_MS, 0, 1);
	}

	private void forgetUnseen() {
		appeared.keySet().retainAll(seen);
		seen.clear();
	}

	/** Model mode: as an entity's model is submitted, its wireframe and fill go with it. */
	@Subscribe(inGame = true)
	@SuppressWarnings("unchecked")
	private void onModel(EntityRenderEvent.Model event) {
		if (mode.get() != Mode.MODEL) return;
		LivingEntityRenderState state = event.state();
		Model<?> model = event.model();
		PoseStack poseStack = event.pose();
		SubmitNodeCollector submits = event.submits();
		Entity e = RenderStates.entity(state);
		if (e == null) return;
		int c = colorFor(e);
		if (c == 0) return;
		float f = fadeOf(e);
		Model<LivingEntityRenderState> m = (Model<LivingEntityRenderState>) model;
		int fill = style.fill(c, f);
		if (ColorUtil.alpha(fill) > 0 && style.shape.get() != Renderer3D.ShapeMode.LINES) {
			ModelShapes.fill(submits, poseStack, m, state, fill, style.throughWalls.get());
		}
		if (style.shape.get() != Renderer3D.ShapeMode.FILL) {
			ModelShapes.wireframe(submits, poseStack, m, state, style.line(c, f), style.lineWidth.getFloat(), style.throughWalls.get());
		}
	}

	@Subscribe
	private void onRender3D(Render3DEvent e) {
		if (!inGame() || mode.get() == Mode.BOX_2D) return;
		ShapeBuilder shapes = e.shapes();
		long now = System.currentTimeMillis();
		for (Entity entity : mc.level.entitiesForRendering()) {
			int c = colorFor(entity);
			if (c == 0) continue;
			float f = fadeFor(entity, now);
			// Model mode: models draw themselves (submitModel); anything without one falls back to its box.
			if (mode.get() == Mode.MODEL && entity instanceof LivingEntity) continue;
			style.draw(shapes, Entities.lerpedBox(entity, e.tickDelta()), c, f);
		}
		forgetUnseen();
	}

	@Subscribe
	private void onRender2D(Render2DEvent e) {
		if (!inGame() || mode.get() != Mode.BOX_2D) return;
		Canvas c = e.canvas();
		long now = System.currentTimeMillis();
		for (Entity entity : mc.level.entitiesForRendering()) {
			int color = colorFor(entity);
			if (color == 0) continue;
			float[] r = project(Entities.lerpedBox(entity, e.tickDelta()));
			if (r == null) continue;
			drawBox2D(c, entity, r, color, fadeFor(entity, now));
		}
		forgetUnseen();
	}

	private void drawBox2D(Canvas c, Entity entity, float[] r, int color, float f) {
		float x = r[0], y = r[1], w = r[2] - r[0], h = r[3] - r[1];
		float width = style.lineWidth.getFloat();
		if (style.shape.get() != Renderer3D.ShapeMode.LINES) c.rect(x, y, w, h, style.fill(color, f));
		if (style.shape.get() != Renderer3D.ShapeMode.FILL) {
			c.outline(x - 0.5f, y - 0.5f, w + 1, h + 1, 0, width + 1, ColorUtil.withAlpha(0xFF000000, (int) (ColorUtil.alpha(color) * f / 2)));
			c.outline(x, y, w, h, 0, width, style.line(color, f));
		}
		if (healthBar.get() && entity instanceof LivingEntity living) {
			float hp = Mth.clamp(living.getHealth() / living.getMaxHealth(), 0, 1);
			float bx = x - 3.5f;
			c.rect(bx - 0.5f, y - 0.5f, 2, h + 1, 0x99000000);
			c.rect(bx, y + h * (1 - hp), 1, h * hp, ColorUtil.lerp(0xFFFF5555, 0xFF55FF55, hp));
		}
	}

	/** Screen rect [left, top, right, bottom] around a world box, or null when it's off screen. */
	private static float[] project(AABB b) {
		float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
		var window = mc.getWindow();
		float sw = window.getGuiScaledWidth(), sh = window.getGuiScaledHeight();
		boolean any = false;
		for (int i = 0; i < 8; i++) {
			Vec3 s = Projection.toScreen(new Vec3((i & 1) == 0 ? b.minX : b.maxX, (i & 2) == 0 ? b.minY : b.maxY, (i & 4) == 0 ? b.minZ : b.maxZ));
			// Corners right by the near plane project far off screen; skip them.
			if (s == null || s.x < -sw * 2 || s.x > sw * 3 || s.y < -sh * 2 || s.y > sh * 3) continue;
			minX = Math.min(minX, (float) s.x);
			minY = Math.min(minY, (float) s.y);
			maxX = Math.max(maxX, (float) s.x);
			maxY = Math.max(maxY, (float) s.y);
			any = true;
		}
		if (!any) return null;
		minX = Math.max(0, minX);
		minY = Math.max(0, minY);
		maxX = Math.min(sw, maxX);
		maxY = Math.min(sh, maxY);
		return maxX <= minX || maxY <= minY ? null : new float[]{minX, minY, maxX, maxY};
	}
}
