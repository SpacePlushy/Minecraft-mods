package io.palmisano.aibuilder.test;

import java.util.List;
import java.util.Optional;

import io.palmisano.aibuilder.core.frame.LocalFrame;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.gadgets.Display;
import io.palmisano.aibuilder.core.gadgets.GadgetUse;
import io.palmisano.aibuilder.core.request.BuildSession;
import io.palmisano.aibuilder.world.Positions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A calculator a build made of gadgets, found from its session (the M9 plan's live check): its first two lever rows and
 * its number, in the build's frame. Sets the levers to two numbers and reads the number back, as a player would.
 */
record FoundCalculator(LocalFrame frame, GadgetUse a, GadgetUse b, GadgetUse number, int settle) {
	/** The session's calculator: two lever rows of 4 and a number; settling as long as its adders and number say, and a margin for wires. */
	static Optional<FoundCalculator> of(BuildSession session) {
		List<GadgetUse> rows = session.gadgets().stream().filter(g -> g.kind().equals("lever_row") && g.placed().marks().size() == 4).toList();
		Optional<GadgetUse> number = session.gadgets().stream().filter(g -> g.kind().equals("number")).findFirst();
		if (rows.size() < 2 || number.isEmpty()) {
			return Optional.empty();
		}
		int settle = 60;
		for (GadgetUse g : session.gadgets()) {
			if (g.kind().equals("adder") || g.kind().equals("number")) {
				settle += io.palmisano.aibuilder.core.gadgets.Gadgets.make(g.kind(), com.google.gson.JsonParser.parseString(g.options()).getAsJsonObject())
						.settleTicks();
			}
		}
		return Optional.of(new FoundCalculator(new LocalFrame(session.origin(), session.facing()), rows.get(0), rows.get(1), number.get(), settle));
	}

	/** Its levers set to {@code x} on the first row and {@code y} on the second (bit i on lever i), pulled only where they differ. */
	void set(MinecraftServer server, int x, int y) {
		pull(server, a, x);
		pull(server, b, y);
	}

	private void pull(MinecraftServer server, GadgetUse row, int value) {
		int bit = 0;
		for (Pos lever : row.placed().marks().values()) {
			BlockPos pos = Positions.toBlockPos(frame.toWorld(lever));
			BlockState state = server.overworld().getBlockState(pos);
			if (state.getBlock() instanceof LeverBlock && state.getValue(LeverBlock.POWERED) != ((value >> bit & 1) == 1)) {
				((LeverBlock) Blocks.LEVER).pull(state, server.overworld(), pos, null);
			}
			bit++;
		}
	}

	/** What its number shows. */
	String shows(MinecraftServer server) {
		return Display.number(number.placed().marks(), p -> {
			BlockState lamp = server.overworld().getBlockState(Positions.toBlockPos(frame.toWorld(p)));
			return lamp.is(Blocks.REDSTONE_LAMP) && lamp.getValue(RedstoneLampBlock.LIT);
		});
	}

	/** A spot 16 in front of the number's faces and a little above them, and the faces' middle: where to look from, and at. */
	Pos[] view() {
		Pos low = number.placed().marks().get("ones segment d");
		Pos high = number.placed().marks().getOrDefault("tens segment a", number.placed().marks().get("ones segment a"));
		Pos middle = new Pos((low.x() + high.x()) / 2, (low.y() + high.y()) / 2, (low.z() + high.z()) / 2);
		Pos out = switch (number.facing()) {
			case "right" -> new Pos(-16, 3, 0);
			case "back" -> new Pos(0, 3, 16);
			case "left" -> new Pos(16, 3, 0);
			default -> new Pos(0, 3, -16);
		};
		return new Pos[] {new Pos(middle.x() + out.x(), middle.y() + out.y(), middle.z() + out.z()), middle};
	}
}
