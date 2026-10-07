package dev.myriad.essentials.modules.render;

import dev.myriad.api.Myriad;
import dev.myriad.api.event.Subscribe;
import dev.myriad.api.event.events.ContainerScreenEvent;
import dev.myriad.api.event.events.ItemTooltipEvent;
import dev.myriad.api.event.events.TickEvent;
import dev.myriad.api.event.events.WorldEvent;
import dev.myriad.api.module.Categories;
import dev.myriad.api.module.Module;
import dev.myriad.api.render.Canvas;
import dev.myriad.api.render.FontFamily;
import dev.myriad.api.setting.BoolSetting;
import dev.myriad.api.setting.KeybindSetting;
import dev.myriad.api.setting.SettingGroup;
import dev.myriad.api.ui.ThemeSettings;
import dev.myriad.api.util.ColorUtil;
import dev.myriad.api.util.ItemInfo;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.jetbrains.annotations.Nullable;

/**
 * Better inventory tooltips:
 * <ul>
 * <li>Containers: hovering a shulker box shows its 27 slots in a themed panel instead of the vanilla tooltip, and so
 * does an ender chest, with what your ender chest held when you last opened it. Hold the lock key to pin the panel
 * so you can hover its items.</li>
 * <li>Maps: hovering a filled map shows the map itself, as far as the server has sent it.</li>
 * <li>Icons: shulker boxes in slots and in the hotbar show their most common item.</li>
 * <li>Durability and food: exact durability, and the hunger and saturation food restores.</li>
 * </ul>
 */
public class Tooltips extends Module {
	private static final int COLS = 9, ROWS = 3, SLOT = 18, WIDTH = COLS * SLOT + 14, HEADER = 16, FOOTER = 7;
	private static final int HEIGHT = HEADER + ROWS * SLOT + FOOTER;
	/** Maps are 128 pixels; drawn at this scale inside the panel. */
	private static final float MAP_SCALE = 0.75f;
	private static final int MAP_SIZE = (int) (128 * MAP_SCALE), MAP_WIDTH = MAP_SIZE + 14, MAP_HEIGHT = HEADER + MAP_SIZE + FOOTER;

	private final MapRenderState mapState = new MapRenderState();

	private final SettingGroup sgContainers = settings.group("Containers");
	private final BoolSetting shulkers = sgContainers.bool("Shulker Boxes").description("Preview shulker box contents.").defaultValue(true).build();
	private final BoolSetting enderChest = sgContainers.bool("Ender Chest").description("Preview your ender chest on ender chest items (once you've opened it).").defaultValue(true).build();
	private final BoolSetting maps = sgContainers.bool("Maps").description("Show what a filled map shows (once its data has reached you).").defaultValue(true).build();
	private final BoolSetting emptyPreview = sgContainers.bool("Empty Boxes").description("Preview empty shulker boxes too.").build();
	private final KeybindSetting lockKey = sgContainers.keybind("Lock Preview").description("Hold to pin the preview so you can hover its items.").build();
	private final BoolSetting slotIcons = sgContainers.bool("Slot Icons").description("Show the most common item on shulker boxes in inventories.").defaultValue(true).build();
	private final BoolSetting hotbarIcons = sgContainers.bool("Hotbar Icons").description("Show the most common item on shulker boxes in your hotbar.").defaultValue(true).build();

	private final SettingGroup sgInfo = settings.group("Info");
	private final BoolSetting durability = sgInfo.bool("Durability").description("Durability left, out of the maximum.").defaultValue(true).build();
	private final BoolSetting food = sgInfo.bool("Food").description("Hunger and saturation restored.").defaultValue(true).build();

	/** The ender chest's 27 slots when you last had it open on this server, or null. */
	private List<ItemStack> enderContents;

	private ItemStack locked;
	private String lockedTitle;
	private List<ItemStack> lockedContents;
	private int lockedX, lockedY;

	public Tooltips() {
		super(Categories.RENDER, "Tooltips", "Container and map previews, durability and food values in tooltips.");
	}

	@Override
	protected void onDisable() {
		locked = null;
		lockedContents = null;
	}

	@Subscribe
	private void onWorld(WorldEvent.Leave e) {
		enderContents = null;
	}

	/** While your ender chest is open, remember what's in it. */
	@Subscribe
	private void onTick(TickEvent.Post e) {
		if (!(mc.gui.screen() instanceof ContainerScreen screen) || !(screen.getMenu() instanceof ChestMenu menu)) return;
		if (!(screen.getTitle().getContents() instanceof TranslatableContents t) || !t.getKey().equals("container.enderchest")) return;
		List<ItemStack> items = new ArrayList<>(27);
		for (int i = 0; i < Math.min(27, menu.getContainer().getContainerSize()); i++) items.add(menu.getContainer().getItem(i).copy());
		enderContents = items;
	}

	@Subscribe
	private void onTooltip(ItemTooltipEvent e) {
		ItemStack stack = e.stack();
		if (durability.get() && stack.isDamageableItem()) {
			int left = stack.getMaxDamage() - stack.getDamageValue();
			e.lines().add(Component.literal("Durability " + left + " / " + stack.getMaxDamage()).withStyle(ChatFormatting.GRAY));
		}
		FoodProperties f = stack.get(DataComponents.FOOD);
		if (food.get() && f != null) {
			e.lines().add(Component.literal(String.format("Hunger +%d  Saturation +%.1f", f.nutrition(), f.saturation())).withStyle(ChatFormatting.GRAY));
		}
	}

	// ---- container previews -----------------------------------------------------------------------------------------

	public static boolean isShulker(ItemStack stack) {
		return stack != null && ItemInfo.isShulkerBox(stack);
	}

	/** The box's 27 slots, empty ones included, so the preview grid lines up. */
	public static List<ItemStack> contents(ItemStack stack) {
		List<ItemStack> items = new ArrayList<>(27);
		for (ItemStack s : ItemInfo.contents(stack)) if (items.size() < 27) items.add(s);
		while (items.size() < 27) items.add(ItemStack.EMPTY);
		return items;
	}

	private static boolean hasItems(List<ItemStack> items) {
		for (ItemStack s : items) if (!s.isEmpty()) return true;
		return false;
	}

	/** What to preview for {@code stack}: its contents, or null for no preview. */
	private @Nullable List<ItemStack> previewOf(ItemStack stack) {
		if (shulkers.get() && isShulker(stack)) {
			List<ItemStack> items = contents(stack);
			return hasItems(items) || emptyPreview.get() ? items : null;
		}
		if (enderChest.get() && stack.is(Items.ENDER_CHEST)) return enderContents;
		return null;
	}

	/** The item the box holds the most of, or null. */
	public static @Nullable ItemStack mostCommon(ItemStack shulker) {
		Map<Item, Integer> counts = new HashMap<>();
		Map<Item, ItemStack> first = new HashMap<>();
		for (ItemStack s : contents(shulker)) {
			if (s.isEmpty()) continue;
			counts.merge(s.getItem(), s.getCount(), Integer::sum);
			first.putIfAbsent(s.getItem(), s);
		}
		return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(e -> first.get(e.getKey())).orElse(null);
	}

	public boolean hotbarIcons() {
		return hotbarIcons.get();
	}

	/** Draws the small icon over a shulker box at (x, y). */
	public static void drawIcon(GuiGraphicsExtractor ctx, ItemStack shulker, int x, int y) {
		ItemStack top = mostCommon(shulker);
		if (top == null) return;
		var pose = ctx.pose();
		pose.pushMatrix();
		pose.translate(x + 6, y + 6);
		pose.scale(0.6f, 0.6f);
		ctx.item(top, 0, 0);
		pose.popMatrix();
	}

	@Subscribe
	private void onTooltip(ContainerScreenEvent.Tooltip e) {
		if (renderTooltip(e.graphics(), e.hovered(), e.mouseX(), e.mouseY())) e.cancel();
	}

	@Subscribe
	private void onSlot(ContainerScreenEvent.SlotDrawn e) {
		Slot slot = e.slot();
		if (slotIcons.get() && slot.hasItem() && isShulker(slot.getItem())) drawIcon(e.graphics(), slot.getItem(), slot.x, slot.y);
	}

	/** In place of the inventory tooltip. Returns true if a preview replaced it. */
	private boolean renderTooltip(GuiGraphicsExtractor ctx, @Nullable Slot hovered, int mouseX, int mouseY) {
		ItemStack hoveredStack = hovered != null && hovered.hasItem() ? hovered.getItem() : null;
		if (lockKey.get().isPressed()) {
			if (locked == null && hoveredStack != null) {
				List<ItemStack> items = previewOf(hoveredStack);
				if (items != null) {
					locked = hoveredStack.copy();
					lockedTitle = hoveredStack.getHoverName().getString();
					lockedContents = items;
					lockedX = mouseX;
					lockedY = mouseY;
				}
			}
		} else {
			locked = null;
			lockedContents = null;
		}

		if (locked == null && hoveredStack != null && maps.get() && renderMap(ctx, hoveredStack, mouseX, mouseY)) return true;

		String title;
		List<ItemStack> items;
		int ax, ay;
		if (locked != null) {
			title = lockedTitle;
			items = lockedContents;
			ax = lockedX;
			ay = lockedY;
		} else {
			if (hoveredStack == null) return false;
			items = previewOf(hoveredStack);
			if (items == null) return false;
			title = hoveredStack.getHoverName().getString();
			ax = mouseX;
			ay = mouseY;
		}

		int[] p = position(ax, ay);
		ItemStack under = hoveredItem(items, p[0], p[1], mouseX, mouseY);
		// A layer of its own above the inventory, like a vanilla tooltip.
		ctx.nextStratum();
		Myriad.ui().draw(ctx, c -> drawPanel(c, title, items, p[0], p[1]));
		if (under != null) ctx.setTooltipForNextFrame(mc.font, under, mouseX, mouseY);
		return true;
	}

	/** A filled map's picture in place of its tooltip; false if it isn't a map or its data hasn't arrived. */
	private boolean renderMap(GuiGraphicsExtractor ctx, ItemStack stack, int mouseX, int mouseY) {
		MapId id = stack.get(DataComponents.MAP_ID);
		MapItemSavedData data = id == null || mc.level == null ? null : MapItem.getSavedData(id, mc.level);
		if (data == null) return false;
		int[] p = position(mouseX, mouseY, MAP_WIDTH, MAP_HEIGHT);
		String title = stack.getHoverName().getString();
		ctx.nextStratum();
		Myriad.ui().draw(ctx, c -> drawFrame(c, title, p[0], p[1], MAP_WIDTH, MAP_HEIGHT));
		// The map on a layer above the panel, as the cartography table draws it.
		ctx.nextStratum();
		mc.getMapRenderer().extractRenderState(id, data, mapState);
		ctx.pose().pushMatrix();
		ctx.pose().translate(p[0] + 7, p[1] + HEADER);
		ctx.pose().scale(MAP_SCALE, MAP_SCALE);
		ctx.map(mapState);
		ctx.pose().popMatrix();
		return true;
	}

	private static int[] position(int mouseX, int mouseY) {
		return position(mouseX, mouseY, WIDTH, HEIGHT);
	}

	private static int[] position(int mouseX, int mouseY, int width, int height) {
		int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
		int x = Math.max(4, Math.min(mouseX + 12, sw - width - 4));
		int y = Math.max(4, Math.min(mouseY - 6, sh - height - 4));
		return new int[]{x, y};
	}

	private static @Nullable ItemStack hoveredItem(List<ItemStack> items, int px, int py, int mx, int my) {
		int col = Math.floorDiv(mx - (px + 7), SLOT), row = Math.floorDiv(my - (py + HEADER), SLOT);
		if (col < 0 || col >= COLS || row < 0 || row >= ROWS) return null;
		ItemStack s = items.get(row * COLS + col);
		return s.isEmpty() ? null : s;
	}

	/** A themed panel with a title, like a window. */
	private static void drawFrame(Canvas c, String name, float x, float y, int width, int height) {
		ThemeSettings theme = Myriad.ui().theme();
		float r = theme.rounding.get();
		if (theme.shadow.get()) c.shadow(x, y, width, height, r, theme.shadowRange.get(), theme.shadowColor.argb());
		c.backdrop(x, y, width, height, r, 1);
		c.roundRect(x, y, width, height, r, ColorUtil.withAlpha(theme.windowBackground.argb(), Math.max(200, ColorUtil.alpha(theme.windowBackground.argb()))));
		c.gradientOutline(x, y, width, height, r, Math.max(1, theme.borderSize.get()), theme.activeBorderFrom.argb(), theme.activeBorderTo.argb(), theme.borderAngle.get().floatValue());
		String title = c.ellipsize(FontFamily.SANS_BOLD, c.defaultFontSize(), name, width - 14);
		c.text(FontFamily.SANS_BOLD, c.defaultFontSize(), title, x + 7, y + (HEADER - c.textHeight()) / 2 + 1, theme.text.argb());
	}

	private static void drawPanel(Canvas c, String name, List<ItemStack> items, float x, float y) {
		ThemeSettings theme = Myriad.ui().theme();
		drawFrame(c, name, x, y, WIDTH, HEIGHT);
		float gx = x + 7, gy = y + HEADER;
		int cell = ColorUtil.withAlpha(theme.surface.argb(), 110);
		for (int i = 0; i < 27; i++) {
			float sx = gx + (i % COLS) * SLOT, sy = gy + (i / COLS) * SLOT;
			c.roundRect(sx + 0.5f, sy + 0.5f, SLOT - 1, SLOT - 1, 2, cell);
		}
		for (int i = 0; i < Math.min(27, items.size()); i++) {
			ItemStack s = items.get(i);
			if (!s.isEmpty()) c.item(s, gx + (i % COLS) * SLOT + 1, gy + (i / COLS) * SLOT + 1, 16);
		}
	}
}
