package com.silo.client.plan;

import com.silo.client.SiloClient;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static com.silo.client.plan.PlanAction.Button;

public final class PlanRunner {
	private static final Button[] CONTROLLED_BUTTONS = Button.values();

	private static final class RunningLook {
		private final PlanAction.PersistentLook action;
		private float segmentStartYaw;
		private float segmentStartPitch;
		private float targetYaw;
		private float targetPitch;
		private int segmentElapsedTicks;

		private RunningLook(PlanAction.PersistentLook action, float yaw, float pitch) {
			this.action = action;
			rebase(yaw, pitch);
		}

		private void rebase(float yaw, float pitch) {
			segmentStartYaw = yaw;
			segmentStartPitch = pitch;
			segmentElapsedTicks = 0;
			chooseTarget();
		}

		private void chooseTarget() {
			if (action.randomLook().isEmpty()) {
				targetYaw = action.yaw();
				targetPitch = action.pitch();
				return;
			}
			PlanAction.RandomLook random = action.randomLook().orElseThrow();
			targetYaw = action.yaw() + randomOffset(random.yawRange());
			targetPitch = clampPitch(action.pitch() + randomOffset(random.pitchRange()));
		}

		private void tick(LocalPlayer player) {
			if (action.randomLook().isEmpty()) {
				player.setYRot(action.yaw());
				player.setXRot(action.pitch());
				return;
			}

			PlanAction.RandomLook random = action.randomLook().orElseThrow();
			if (segmentElapsedTicks >= random.speedTicks()) {
				segmentStartYaw = targetYaw;
				segmentStartPitch = targetPitch;
				segmentElapsedTicks = 0;
				chooseTarget();
			}
			segmentElapsedTicks++;
			float progress = (float) segmentElapsedTicks / random.speedTicks();
			float easedProgress = progress * progress * (3 - 2 * progress);
			float yaw = segmentStartYaw + shortestAngle(segmentStartYaw, targetYaw) * easedProgress;
			float pitch = segmentStartPitch + (targetPitch - segmentStartPitch) * easedProgress;
			player.setYRot(yaw);
			player.setXRot(clampPitch(pitch));
		}

		private static float randomOffset(float range) {
			return range == 0 ? 0 : (float) ThreadLocalRandom.current().nextDouble(-range, range);
		}

		private static float shortestAngle(float from, float to) {
			float difference = (to - from) % 360;
			if (difference >= 180) {
				difference -= 360;
			} else if (difference < -180) {
				difference += 360;
			}
			return difference;
		}

		private static float clampPitch(float pitch) {
			return Math.max(-90, Math.min(90, pitch));
		}
	}

	private String planText = "";
	private List<PlanAction> actions = List.of();
	private Map<KeyMapping, Boolean> originalKeyStates = Map.of();
	private Set<Button> activeButtons = Set.of();
	private PlanAction.ActionGroup activeGroup;
	private final Map<Integer, RunningLook> persistentLooks = new LinkedHashMap<>();
	private int activePersistentLookId;
	private int actionIndex;
	private int remainingTicks;
	private int groupElapsedTicks;
	private int entityAttackCooldown;
	private BlockPos breakingBlock;
	private boolean running;

	public String planText() {
		return planText;
	}

	public void validate(String text) throws PlanParseException {
		PlanParser.parse(text);
	}

	public void setPlan(String text) throws PlanParseException {
		if (running) {
			throw new IllegalStateException("Stop the current Silo plan before replacing it.");
		}
		List<PlanAction> parsed = PlanParser.parse(text);
		planText = text;
		actions = parsed;
	}

	public boolean isRunning() {
		return running;
	}

	public void start(FabricClientCommandSource source) throws PlanParseException {
		if (running) {
			throw new IllegalStateException("A Silo plan is already running.");
		}
		if (source.getPlayer() == null) {
			throw new IllegalStateException("Join a world before running a Silo plan.");
		}
		actions = PlanParser.parse(planText);
		persistentLooks.clear();
		activePersistentLookId = 0;
		originalKeyStates = captureKeyStates(source.getClient().options);
		activeButtons = Set.of();
		activeGroup = null;
		actionIndex = 0;
		remainingTicks = 0;
		groupElapsedTicks = 0;
		entityAttackCooldown = 0;
		breakingBlock = null;
		running = true;
		SiloClient.LOGGER.info("Started Silo plan with {} actions.", actions.size());
		source.sendFeedback(Component.literal("Running Silo plan (" + actions.size() + " actions)."));
	}

	public void tick(Minecraft client) {
		if (!running) {
			return;
		}
		if (client.player == null || client.level == null) {
			finish(client, "Silo plan stopped because the world was closed.");
			return;
		}

		if (remainingTicks == 0 && activeGroup == null) {
			activateNextAction(client);
			if (!running) {
				return;
			}
		}

		if (activeGroup != null) {
			activeButtons = groupButtonsAt(client, groupElapsedTicks);
		}
		updatePersistentLook(client.player);
		applyControlledButtons(client.options);
		processAttack(client);
		if (remainingTicks > 0) {
			remainingTicks--;
		}
		if (activeGroup != null) {
			groupElapsedTicks++;
		}
		if (activeGroup != null && groupComplete(client.player)) {
			actionIndex++;
			activeGroup = null;
			activeButtons = Set.of();
		} else if (activeGroup == null && remainingTicks == 0) {
			actionIndex++;
			activeButtons = Set.of();
		}
	}

	public boolean stop() {
		if (!running) {
			return false;
		}
		finish(Minecraft.getInstance(), null);
		return true;
	}

	public String status() {
		return running
				? "Silo plan running: action " + Math.min(actionIndex + 1, actions.size()) + " of " + actions.size() + "."
				: "Silo plan is idle.";
	}

	private void activateNextAction(Minecraft client) {
		while (actionIndex < actions.size()) {
			PlanAction action = actions.get(actionIndex);
			SiloClient.LOGGER.info("Executing Silo action {}/{}: {}", actionIndex + 1, actions.size(), action);
			activeButtons = Set.of();
			if (action instanceof PlanAction.Look look) {
				LocalPlayer player = client.player;
				player.setYRot(look.yaw());
				player.setXRot(look.pitch());
				actionIndex++;
				continue;
			}
			if (action instanceof PlanAction.Press press) {
				activeButtons = press.buttons();
				remainingTicks = press.ticks();
			} else if (action instanceof PlanAction.Wait wait) {
				remainingTicks = wait.ticks();
			} else if (action instanceof PlanAction.ActionGroup group) {
				activeGroup = group;
				groupElapsedTicks = 0;
				remainingTicks = group.durationTicks();
			}
			return;
		}
		finish(client, "Silo plan completed.");
	}

	private void finish(Minecraft client, String message) {
		stopBlockBreaking(client.gameMode);
		running = false;
		remainingTicks = 0;
		entityAttackCooldown = 0;
		activeGroup = null;
		persistentLooks.clear();
		activePersistentLookId = 0;
		for (Map.Entry<KeyMapping, Boolean> entry : originalKeyStates.entrySet()) {
			entry.getKey().setDown(entry.getValue());
		}
		originalKeyStates = Map.of();
		activeButtons = Set.of();
		if (message != null) {
			SiloClient.LOGGER.info("{}", message);
		}
		if (message != null && client.player != null) {
			client.player.sendSystemMessage(Component.literal(message));
		}
	}

	private static Map<KeyMapping, Boolean> captureKeyStates(Options options) {
		Map<KeyMapping, Boolean> states = new HashMap<>();
		for (Button button : CONTROLLED_BUTTONS) {
			KeyMapping key = keyFor(options, button);
			states.put(key, key.isDown());
		}
		return states;
	}

	private void applyControlledButtons(Options options) {
		for (Button button : CONTROLLED_BUTTONS) {
			keyFor(options, button).setDown(button != Button.ATTACK && activeButtons.contains(button));
		}
	}

	private Set<Button> groupButtonsAt(Minecraft client, int elapsedTicks) {
		PlanAction.ActionGroup group = activeGroup;
		if (group == null || elapsedTicks < group.delayTicks()) {
			return Set.of();
		}

		if (elapsedTicks == group.delayTicks()) {
			for (PlanAction action : group.actions()) {
				if (action instanceof PlanAction.Look look) {
					if (client.player != null) {
						client.player.setYRot(look.yaw());
						client.player.setXRot(look.pitch());
					}
				} else if (action instanceof PlanAction.PersistentLook look && client.player != null) {
					persistentLooks.remove(look.id());
					persistentLooks.put(look.id(), new RunningLook(look,
							client.player.getYRot(), client.player.getXRot()));
				} else if (action instanceof PlanAction.EndLook look) {
					persistentLooks.remove(look.id());
				}
			}
		}

		int groupActionElapsed = elapsedTicks - group.delayTicks();
		Set<Button> buttons = java.util.EnumSet.noneOf(Button.class);
		for (PlanAction action : group.actions()) {
			if (action instanceof PlanAction.Press press && groupActionElapsed < press.ticks()) {
				buttons.addAll(press.buttons());
			} else if (action instanceof PlanAction.CoordinatePress press
					&& !press.target().reached(client.player.getX(), client.player.getY(), client.player.getZ())) {
				buttons.addAll(press.buttons());
			}
		}
		return Set.copyOf(buttons);
	}

	private boolean groupComplete(LocalPlayer player) {
		PlanAction.ActionGroup group = activeGroup;
		if (group == null || groupElapsedTicks < group.durationTicks()) {
			return false;
		}
		for (PlanAction action : group.actions()) {
			if (action instanceof PlanAction.CoordinatePress press
					&& !press.target().reached(player.getX(), player.getY(), player.getZ())) {
				return false;
			}
		}
		return true;
	}

	private void updatePersistentLook(LocalPlayer player) {
		RunningLook activeLook = null;
		int activeId = 0;
		for (Map.Entry<Integer, RunningLook> entry : persistentLooks.entrySet()) {
			activeId = entry.getKey();
			activeLook = entry.getValue();
		}
		if (activeLook == null) {
			activePersistentLookId = 0;
			return;
		}
		if (activePersistentLookId != activeId) {
			activeLook.rebase(player.getYRot(), player.getXRot());
			activePersistentLookId = activeId;
		}
		activeLook.tick(player);
	}

	private void processAttack(Minecraft client) {
		if (!activeButtons.contains(Button.ATTACK) || client.gameMode == null || client.player == null) {
			stopBlockBreaking(client.gameMode);
			return;
		}

		HitResult target = client.hitResult;
		if (target instanceof BlockHitResult blockHit
				&& !client.level.getBlockState(blockHit.getBlockPos()).isAir()) {
			BlockPos targetPos = blockHit.getBlockPos();
			if (!targetPos.equals(breakingBlock)) {
				stopBlockBreaking(client.gameMode);
				breakingBlock = targetPos;
				SiloClient.LOGGER.info("Silo is breaking block at {}.", targetPos);
				client.gameMode.startDestroyBlock(targetPos, blockHit.getDirection());
			} else if (client.gameMode.continueDestroyBlock(targetPos, blockHit.getDirection())) {
				client.level.addBreakingBlockEffect(targetPos, blockHit.getDirection());
				client.player.swing(InteractionHand.MAIN_HAND);
			}
			return;
		}

		stopBlockBreaking(client.gameMode);
		if (entityAttackCooldown > 0) {
			entityAttackCooldown--;
			return;
		}
		if (target instanceof EntityHitResult entityHit && entityAttackCooldown == 0) {
			SiloClient.LOGGER.info("Silo is attacking entity {}.", entityHit.getEntity().getName().getString());
			client.gameMode.attack(client.player, entityHit.getEntity());
			client.player.swing(InteractionHand.MAIN_HAND);
			entityAttackCooldown = Math.max(1, (int) Math.ceil(client.player.getCurrentItemAttackStrengthDelay()));
		}
	}

	private void stopBlockBreaking(MultiPlayerGameMode gameMode) {
		if (breakingBlock != null) {
			if (gameMode != null) {
				gameMode.stopDestroyBlock();
			}
			breakingBlock = null;
		}
	}

	private static KeyMapping keyFor(Options options, Button button) {
		return switch (button) {
			case FORWARD -> options.keyUp;
			case BACK -> options.keyDown;
			case LEFT -> options.keyLeft;
			case RIGHT -> options.keyRight;
			case JUMP -> options.keyJump;
			case SNEAK -> options.keyShift;
			case SPRINT -> options.keySprint;
			case ATTACK -> options.keyAttack;
			case USE -> options.keyUse;
		};
	}
}
