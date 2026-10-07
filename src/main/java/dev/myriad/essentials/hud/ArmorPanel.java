package dev.myriad.essentials.hud;

import dev.myriad.api.render.Canvas;
import dev.myriad.api.render.FontFamily;
import dev.myriad.api.setting.BoolSetting;
import dev.myriad.api.setting.DoubleSetting;
import dev.myriad.api.setting.EnumSetting;
import dev.myriad.api.ui.Rect;
import dev.myriad.api.ui.hud.HudPanel;
import dev.myriad.api.ui.hud.ItemHud;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Your armour, helmet first, with durability as a bar and/or percentage. */
public final class ArmorPanel extends HudPanel {
	public enum Layout {
		HORIZONTAL, VERTICAL
	}

	private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

	private final EnumSetting<Layout> layout = sgGeneral.enumSetting("Layout", Layout.HORIZONTAL).build();
	private final EnumSetting<ItemHud.Durability> durability = sgGeneral.enumSetting("Durability", ItemHud.Durability.BAR).build();
	private final BoolSetting hideUndamaged = sgGeneral.bool("Hide Undamaged").description("No durability on armour that hasn't taken any.").defaultValue(true)
		.visible(() -> durability.get() != ItemHud.Durability.NONE).build();
	private final DoubleSetting textScale = sgGeneral.doubleSetting("Text Scale").description("Size of the percentage, relative to the HUD text.").defaultValue(0.8)
		.range(0.5, 1.5).decimals(2).visible(() -> ItemHud.showsPercent(durability.get())).build();
	private final BoolSetting skipEmpty = sgGeneral.bool("Skip Empty").description("Close up gaps left by empty slots.").build();
	private final BoolSetting avoidAir = sgGeneral.bool("Avoid Air Bar").description("Move up while the air bubbles are showing, like vanilla HUD rows.").defaultValue(true).build();
	private final BoolSetting shadow = sgGeneral.bool("Shadow").defaultValue(true).build();

	public ArmorPanel() {
		super("Armor", "\uf132");
	}

	private List<ItemStack> stacks() {
		List<ItemStack> list = new ArrayList<>(4);
		if (mc.player == null) {
			// Preview while editing the HUD from the title screen.
			list.add(Items.DIAMOND_HELMET.getDefaultInstance());
			list.add(Items.DIAMOND_CHESTPLATE.getDefaultInstance());
			list.add(Items.DIAMOND_LEGGINGS.getDefaultInstance());
			list.add(Items.DIAMOND_BOOTS.getDefaultInstance());
			return list;
		}
		for (EquipmentSlot slot : SLOTS) {
			ItemStack s = mc.player.getItemBySlot(slot);
			if (s.isEmpty() && skipEmpty.get()) continue;
			list.add(s);
		}
		return list;
	}

	private float slot() {
		return 18 * scale.getFloat();
	}

	private float percentHeight(Canvas c) {
		return ItemHud.showsPercent(durability.get()) ? c.textHeight(FontFamily.SANS, textSize(c)) : 0;
	}

	private float textSize(Canvas c) {
		return c.defaultFontSize() * textScale.getFloat() * scale.getFloat();
	}

	@Override
	public Rect preferredSize(Canvas c) {
		int n = Math.max(1, skipEmpty.get() ? stacks().size() : 4);
		float slot = slot(), ph = percentHeight(c);
		return layout.get() == Layout.HORIZONTAL ? new Rect(0, 0, slot * n, slot + ph) : new Rect(0, 0, slot, (slot + ph) * n);
	}

	@Override
	public void render(Canvas c, float w, float h, float mx, float my) {
		float slot = slot(), size = 16 * scale.getFloat(), pad = (slot - size) / 2, ph = percentHeight(c);
		// Vanilla draws the air bubbles one row above the hunger bar; step out of their way while they show.
		float yShift = avoidAir.get() && mc.player != null && mc.player.getAirSupply() < mc.player.getMaxAirSupply() ? -10 : 0;
		List<ItemStack> stacks = stacks();
		for (int i = 0; i < stacks.size(); i++) {
			ItemStack stack = stacks.get(i);
			if (stack.isEmpty()) continue;
			float x = layout.get() == Layout.HORIZONTAL ? i * slot : 0;
			float y = (layout.get() == Layout.HORIZONTAL ? 0 : i * (slot + ph)) + yShift;
			float ix = x + pad, iy = y + ph + pad;
			c.item(stack, ix, iy, size, false);
			if (!stack.isDamageableItem() || durability.get() == ItemHud.Durability.NONE) continue;
			if (hideUndamaged.get() && !stack.isDamaged()) continue;
			if (durability.get() == ItemHud.Durability.BAR || durability.get() == ItemHud.Durability.BOTH) ItemHud.bar(c, stack, ix, iy, size);
			if (ItemHud.showsPercent(durability.get())) ItemHud.percentText(c, stack, ix, y, size, textSize(c), shadow.get());
		}
	}
}
