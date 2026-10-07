package dev.myriad.essentials.modules.player;

import dev.myriad.api.Myriad;
import dev.myriad.api.event.Subscribe;
import dev.myriad.api.event.events.ContainerScreenEvent;
import dev.myriad.api.module.Categories;
import dev.myriad.api.module.Module;
import dev.myriad.api.service.Breaking;
import dev.myriad.api.service.Placement;
import dev.myriad.api.setting.BoolSetting;
import dev.myriad.api.util.ItemInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Right-click a shulker box (or ender chest) in your inventory to open it on the spot: it's placed beside you, opened,
 * and broken and picked back up when you close it. Ender chests need a Silk Touch pickaxe in the hotbar, or breaking
 * them would only drop obsidian.
 */
public class InventoryTweaks extends Module {
	private final BoolSetting shulkers = sgGeneral.bool("Instant Shulker").description("Right-click a shulker box in your inventory to open it.").defaultValue(true).build();
	private final BoolSetting enderChests = sgGeneral.bool("Instant Ender Chest").description("Right-click an ender chest in your inventory to open it (needs a Silk Touch pickaxe to pick it back up).")
		.defaultValue(true).build();

	/** How long to wait for you to close the box, in ticks: long enough to sort a whole box by hand. */
	private static final int BROWSE_TICKS = 20 * 60 * 30;

	private boolean busy;

	public InventoryTweaks() {
		super(Categories.PLAYER, "Inventory Tweaks", "Open shulker boxes and ender chests straight from your inventory.");
	}

	@Override
	protected void onDisable() {
		busy = false;
	}

	@Subscribe
	private void onClick(ContainerScreenEvent.Click e) {
		if (onSlotClick(e.slot(), e.button(), e.input())) e.cancel();
	}

	/** Each slot click in an inventory screen. Returns true to swallow the click. */
	private boolean onSlotClick(Slot slot, int button, ContainerInput input) {
		if (busy || input != ContainerInput.PICKUP || button != 1 || slot == null || !slot.hasItem() || !inGame()) return false;
		if (mc.player.containerMenu != mc.player.inventoryMenu || mc.player.isCreative() || slot.container != mc.player.getInventory()) return false;
		if (!mc.player.inventoryMenu.getCarried().isEmpty()) return false;
		ItemStack stack = slot.getItem();
		boolean shulker = shulkers.get() && ItemInfo.isShulkerBox(stack);
		boolean ender = enderChests.get() && stack.is(Items.ENDER_CHEST);
		if (!shulker && !ender) return false;
		int index = slot.getContainerSlot();
		if (index < 0 || index >= 36) return false;

		int silk = ender ? silkPickaxe() : -1;
		if (ender && silk < 0) {
			warn("Needs a Silk Touch pickaxe in the hotbar to pick the ender chest back up");
			return true;
		}
		BlockPos spot = spot();
		if (spot == null) {
			warn("No room beside you to place it");
			return true;
		}
		open(index, spot, silk);
		return true;
	}

	private void open(int index, BlockPos spot, int silk) {
		busy = true;
		int[] hotbar = {index < 9 ? index : -1};
		int[] selected = {-1};
		Myriad.tasks().sequence(this)
			.run(() -> {
				if (hotbar[0] >= 0) return;
				int free = Myriad.inventory().findInHotbar(ItemStack::isEmpty);
				int to = free >= 0 ? free : mc.player.getInventory().getSelectedSlot();
				if (Myriad.inventory().moveToHotbar(index, to)) hotbar[0] = to;
			})
			.require(() -> hotbar[0] >= 0, "Couldn't move it to the hotbar")
			.run(() -> mc.player.closeContainer())
			.await(() -> succeeded(Myriad.placement().place(this, floorClick(spot), hotbar[0], Placement.Options.STRICT).result(), "The server didn't let it be placed"), 40)
			.await(() -> Myriad.containers().open(spot, 20), 40)
			.waitUntil(() -> Myriad.containers().current().isEmpty(), BROWSE_TICKS)
			.run(() -> {
				if (silk < 0) return;
				// Break it with the Silk Touch pickaxe, not just the fastest tool.
				selected[0] = mc.player.getInventory().getSelectedSlot();
				Myriad.inventory().select(silk);
			})
			.await(() -> succeeded(Myriad.breaking().breakBlock(this, spot, 10, breakOptions(silk >= 0)).result(), "Couldn't break it back up"), 20 * 30)
			// Let the drop reach you.
			.wait(10)
			.onFinish(() -> finish(selected[0]))
			.onFail(t -> {
				finish(selected[0]);
				warn(t.getMessage());
			})
			.start();
	}

	private void finish(int selected) {
		busy = false;
		if (selected >= 0 && inGame()) Myriad.inventory().select(selected);
	}

	private static Breaking.Options breakOptions(boolean silkTouch) {
		Breaking.Options d = Breaking.Options.DEFAULT;
		return silkTouch ? d.withAutoTool(false) : d;
	}

	/** {@code result} as a future that fails with {@code message} when it completes false. */
	private static CompletableFuture<Boolean> succeeded(CompletableFuture<Boolean> result, String message) {
		return result.thenApply(ok -> {
			if (!ok) throw new IllegalStateException(message);
			return true;
		});
	}

	/**
	 * Where to put it: beside you at foot level on solid ground, with air above so the lid can open, close enough that
	 * you pick it up when it breaks.
	 */
	private BlockPos spot() {
		BlockPos feet = mc.player.blockPosition();
		List<BlockPos> candidates = new ArrayList<>();
		for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (dx != 0 || dz != 0) candidates.add(feet.offset(dx, 0, dz));
		Vec3 pos = mc.player.position();
		candidates.sort(Comparator.comparingDouble(p -> Vec3.atBottomCenterOf(p).distanceToSqr(pos)));
		for (BlockPos p : candidates) {
			if (!mc.level.getBlockState(p.above()).isAir()) continue;
			if (!mc.level.getBlockState(p.below()).isFaceSturdy(mc.level, p.below(), Direction.UP)) continue;
			if (Myriad.placement().check(p, Placement.Options.STRICT).ok()) return p;
		}
		return null;
	}

	/** A click on the top of the block under {@code spot}, so the box faces up and its lid opens into the air above. */
	private static BlockHitResult floorClick(BlockPos spot) {
		BlockPos floor = spot.below();
		return new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false);
	}

	private int silkPickaxe() {
		return Myriad.inventory().findInHotbar(s -> s.is(ItemTags.PICKAXES) && ItemInfo.enchantmentLevel(s, Enchantments.SILK_TOUCH) > 0);
	}
}
