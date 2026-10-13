package com.silo.client.screen;

import com.silo.client.SiloClient;
import com.silo.client.plan.PlanParseException;
import com.silo.client.plan.PlanParser;
import com.silo.client.plan.PlanRunner;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;

public final class PlanScreen extends Screen {
	private final PlanRunner runner;
	private MultiLineEditBox editor;
	private String errorMessage;

	public PlanScreen(PlanRunner runner) {
		super(Component.literal("Silo Plan"));
		this.runner = runner;
	}

	@Override
	protected void init() {
		int panelWidth = Math.min(520, width - 40);
		int left = (width - panelWidth) / 2;
		int editorTop = 62;
		int buttonY = height - 32;
		int editorHeight = Math.max(70, buttonY - editorTop - 18);

		editor = addRenderableWidget(MultiLineEditBox.builder()
				.setX(left)
				.setY(editorTop)
				.setPlaceholder(Component.literal(":Action_1\n    look [180 8]\n        pers [30]\n        rand [12] [2] [100]\n:Action_2\n    hold left\n        CordA [34 64 -12]"))
				.setShowDecorations(true)
				.build(font, panelWidth, editorHeight, Component.literal("Plan actions")));
		editor.setCharacterLimit(PlanParser.MAX_PLAN_LENGTH);
		editor.setLineLimit(PlanParser.MAX_PLAN_LENGTH);
		editor.setValue(runner.planText());

		addRenderableWidget(Button.builder(Component.literal("Save plan"), button -> savePlan())
				.bounds(left, buttonY, 110, 20)
				.build());
		addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> onClose())
				.bounds(width - left - 110, buttonY, 110, 20)
				.build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, "Silo Plan", width / 2, 24, 0xFFFFFF);
		graphics.centeredText(font, "Groups run in order; use /silo run to execute the saved plan.", width / 2, 43, 0xA0A0A0);
		if (errorMessage != null) {
			graphics.centeredText(font, errorMessage, width / 2, height - 53, 0xFF5555);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private void savePlan() {
		try {
			SiloClient.savePlan(editor.getValue());
			SiloClient.LOGGER.info("Closed plan editor after saving.");
			minecraft.setScreenAndShow(null);
		} catch (PlanParseException | IOException | IllegalStateException exception) {
			SiloClient.LOGGER.warn("Could not save Silo plan: {}", exception.getMessage());
			errorMessage = exception.getMessage();
		}
	}
}
