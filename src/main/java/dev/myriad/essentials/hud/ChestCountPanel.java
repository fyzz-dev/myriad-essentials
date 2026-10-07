package dev.myriad.essentials.hud;

import dev.myriad.api.setting.BoolSetting;
import dev.myriad.api.ui.hud.HudStyle;
import dev.myriad.api.ui.hud.TextHudPanel;
import dev.myriad.api.world.ChunkCache;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * How many chests are loaded around you (trapped chests included), counting a double chest once. Each chunk is counted
 * when it loads or changes, only while the element is on your HUD.
 */
public final class ChestCountPanel extends TextHudPanel {
	private final BoolSetting doublesOnly = sgGeneral.bool("Doubles Only").description("Only count double chests.").build();

	/** Per chunk: single chests, double chests. */
	private final ChunkCache<int[]> counts = ChunkCache.standalone(ChestCountPanel::count).name("Chest Count").build();

	public ChestCountPanel() {
		super("Chest Count", "", HudStyle.Mode.TEXT);
	}

	@Override
	public void onOpen() {
		counts.start();
	}

	@Override
	public void onClose() {
		counts.stop();
	}

	private static int[] count(LevelChunk chunk) {
		int singles = 0, doubles = 0;
		for (BlockEntity be : chunk.getBlockEntities().values()) {
			if (!(be instanceof ChestBlockEntity)) continue;
			BlockState s = be.getBlockState();
			ChestType type = s.hasProperty(ChestBlock.TYPE) ? s.getValue(ChestBlock.TYPE) : ChestType.SINGLE;
			if (type == ChestType.SINGLE) singles++;
			else if (type == ChestType.LEFT) doubles++;
		}
		return singles == 0 && doubles == 0 ? null : new int[]{singles, doubles};
	}

	@Override
	protected void lines(Lines out) {
		int[] total = {0};
		counts.forEach(c -> total[0] += doublesOnly.get() ? c[1] : c[0] + c[1]);
		out.add("Chests", String.valueOf(preview() ? 12 : total[0]));
	}
}
