package dev.myriad.essentials.modules.combat;

import com.google.gson.JsonPrimitive;
import dev.myriad.api.Myriad;
import dev.myriad.api.event.Subscribe;
import dev.myriad.api.event.events.TickEvent;
import dev.myriad.api.module.Categories;
import dev.myriad.api.module.Module;
import dev.myriad.api.setting.BoolSetting;
import dev.myriad.api.setting.DoubleSetting;
import dev.myriad.api.setting.EnumSetting;
import dev.myriad.api.setting.IntSetting;
import dev.myriad.api.setting.RegistryListSetting;
import dev.myriad.api.setting.SavedSettings;
import dev.myriad.api.setting.SettingGroup;
import dev.myriad.api.util.ItemInfo;
import dev.myriad.api.combat.Threats;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

/**
 * Leaves the server before you die: at low health, after too many totem pops or with too few totems left, when your
 * armour is about to break, a fall would kill you or you drop into the void, when a player you haven't friended or an
 * entity you picked comes close, or when a bed, respawn anchor, end crystal or creeper nearby could kill you. The
 * disconnect screen says why. Singleplayer is left alone.
 * <p>
 * Afterwards it either turns itself off, or stays on and waits for the reason it left to clear before that reason can
 * trigger again, so you can rejoin (say with no totems left) without leaving again straight away.
 */
public class AutoDisconnect extends Module {
	public enum AfterLeaving {
		TURN_OFF, WAIT_FOR_CLEAR
	}

	private final SettingGroup sgHealth = settings.group("Health");
	private final BoolSetting health = sgHealth.bool("Health").description("Leave at low health (absorption included).").defaultValue(true).build();
	private final DoubleSetting healthThreshold = sgHealth.doubleSetting("Threshold").defaultValue(6).range(1, 36).decimals(1).visible(health::get).build();
	private final BoolSetting pops = sgHealth.bool("Totem Pops").description("Leave after this many totems pop in one life.").build();
	private final IntSetting popCount = sgHealth.intSetting("Pops").defaultValue(3).range(1, 20).visible(pops::get).build();
	private final BoolSetting totems = sgHealth.bool("Totems Left").description("Leave when you have this many totems or fewer.").build();
	private final IntSetting totemCount = sgHealth.intSetting("Totems").defaultValue(0).range(0, 20).visible(totems::get).build();
	private final BoolSetting armor = sgHealth.bool("Armor").description("Leave when a piece of armour you wear is about to break.").build();
	private final IntSetting armorDurability = sgHealth.intSetting("Armor Durability").description("Durability left, in percent.").defaultValue(10).range(1, 50).visible(armor::get).build();

	private final SettingGroup sgDanger = settings.group("Danger");
	private final BoolSetting players = sgDanger.bool("Players").description("Leave when a player who isn't a friend comes close.").build();
	private final DoubleSetting playerRange = sgDanger.doubleSetting("Player Range").defaultValue(16).range(1, 128).decimals(0).visible(players::get).build();
	private final BoolSetting beds = sgDanger.bool("Beds").description("Leave when a bed nearby could kill you (outside the Overworld).").defaultValue(true).build();
	private final BoolSetting anchors = sgDanger.bool("Anchors").description("Leave when a charged respawn anchor nearby could kill you (outside the Nether).").defaultValue(true).build();
	private final BoolSetting crystals = sgDanger.bool("Crystals").description("Leave when end crystals nearby could kill you.").build();
	private final BoolSetting creepers = sgDanger.bool("Creepers").description("Leave when a creeper comes close.").build();
	private final DoubleSetting creeperRange = sgDanger.doubleSetting("Creeper Range").defaultValue(5).range(1, 16).decimals(1).visible(creepers::get).build();
	private final BoolSetting falls = sgDanger.bool("Falls").description("Leave when the fall you're in would kill you.").build();
	private final BoolSetting voidFall = sgDanger.bool("Void").description("Leave when you fall below the bottom of the world.").defaultValue(true).build();
	private final RegistryListSetting<EntityType<?>> entities = sgDanger.entityTypes("Entities").description("Leave when one of these comes close (TNT minecarts, withers...).").build();
	private final DoubleSetting entityRange = sgDanger.doubleSetting("Entity Range").defaultValue(10).range(1, 64).decimals(0).visible(() -> !entities.get().isEmpty()).build();

	private final EnumSetting<AfterLeaving> afterLeaving = sgGeneral.enumSetting("After Leaving", AfterLeaving.TURN_OFF)
		.description("Turn off, or stay on and wait for the reason you left to clear before it can make you leave again.").build();
	private final BoolSetting copyCoords = sgGeneral.bool("Copy Coords").description("Copy where you were to the clipboard.").build();

	/** Reasons that made you leave and haven't cleared since (Wait For Clear). */
	private final Set<String> waiting = new HashSet<>();

	private record Trigger(BooleanSupplier enabled, Supplier<@Nullable String> check) {
	}

	/** Each reason to leave by key, in the order they're checked: the check returns why, or null. */
	private final Map<String, Trigger> triggers = new LinkedHashMap<>();

	public AutoDisconnect() {
		super(Categories.COMBAT, "Auto Disconnect", "Leaves the server when you're about to die.");
		trigger("health", health::get, () -> Threats.health() <= healthThreshold.get() ? String.format("health %.1f", Threats.health()) : null);
		trigger("pops", pops::get, () -> {
			int n = Myriad.server().totemPops(mc.player);
			return n >= popCount.get() ? n + " totem pops" : null;
		});
		trigger("totems", totems::get, () -> {
			int n = Myriad.inventory().count(s -> s.is(Items.TOTEM_OF_UNDYING));
			return n <= totemCount.get() ? n + (n == 1 ? " totem" : " totems") + " left" : null;
		});
		trigger("armor", armor::get, this::wornOut);
		trigger("players", players::get, this::nearbyPlayer);
		trigger("beds", beds::get, () -> Threats.beds(8) >= Threats.health() ? "a bed could kill you" : null);
		trigger("anchors", anchors::get, () -> Threats.anchors(8) >= Threats.health() ? "a respawn anchor could kill you" : null);
		trigger("crystals", crystals::get, () -> Threats.crystals(12, true) >= Threats.health() ? "end crystals could kill you" : null);
		trigger("creepers", creepers::get, () -> {
			var near = mc.level.getEntitiesOfClass(Creeper.class, mc.player.getBoundingBox().inflate(creeperRange.get()));
			return near.isEmpty() ? null : "a creeper " + Math.round(mc.player.distanceTo(near.getFirst())) + " blocks away";
		});
		trigger("falls", falls::get, () -> Threats.fall() >= Threats.health() ? "a fall would kill you" : null);
		trigger("void", voidFall::get, () -> mc.player.getY() < mc.level.getMinY() ? "falling into the void" : null);
		trigger("entities", () -> !entities.get().isEmpty(), this::nearbyEntity);
	}

	private void trigger(String key, BooleanSupplier enabled, Supplier<@Nullable String> check) {
		triggers.put(key, new Trigger(enabled, check));
	}

	/** Version 2 replaced the Auto Disable toggle with After Leaving. */
	@Override
	public int settingsVersion() {
		return 2;
	}

	@Override
	protected void migrateSettings(int fromVersion, SavedSettings saved) {
		if (fromVersion == 1) {
			// Only "off" was ever saved (on was the default): staying on now means waiting for the reason to clear.
			saved.get("General", "Auto Disable").ifPresent(v -> {
				if (!v.getAsBoolean()) saved.set("General", "After Leaving", new JsonPrimitive("WAIT_FOR_CLEAR"));
			});
			saved.remove("General", "Auto Disable");
		}
	}

	@Override
	public String hudInfo() {
		return mc.player == null ? null : String.format("%.1f", Threats.health());
	}

	@Override
	protected void onEnable() {
		waiting.clear();
	}

	@Subscribe
	private void onTick(TickEvent.Post e) {
		if (!inGame() || mc.player.isCreative() || mc.player.isSpectator() || mc.isLocalServer() || mc.getConnection() == null) return;
		String leaveFor = null, leaveKey = null;
		for (Map.Entry<String, Trigger> t : triggers.entrySet()) {
			String reason = t.getValue().enabled.getAsBoolean() ? t.getValue().check.get() : null;
			if (reason == null) {
				// Cleared: it can trigger again.
				waiting.remove(t.getKey());
			} else if (leaveFor == null && !waiting.contains(t.getKey())) {
				leaveFor = reason;
				leaveKey = t.getKey();
			}
		}
		if (leaveFor != null) leave(leaveKey, leaveFor);
	}

	private @Nullable String wornOut() {
		for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			ItemStack s = mc.player.getItemBySlot(slot);
			if (s.isDamageableItem() && ItemInfo.durabilityFraction(s) * 100 < armorDurability.get()) return s.getHoverName().getString() + " is about to break";
		}
		return null;
	}

	private @Nullable String nearbyPlayer() {
		for (Player p : mc.level.players()) {
			if (p == mc.player || !p.isAlive() || p.isSpectator() || Myriad.friends().isFriend(p)) continue;
			if (mc.player.distanceTo(p) <= playerRange.get()) return p.getGameProfile().name() + " " + Math.round(mc.player.distanceTo(p)) + " blocks away";
		}
		return null;
	}

	private @Nullable String nearbyEntity() {
		Set<EntityType<?>> types = entities.get();
		for (Entity en : mc.level.getEntities(mc.player, mc.player.getBoundingBox().inflate(entityRange.get()), en -> types.contains(en.getType()))) {
			return en.getName().getString() + " " + Math.round(mc.player.distanceTo(en)) + " blocks away";
		}
		return null;
	}

	private void leave(String key, String reason) {
		if (copyCoords.get()) mc.keyboardHandler.setClipboard(String.format("%d %d %d", mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ()));
		if (afterLeaving.get() == AfterLeaving.TURN_OFF) disable();
		else waiting.add(key);
		mc.getConnection().getConnection().disconnect(Component.literal("[Auto Disconnect] " + reason));
	}
}
