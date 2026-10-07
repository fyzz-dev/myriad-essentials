package dev.myriad.essentials.modules.player;

import dev.myriad.api.Myriad;
import dev.myriad.api.event.Subscribe;
import dev.myriad.api.event.events.AttackEvent;
import dev.myriad.api.event.events.BlockBreakEvent;
import dev.myriad.api.event.events.TickEvent;
import dev.myriad.api.module.Categories;
import dev.myriad.api.module.Module;
import dev.myriad.api.setting.BoolSetting;
import dev.myriad.api.setting.DoubleSetting;
import dev.myriad.api.util.Interactions;
import dev.myriad.api.util.ItemInfo;
import dev.myriad.api.util.Mining;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.ToDoubleFunction;

/**
 * Switches to the best tool for the block you're mining, and optionally the best weapon when you attack. Silent mode
 * swaps only on the server, so your hotbar doesn't move and mining still runs at the tool's speed; otherwise it selects
 * the tool and (with Swap Back) returns to your previous slot when you stop. Tools about to break are left alone.
 * A better tool in your inventory is brought into the hotbar (an empty slot, over a worse tool, or over building
 * blocks); while you move, your keys are released for a tick first, since Grim (2b2t) refuses inventory clicks while
 * you move.
 */
public class AutoTool extends Module {
	private final BoolSetting silent = sgGeneral.bool("Silent").description("Swap on the server only; your selected slot stays put.").defaultValue(true).build();
	private final BoolSetting swapBack = sgGeneral.bool("Swap Back").description("Go back to the slot you had when you stop mining.").defaultValue(true)
		.visible(() -> !silent.get()).build();
	private final BoolSetting weapon = sgGeneral.bool("Weapon").description("Switch to your best weapon when you attack.").build();
	private final DoubleSetting preserve = sgGeneral.doubleSetting("Preserve").description("Don't use tools with less durability than this (%).").defaultValue(3).range(0, 50).decimals(0).build();

	private boolean holding;
	private int previousSlot = -1, holdTicks;

	public AutoTool() {
		super(Categories.PLAYER, "Auto Tool", "Switches to the best tool for what you're mining.");
	}

	@Override
	protected void onDisable() {
		stop();
	}

	@Subscribe
	private void onStart(BlockBreakEvent.Start e) {
		equipFor(e.pos());
	}

	@Subscribe
	private void onProgress(BlockBreakEvent.Progress e) {
		equipFor(e.pos());
	}

	@Subscribe
	private void onAttack(AttackEvent e) {
		if (!weapon.get() || !inGame()) return;
		int slot = Myriad.inventory().bestInHotbar(this::weaponScore);
		if (slot >= 0) use(slot, 4);
	}

	/** At the start of the tick, so switching back goes out before movement, where vanilla switches slots. */
	@Subscribe
	private void onTick(TickEvent.Pre e) {
		if (!inGame()) {
			stop();
			return;
		}
		if (holdTicks > 0) holdTicks--;
		if (holdTicks == 0 && !Interactions.isMining()) stop();
	}

	private void equipFor(BlockPos pos) {
		if (!inGame() || mc.player.isCreative()) return;
		BlockState state = mc.level.getBlockState(pos);
		ToDoubleFunction<ItemStack> score = s -> toolScore(s, state);
		int slot = Myriad.inventory().bestInHotbar(score);
		int best = Myriad.inventory().bestInInventory(score);
		if (best >= 9) {
			// Better than anything in the hotbar: borrow it (a worse tool makes room; a tick later while you move). It
			// goes back to the inventory once you stop mining.
			int pulled = Myriad.inventory().borrow(this, best, s -> toolScore(s, state) > 0);
			if (pulled >= 0) slot = pulled;
		}
		if (slot >= 0) {
			Myriad.inventory().borrow(this, slot, null);
			use(slot, 2);
		}
	}

	/** Puts {@code slot} in hand (on the server only when silent) for at least {@code ticks}. */
	private void use(int slot, int ticks) {
		holdTicks = Math.max(holdTicks, ticks);
		if (silent.get()) {
			if (Myriad.inventory().hold(this, slot, ticks + 20)) holding = true;
			return;
		}
		int selected = mc.player.getInventory().getSelectedSlot();
		if (selected == slot) return;
		if (previousSlot < 0) previousSlot = selected;
		Myriad.inventory().select(slot);
	}

	private void stop() {
		if (holding) {
			holding = false;
			Myriad.inventory().release(this);
		}
		if (previousSlot >= 0 && mc.player != null && swapBack.get()) Myriad.inventory().select(previousSlot);
		previousSlot = -1;
	}

	/** How fast {@code s} mines {@code state}, with a bonus for actually getting the drops; 0 for no better than a hand. */
	private double toolScore(ItemStack s, BlockState state) {
		if (s.isEmpty() || worn(s)) return 0;
		double speed = s.getDestroySpeed(state);
		if (speed <= 1) return 0;
		int eff = Mining.efficiency(s);
		if (eff > 0) speed += eff * eff + 1;
		return speed + (state.requiresCorrectToolForDrops() && s.isCorrectToolForDrops(state) ? 1000 : 0);
	}

	private double weaponScore(ItemStack s) {
		if (s.isEmpty() || worn(s)) return 0;
		double damage = s.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY).compute(Attributes.ATTACK_DAMAGE, 1, EquipmentSlot.MAINHAND);
		if (damage <= 1) return 0;
		int sharpness = ItemInfo.enchantmentLevel(s, Enchantments.SHARPNESS);
		return damage + (sharpness > 0 ? 0.5 * sharpness + 0.5 : 0);
	}

	private boolean worn(ItemStack s) {
		return s.isDamageableItem() && ItemInfo.durabilityFraction(s) * 100 < preserve.get();
	}
}
