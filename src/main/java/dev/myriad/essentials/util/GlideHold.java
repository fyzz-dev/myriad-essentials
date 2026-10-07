package dev.myriad.essentials.util;

import dev.myriad.api.Myriad;
import dev.myriad.api.event.Priority;
import dev.myriad.api.event.Subscribe;
import dev.myriad.api.event.events.PacketEvent;
import dev.myriad.api.event.events.TeleportEvent;
import dev.myriad.api.event.events.WorldEvent;
import dev.myriad.essentials.mixin.EntityAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;

import java.util.ArrayDeque;

/**
 * Keeps the local player gliding through a moment the server stops the glide, without Grim (2b2t) noticing: the
 * trick behind Elytra Tweaks' No Durability and Elytra Fly's bounce.
 * <p>
 * The server stops a glide (it sees the elytra come off, or you land) by sending your entity flags with the gliding bit
 * cleared. Grim sends one of its pings just before those flags, and only takes the glide as stopped once that ping is
 * answered. So from the ping that came in right before the clear, the answers are held back: Grim keeps expecting
 * gliding, and the client keeps gliding (the clear isn't applied). The owner then sends the answers ({@link #release()})
 * just before it starts gliding again, so Grim sees the stop and then the new start, in order, and nothing in between
 * looks wrong to it. One owner at a time; the hold is let go if the owner doesn't release it within a second.
 * <p>
 * The ping and flags are watched as they arrive, on the network thread (the ping is answered on the game thread
 * before the flags are applied); the mixins call in on the game thread to hold answers and keep the glide.
 */
public final class GlideHold {
	private static final GlideHold INSTANCE = new GlideHold();
	private static final int NONE = Integer.MIN_VALUE;
	/** Held answers are sent anyway after this long, so a forgotten hold can't time you out. */
	private static final long MAX_HOLD_MS = 1000;
	/** Vanilla's gliding bit in the entity flag byte ({@code Entity.FLAG_FALL_FLYING}). */
	private static final int GLIDING_BIT = 1 << 7;

	private volatile Object owner;
	private boolean subscribed;

	/** Network thread: the last ping to arrive; and, once a clear has arrived, the ping that came just before it. */
	private volatile int lastPing = NONE, holdFrom = NONE;
	/** Network thread: a clear has arrived while armed; answers from {@link #holdFrom} on are held. */
	private volatile boolean clearArriving;
	private volatile long clearArrivedMs;

	// Game thread.
	private boolean holdingAnswers, clearApplied, exposed;
	private final ArrayDeque<Integer> held = new ArrayDeque<>();
	/** Recently answered pings, to tell whether a clear's own ping was answered before the hold began. */
	private final int[] answered = new int[64];
	private int answeredNext;

	private GlideHold() {
		java.util.Arrays.fill(answered, NONE);
	}

	/** Holds the next glide clear for {@code owner}; false if another owner has it. */
	public static boolean arm(Object owner) {
		GlideHold h = INSTANCE;
		if (h.owner != null && h.owner != owner) return false;
		h.owner = owner;
		if (!h.subscribed) {
			Myriad.events().subscribe(h);
			h.subscribed = true;
		}
		return true;
	}

	/** Lets go: any held answers are sent, and nothing more is held for {@code owner}. */
	public static void disarm(Object owner) {
		GlideHold h = INSTANCE;
		if (h.owner != owner) return;
		h.flush();
		h.owner = null;
		if (h.subscribed) {
			Myriad.events().unsubscribe(h);
			h.subscribed = false;
		}
	}

	/**
	 * Sends the held answers and ends this hold (staying armed for the next clear). Call it right before starting the
	 * glide again: Grim then sees the stop before the start. The client keeps gliding until the owner decides otherwise.
	 */
	public static void release(Object owner) {
		if (INSTANCE.owner == owner) INSTANCE.flush();
	}

	/** Whether the server has stopped the glide and the client kept gliding through it (Grim doesn't know yet). */
	public static boolean clearHeld(Object owner) {
		return INSTANCE.owner == owner && INSTANCE.clearApplied && !INSTANCE.exposed;
	}

	/**
	 * Whether a clear came in that Grim already knows about (its ping was answered before it could be held, or a
	 * teleport sent the answers): the glide has to be started again at once, as Grim now expects no gliding.
	 */
	public static boolean exposed(Object owner) {
		return INSTANCE.owner == owner && INSTANCE.exposed;
	}

	/** Whether a clear has come in (held or exposed) and the next start may go: Grim takes the glide as stopped by then. */
	public static boolean cleared(Object owner) {
		return INSTANCE.owner == owner && (INSTANCE.clearApplied || INSTANCE.exposed);
	}

	private void flush() {
		var connection = Minecraft.getInstance().getConnection();
		while (!held.isEmpty()) {
			int id = held.pollFirst();
			if (connection != null) connection.send(new ServerboundPongPacket(id));
			recordAnswered(id);
		}
		holdingAnswers = clearApplied = exposed = false;
		clearArriving = false;
		holdFrom = NONE;
	}

	// ---- network thread ---------------------------------------------------------------------------------------------

	@Subscribe(priority = Priority.HIGHEST, packets = {ClientboundPingPacket.class, ClientboundSetEntityDataPacket.class})
	private void onReceive(PacketEvent.Receive e) {
		if (e.packet() instanceof ClientboundPingPacket ping) {
			lastPing = ping.getId();
			return;
		}
		if (e.packet() instanceof ClientboundSetEntityDataPacket data && owner != null && !clearArriving && endsOwnGlide(data)) {
			holdFrom = lastPing;
			clearArrivedMs = System.currentTimeMillis();
			clearArriving = true;
		}
	}

	/** Whether {@code data} is the server turning the local player's glide off while the client still glides. */
	private static boolean endsOwnGlide(ClientboundSetEntityDataPacket data) {
		LocalPlayer p = Minecraft.getInstance().player;
		if (p == null || data.id() != p.getId() || !p.isFallFlying()) return false;
		int flagsId = EntityAccessor.essentials$entityFlags().id();
		return data.packedItems().stream().anyMatch(v -> v.id() == flagsId && v.value() instanceof Byte flags && (flags & GLIDING_BIT) == 0);
	}

	@Subscribe
	private void onTeleport(TeleportEvent e) {
		onTeleport();
	}

	@Subscribe
	private void onLeave(WorldEvent.Leave e) {
		held.clear();
		holdingAnswers = clearApplied = exposed = clearArriving = false;
		holdFrom = lastPing = NONE;
	}

	// ---- game thread, from the mixins and events --------------------------------------------------------------------

	/** A ping is being answered: true to hold the answer back. */
	public static boolean holdAnswer(int id) {
		GlideHold h = INSTANCE;
		if (h.owner != null && h.clearArriving && !h.holdingAnswers && (h.holdFrom == NONE || id == h.holdFrom)) h.holdingAnswers = true;
		if (h.holdingAnswers && System.currentTimeMillis() - h.clearArrivedMs > MAX_HOLD_MS) {
			h.flush();
			h.exposed = true;
		}
		if (h.holdingAnswers && h.owner != null) {
			h.held.addLast(id);
			return true;
		}
		h.recordAnswered(id);
		return false;
	}

	/**
	 * The server's clear is being applied to the local player: true to keep gliding (the caller starts the glide
	 * again on the client). If its ping was answered before the hold could begin, Grim already knows, so it's
	 * exposed: the owner has to start the glide again at once.
	 */
	public static boolean keepGliding() {
		GlideHold h = INSTANCE;
		if (h.owner == null || !h.clearArriving) return false;
		h.clearApplied = true;
		if (!h.holdingAnswers && h.holdFrom != NONE && h.wasAnswered(h.holdFrom)) h.exposed = true;
		return true;
	}

	/** A teleport is being applied: Grim resets around it, so the held answers go out and the hold is exposed. */
	private static void onTeleport() {
		GlideHold h = INSTANCE;
		if (h.owner == null || h.held.isEmpty() && !h.clearApplied) return;
		boolean applied = h.clearApplied;
		h.flush();
		if (applied) {
			h.clearApplied = true;
			h.exposed = true;
		}
	}

	private void recordAnswered(int id) {
		answered[answeredNext] = id;
		answeredNext = (answeredNext + 1) % answered.length;
	}

	private boolean wasAnswered(int id) {
		for (int a : answered) if (a == id) return true;
		return false;
	}
}
