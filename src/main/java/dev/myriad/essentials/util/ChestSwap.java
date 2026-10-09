package dev.myriad.essentials.util;

import dev.myriad.api.Myriad;
import dev.myriad.api.event.Subscribe;
import dev.myriad.api.event.events.PacketEvent;
import dev.myriad.api.util.ItemInfo;
import dev.myriad.api.util.Packets;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Swapping the worn elytra with a chestplate held in the off hand or hotbar, by using the held one (as a right-click
 * equips armour): how Elytra Tweaks' No Durability and Elytra Fly's bounce keep the server from gliding long enough to
 * wear the elytra. The server stops a glide on its next tick once the elytra is off; {@link GlideHold} keeps the client
 * (and Grim) gliding meanwhile, and {@link #startGlide()} starts it again with the elytra put back for a moment.
 * <p>
 * The server plays an equip sound for every swap, several times a second while gliding; those are muted here.
 */
public final class ChestSwap {
	/** How long after a swap its equip sound may still arrive. */
	private static final long MUTE_MS = 2000;
	/** How far from you the server may play it (it plays it where it has you, a round trip behind at glide speed). */
	private static final double MUTE_RANGE = 24;

	/** Extra wait past a round trip for the server's answer to the last swap, for jitter. */
	private static final long SETTLE_MARGIN_MS = 150, MAX_SETTLE_MS = 700;

	private static volatile Set<SoundEvent> muted = Set.of();
	private static long lastSwapMs;
	/**
	 * Where the last swap took its item from, so where what came off went: the next swap the other way takes it from
	 * there. With several elytras (or chestplates) at hand, the same one keeps going on.
	 */
	private static Pair home;
	/** The last time {@link #ensurePair()} moved something, and since when it has found nothing to move. */
	private static long lastFetchMs, missingSinceMs;
	private static final long FETCH_GAP_MS = 500, MISSING_MS = 1000;
	private static volatile long muteUntil;
	private static boolean subscribed;

	private ChestSwap() {
	}

	/** Where the item to swap with what's worn is: the off hand, or a hotbar slot (used from the main hand). */
	public record Pair(InteractionHand hand, int slot) {
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	/** Where the counterpart of what's worn is: a chestplate for the elytra, the elytra for a chestplate; null if none. */
	public static Pair pair() {
		var p = mc().player;
		ItemStack worn = p.getItemBySlot(EquipmentSlot.CHEST);
		boolean gliderOn = ItemInfo.isGlider(worn);
		if (!gliderOn && !isChestArmor(worn)) return null;
		Predicate<ItemStack> counterpart = gliderOn ? ChestSwap::isChestArmor : s -> ItemInfo.isGlider(s) && chestEquippable(s);
		// Not gear No Break put away (an elytra about to wear out) unless it's all there is: the elytra has to go back on,
		// and a chestplate worn a tick at a time in the air doesn't wear.
		Pair pair = find(counterpart.and(s -> !Myriad.inventory().isSpared(s)));
		return pair != null ? pair : find(counterpart);
	}

	private static Pair find(Predicate<ItemStack> counterpart) {
		var p = mc().player;
		if (home != null && counterpart.test(item(home))) return home;
		if (counterpart.test(p.getOffhandItem())) return new Pair(InteractionHand.OFF_HAND, -1);
		var inv = p.getInventory();
		if (counterpart.test(inv.getItem(inv.getSelectedSlot()))) return new Pair(InteractionHand.MAIN_HAND, inv.getSelectedSlot());
		for (int i = 0; i < 9; i++) if (counterpart.test(inv.getItem(i))) return new Pair(InteractionHand.MAIN_HAND, i);
		return null;
	}

	/** Whether a swap can go now: something to swap with, and no Curse of Binding on either. */
	public static boolean ready() {
		Pair pair = pair();
		return pair != null && !ItemInfo.isBound(mc().player.getItemBySlot(EquipmentSlot.CHEST)) && !ItemInfo.isBound(item(pair));
	}

	/** Whether an elytra is worn, or one is ready to be swapped on. */
	public static boolean hasGlider() {
		if (elytraWorn()) return true;
		Pair pair = pair();
		return pair != null && ItemInfo.isGlider(item(pair));
	}

	/**
	 * Swaps what's worn with what {@link #pair()} finds, told to the server with the rotation this tick's movement packet
	 * carries (Grim's BadPacketsJ). False if there's nothing to swap with.
	 */
	public static boolean swap() {
		Pair pair = pair();
		if (pair == null) return false;
		lastSwapMs = System.currentTimeMillis();
		home = pair;
		mute(item(pair), mc().player.getItemBySlot(EquipmentSlot.CHEST));
		Runnable use = () -> Packets.sendSequenced(seq -> {
			var p = mc().player;
			InteractionHand hand = pair.hand();
			ItemStack stack = p.getItemInHand(hand);
			InteractionResult result = stack.use(mc().level, p, hand);
			ItemStack after = result instanceof InteractionResult.Success s && s.heldItemTransformedTo() != null ? s.heldItemTransformedTo() : p.getItemInHand(hand);
			if (after != stack) p.setItemInHand(hand, after);
			float[] r = Myriad.rotations().rotationForAction();
			return new ServerboundUseItemPacket(hand, seq, r[0], r[1]);
		});
		if (pair.hand() == InteractionHand.OFF_HAND) use.run();
		else Myriad.inventory().silentSwap(pair.slot(), use);
		return true;
	}

	/**
	 * Starts the glide again on the server: the elytra goes on if it isn't, and the start is sent. The caller sends the
	 * answers {@link GlideHold} held first, presses jump this tick (released the tick before, as Grim's ElytraB wants)
	 * and swaps the chestplate back on after, if it wants to.
	 */
	public static boolean startGlide() {
		if (!elytraWorn() && !swap()) return false;
		Packets.send(new ServerboundPlayerCommandPacket(mc().player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
		return true;
	}

	/**
	 * Whether the server has answered the last swap: what's worn can be trusted again. Until then the client may show a
	 * moment that's already past: two swaps sent in one tick can reach the server in different ticks (jitter), and its
	 * answer to the first puts the elytra back on the client for a tick.
	 */
	public static boolean settled() {
		// Capped, so a glide waiting on it stays well short of the 20 ticks (1 s) that wear the elytra.
		return System.currentTimeMillis() - lastSwapMs > Math.min(Myriad.server().ping() + SETTLE_MARGIN_MS, MAX_SETTLE_MS);
	}

	/** Puts the elytra back on if a chestplate is worn and the elytra is at hand. */
	public static void restoreElytra() {
		if (!elytraWorn() && hasGlider()) swap();
	}

	/** What {@link #ensurePair()} found. */
	public enum Need {
		/** The counterpart is at hand: swaps can go. */
		READY,
		/** It's being brought into the hotbar, or what's worn can't be trusted yet (a swap is on its way): ask again. */
		WAITING,
		/** There's no counterpart anywhere (for a while: not just a swap's moment): say so. */
		MISSING,
		/** There is one, in the inventory, but no hotbar slot to bring it into. */
		NO_ROOM
	}

	/**
	 * Makes sure the counterpart of what's worn is at hand (see {@link #pair()}), bringing it into the hotbar from the
	 * inventory if it isn't: into an empty slot, or one holding a spare of what's worn (another elytra while one is worn),
	 * which goes back where this came from, so spares don't pile up in the hotbar. Never while a swap is on its way (what
	 * the client shows as worn may be a moment old), and at most one click every half second; with no slot to use it
	 * doesn't click at all. Grim refuses a click while you move or sprint: with {@code mayStop} your keys are let go for
	 * a tick first, otherwise it waits for a moment you're still (a bounce holds sprint, so it can't stop it).
	 */
	public static Need ensurePair(boolean mayStop) {
		var p = mc().player;
		if (pair() != null) {
			missingSinceMs = 0;
			return Need.READY;
		}
		if (!settled()) return Need.WAITING;
		ItemStack worn = p.getItemBySlot(EquipmentSlot.CHEST);
		boolean gliderOn = ItemInfo.isGlider(worn);
		// Nothing worn to swap (a moment between two swaps, or the chest slot is empty): nothing to bring in.
		if (!gliderOn && !isChestArmor(worn)) return Need.WAITING;
		Predicate<ItemStack> wanted = gliderOn ? ChestSwap::isChestArmor : s -> ItemInfo.isGlider(s) && chestEquippable(s);
		var inv = p.getInventory();
		int from = -1;
		for (int i = 9; i < 36 && from < 0; i++) if (wanted.test(inv.getItem(i)) && !Myriad.inventory().isSpared(inv.getItem(i))) from = i;
		for (int i = 9; i < 36 && from < 0; i++) if (wanted.test(inv.getItem(i))) from = i;
		long now = System.currentTimeMillis();
		if (from < 0) {
			if (missingSinceMs == 0) missingSinceMs = now;
			return now - missingSinceMs > MISSING_MS ? Need.MISSING : Need.WAITING;
		}
		missingSinceMs = 0;
		Predicate<ItemStack> spare = gliderOn ? ItemInfo::isGlider : ChestSwap::isChestArmor;
		int to = -1;
		for (int i = 0; i < 9 && to < 0; i++) if (usable(i) && inv.getItem(i).isEmpty()) to = i;
		for (int i = 0; i < 9 && to < 0; i++) if (usable(i) && spare.test(inv.getItem(i))) to = i;
		if (to < 0) return Need.NO_ROOM;
		if (now - lastFetchMs < FETCH_GAP_MS) return Need.WAITING;
		if (mayStop ? !Myriad.inventory().prepareClick() : !Myriad.inventory().safeToClick() || p.isSprinting()) return Need.WAITING;
		Myriad.inventory().move(from, to);
		lastFetchMs = now;
		return Need.WAITING;
	}

	/** A hotbar slot to bring something into: not in hand, and not held by a module. */
	private static boolean usable(int slot) {
		var inv = mc().player.getInventory();
		return slot != inv.getSelectedSlot() && slot != Myriad.inventory().serverSlot();
	}

	public static boolean elytraWorn() {
		return ItemInfo.isGlider(mc().player.getItemBySlot(EquipmentSlot.CHEST));
	}

	/** Mutes the equip sounds the server plays for this swap (whichever of the two goes on). */
	private static void mute(ItemStack a, ItemStack b) {
		if (!subscribed) {
			Myriad.events().subscribe(new Object() {
				@Subscribe(packets = ClientboundSoundPacket.class)
				private void onReceive(PacketEvent.Receive e) {
					if (isSwapSound((ClientboundSoundPacket) e.packet())) e.cancel();
				}
			});
			subscribed = true;
		}
		Set<SoundEvent> sounds = new HashSet<>();
		for (ItemStack stack : new ItemStack[]{a, b}) {
			Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
			if (equippable != null) sounds.add(equippable.equipSound().value());
		}
		muted = sounds;
		muteUntil = System.currentTimeMillis() + MUTE_MS;
	}

	/** Network thread: an equip sound from a recent swap, played where the server has you. */
	private static boolean isSwapSound(ClientboundSoundPacket sound) {
		if (System.currentTimeMillis() > muteUntil || !muted.contains(sound.getSound().value())) return false;
		var p = mc().player;
		return p != null && p.distanceToSqr(sound.getX(), sound.getY(), sound.getZ()) < MUTE_RANGE * MUTE_RANGE;
	}

	private static ItemStack item(Pair pair) {
		return pair.hand() == InteractionHand.OFF_HAND ? mc().player.getOffhandItem() : mc().player.getInventory().getItem(pair.slot());
	}

	private static boolean chestEquippable(ItemStack stack) {
		Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
		return equippable != null && equippable.swappable() && equippable.slot() == EquipmentSlot.CHEST && stack.getCount() == 1;
	}

	private static boolean isChestArmor(ItemStack stack) {
		return chestEquippable(stack) && !ItemInfo.isGlider(stack);
	}
}
