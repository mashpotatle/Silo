package com.silo.client;

import com.mojang.brigadier.CommandDispatcher;
import com.silo.client.plan.PlanParseException;
import com.silo.client.plan.PlanRunner;
import com.silo.client.screen.PlanScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.network.chat.Component;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SiloClient implements ClientModInitializer {
	public static final String MOD_ID = "silo";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final PlanRunner PLAN_RUNNER = new PlanRunner();
	private static final Path PLAN_FILE = FabricLoader.getInstance().getConfigDir().resolve("silo-plan.txt");
	private boolean planEditorRequested;

	@Override
	public void onInitializeClient() {
		LOGGER.info("Initializing Silo client mod.");
		loadSavedPlan();
		ClientCommandRegistrationCallback.EVENT.register(this::registerCommands);
		ClientTickEvents.START_CLIENT_TICK.register(PLAN_RUNNER::tick);
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (planEditorRequested) {
				planEditorRequested = false;
				LOGGER.info("Opening the Silo plan editor after command handling.");
				client.setScreenAndShow(new PlanScreen(PLAN_RUNNER));
			}
		});
	}

	private void registerCommands(
			CommandDispatcher<FabricClientCommandSource> dispatcher,
			CommandBuildContext registryAccess
	) {
		dispatcher.register(ClientCommands.literal("silo")
				.then(ClientCommands.literal("plan").executes(context -> {
					planEditorRequested = true;
					LOGGER.info("Queued the Silo plan editor to open after the chat command closes.");
					return 1;
				}))
				.then(ClientCommands.literal("run").executes(context -> {
					try {
						PLAN_RUNNER.start(context.getSource());
						return 1;
					} catch (PlanParseException | IllegalStateException exception) {
						LOGGER.warn("Could not start Silo plan: {}", exception.getMessage());
						context.getSource().sendError(Component.literal(exception.getMessage()));
						return 0;
					}
				}))
				.then(ClientCommands.literal("stop").executes(context -> {
					if (PLAN_RUNNER.stop()) {
						LOGGER.info("Silo plan stopped by command.");
						context.getSource().sendFeedback(Component.literal("Silo plan stopped."));
					} else {
						LOGGER.info("Stop requested while no Silo plan was running.");
						context.getSource().sendFeedback(Component.literal("No Silo plan is running."));
					}
					return 1;
				}))
				.then(ClientCommands.literal("status").executes(context -> {
					LOGGER.info("Silo status requested: {}", PLAN_RUNNER.status());
					context.getSource().sendFeedback(Component.literal(PLAN_RUNNER.status()));
					return 1;
				})));
	}

	public static void savePlan(String text) throws IOException, PlanParseException {
		PLAN_RUNNER.validate(text);
		if (PLAN_RUNNER.isRunning()) {
			throw new IllegalStateException("Stop the current Silo plan before saving a new one.");
		}
		Files.createDirectories(PLAN_FILE.getParent());
		Files.writeString(PLAN_FILE, text);
		PLAN_RUNNER.setPlan(text);
		LOGGER.info("Saved Silo plan to {}.", PLAN_FILE);
	}

	private static void loadSavedPlan() {
		if (!Files.exists(PLAN_FILE)) {
			return;
		}
		try {
			PLAN_RUNNER.setPlan(Files.readString(PLAN_FILE));
			LOGGER.info("Loaded saved Silo plan from {}.", PLAN_FILE);
		} catch (IOException | PlanParseException exception) {
			LOGGER.error("Could not load saved Silo plan from {}", PLAN_FILE, exception);
		}
	}
}
