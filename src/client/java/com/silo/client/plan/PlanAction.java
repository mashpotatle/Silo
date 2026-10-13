package com.silo.client.plan;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public sealed interface PlanAction permits PlanAction.Look, PlanAction.PersistentLook, PlanAction.EndLook,
		PlanAction.Press, PlanAction.Wait, PlanAction.ActionGroup {
	record Look(float yaw, float pitch) implements PlanAction {
	}

	record PersistentLook(int id, float yaw, float pitch, Optional<RandomLook> randomLook) implements PlanAction {
		public PersistentLook {
			if (id < 1) {
				throw new IllegalArgumentException("persistent look ID must be positive.");
			}
			randomLook = Objects.requireNonNull(randomLook);
		}
	}

	record RandomLook(float yawRange, float pitchRange, int speedTicks) {
	}

	record EndLook(int id) implements PlanAction {
		public EndLook {
			if (id < 1) {
				throw new IllegalArgumentException("persistent look ID must be positive.");
			}
		}
	}

	record Press(Set<Button> buttons, int ticks) implements PlanAction {
	}

	record Wait(int ticks) implements PlanAction {
	}

	record ActionGroup(int delayTicks, List<PlanAction> actions, int durationTicks) implements PlanAction {
		public ActionGroup {
			actions = List.copyOf(actions);
		}
	}

	enum Button {
		FORWARD,
		BACK,
		LEFT,
		RIGHT,
		JUMP,
		SNEAK,
		SPRINT,
		ATTACK,
		USE
	}
}
