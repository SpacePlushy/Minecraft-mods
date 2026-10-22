package io.palmisano.aibuilder;

import io.palmisano.aibuilder.net.BuildModePayload;
import io.palmisano.aibuilder.net.BuildPromptPayload;
import io.palmisano.aibuilder.net.BuildStatusPayload;
import io.palmisano.aibuilder.world.BuildTickets;
import io.palmisano.aibuilder.world.JobScheduler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AiBuilderMod implements ModInitializer {
	public static final String MOD_ID = "aibuilder";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.serverboundPlay().register(BuildPromptPayload.TYPE, BuildPromptPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(BuildModePayload.Query.TYPE, BuildModePayload.Query.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(BuildStatusPayload.TYPE, BuildStatusPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(BuildModePayload.TYPE, BuildModePayload.CODEC);
		// Fabric runs play payload handlers on the server thread.
		ServerPlayNetworking.registerGlobalReceiver(BuildPromptPayload.TYPE, (payload, context) -> BuildManager
				.of(context.player().level().getServer()).submit(context.player(), payload.prompt(), payload.size(), payload.forceNew()));
		ServerPlayNetworking.registerGlobalReceiver(BuildModePayload.Query.TYPE, (payload, context) -> context.responseSender()
				.sendPacket(new BuildModePayload(BuildManager.of(context.player().level().getServer()).refining(context.player()))));
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> AiBuildCommand.register(dispatcher));
		BuildTickets.register();
		JobScheduler.register();
		BuildManager.register();
		LOG.info("AI Builder loaded");
	}
}
