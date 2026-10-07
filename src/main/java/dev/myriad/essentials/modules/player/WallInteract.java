package dev.myriad.essentials.modules.player;

import dev.myriad.api.module.Categories;
import dev.myriad.api.module.Module;
import dev.myriad.api.module.Modules;
import dev.myriad.api.setting.BoolSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Hit and use things through walls: an entity behind a block within your reach becomes what you're pointing at, and
 * so (optionally) does a chest or other container. Applied by this addon's ClientPlayerEntityMixin to the crosshair
 * target, so attacking, using and the outline all follow it.
 */
public class WallInteract extends Module {
	private final BoolSetting entities = sgGeneral.bool("Entities").description("Hit and use entities through blocks.").defaultValue(true).build();
	private final BoolSetting players = sgGeneral.bool("Only Players").description("Only players count, not mobs.").visible(entities::get).build();
	private final BoolSetting containers = sgGeneral.bool("Containers").description("Open chests and other containers through blocks.").build();

	public WallInteract() {
		super(Categories.PLAYER, "Wall Interact", "Hit entities and open containers through walls.");
	}

	/** The crosshair target to use instead of vanilla's {@code original}. */
	public static HitResult retarget(HitResult original, Entity camera, float partialTicks) {
		WallInteract m = Modules.active(WallInteract.class);
		if (m == null || mc.player == null || original.getType() == HitResult.Type.ENTITY) return original;
		Vec3 from = camera.getEyePosition(partialTicks), dir = camera.getViewVector(partialTicks);
		if (m.entities.get()) {
			double range = mc.player.entityInteractionRange();
			Vec3 to = from.add(dir.scale(range));
			AABB box = camera.getBoundingBox().expandTowards(dir.scale(range)).inflate(1);
			EntityHitResult hit = ProjectileUtil.getEntityHitResult(camera, from, to, box,
				e -> EntitySelector.CAN_BE_PICKED.test(e) && (!m.players.get() || e instanceof net.minecraft.world.entity.player.Player), range * range);
			if (hit != null) return hit;
		}
		if (m.containers.get()) {
			BlockHitResult container = m.container(from, dir, mc.player.blockInteractionRange());
			if (container != null && !(original instanceof BlockHitResult b && b.getBlockPos().equals(container.getBlockPos()))) return container;
		}
		return original;
	}

	/** The first container along the ray within {@code range}, ignoring anything in front of it. */
	private BlockHitResult container(Vec3 from, Vec3 dir, double range) {
		BlockPos last = null;
		for (double d = 0; d <= range; d += 0.05) {
			Vec3 at = from.add(dir.scale(d));
			BlockPos pos = BlockPos.containing(at);
			if (pos.equals(last)) continue;
			last = pos;
			BlockEntity be = mc.level.getBlockEntity(pos);
			if (be instanceof Container || be instanceof EnderChestBlockEntity) {
				return new BlockHitResult(at, Direction.getApproximateNearest(-dir.x, -dir.y, -dir.z), pos, false);
			}
		}
		return null;
	}
}
