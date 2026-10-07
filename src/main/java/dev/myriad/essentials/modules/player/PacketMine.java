package dev.myriad.essentials.modules.player;

import dev.myriad.api.Myriad;
import dev.myriad.api.event.Subscribe;
import dev.myriad.api.event.events.BlockBreakEvent;
import dev.myriad.api.event.events.Render3DEvent;
import dev.myriad.api.event.events.TickEvent;
import dev.myriad.api.module.Categories;
import dev.myriad.api.module.Module;
import dev.myriad.api.render.Renderer3D;
import dev.myriad.api.service.Breaking;
import dev.myriad.api.setting.BoolSetting;
import dev.myriad.api.setting.ColorSetting;
import dev.myriad.api.setting.SettingColor;
import dev.myriad.api.util.ColorUtil;
import dev.myriad.api.util.Reach;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Hit a block once and it's mined for you with packets, through the shared breaking service, while your hand stays
 * free: the best tool is swapped in only to start and finish (brought into the hotbar from your inventory when you
 * stand still). Any block your crosshair reaches can be picked, measured as the server does, to the block's nearest
 * point; one you walk out of reach of is dropped. Hold the button and drag to line up every block you pass over; your
 * hand swings while you hold, and not otherwise (the swing Grim expects with each break packet is sent unseen).
 * Timed for Grim (2b2t):
 * vanilla's full break time from the start, judged by the tool you start with, and vanilla's pause between blocks, so
 * finishes aren't refused. The block you mined last can be re-broken with a single packet when something is placed
 * back there, once enough time has passed for that block.
 * <p>
 * Fast is the decoy trick for older Grim builds (see {@code Breaking.Mode.FAST_GRIM}; current Grim catches it): blocks
 * finish at 70% of the time, with decoy start packets that kept older Grim from noticing, and Double Break mines a
 * second block meanwhile.
 */
public class PacketMine extends Module {
	private final BoolSetting fast = sgGeneral.bool("Fast").description("Always finish at 70% of the time, as 2b2t clients do: once Grim's allowance "
		+ "for early finishes runs out, a decoy start far above hides the speed (needs ViaVersion, as 2b2t has). Each decoy is an AirLiquidBreak flag.").build();
	private final BoolSetting doubleBreak = sgGeneral.bool("Double Break").description("Mine a second block while the server finishes the first.")
		.defaultValue(true).visible(fast::get).build();
	private final BoolSetting queue = sgGeneral.bool("Queue").description("A new click adds to the line, instead of replacing the blocks still waiting. "
		+ "Dragging while you hold always adds.").build();
	private final BoolSetting autoRebreak = sgGeneral.bool("Auto Rebreak").description("Break the last mined block again as soon as something placed there can be.").build();
	private final BoolSetting rotate = sgGeneral.bool("Rotate").description("Face the block when starting and finishing, for servers that check.").build();
	private final ColorSetting color = sgGeneral.color("Color").defaultValue(SettingColor.role(SettingColor.Mode.ACCENT)).build();

	/** Positions this module asked to break that haven't finished. */
	private final List<BlockPos> mining = new ArrayList<>();
	private BlockPos last;

	public PacketMine() {
		super(Categories.PLAYER, "Packet Mine", "Mines the blocks you hit for you, with your hand free.");
	}

	@Override
	protected void onDisable() {
		mining.clear();
		last = null;
	}

	@Override
	public String hudInfo() {
		return mining.isEmpty() ? null : String.valueOf(mining.size());
	}

	/** A click: a new selection (replacing what's waiting, unless Queue is on). Vanilla swings for the click. */
	@Subscribe
	private void onStart(BlockBreakEvent.Start e) {
		if (!inGame() || mc.player.isCreative()) return;
		e.cancel();
		select(e.pos(), !queue.get());
	}

	/**
	 * Holding the button: vanilla would keep mining the block under the crosshair; instead every block it passes over
	 * joins the line, and the hand swings as long as you hold.
	 */
	@Subscribe
	private void onProgress(BlockBreakEvent.Progress e) {
		if (!inGame() || mc.player.isCreative()) return;
		e.cancel();
		select(e.pos(), false);
		mc.player.swing(InteractionHand.MAIN_HAND);
	}

	private void select(BlockPos pos, boolean replaceWaiting) {
		if (mining.contains(pos)) return;
		if (replaceWaiting) {
			// Drop what's still waiting its turn; blocks already being mined carry on.
			for (BlockPos p : new ArrayList<>(mining)) {
				if (Myriad.breaking().progress(p) == 0) {
					Myriad.breaking().cancel(p);
					mining.remove(p);
				}
			}
		}
		Breaking.Attempt a = Myriad.breaking().breakBlock(this, pos, 0, options());
		if (!a.accepted()) return;
		BlockPos key = a.pos();
		mining.add(key);
		a.result().thenAccept(broken -> {
			mining.remove(key);
			if (broken) last = key;
		});
	}

	private Breaking.Options options() {
		Breaking.Mode mode = fast.get() ? Breaking.Mode.FAST_GRIM : Breaking.Mode.PACKET;
		// No swings of its own: your hand only moves while you hold the button.
		return Breaking.Options.PACKET.withMode(mode).withRotate(rotate.get()).withSwing(false).withRange(Reach.blockRange()).withDoubleBreak(fast.get() && doubleBreak.get());
	}

	@Subscribe(inGame = true)
	private void onTick(TickEvent.Post e) {
		mining.removeIf(p -> !Myriad.breaking().isPending(p));
		if (autoRebreak.get() && last != null && Myriad.breaking().canRebreak(last)) select(last, false);
	}

	@Subscribe(inGame = true)
	private void onRender(Render3DEvent e) {
		int c = color.argb();
		for (BlockPos pos : mining) {
			float progress = Myriad.breaking().progress(pos);
			if (progress == 0) {
				// Waiting its turn: the block outlined, dimmer than the one being mined.
				Renderer3D.box(new AABB(pos), ColorUtil.withAlpha(c, 18), ColorUtil.withAlpha(c, 90), Renderer3D.ShapeMode.BOTH, false);
				continue;
			}
			// Being mined: grows from the centre as the block breaks, inside a full outline.
			double half = 0.1 + 0.4 * progress;
			Vec3 center = Vec3.atCenterOf(pos);
			AABB box = new AABB(center.subtract(half, half, half), center.add(half, half, half));
			Renderer3D.box(box, ColorUtil.withAlpha(c, 60), c, Renderer3D.ShapeMode.BOTH, false);
			Renderer3D.box(new AABB(pos), 0, ColorUtil.withAlpha(c, 160), Renderer3D.ShapeMode.LINES, false);
		}
		if (autoRebreak.get() && last != null && !mining.contains(last)) {
			Renderer3D.box(new AABB(last), 0, ColorUtil.withAlpha(c, 120), Renderer3D.ShapeMode.LINES, false);
		}
	}
}
