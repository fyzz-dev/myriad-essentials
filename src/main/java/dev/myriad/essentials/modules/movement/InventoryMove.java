package dev.myriad.essentials.modules.movement;

import com.mojang.blaze3d.platform.InputConstants;
import dev.myriad.api.Myriad;
import dev.myriad.api.event.Priority;
import dev.myriad.api.event.Subscribe;
import dev.myriad.api.event.events.InputEvent;
import dev.myriad.api.event.events.TickEvent;
import dev.myriad.api.module.Categories;
import dev.myriad.api.module.Module;
import dev.myriad.api.setting.BoolSetting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractCommandBlockEditScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

/**
 * Keeps the movement keys working while a screen is open (your inventory, a chest, a furnace), except where you type:
 * chat, signs, anvils, books, command blocks, any focused text box, and the Myriad menu.
 */
public class InventoryMove extends Module {
	private final BoolSetting sneak = sgGeneral.bool("Sneak").description("Also sneak in screens. Off, shift-clicking items doesn't make you sneak.").build();
	private final BoolSetting arrowLook = sgGeneral.bool("Arrow Keys Look").description("Turn with the arrow keys while a screen is open.").build();

	/** Degrees per tick the arrow keys turn. */
	private static final float LOOK_SPEED = 6;

	public InventoryMove() {
		super(Categories.MOVEMENT, "Inventory Move", "Walk, jump and sprint with your inventory or a container open.");
	}

	private boolean active() {
		Screen screen = mc.gui.screen();
		if (screen == null || Myriad.ui().isOpen()) return false;
		if (screen instanceof ChatScreen || screen instanceof AbstractSignEditScreen || screen instanceof AnvilScreen
			|| screen instanceof BookEditScreen || screen instanceof AbstractCommandBlockEditScreen) return false;
		return !(screen.getFocused() instanceof EditBox);
	}

	/** Before other input handlers (and the rotation move fix), so they see these keys. */
	@Subscribe(priority = Priority.HIGH)
	private void onInput(InputEvent e) {
		if (!active()) return;
		e.forward = down(mc.options.keyUp);
		e.backward = down(mc.options.keyDown);
		e.left = down(mc.options.keyLeft);
		e.right = down(mc.options.keyRight);
		e.jump = down(mc.options.keyJump);
		e.sprint = down(mc.options.keySprint);
		if (sneak.get()) e.sneak = down(mc.options.keyShift);
	}

	@Subscribe
	private void onTick(TickEvent.Pre e) {
		if (!arrowLook.get() || !inGame() || !active()) return;
		long window = mc.getWindow().handle();
		float yaw = (key(window, GLFW.GLFW_KEY_RIGHT) - key(window, GLFW.GLFW_KEY_LEFT)) * LOOK_SPEED;
		float pitch = (key(window, GLFW.GLFW_KEY_DOWN) - key(window, GLFW.GLFW_KEY_UP)) * LOOK_SPEED;
		if (yaw == 0 && pitch == 0) return;
		mc.player.setYRot(mc.player.getYRot() + yaw);
		mc.player.setXRot(Mth.clamp(mc.player.getXRot() + pitch, -90, 90));
	}

	private static int key(long window, int key) {
		return GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS ? 1 : 0;
	}

	/** Whether the key or mouse button bound to {@code mapping} is held, read straight from the window. */
	private boolean down(KeyMapping mapping) {
		if (mapping.isUnbound()) return false;
		InputConstants.Key key = InputConstants.getKey(mapping.saveString());
		if (key.getType() == InputConstants.Type.MOUSE) return GLFW.glfwGetMouseButton(mc.getWindow().handle(), key.getValue()) == GLFW.GLFW_PRESS;
		return InputConstants.isKeyDown(mc.getWindow(), key.getValue());
	}
}
