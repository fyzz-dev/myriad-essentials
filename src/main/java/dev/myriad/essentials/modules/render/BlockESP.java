package dev.myriad.essentials.modules.render;

import dev.myriad.api.event.Subscribe;
import dev.myriad.api.event.events.Render3DEvent;
import dev.myriad.api.module.Categories;
import dev.myriad.api.module.Module;
import dev.myriad.api.render.MergedBoxes;
import dev.myriad.api.render.MeshBuilder;
import dev.myriad.api.render.Renderer3D;
import dev.myriad.api.setting.BoolSetting;
import dev.myriad.api.setting.ColorSetting;
import dev.myriad.api.setting.IntSetting;
import dev.myriad.api.setting.RegistryListSetting;
import dev.myriad.api.setting.SettingColor;
import dev.myriad.api.world.BlockScan;
import dev.myriad.api.world.ChunkCache;
import dev.myriad.api.render.BoxStyle;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Highlights the blocks you pick (spawners, beds, portals, an ore...) through walls, in each block's own colour or one
 * of your choosing. Chunks are searched with {@link BlockScan}, which skips sections that can't hold a match, when they
 * load and again only when they change; the boxes stay on the GPU.
 */
public class BlockESP extends Module {
	/** Past this many in one chunk the rest are skipped, so picking something common can't swamp the renderer. */
	private static final int MAX_PER_CHUNK = 2048;

	private final RegistryListSetting<Block> blocks = sgGeneral.blocks("Blocks").description("The blocks to highlight.")
		.defaultValue(Blocks.SPAWNER, Blocks.TRIAL_SPAWNER).build();
	private final IntSetting range = sgGeneral.intSetting("Range").description("Chunks around you.").defaultValue(8).range(1, 32).build();
	private final BoolSetting blockColors = sgGeneral.bool("Block Colors").description("Draw each block in its own colour (as on a map).").defaultValue(true).build();
	private final ColorSetting color = sgGeneral.color("Color").defaultValue(SettingColor.role(SettingColor.Mode.ACCENT)).visible(() -> !blockColors.get()).build();
	private final BoolSetting merge = sgGeneral.bool("Merge").description("Draw touching blocks of the same colour as one outline (portals, veins, beds).").defaultValue(true).build();
	private final BoolSetting tracers = sgGeneral.bool("Tracers").description("A line from the crosshair to each one.").build();
	private final BoxStyle style = new BoxStyle(sgGeneral);

	private final ChunkCache<List<BlockPos>> found = ChunkCache.of(this, this::scan)
		.range(range::get)
		.mesh(this::mesh)
		.neighbours()
		.build();

	public BlockESP() {
		super(Categories.RENDER, "Blocks", "Highlights the blocks you pick, like spawners or beds, through walls.");
		// A different list needs a new search; anything else only re-meshes.
		settings.onAnyChanged(s -> {
			if (s == blocks) found.invalidateAll();
			else found.remeshAll();
		});
	}

	@Override
	public String hudInfo() {
		int[] count = {0};
		found.forEach(list -> count[0] += list.size());
		return count[0] == 0 ? null : String.valueOf(count[0]);
	}

	private List<BlockPos> scan(LevelChunk chunk) {
		Set<Block> wanted = blocks.get();
		if (wanted.isEmpty()) return null;
		List<BlockPos> list = new ArrayList<>();
		BlockScan.forEach(chunk, state -> wanted.contains(state.getBlock()), (pos, state) -> {
			if (list.size() < MAX_PER_CHUNK) list.add(pos.immutable());
		});
		return list.isEmpty() ? null : list;
	}

	private void mesh(List<BlockPos> list, MeshBuilder mesh) {
		mesh.lineWidth(style.lineWidth.getFloat());
		if (merge.get()) {
			boolean lines = style.shape.get() != Renderer3D.ShapeMode.FILL, fills = style.shape.get() != Renderer3D.ShapeMode.LINES;
			MergedBoxes.draw(mesh, list, lookup, g -> fills ? style.fill(g, 1) : 0, g -> lines ? style.line(g, 1) : 0, style.throughWalls.get());
			return;
		}
		for (BlockPos pos : list) {
			BlockState state = mc.level.getBlockState(pos);
			style.draw(mesh, shape(state, pos).move(pos), colorOf(state, pos), 1);
		}
	}

	private final MergedBoxes.Lookup lookup = new MergedBoxes.Lookup() {
		@Override
		public int group(BlockPos pos) {
			BlockState state = mc.level.getBlockState(pos);
			return blocks.get().contains(state.getBlock()) ? colorOf(state, pos) : 0;
		}

		@Override
		public AABB shape(BlockPos pos) {
			return BlockESP.shape(mc.level.getBlockState(pos), pos);
		}
	};

	private static AABB shape(BlockState state, BlockPos pos) {
		VoxelShape shape = state.getShape(mc.level, pos);
		return shape.isEmpty() ? new AABB(0, 0, 0, 1, 1, 1) : shape.bounds();
	}

	private int colorOf(BlockState state, BlockPos pos) {
		if (blockColors.get()) {
			MapColor map = state.getMapColor(mc.level, pos);
			if (map != MapColor.NONE) return 0xFF000000 | map.col;
		}
		return color.argb();
	}

	@Subscribe
	private void onRender(Render3DEvent e) {
		if (!tracers.get() || !inGame()) return;
		found.forEach(list -> {
			for (BlockPos pos : list) Renderer3D.tracer(Vec3.atCenterOf(pos), colorOf(mc.level.getBlockState(pos), pos));
		});
	}
}
