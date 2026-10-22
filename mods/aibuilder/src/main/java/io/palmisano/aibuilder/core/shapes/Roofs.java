package io.palmisano.aibuilder.core.shapes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.palmisano.aibuilder.core.blocks.BlockSpec;
import io.palmisano.aibuilder.core.frame.Pos;

/**
 * Roofs from stairs and slabs, starting at the box's lowest y over its footprint grown by the overhang. Stairs
 * climb toward their facing, so each slope faces inward and up toward the ridge; stair corners are left to the
 * placement engine's shape pass.
 */
public final class Roofs {
	private Roofs() {
	}

	/** {@code slab}, {@code ridge} and {@code gable} may be null; a null ridge runs along the longer side (X on a tie). */
	public static List<LocalBlock> roof(Box box, RoofStyle style, BlockSpec stairs, BlockSpec slab, Axis ridge, int overhang, BlockSpec gable) {
		if (overhang < 0 || overhang > 2) {
			throw new IllegalArgumentException("overhang must be 0, 1 or 2");
		}
		Footprint f = new Footprint(box, overhang);
		return switch (style) {
			case GABLE -> gable(f, stairs, slab, ridge != null ? ridge : longerSide(box), gable);
			case HIP -> hip(f, stairs, slab);
			case FLAT -> flat(f, slab);
		};
	}

	private record Footprint(Box box, int x0, int x1, int z0, int z1, int y) {
		Footprint(Box box, int overhang) {
			this(box, box.min().x() - overhang, box.max().x() + overhang, box.min().z() - overhang, box.max().z() + overhang, box.min().y());
		}
	}

	private static Axis longerSide(Box box) {
		return box.max().x() - box.min().x() >= box.max().z() - box.min().z() ? Axis.X : Axis.Z;
	}

	private static List<LocalBlock> gable(Footprint f, BlockSpec stairs, BlockSpec slab, Axis ridge, BlockSpec gable) {
		if (ridge == Axis.Y) {
			throw new IllegalArgumentException("a roof's ridge runs along x or z");
		}
		boolean ridgeAlongZ = ridge == Axis.Z;
		// Work in (across, along): across is the sloped direction, along runs parallel to the ridge.
		int acrossLow = ridgeAlongZ ? f.x0() : f.z0();
		int acrossHigh = ridgeAlongZ ? f.x1() : f.z1();
		int alongLow = ridgeAlongZ ? f.z0() : f.x0();
		int alongHigh = ridgeAlongZ ? f.z1() : f.x1();
		int boxAcrossLow = ridgeAlongZ ? f.box().min().x() : f.box().min().z();
		int boxAcrossHigh = ridgeAlongZ ? f.box().max().x() : f.box().max().z();
		int[] ends = ridgeAlongZ ? new int[] {f.box().min().z(), f.box().max().z()} : new int[] {f.box().min().x(), f.box().max().x()};
		String upFromLow = ridgeAlongZ ? "right" : "forward";
		String upFromHigh = ridgeAlongZ ? "left" : "back";

		List<LocalBlock> out = new ArrayList<>();
		for (int k = 0; acrossLow + k <= acrossHigh - k; k++) {
			int a = acrossLow + k;
			int b = acrossHigh - k;
			int y = f.y() + k;
			if (a == b) {
				for (int along = alongLow; along <= alongHigh; along++) {
					out.add(block(ridgeAlongZ, a, y, along, slab(slab, "an odd-width gable roof needs a slab for its ridge")));
				}
				break;
			}
			for (int along = alongLow; along <= alongHigh; along++) {
				out.add(block(ridgeAlongZ, a, y, along, stair(stairs, upFromLow)));
				out.add(block(ridgeAlongZ, b, y, along, stair(stairs, upFromHigh)));
			}
			if (gable != null) {
				for (int across = Math.max(a + 1, boxAcrossLow); across <= Math.min(b - 1, boxAcrossHigh); across++) {
					for (int end : ends) {
						out.add(block(ridgeAlongZ, across, y, end, gable));
					}
				}
			}
		}
		return out;
	}

	private static List<LocalBlock> hip(Footprint f, BlockSpec stairs, BlockSpec slab) {
		List<LocalBlock> out = new ArrayList<>();
		for (int k = 0; ; k++) {
			int x0 = f.x0() + k;
			int x1 = f.x1() - k;
			int z0 = f.z0() + k;
			int z1 = f.z1() - k;
			int y = f.y() + k;
			if (x0 > x1 || z0 > z1) {
				return out;
			}
			if (x0 == x1 || z0 == z1) {
				BlockSpec top = slab(slab, "this hip roof ends in a ridge line, which needs a slab");
				for (int x = x0; x <= x1; x++) {
					for (int z = z0; z <= z1; z++) {
						out.add(new LocalBlock(new Pos(x, y, z), top));
					}
				}
				return out;
			}
			for (int x = x0; x <= x1; x++) {
				out.add(new LocalBlock(new Pos(x, y, z0), stair(stairs, "forward")));
				out.add(new LocalBlock(new Pos(x, y, z1), stair(stairs, "back")));
			}
			for (int z = z0 + 1; z < z1; z++) {
				out.add(new LocalBlock(new Pos(x0, y, z), stair(stairs, "right")));
				out.add(new LocalBlock(new Pos(x1, y, z), stair(stairs, "left")));
			}
		}
	}

	private static List<LocalBlock> flat(Footprint f, BlockSpec slab) {
		BlockSpec top = slab(slab, "a flat roof needs a slab");
		List<LocalBlock> out = new ArrayList<>();
		for (int x = f.x0(); x <= f.x1(); x++) {
			for (int z = f.z0(); z <= f.z1(); z++) {
				out.add(new LocalBlock(new Pos(x, f.y(), z), top));
			}
		}
		return out;
	}

	private static LocalBlock block(boolean ridgeAlongZ, int across, int y, int along, BlockSpec block) {
		return new LocalBlock(ridgeAlongZ ? new Pos(across, y, along) : new Pos(along, y, across), block);
	}

	private static BlockSpec stair(BlockSpec stairs, String facing) {
		return new BlockSpec(stairs.id(), Map.of("facing", facing, "half", "bottom"));
	}

	private static BlockSpec slab(BlockSpec slab, String missing) {
		if (slab == null) {
			throw new IllegalArgumentException(missing);
		}
		return new BlockSpec(slab.id(), Map.of("type", "bottom"));
	}
}
