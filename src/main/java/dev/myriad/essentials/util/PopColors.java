package dev.myriad.essentials.util;

/** The colour scale Essentials uses for totem pop counts. */
public final class PopColors {
	private PopColors() {
	}

	/** Green for few pops, through yellow, to red for many. */
	public static int of(int pops) {
		return switch (Math.min(pops, 6)) {
			case 0, 1 -> 0xFF55FF55;
			case 2 -> 0xFFAAFF55;
			case 3 -> 0xFFFFFF55;
			case 4 -> 0xFFFFAA55;
			case 5 -> 0xFFFF7755;
			default -> 0xFFFF5555;
		};
	}
}
