package dev.myriad.essentials;

import dev.myriad.api.addon.AddonContext;
import dev.myriad.api.addon.MyriadAddon;
import dev.myriad.essentials.hud.ArmorPanel;
import dev.myriad.essentials.hud.BindsPanel;
import dev.myriad.essentials.hud.ChestCountPanel;
import dev.myriad.essentials.hud.CoordinatesPanel;
import dev.myriad.essentials.hud.DirectionPanel;
import dev.myriad.essentials.hud.EffectsPanel;
import dev.myriad.essentials.hud.FpsPanel;
import dev.myriad.essentials.hud.HealthPanel;
import dev.myriad.essentials.hud.ModuleListPanel;
import dev.myriad.essentials.hud.PlayerCountPanel;
import dev.myriad.essentials.hud.SpeedPanel;
import dev.myriad.essentials.hud.TotemsPanel;
import dev.myriad.essentials.hud.TpsPanel;
import dev.myriad.essentials.hud.WatermarkPanel;
import dev.myriad.essentials.modules.combat.AutoArmor;
import dev.myriad.essentials.modules.combat.AutoDisconnect;
import dev.myriad.essentials.modules.combat.KillAura;
import dev.myriad.essentials.modules.combat.Offhand;
import dev.myriad.essentials.modules.movement.ElytraFly;
import dev.myriad.essentials.modules.movement.ElytraTweaks;
import dev.myriad.essentials.modules.movement.InventoryMove;
import dev.myriad.essentials.modules.movement.Velocity;
import dev.myriad.essentials.modules.player.AutoEat;
import dev.myriad.essentials.modules.player.AutoTool;
import dev.myriad.essentials.modules.player.InventoryTweaks;
import dev.myriad.essentials.modules.player.MiddleClick;
import dev.myriad.essentials.modules.player.PacketMine;
import dev.myriad.essentials.modules.player.Reach;
import dev.myriad.essentials.modules.player.StackReplenish;
import dev.myriad.essentials.modules.player.WallInteract;
import dev.myriad.essentials.modules.player.XCarry;
import dev.myriad.essentials.modules.render.BlockESP;
import dev.myriad.essentials.modules.render.ESP;
import dev.myriad.essentials.modules.render.FreeLook;
import dev.myriad.essentials.modules.render.Freecam;
import dev.myriad.essentials.modules.render.FullBright;
import dev.myriad.essentials.modules.render.Nametags;
import dev.myriad.essentials.modules.render.NoRender;
import dev.myriad.essentials.modules.render.Storage;
import dev.myriad.essentials.modules.render.Tooltips;
import dev.myriad.essentials.modules.render.Tracers;
import dev.myriad.essentials.modules.render.ViewModel;
import dev.myriad.essentials.modules.render.Zoom;
import dev.myriad.essentials.modules.world.AirPlace;
import dev.myriad.essentials.modules.world.Scaffold;

import static dev.myriad.api.ui.PanelType.Anchor.BOTTOM_LEFT;
import static dev.myriad.api.ui.PanelType.Anchor.TOP_LEFT;
import static dev.myriad.api.ui.PanelType.Anchor.TOP_RIGHT;

/**
 * The stock module set. It is an ordinary Myriad addon: it uses nothing but the public API, so removing this jar
 * leaves Myriad fully working with no modules.
 */
public final class Essentials implements MyriadAddon {
	@Override
	public void initialize(AddonContext ctx) {
		ctx.registerModules(
			// Combat
			new Offhand(), new AutoArmor(), new AutoDisconnect(), new KillAura(),
			// Movement
			new ElytraFly(), new ElytraTweaks(), new InventoryMove(), new Velocity(),
			// Player
			new AutoEat(), new AutoTool(), new InventoryTweaks(), new MiddleClick(), new PacketMine(), new Reach(), new StackReplenish(), new WallInteract(), new XCarry(),
			// Render
			new ESP(), new BlockESP(), new Storage(), new Tracers(), new Nametags(), new NoRender(), new Tooltips(), new FullBright(), new FreeLook(), new Freecam(),
			new ViewModel(), new Zoom(),
			// World
			new AirPlace(), new Scaffold()
		);

		// HUD elements. The ones with a position are placed on a fresh install; the rest are added from the HUD workspace.
		ctx.registerHud("Watermark", "", WatermarkPanel::new, TOP_LEFT, 0, 0);
		ctx.registerHud("Module List", "", ModuleListPanel::new, TOP_RIGHT, 0, 0);
		ctx.registerHud("Coordinates", "", CoordinatesPanel::new, BOTTOM_LEFT, 0, 0);
		ctx.registerHud("Armor", "", ArmorPanel::new);
		ctx.registerHud("Binds", "", BindsPanel::new);
		ctx.registerHud("Chest Count", "", ChestCountPanel::new);
		ctx.registerHud("Direction", "", DirectionPanel::new);
		ctx.registerHud("Effects", "", EffectsPanel::new);
		ctx.registerHud("FPS", "", FpsPanel::new);
		ctx.registerHud("HP", "", HealthPanel::new);
		ctx.registerHud("Player Count", "", PlayerCountPanel::new);
		ctx.registerHud("Speed", "", SpeedPanel::new);
		ctx.registerHud("Totems", "", TotemsPanel::new);
		ctx.registerHud("TPS", "", TpsPanel::new);
	}
}
