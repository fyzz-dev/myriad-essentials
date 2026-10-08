package dev.myriad.essentials.modules.movement;

import dev.myriad.api.Myriad;
import dev.myriad.api.event.Priority;
import dev.myriad.api.event.Subscribe;
import dev.myriad.api.event.Subscription;
import dev.myriad.api.event.events.InputEvent;
import dev.myriad.api.event.events.InteractEvent;
import dev.myriad.api.event.events.PacketEvent;
import dev.myriad.api.event.events.TickEvent;
import dev.myriad.api.module.Categories;
import dev.myriad.api.module.Module;
import dev.myriad.api.module.Modules;
import dev.myriad.api.setting.BoolSetting;
import dev.myriad.api.util.MathUtil;
import dev.myriad.essentials.mixin.FireworkRocketEntityAccessor;
import dev.myriad.essentials.util.ChestSwap;
import dev.myriad.essentials.util.GlideHold;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;


/**
 * Tweaks to elytra flight that work with Grim (2b2t).
 * <p>
 * <b>Rocket Boost.</b> Grim doesn't simulate a rocket's push. While one is attached to you it takes the plain glide it
 * predicts from last tick's movement and widens it, on each axis, by up to 1.7 times your current look plus the one
 * before it (with a little slack for skipped ticks), capped at 1.7 blocks a tick; anything inside passes. Vanilla's push
 * only ever approaches 1.7 blocks a tick along your look, and takes a while to get there. With Rocket Boost, every
 * tick a rocket is attached you move as far along your look as that window allows (a little inside it): full speed at
 * once, and since the cap is per axis, faster when you fly diagonally. Looking level holds your height. Every
 * movement packet carries your rotation meanwhile, so the look before is exactly the one sent last tick. Applied by
 * this addon's LivingEntityMixin.
 * <p>
 * <b>No Durability.</b> The server wears an elytra by one for every 20 ticks of gliding without a break. With a
 * chestplate in your off hand or hotbar, the elytra is swapped for it (using it, as a right-click equips armour)
 * while you glide, so the server stops the glide on its next tick; the client keeps gliding through that (see
 * {@link GlideHold}), and Grim doesn't notice. Every 8 ticks the elytra goes back on just long enough to start the
 * glide again (jump pressed, as vanilla starts one), and comes off again. The server never glides for more than a
 * tick in one go, so it never wears the elytra. Rockets you use meanwhile go out in those moments, when the server
 * takes them.
 * <p>
 * Meanwhile the server takes you as falling, and counts every block you descend as fall distance; only rising again
 * clears it (or gliding long enough at a gentle slope once the elytra's back on). Landing, or hitting something,
 * with that count would cost all of it. So swaps only run with room around you: below, and along the way you're flying
 * for as long as the server takes to hear from you, or, once you've come down more than a safe fall since you last
 * rose, for as long as it then takes to glide that count away too. The elytra is back on and the server gliding before
 * you could land or hit anything. The server's equip sound for each swap is muted. Eating (or anything you hold to use) pauses the swapping: Grim stops what
 * you're using at every swap, so the elytra goes back on at the next start, and swapping picks up again once you're
 * done.
 */
public class ElytraTweaks extends Module {
	private final BoolSetting rocketBoost = sgGeneral.bool("Rocket Boost")
		.description("While a rocket is attached, fly as fast along your look as Grim allows: full speed at once, and faster on diagonals.")
		.defaultValue(true).build();
	private final BoolSetting noDurability = sgGeneral.bool("No Durability")
		.description("Keep the elytra from wearing out while you glide, by swapping it with a chestplate held in your off hand or hotbar.")
		.defaultValue(true).build();

	// ---- Rocket Boost -----------------------------------------------------------------------------------------------

	/** Grim's allowance for a rocket's push on each axis, blocks a tick (scale and cap alike), less a margin. */
	private static final double ROCKET_PUSH = 1.68;
	/** Slack Grim gives each aim's axes for a skipped tick. */
	private static final double LOOK_SLACK = 0.05;
	/** Ticks of vanilla flight after a setback while Grim catches up. */
	private static final int SETTLE_TICKS = 5;
	/** A target speed beyond any rocket's, so the clamp alone decides how fast. */
	private static final double REACH = 10;

	/** The player's position a tick ago while gliding: the movement Grim's prediction starts from. */
	private Vec3 prevTickPos;
	private volatile boolean setBack;
	private int settleTicks;

	// ---- No Durability ----------------------------------------------------------------------------------------------

	/** Ticks between glide restarts: well under vanilla's 80-tick floating kick and the 20 ticks that wear the elytra. */
	private static final int INTERVAL = 8;
	/** Give up if the server hasn't stopped the glide this long after a swap (it refused it). */
	private static final int CLEAR_TIMEOUT = 40;
	/** Ticks of gliding with room below before the first swap. */
	private static final int ARM_TICKS = 10;
	/** Coming down more than this since you last rose, the server's count of your fall would hurt on landing. */
	private static final double SAFE_DESCENT = 3;
	/**
	 * Ticks of gliding the server needs, elytra back on, before it lets go of that count: it only does while it sees you
	 * descend gently, and its idea of your speed (a free fall while it wasn't gliding you) takes a while to settle.
	 */
	private static final int FORGET_TICKS = 30;

	private boolean swapping, startNow, rocketWanted, firing, warned;
	/**
	 * Stopping in mid-air: the elytra is back on, and the glide starts again once the server's stop for the last swap
	 * is in (held meanwhile, so you keep gliding) and not straight after the last start; {@link #finishWait} counts the
	 * ticks spent waiting for it.
	 */
	private boolean finishing;
	private int finishWait;
	/** How far you've come down, as the server counts it, since you last rose; and ticks of gliding since swapping. */
	private double descent;
	private int glidingTicks;
	/** Something to eat or hold to use was used meanwhile: swapping stops at the next start, so it can go then. */
	private boolean stopForUse;
	private InteractionHand rocketHand = InteractionHand.MAIN_HAND;
	/** The hotbar slot a waiting rocket was used from (it may have been a silent swap, as Middle Click's are). */
	private int rocketSlot = -1;
	private int armTicks, sinceStart;

	public ElytraTweaks() {
		super(Categories.MOVEMENT, "Elytra Tweaks", "Faster rocket boosts and an elytra that doesn't wear out, Grim-safe.");
	}

	@Override
	protected void onEnable() {
		prevTickPos = null;
		swapping = startNow = rocketWanted = stopForUse = finishing = false;
		descent = 0;
		armTicks = 0;
	}

	@Override
	protected void onDisable() {
		if (inGame()) {
			// No next tick to wait for: start the glide again now rather than drop out of it in mid-air.
			finish(true);
			// Turning off mid-swap can start the glide again, and this module no longer hears the input event that presses
			// jump with it (Grim's ElytraB): press it from a listener of its own, for that one tick.
			if (startNow) pressJumpOnce();
		}
		swapping = startNow = false;
		GlideHold.disarm(this);
	}

	private static void pressJumpOnce() {
		Subscription[] once = new Subscription[1];
		once[0] = Myriad.events().listen(InputEvent.class, Priority.LOWEST, e -> {
			e.jump = true;
			once[0].unsubscribe();
		});
	}

	/** Whether the elytra is being swapped (Auto Armor leaves the chest slot alone meanwhile). */
	public static boolean holdsChest() {
		ElytraTweaks m = Modules.active(ElytraTweaks.class);
		return m != null && m.swapping || ElytraFly.swapsChest();
	}

	/** Whether No Durability is on, for Elytra Fly's bounce to swap with a chestplate as well. */
	public static boolean noDurability() {
		ElytraTweaks m = Modules.active(ElytraTweaks.class);
		return m != null && m.noDurability.get();
	}

	/** Before any of this tick's actions: a restart sends held ping answers, which must come first (Grim's Post). */
	@Subscribe(priority = Priority.BEFORE_ACTIONS)
	private void onTickStart(TickEvent.Pre e) {
		startNow = false;
		if (!inGame()) {
			swapping = false;
			return;
		}
		LocalPlayer p = mc.player;
		if (settleTicks > 0) settleTicks--;
		// Rocket Boost: every movement packet carries a rotation while a rocket pushes, so Grim's look before is known.
		if (rocketBoost.get() && p.isFallFlying() && rocketAttached(p)) Myriad.rotations().sendRotationThisTick();
		catchGlide(p);
		tickNoDurability();
	}

	// ---- No Durability ----------------------------------------------------------------------------------------------

	private void tickNoDurability() {
		LocalPlayer p = mc.player;
		trackDescent(p);
		if (!noDurability.get()) {
			finish();
			return;
		}
		if (!swapping) {
			// Elytra Fly's bounce does its own swapping.
			// Not while you eat (or use anything held): a swap would stop it (see onUse).
			boolean ready = p.isFallFlying() && ChestSwap.elytraWorn() && !p.onGround() && !p.isInWater() && !p.isPassenger() && !p.isUsingItem()
				&& !ElytraFly.holdsGlide() && room(true);
			armTicks = ready ? armTicks + 1 : 0;
			if (armTicks < ARM_TICKS) return;
			if (ChestSwap.pair() == null) {
				if (!ChestSwap.fetchChestplate()) warnOnce("No Durability needs a chestplate in your inventory.");
				return;
			}
			if (!ChestSwap.ready()) {
				warnOnce("No Durability can't swap armour with Curse of Binding.");
				return;
			}
			if (!GlideHold.arm(this)) return;
			ChestSwap.swap();
			swapping = true;
			sinceStart = 0;
			return;
		}
		sinceStart++;
		if (finishing) {
			finish();
			return;
		}
		// Landing, water, or the ground (below or ahead) close enough that the server could see you land without the
		// elytra.
		if (!p.isFallFlying() || p.onGround() || p.isInWater() || !room(false)) {
			finish();
			return;
		}
		boolean cleared = GlideHold.cleared(this);
		boolean due = sinceStart >= INTERVAL || rocketWanted || stopForUse || GlideHold.exposed(this);
		// Not two starts in a row (Grim's ElytraC).
		if (cleared && due && sinceStart >= 2) {
			// Something to eat is waiting: this start puts the elytra back for good, and the swapping stops.
			if (stopForUse) finish();
			else restart(true);
		} else if (!cleared && sinceStart > INTERVAL + CLEAR_TIMEOUT) {
			finish();
		}
	}

	/**
	 * Starts the glide again: the held ping answers go first (Grim sees the stop), the elytra goes on, the start is
	 * sent with jump pressed this tick and released the tick before (as vanilla starts one; Grim's ElytraB), a waiting
	 * rocket is used while the server takes it, and with {@code swapBack} the chestplate goes back on a couple of ticks
	 * later.
	 */
	private void restart(boolean swapBack) {
		GlideHold.release(this);
		if (!ChestSwap.startGlide()) {
			finish();
			return;
		}
		if (rocketWanted) fireRocket();
		startNow = true;
		sinceStart = 0;
		if (swapBack) ChestSwap.swap();
	}

	private void finish() {
		finish(false);
	}

	/**
	 * Stops swapping, with the elytra back on and gliding again if the server stopped the glide. Never by dropping out of
	 * the glide in mid-air, which would land you with every block fallen since the server last glided: in the air it
	 * waits (unless {@code now}) for the server's stop for the last swap, which may still be on its way, and starts the
	 * glide again once it's in; it lets go without a start if none comes within a round trip (the elytra was back on in
	 * time, so the server never stopped).
	 */
	private void finish(boolean now) {
		if (!swapping) return;
		LocalPlayer p = mc.player;
		boolean inAir = p.isFallFlying() && !p.onGround() && !p.isInWater();
		boolean cleared = GlideHold.cleared(this);
		if (inAir && !now && (!cleared || sinceStart < 2)) {
			if (!finishing) {
				finishing = true;
				finishWait = 0;
				ChestSwap.restoreElytra();
			}
			if (cleared || ++finishWait <= roundTripTicks() + 4) return;
		}
		swapping = finishing = false;
		armTicks = 0;
		if (inAir && cleared) {
			restart(false);
		} else {
			GlideHold.release(this);
			ChestSwap.restoreElytra();
			// The server had stopped the glide: so does the client, where you are.
			if (cleared && p.isFallFlying()) p.stopFallFlying();
		}
		GlideHold.disarm(this);
		rocketWanted = stopForUse = false;
	}

	/** Ticks for a packet to reach the server and its answer to come back, with a little slack. */
	private static int roundTripTicks() {
		return Mth.clamp(Myriad.server().ping() / 50 + 2, 2, 12);
	}

	/** While swapping: jump only as the press that starts the glide again (released the tick before). Same for a catch. */
	@Subscribe(priority = Priority.LOWEST)
	private void onInput(InputEvent e) {
		if (catching > 0) e.jump = catchNow;
		else if (swapping || startNow) e.jump = startNow;
	}

	// ---- catching a dropped glide -----------------------------------------------------------------------------------

	/** How long to keep trying to open the elytra again after the glide drops in mid-air. */
	private static final int CATCH_TICKS = 20;

	private boolean wasGliding, catchNow;
	/** Ticks left to catch a dropped glide (0: nothing to catch). */
	private int catching;

	/**
	 * A safety net: the glide stopping in mid-air with nothing a player would stop it for (no ground, water, ladder or
	 * vehicle) means the server's stop got through to the client (a setback, a lost hold, chunks that came in late), and
	 * a fall from there could cost every block the server counted while it wasn't gliding you. So the elytra is opened
	 * again at once, as a player would: on if a chestplate is worn, then jump released for a tick and pressed.
	 */
	private void catchGlide(LocalPlayer p) {
		boolean gliding = p.isFallFlying();
		boolean free = !p.onGround() && !p.isInWater() && !p.isPassenger() && !p.onClimbable() && !p.getAbilities().flying;
		if (wasGliding && !gliding && free && !ElytraFly.bouncing()) {
			catching = CATCH_TICKS;
			info("Glide dropped in mid-air: opening the elytra again");
		}
		wasGliding = gliding;
		catchNow = false;
		if (catching == 0) return;
		if (gliding || !free) {
			catching = 0;
			return;
		}
		catching--;
		if (!ChestSwap.elytraWorn()) {
			ChestSwap.restoreElytra();
			return;
		}
		// Released last tick (the press opens it, as vanilla does), pressed this one.
		catchNow = !lastCatchPress;
		lastCatchPress = catchNow;
	}

	private boolean lastCatchPress;

	/**
	 * A rocket used meanwhile waits for the next start: the server only attaches rockets while it sees you gliding.
	 * Something you hold to use (food, a potion, a bow) waits for the swapping to stop: Grim stops whatever you're using
	 * each time you use another item (each swap) or change slot, so you'd never finish eating. It's used once the elytra
	 * is back on for good, at the next start (Auto Eat tries again, and holding right click uses it again).
	 */
	@Subscribe
	private void onUse(InteractEvent.Item e) {
		if (!swapping || firing) return;
		ItemStack stack = mc.player.getItemInHand(e.hand());
		if (stack.is(Items.FIREWORK_ROCKET)) {
			e.cancel();
			rocketWanted = true;
			rocketHand = e.hand();
			rocketSlot = e.hand() == InteractionHand.MAIN_HAND ? Myriad.inventory().serverSlot() : -1;
		} else if (stack.getUseDuration(mc.player) > 0) {
			e.cancel();
			stopForUse = true;
		}
	}

	/** Uses the waiting rocket, from the hand or hotbar slot it was used from (or any rockets in the hotbar). */
	private void fireRocket() {
		rocketWanted = false;
		var inv = mc.player.getInventory();
		int slot = rocketSlot;
		if (rocketHand == InteractionHand.MAIN_HAND && (slot < 0 || !inv.getItem(slot).is(Items.FIREWORK_ROCKET))) slot = Myriad.inventory().findInHotbar(s -> s.is(Items.FIREWORK_ROCKET));
		boolean offHand = rocketHand == InteractionHand.OFF_HAND && mc.player.getOffhandItem().is(Items.FIREWORK_ROCKET);
		if (!offHand && slot < 0) return;
		firing = true;
		try {
			if (offHand) mc.gameMode.useItem(mc.player, InteractionHand.OFF_HAND);
			else Myriad.inventory().silentSwap(slot, () -> mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND));
		} finally {
			firing = false;
		}
	}

	private void warnOnce(String message) {
		if (!warned) warn(message);
		warned = true;
	}

	/**
	 * Keeps {@link #descent} as the server counts it: every block down adds, rising clears it, as does landing, and so
	 * does gliding (not swapping) long enough at a gentle slope.
	 */
	private void trackDescent(LocalPlayer p) {
		double dy = p.getY() - p.yo;
		if (dy > 0 || p.onGround() || p.isInWater()) descent = 0;
		else descent -= dy;
		glidingTicks = swapping ? 0 : glidingTicks + 1;
		if (!swapping && glidingTicks >= FORGET_TICKS && p.getDeltaMovement().y > -0.5) descent = 0;
	}

	/**
	 * Whether there's room enough that the server won't see you land, or hit something, while it isn't gliding you:
	 * a few blocks below plus how far you fall (or rise) in a round trip, and nothing along the way you're flying for
	 * that long (rising ground, a wall, a tree). Swapping stops when there isn't, in time for the elytra to be back on
	 * and the server gliding first.
	 */
	private boolean room(boolean arming) {
		LocalPlayer p = mc.player;
		Vec3 v = p.getDeltaMovement();
		int roundTrip = roundTripTicks();
		// Once the server counts a fall that would hurt, it needs time to forget it before you touch anything: for the
		// last swap's stop to come in, the glide to start again, and the server to glide a while.
		double ticks = roundTrip + 4 + (arming ? 2 : 0) + (descent > SAFE_DESCENT ? roundTrip + FORGET_TICKS : 0);
		double need = 6 + (v.y < 0 ? -v.y * ticks : v.y * (roundTrip / 2.0 + 2)) + (arming ? 2 : 0);
		AABB box = p.getBoundingBox();
		// Chunks you'd reach that haven't come in: the client stops you there until they do, which shouldn't happen
		// while the server isn't gliding you.
		for (double t = 0; t <= ticks; t += 4) {
			if (!mc.level.hasChunk(Mth.floor(p.getX() + v.x * t) >> 4, Mth.floor(p.getZ() + v.z * t) >> 4)) return false;
		}
		if (!mc.level.noBlockCollision(p, box.expandTowards(0, -need, 0))) return false;
		// Along the way: the whole hitbox swept to where you'll be, and a couple of blocks under that path.
		return mc.level.noBlockCollision(p, box.expandTowards(v.x * ticks, Math.min(0, v.y * ticks) - 2, v.z * ticks));
	}

	// ---- Rocket Boost -----------------------------------------------------------------------------------------------

	@Subscribe(packets = ClientboundPlayerPositionPacket.class)
	private void onReceive(PacketEvent.Receive e) {
		setBack = true;
	}

	/**
	 * The movement the local player's glide should make this tick, given vanilla's: unchanged unless Rocket Boost is on,
	 * you're gliding with a rocket attached, and no module is turning you server-side (the window is built from the
	 * rotation the server gets, so it has to be yours).
	 */
	public static Vec3 glideMovement(Vec3 vanilla) {
		ElytraTweaks m = Modules.active(ElytraTweaks.class);
		if (m == null || !m.rocketBoost.get()) return vanilla;
		LocalPlayer p = Minecraft.getInstance().player;
		if (p == null || !p.isFallFlying()) {
			m.prevTickPos = null;
			return vanilla;
		}
		Vec3 here = p.position(), there = m.prevTickPos;
		m.prevTickPos = here;
		if (m.setBack) {
			m.setBack = false;
			m.settleTicks = SETTLE_TICKS;
		}
		if (there == null || m.settleTicks > 0 || p.hurtTime > 0 || Myriad.rotations().isRotating() || !rocketAttached(p)) return vanilla;
		// Grim works from the movement it saw last tick, not the client's velocity.
		Vec3 carried = here.subtract(there);
		if (carried.lengthSqr() > 40 * 40) return vanilla;
		Vec3 aim = MathUtil.direction(p.getYRot(), p.getXRot());
		Vec3 prevAim = MathUtil.direction(Myriad.rotations().serverYaw(), Myriad.rotations().serverPitch());
		Vec3 coast = vanillaGlide(p, carried, aim, p.getXRot());
		// Reach for far past any speed along the aim, then pull back into what Grim allows on each axis.
		Vec3 far = aim.scale(REACH);
		Vec3 pushed = new Vec3(
			Math.clamp(far.x, allowed(aim.x, prevAim.x, carried.x, coast.x, false), allowed(aim.x, prevAim.x, carried.x, coast.x, true)),
			Math.clamp(far.y, allowed(aim.y, prevAim.y, carried.y, coast.y, false), allowed(aim.y, prevAim.y, carried.y, coast.y, true)),
			Math.clamp(far.z, allowed(aim.z, prevAim.z, carried.z, coast.z, false), allowed(aim.z, prevAim.z, carried.z, coast.z, true)));
		// Vanilla's push is always allowed too; only take over where this goes further along the aim.
		return pushed.dot(aim) > vanilla.dot(aim) ? pushed : vanilla;
	}

	/**
	 * One edge of what Grim accepts on one axis: the rocket-free glide ({@code coast}), stretched by however far the
	 * rocket's reach (from this tick's and last tick's aim, each given a little slack) goes past last tick's movement.
	 */
	private static double allowed(double aim, double prevAim, double carried, double coast, boolean upper) {
		double reach = upper ? Math.max(LOOK_SLACK, aim) + Math.max(LOOK_SLACK, prevAim) : Math.min(-LOOK_SLACK, aim) + Math.min(-LOOK_SLACK, prevAim);
		double rocket = Math.clamp(reach * ROCKET_PUSH, -ROCKET_PUSH, ROCKET_PUSH);
		double stretch = rocket - carried;
		return coast + (upper ? Math.max(0, stretch) : Math.min(0, stretch));
	}

	/** Vanilla's elytra step (Grim predicts the same) from {@code motion} with {@code aim}, before any rocket, drag included. */
	private static Vec3 vanillaGlide(LocalPlayer p, Vec3 motion, Vec3 aim, float pitchDegrees) {
		double g = p.getAttributeValue(Attributes.GRAVITY);
		if (motion.y <= 0 && p.hasEffect(MobEffects.SLOW_FALLING)) g = Math.min(g, 0.01);
		float rad = pitchDegrees * Mth.DEG_TO_RAD;
		double aimFlat = aim.horizontalDistance();
		double flatSpeed = motion.horizontalDistance();
		double cos = Math.cos(rad);
		double liftFactor = cos * cos * Math.min(1, aim.length() / 0.4);
		Vec3 next = motion.add(0, g * (liftFactor * 0.75 - 1), 0);
		if (aimFlat > 0) {
			double dirX = aim.x / aimFlat, dirZ = aim.z / aimFlat;
			if (next.y < 0) {
				// Falling converts into forward speed.
				double k = -0.1 * next.y * liftFactor;
				next = next.add(dirX * k, k, dirZ * k);
			}
			if (rad < 0) {
				// Pulling up trades forward speed for height.
				double k = -0.04 * flatSpeed * Mth.sin(rad);
				next = next.add(-dirX * k, 3.2 * k, -dirZ * k);
			}
			next = next.add((dirX * flatSpeed - next.x) * 0.1, 0, (dirZ * flatSpeed - next.z) * 0.1);
		}
		return next.multiply(0.99f, 0.98f, 0.99f);
	}

	/** Whether a rocket is boosting {@code p}, as the server told us (the same moment Grim learns it). */
	private static boolean rocketAttached(LocalPlayer p) {
		for (FireworkRocketEntity rocket : p.level().getEntitiesOfClass(FireworkRocketEntity.class, p.getBoundingBox().inflate(4))) {
			if (rocket.isRemoved()) continue;
			var target = rocket.getEntityData().get(FireworkRocketEntityAccessor.essentials$attachedTarget());
			if (target.isPresent() && target.getAsInt() == p.getId()) return true;
		}
		return false;
	}
}
