package dev.myriad.essentials.modules.player;

import dev.myriad.api.Myriad;
import dev.myriad.api.event.Subscribe;
import dev.myriad.api.event.events.TickEvent;
import dev.myriad.api.module.Categories;
import dev.myriad.api.module.Module;
import dev.myriad.api.setting.BoolSetting;
import dev.myriad.api.setting.IntSetting;
import dev.myriad.api.setting.RegistryListSetting;
import dev.myriad.api.util.Baritone;
import dev.myriad.api.util.Interactions;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Eats the most saturating food in your hotbar when your health or hunger drops below a threshold, then switches
 * back. Keeps eating while a screen is open, and pauses Baritone (when it's installed) until it's done.
 */
public class AutoEat extends Module {
	/** Ticks past the food's own eating time before giving up (lag, or the server refusing it). */
	private static final int GRACE_TICKS = 20;

	private final BoolSetting health = sgGeneral.bool("Health").description("Eat when health is low.").defaultValue(true).build();
	private final IntSetting healthThreshold = sgGeneral.intSetting("Health Threshold").description("Health (including absorption) to eat at.").defaultValue(10).range(1, 36).visible(health::get).build();
	private final BoolSetting hunger = sgGeneral.bool("Hunger").description("Eat when hunger is low.").defaultValue(true).build();
	private final IntSetting hungerThreshold = sgGeneral.intSetting("Hunger Threshold").description("Food level to eat at; 18 or more keeps natural regeneration going.")
		.defaultValue(16).range(1, 19).visible(hunger::get).build();
	private final RegistryListSetting<Item> blacklist = sgGeneral.items("Blacklist").description("Foods never to eat.")
		.defaultValue(Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO, Items.PUFFERFISH, Items.CHORUS_FRUIT, Items.SUSPICIOUS_STEW)
		.filter(i -> i.components().has(DataComponents.FOOD)).build();

	/** The hotbar slot being eaten from, or -1 when not eating. */
	private int eatSlot = -1;
	/** The slot to go back to afterwards. */
	private int returnSlot = -1;
	/** Ticks left before an eat that hasn't finished is given up. */
	private int deadline;

	public AutoEat() {
		super(Categories.PLAYER, "Auto Eat", "Eats when your health or hunger gets low.");
	}

	@Override
	public String hudInfo() {
		return eatSlot >= 0 ? "Eating" : null;
	}

	@Override
	protected void onDisable() {
		stop();
	}

	@Subscribe
	private void onTick(TickEvent.Pre e) {
		if (!inGame()) {
			stop();
		} else if (eatSlot >= 0) {
			keepEating();
		} else if (needsFood() && !mc.player.isUsingItem()) {
			begin();
		}
	}

	private boolean needsFood() {
		var p = mc.player;
		if (health.get() && p.getHealth() + p.getAbsorptionAmount() <= healthThreshold.get()) return true;
		return hunger.get() && p.getFoodData().getFoodLevel() <= hungerThreshold.get();
	}

	private void begin() {
		int slot = Myriad.inventory().bestInHotbar(this::score);
		if (slot < 0) return;
		returnSlot = mc.player.getInventory().getSelectedSlot();
		Myriad.inventory().select(slot);
		Interactions.useItem(InteractionHand.MAIN_HAND);
		if (!mc.player.isUsingItem()) {
			// The server didn't start it (cooldown, full hunger with a non-always-edible food): just switch back.
			Myriad.inventory().select(returnSlot);
			returnSlot = -1;
			return;
		}
		eatSlot = slot;
		deadline = mc.player.getUseItem().getUseDuration(mc.player) + GRACE_TICKS;
		mc.options.keyUse.setDown(true);
		Baritone.pause(this);
	}

	private void keepEating() {
		var p = mc.player;
		boolean eating = p.isAlive() && p.isUsingItem() && p.getUseItem().has(DataComponents.FOOD);
		if (!eating || --deadline < 0) {
			stop();
			return;
		}
		// Something else may have switched slots mid-bite.
		if (p.getInventory().getSelectedSlot() != eatSlot) Myriad.inventory().select(eatSlot);
		mc.options.keyUse.setDown(true);
	}

	/** Saturation first (it's what keeps hunger up), nutrition to break ties; 0 for anything not to eat. */
	private double score(ItemStack stack) {
		FoodProperties food = stack.get(DataComponents.FOOD);
		if (food == null || blacklist.contains(stack.getItem())) return 0;
		return 1 + food.saturation() * 100 + food.nutrition();
	}

	private void stop() {
		if (eatSlot < 0 && returnSlot < 0) return;
		mc.options.keyUse.setDown(false);
		var p = mc.player;
		if (p != null) {
			if (p.isUsingItem() && p.getUseItem().has(DataComponents.FOOD)) mc.gameMode.releaseUsingItem(p);
			if (returnSlot >= 0) Myriad.inventory().select(returnSlot);
		}
		Baritone.resume(this);
		eatSlot = returnSlot = -1;
	}
}
