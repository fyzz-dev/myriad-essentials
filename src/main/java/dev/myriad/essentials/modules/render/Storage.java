package dev.myriad.essentials.modules.render;

import dev.myriad.api.event.Subscribe;
import dev.myriad.api.event.events.PacketEvent;
import dev.myriad.api.event.events.Render3DEvent;
import dev.myriad.api.event.events.TickEvent;
import dev.myriad.api.event.events.WorldEvent;
import dev.myriad.api.module.Categories;
import dev.myriad.api.module.Module;
import dev.myriad.api.render.MergedBoxes;
import dev.myriad.api.render.MeshBuilder;
import dev.myriad.api.render.Renderer3D;
import dev.myriad.api.render.ShapeBuilder;
import dev.myriad.api.setting.BoolSetting;
import dev.myriad.api.setting.ColorSetting;
import dev.myriad.api.setting.IntSetting;
import dev.myriad.api.setting.SettingColor;
import dev.myriad.api.setting.SettingGroup;
import dev.myriad.api.util.ColorUtil;
import dev.myriad.api.util.Entities;
import dev.myriad.api.world.ChunkCache;
import dev.myriad.api.render.BoxStyle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractChestedHorse;
import net.minecraft.world.entity.vehicle.boat.ChestBoat;
import net.minecraft.world.entity.vehicle.boat.ChestRaft;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.entity.vehicle.minecart.MinecartFurnace;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;

/**
 * Highlights storage: chests, trapped and ender chests, barrels, shulker boxes, hoppers, dispensers and droppers,
 * furnaces and other containers, plus storage you can't place (chest and hopper minecarts, chest boats and rafts,
 * donkeys, mules and llamas carrying chests). Each kind can be turned off and has its own colour; double chests are one
 * box, and shulker boxes can take their dye colour.
 * <ul>
 * <li>Lids: a chest or shulker box that opens is drawn as it animates, lid and all, then goes back to its box.</li>
 * <li>Merge: touching containers of the same colour are drawn as one shape that fits their hitboxes, so a wall of
 * chests is one outline; a chest opening in it keeps its body in the shape and only the lid moves.</li>
 * </ul>
 * Blocks are found per chunk with a {@link ChunkCache} (scanned when a chunk loads and again only when a block in it
 * changes) and their boxes stay on the GPU, so settings only re-mesh. Only containers whose lids are moving, and
 * entities, are drawn each frame. Tracers can also draw lines to everything this shows.
 */
public class Storage extends Module {
	/** Chest model, in sixteenths of a block: the body is 10 tall, the lid sits from 9 to 14 and hinges at the back. */
	private static final double BODY_TOP = 10 / 16.0, LID_BOTTOM = 9 / 16.0, LID_HEIGHT = 5 / 16.0;
	/** Shulker box model: the base is the back half; the lid, 12/16 deep from 4/16, rises half a block and twists 270°. */
	private static final double SHULKER_BASE = 8 / 16.0, SHULKER_LID_FROM = 4 / 16.0, SHULKER_RISE = 0.5;
	/** How long after an open or close event a lid is drawn live, at least (the animation then has to settle). */
	private static final long LID_MS = 1000;

	private final IntSetting range = sgGeneral.intSetting("Range").description("Chunks around you.").defaultValue(8).range(1, 32).build();
	private final BoolSetting tracers = sgGeneral.bool("Tracers").description("A line from the crosshair to each one.").build();
	private final BoolSetting lids = sgGeneral.bool("Lids").description("Follow chest and shulker box lids as they open.").defaultValue(true).build();
	private final BoolSetting merge = sgGeneral.bool("Merge").description("Draw touching containers of the same colour as one outline.").build();
	private final BoxStyle style = new BoxStyle(sgGeneral);

	private final SettingGroup sgBlocks = settings.group("Blocks");
	private final Kind chests = kind(sgBlocks, "Chests", true, SettingColor.Mode.YELLOW);
	private final Kind trappedChests = kind(sgBlocks, "Trapped Chests", true, SettingColor.Mode.RED);
	private final Kind enderChests = kind(sgBlocks, "Ender Chests", true, SettingColor.Mode.MAGENTA);
	private final Kind barrels = kind(sgBlocks, "Barrels", true, SettingColor.Mode.YELLOW);
	private final Kind shulkers = kind(sgBlocks, "Shulker Boxes", true, SettingColor.Mode.ACCENT);
	private final BoolSetting dyedShulkers = sgBlocks.bool("Dyed Shulkers").description("Draw shulker boxes in their own dye colour.")
		.defaultValue(true).visible(shulkers.enabled::get).build();
	private final Kind hoppers = kind(sgBlocks, "Hoppers", false, SettingColor.Mode.TEXT);
	private final Kind dispensers = kind(sgBlocks, "Dispensers & Droppers", false, SettingColor.Mode.TEXT);
	private final Kind furnaces = kind(sgBlocks, "Furnaces", false, SettingColor.Mode.SECONDARY);
	private final Kind otherBlocks = kind(sgBlocks, "Other Containers", false, SettingColor.Mode.TEXT);

	private final SettingGroup sgEntities = settings.group("Entities");
	private final Kind chestMinecarts = kind(sgEntities, "Chest Minecarts", true, SettingColor.Mode.YELLOW);
	private final Kind hopperMinecarts = kind(sgEntities, "Hopper Minecarts", false, SettingColor.Mode.TEXT);
	private final Kind furnaceMinecarts = kind(sgEntities, "Furnace Minecarts", false, SettingColor.Mode.SECONDARY);
	private final Kind chestBoats = kind(sgEntities, "Chest Boats", true, SettingColor.Mode.YELLOW);
	private final Kind chestAnimals = kind(sgEntities, "Chest Animals", false, SettingColor.Mode.GREEN);

	/** Every storage block per chunk, whether or not its kind is shown: toggling a kind only re-meshes. */
	private final ChunkCache<List<Found>> found = ChunkCache.of(this, this::scan)
		.range(range::get)
		.mesh(this::mesh)
		.neighbours()
		.build();

	/** Containers whose lids are moving, by the position they're drawn from, and when they last opened or closed. */
	private final Map<BlockPos, Long> animating = new HashMap<>();
	/** Open/close events from the network thread, handled on the next tick. */
	private final Queue<BlockPos> lidEvents = new ConcurrentLinkedQueue<>();

	private record Kind(BoolSetting enabled, ColorSetting color) {
	}

	private enum Lid {
		NONE, CHEST, SHULKER
	}

	/** {@code pos} is the block it's drawn from (a double chest's left half); {@code tint} a shulker box's dye, or 0. */
	private record Found(BlockPos pos, AABB box, Kind kind, int tint, Lid lid) {
	}

	public Storage() {
		super(Categories.RENDER, "Storage", "Highlights chests, shulker boxes and other storage through walls.");
		settings.onAnyChanged(s -> found.remeshAll());
	}

	private Kind kind(SettingGroup group, String name, boolean on, SettingColor.Mode color) {
		BoolSetting enabled = group.bool(name).defaultValue(on).build();
		ColorSetting c = group.color(name + " Color").defaultValue(SettingColor.role(color)).visible(enabled::get).build();
		return new Kind(enabled, c);
	}

	@Override
	protected void onDisable() {
		animating.clear();
		lidEvents.clear();
	}

	@Override
	public String hudInfo() {
		int[] count = {0};
		found.forEach(list -> {
			for (Found f : list) if (f.kind.enabled.get()) count[0]++;
		});
		return count[0] == 0 ? null : String.valueOf(count[0]);
	}

	/**
	 * Calls {@code out} with the box and colour of every storage block and entity being shown (for Tracers). Nothing
	 * while this module is off.
	 */
	public void forEachShown(float tickDelta, BiConsumer<AABB, Integer> out) {
		if (!isEnabled() || !inGame()) return;
		found.forEach(list -> {
			for (Found f : list) if (f.kind.enabled.get()) out.accept(f.box, color(f));
		});
		for (Entity e : mc.level.entitiesForRendering()) {
			Kind k = kindOf(e);
			if (k != null && k.enabled.get()) out.accept(Entities.lerpedBox(e, tickDelta), k.color.argb());
		}
	}

	// ---- per chunk --------------------------------------------------------------------------------------------------

	private List<Found> scan(LevelChunk chunk) {
		List<Found> list = null;
		for (BlockEntity be : chunk.getBlockEntities().values()) {
			Kind k = kindOf(be);
			if (k == null) continue;
			AABB box = boxOf(be);
			if (box == null) continue;
			if (list == null) list = new ArrayList<>();
			Lid lid = be instanceof ChestBlockEntity || be instanceof EnderChestBlockEntity ? Lid.CHEST : be instanceof ShulkerBoxBlockEntity ? Lid.SHULKER : Lid.NONE;
			list.add(new Found(be.getBlockPos().immutable(), box, k, tintOf(be), lid));
		}
		return list;
	}

	private void mesh(List<Found> list, MeshBuilder mesh) {
		mesh.lineWidth(style.lineWidth.getFloat());
		if (merge.get()) {
			List<BlockPos> cells = new ArrayList<>();
			for (Found f : list) if (f.kind.enabled.get()) for (BlockPos cell : cells(f)) cells.add(cell.immutable());
			boolean lines = style.shape.get() != Renderer3D.ShapeMode.FILL, fills = style.shape.get() != Renderer3D.ShapeMode.LINES;
			MergedBoxes.draw(mesh, cells, mergeLookup, g -> fills ? style.fill(g, 1) : 0, g -> lines ? style.line(g, 1) : 0, style.throughWalls.get());
			return;
		}
		for (Found f : list) if (f.kind.enabled.get()) style.draw(mesh, meshBox(f), color(f), 1);
	}

	/** What goes in the mesh: while a lid moves, a chest keeps its body there and a shulker box its base. */
	private AABB meshBox(Found f) {
		if (f.lid == Lid.NONE || !animating.containsKey(f.pos)) return f.box;
		if (f.lid == Lid.SHULKER) return shulkerBase(shulkerFacing(f.pos)).move(f.pos);
		return new AABB(f.box.minX, f.box.minY, f.box.minZ, f.box.maxX, Math.min(f.box.maxY, f.box.minY + BODY_TOP), f.box.maxZ);
	}

	private Direction shulkerFacing(BlockPos pos) {
		BlockState state = mc.level.getBlockState(pos);
		return state.hasProperty(ShulkerBoxBlock.FACING) ? state.getValue(ShulkerBoxBlock.FACING) : Direction.UP;
	}

	/** A shulker box's base within its block: the half at its back. */
	private static AABB shulkerBase(Direction facing) {
		double[] min = {0, 0, 0}, max = {1, 1, 1};
		int i = facing.getAxis().ordinal();
		if (facing.getAxisDirection() == Direction.AxisDirection.POSITIVE) max[i] = SHULKER_BASE;
		else min[i] = 1 - SHULKER_BASE;
		return new AABB(min[0], min[1], min[2], max[0], max[1], max[2]);
	}

	private int color(Found f) {
		return f.kind == shulkers && !dyedShulkers.get() ? f.kind.color.argb() : f.tint != 0 ? f.tint : f.kind.color.argb();
	}

	private Kind kindOf(BlockEntity be) {
		if (be instanceof TrappedChestBlockEntity) return trappedChests;
		if (be instanceof ChestBlockEntity) return chests;
		if (be instanceof EnderChestBlockEntity) return enderChests;
		if (be instanceof BarrelBlockEntity) return barrels;
		if (be instanceof ShulkerBoxBlockEntity) return shulkers;
		if (be instanceof HopperBlockEntity) return hoppers;
		if (be instanceof DispenserBlockEntity) return dispensers;
		if (be instanceof AbstractFurnaceBlockEntity) return furnaces;
		if (be instanceof CrafterBlockEntity || be instanceof BrewingStandBlockEntity) return otherBlocks;
		return null;
	}

	private Kind kindOf(Entity e) {
		if (e instanceof MinecartChest) return chestMinecarts;
		if (e instanceof MinecartHopper) return hopperMinecarts;
		if (e instanceof MinecartFurnace) return furnaceMinecarts;
		if (e instanceof ChestBoat || e instanceof ChestRaft) return chestBoats;
		if (e instanceof AbstractChestedHorse horse && horse.hasChest()) return chestAnimals;
		return null;
	}

	/** A shulker box's dye colour at the kind's alpha, or 0 for an undyed box or anything else. */
	private int tintOf(BlockEntity be) {
		if (!(be instanceof ShulkerBoxBlockEntity box)) return 0;
		DyeColor dye = box.getColor();
		return dye == null ? 0 : ColorUtil.withAlpha(dye.getTextureDiffuseColor(), 255);
	}

	/** The block's outline as one box (both halves of a double chest), or null for the half that isn't drawn. */
	private AABB boxOf(BlockEntity be) {
		BlockPos pos = be.getBlockPos();
		BlockState state = be.getBlockState();
		AABB box = shapeBox(state, pos);
		if (be instanceof ChestBlockEntity && state.hasProperty(ChestBlock.TYPE) && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
			// Draw a double chest once, from its left half.
			if (state.getValue(ChestBlock.TYPE) == ChestType.RIGHT) return null;
			BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
			box = box.minmax(shapeBox(mc.level.getBlockState(other), other));
		}
		return box;
	}

	private AABB shapeBox(BlockState state, BlockPos pos) {
		// A shulker box's shape grows while it's open; its box is the closed cube (an opening lid is drawn live).
		if (state.getBlock() instanceof ShulkerBoxBlock) return new AABB(pos);
		VoxelShape shape = state.getShape(mc.level, pos);
		return shape.isEmpty() ? new AABB(pos) : shape.bounds().move(pos);
	}

	/** The position a container is drawn from: a double chest's right half maps to its left half. */
	private BlockPos drawnFrom(BlockPos pos) {
		BlockState state = mc.level.getBlockState(pos);
		if (state.hasProperty(ChestBlock.TYPE) && state.getValue(ChestBlock.TYPE) == ChestType.RIGHT) return pos.relative(ChestBlock.getConnectedDirection(state));
		return pos;
	}

	// ---- merging ----------------------------------------------------------------------------------------------------

	/** The colour the container at {@code pos} is drawn in, or 0 if none is drawn there. */
	private int colorAt(BlockPos pos) {
		BlockEntity be = mc.level.getBlockEntity(pos);
		Kind k = be == null ? null : kindOf(be);
		if (k == null || !k.enabled.get()) return 0;
		int tint = tintOf(be);
		return k == shulkers && !dyedShulkers.get() ? k.color.argb() : tint != 0 ? tint : k.color.argb();
	}

	/** The blocks a container covers (both halves of a double chest). */
	private static Iterable<BlockPos> cells(Found f) {
		return BlockPos.betweenClosed(BlockPos.containing(f.box.minX, f.box.minY, f.box.minZ), BlockPos.containing(f.box.maxX - 1e-4, f.box.maxY - 1e-4, f.box.maxZ - 1e-4));
	}

	/**
	 * What Merge joins: containers of the same colour, each in its own shape. While a lid moves, a shulker box counts as
	 * its base and a chest as its body.
	 */
	private final MergedBoxes.Lookup mergeLookup = new MergedBoxes.Lookup() {
		@Override
		public int group(BlockPos pos) {
			return colorAt(pos);
		}

		@Override
		public AABB shape(BlockPos pos) {
			BlockState state = mc.level.getBlockState(pos);
			if (state.getBlock() instanceof ShulkerBoxBlock) return animating.containsKey(pos) ? shulkerBase(shulkerFacing(pos)) : null;
			VoxelShape shape = state.getShape(mc.level, pos);
			AABB own = shape.isEmpty() ? new AABB(0, 0, 0, 1, 1, 1) : shape.bounds();
			BlockEntity be = mc.level.getBlockEntity(pos);
			if ((be instanceof ChestBlockEntity || be instanceof EnderChestBlockEntity) && animating.containsKey(drawnFrom(pos))) {
				own = new AABB(own.minX, own.minY, own.minZ, own.maxX, Math.min(own.maxY, BODY_TOP), own.maxZ);
			}
			return own;
		}
	};

	// ---- lids -------------------------------------------------------------------------------------------------------

	/** Chests and shulker boxes tell the client when they open and close: start drawing those live. */
	@Subscribe(packets = ClientboundBlockEventPacket.class)
	private void onPacket(PacketEvent.Receive e) {
		if (!lids.get() || !(e.packet() instanceof ClientboundBlockEventPacket p) || p.getB0() != 1) return;
		if (p.getBlock() instanceof ChestBlock || p.getBlock() instanceof EnderChestBlock || p.getBlock() instanceof ShulkerBoxBlock) lidEvents.add(p.getPos());
	}

	@Subscribe
	private void onWorld(WorldEvent e) {
		animating.clear();
		lidEvents.clear();
	}

	@Subscribe(inGame = true)
	private void onTick(TickEvent.Post e) {
		long now = System.currentTimeMillis();
		for (BlockPos pos; (pos = lidEvents.poll()) != null; ) {
			BlockPos from = drawnFrom(pos);
			// Out of the mesh and drawn live until the lid settles.
			if (animating.put(from, now) == null) remesh(from);
		}
		for (Iterator<Map.Entry<BlockPos, Long>> it = animating.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<BlockPos, Long> en = it.next();
			if (now - en.getValue() < LID_MS || openness(en.getKey(), 1) > 0) continue;
			it.remove();
			remesh(en.getKey());
		}
	}

	/** Re-meshes the chunk of {@code pos}, and the chunks beside it, which a merged shape may reach into. */
	private void remesh(BlockPos pos) {
		found.invalidate(pos);
		if (!merge.get()) return;
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos n = pos.relative(d);
			if ((n.getX() >> 4) != (pos.getX() >> 4) || (n.getZ() >> 4) != (pos.getZ() >> 4)) found.invalidate(n);
		}
	}

	/** How far open (0-1) the chest or shulker box at {@code pos} is; a double chest takes its more open half. */
	private float openness(BlockPos pos, float tickDelta) {
		BlockEntity be = mc.level.getBlockEntity(pos);
		if (be instanceof ShulkerBoxBlockEntity box) return box.getProgress(tickDelta);
		float open = be instanceof ChestBlockEntity chest ? chest.getOpenNess(tickDelta) : be instanceof EnderChestBlockEntity ender ? ender.getOpenNess(tickDelta) : 0;
		BlockState state = mc.level.getBlockState(pos);
		if (be instanceof ChestBlockEntity && state.hasProperty(ChestBlock.TYPE) && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE
			&& mc.level.getBlockEntity(pos.relative(ChestBlock.getConnectedDirection(state))) instanceof ChestBlockEntity other) {
			open = Math.max(open, other.getOpenNess(tickDelta));
		}
		return open;
	}

	/** Draws what the mesh leaves out while a lid moves: a chest's lid, or a shulker box's rising, twisting lid. */
	private void drawLive(ShapeBuilder shapes, Found f, float tickDelta) {
		int color = color(f);
		BlockState state = mc.level.getBlockState(f.pos);
		if (f.lid == Lid.SHULKER) {
			drawShulkerLid(shapes, f.pos, shulkerFacing(f.pos), openness(f.pos, tickDelta), color);
			return;
		}
		if (f.lid != Lid.CHEST) return;
		Direction facing = state.hasProperty(BlockStateProperties.HORIZONTAL_FACING) ? state.getValue(BlockStateProperties.HORIZONTAL_FACING) : Direction.SOUTH;
		float open = openness(f.pos, tickDelta);
		// Vanilla's easing for the lid swing.
		open = 1 - (1 - open) * (1 - open) * (1 - open);
		drawLid(shapes, f.box, facing, open * Math.PI / 2, color);
	}

	/** A shulker box's lid, risen and twisted by {@code progress} (0-1) along {@code facing}, as the model does. */
	private void drawShulkerLid(ShapeBuilder shapes, BlockPos pos, Direction facing, float progress, int color) {
		Vec3 n = Vec3.atLowerCornerOf(facing.getUnitVec3i());
		// Two axes across the facing, and the middle of the box's back face.
		Vec3 u = facing.getAxis() == Direction.Axis.X ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
		Vec3 v = n.cross(u);
		Vec3 back = Vec3.atCenterOf(pos).subtract(n.scale(0.5));
		double rise = SHULKER_RISE * progress, angle = Math.toRadians(270 * progress), cos = Math.cos(angle), sin = Math.sin(angle);
		Vec3[] c = new Vec3[8];
		for (int i = 0; i < 8; i++) {
			double x = (i & 1) == 0 ? -0.5 : 0.5, y = (i & 2) == 0 ? -0.5 : 0.5;
			double h = ((i & 4) == 0 ? SHULKER_LID_FROM : 1) + rise;
			c[i] = back.add(n.scale(h)).add(u.scale(x * cos - y * sin)).add(v.scale(x * sin + y * cos));
		}
		orientedBox(shapes, c, color);
	}

	/** The lid of a chest occupying {@code b}, swung up by {@code angle} about the hinge along its back edge. */
	private void drawLid(ShapeBuilder shapes, AABB b, Direction facing, double angle, int color) {
		boolean alongX = facing.getAxis() == Direction.Axis.Z;
		double lo = alongX ? b.minX : b.minZ, hi = alongX ? b.maxX : b.maxZ;
		int front = facing.getAxisDirection().getStep();
		double back = front > 0 ? (alongX ? b.minZ : b.minX) : (alongX ? b.maxZ : b.maxX);
		double depth = alongX ? b.maxZ - b.minZ : b.maxX - b.minX;
		double hingeY = b.minY + LID_BOTTOM, cos = Math.cos(angle), sin = Math.sin(angle);
		Vec3[] c = new Vec3[8];
		for (int i = 0; i < 8; i++) {
			double side = (i & 1) == 0 ? lo : hi, d = (i & 2) == 0 ? 0 : depth, h = (i & 4) == 0 ? 0 : LID_HEIGHT;
			double up = h * cos + d * sin, dep = d * cos - h * sin;
			double across = back + front * dep;
			c[i] = alongX ? new Vec3(side, hingeY + up, across) : new Vec3(across, hingeY + up, side);
		}
		orientedBox(shapes, c, color);
	}

	/** A box from its 8 corners, indexed by bits (side, depth, height), in this module's style. */
	private void orientedBox(ShapeBuilder shapes, Vec3[] c, int color) {
		int fill = style.fill(color, 1), line = style.line(color, 1);
		boolean walls = style.throughWalls.get();
		if (style.shape.get() != Renderer3D.ShapeMode.LINES) {
			int[][] faces = {{0, 1, 3, 2}, {4, 5, 7, 6}, {0, 1, 5, 4}, {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 3, 7, 5}};
			for (int[] q : faces) shapes.quad(c[q[0]], c[q[1]], c[q[2]], c[q[3]], fill, walls);
		}
		if (style.shape.get() != Renderer3D.ShapeMode.FILL) {
			shapes.lineWidth(style.lineWidth.getFloat());
			// Each edge joins two corners that differ in one bit.
			for (int i = 0; i < 8; i++) {
				for (int bit = 1; bit < 8; bit <<= 1) if ((i & bit) == 0) shapes.line(c[i], c[i | bit], line, walls);
			}
		}
	}

	// ---- per frame --------------------------------------------------------------------------------------------------

	@Subscribe(inGame = true)
	private void onRender3D(Render3DEvent e) {
		ShapeBuilder shapes = e.shapes();
		for (BlockPos pos : animating.keySet()) {
			List<Found> list = found.get(pos);
			if (list == null) continue;
			for (Found f : list) if (f.pos.equals(pos) && f.kind.enabled.get()) drawLive(shapes, f, e.tickDelta());
		}
		if (tracers.get()) {
			found.forEach(list -> {
				for (Found f : list) if (f.kind.enabled.get()) Renderer3D.tracer(f.box.getCenter(), color(f));
			});
		}
		for (Entity entity : mc.level.entitiesForRendering()) {
			Kind k = kindOf(entity);
			if (k == null || !k.enabled.get()) continue;
			AABB box = Entities.lerpedBox(entity, e.tickDelta());
			style.draw(shapes, box, k.color.argb(), 1);
			if (tracers.get()) Renderer3D.tracer(box.getCenter(), k.color.argb());
		}
	}
}
