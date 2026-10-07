package dev.myriad.essentials.modules.world;

import dev.myriad.api.Myriad;
import dev.myriad.api.build.Target;
import dev.myriad.api.event.Priority;
import dev.myriad.api.event.Subscribe;
import dev.myriad.api.event.events.TickEvent;
import dev.myriad.api.module.Categories;
import dev.myriad.api.module.Module;
import dev.myriad.api.service.Placement;
import dev.myriad.api.setting.BoolSetting;
import dev.myriad.api.setting.RegistryListSetting;
import dev.myriad.api.util.Reach;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Places blocks under you as you walk, and a little ahead while you move so you never step off an edge. Jumping in
 * place builds a tower. When the block under you has nothing to be placed against (sprinting off a corner), a
 * neighbouring block is placed first to bridge to it. Blocks come from the hotbar, or are moved there from your
 * inventory, with your keys released for a tick first (Grim refuses inventory clicks while you move). Placements
 * click a face you can see. Without Rotate they're sent without turning, as 2b2t clients usually do: 2b2t
 * takes them, and you keep running smoothly. With Rotate each placement faces its block first, for Grim builds that
 * check where you look (RotationPlace, as on the test server); the block you place against is behind you, so while
 * facing it you walk along that yaw, and sprint drops for that tick.
 */
public class Scaffold extends Module {
	private final RegistryListSetting<Block> blocks = sgGeneral.blocks("Blocks").description("Only use these. Empty uses any ordinary building block.").build();
	private final BoolSetting rotate = sgGeneral.bool("Rotate").description("Face each block before placing it, for servers that check where you look. "
		+ "Breaks your sprint for a moment at each block; 2b2t doesn't need it.").build();

	/** Most placements started per tick: the block under you, the one ahead, and a bridge. */
	private static final int PER_TICK = 3;
	/** How far ahead to place while moving, in ticks of your current speed. */
	private static final double AHEAD_TICKS = 2.5;

	public Scaffold() {
		super(Categories.WORLD, "Scaffold", "Places blocks under you as you walk.");
	}

	/** Clicks only faces you can see; with Rotate, faces each block first (walking along that yaw meanwhile). */
	private Placement.Options options() {
		return Placement.Options.STRICT.withRotate(rotate.get()).withRange(Reach.blockRange());
	}

	/** Which items it may place. */
	private Predicate<ItemStack> usable() {
		return s -> s.getItem() instanceof BlockItem item && (blocks.get().isEmpty() ? Target.solid().preference(s) >= 0 : blocks.get().contains(item.getBlock()));
	}

	/** Before the placement service runs this tick, so a rotated placement goes out in this tick's movement packet. */
	@Subscribe(priority = Priority.HIGH)
	private void onTick(TickEvent.Pre e) {
		if (!inGame() || mc.player.getAbilities().flying || mc.player.isFallFlying()) return;
		int slot = blockSlot();
		if (slot < 0) return;

		Vec3 pos = mc.player.position();
		BlockPos under = BlockPos.containing(pos.x, pos.y, pos.z).below();
		List<BlockPos> wanted = new ArrayList<>(2);
		wanted.add(under);
		Vec3 motion = mc.player.getDeltaMovement();
		if (motion.horizontalDistanceSqr() > 1e-4) {
			BlockPos ahead = BlockPos.containing(pos.x + motion.x * AHEAD_TICKS, pos.y, pos.z + motion.z * AHEAD_TICKS).below();
			if (!ahead.equals(under)) wanted.add(ahead);
		}

		Placement.Options o = options();
		int sent = 0;
		for (BlockPos target : wanted) {
			if (sent >= PER_TICK || !mc.level.getBlockState(target).canBeReplaced()) continue;
			Placement.Attempt a = Myriad.placement().place(this, target, slot, o);
			if (a.sent()) sent++;
			else if (a.check() == Placement.Check.NO_SUPPORT && bridge(target, slot, o)) sent++;
		}
	}

	/** Places a block next to {@code target} that it can then be placed against; true if one was sent. */
	private boolean bridge(BlockPos target, int slot, Placement.Options o) {
		Vec3 pos = mc.player.position();
		List<BlockPos> around = new ArrayList<>(5);
		around.add(target.below());
		for (Direction d : Direction.Plane.HORIZONTAL) around.add(target.relative(d));
		around.sort(Comparator.comparingDouble(p -> Vec3.atCenterOf(p).distanceToSqr(pos)));
		for (BlockPos p : around) {
			if (!mc.level.getBlockState(p).canBeReplaced()) continue;
			if (Myriad.placement().place(this, p, slot, o).sent()) return true;
		}
		return false;
	}

	/** The held block if it's usable, else one in the hotbar; moves one in from the inventory if needed (-1 that tick). */
	private int blockSlot() {
		Predicate<ItemStack> usable = usable();
		int selected = mc.player.getInventory().getSelectedSlot();
		if (usable.test(mc.player.getInventory().getItem(selected))) return selected;
		int slot = Myriad.inventory().findInHotbar(usable);
		if (slot >= 0) return slot;
		// Grim cancels inventory clicks while you move: your keys are released for a tick before blocks come in.
		if (Myriad.inventory().findInInventory(usable) >= 0 && Myriad.inventory().prepareClick()) Myriad.inventory().ensureInHotbar(usable, -1);
		return -1;
	}
}
