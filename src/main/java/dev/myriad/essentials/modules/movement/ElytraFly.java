package dev.myriad.essentials.modules.movement;

import dev.myriad.api.Myriad;
import dev.myriad.api.event.Priority;
import dev.myriad.api.event.Subscribe;
import dev.myriad.api.event.events.InputEvent;
import dev.myriad.api.event.events.InteractEvent;
import dev.myriad.api.event.events.PacketEvent;
import dev.myriad.api.event.events.TickEvent;
import dev.myriad.api.module.Categories;
import dev.myriad.api.module.Module;
import dev.myriad.api.module.Modules;
import dev.myriad.api.service.Breaking;
import dev.myriad.api.service.Rotations;
import dev.myriad.api.setting.EnumSetting;
import dev.myriad.api.util.ItemInfo;
import dev.myriad.api.util.Baritone;
import dev.myriad.api.util.Interactions;
import dev.myriad.api.util.Packets;
import dev.myriad.essentials.util.ChestSwap;
import dev.myriad.essentials.util.GlideHold;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;

/**
 * Two ways to fly an elytra, by what you're doing:
 * <ul>
 * <li><b>Recast</b> bounces along a highway along the ground. It jumps each time you touch down and keeps you on the nearest 45°
 * lane. The client keeps gliding through each ground touch instead of letting the elytra close; the server still closes
 * it on landing, so it's reopened (one packet) once you're back in the air, but your own physics never drop to walking
 * while that round trip happens. Jump is only pressed on the ticks you're on the ground and forward is never pressed
 * (strict anticheats check both against the glide); sprint stays on so every jump still adds its boost. The pitch dives
 * while you're rising and levels out as you fall, which turns each bounce into the most forward speed. The server
 * stops the glide at each landing; the answers to Grim's pings from then on are held back until you're in the air again
 * (see GlideHold), so Grim keeps expecting the glide the client keeps up, and the elytra is opened again (with a jump
 * press, as vanilla does) right after they're sent. The server's glide restarts every hop, so the elytra doesn't wear
 * either (with No Durability, long glides are cut with a chestplate, paused while you eat). Something in the lane
 * (an ender chest, a portal, a wall) stops the bounce, and Obstacles decides what happens next: stop, mine through it,
 * or have Baritone walk you round it and carry on bouncing past it.</li>
 * <li><b>Altitude</b> crosses open country without fireworks, "pitch 40" style. It dives until you're fast, pulls up hard, then eases
 * back to level: pulling up gives back more height than the speed it costs, so the cycle holds the altitude you started
 * gliding at (it dives harder when you're above it and climbs more when you're below). Start high, since the first dive
 * from a slow glide drops you 50 or so blocks. Steer with the camera. Works with Elytra Tweaks' No Durability, which
 * swaps the chestplate on and off meanwhile.</li>
 * </ul>
 * The flight rotation only goes to the server and the flight physics, so you can look around freely.
 */
public class ElytraFly extends Module {
	public enum Mode {
		RECAST, ALTITUDE
	}

	public enum Obstacles {
		STOP, MINE, BARITONE
	}

	private final EnumSetting<Mode> mode = sgGeneral.enumSetting("Mode", Mode.RECAST)
		.description("Recast to bounce along a highway, Altitude to cross open country without fireworks.").build();
	private final EnumSetting<Obstacles> obstacles = sgGeneral.enumSetting("Obstacles", Obstacles.MINE)
		.description("What to do about blocks in the lane: stop, mine them, or walk round with Baritone (mines if Baritone isn't installed).")
		.visible(() -> mode.get() == Mode.RECAST).build();

	private enum State {
		IDLE, BOUNCING, MINING, PATHING
	}

	private enum Phase {
		DIVE, PULL_UP, CLIMB
	}

	/** Recast: dive while vertical speed is above this, level out below it. */
	private static final double DIVE_UNTIL_Y = -0.2;
	/** Recast: how far ahead to look for blocks in the lane, and how far past them Baritone walks. */
	private static final double LOOK_AHEAD = 3.5, BYPASS_DISTANCE = 8;
	/** How far apart the swept hitboxes are along the lane. */
	private static final double SWEEP_STEP = 0.25;
	/** Recast: ticks to stop after the server snaps you back. */
	private static final int FLAG_PAUSE = 5;
	/**
	 * Altitude, worked out on vanilla's glide physics: dive at {@code DIVE_PITCH} until faster than the threshold (blocks a
	 * second), snap up to {@code CLIMB_PITCH} at {@code PULL_RATE} a tick, then ease back to level at {@code EASE_RATE}.
	 * The threshold rises by {@code HOLD_GAIN} for every block above the cruise height (more speed, less climb) and falls
	 * below it, which keeps the cycle at that height: about 28 blocks a second.
	 */
	private static final float DIVE_PITCH = 36, CLIMB_PITCH = -49, PULL_RATE = 5, EASE_RATE = 0.8f;
	private static final double DIVE_SPEED = 52, HOLD_GAIN = 0.2, MIN_DIVE_SPEED = 36, MAX_DIVE_SPEED = 58;

	private Mode activeMode = Mode.RECAST;
	private State state = State.IDLE;
	private Phase phase = Phase.DIVE;
	private float climbPitch;
	private double cruiseY = Double.NaN;

	private boolean wantJump, spoofing;
	/** Open the elytra this tick (see {@link #startGliding}); and whether jump was pressed last tick. */
	private boolean wantOpen, jumpedLastTick;
	/** Keep gliding client-side through ground touches; set once the elytra has opened while bouncing. */
	private boolean holdGlide;
	private volatile boolean flagged;
	private int pauseTicks;
	private double groundY;
	/** Recast: ticks since you last touched the ground. */
	private int airTicks;
	/** Recast: swapping the elytra with a chestplate (No Durability) to end glides that run long. */
	private boolean chestMode;
	/** Recast: ticks the server has been gliding without a stop; and the most before the chestplate ends it. */
	private int longGlide;
	private static final int LONG_GLIDE = 12;
	private float lane, spoofYaw, spoofPitch;
	private BlockPos mining;
	private int pathWait;

	public ElytraFly() {
		super(Categories.MOVEMENT, "Elytra Fly", "Bounce along highways, or cross open country without fireworks.");
	}

	@Override
	protected void onEnable() {
		activeMode = mode.get();
		reset();
		if (inGame()) lane = snap(mc.player.getYRot());
	}

	@Override
	protected void onDisable() {
		reset();
	}

	private void reset() {
		if (state == State.PATHING) Baritone.stop();
		state = State.IDLE;
		phase = Phase.DIVE;
		cruiseY = groundY = Double.NaN;
		wantJump = spoofing = holdGlide = flagged = false;
		letGo();
		putElytraBack();
		pathWait = pauseTicks = 0;
		stopMining();
	}

	@Override
	public String hudInfo() {
		if (activeMode == Mode.ALTITUDE) return Double.isNaN(cruiseY) ? "Altitude" : String.format("Y%.0f", cruiseY);
		return switch (state) {
			case MINING -> "Mining";
			case PATHING -> "Baritone";
			case BOUNCING -> pauseTicks > 0 ? "Flagged" : String.format("%.0f°", lane);
			case IDLE -> null;
		};
	}

	// ---- hooks used by this addon's mixins ------------------------------------------------------------------------

	/** Whether flight physics should use the spoofed rotation instead of the camera's. */
	public static boolean spoofing() {
		ElytraFly m = Modules.active(ElytraFly.class);
		return m != null && m.spoofing;
	}

	/** The rotation flight physics should use; only meaningful while {@link #spoofing()}. */
	public static float spoofYaw() {
		ElytraFly m = Modules.get(ElytraFly.class);
		return m == null ? 0 : m.spoofYaw;
	}

	public static float spoofPitch() {
		ElytraFly m = Modules.get(ElytraFly.class);
		return m == null ? 0 : m.spoofPitch;
	}

	/** Whether the local player should count as gliding even though the elytra closed (it closes on every landing). */
	public static boolean holdsGlide() {
		ElytraFly m = Modules.active(ElytraFly.class);
		return m != null && m.holdGlide;
	}

	/**
	 * Keep sprinting while gliding, so each jump off the ground adds the sprint boost without holding forward: when you
	 * could sprint at all (enough food, not blind), as Grim checks that.
	 */
	public static boolean holdsSprint() {
		var p = Minecraft.getInstance().player;
		return holdsGlide() && p != null && p.getFoodData().hasEnoughFood() && !p.hasEffect(MobEffects.BLINDNESS);
	}

	/** Whether the bounce is swapping the elytra with a chestplate (Auto Armor leaves the chest slot alone meanwhile). */
	public static boolean swapsChest() {
		ElytraFly m = Modules.active(ElytraFly.class);
		return m != null && m.chestMode && m.state == State.BOUNCING;
	}

	// ---- tick -----------------------------------------------------------------------------------------------------

	@Subscribe
	private void onInput(InputEvent e) {
		if (wantJump) e.jump = true;
		// Vanilla opens the elytra when jump goes from released to pressed in the air: release it for a tick if it's
		// held, then press it.
		if (wantOpen) e.jump = !jumpedLastTick;
		jumpedLastTick = e.jump;
	}

	/**
	 * Something you hold to use (food, a potion) waits while the chestplate is on: putting the elytra back at the next
	 * redeploy is a swap, which Grim takes as using another item and stops what you were using. It's used once the
	 * elytra is back (Auto Eat tries again, and holding right click uses it again); no swaps run meanwhile.
	 */
	@Subscribe
	private void onUse(InteractEvent.Item e) {
		if (state != State.BOUNCING || !chestMode || !inGame() || ChestSwap.elytraWorn()) return;
		if (mc.player.getItemInHand(e.hand()).getUseDuration(mc.player) > 0) e.cancel();
	}

	@Subscribe(packets = ClientboundPlayerPositionPacket.class)
	private void onPacket(PacketEvent.Receive e) {
		flagged = true;
	}

	/**
	 * Before any of this tick's actions (a redeploy sends held ping answers, which must come first: Grim's Post), and
	 * before Elytra Tweaks, so the flight rotation is asked for before it fixes this tick's rotation.
	 */
	@Subscribe(priority = Priority.BEFORE_ACTIONS + 10)
	private void onTick(TickEvent.Pre e) {
		wantJump = wantOpen = false;
		if (!inGame()) return;
		if (mode.get() != activeMode) {
			reset();
			activeMode = mode.get();
		}
		if (activeMode == Mode.ALTITUDE) tickAltitude();
		else tickBounce();
	}

	// ---- Altitude -------------------------------------------------------------------------------------------------

	private void tickAltitude() {
		flagged = false;
		if (!canGlide()) {
			spoofing = false;
			return;
		}
		var p = mc.player;
		if (!p.isFallFlying()) {
			spoofing = false;
			phase = Phase.DIVE;
			cruiseY = Double.NaN;
			// Open the elytra once you're falling: off a ledge, or on the way down from a jump.
			if (!p.onGround() && p.getDeltaMovement().y < -0.3) startGliding();
			return;
		}
		if (Double.isNaN(cruiseY)) cruiseY = p.getY();

		double speed = p.getDeltaMovement().length() * 20;
		double diveUntil = Mth.clamp(DIVE_SPEED + HOLD_GAIN * (p.getY() - cruiseY), MIN_DIVE_SPEED, MAX_DIVE_SPEED);
		float flightPitch = switch (phase) {
			case DIVE -> {
				if (speed > diveUntil) {
					phase = Phase.PULL_UP;
					climbPitch = 0;
				}
				yield DIVE_PITCH;
			}
			case PULL_UP -> {
				climbPitch = Math.max(CLIMB_PITCH, climbPitch - PULL_RATE);
				if (climbPitch <= CLIMB_PITCH) phase = Phase.CLIMB;
				yield climbPitch;
			}
			case CLIMB -> {
				climbPitch = Math.min(0, climbPitch + EASE_RATE);
				if (climbPitch >= 0) phase = Phase.DIVE;
				yield climbPitch;
			}
		};
		spoof(p.getYRot(), flightPitch);
	}

	// ---- Recast ---------------------------------------------------------------------------------------------------

	private void tickBounce() {
		if (flagged) {
			flagged = false;
			pauseTicks = FLAG_PAUSE;
		}
		if (state == State.PATHING) {
			tickPathing();
			return;
		}
		if (!canGlide()) {
			stopBouncing();
			state = State.IDLE;
			return;
		}
		if (state != State.MINING) lane = snap(mc.player.getYRot());

		List<BlockPos> blocked = laneBlocks();
		if (!blocked.isEmpty()) {
			handleObstacle(blocked);
			return;
		}
		if (state == State.MINING) stopMining();
		state = State.BOUNCING;
		if (pauseTicks > 0) {
			// The server rejected a move: let its correction land before bouncing on from there.
			pauseTicks--;
			spoofing = holdGlide = false;
			letGo();
			return;
		}
		GlideHold.arm(this);
		spoof(lane, mc.player.getDeltaMovement().y > DIVE_UNTIL_Y ? 90 : 4);
		// Vanilla waits 10 ticks between held jumps; jump the tick you touch down.
		Interactions.setJumpCooldown(0);
		boolean ground = mc.player.onGround();
		airTicks = ground ? 0 : airTicks + 1;
		if (ground) {
			groundY = mc.player.getY();
			wantJump = true;
		}

		boolean gliding = elytraOpen();
		if (gliding) holdGlide = true;
		// With Elytra Tweaks' No Durability: the server wears the elytra once one of its glides lasts 20 ticks, which a
		// hop never does unless the server misses the landing (packets bunched up) or you glide off an edge. A glide
		// running that long gets the chestplate put on, so the server stops it first; it's opened again as usual.
		// Not while you eat (or use anything held): Grim stops it at every swap (see onUse).
		chestMode = ElytraTweaks.noDurability() && ChestSwap.ready() && !mc.player.isUsingItem();
		boolean cleared = GlideHold.cleared(this);
		longGlide = cleared || !gliding || ground || !ChestSwap.elytraWorn() ? 0 : longGlide + 1;
		if (cleared && !ground && airTicks >= 2) {
			// The server stopped the glide (at the landing, or when the chestplate went on) and the client kept it up:
			// start it again now you're back in the air, the held ping answers first (on the tick jump goes down) so
			// Grim sees the stop, then the start.
			if (!jumpedLastTick) {
				GlideHold.release(this);
				// The elytra is off (a long glide was stopped): it goes back on with the start, sent here.
				if (!(chestMode && !ChestSwap.elytraWorn() && ChestSwap.startGlide())) {
					// Not gliding for vanilla's check this tick, so its jump press opens the elytra (and sends the start).
					holdGlide = false;
					mc.player.stopFallFlying();
				}
			}
			startGliding();
		} else if (!gliding && !ground) {
			// Take off the same way, after the first jump or off a ledge (the elytra on first, if it's off).
			if (chestMode) ChestSwap.restoreElytra();
			startGliding();
		} else if (chestMode && longGlide >= LONG_GLIDE) {
			ChestSwap.swap();
			longGlide = 0;
		}
	}

	private void handleObstacle(List<BlockPos> blocked) {
		stopBouncing();
		Obstacles how = obstacles.get();
		if (how == Obstacles.BARITONE && Baritone.isAvailable()) {
			startPathing();
			return;
		}
		if (how == Obstacles.STOP) {
			state = State.IDLE;
			return;
		}
		// Unbreakable blocks (bedrock, barriers) can only be stopped at or walked round.
		for (BlockPos pos : blocked) {
			if (mc.level.getBlockState(pos).getDestroySpeed(mc.level, pos) < 0) continue;
			state = State.MINING;
			mine(pos);
			return;
		}
		state = State.IDLE;
	}

	private void stopBouncing() {
		spoofing = false;
		holdGlide = false;
		letGo();
		putElytraBack();
		if (state == State.BOUNCING || state == State.MINING) pauseFlight();
	}

	/**
	 * Lets go of a held glide stop: Grim gets its answers and takes the glide as stopped, as the server already does, so
	 * the client stops gliding there too.
	 */
	private void letGo() {
		boolean cleared = GlideHold.cleared(this);
		GlideHold.disarm(this);
		if (cleared && mc.player != null && elytraOpen()) mc.player.stopFallFlying();
	}

	/** After bouncing with the chestplate on, the elytra goes back on. */
	private void putElytraBack() {
		if (chestMode && mc.player != null) ChestSwap.restoreElytra();
		chestMode = false;
	}

	// ---- Baritone -------------------------------------------------------------------------------------------------

	private void startPathing() {
		Vec3 lane = laneDir();
		Vec3 target = mc.player.position().add(lane.scale(LOOK_AHEAD + BYPASS_DISTANCE));
		// Come back to the lane's own level, not wherever the obstacle left you standing.
		int y = Double.isNaN(groundY) ? mc.player.getBlockY() : Mth.floor(groundY + 0.5);
		if (!Baritone.pathTo(Mth.floor(target.x), y, Mth.floor(target.z))) {
			state = State.MINING;
			return;
		}
		state = State.PATHING;
		pathWait = 0;
		info("Lane blocked, walking round it with Baritone");
	}

	/** Waits for Baritone to get past the obstacle, then bounces on (or gives up after a few seconds without a path). */
	private void tickPathing() {
		spoofing = false;
		holdGlide = false;
		letGo();
		if (Baritone.isPathing()) {
			pathWait = 0;
			return;
		}
		// Give the pathfinder a moment to start before deciding it's done or failed.
		if (++pathWait < 40) return;
		state = State.IDLE;
		if (laneBlocks().isEmpty()) return;
		warn("Baritone couldn't get past the obstacle");
		disable();
	}

	// ---- rotation and flight --------------------------------------------------------------------------------------

	/** Flies (and tells the server) with this rotation, leaving the camera alone. */
	private void spoof(float yaw, float pitch) {
		spoofYaw = Mth.wrapDegrees(yaw);
		spoofPitch = pitch;
		spoofing = true;
		Myriad.rotations().request(this, spoofYaw, spoofPitch, Rotations.PRIORITY_HIGH + 50);
	}

	/**
	 * Opens the elytra the way vanilla does: by pressing jump in the air after a tick with it released, so vanilla
	 * sends the start itself, before the tick's input and movement. Grim checks exactly that (a start with jump released
	 * the tick before and pressed in the same tick, and no two starts in a row); a start sent on its own is set back.
	 */
	private void startGliding() {
		wantOpen = true;
	}

	/** Whether the elytra is really open (as the server last said, or as we just asked), ignoring {@link #holdsGlide()}. */
	private boolean elytraOpen() {
		boolean held = holdGlide;
		holdGlide = false;
		boolean open = mc.player.isFallFlying();
		holdGlide = held;
		return open;
	}

	/** Stops in front of an obstacle: no more forward speed, the elytra closed, falling allowed. */
	private void pauseFlight() {
		holdGlide = false;
		var p = mc.player;
		if (p.isFallFlying()) p.stopFallFlying();
		p.setDeltaMovement(0, Math.min(0, p.getDeltaMovement().y), 0);
		p.hurtMarked = true;
	}

	/** Whether the player could glide right now: an elytra on (or swappable on), and nothing vanilla forbids it for. */
	private boolean canGlide() {
		var p = mc.player;
		boolean blocked = p.getAbilities().flying || p.isPassenger() || p.isInWater() || p.onClimbable() || p.hasEffect(MobEffects.LEVITATION);
		if (blocked) return false;
		// With No Durability a chestplate may be worn, with the elytra at hand to swap on.
		return ItemInfo.isGlider(p.getItemBySlot(EquipmentSlot.CHEST)) || (ElytraTweaks.noDurability() && ChestSwap.hasGlider());
	}

	/** The way the lane runs. */
	private Vec3 laneDir() {
		return Vec3.directionFromRotation(0, lane);
	}

	/** The nearest of the eight highway directions. */
	private static float snap(float yaw) {
		return Mth.wrapDegrees(45f * Math.round(yaw / 45f));
	}

	// ---- mining ---------------------------------------------------------------------------------------------------

	private void mine(BlockPos pos) {
		BlockState block = mc.level.getBlockState(pos);
		if (isPortal(block)) {
			// Portals break instantly server-side but not client-side: send the dig directly.
			Direction face = Direction.getApproximateNearest(-laneDir().x, 0, -laneDir().z);
			Packets.sendSequenced(seq -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, face, seq));
			Packets.sendSequenced(seq -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, face, seq));
			mc.player.swing(InteractionHand.MAIN_HAND);
		} else {
			// The breaking service picks and holds the tool, and swings.
			Myriad.breaking().breakBlock(this, pos, Rotations.PRIORITY_HIGH, Breaking.Options.DEFAULT);
		}
		mining = pos.immutable();
	}

	private void stopMining() {
		mining = null;
		Myriad.breaking().cancel(this);
	}

	/**
	 * What's in the way: the blocks the player's hitbox would run into sliding {@link #LOOK_AHEAD} blocks along the lane
	 * (a little narrower than the hitbox, so brushing a wall doesn't count), nearest first.
	 */
	private List<BlockPos> laneBlocks() {
		Vec3 dir = laneDir();
		// Lifted off the floor (so the ground isn't an obstacle) and with room above the head for the bounce.
		AABB box = mc.player.getBoundingBox();
		AABB body = box.inflate(-0.2, 0, -0.2).setMinY(box.minY + 0.2).setMaxY(box.maxY + 0.5);
		Set<BlockPos> hits = new LinkedHashSet<>();
		for (double d = SWEEP_STEP; d <= LOOK_AHEAD + 1e-9; d += SWEEP_STEP) {
			AABB at = body.move(dir.scale(d));
			for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(at.minX, at.minY, at.minZ), BlockPos.containing(at.maxX, at.maxY, at.maxZ))) {
				if (!hits.contains(pos) && obstructs(pos, at)) hits.add(pos.immutable());
			}
		}
		List<BlockPos> sorted = new ArrayList<>(hits);
		Vec3 eye = mc.player.position();
		sorted.sort(Comparator.comparingDouble(pos -> pos.distToCenterSqr(eye)));
		return sorted;
	}

	/** Whether the block at {@code pos} is something to clear: a portal, or a collision shape that meets {@code box}. */
	private boolean obstructs(BlockPos pos, AABB box) {
		BlockState state = mc.level.getBlockState(pos);
		if (isPortal(state)) return true;
		if (state.isAir() || !state.getFluidState().isEmpty()) return false;
		return state.getCollisionShape(mc.level, pos).toAabbs().stream().anyMatch(part -> part.move(pos).intersects(box));
	}

	private static boolean isPortal(BlockState state) {
		return state.is(Blocks.NETHER_PORTAL) || state.is(Blocks.END_PORTAL) || state.is(Blocks.END_GATEWAY);
	}
}
