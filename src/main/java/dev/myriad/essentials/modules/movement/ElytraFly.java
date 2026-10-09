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
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
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
 * either (with No Durability, long glides are cut with a chestplate, paused while you eat). The highway is fixed when
 * you turn it on (the nearest 45° to where you face, through the middle of the block you stand on), so looking around
 * never steers; drifting off that line turns the flight a little back onto it. Far enough ahead to stop in time (it
 * grows with your speed) it watches for anything in the way: a block (an ender chest, a portal, a wall), a hole you
 * could fall into, or chunks that haven't loaded. It then stops jumping and lands, as you would, and Obstacles decides
 * what happens next: stop, mine through it, or have Baritone walk you to the first clear spot past it and bounce on
 * from there. It also notices when it's stuck, or the server keeps setting you back, and doesn't keep trying.</li>
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
		IDLE, BOUNCING, BRAKING, WALKING, MINING, PATHING, WAITING, BLOCKED, UNDER
	}

	/** What's ahead in the lane, and how far along it. CEILING: too low to hop under, but room to walk. */
	private enum Hazard {
		BLOCKS, CEILING, HOLE, UNLOADED, STUCK
	}

	private record Obstruction(Hazard hazard, double distance, List<BlockPos> blocks) {
	}

	private enum Phase {
		DIVE, PULL_UP, CLIMB
	}

	/** Recast: dive while vertical speed is above this, level out below it. */
	private static final double DIVE_UNTIL_Y = -0.2;
	/**
	 * Recast: how far ahead the lane is watched, in blocks, plus this many ticks of travel at the current speed. A hop
	 * is about 9 ticks in the air, and stopping takes the rest of the hop and a few ticks of sliding once you land.
	 */
	private static final double LOOK_AHEAD = 4, LOOK_AHEAD_TICKS = 14;
	/**
	 * Recast: once stopped, bouncing only starts again with this much clear: what it watches at a usual bounce's speed
	 * (2 blocks a tick), so it doesn't hop, see the obstacle again and stop, over and over, on the way up to it.
	 */
	private static final double RESUME_LOOK_AHEAD = LOOK_AHEAD + 2 * LOOK_AHEAD_TICKS;
	/** How far apart the swept hitboxes are along the lane (each also covers the step to the next). */
	private static final double SWEEP_STEP = 0.5;
	/** What counts as in the way, above the floor: from this (carpets and snow layers don't) to the top of a hop. */
	private static final double FLOOR_CLEARANCE = 0.2, HOP_CLEARANCE = 2.9;
	/** A standing player's height: a ceiling above it (and below a hop's top) can be walked under, not bounced under. */
	private static final double STAND_HEIGHT = 1.8;
	/** Past a low ceiling, how much further a wall or a hole still counts as the thing to deal with first. */
	private static final double UNDER_LOOK_AHEAD = 4;
	/** Recast: ticks to stop after the server snaps you back; and how many snaps within the window mean it's stuck. */
	private static final int FLAG_PAUSE = 5, SETBACK_LIMIT = 3, SETBACK_WINDOW = 100;
	/** Recast: less than this far along the lane in this many ticks of trying means stuck. */
	private static final double STUCK_PROGRESS = 1.5;
	private static final int STUCK_TICKS = 40;
	/**
	 * Recast, keeping to the line: the sideways speed asked for per block off it (blocks a tick), its most, how hard it
	 * damps the sideways speed you have, and the most the flight turns away from the lane, in degrees.
	 */
	private static final double STEER_GAIN = 0.15, STEER_MAX_SPEED = 0.25, STEER_DAMPING = 1.5;
	private static final float STEER_MAX_TURN = 10;
	/** Recast with Baritone: this far off the line, walk back to it; and how far past an obstacle to look for room. */
	private static final double RECENTRE_DISTANCE = 3, PASS_SEARCH = 48;
	/** Within this of a block, mine it rather than walk closer. */
	private static final double MINE_REACH = 3.5;
	/**
	 * Altitude, worked out on vanilla's glide physics: dive at {@code DIVE_PITCH} until faster than the threshold (blocks a
	 * second), snap up to {@code CLIMB_PITCH} at {@code PULL_RATE} a tick, then ease back to level at {@code EASE_RATE}.
	 * The threshold rises by {@code HOLD_GAIN} for every block above the cruise height (more speed, less climb) and falls
	 * below it, which keeps the cycle at that height: about 28 blocks a second.
	 */
	private static final float DIVE_PITCH = 36, CLIMB_PITCH = -49, PULL_RATE = 5, EASE_RATE = 0.8f;
	private static final double DIVE_SPEED = 52, HOLD_GAIN = 0.2, MIN_DIVE_SPEED = 36, MAX_DIVE_SPEED = 58;
	/**
	 * Altitude over terrain: how far ahead the ground is checked (loaded chunks' height maps, along your heading and a
	 * few blocks to each side), and how high above the highest of it the cruise height is kept.
	 */
	private static final int TERRAIN_AHEAD = 160, TERRAIN_STEP = 4, TERRAIN_SIDE = 4;
	private static final double TERRAIN_CLEARANCE = 24;
	/** Altitude: closer than this many ticks of flight, ground up to this far below you means pull up, whatever the cycle. */
	private static final int PULL_UP_TICKS = 60;
	private static final double PULL_UP_MARGIN = 8;
	/**
	 * Altitude: the pitch it comes down at once the elytra is wearing out, and how fast that descends (blocks a second,
	 * on the slow side). The server wears a point every second of gliding; with fewer left than the way down takes (and
	 * a margin), it lands while it still can.
	 */
	private static final float LAND_PITCH = 20;
	private static final double LAND_DESCENT = 3, LAND_MARGIN = 30;
	/** Altitude, landing: this close above the ground it levels out, so the touchdown is gentle (and the server's fall count forgotten). */
	private static final double FLARE_HEIGHT = 16;

	private Mode activeMode = Mode.RECAST;
	private State state = State.IDLE;
	private Phase phase = Phase.DIVE;
	private float climbPitch;
	private double cruiseY = Double.NaN;
	/** Altitude: coming down to land, the elytra being nearly worn out. */
	private boolean landing;

	private boolean wantJump, wantForward, spoofing;
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
	/**
	 * Recast: ticks since the server's glide last (re)started, counted until the server says it stopped, landings
	 * included: it can miss one (the landing and the next hop's packets handled in the same server tick, with jitter or
	 * low TPS) and glide on through the next hop. The chestplate ends it at {@link #LONG_GLIDE}, room for jitter and lag
	 * short of the 20 that wear the elytra.
	 */
	private int longGlide;
	private static final int LONG_GLIDE = 10;
	/** Recast: a long glide is only cut this close to the ground, so dropping out of it can't cost a fall. */
	private static final double CUT_HEIGHT = 2.5;
	private float spoofYaw, spoofPitch;
	/** Recast: the lane's direction (one of the eight highway directions, NaN until fixed) and a point on its line. */
	private float lane = Float.NaN;
	private Vec3 lineOrigin;
	private BlockPos mining;
	private int portalWait;
	/** Recast with Baritone: where it's walking you, and how long it's been idle on the way. */
	private Vec3 pathTarget;
	private int pathWait, pathTries;
	/** Recast: how far along the lane you were each of the last {@link #STUCK_TICKS} ticks spent trying to move. */
	private final double[] progress = new double[STUCK_TICKS];
	private int progressCount;
	/** Recast: game times of recent setbacks. */
	private final java.util.ArrayDeque<Long> setbacks = new java.util.ArrayDeque<>();
	/** Recast: stuck, or set back too often: the lane counts as blocked until it's passed. */
	private boolean forcedBlock;
	private boolean warned, chestWarned;

	public ElytraFly() {
		super(Categories.MOVEMENT, "Elytra Fly", "Bounce along highways, or cross open country without fireworks.");
	}

	@Override
	protected void onEnable() {
		activeMode = mode.get();
		reset();
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
		landing = false;
		wantJump = wantForward = spoofing = holdGlide = flagged = forcedBlock = warned = chestWarned = false;
		letGo();
		putElytraBack();
		pathWait = pathTries = pauseTicks = progressCount = 0;
		pathTarget = null;
		lane = Float.NaN;
		setbacks.clear();
		stopMining();
	}

	@Override
	public String hudInfo() {
		if (activeMode == Mode.ALTITUDE) return landing ? "Landing" : Double.isNaN(cruiseY) ? "Altitude" : String.format("Y%.0f", cruiseY);
		return switch (state) {
			case BRAKING -> "Stopping";
			case WALKING, MINING -> "Mining";
			case UNDER -> "Low ceiling";
			case PATHING -> "Baritone";
			case WAITING -> "Loading";
			case BLOCKED -> "Blocked";
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

	/** Whether Recast is bouncing: it lets the glide drop and opens it again itself, every hop. */
	public static boolean bouncing() {
		ElytraFly m = Modules.active(ElytraFly.class);
		return m != null && m.activeMode == Mode.RECAST && m.state != State.IDLE;
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
		if (wantForward) {
			e.forward = e.sprint = true;
			e.backward = e.left = e.right = false;
		}
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
		wantJump = wantOpen = wantForward = false;
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
			if (p.onGround()) landing = false;
			// Open the elytra once you're falling: off a ledge, or on the way down from a jump.
			if (!p.onGround() && p.getDeltaMovement().y < -0.3) startGliding();
			return;
		}
		if (Double.isNaN(cruiseY)) cruiseY = p.getY();
		if (!landing && elytraWearingOut()) {
			landing = true;
			warn("Elytra nearly worn out: coming down to land");
		}
		// Higher ground ahead raises the cruise height in time to clear it (it's never lowered again by itself).
		Vec3 heading = Vec3.directionFromRotation(0, p.getYRot());
		cruiseY = Math.max(cruiseY, groundAhead(heading, TERRAIN_AHEAD) + TERRAIN_CLEARANCE);

		double speed = p.getDeltaMovement().length() * 20;
		// Too close to clear by cruising: pull up and keep climbing (slowing down if it must: settling onto a slope
		// slowly beats hitting it at full speed).
		double near = Math.max(16, Math.sqrt(p.getDeltaMovement().horizontalDistanceSqr()) * PULL_UP_TICKS);
		if (groundAhead(heading, near) > p.getY() - PULL_UP_MARGIN) {
			climbPitch = CLIMB_PITCH;
			phase = Phase.CLIMB;
			spoof(p.getYRot(), CLIMB_PITCH);
			return;
		}
		if (landing) {
			double above = p.getY() - mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, p.getBlockX(), p.getBlockZ());
			spoof(p.getYRot(), above > FLARE_HEIGHT ? LAND_PITCH : 0);
			return;
		}
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

	/**
	 * Whether the worn elytra has fewer uses left than gliding down from here takes (a point a second while the server
	 * glides you), with a margin. No Durability keeps it from wearing, but only while it can swap.
	 */
	private boolean elytraWearingOut() {
		var p = mc.player;
		ItemStack chest = p.getItemBySlot(EquipmentSlot.CHEST);
		if (!ItemInfo.isGlider(chest) || !chest.isDamageableItem()) return false;
		double above = p.getY() - mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, p.getBlockX(), p.getBlockZ());
		return ItemInfo.durability(chest) < above / LAND_DESCENT + LAND_MARGIN;
	}

	/**
	 * The highest ground (any block that stops movement, trees and water included) within {@code distance} blocks
	 * along {@code heading}, and {@link #TERRAIN_SIDE} to each side; only chunks that have loaded count.
	 */
	private double groundAhead(Vec3 heading, double distance) {
		var p = mc.player;
		double best = Double.NEGATIVE_INFINITY;
		for (double d = 0; d <= distance; d += TERRAIN_STEP) {
			for (int side = -TERRAIN_SIDE; side <= TERRAIN_SIDE; side += TERRAIN_SIDE) {
				int x = Mth.floor(p.getX() + heading.x * d - heading.z * side), z = Mth.floor(p.getZ() + heading.z * d + heading.x * side);
				if (!mc.level.hasChunk(x >> 4, z >> 4)) continue;
				best = Math.max(best, mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
			}
		}
		return best;
	}

	// ---- Recast ---------------------------------------------------------------------------------------------------

	private void tickBounce() {
		if (flagged) {
			flagged = false;
			pauseTicks = FLAG_PAUSE;
			countSetback();
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
		if (Float.isNaN(lane)) fixLine();
		if (mc.player.onGround()) groundY = mc.player.getY();

		double range = state == State.BOUNCING ? lookAhead() : Math.max(lookAhead(), RESUME_LOOK_AHEAD);
		Obstruction ahead = forcedBlock ? new Obstruction(Hazard.STUCK, 0, List.of()) : scanAhead(range);
		if (ahead == null && obstacles.get() == Obstacles.BARITONE && Baritone.isAvailable()
			&& Math.abs(offLine(mc.player.position())) > RECENTRE_DISTANCE) {
			// Well off the highway (a setback, or the way round something): walk back onto it first.
			ahead = new Obstruction(Hazard.STUCK, 0, List.of());
		}
		if (ahead != null) {
			handleObstacle(ahead);
			return;
		}
		if (state != State.BOUNCING) {
			stopMining();
			state = State.BOUNCING;
			warned = false;
			progressCount = 0;
		}
		if (pauseTicks > 0) {
			// The server rejected a move: let its correction land before bouncing on from there. A glide the server
			// started before it still gets cut short meanwhile.
			pauseTicks--;
			spoofing = holdGlide = false;
			letGo();
			cutLongGlide(elytraOpen(), false);
			progressCount = 0;
			return;
		}
		if (trackStuck()) return;
		bounce();
	}

	/** One tick of the bounce itself. */
	private void bounce() {
		GlideHold.arm(this);
		spoof(steeredYaw(), mc.player.getDeltaMovement().y > DIVE_UNTIL_Y ? 90 : 4);
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
		// running that long gets the chestplate put on, so the server stops it first; it's opened again as usual. No
		// chestplate in the hotbar or off hand: one is brought in from the inventory.
		// Not while you eat (or use anything held): Grim stops it at every swap (see onUse).
		chestMode = ElytraTweaks.noDurability() && ChestSwap.ready() && !mc.player.isUsingItem();
		if (ElytraTweaks.noDurability() && ChestSwap.pair() == null && !ChestSwap.fetchChestplate() && !chestWarned) {
			warn("No Durability needs a chestplate in your inventory to keep the bounce from wearing the elytra.");
			chestWarned = true;
		}
		boolean cleared = GlideHold.cleared(this);
		// What's worn only counts once the server has answered the last swap (see ChestSwap.settled): with jitter the
		// client can show a moment that's already past, and swapping on that puts the client and Grim out of step.
		boolean settled = !chestMode || ChestSwap.settled();
		if (cleared && !ground && airTicks >= 2 && settled) {
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
		} else if (!gliding && !ground && !cleared) {
			// Take off the same way, after the first jump or off a ledge (the elytra on first, if it's off).
			if (chestMode) ChestSwap.restoreElytra();
			startGliding();
		} else {
			cutLongGlide(gliding, cleared);
		}
	}

	/**
	 * Counts the server's glide since it started (see {@link #longGlide}) and, with No Durability, ends it with the
	 * chestplate once it runs long: only once the server has answered the last swap, and with the ground close below.
	 */
	private void cutLongGlide(boolean gliding, boolean cleared) {
		var p = mc.player;
		longGlide = cleared || !gliding || !ChestSwap.elytraWorn() ? 0 : longGlide + 1;
		if (!chestMode || longGlide < LONG_GLIDE || !ChestSwap.settled()) return;
		if (mc.level.noBlockCollision(p, p.getBoundingBox().expandTowards(0, -CUT_HEIGHT, 0))) return;
		ChestSwap.swap();
		longGlide = 0;
	}

	/**
	 * Something's in the way (or the lane ahead hasn't loaded): stop the bounce first, then deal with it once you're
	 * standing.
	 */
	private void handleObstacle(Obstruction ahead) {
		if (state == State.BOUNCING || state == State.BRAKING) {
			if (!brake()) {
				state = State.BRAKING;
				return;
			}
		}
		Obstacles how = obstacles.get();
		boolean walkRound = how != Obstacles.STOP && Baritone.isAvailable();
		switch (ahead.hazard()) {
			case UNLOADED -> {
				// Not an obstacle: wait where you are until it loads, then bounce on.
				stand();
				state = State.WAITING;
			}
			case BLOCKS -> {
				if (how == Obstacles.BARITONE && walkRound) startPathing(ahead.distance());
				else if (how == Obstacles.STOP) blocked("Lane blocked");
				else {
					// Unbreakable blocks (bedrock, barriers) can only be stopped at or walked round.
					BlockPos breakable = null;
					for (BlockPos pos : ahead.blocks()) {
						if (mc.level.getBlockState(pos).getDestroySpeed(mc.level, pos) >= 0) {
							breakable = pos;
							break;
						}
					}
					if (breakable != null) mineOrApproach(breakable);
					else if (walkRound) startPathing(ahead.distance());
					else blocked("Lane blocked by something unbreakable");
				}
			}
			case CEILING -> walkUnder();
			case HOLE -> {
				if (walkRound) startPathing(ahead.distance());
				else blocked("Hole in the lane");
			}
			case STUCK -> {
				forcedBlock = false;
				if (walkRound) startPathing(1);
				else giveUp("Stuck (or set back over and over) and no way round without Baritone");
			}
		}
	}

	/**
	 * Stops bouncing the way a player would, so nothing about it looks odd to the server: no more jumps and the glide let
	 * go, diving (along the lane) to land soon, then sliding to a halt. True once you're standing still.
	 */
	private boolean brake() {
		holdGlide = false;
		letGo();
		putElytraBack();
		var p = mc.player;
		// Still flying while the elytra's open, the tick you land too: along the lane, not where the camera looks.
		if (!p.onGround() || elytraOpen()) {
			spoof(lane, 90);
			return false;
		}
		spoofing = false;
		Vec3 v = p.getDeltaMovement();
		return v.x * v.x + v.z * v.z < 0.03 * 0.03;
	}

	/** Standing in front of an obstacle: no flight, nothing pressed. */
	private void stand() {
		spoofing = holdGlide = false;
		letGo();
		putElytraBack();
	}

	private void blocked(String why) {
		stand();
		state = State.BLOCKED;
		if (!warned) warn(why + ", waiting until it's clear");
		warned = true;
	}

	private void giveUp(String why) {
		warn(why + ": turning off");
		disable();
	}

	/**
	 * Under a ceiling too low to hop (a two-high tunnel, a roof over the lane), or on the way to one: walks along the
	 * lane, sprinting, without mining anything, until there's room overhead to bounce on from.
	 */
	private void walkUnder() {
		if (state == State.MINING) stopMining();
		state = State.UNDER;
		spoof(steeredYaw(), 0);
		wantForward = true;
		trackStuck();
	}

	/** Mines the block once it's in reach, walking along the lane up to it first. */
	private void mineOrApproach(BlockPos pos) {
		if (mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) <= MINE_REACH) {
			stand();
			if (!pos.equals(mining)) stopMining();
			// Asked every tick (asking again for the same block is free), so a refused or dropped break is tried again.
			mine(pos);
			state = State.MINING;
			progressCount = 0;
			return;
		}
		if (state == State.MINING) stopMining();
		state = State.WALKING;
		// Walk the lane's way whatever the camera does (the spoofed yaw moves you), back towards its line.
		spoof(steeredYaw(), 0);
		wantForward = true;
		trackStuck();
	}

	/**
	 * Counts the server's setbacks: a few in a short while (something the bounce keeps running into that isn't a
	 * block, or a check it keeps failing) count as the lane being blocked.
	 */
	private void countSetback() {
		long now = mc.level.getGameTime();
		setbacks.addLast(now);
		while (!setbacks.isEmpty() && now - setbacks.peekFirst() > SETBACK_WINDOW) setbacks.removeFirst();
		if (setbacks.size() >= SETBACK_LIMIT && state == State.BOUNCING) {
			setbacks.clear();
			forcedBlock = true;
		}
	}

	/** Whether you've been trying to move along the lane without getting anywhere; if so the lane counts as blocked. */
	private boolean trackStuck() {
		double along = along(mc.player.position());
		int slot = progressCount % STUCK_TICKS;
		boolean stuck = progressCount >= STUCK_TICKS && along - progress[slot] < STUCK_PROGRESS;
		progress[slot] = along;
		progressCount++;
		if (!stuck) return false;
		progressCount = 0;
		forcedBlock = true;
		return true;
	}

	private void stopBouncing() {
		spoofing = false;
		holdGlide = false;
		letGo();
		putElytraBack();
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

	/**
	 * Walks to the first spot on the lane's line, past {@code distance} ahead, with a floor and room to bounce from,
	 * then bounces on from there.
	 */
	private void startPathing(double distance) {
		stand();
		Vec3 target = clearSpotPast(along(mc.player.position()) + distance);
		if (target == null || !Baritone.pathTo(Mth.floor(target.x), Mth.floor(target.y + 0.1), Mth.floor(target.z))) {
			giveUp("No way past the obstacle");
			return;
		}
		if (pathTarget == null) info("Lane blocked, walking past it with Baritone");
		pathTarget = target;
		state = State.PATHING;
		pathWait = 0;
	}

	/** Waits for Baritone to get you back onto the lane past the obstacle, then bounces on. */
	private void tickPathing() {
		spoofing = false;
		holdGlide = false;
		letGo();
		Vec3 pos = mc.player.position();
		if (mc.player.onGround() && along(pos) >= along(pathTarget) - 1 && Math.abs(offLine(pos)) <= 1) {
			Baritone.stop();
			pathTarget = null;
			pathTries = 0;
			state = State.BOUNCING;
			progressCount = 0;
			return;
		}
		if (Baritone.isPathing()) {
			pathWait = 0;
			return;
		}
		// Give the pathfinder a moment to start before deciding it's stuck, then try somewhere further on, twice.
		if (++pathWait < 40) return;
		if (++pathTries > 2) {
			giveUp("Baritone couldn't get past the obstacle");
			return;
		}
		startPathing(along(pathTarget) - along(pos) + 4);
	}

	/**
	 * The first spot from {@code from} along the line (and the one after it) you could stand and bounce from: with room
	 * overhead to hop if there's one within reach, otherwise anywhere you can stand (it walks on from there under the
	 * ceiling).
	 */
	private Vec3 clearSpotPast(double from) {
		for (double height : new double[]{HOP_CLEARANCE, STAND_HEIGHT}) {
			for (double t = Math.ceil(from); t < from + PASS_SEARCH; t++) {
				if (standable(pointOnLine(t), height) && standable(pointOnLine(t + 1), height)) return pointOnLine(t);
			}
		}
		return null;
	}

	private boolean standable(Vec3 feet, double height) {
		AABB body = new AABB(feet.x - 0.3, feet.y + FLOOR_CLEARANCE, feet.z - 0.3, feet.x + 0.3, feet.y + height, feet.z + 0.3);
		if (!loaded(feet) || blocksIn(body, null)) return false;
		BlockPos floor = BlockPos.containing(feet.x, feet.y - 0.5, feet.z);
		return !mc.level.getBlockState(floor).getCollisionShape(mc.level, floor).isEmpty();
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

	/**
	 * Fixes the lane: the nearest highway direction to where you face, through the middle of the block you're on (on a
	 * highway, diagonal or not, that's a line through the middle of its blocks).
	 */
	private void fixLine() {
		var p = mc.player;
		lane = snap(p.getYRot());
		lineOrigin = new Vec3(p.getBlockX() + 0.5, p.getY(), p.getBlockZ() + 0.5);
	}

	/** How far {@code pos} is along the lane from where it was fixed. */
	private double along(Vec3 pos) {
		Vec3 dir = laneDir();
		return (pos.x - lineOrigin.x) * dir.x + (pos.z - lineOrigin.z) * dir.z;
	}

	/** How far {@code pos} is to the side of the lane's line (positive the way yaw turns). */
	private double offLine(Vec3 pos) {
		Vec3 dir = laneDir();
		return (pos.x - lineOrigin.x) * -dir.z + (pos.z - lineOrigin.z) * dir.x;
	}

	/** The point {@code t} blocks along the line, at the floor's height. */
	private Vec3 pointOnLine(double t) {
		Vec3 dir = laneDir();
		return new Vec3(lineOrigin.x + dir.x * t, floorY(), lineOrigin.z + dir.z * t);
	}

	/** The height you bounce off: where you last landed. */
	private double floorY() {
		return Double.isNaN(groundY) ? lineOrigin.y : groundY;
	}

	/**
	 * The flight yaw: the lane's, turned a little back towards its line when you've drifted to the side, by as much as
	 * brings you back without overshooting (the sideways speed you already have counts against it).
	 */
	private float steeredYaw() {
		Vec3 dir = laneDir(), v = mc.player.getDeltaMovement();
		double off = offLine(mc.player.position());
		double sideways = v.x * -dir.z + v.z * dir.x, forwards = Math.max(0.5, v.x * dir.x + v.z * dir.z);
		double want = Mth.clamp(-off * STEER_GAIN, -STEER_MAX_SPEED, STEER_MAX_SPEED);
		double aim = want + (want - sideways) * STEER_DAMPING;
		return lane + Mth.clamp((float) Math.toDegrees(Math.atan2(aim, forwards)), -STEER_MAX_TURN, STEER_MAX_TURN);
	}

	/** How far ahead to watch: enough to stop from this speed. */
	private double lookAhead() {
		Vec3 v = mc.player.getDeltaMovement();
		return LOOK_AHEAD + Math.sqrt(v.x * v.x + v.z * v.z) * LOOK_AHEAD_TICKS;
	}

	/** The nearest of the eight highway directions. */
	private static float snap(float yaw) {
		return Mth.wrapDegrees(45f * Math.round(yaw / 45f));
	}

	// ---- mining ---------------------------------------------------------------------------------------------------

	private void mine(BlockPos pos) {
		BlockState block = mc.level.getBlockState(pos);
		boolean again = pos.equals(mining);
		portalWait = again ? portalWait + 1 : 0;
		if (isPortal(block)) {
			// Once, and again every half second while it's still there.
			if (again && portalWait % 10 != 0) return;
			// Portals break instantly server-side but not client-side: send the dig directly.
			Direction face = Direction.getApproximateNearest(-laneDir().x, 0, -laneDir().z);
			Packets.sendSequenced(seq -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, face, seq));
			Packets.sendSequenced(seq -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, face, seq));
			mc.player.swing(InteractionHand.MAIN_HAND);
		} else {
			// The breaking service picks the tool, faces the block and swings, with Grim's timing.
			Myriad.breaking().breakBlock(this, pos, Rotations.PRIORITY_HIGH, Breaking.Options.PACKET.withRotate(true));
		}
		mining = pos.immutable();
	}

	private void stopMining() {
		mining = null;
		Myriad.breaking().cancel(this);
	}

	/**
	 * The first thing in the lane within {@code range}: blocks the player's hitbox would run into sliding along it (a
	 * little narrower than the hitbox, so brushing a wall doesn't count; from just above the floor to a standing
	 * player's height), a hole at least two deep (one you'd land in and not hop out of), or chunks that haven't loaded.
	 * A ceiling that only a hop would reach is walked under rather than mined; it's what's ahead unless one of the
	 * others comes just after it. Null if it's clear.
	 */
	private Obstruction scanAhead(double range) {
		Vec3 dir = laneDir(), step = dir.scale(SWEEP_STEP);
		AABB box = mc.player.getBoundingBox();
		double floor = floorY();
		AABB body = new AABB(box.minX + 0.2, floor + FLOOR_CLEARANCE, box.minZ + 0.2, box.maxX - 0.2, floor + STAND_HEIGHT, box.maxZ - 0.2);
		AABB headroom = new AABB(body.minX, floor + STAND_HEIGHT, body.minZ, body.maxX, floor + HOP_CLEARANCE, body.maxZ);
		AABB feet = new AABB(box.minX, floor - 2, box.minZ, box.maxX, floor - 0.5, box.maxZ);
		List<BlockPos> hits = new ArrayList<>();
		Obstruction ceiling = null;
		for (double d = 0; d <= range; d += SWEEP_STEP) {
			if (ceiling != null && d > ceiling.distance() + UNDER_LOOK_AHEAD) return ceiling;
			Vec3 at = dir.scale(d);
			if (!loaded(mc.player.position().add(at))) return new Obstruction(Hazard.UNLOADED, d, List.of());
			if (blocksIn(body.move(at).expandTowards(step), hits)) {
				Vec3 eye = mc.player.getEyePosition();
				hits.sort(Comparator.comparingDouble(pos -> pos.distToCenterSqr(eye)));
				return new Obstruction(Hazard.BLOCKS, d, hits);
			}
			if (ceiling == null && blocksIn(headroom.move(at).expandTowards(step), null)) ceiling = new Obstruction(Hazard.CEILING, d, List.of());
			// The floor: nothing to stand on for two blocks down under the whole hitbox. Every quarter block, so even a
			// hole just wider than you (one block) is found wherever you are.
			if (d >= 1 && (!blocksIn(feet.move(at), null) || !blocksIn(feet.move(at.add(step.scale(0.5))), null))) {
				return new Obstruction(Hazard.HOLE, d, List.of());
			}
		}
		return ceiling;
	}

	/** Whether anything in {@code box} is in the way (see {@link #obstructs}); each such block is added to {@code hits}. */
	private boolean blocksIn(AABB box, List<BlockPos> hits) {
		boolean any = false;
		for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
			if (!obstructs(pos, box)) continue;
			if (hits == null) return true;
			hits.add(pos.immutable());
			any = true;
		}
		return any;
	}

	private boolean loaded(Vec3 pos) {
		return mc.level.hasChunk(SectionPos.blockToSectionCoord(pos.x), SectionPos.blockToSectionCoord(pos.z));
	}

	/**
	 * Whether the block at {@code pos} is something to clear: a portal, a cobweb (no collision, but it stops you dead),
	 * or a collision shape that meets {@code box}.
	 */
	private boolean obstructs(BlockPos pos, AABB box) {
		BlockState state = mc.level.getBlockState(pos);
		if (isPortal(state) || state.is(Blocks.COBWEB)) return true;
		if (state.isAir() || !state.getFluidState().isEmpty()) return false;
		return state.getCollisionShape(mc.level, pos).toAabbs().stream().anyMatch(part -> part.move(pos).intersects(box));
	}

	private static boolean isPortal(BlockState state) {
		return state.is(Blocks.NETHER_PORTAL) || state.is(Blocks.END_PORTAL) || state.is(Blocks.END_GATEWAY);
	}
}
