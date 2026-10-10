package dev.myriad.essentials.modules.player;

import dev.myriad.api.Myriad;
import dev.myriad.api.event.Subscribe;
import dev.myriad.api.event.events.TickEvent;
import dev.myriad.api.module.Categories;
import dev.myriad.api.module.Module;
import dev.myriad.api.module.Modules;
import dev.myriad.api.setting.BoolSetting;
import dev.myriad.api.setting.IntSetting;
import dev.myriad.api.setting.RegistryListSetting;
import dev.myriad.api.util.Baritone;
import dev.myriad.api.util.Interactions;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.function.ToDoubleFunction;

/**
 * Eats when your health or hunger drops below a threshold, picking the food by why it eats: low health goes for
 * Health Foods first (golden apples; the strongest of them first, an enchanted one before a plain one, for the most
 * health at once), and hunger for the most saturating ordinary food
 * (golden carrots before steak), keeping the Health Foods for when they're needed unless they're all you have. Food is
 * found anywhere in your inventory: from outside the hotbar it's borrowed into it and put back afterwards. Switches
 * back to the slot you had, keeps eating while a screen is open, and pauses Baritone (when it's installed) until it's
 * done.
 */
public class AutoEat extends Module {
	/** Ticks past the food's own eating time before giving up (lag, or the server refusing it). */
	private static final int GRACE_TICKS = 20;

	private final BoolSetting health = sgGeneral.bool("Health").description("Eat when health is low.").defaultValue(true).build();
	private final IntSetting healthThreshold = sgGeneral.intSetting("Health Threshold").description("Health (including absorption) to eat at.").defaultValue(10).range(1, 36).visible(health::get).build();
	private final BoolSetting hunger = sgGeneral.bool("Hunger").description("Eat when hunger is low.").defaultValue(true).build();
	private final IntSetting hungerThreshold = sgGeneral.intSetting("Hunger Threshold").description("Food level to eat at; 18 or more keeps natural regeneration going.")
		.defaultValue(16).range(1, 19).visible(hunger::get).build();
	private final RegistryListSetting<Item> healthFoods = sgGeneral.items("Health Foods")
		.description("Eaten first when health is low (the strongest of them first).")
		.defaultValue(Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE)
		.filter(i -> i.components().has(DataComponents.FOOD)).build();
	private final BoolSetting saveHealthFoods = sgGeneral.bool("Save Health Foods")
		.description("For hunger, eat Health Foods only when there's nothing else.").defaultValue(true).build();
	private final RegistryListSetting<Item> blacklist = sgGeneral.items("Blacklist").description("Foods never to eat.")
		.defaultValue(Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO, Items.PUFFERFISH, Items.CHORUS_FRUIT, Items.SUSPICIOUS_STEW)
		.filter(i -> i.components().has(DataComponents.FOOD)).build();

	/** The hotbar slot being eaten from, or -1 when not eating. */
	private int eatSlot = -1;
	/** Whether {@link #eatSlot} holds food borrowed from the main inventory (it goes back afterwards). */
	private boolean borrowed;
	/** The slot to go back to afterwards. */
	private int returnSlot = -1;
	/** Ticks left before an eat that hasn't finished is given up. */
	private int deadline;
	/** Ticks left that the last refused bite (the server didn't start it) keeps {@link #wantsToEat} quiet. */
	private int refused;
	private static final int REFUSED_TICKS = 40;

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

	/**
	 * Whether Auto Eat is eating, or about to (hungry or hurt enough, with something to eat): what would stop it waits
	 * meanwhile, as Elytra Fly's swaps do (Grim stops eating at each), also between one bite and the next.
	 */
	public static boolean wantsToEat() {
		AutoEat m = Modules.active(AutoEat.class);
		if (m == null || Minecraft.getInstance().player == null) return false;
		if (m.eatSlot >= 0) return true;
		if (m.refused > 0) return false;
		boolean health = m.lowHealth();
		if (!health && !m.hungry()) return false;
		return Myriad.inventory().bestInInventory(health ? m::healthScore : m::hungerScore) >= 0;
	}

	@Subscribe
	private void onTick(TickEvent.Pre e) {
		if (refused > 0) refused--;
		if (!inGame()) {
			stop();
		} else if (eatSlot >= 0) {
			keepEating();
		} else if (!mc.player.isUsingItem()) {
			if (lowHealth()) begin(this::healthScore);
			else if (hungry()) begin(this::hungerScore);
		}
	}

	private boolean lowHealth() {
		var p = mc.player;
		return health.get() && p.getHealth() + p.getAbsorptionAmount() <= healthThreshold.get();
	}

	private boolean hungry() {
		return hunger.get() && mc.player.getFoodData().getFoodLevel() <= hungerThreshold.get();
	}

	/** Eats the best-scoring food in the inventory, borrowing it into the hotbar if it isn't there (may take a tick). */
	private void begin(ToDoubleFunction<ItemStack> score) {
		int index = Myriad.inventory().bestInInventory(score);
		if (index < 0) return;
		int slot = index;
		if (index >= 9) {
			// Into a hotbar slot for now (it goes back once eaten), never over other food.
			slot = Myriad.inventory().borrow(this, index, s -> s.get(DataComponents.FOOD) == null);
			if (slot < 0) return;
			borrowed = true;
		}
		returnSlot = mc.player.getInventory().getSelectedSlot();
		Myriad.inventory().select(slot);
		Interactions.useItem(InteractionHand.MAIN_HAND);
		if (!mc.player.isUsingItem()) {
			// The server didn't start it (cooldown, full hunger with a non-always-edible food): just switch back.
			refused = REFUSED_TICKS;
			Myriad.inventory().select(returnSlot);
			returnSlot = -1;
			giveBack();
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
		// Borrowed food stays in the hotbar while it's wanted (asked every tick).
		if (borrowed) Myriad.inventory().borrow(this, eatSlot, s -> false);
		mc.options.keyUse.setDown(true);
	}

	/**
	 * Low health: Health Foods first, the strongest (rarest: an enchanted golden apple's absorption and regeneration
	 * before a plain one's) of them first; then the most saturating of the rest.
	 */
	private double healthScore(ItemStack stack) {
		double food = foodScore(stack);
		if (food <= 0 || !healthFoods.contains(stack.getItem())) return food;
		return 100_000 + stack.getRarity().ordinal() * 10_000 + food;
	}

	/**
	 * Hunger: the most saturating food; with Save Health Foods, Health Foods only when nothing else is left, and then the
	 * cheapest (least rare) of them first. Every other food scores at least 1, these below it.
	 */
	private double hungerScore(ItemStack stack) {
		double food = foodScore(stack);
		if (food <= 0 || !saveHealthFoods.get() || !healthFoods.contains(stack.getItem())) return food;
		return (5 - stack.getRarity().ordinal()) / 10.0 + food / 10_000_000;
	}

	/** Saturation first (it's what keeps hunger up), nutrition to break ties; 0 for anything not to eat. */
	private double foodScore(ItemStack stack) {
		FoodProperties food = stack.get(DataComponents.FOOD);
		if (food == null || blacklist.contains(stack.getItem())) return 0;
		return 1 + food.saturation() * 100 + food.nutrition();
	}

	private void giveBack() {
		if (borrowed) Myriad.inventory().giveBack(this);
		borrowed = false;
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
		giveBack();
		eatSlot = returnSlot = -1;
	}
}
