package dev.myriad.essentials.modules.render;

import dev.myriad.api.event.Subscribe;
import dev.myriad.api.event.events.BlockRenderEvent;
import dev.myriad.api.event.events.EntityRenderEvent;
import dev.myriad.api.module.Categories;
import dev.myriad.api.module.Module;
import dev.myriad.api.module.Modules;
import dev.myriad.api.setting.BoolSetting;
import dev.myriad.api.setting.RegistryListSetting;
import dev.myriad.api.setting.SettingGroup;
import java.util.function.Function;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Hides distracting overlays, HUD parts, entities and blocks. Read by this addon's render mixins. */
public class NoRender extends Module {
	/** Where fog starts with Fog hidden: never reached. Start and end must differ, or the fog shader divides by zero. */
	public static final float FOG_FAR = 1_000_000f;

	private final SettingGroup sgOverlays = settings.group("Overlays");
	public final BoolSetting hurtCam = sgGeneral.bool("Hurt Camera").description("No camera shake when you take damage.").defaultValue(true).build();
	public final BoolSetting totem = sgGeneral.bool("Totem Animation").description("No totem pop animation.").defaultValue(true).build();
	public final BoolSetting weather = sgGeneral.bool("Weather").description("No rain or snow.").build();
	public final BoolSetting toasts = sgGeneral.bool("Toasts").description("No advancement, recipe or system toasts.").build();
	public final BoolSetting fire = sgOverlays.bool("Fire Overlay").defaultValue(true).build();
	public final BoolSetting blockOverlay = sgOverlays.bool("Block Overlay").description("No texture when your head is inside a block.").defaultValue(true).build();
	public final BoolSetting liquidOverlay = sgOverlays.bool("Liquid Overlay").description("No underwater texture.").build();
	public final BoolSetting vignette = sgOverlays.bool("Vignette").defaultValue(true).build();
	public final BoolSetting portal = sgOverlays.bool("Portal Overlay").description("No purple swirl or nausea overlay.").defaultValue(true).build();
	public final BoolSetting pumpkin = sgOverlays.bool("Pumpkin Overlay").defaultValue(true).build();
	public final BoolSetting powderSnow = sgOverlays.bool("Powder Snow Overlay").description("No frost around the screen while freezing.").defaultValue(true).build();
	public final BoolSetting spyglass = sgOverlays.bool("Spyglass Overlay").description("Zoom with a spyglass without the black ring.").build();

	private final SettingGroup sgEffects = settings.group("Effects");
	public final BoolSetting blindness = sgEffects.bool("Blindness").description("See normally while blinded.").defaultValue(true).build();
	public final BoolSetting darkness = sgEffects.bool("Darkness").description("No pulsing darkness near wardens and sculk shriekers.").defaultValue(true).build();
	public final BoolSetting nausea = sgEffects.bool("Nausea").description("No screen warping from nausea.").defaultValue(true).build();

	private final SettingGroup sgHud = settings.group("HUD");
	public final BoolSetting bossBar = sgHud.bool("Boss Bar").build();
	public final BoolSetting statusEffects = sgHud.bool("Status Effects").description("No potion icons in the top right.").build();
	public final BoolSetting xpBar = sgHud.bool("XP Bar").build();
	public final BoolSetting itemName = sgHud.bool("Item Name").description("No item name above the hotbar when switching.").build();
	public final BoolSetting scoreboard = sgHud.bool("Scoreboard").description("No scoreboard on the right of the screen.").build();

	private final SettingGroup sgWorld = settings.group("World");
	public final BoolSetting armor = sgWorld.bool("Armor").description("No armour on players and mobs.").build();
	public final BoolSetting burning = sgWorld.bool("Burning").description("No flames on burning entities.").build();
	public final BoolSetting damageTint = sgWorld.bool("Damage Tint").description("No red tint on hurt entities.").build();
	public final BoolSetting fog = sgWorld.bool("Fog").description("No distance or weather fog (water and lava fog stay).").build();
	public final BoolSetting glint = sgWorld.bool("Enchantment Glint").description("No shimmer on enchanted items and armour.").build();
	public final BoolSetting deadEntities = sgWorld.bool("Dead Entities").description("Hide entities as soon as they die.").build();
	private final RegistryListSetting<EntityType<?>> entities = sgWorld.entityTypes("Entities").description("Entity types never drawn.").build();
	private final RegistryListSetting<Block> blocks = sgWorld.blocks("Blocks").description("Blocks never drawn.").onChanged(v -> reload()).build();
	public final BoolSetting vines = sgWorld.bool("Vines").description("Hide vines, kelp and other hanging plants.").onChanged(v -> reload()).build();
	public final BoolSetting textureRotations = sgWorld.bool("Texture Rotations").description("Remove random block offsets (flowers, grass), which can leak coordinates.")
		.onChanged(v -> reload()).build();

	public NoRender() {
		super(Categories.RENDER, "No Render", "Hides distracting effects, overlays and things.");
	}

	/**
	 * Whether No Render is on and hides the thing {@code option} picks, e.g. {@code NoRender.hides(n -> n.fire)}.
	 * Used by this addon's mixins.
	 */
	public static boolean hides(Function<NoRender, BoolSetting> option) {
		NoRender m = Modules.active(NoRender.class);
		return m != null && option.apply(m).get();
	}

	@Override
	protected void onEnable() {
		reload();
	}

	@Override
	protected void onDisable() {
		reload();
	}

	private void reload() {
		if (mc.levelRenderer != null && mc.level != null && (isEnabled() || !blocks.get().isEmpty() || vines.get() || textureRotations.get())) mc.levelRenderer.invalidateCompiledGeometry(mc.level, mc.options, mc.gameRenderer.mainCamera(), mc.getBlockColors());
	}

	/** Hidden entities aren't drawn at all. */
	@Subscribe
	private void onEntity(EntityRenderEvent.Visible e) {
		Entity entity = e.entity();
		if (entities.contains(entity.getType()) || deadEntities.get() && entity instanceof LivingEntity l && l.isDeadOrDying()) e.cancel();
	}

	/** Hidden blocks leave the chunk mesh (asked on the chunk builder threads; settings are safe to read there). */
	@Subscribe
	private void onBlock(BlockRenderEvent e) {
		Block b = e.state().getBlock();
		if (blocks.contains(b) || vines.get() && (b == Blocks.VINE || b == Blocks.CAVE_VINES || b == Blocks.CAVE_VINES_PLANT || b == Blocks.TWISTING_VINES
			|| b == Blocks.TWISTING_VINES_PLANT || b == Blocks.WEEPING_VINES || b == Blocks.WEEPING_VINES_PLANT || b == Blocks.KELP || b == Blocks.KELP_PLANT
			|| b == Blocks.GLOW_LICHEN)) e.cancel();
	}
}
