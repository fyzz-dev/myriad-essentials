package dev.myriad.essentials.modules.render;

import dev.myriad.api.Myriad;
import dev.myriad.api.event.Subscribe;
import dev.myriad.api.event.events.EntityRenderEvent;
import dev.myriad.api.event.events.Render2DEvent;
import dev.myriad.api.module.Categories;
import dev.myriad.api.module.Module;
import dev.myriad.api.render.Canvas;
import dev.myriad.api.render.FontFamily;
import dev.myriad.api.render.PlayerHeads;
import dev.myriad.api.render.Projection;
import dev.myriad.api.render.WorldLabel;
import dev.myriad.api.setting.BoolSetting;
import dev.myriad.api.setting.ColorSetting;
import dev.myriad.api.setting.DoubleSetting;
import dev.myriad.api.setting.SettingColor;
import dev.myriad.api.setting.SettingGroup;
import dev.myriad.api.util.ColorUtil;
import dev.myriad.api.util.Entities;
import dev.myriad.api.util.ItemInfo;
import dev.myriad.essentials.util.PopColors;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.Holder;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Readable name tags, drawn through walls:
 * <ul>
 * <li>Players: head, name, health, ping, totem pops, distance and gamemode, with their armour and held items (and
 * enchantments) above.</li>
 * <li>Mobs: name and health.</li>
 * <li>Items: what's on the ground and how many, grouped when they lie together.</li>
 * <li>Pearls: who threw each ender pearl.</li>
 * </ul>
 * Vanilla's labels are hidden for whatever this draws.
 */
public class Nametags extends Module {
	private static final float PAD_X = 3, PAD_Y = 2, GAP = 3, ITEM = 12, ITEM_GAP = 14;

	private final DoubleSetting scale = sgGeneral.doubleSetting("Scale").defaultValue(1.1).range(0.3, 4).decimals(1).build();
	private final DoubleSetting range = sgGeneral.doubleSetting("Range").description("0 = no limit.").defaultValue(0).range(0, 256).decimals(0).build();
	private final BoolSetting background = sgGeneral.bool("Background").defaultValue(true).build();
	private final ColorSetting fill = sgGeneral.color("Fill").defaultValue(0x64000000).visible(background::get).build();
	private final ColorSetting line = sgGeneral.color("Line").defaultValue(SettingColor.role(SettingColor.Mode.ACCENT, 0xC0)).visible(background::get).build();

	private final SettingGroup sgPlayers = settings.group("Players");
	private final BoolSetting players = sgPlayers.bool("Players").defaultValue(true).build();
	private final BoolSetting self = sgPlayers.bool("Self").description("Your own tag in third person.").defaultValue(true).visible(players::get).build();
	private final BoolSetting head = sgPlayers.bool("Head").description("The player's face before the name.").defaultValue(true).visible(players::get).build();
	private final BoolSetting health = sgPlayers.bool("Health").defaultValue(true).visible(players::get).build();
	private final BoolSetting ping = sgPlayers.bool("Ping").defaultValue(true).visible(players::get).build();
	private final BoolSetting totemPops = sgPlayers.bool("Totem Pops").defaultValue(true).visible(players::get).build();
	private final BoolSetting distance = sgPlayers.bool("Distance").visible(players::get).build();
	private final BoolSetting gamemode = sgPlayers.bool("Gamemode").visible(players::get).build();
	private final DoubleSetting offset = sgPlayers.doubleSetting("Offset").description("Height above the head, in blocks.").defaultValue(0.4).range(-2, 3).decimals(1).visible(players::get).build();
	private final ColorSetting nameColor = sgPlayers.color("Name").defaultValue(SettingColor.role(SettingColor.Mode.TEXT)).visible(players::get).build();
	private final ColorSetting friendColor = sgPlayers.color("Friends").defaultValue(SettingColor.role(SettingColor.Mode.CYAN)).visible(players::get).build();

	private final SettingGroup sgGear = settings.group("Gear");
	private final BoolSetting armor = sgGear.bool("Armor").defaultValue(true).build();
	private final BoolSetting heldItems = sgGear.bool("Held Items").defaultValue(true).build();
	private final BoolSetting heldItemName = sgGear.bool("Held Item Name").defaultValue(true).visible(heldItems::get).build();
	private final BoolSetting durability = sgGear.bool("Durability").description("Durability percentage under each piece.").build();
	private final BoolSetting enchants = sgGear.bool("Enchantments").description("Short enchantment names over each item.").build();
	private final ColorSetting useProgress = sgGear.color("Use Progress").description("Bar over the item being eaten or used.").defaultValue(0x5000C800).visible(heldItems::get).build();

	private final SettingGroup sgOthers = settings.group("Others");
	private final BoolSetting mobs = sgOthers.bool("Mobs").description("Name and health over mobs.").build();
	private final BoolSetting items = sgOthers.bool("Items").description("Names and counts over dropped items.").defaultValue(true).build();
	private final BoolSetting groupItems = sgOthers.bool("Group Items").description("One tag per item type for items lying together.").defaultValue(true).visible(items::get).build();
	private final DoubleSetting itemRange = sgOthers.doubleSetting("Item Range").defaultValue(32).range(1, 128).decimals(0).visible(items::get).build();
	private final BoolSetting pearls = sgOthers.bool("Pearls").description("Who threw each ender pearl.").defaultValue(true).build();

	private record Segment(String text, int color) {
	}

	public Nametags() {
		super(Categories.RENDER, "Nametags", "Readable name tags for players, mobs, items and pearls.");
	}

	/** Whether this draws {@code entity}'s label, so vanilla's should be hidden. */
	/** Vanilla's tag stays off whatever this module draws its own for. */
	@Subscribe
	private void onVanillaTag(EntityRenderEvent.Nametag e) {
		Entity entity = e.entity();
		boolean ours = entity instanceof Player ? players.get() : entity instanceof ItemEntity ? items.get()
			: entity instanceof LivingEntity && !(entity instanceof ArmorStand) && mobs.get();
		if (ours) e.cancel();
	}

	@Subscribe(inGame = true)
	private void onRender(Render2DEvent e) {
		Canvas c = e.canvas();
		Vec3 cam = Projection.camera();
		List<Entity> entities = new ArrayList<>();
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!entity.isAlive() || (range.get() > 0 && entity.distanceToSqr(cam) > range.get() * range.get())) continue;
			if (entity instanceof Player p ? players.get() && (p != mc.player || (self.get() && !mc.options.getCameraType().isFirstPerson()))
				: entity instanceof ThrownEnderpearl ? pearls.get()
				: entity instanceof LivingEntity && !(entity instanceof ArmorStand) && mobs.get()) {
				entities.add(entity);
			}
		}
		// Far tags first so near ones draw on top.
		entities.sort(Comparator.comparingDouble(en -> -en.distanceToSqr(cam)));
		for (Entity entity : entities) {
			if (entity instanceof Player p) drawPlayer(c, p, e.tickDelta());
			else if (entity instanceof ThrownEnderpearl pearl) drawPearl(c, pearl, e.tickDelta());
			else drawMob(c, (LivingEntity) entity, e.tickDelta());
		}
		if (items.get()) drawItems(c, e.tickDelta());
	}

	// ---- players ----------------------------------------------------------------------------------------------------

	private void drawPlayer(Canvas c, Player p, float tickDelta) {
		AABB box = Entities.lerpedBox(p, tickDelta);
		Vec3 at = new Vec3(box.getCenter().x, box.maxY + offset.get(), box.getCenter().z);
		Vec3 s = Projection.toScreen(at);
		if (s == null || !Projection.onScreen(s, 100)) return;
		float k = scale.getFloat() * WorldLabel.distanceScale(at);
		float size = c.defaultFontSize() * k;
		float th = c.textHeight(FontFamily.SANS, size);

		List<Segment> segs = segments(p);
		float width = 0;
		for (int i = 0; i < segs.size(); i++) width += c.textWidth(FontFamily.SANS, size, segs.get(i).text) + (i > 0 ? GAP * k : 0);
		boolean face = head.get() && p instanceof AbstractClientPlayer;
		if (face) width += th + GAP * k;
		float w = width + PAD_X * 2 * k, h = th + PAD_Y * 2 * k;
		float x = (float) s.x - w / 2, y = (float) s.y - h;
		drawBackground(c, x, y, w, h);
		float cx = x + PAD_X * k, ty = y + PAD_Y * k;
		int nameIndex = gamemode.get() ? 1 : 0;
		for (int i = 0; i < segs.size(); i++) {
			if (i == nameIndex && face) {
				PlayerHeads.draw(c, p, cx, ty, th);
				cx += th + GAP * k;
			}
			Segment seg = segs.get(i);
			c.text(FontFamily.SANS, size, seg.text, cx + 0.6f, ty + 0.6f, 0x99000000);
			cx += c.text(FontFamily.SANS, size, seg.text, cx, ty, seg.color) + GAP * k;
		}
		if (armor.get() || heldItems.get()) drawGear(c, p, (float) s.x, y - 1, k, size);
	}

	private void drawBackground(Canvas c, float x, float y, float w, float h) {
		if (!background.get()) return;
		float r = Math.min(Myriad.ui().theme().rounding.get(), h / 2);
		c.roundRect(x, y, w, h, r, fill.argb());
		if ((line.argb() >>> 24) != 0) c.outline(x, y, w, h, r, 1, line.argb());
	}

	/** The optional gamemode, the name, then the stats. */
	private List<Segment> segments(Player p) {
		List<Segment> segs = new ArrayList<>();
		PlayerInfo entry = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(p.getUUID());
		if (gamemode.get()) segs.add(new Segment(p.isCreative() ? "[C]" : p.isSpectator() ? "[SP]" : "[S]", 0xFFAAAAAA));
		segs.add(new Segment(p.getGameProfile().name(), Entities.isFriend(p) ? friendColor.argb() : nameColor.argb()));
		if (health.get()) segs.add(healthSegment(p));
		if (ping.get()) segs.add(new Segment((entry == null ? 0 : entry.getLatency()) + "ms", 0xFFCCCCCC));
		if (distance.get() && p != mc.player) segs.add(new Segment(String.format("%.0fm", mc.player.distanceTo(p)), 0xFFCCCCCC));
		if (totemPops.get()) {
			int pops = Myriad.server().totemPops(p);
			if (pops > 0) segs.add(new Segment("-" + pops, PopColors.of(pops)));
		}
		return segs;
	}

	private static Segment healthSegment(LivingEntity e) {
		float hp = e.getHealth() + e.getAbsorptionAmount();
		return new Segment(String.format("%.1f", hp), ColorUtil.lerp(0xFFFF5555, 0xFF55FF55, Math.clamp(hp / e.getMaxHealth(), 0f, 1f)));
	}

	private void drawGear(Canvas c, Player p, float centerX, float bottom, float k, float textSize) {
		List<ItemStack> stacks = new ArrayList<>(6);
		if (heldItems.get()) stacks.add(p.getOffhandItem());
		if (armor.get()) {
			stacks.add(p.getItemBySlot(EquipmentSlot.HEAD));
			stacks.add(p.getItemBySlot(EquipmentSlot.CHEST));
			stacks.add(p.getItemBySlot(EquipmentSlot.LEGS));
			stacks.add(p.getItemBySlot(EquipmentSlot.FEET));
		}
		if (heldItems.get()) stacks.add(p.getMainHandItem());
		if (stacks.stream().allMatch(ItemStack::isEmpty)) return;

		float item = ITEM * k, step = ITEM_GAP * k;
		float small = textSize * 0.6f, smallH = c.textHeight(FontFamily.SANS, small);
		float x0 = centerX - stacks.size() * step / 2, y = bottom - item - (durability.get() ? smallH : 0);
		float top = y;
		for (int i = 0; i < stacks.size(); i++) {
			ItemStack st = stacks.get(i);
			if (st.isEmpty()) continue;
			float ix = x0 + i * step + (step - item) / 2;
			boolean main = heldItems.get() && i == stacks.size() - 1, off = heldItems.get() && i == 0;
			if (p.isUsingItem() && ((main && p.getUsedItemHand() == InteractionHand.MAIN_HAND) || (off && p.getUsedItemHand() == InteractionHand.OFF_HAND))) {
				int max = p.getUseItem().getUseDuration(p);
				float prog = max <= 0 ? 0 : Math.clamp((max - p.getUseItemRemainingTicks()) / (float) max, 0f, 1f);
				c.rect(ix, y, item * prog, item, useProgress.argb());
			}
			c.item(st, ix, y, item);
			if (durability.get() && st.isDamageableItem()) {
				float pct = ItemInfo.durabilityFraction(st);
				String t = Math.round(pct * 100) + "%";
				c.text(FontFamily.SANS, small, t, ix + (item - c.textWidth(FontFamily.SANS, small, t)) / 2, y + item, ColorUtil.lerp(0xFFFF5555, 0xFF55FF55, pct));
			}
			if (enchants.get()) {
				// Stacked upwards from the item: one short name per line.
				float ey = y;
				for (String ench : enchantNames(st)) {
					ey -= smallH;
					c.text(FontFamily.SANS, small, ench, ix + (item - c.textWidth(FontFamily.SANS, small, ench)) / 2, ey, 0xFFD8A8FF);
				}
				top = Math.min(top, ey);
			}
		}
		if (heldItems.get() && heldItemName.get() && !p.getMainHandItem().isEmpty()) {
			String name = p.getMainHandItem().getHoverName().getString();
			float ts = textSize * 0.75f;
			float tw = c.textWidth(FontFamily.SANS, ts, name);
			c.text(FontFamily.SANS, ts, name, centerX - tw / 2, top - c.textHeight(FontFamily.SANS, ts) - 1, 0xFFFFFFFF);
		}
	}

	/** "Pro4", "Unb3", … for the item's first four enchantments. */
	private static List<String> enchantNames(ItemStack stack) {
		List<String> out = new ArrayList<>();
		for (Object2IntMap.Entry<Holder<Enchantment>> e : stack.getEnchantments().entrySet()) {
			if (out.size() == 4) break;
			String path = e.getKey().unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
			String shortName = path.length() <= 3 ? path : path.substring(0, 3);
			shortName = shortName.substring(0, 1).toUpperCase(Locale.ROOT) + shortName.substring(1);
			out.add(e.getIntValue() > 1 ? shortName + e.getIntValue() : shortName);
		}
		return out;
	}

	// ---- mobs, pearls and items -------------------------------------------------------------------------------------

	private void drawMob(Canvas c, LivingEntity mob, float tickDelta) {
		AABB box = Entities.lerpedBox(mob, tickDelta);
		Vec3 at = new Vec3(box.getCenter().x, box.maxY + 0.3, box.getCenter().z);
		Segment hp = healthSegment(mob);
		label(c, at, List.of(new WorldLabel.Segment(mob.getName().getString(), nameColor.argb()), new WorldLabel.Segment(hp.text, hp.color)));
	}

	private void drawPearl(Canvas c, ThrownEnderpearl pearl, float tickDelta) {
		Entity owner = pearl.getOwner();
		if (owner == null) return;
		AABB box = Entities.lerpedBox(pearl, tickDelta);
		int color = owner instanceof Player p && Entities.isFriend(p) ? friendColor.argb() : nameColor.argb();
		label(c, new Vec3(box.getCenter().x, box.maxY + 0.25, box.getCenter().z), List.of(new WorldLabel.Segment(owner.getScoreboardName(), color)));
	}

	private void drawItems(Canvas c, float tickDelta) {
		double maxSq = itemRange.get() * itemRange.get();
		List<List<ItemEntity>> groups = new ArrayList<>();
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof ItemEntity item) || mc.player.distanceToSqr(entity) > maxSq) continue;
			List<ItemEntity> target = null;
			if (groupItems.get()) {
				for (List<ItemEntity> g : groups) {
					if (g.getFirst().distanceToSqr(item) < 4) {
						target = g;
						break;
					}
				}
			}
			if (target == null) groups.add(target = new ArrayList<>());
			target.add(item);
		}
		for (List<ItemEntity> g : groups) {
			double x = 0, y = 0, z = 0;
			for (ItemEntity it : g) {
				AABB box = Entities.lerpedBox(it, tickDelta);
				x += box.getCenter().x;
				y += box.maxY;
				z += box.getCenter().z;
			}
			x /= g.size();
			y /= g.size();
			z /= g.size();
			Map<String, Integer> counts = new LinkedHashMap<>();
			for (ItemEntity it : g) counts.merge(it.getItem().getHoverName().getString(), it.getItem().getCount(), Integer::sum);
			int row = 0;
			for (Map.Entry<String, Integer> en : counts.entrySet()) {
				String text = en.getValue() > 1 ? en.getKey() + " x" + en.getValue() : en.getKey();
				label(c, new Vec3(x, y + 0.3 + row++ * 0.28, z), List.of(new WorldLabel.Segment(text, nameColor.argb())));
			}
		}
	}

	private void label(Canvas c, Vec3 at, List<WorldLabel.Segment> segments) {
		WorldLabel.draw(c, at, scale.getFloat() * 0.9f * WorldLabel.distanceScale(at), segments,
			background.get() ? WorldLabel.Background.ROUNDED : WorldLabel.Background.NONE, fill.argb(), 0, true);
	}
}
