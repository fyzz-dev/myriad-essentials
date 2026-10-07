package dev.myriad.essentials.modules.world;

import dev.myriad.api.Myriad;
import dev.myriad.api.event.Subscribe;
import dev.myriad.api.event.events.InteractEvent;
import dev.myriad.api.event.events.Render3DEvent;
import dev.myriad.api.module.Categories;
import dev.myriad.api.module.Module;
import dev.myriad.api.render.Renderer3D;
import dev.myriad.api.service.Placement;
import dev.myriad.api.setting.DoubleSetting;
import dev.myriad.api.setting.SettingColor;
import dev.myriad.api.util.Reach;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Places blocks in mid-air: with a block in hand and nothing under the crosshair, right-click puts it where you look,
 * {@link #distance} blocks away, outlined beforehand. Only lenient servers accept placements with nothing to click
 * against.
 */
public class AirPlace extends Module {
	private final DoubleSetting distance = sgGeneral.doubleSetting("Distance").description("How far in front of you the block goes.")
		.defaultValue(3).range(1, 5).decimals(1).build();

	public AirPlace() {
		super(Categories.WORLD, "Air Place", "Place blocks in mid-air where you look.");
	}

	private Placement.Options options() {
		return Placement.Options.DEFAULT.withAirPlace(true).withRange(Reach.blockRange());
	}

	/** Where a right-click would place now, or null if vanilla should handle it (or it can't go there). */
	private BlockPos target() {
		if (!inGame() || !(mc.player.getMainHandItem().getItem() instanceof BlockItem)) return null;
		if (mc.hitResult != null && mc.hitResult.getType() != HitResult.Type.MISS) return null;
		Vec3 eyes = mc.player.getEyePosition();
		BlockPos pos = BlockPos.containing(eyes.add(mc.player.getViewVector(1).scale(Math.min(distance.get(), Reach.blockRange()))));
		return Myriad.placement().check(pos, options()).ok() ? pos : null;
	}

	@Subscribe
	private void onUse(InteractEvent.Item e) {
		if (e.hand() != InteractionHand.MAIN_HAND) return;
		BlockPos pos = target();
		if (pos == null) return;
		e.cancel();
		Myriad.placement().place(this, pos, mc.player.getInventory().getSelectedSlot(), options());
	}

	@Subscribe
	private void onRender(Render3DEvent e) {
		BlockPos pos = target();
		if (pos == null) return;
		int color = SettingColor.role(SettingColor.Mode.ACCENT).argb();
		Renderer3D.box(new AABB(pos), 0, color, Renderer3D.ShapeMode.LINES, false);
	}
}
