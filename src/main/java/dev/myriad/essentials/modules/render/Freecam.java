package dev.myriad.essentials.modules.render;

import dev.myriad.api.event.Subscribe;
import dev.myriad.api.event.events.CameraEvent;
import dev.myriad.api.event.events.MouseLookEvent;
import dev.myriad.api.event.events.MouseScrollEvent;
import dev.myriad.api.event.events.MovementPacketsEvent;
import dev.myriad.api.event.events.PlayerViewEvent;
import dev.myriad.api.event.events.TickEvent;
import dev.myriad.api.event.events.WorldEvent;
import dev.myriad.api.module.Categories;
import dev.myriad.api.module.Module;
import dev.myriad.api.setting.BoolSetting;
import dev.myriad.api.setting.DoubleSetting;
import net.minecraft.client.Options;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * Fly the camera around while your player stays put. Movement keys move the camera, Space/Shift go up/down.
 */
public class Freecam extends Module {
	private final DoubleSetting speed = sgGeneral.doubleSetting("Speed").description("Blocks per tick.").defaultValue(1.0).range(0.1, 10).decimals(1).build();
	private final DoubleSetting verticalSpeed = sgGeneral.doubleSetting("Vertical Speed").description("Blocks per tick going up and down.").defaultValue(1.0).range(0.1, 10).decimals(1).build();
	private final BoolSetting scrollSpeed = sgGeneral.bool("Scroll Speed").description("Scroll to change speed while flying.").defaultValue(true).build();
	private final BoolSetting interaction = sgGeneral.bool("Interaction").description("Break, place and target from the camera's position.").defaultValue(true).build();
	private final BoolSetting rotate = sgGeneral.bool("Rotate").description("Turn your real player to face where the camera looks.").build();

	private Vec3 position, lastPosition;
	private float yaw, pitch;
	private boolean savedCulling;

	public Freecam() {
		super(Categories.RENDER, "Freecam", "Detach the camera and fly it around.");
	}

	@Override
	protected void onEnable() {
		if (!inGame()) {
			disable();
			return;
		}
		position = lastPosition = mc.gameRenderer.mainCamera().position();
		yaw = mc.player.getYRot();
		pitch = mc.player.getXRot();
		mc.player.input = new CameraInput(mc.options);
		// Occlusion culling is computed from the player's chunk; turn it off so terrain around the camera shows.
		savedCulling = mc.smartCull;
		mc.smartCull = false;
		if (mc.levelRenderer != null) mc.levelRenderer.sectionOcclusionGraph().invalidate();
	}

	@Override
	protected void onDisable() {
		if (mc.player != null) mc.player.input = new KeyboardInput(mc.options);
		mc.smartCull = savedCulling;
		if (mc.levelRenderer != null) mc.levelRenderer.sectionOcclusionGraph().invalidate();
		position = lastPosition = null;
	}

	@Subscribe
	private void onLeave(WorldEvent.Leave e) {
		disable();
	}

	@Subscribe
	private void onTick(TickEvent.Pre e) {
		if (!inGame() || mc.player.isDeadOrDying()) {
			disable();
			return;
		}
		if (!(mc.player.input instanceof CameraInput)) mc.player.input = new CameraInput(mc.options);
		if (rotate.get()) {
			mc.player.setYRot(yaw);
			mc.player.setXRot(pitch);
		}
	}

	@Subscribe
	private void onMovement(MovementPacketsEvent e) {
		if (rotate.get()) {
			e.yaw = yaw;
			e.pitch = pitch;
		}
	}

	@Subscribe
	private void onScroll(MouseScrollEvent e) {
		if (!scrollSpeed.get() || mc.gui.screen() != null) return;
		double k = e.vertical() > 0 ? 1.15 : 1 / 1.15;
		speed.set(Math.clamp(speed.get() * k, 0.1, 10));
		verticalSpeed.set(Math.clamp(verticalSpeed.get() * k, 0.1, 10));
		e.cancel();
	}

	@Override
	public String hudInfo() {
		return String.format("%.1f", speed.get());
	}

	@Subscribe
	private void onMouse(MouseLookEvent e) {
		e.cancel();
		yaw += (float) (e.deltaX() * 0.15);
		pitch = Mth.clamp(pitch + (float) (e.deltaY() * 0.15), -90, 90);
	}

	@Subscribe
	private void onCameraPosition(CameraEvent.Position e) {
		if (position == null) return;
		Vec3 p = lastPosition.lerp(position, e.tickDelta());
		e.x = p.x;
		e.y = p.y;
		e.z = p.z;
	}

	@Subscribe
	private void onCameraRotation(CameraEvent.Rotation e) {
		e.yaw = yaw;
		e.pitch = pitch;
	}

	@Subscribe
	private void onDetached(CameraEvent.Detached e) {
		e.renderSelf = true;
	}

	@Subscribe
	private void onHand(CameraEvent.Hand e) {
		e.cancel();
	}

	@Subscribe
	private void onEyes(PlayerViewEvent.Eyes e) {
		if (interaction.get() && position != null) e.value = lastPosition.lerp(position, e.tickDelta());
	}

	@Subscribe
	private void onLook(PlayerViewEvent.Look e) {
		if (interaction.get()) e.value = Vec3.directionFromRotation(pitch, yaw);
	}

	/** Replaces the player's input: keys move the camera, the player gets no movement. */
	private final class CameraInput extends KeyboardInput {
		private final Options options;

		CameraInput(Options options) {
			super(options);
			this.options = options;
		}

		@Override
		public void tick() {
			keyPresses = Input.EMPTY;
			moveVector = Vec2.ZERO;
			if (position == null) return;
			float forward = axis(options.keyUp.isDown(), options.keyDown.isDown());
			float strafe = axis(options.keyLeft.isDown(), options.keyRight.isDown());
			if (forward != 0 && strafe != 0) {
				forward *= (float) Math.sin(Math.PI / 4);
				strafe *= (float) Math.cos(Math.PI / 4);
			}
			double s = speed.get();
			double rad = Math.toRadians(yaw);
			double dx = (forward * -Math.sin(rad) + strafe * Math.cos(rad)) * s;
			double dz = (forward * Math.cos(rad) + strafe * Math.sin(rad)) * s;
			double v = verticalSpeed.get();
			double dy = options.keyJump.isDown() ? v : options.keyShift.isDown() ? -v : 0;
			lastPosition = position;
			position = position.add(dx, dy, dz);
		}

		private static float axis(boolean positive, boolean negative) {
			return positive == negative ? 0 : positive ? 1 : -1;
		}
	}
}
