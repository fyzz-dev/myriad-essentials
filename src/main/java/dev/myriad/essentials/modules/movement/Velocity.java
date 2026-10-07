package dev.myriad.essentials.modules.movement;

import dev.myriad.api.event.Priority;
import dev.myriad.api.event.Subscribe;
import dev.myriad.api.event.events.PacketEvent;
import dev.myriad.api.event.events.TickEvent;
import dev.myriad.api.module.Categories;
import dev.myriad.api.module.Module;
import dev.myriad.api.module.Modules;
import dev.myriad.api.setting.BoolSetting;
import dev.myriad.api.setting.DoubleSetting;
import dev.myriad.api.setting.SettingGroup;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Reduces or cancels knockback from hits and explosions, and can stop blocks, liquids and other entities from
 * pushing you. Walls Only cancels knockback only while you're phased into a block (and, with Require Blocked, only
 * knockback that would push you further into it), then gives the cancelled push back once you can move, so the
 * server and client agree. Static stops you dead after a hit when you aren't pressing movement keys.
 */
public class Velocity extends Module {
	/** Smaller pushes than this (blocks per tick, either way) are ordinary movement corrections, not knockback. */
	private static final double MIN_KNOCKBACK = 0.1;
	/** A cancelled push is owed back for at most this long, and never faster than {@link #MAX_OWED}. */
	private static final int OWED_TICKS = 20;
	private static final double MAX_OWED = 0.5;
	/** Vanilla's air drag, so a push that can't be paid back yet fades the way the server's copy of it does. */
	private static final double DRAG_XZ = 0.91, DRAG_Y = 0.98;
	/** Shrinks boxes a hair so touching a wall doesn't count as being inside it. */
	private static final double SKIN = 1.0E-7;

	private final DoubleSetting horizontal = sgGeneral.doubleSetting("Horizontal").description("Percent of horizontal knockback to keep.").defaultValue(0).range(0, 100).decimals(0).build();
	private final DoubleSetting vertical = sgGeneral.doubleSetting("Vertical").description("Percent of vertical knockback to keep.").defaultValue(0).range(0, 100).decimals(0).build();
	private final BoolSetting explosions = sgGeneral.bool("Explosions").defaultValue(true).build();
	private final BoolSetting fishingRods = sgGeneral.bool("Fishing Rods").description("Don't get pulled by fishing rods.").defaultValue(true).build();
	private final BoolSetting pauseLag = sgGeneral.bool("Pause Lag").description("Ignore the zero-velocity packet some servers send right after a setback.").build();
	private final BoolSetting wallsOnly = sgGeneral.bool("Walls Only").description("Only cancel knockback while phased into a block.").build();
	private final BoolSetting trapped = sgGeneral.bool("Trapped").description("Also count a block over your head as phased.").visible(wallsOnly::get).build();
	private final BoolSetting groundOnly = sgGeneral.bool("Ground Only").defaultValue(true).visible(wallsOnly::get).build();
	private final BoolSetting requireBlocked = sgGeneral.bool("Require Blocked").description("Only cancel knockback that would push you further into a block.")
		.defaultValue(true).visible(wallsOnly::get).build();
	private final BoolSetting staticVelocity = sgGeneral.bool("Static").description("Stop dead after knockback when you aren't pressing movement keys.").build();
	private final BoolSetting staticPhasedOnly = sgGeneral.bool("Static Phased Only").description("Only stop dead while phased.").visible(staticVelocity::get).build();

	private final SettingGroup sgPush = settings.group("No Push");
	private final BoolSetting pushBlocks = sgPush.bool("Blocks").description("Don't get pushed out of blocks.").build();
	private final BoolSetting pushLiquids = sgPush.bool("Liquids").description("Don't get pushed by flowing water and lava.").build();
	private final BoolSetting pushEntities = sgPush.bool("Entities").description("Don't get pushed by other entities.").build();

	// Set on the network thread, read on the game thread.
	private volatile boolean skipNextZero, stopNextTick;
	/** Knockback cancelled while phased, given back once there's room (game thread and network thread, so guarded). */
	private Vec3 owed = Vec3.ZERO;
	private int owedFor;

	public Velocity() {
		super(Categories.MOVEMENT, "Velocity", "Reduces knockback and pushing.");
	}

	@Override
	public String hudInfo() {
		if (wallsOnly.get()) return inGame() && collides(Vec3.ZERO) ? "Phase" : "Wait";
		return String.format("H%.0f%% V%.0f%%", horizontal.get(), vertical.get());
	}

	@Override
	protected void onDisable() {
		skipNextZero = stopNextTick = false;
		forgetOwed();
	}

	public static boolean cancelsBlockPush() {
		Velocity m = Modules.active(Velocity.class);
		return m != null && m.pushBlocks.get() && (!m.wallsOnly.get() || m.collides(Vec3.ZERO));
	}

	public static boolean cancelsLiquidPush() {
		Velocity m = Modules.active(Velocity.class);
		return m != null && m.pushLiquids.get();
	}

	public static boolean cancelsEntityPush() {
		Velocity m = Modules.active(Velocity.class);
		return m != null && m.pushEntities.get();
	}

	@Subscribe
	private void onTick(TickEvent.Pre e) {
		if (!inGame()) {
			stopNextTick = false;
			forgetOwed();
			return;
		}
		if (stopNextTick) {
			stopNextTick = false;
			stopIfIdle();
		}
		payOwed();
	}

	@Subscribe(priority = Priority.LOWEST, inGame = true, packets = {ClientboundSetEntityMotionPacket.class, ClientboundExplodePacket.class, ClientboundPlayerPositionPacket.class,
		ClientboundEntityEventPacket.class})
	private void onReceive(PacketEvent.Receive e) {
		switch (e.packet()) {
			case ClientboundSetEntityMotionPacket p when p.id() == mc.player.getId() -> {
				if (skipNextZero && p.movement().lengthSqr() == 0) {
					skipNextZero = false;
					return;
				}
				Vec3 kept = adjust(p.movement().subtract(mc.player.getDeltaMovement()), p.movement());
				if (kept == null) e.cancel();
				else if (kept != p.movement()) e.setPacket(new ClientboundSetEntityMotionPacket(p.id(), kept));
				if (staticVelocity.get()) stopNextTick = true;
			}
			case ClientboundExplodePacket p when explosions.get() && p.playerKnockback().isPresent() -> {
				Vec3 push = p.playerKnockback().get();
				Optional<Vec3> kept = Optional.ofNullable(adjust(push, push));
				// Keep the packet (particles and sound), just change the push.
				e.setPacket(new ClientboundExplodePacket(p.center(), p.radius(), p.blockCount(), kept, p.explosionParticle(), p.explosionSound(), p.blockParticles()));
			}
			case ClientboundPlayerPositionPacket ignored when pauseLag.get() -> skipNextZero = true;
			case ClientboundEntityEventPacket p when fishingRods.get() && p.getEventId() == EntityEvent.FISHING_ROD_REEL_IN -> {
				if (p.getEntity(mc.level) instanceof FishingHook hook && hook.getHookedIn() == mc.player) e.cancel();
			}
			default -> {
			}
		}
	}

	/**
	 * What's left of a knockback: {@code push} is the change it makes, {@code result} the motion it sets. Returns null to
	 * drop it, {@code result} itself to let it through untouched, or a scaled copy.
	 */
	private @Nullable Vec3 adjust(Vec3 push, Vec3 result) {
		if (!wallsOnly.get()) {
			if (horizontal.get() == 0 && vertical.get() == 0) return null;
			double h = horizontal.get() / 100, v = vertical.get() / 100;
			return new Vec3(result.x * h, result.y * v, result.z * h);
		}
		boolean knockback = push.horizontalDistance() >= MIN_KNOCKBACK || push.y >= MIN_KNOCKBACK;
		if (!knockback || !inWall() || (requireBlocked.get() && !pushesInto(push))) return result;
		owe(push);
		return null;
	}

	/** Phased into a block (or, with Trapped, with a block over your head), and on the ground if that's required. */
	private boolean inWall() {
		if (groundOnly.get() && !mc.player.onGround()) return false;
		return collides(Vec3.ZERO) || (trapped.get() && headBlocked());
	}

	/** Whether the player's box, moved by {@code offset}, overlaps a block. */
	private boolean collides(Vec3 offset) {
		AABB box = mc.player.getBoundingBox().move(offset).deflate(SKIN);
		return mc.level.getBlockCollisions(mc.player, box).iterator().hasNext();
	}

	/** A solid block right above the top of your hitbox (standing, sneaking or crawling alike). */
	private boolean headBlocked() {
		AABB box = mc.player.getBoundingBox();
		BlockPos above = BlockPos.containing(box.getCenter().x, box.maxY + 0.5, box.getCenter().z);
		return !mc.level.getBlockState(above).canBeReplaced();
	}

	/** Whether {@code push} (up to two blocks of it) would carry you into a block. */
	private boolean pushesInto(Vec3 push) {
		double length = push.length();
		return length > 1.0E-4 && collides(push.scale(Math.min(1, 2 / length)));
	}

	private void stopIfIdle() {
		if (!staticVelocity.get() || (staticPhasedOnly.get() && !collides(Vec3.ZERO))) return;
		var keys = mc.player.input.keyPresses;
		boolean moving = keys.forward() || keys.backward() || keys.left() || keys.right() || keys.jump();
		if (!moving) mc.player.setDeltaMovement(Vec3.ZERO);
	}

	// ---- knockback owed back ----------------------------------------------------------------------------------------

	private synchronized void owe(Vec3 push) {
		Vec3 total = owed.add(push);
		owed = total.length() > MAX_OWED ? total.normalize().scale(MAX_OWED) : total;
		owedFor = OWED_TICKS;
	}

	/** Adds the owed push to your motion as soon as it no longer leads into a block; until then it fades like motion does. */
	private synchronized void payOwed() {
		if (owedFor == 0) return;
		if (--owedFor == 0 || owed.length() < 0.03) {
			forgetOwed();
		} else if (pushesInto(owed)) {
			owed = owed.multiply(DRAG_XZ, DRAG_Y, DRAG_XZ);
		} else {
			mc.player.setDeltaMovement(mc.player.getDeltaMovement().add(owed));
			forgetOwed();
		}
	}

	private synchronized void forgetOwed() {
		owed = Vec3.ZERO;
		owedFor = 0;
	}
}
