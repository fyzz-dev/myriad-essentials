package dev.myriad.essentials.modules.player;

import dev.myriad.api.Myriad;
import dev.myriad.api.event.Subscribe;
import dev.myriad.api.event.events.TickEvent;
import dev.myriad.api.module.Categories;
import dev.myriad.api.module.Module;
import dev.myriad.api.setting.BoolSetting;
import dev.myriad.api.setting.IntSetting;
import dev.myriad.api.util.Slots;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Keeps hotbar stacks topped up from the rest of your inventory: a stack running low is refilled with the same item,
 * and a slot you've just used up gets the same item back. One refill a tick, and only with no screen open, so it never
 * fights you rearranging your inventory, and only while you stand still, since Grim (2b2t) refuses inventory clicks
 * while you move.
 */
public class StackReplenish extends Module {
	private final IntSetting threshold = sgGeneral.intSetting("Threshold").description("Refill a stack when it drops to this percent of a full one.")
		.defaultValue(25).range(1, 99).build();
	private final BoolSetting offhand = sgGeneral.bool("Offhand").description("Refill the off hand too (crystals, golden apples).").defaultValue(true).build();

	/** What each hotbar slot (and the off hand, last) held last tick, so an emptied slot can get the same item back. */
	private final ItemStack[] last = new ItemStack[10];

	public StackReplenish() {
		super(Categories.PLAYER, "Stack Replenish", "Refills hotbar stacks from your inventory.");
	}

	@Override
	protected void onEnable() {
		java.util.Arrays.fill(last, ItemStack.EMPTY);
	}

	@Subscribe
	private void onTick(TickEvent.Pre e) {
		if (!inGame() || mc.player.containerMenu != mc.player.inventoryMenu || !mc.player.inventoryMenu.getCarried().isEmpty()) return;
		Inventory inv = mc.player.getInventory();
		// With a screen open, slots empty because you're moving things; just keep track.
		boolean refilled = mc.gui.screen() != null;
		// Grim cancels inventory clicks while you move, so refills wait until you stand still (remembering what each
		// slot held, so one used up meanwhile is still refilled then).
		if (!refilled && !Myriad.inventory().safeToClick()) return;
		for (int i = 0; i < last.length; i++) {
			int slot = i < 9 ? i : Slots.OFF_HAND;
			if (slot == Slots.OFF_HAND && !offhand.get()) continue;
			ItemStack stack = inv.getItem(slot);
			if (!refilled) refilled = refill(slot, stack, last[i]);
			last[i] = stack.copy();
		}
	}

	/** Refills {@code slot} if it's low or was just emptied. Returns true if it sent a refill. */
	private boolean refill(int slot, ItemStack stack, ItemStack before) {
		if (stack.isEmpty()) {
			// Used up: put the same item back from the inventory.
			if (before == null || before.isEmpty() || !before.isStackable()) return false;
			int from = source(before);
			if (from < 0) return false;
			return slot == Slots.OFF_HAND ? Myriad.inventory().swapWithOffhand(from) : Myriad.inventory().moveToHotbar(from, slot);
		}
		if (!stack.isStackable() || stack.getCount() * 100 > stack.getMaxStackSize() * threshold.get()) return false;
		int from = source(stack);
		return from >= 0 && Myriad.inventory().merge(from, slot);
	}

	/** A main-inventory slot (9-35) holding the same item, or -1. */
	private int source(ItemStack like) {
		Inventory inv = mc.player.getInventory();
		for (int i = 9; i < 36; i++) if (ItemStack.isSameItemSameComponents(inv.getItem(i), like)) return i;
		return -1;
	}
}
