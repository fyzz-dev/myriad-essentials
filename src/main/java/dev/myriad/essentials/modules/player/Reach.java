package dev.myriad.essentials.modules.player;

import dev.myriad.api.module.Categories;
import dev.myriad.api.module.Module;
import dev.myriad.api.module.Modules;
import dev.myriad.api.setting.DoubleSetting;

/**
 * Sets how far you can place and break blocks and hit entities. Everything that uses your reach follows it: the
 * crosshair, Packet Mine, Scaffold and the other services. Servers check reach themselves: vanilla ones allow up to a
 * block more for blocks (measured to the nearest point of the block), Grim (2b2t) allows nothing past vanilla, and
 * further only works where they don't check. It starts at vanilla's values, so turning it on changes nothing until
 * you raise them. Applied by this addon's PlayerEntityMixin.
 */
public class Reach extends Module {
	private final DoubleSetting blockRange = sgGeneral.doubleSetting("Block Range")
		.description("Blocks you can place, break and use, to their nearest point (vanilla 4.5; Grim flags more).")
		.defaultValue(4.5).range(1, 10).decimals(2).build();
	private final DoubleSetting entityRange = sgGeneral.doubleSetting("Entity Range")
		.description("Entities you can hit and use, to their hitbox (vanilla 3; Grim flags more).")
		.defaultValue(3).range(1, 10).decimals(2).build();

	public Reach() {
		super(Categories.PLAYER, "Reach", "Place blocks and hit entities from further away.");
	}

	@Override
	public String hudInfo() {
		return String.format("%.1f", entityRange.get());
	}

	/** The block range to use instead of {@code original}. */
	public static double blockRange(double original) {
		Reach m = Modules.active(Reach.class);
		return m == null ? original : m.blockRange.get();
	}

	/** The entity range to use instead of {@code original}. */
	public static double entityRange(double original) {
		Reach m = Modules.active(Reach.class);
		return m == null ? original : m.entityRange.get();
	}
}
