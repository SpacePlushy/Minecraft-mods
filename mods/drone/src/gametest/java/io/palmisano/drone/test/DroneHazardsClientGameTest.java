package io.palmisano.drone.test;

import static io.palmisano.drone.test.GameTestSupport.check;

import io.palmisano.drone.entity.DroneEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.phys.AABB;

/**
 * Spec §8 "lava: flies through like air" and §2 "never breaks". Holds because DroneEntity never
 * calls applyEffectsFromBlocks() (boats do): adding that call makes a vehicle in lava break in 2 ticks.
 */
@SuppressWarnings("UnstableApiUsage")
public class DroneHazardsClientGameTest implements FabricClientGameTest {
	private static final AABB LAVA_SPOT = new AABB(-2, 99, 4, 3, 102, 9);

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = GameTestSupport.flatArena(ctx)) {
			sp.getServer().runCommand("setblock 0 100 6 minecraft:lava");
			sp.getServer().runCommand("summon drone:drone 0.5 100 6.5"); // sits in the lava
			ctx.waitTicks(40);

			String result = sp.getServer().computeOnServer(s -> {
				var drones = s.overworld().getEntitiesOfClass(DroneEntity.class, LAVA_SPOT);
				return drones.size() + (drones.size() == 1 && drones.getFirst().isOnFire() ? " burning" : "");
			});
			check(result.equals("1"), "lava hurt the drone: drones left " + result);
		}
	}
}
