package com.silo.client.plan;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public sealed interface PlanAction permits PlanAction.Look, PlanAction.PersistentLook, PlanAction.EndLook,
		PlanAction.Press, PlanAction.CoordinatePress, PlanAction.Wait, PlanAction.ActionGroup {
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

	record CoordinatePress(Set<Button> buttons, CoordinateTarget target) implements PlanAction {
	}

	sealed interface CoordinateTarget permits CordA, CordR {
		boolean reached(double x, double y, double z);
	}

	record CordA(int x, int y, int z) implements CoordinateTarget {
		@Override
		public boolean reached(double playerX, double playerY, double playerZ) {
			return Math.floor(playerX) == x
					&& Math.floor(playerY) == y
					&& Math.floor(playerZ) == z;
		}
	}

	record CordR(double minX, double minY, double minZ, double maxX, double maxY, double maxZ)
			implements CoordinateTarget {
		@Override
		public boolean reached(double x, double y, double z) {
			return x >= minX && x <= maxX
					&& y >= minY && y <= maxY
					&& z >= minZ && z <= maxZ;
		}
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
