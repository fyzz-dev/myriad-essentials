package dev.myriad.essentials.modules.render;

import com.google.gson.JsonPrimitive;
import dev.myriad.api.event.Subscribe;
import dev.myriad.api.event.events.MouseLookEvent;
import dev.myriad.api.event.events.MouseScrollEvent;
import dev.myriad.api.event.events.TickEvent;
import dev.myriad.api.module.Categories;
import dev.myriad.api.module.Module;
import dev.myriad.api.module.Modules;
import dev.myriad.api.render.Easing;
import dev.myriad.api.setting.BoolSetting;
import dev.myriad.api.setting.DoubleSetting;
import dev.myriad.api.setting.IntSetting;
import dev.myriad.api.setting.SavedSettings;
import net.minecraft.util.Mth;

/**
 * Zooms the camera by dividing the field of view, easing in and out. Mouse turning slows down to match, and you can
 * scroll to change the zoom while it's on. Works best as a hold bind. Applied by this addon's CameraMixin.
 */
public class Zoom extends Module {
	/** Scrolling multiplies or divides the zoom by this much per notch. */
	private static final double SCROLL_STEP = 1.2;
	private static final double MAX_ZOOM = 50;

	private final DoubleSetting factor = sgGeneral.doubleSetting("Factor").description("How much the field of view is divided.").defaultValue(4).range(1.5, 12).decimals(1).build();
	private final BoolSetting scroll = sgGeneral.bool("Scroll").description("Scroll to zoom further in or out while zoomed.").defaultValue(true).build();
	private final IntSetting duration = sgGeneral.intSetting("Duration").description("Milliseconds to ease in and out; 0 snaps.").defaultValue(150).range(0, 600).build();
	private final BoolSetting smoothCamera = sgGeneral.bool("Smooth Camera").description("Use Minecraft's cinematic camera while zoomed.").build();
	private final DoubleSetting smoothCameraSpeed = sgGeneral.doubleSetting("Smooth Camera Speed").description("Look speed multiplier with the smooth camera.").defaultValue(1)
		.range(0.1, 3).decimals(1).visible(smoothCamera::get).build();

	/** How far in the zoom is, 0 (off) to 1 (all the way), before easing. Moves at a steady rate towards on or off. */
	private double t;
	private long lastFrameMs;
	/** The zoom right now, scrolled in or out from {@link #factor}. */
	private double zoom;
	/** The cinematic camera option as it was before this module turned it on; null while it's untouched. */
	private Boolean cinematicBefore;

	public Zoom() {
		super(Categories.RENDER, "Zoom", "Zooms the camera in.");
	}

	@Override
	public int settingsVersion() {
		return 2;
	}

	@Override
	protected void migrateSettings(int fromVersion, SavedSettings saved) {
		// 1 had a Smooth toggle and a Speed; easing is now a duration, and 0 is what Smooth off meant.
		if (fromVersion == 1) {
			saved.get("General", "Smooth").filter(v -> !v.getAsBoolean()).ifPresent(v -> saved.set("General", "Duration", new JsonPrimitive(0)));
			saved.remove("General", "Smooth");
			saved.remove("General", "Speed");
		}
	}

	@Override
	public String hudInfo() {
		return String.format("%.1fx", zoom);
	}

	@Override
	protected void onEnable() {
		zoom = factor.get();
		lastFrameMs = System.currentTimeMillis();
	}

	@Override
	protected void onDisable() {
		lastFrameMs = System.currentTimeMillis();
		releaseCinematic();
	}

	@Subscribe
	private void onTick(TickEvent.Post e) {
		if (!smoothCamera.get()) {
			releaseCinematic();
		} else if (mc.options != null) {
			if (cinematicBefore == null) cinematicBefore = mc.options.smoothCamera;
			mc.options.smoothCamera = true;
		}
	}

	@Subscribe
	private void onScroll(MouseScrollEvent e) {
		if (!scroll.get() || mc.gui.screen() != null) return;
		zoom = Mth.clamp(e.vertical() > 0 ? zoom * SCROLL_STEP : zoom / SCROLL_STEP, 1, MAX_ZOOM);
		e.cancel();
	}

	@Subscribe
	private void onLook(MouseLookEvent e) {
		double sensitivity = 1 / magnification();
		if (smoothCamera.get()) sensitivity *= smoothCameraSpeed.get();
		e.set(e.deltaX() * sensitivity, e.deltaY() * sensitivity);
	}

	/** Divides {@code fov} by the current magnification; keeps easing out for a moment after the module is turned off. */
	public static float apply(float fov) {
		Zoom m = Modules.get(Zoom.class);
		return m == null ? fov : (float) (fov / m.magnification());
	}

	/**
	 * 1 when off, the zoom when fully on. In between it's {@code zoom ^ eased}, so each frame of the ease magnifies by
	 * the same ratio and the motion looks even rather than rushing at the start.
	 */
	private double magnification() {
		advance();
		if (t <= 0) return 1;
		return Math.pow(Math.max(1, zoom), Easing.IN_OUT_QUAD.ease(t));
	}

	private void advance() {
		long now = System.currentTimeMillis();
		double step = duration.get() == 0 ? 1 : (now - lastFrameMs) / (double) duration.get();
		lastFrameMs = now;
		t = isEnabled() ? Math.min(1, t + step) : Math.max(0, t - step);
	}

	private void releaseCinematic() {
		if (cinematicBefore == null) return;
		if (mc.options != null) mc.options.smoothCamera = cinematicBefore;
		cinematicBefore = null;
	}
}
