package dev.myriad.essentials.modules.player;

import dev.myriad.api.Myriad;
import dev.myriad.api.event.Subscribe;
import dev.myriad.api.event.events.MouseButtonEvent;
import dev.myriad.api.event.events.TickEvent;
import dev.myriad.api.module.Categories;
import dev.myriad.api.module.Module;
import dev.myriad.api.setting.BoolSetting;
import dev.myriad.api.setting.EnumSetting;
import dev.myriad.api.setting.IntSetting;
import dev.myriad.api.util.Interactions;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;

/**
 * Middle click does something useful depending on what you're pointing at: in the air, at an entity, at a block, or
 * while flying with an elytra. Each can add or remove a friend, throw experience bottles (for as long as you hold the
 * button), launch a rocket or throw a pearl. Items are used with a silent swap, so your selected slot doesn't change,
 * and vanilla's pick block is skipped when an action runs.
 */
public class MiddleClick extends Module {
	public enum Action {
		NONE, FRIEND, XP, ROCKET, PEARL
	}

	private final EnumSetting<Action> air = sgGeneral.enumSetting("Air", Action.PEARL).description("Pointing at nothing.").build();
	private final EnumSetting<Action> entity = sgGeneral.enumSetting("Entity", Action.FRIEND).description("Pointing at an entity (Friend only works on players).").build();
	private final EnumSetting<Action> block = sgGeneral.enumSetting("Block", Action.XP).description("Pointing at a block.").build();
	private final EnumSetting<Action> flying = sgGeneral.enumSetting("Flying", Action.ROCKET).description("Flying with an elytra, whatever you point at.").build();
	private final IntSetting xpDelay = sgGeneral.intSetting("XP Delay").description("Ticks between experience bottles while held.").defaultValue(0).range(0, 10).build();
	private final BoolSetting fromInventory = sgGeneral.bool("From Inventory").description("Use the item from your inventory when it isn't in the hotbar (it's put back after).").defaultValue(true).build();
	private final BoolSetting whileUsing = sgGeneral.bool("While Using").description("Allow actions while eating or blocking.").build();

	private boolean held;
	private int xpWait;
	/** An item to use once it has been borrowed from the inventory (the click waits a tick while you move). */
	private Item pending;
	private int pendingTicks;
	private static final int PENDING_TICKS = 5;

	public MiddleClick() {
		super(Categories.PLAYER, "Middle Click", "Friends, experience, rockets or pearls on the middle mouse button.");
	}

	@Override
	protected void onDisable() {
		held = false;
		pending = null;
	}

	@Subscribe
	private void onMouse(MouseButtonEvent e) {
		if (e.button() != GLFW.GLFW_MOUSE_BUTTON_MIDDLE || e.inScreen()) return;
		if (e.action() == GLFW.GLFW_RELEASE) {
			held = false;
			return;
		}
		if (!e.isPress() || !inGame()) return;
		Action action = current();
		if (action == Action.NONE) return;
		// Run the action instead of vanilla's pick block.
		e.cancel();
		if (mc.player.isUsingItem() && !whileUsing.get()) return;
		held = true;
		xpWait = 0;
		run(action, true);
	}

	@Subscribe
	private void onTick(TickEvent.Pre e) {
		if (pending != null && inGame()) {
			Item item = pending;
			pending = null;
			if (--pendingTicks >= 0) use(item);
		}
		if (!held || !inGame()) return;
		if (mc.gui.screen() != null || GLFW.glfwGetMouseButton(mc.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_MIDDLE) != GLFW.GLFW_PRESS) {
			held = false;
			return;
		}
		if (mc.player.isUsingItem() && !whileUsing.get()) return;
		// Only experience repeats while the button is held.
		Action action = current();
		if (action == Action.XP && --xpWait < 0) run(action, false);
	}

	/** The action for what you're pointing at right now. */
	private Action current() {
		if (mc.player.isFallFlying()) return flying.get();
		HitResult hit = mc.hitResult;
		if (hit == null || hit.getType() == HitResult.Type.MISS) return air.get();
		if (hit.getType() == HitResult.Type.ENTITY) {
			Action a = entity.get();
			return a == Action.FRIEND && !(((EntityHitResult) hit).getEntity() instanceof Player) ? Action.NONE : a;
		}
		return block.get();
	}

	private void run(Action action, boolean press) {
		switch (action) {
			case FRIEND -> {
				if (press && mc.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof Player p) toggleFriend(p);
			}
			case XP -> {
				use(Items.EXPERIENCE_BOTTLE);
				xpWait = xpDelay.get();
			}
			case ROCKET -> use(Items.FIREWORK_ROCKET);
			case PEARL -> use(Items.ENDER_PEARL);
			case NONE -> {
			}
		}
	}

	private void toggleFriend(Player p) {
		String name = p.getGameProfile().name();
		if (Myriad.friends().isFriend(name)) {
			Myriad.friends().remove(name);
			info("Removed " + name + " from friends");
		} else {
			Myriad.friends().add(name);
			info("Added " + name + " to friends");
		}
	}

	/**
	 * Uses {@code item} from the off hand or the hotbar, without changing the visible slot; or borrowed from the
	 * inventory, which puts it back after (while you move, the click waits a tick, so the use does too).
	 */
	private void use(Item item) {
		if (mc.player.getOffhandItem().is(item)) {
			Interactions.useItem(InteractionHand.OFF_HAND);
			Interactions.swing(InteractionHand.OFF_HAND);
			return;
		}
		int slot = Myriad.inventory().findInHotbar(s -> s.is(item));
		if (slot < 0 && fromInventory.get()) {
			int from = Myriad.inventory().findInInventory(s -> s.is(item));
			if (from < 0) return;
			slot = Myriad.inventory().borrow(this, from, null);
			if (slot < 0) {
				if (pending == null && pendingTicks <= 0) pendingTicks = PENDING_TICKS;
				pending = item;
				return;
			}
		}
		if (slot < 0) return;
		pendingTicks = 0;
		// Keeps it if it's borrowed (it goes back once you stop using it).
		Myriad.inventory().borrow(this, slot, null);
		Myriad.inventory().silentSwap(slot, () -> {
			Interactions.useItem(InteractionHand.MAIN_HAND);
			Interactions.swing(InteractionHand.MAIN_HAND);
		});
	}
}
