package dev.myriad.essentials.modules.render;

import dev.myriad.api.event.Subscribe;
import dev.myriad.api.event.events.CameraEvent;
import dev.myriad.api.event.events.MouseLookEvent;
import dev.myriad.api.event.events.MouseScrollEvent;
import dev.myriad.api.module.Categories;
import dev.myriad.api.module.Module;
import dev.myriad.api.module.Modules;
import dev.myriad.api.setting.BoolSetting;
import dev.myriad.api.setting.EnumSetting;
import net.minecraft.client.CameraType;
import net.minecraft.util.Mth;

/**
 * Look around without turning: the camera orbits the middle of your player in third person while your player keeps
 * facing (and moving) the same way. The scroll wheel moves the camera nearer or further. Works well as a hold bind.
 */
public class FreeLook extends Module {
	public enum View {
		BEHIND, FRONT
	}

	/** Vanilla's third-person distance, where each use starts. */
	private static final float START_DISTANCE = 4;
	private static final float MIN_DISTANCE = 1, MAX_DISTANCE = 30;
	/** How much one scroll notch moves the camera: a ratio, so it feels the same near and far. */
	private static final float SCROLL_STEP = 1.15f;

	private final EnumSetting<View> view = sgGeneral.enumSetting("View", View.BEHIND).description("Which third-person view to use.").build();
	private final BoolSetting snapBack = sgGeneral.bool("Snap Back").description("Return to your previous perspective when turned off.").defaultValue(true).build();

	private float yaw, pitch;
	private CameraType previous;
	/** Where scrolling has put the camera, and where it is now, easing towards it. */
	private float targetDistance = START_DISTANCE, distance = START_DISTANCE;
	private long lastFrameNanos;

	public FreeLook() {
		super(Categories.RENDER, "Free Look", "Orbit the camera without turning your player; scroll to move it nearer or further.");
	}

	@Override
	protected void onEnable() {
		targetDistance = distance = START_DISTANCE;
		lastFrameNanos = System.nanoTime();
		if (!inGame()) return;
		yaw = mc.player.getYRot();
		pitch = mc.player.getXRot();
		previous = mc.options.getCameraType();
		mc.options.setCameraType(view.get() == View.FRONT ? CameraType.THIRD_PERSON_FRONT : CameraType.THIRD_PERSON_BACK);
	}

	@Override
	protected void onDisable() {
		if (snapBack.get() && previous != null) mc.options.setCameraType(previous);
		previous = null;
	}

	@Subscribe
	private void onMouse(MouseLookEvent e) {
		e.cancel();
		yaw += (float) (e.deltaX() * 0.15);
		pitch = Mth.clamp(pitch + (float) (e.deltaY() * 0.15), -90, 90);
	}

	/** The wheel moves the camera instead of changing the hotbar slot. */
	@Subscribe
	private void onScroll(MouseScrollEvent e) {
		if (mc.gui.screen() != null || e.vertical() == 0) return;
		targetDistance = Mth.clamp(e.vertical() > 0 ? targetDistance / SCROLL_STEP : targetDistance * SCROLL_STEP, MIN_DISTANCE, MAX_DISTANCE);
		e.cancel();
	}

	@Subscribe
	private void onCamera(CameraEvent.Rotation e) {
		// Front view looks back at the player, so flip the orbit.
		e.yaw = view.get() == View.FRONT ? yaw + 180 : yaw;
		e.pitch = view.get() == View.FRONT ? -pitch : pitch;
	}

	/** Orbit the middle of the player rather than its eyes, so looking straight up or down circles the whole body. */
	@Subscribe
	private void onPosition(CameraEvent.Position e) {
		if (!inGame() || mc.getCameraEntity() != mc.player || mc.options.getCameraType().isFirstPerson()) return;
		e.y = Mth.lerp(e.tickDelta(), mc.player.yo, mc.player.getY()) + mc.player.getBbHeight() * 0.5;
	}

	/**
	 * How far the third-person camera backs off ({@code vanilla} while this is off). Vanilla still shortens it where a
	 * block is in the way.
	 */
	public static float distance(float vanilla) {
		FreeLook m = Modules.get(FreeLook.class);
		if (m == null || !m.isEnabled()) return vanilla;
		long now = System.nanoTime();
		float seconds = Math.min(0.1f, (now - m.lastFrameNanos) / 1e9f);
		m.lastFrameNanos = now;
		// Eases in about a tenth of a second, whatever the frame rate.
		m.distance += (m.targetDistance - m.distance) * (1 - (float) Math.exp(-seconds * 20));
		return m.distance;
	}
}
