package dev.myriad.essentials.modules.combat;

import dev.myriad.api.Myriad;
import dev.myriad.api.combat.TargetSettings;
import dev.myriad.api.combat.Targets;
import dev.myriad.api.event.Subscribe;
import dev.myriad.api.event.events.Render3DEvent;
import dev.myriad.api.event.events.TickEvent;
import dev.myriad.api.module.Categories;
import dev.myriad.api.module.Module;
import dev.myriad.api.render.Renderer3D;
import dev.myriad.api.service.Rotations;
import dev.myriad.api.setting.BoolSetting;
import dev.myriad.api.setting.ColorSetting;
import dev.myriad.api.setting.EnumSetting;
import dev.myriad.api.setting.IntSetting;
import dev.myriad.api.setting.SettingColor;
import dev.myriad.api.util.ColorUtil;
import dev.myriad.api.util.Entities;
import dev.myriad.api.util.Interactions;
import dev.myriad.api.util.ItemInfo;
import dev.myriad.api.util.MathUtil;
import dev.myriad.api.util.Reach;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Attacks the best target in reach, the way Grim (2b2t) checks hits: it turns to face the target (walking along that
 * yaw meanwhile, so movement still matches), and hits when the look puts the target under the crosshair within your
 * entity reach, measured along the look from where you stand, as Grim does. Grim judges a hit by this tick's rotation
 * or the one before it, so a hit can go out the same tick the turn does. Hits wait for a full attack charge, go out
 * before the tick's movement as a click would, and are followed by the swing, in vanilla's order.
 * <p>
 * In Switch mode the weapon is held on the server only, as Packet Mine holds its pickaxe: your hotbar and hand stay as
 * they are (a weapon from the inventory is borrowed and put back afterwards). It's held from a few blocks before a
 * target comes into reach, so the charge, which starts over when the item in hand changes, is full by the time it does.
 * <p>
 * It pauses while you use an item (eating, blocking, drawing a bow), fly with an elytra (turning would steer you) or
 * have a container open, as vanilla can't attack then either.
 */
public class KillAura extends Module {
	public enum Weapon {
		/** Only attack while holding a weapon. */
		HOLDING,
		/** Hold the best weapon on the server only, from the hotbar or the inventory, leaving your hand as it is. */
		SWITCH,
		/** Hit with whatever is in your hand. */
		ANYTHING
	}

	private final EnumSetting<Weapon> weapon = sgGeneral.enumSetting("Weapon", Weapon.HOLDING)
		.description("Holding: only attack while you hold a weapon. Switch: attack with your best weapon, from the hotbar or inventory, without changing what you hold. Anything: hit with whatever you hold.").build();
	private final IntSetting turnSpeed = sgGeneral.intSetting("Turn Speed")
		.description("Most degrees to turn per tick towards a target; 0 turns at once (fine on Grim, some anti-cheats want it limited).")
		.defaultValue(0).range(0, 180).build();
	private final BoolSetting render = sgGeneral.bool("Render").description("Outline the target.").defaultValue(true).build();
	private final ColorSetting color = sgGeneral.color("Color").defaultValue(SettingColor.role(SettingColor.Mode.RED)).visible(render::get).build();

	private final TargetSettings targets = new TargetSettings(settings, Targets.Type.PLAYERS, Targets.Type.HOSTILES);

	/** Switch: blocks beyond reach a target may be when the weapon is taken in hand, so its charge is full on arrival. */
	private static final double PRE_CHARGE = 8;
	/** Switch: ticks the hold lasts without being renewed. */
	private static final int HOLD_TICKS = 5;

	private Entity target;

	public KillAura() {
		super(Categories.COMBAT, "Kill Aura", "Attacks targets in reach, facing them first.");
	}

	@Override
	protected void onDisable() {
		target = null;
		if (inGame()) Myriad.inventory().release(this);
	}

	@Override
	public String hudInfo() {
		return target == null ? null : target.getName().getString();
	}

	/** Before the movement packet, so the hit goes out first (like a click) and this tick's turn goes out with it. */
	@Subscribe
	private void onTick(TickEvent.Pre e) {
		target = null;
		if (!inGame()) return;
		double range = Reach.entityRange();
		Entity near = paused() ? null : targets.best(range + (weapon.get() == Weapon.SWITCH ? PRE_CHARGE : 0));
		Entity t = near == null ? null : targets.best(range);
		if (!armed(near != null, t != null) || t == null) return;
		target = t;

		// Aimed from where you stand: Grim measures the hit from there.
		Vec3 eyes = mc.player.getEyePosition();
		float[] r = MathUtil.anglesTo(eyes, aimPoint(t, eyes));
		Myriad.rotations().request(this, r[0], r[1], Rotations.PRIORITY_NORMAL, Rotations.Options.MOVE_FIX.withTurnSpeed(turnSpeed.get()), null);

		// The charge counts for the item the server holds (a held weapon's, not what you see).
		if (Interactions.attackCharge() < 1) return;
		if (lands(t, range, Myriad.rotations().serverYaw(), Myriad.rotations().serverPitch()) || landsThisTick(t, range, r)) {
			Interactions.attack(t, true);
		}
	}

	/**
	 * Whether this tick's rotation lands, when the one asked for would: fixed for this tick's movement packet then
	 * (Grim checks a hit against it once that packet arrives).
	 */
	private boolean landsThisTick(Entity t, double range, float[] wanted) {
		if (!lands(t, range, wanted[0], wanted[1])) return false;
		float[] sent = Myriad.rotations().rotationForAction();
		return lands(t, range, sent[0], sent[1]);
	}

	private boolean paused() {
		return mc.player.isSpectator() || mc.player.isFallFlying() || mc.player.isUsingItem() || mc.player.containerMenu != mc.player.inventoryMenu;
	}

	/**
	 * Whether the hand is ready to fight. In Switch mode the best weapon is held on the server while a target is near
	 * ({@code near}), from a few blocks before it comes into reach; until then a module that needs the hand (Packet
	 * Mine's pickaxe) has it first.
	 */
	private boolean armed(boolean near, boolean inReach) {
		return switch (weapon.get()) {
			case ANYTHING -> near;
			case HOLDING -> near && weaponScore(Myriad.inventory().serverItem()) > 0;
			case SWITCH -> {
				if (!near) {
					Myriad.inventory().release(this);
					yield false;
				}
				int best = Myriad.inventory().bestInHotbar(this::weaponScore);
				int anywhere = Myriad.inventory().bestInInventory(this::weaponScore);
				if (anywhere >= 9) {
					// A better weapon in the inventory: borrowed into the hotbar (over a worse one if it's full), with your
					// keys released for a tick first if you're moving, as Grim requires for the click. It goes back once
					// the fight is over, and the hotbar keeps showing what was there meanwhile.
					int pulled = Myriad.inventory().borrow(this, anywhere, s -> weaponScore(s) > 0);
					if (pulled >= 0) best = pulled;
				} else if (best >= 0) {
					// Keep a borrowed weapon while fighting.
					Myriad.inventory().borrow(this, best, null);
				}
				var inv = mc.player.getInventory();
				if (best >= 0 && best != inv.getSelectedSlot() && weaponScore(inv.getItem(best)) > weaponScore(inv.getSelectedItem())) {
					if (inReach) Myriad.inventory().hold(this, best, HOLD_TICKS);
					else Myriad.inventory().holdWeakly(this, best, HOLD_TICKS);
				} else {
					Myriad.inventory().release(this);
				}
				yield true;
			}
		};
	}

	/**
	 * Whether looking along {@code yaw}/{@code pitch} from your eyes meets the target's hitbox within reach: Grim's
	 * Reach and Hitboxes checks.
	 */
	private boolean lands(Entity t, double range, float yaw, float pitch) {
		Vec3 eyes = mc.player.getEyePosition();
		AABB box = t.getBoundingBox();
		if (box.contains(eyes)) return true;
		Vec3 look = MathUtil.direction(yaw, pitch);
		return box.clip(eyes, eyes.add(look.scale(range))).isPresent();
	}

	/**
	 * The point of the target's hitbox nearest {@code eyes}, kept well inside the box, so the look meets it at nearly the
	 * shortest reach without grazing the edge (you and the target move between the look and the hit).
	 */
	private static Vec3 aimPoint(Entity t, Vec3 eyes) {
		AABB box = t.getBoundingBox();
		double ix = box.getXsize() * 0.3, iy = Math.min(0.3, box.getYsize() * 0.3), iz = box.getZsize() * 0.3;
		return MathUtil.closestPoint(box.deflate(ix, iy, iz), eyes);
	}

	/** Damage per second at full charge (with Sharpness); 0 for things that aren't weapons or are about to break. */
	private double weaponScore(ItemStack s) {
		if (s.isEmpty() || s.isDamageableItem() && ItemInfo.durabilityFraction(s) < 0.03) return 0;
		ItemAttributeModifiers mods = s.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		double damage = mods.compute(Attributes.ATTACK_DAMAGE, 1, EquipmentSlot.MAINHAND);
		if (damage <= 1) return 0;
		int sharpness = ItemInfo.enchantmentLevel(s, Enchantments.SHARPNESS);
		if (sharpness > 0) damage += 0.5 * sharpness + 0.5;
		return damage * mods.compute(Attributes.ATTACK_SPEED, 4, EquipmentSlot.MAINHAND);
	}

	@Subscribe
	private void onRender(Render3DEvent e) {
		if (!render.get() || target == null || !inGame()) return;
		int c = color.argb();
		Renderer3D.box(Entities.lerpedBox(target, e.tickDelta()), ColorUtil.withAlpha(c, 40), c, Renderer3D.ShapeMode.BOTH, false);
	}
}
