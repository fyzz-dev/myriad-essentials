package dev.myriad.essentials.modules.player;

import dev.myriad.api.Myriad;
import dev.myriad.api.event.Subscribe;
import dev.myriad.api.event.events.TickEvent;
import dev.myriad.api.module.Categories;
import dev.myriad.api.module.Module;
import dev.myriad.api.setting.BoolSetting;
import dev.myriad.api.setting.IntSetting;
import dev.myriad.api.util.ItemInfo;
import dev.myriad.api.util.Slots;
import dev.myriad.essentials.modules.movement.ElytraTweaks;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Takes gear out of use before it breaks, so it can be mended later. Worn armour and elytras, and the tools and weapons
 * in your hands, are swapped with the best piece of the same kind you carry with more uses left (another elytra for the
 * elytra, another pickaxe for the pickaxe) once they're down to their threshold. With none to swap in, armour comes off
 * and a tool goes into the inventory, out of your hands; the elytra you're flying with stays on until you land.
 * <p>
 * What it takes out of use is spared ({@link dev.myriad.api.service.Inventory#spare}): Auto Armor, Auto Tool, Elytra
 * Tweaks and core's tool picking for breaks leave it where it is. While you move, your keys are released for a tick before
 * each swap, as Grim (2b2t) refuses inventory clicks while you move.
 */
public class NoBreak extends Module {
	private final BoolSetting armor = sgGeneral.bool("Armor").description("Worn armour and elytras.").defaultValue(true).build();
	private final BoolSetting tools = sgGeneral.bool("Tools").description("Tools and weapons in your hands.").defaultValue(true).build();
	private final IntSetting armorUses = sgGeneral.intSetting("Armor Uses")
		.description("Swap armour out with this many uses left. Each piece loses a quarter of a hit's damage, so blasts need room; an elytra loses one a second.")
		.defaultValue(20).range(1, 100).visible(armor::get).build();
	private final IntSetting toolUses = sgGeneral.intSetting("Tool Uses").description("Swap tools and weapons out with this many uses left.")
		.defaultValue(10).range(1, 100).visible(tools::get).build();

	private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
	/** Tools of the same kind replace each other; anything else only by the same item. */
	private static final List<TagKey<Item>> KINDS = List.of(ItemTags.PICKAXES, ItemTags.AXES, ItemTags.SHOVELS, ItemTags.HOES, ItemTags.SWORDS);

	/** Something couldn't be taken off for lack of room (said once until it can). */
	private boolean warnedFull;

	public NoBreak() {
		super(Categories.PLAYER, "No Break", "Swaps armour, elytras and tools out before they break, so they can be mended.");
	}

	@Override
	protected void onEnable() {
		warnedFull = false;
		Myriad.inventory().spare(this, this::spared);
	}

	@Override
	protected void onDisable() {
		Myriad.inventory().spare(this, null);
	}

	/** Gear at or under its threshold of uses left. */
	private boolean spared(ItemStack stack) {
		if (!stack.isDamageableItem()) return false;
		boolean worn = ItemInfo.armorSlot(stack) != null;
		if (worn ? !armor.get() : !tools.get()) return false;
		return ItemInfo.durability(stack) <= (worn ? armorUses.get() : toolUses.get());
	}

	@Subscribe
	private void onTick(TickEvent.Pre e) {
		if (!inGame() || mc.player.isCreative()) return;
		if (mc.gui.screen() != null && !(mc.gui.screen() instanceof InventoryScreen) && mc.player.containerMenu != mc.player.inventoryMenu) return;
		// One move a tick: each waits for a click that's safe to send.
		if (armor.get()) {
			for (EquipmentSlot slot : ARMOR) if (saveArmor(slot)) return;
		}
		if (tools.get()) {
			var inv = mc.player.getInventory();
			if (saveHeld(inv.getSelectedSlot())) return;
			if (Myriad.inventory().serverSlot() != inv.getSelectedSlot() && saveHeld(Myriad.inventory().serverSlot())) return;
			saveHeld(Slots.OFF_HAND);
		}
	}

	/** Swaps the piece worn in {@code slot} out if it's about to break; true if it did something (or is waiting to). */
	private boolean saveArmor(EquipmentSlot slot) {
		ItemStack worn = mc.player.getItemBySlot(slot);
		if (worn.isEmpty() || !spared(worn) || ItemInfo.isBound(worn)) return false;
		// Elytra Tweaks and Elytra Fly swap the elytra with a chestplate while you fly; they put it back themselves.
		if (slot == EquipmentSlot.CHEST && ElytraTweaks.holdsChest()) return false;
		boolean glider = ItemInfo.isGlider(worn);
		int to = ItemInfo.inventoryIndex(slot);
		int with = best(s -> {
			if (glider) return ItemInfo.isGlider(s) ? ItemInfo.durability(s) : 0;
			if (ItemInfo.armorSlot(s) != slot || ItemInfo.isGlider(s) || ItemInfo.isBound(s)) return 0;
			return 1 + ItemInfo.armor(s, slot) * 10 + ItemInfo.toughness(s, slot) * 4 + ItemInfo.durabilityFraction(s);
		});
		boolean takeOff = with < 0;
		if (takeOff) {
			// Taking the elytra off in mid-air would drop you: it waits for the ground (or a fresh one).
			if (glider && (mc.player.isFallFlying() || !mc.player.onGround())) return false;
			with = emptySlot();
			if (with < 0) return full(worn);
		}
		if (!Myriad.inventory().prepareClick()) return true;
		String name = worn.getHoverName().getString();
		if (Myriad.inventory().move(with, to)) moved((takeOff ? "Took off " : "Swapped out ") + name, worn);
		return true;
	}

	/** Swaps the item in hand at {@code index} (a hotbar slot or the off hand) out if it's about to break. */
	private boolean saveHeld(int index) {
		var inv = mc.player.getInventory();
		ItemStack held = index == Slots.OFF_HAND ? mc.player.getOffhandItem() : inv.getItem(index);
		if (held.isEmpty() || ItemInfo.armorSlot(held) != null || !spared(held)) return false;
		TagKey<Item> kind = kind(held);
		int with = best(s -> s != held && (kind != null ? s.is(kind) : s.is(held.getItem())) ? (s.is(held.getItem()) ? 1_000_000 : 0) + ItemInfo.durability(s) : 0);
		if (with < 0) {
			// Out of your hands: into an empty slot of the main inventory, else swapped with something that doesn't wear.
			with = emptyMainSlot();
			if (with < 0) {
				for (int i = Slots.MAIN_START; i < Slots.MAIN_END && with < 0; i++) if (!inv.getItem(i).isDamageableItem()) with = i;
			}
			if (with < 0) return full(held);
		}
		if (!Myriad.inventory().prepareClick()) return true;
		String name = held.getHoverName().getString();
		boolean moved = index == Slots.OFF_HAND ? Myriad.inventory().swapWithOffhand(with) : Myriad.inventory().move(with, index);
		if (moved) moved("Put away " + name, held);
		return true;
	}

	private void moved(String what, ItemStack stack) {
		info(what + " (" + ItemInfo.durability(stack) + " uses left)");
		warnedFull = false;
	}

	/** The hotbar or main-inventory slot (0-35) whose item scores highest, passing over spared ones; -1 if none. */
	private int best(ToDoubleFunction<ItemStack> score) {
		return Myriad.inventory().bestInInventory(score);
	}

	private static TagKey<Item> kind(ItemStack stack) {
		for (TagKey<Item> kind : KINDS) if (stack.is(kind)) return kind;
		return null;
	}

	/** An empty main-inventory slot, else an empty hotbar slot that isn't in hand; -1 if none. */
	private int emptySlot() {
		int main = emptyMainSlot();
		if (main >= 0) return main;
		var inv = mc.player.getInventory();
		for (int i = 0; i < 9; i++) if (i != inv.getSelectedSlot() && i != Myriad.inventory().serverSlot() && inv.getItem(i).isEmpty()) return i;
		return -1;
	}

	private int emptyMainSlot() {
		var inv = mc.player.getInventory();
		for (int i = Slots.MAIN_START; i < Slots.MAIN_END; i++) if (inv.getItem(i).isEmpty()) return i;
		return -1;
	}

	private boolean full(ItemStack stack) {
		if (!warnedFull) warn("No room to put away " + stack.getHoverName().getString() + " before it breaks.");
		warnedFull = true;
		return false;
	}
}
