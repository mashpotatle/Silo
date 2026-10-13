package com.silo.client.plan;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.silo.client.plan.PlanAction.Button;

public final class PlanParser {
	public static final int MAX_PLAN_LENGTH = 8_192;
	public static final int MAX_ACTIONS = 100;
	public static final int MAX_ACTION_TICKS = 1_200;
	private static final Pattern ACTION_HEADER = Pattern.compile(":Action_[A-Za-z0-9_-]+");
	private static final Pattern OPTION_TOKEN = Pattern.compile("\\[[^\\]]*]|[^\\s\\[\\]]+|[\\[\\]]");

	private PlanParser() {
	}

	public static List<PlanAction> parse(String text) throws PlanParseException {
		if (text.length() > MAX_PLAN_LENGTH) {
			throw new PlanParseException("Plan is too long (maximum " + MAX_PLAN_LENGTH + " characters).");
		}

		String[] lines = text.split("\\R", -1);
		for (int i = 0; i < lines.length; i++) {
			String line = withoutComment(lines[i]);
			if (line.strip().startsWith(":Action_")) {
				return parseActionGroups(lines);
			}
		}
		return parseLegacyActions(lines);
	}

	private static List<PlanAction> parseLegacyActions(String[] lines) throws PlanParseException {
		List<PlanAction> actions = new ArrayList<>();
		for (int i = 0; i < lines.length; i++) {
			String line = withoutComment(lines[i]).strip();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}

			try {
				actions.add(parseLine(line));
			} catch (IllegalArgumentException exception) {
				throw new PlanParseException("Line " + (i + 1) + ": " + exception.getMessage());
			}
			if (actions.size() > MAX_ACTIONS) {
				throw new PlanParseException("Plan has more than " + MAX_ACTIONS + " actions.");
			}
		}
		if (actions.isEmpty()) {
			throw new PlanParseException("Plan must contain at least one action.");
		}
		return List.copyOf(actions);
	}

	private static List<PlanAction> parseActionGroups(String[] lines) throws PlanParseException {
		List<PlanAction> groups = new ArrayList<>();
		int totalActions = 0;
		for (int i = 0; i < lines.length; ) {
			String line = withoutComment(lines[i]).stripTrailing();
			if (line.isBlank()) {
				i++;
				continue;
			}
			if (line.startsWith("\t") || !ACTION_HEADER.matcher(line.strip()).matches() || !line.equals(line.strip())) {
				throw error(i, "expected an unindented :Action_<name> header.");
			}

			int headerLine = i++;
			int delayTicks = 0;
			boolean sawSleep = false;
			List<PlanAction> concurrentActions = new ArrayList<>();
			int concurrentDuration = 0;

			while (i < lines.length) {
				String raw = withoutComment(lines[i]);
				if (raw.isBlank()) {
					i++;
					continue;
				}
				if (raw.startsWith(":")) {
					break;
				}
				if (raw.indexOf('\t') >= 0) {
					throw error(i, "use spaces for indentation, not tabs.");
				}
				if (!raw.startsWith("    ")) {
					throw error(i, "action lines must be indented with four spaces.");
				}

				String command = raw.substring(4).strip();
				if (command.startsWith(" ")) {
					throw error(i, "action lines must use exactly four leading spaces.");
				}
				String[] parts = splitOptions(command);
				switch (parts[0].toLowerCase(Locale.ROOT)) {
					case "sleep" -> {
						if (!concurrentActions.isEmpty() || sawSleep) {
							throw error(i, "sleep must appear once before the concurrent actions.");
						}
						delayTicks = parseTicksAtLine(parts, 1, i, "sleep [ticks]");
						sawSleep = true;
						i++;
					}
					case "hold" -> {
						if (parts.length != 2) {
							throw error(i, "expected: hold <button[,button...]>, followed by an eight-space-indented time [ticks], CordA [x y z], or CordR [x y z] [x y z] line.");
						}
						Set<Button> buttons = parseButtonsAtLine(parts[1], i);
						int holdLine = i++;
						i = skipBlankAndCommentLines(lines, i);
						if (i >= lines.length || !lines[i].startsWith("        ")
								|| lines[i].indexOf('\t') >= 0
								|| lines[i].substring(8).startsWith(" ")) {
							throw error(holdLine, "hold must be followed by time [ticks], CordA [x y z], or CordR [x y z] [x y z] indented with eight spaces.");
						}
						String optionLine = withoutComment(lines[i]).substring(8).strip();
						String[] optionParts = splitOptions(optionLine);
						switch (optionParts[0].toLowerCase(Locale.ROOT)) {
							case "time" -> {
								int ticks = parseTicksAtLine(optionParts, 1, i, "time [ticks]");
								concurrentActions.add(new PlanAction.Press(buttons, ticks));
								concurrentDuration = Math.max(concurrentDuration, ticks);
							}
							case "corda" -> concurrentActions.add(new PlanAction.CoordinatePress(
									buttons, parseCordAAtLine(optionParts, i)));
							case "cordr" -> concurrentActions.add(new PlanAction.CoordinatePress(
									buttons, parseCordRAtLine(optionParts, i)));
							default -> throw error(i, "expected: time [ticks], CordA [x y z], or CordR [x y z] [x y z].");
						}
						i++;
						totalActions++;
					}
					case "click" -> {
						if (parts.length != 2) {
							throw error(i, "expected: click <attack|use>.");
						}
						Button button = parseButtonAtLine(parts[1], i);
						if (button != Button.ATTACK && button != Button.USE) {
							throw error(i, "click only accepts attack or use.");
						}
						concurrentActions.add(new PlanAction.Press(Set.of(button), 1));
						concurrentDuration = Math.max(concurrentDuration, 1);
						i++;
						totalActions++;
					}
					case "look" -> {
						PlanAction.Look look;
						try {
							look = parseGroupedLook(parts, i);
						} catch (IllegalArgumentException exception) {
							throw error(i, exception.getMessage());
						}
						int lookLine = i;
						i++;
						Integer persistentId = null;
						PlanAction.RandomLook randomLook = null;
						while (true) {
							int modifierLine = skipBlankAndCommentLines(lines, i);
							if (modifierLine >= lines.length || !lines[modifierLine].startsWith("        ")) {
								i = modifierLine;
								break;
							}
							String rawModifier = withoutComment(lines[modifierLine]);
							if (rawModifier.indexOf('\t') >= 0 || rawModifier.substring(8).startsWith(" ")) {
								throw error(modifierLine, "look modifiers must use exactly eight leading spaces.");
							}
							String[] modifier = splitOptions(rawModifier.substring(8).strip());
							switch (modifier[0].toLowerCase(Locale.ROOT)) {
								case "pers" -> {
									if (persistentId != null) {
										throw error(modifierLine, "look can only have one pers modifier.");
									}
									if (modifier.length != 2) {
										throw error(modifierLine, "expected: pers [id].");
									}
									persistentId = parseIdAtLine(modifier[1], modifierLine);
								}
								case "rand" -> {
									if (randomLook != null) {
										throw error(modifierLine, "look can only have one rand modifier.");
									}
									if (modifier.length != 4) {
										throw error(modifierLine, "expected: rand [yaw-range] [pitch-range] [speed-ticks].");
									}
									try {
										float yawRange = parseBracketedFloat(modifier[1], "yaw range");
										float pitchRange = parseBracketedFloat(modifier[2], "pitch range");
										int speedTicks = parseBracketedTicks(modifier[3]);
										if (yawRange < 0 || yawRange > 180) {
											throw new IllegalArgumentException("yaw range must be between 0 and 180 degrees.");
										}
										if (pitchRange < 0 || pitchRange > 90) {
											throw new IllegalArgumentException("pitch range must be between 0 and 90 degrees.");
										}
										randomLook = new PlanAction.RandomLook(yawRange, pitchRange, speedTicks);
									} catch (IllegalArgumentException exception) {
										throw error(modifierLine, exception.getMessage());
									}
								}
								default -> throw error(modifierLine, "expected a look modifier: pers [id] or rand [yaw-range] [pitch-range] [speed-ticks].");
							}
							i = modifierLine + 1;
						}
						if (randomLook != null && persistentId == null) {
							throw error(lookLine, "rand requires a pers [id] modifier.");
						}
						if (persistentId == null) {
							concurrentActions.add(look);
						} else {
							concurrentActions.add(new PlanAction.PersistentLook(
									persistentId, look.yaw(), look.pitch(), Optional.ofNullable(randomLook)));
						}
						totalActions++;
					}
					case "end" -> {
						if (parts.length != 2) {
							throw error(i, "expected: end [id].");
						}
						concurrentActions.add(new PlanAction.EndLook(parseIdAtLine(parts[1], i)));
						i++;
						totalActions++;
					}
					default -> throw error(i, "unknown action '" + parts[0] + "'.");
				}
				if (totalActions > MAX_ACTIONS) {
					throw error(i - 1, "plan has more than " + MAX_ACTIONS + " actions.");
				}
			}
			if (concurrentActions.isEmpty()) {
				throw error(headerLine, "action group must contain at least one action.");
			}
			groups.add(new PlanAction.ActionGroup(delayTicks, concurrentActions,
					Math.max(1, delayTicks + concurrentDuration)));
		}
		if (groups.isEmpty()) {
			throw new PlanParseException("Plan must contain at least one action.");
		}
		return List.copyOf(groups);
	}

	private static PlanAction parseLine(String line) {
		String[] parts = line.split("\\s+");
		return switch (parts[0].toLowerCase(Locale.ROOT)) {
			case "look" -> parseLook(parts);
			case "move" -> parseMove(parts);
			case "hold" -> parseHold(parts);
			case "wait" -> parseWait(parts);
			default -> throw new IllegalArgumentException("unknown action '" + parts[0] + "'.");
		};
	}

	private static PlanAction.Look parseLook(String[] parts) {
		requireCount(parts, 3, "look <yaw> <pitch>");
		float yaw = parseFiniteFloat(parts[1], "yaw");
		float pitch = parseFiniteFloat(parts[2], "pitch");
		if (pitch < -90 || pitch > 90) {
			throw new IllegalArgumentException("pitch must be between -90 and 90 degrees.");
		}
		return new PlanAction.Look(yaw, pitch);
	}

	private static PlanAction.Look parseGroupedLook(String[] parts, int line) throws PlanParseException {
		if (parts.length != 2) {
			throw error(line, "expected: look [yaw pitch].");
		}
		float[] angles = parseBracketedVector(parts[1], 2, "look angles", line);
		if (angles[1] < -90 || angles[1] > 90) {
			throw error(line, "pitch must be between -90 and 90 degrees.");
		}
		return new PlanAction.Look(angles[0], angles[1]);
	}

	private static PlanAction.CordA parseCordAAtLine(String[] parts, int line) throws PlanParseException {
		if (parts.length != 2) {
			throw error(line, "expected: CordA [x y z].");
		}
		String[] coordinates = parseBracketedValues(parts[1], 3, "CordA coordinates", line);
		int[] parsed = new int[3];
		try {
			for (int i = 0; i < parsed.length; i++) {
				parsed[i] = Integer.parseInt(coordinates[i]);
			}
		} catch (NumberFormatException exception) {
			throw error(line, "CordA coordinates must be whole numbers in the integer range.");
		}
		return new PlanAction.CordA(parsed[0], parsed[1], parsed[2]);
	}

	private static PlanAction.CordR parseCordRAtLine(String[] parts, int line) throws PlanParseException {
		if (parts.length != 3) {
			throw error(line, "expected: CordR [x y z] [x y z].");
		}
		String[] firstValues = parseBracketedValues(parts[1], 3, "CordR coordinates", line);
		String[] secondValues = parseBracketedValues(parts[2], 3, "CordR coordinates", line);
		double[] first = new double[3];
		double[] second = new double[3];
		try {
			for (int i = 0; i < 3; i++) {
				first[i] = parseFiniteDouble(firstValues[i], "CordR coordinate");
				second[i] = parseFiniteDouble(secondValues[i], "CordR coordinate");
			}
		} catch (IllegalArgumentException exception) {
			throw error(line, exception.getMessage());
		}
		return new PlanAction.CordR(
				Math.min(first[0], second[0]), Math.min(first[1], second[1]), Math.min(first[2], second[2]),
				Math.max(first[0], second[0]), Math.max(first[1], second[1]), Math.max(first[2], second[2]));
	}

	private static PlanAction parseMove(String[] parts) {
		requireCount(parts, 3, "move <forward|back|left|right> <ticks>");
		Button button = parseButton(parts[1]);
		if (button != Button.FORWARD && button != Button.BACK && button != Button.LEFT && button != Button.RIGHT) {
			throw new IllegalArgumentException("move only accepts forward, back, left, or right.");
		}
		return new PlanAction.Press(Set.of(button), parseTicks(parts[2]));
	}

	private static PlanAction parseHold(String[] parts) {
		requireCount(parts, 3, "hold <button[,button...]> <ticks>");
		return new PlanAction.Press(parseButtons(parts[1]), parseTicks(parts[2]));
	}

	private static PlanAction parseWait(String[] parts) {
		requireCount(parts, 2, "wait <ticks>");
		return new PlanAction.Wait(parseTicks(parts[1]));
	}

	private static Button parseButton(String value) {
		try {
			return Button.valueOf(value.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("unknown button '" + value + "'.");
		}
	}

	private static Set<Button> parseButtons(String value) {
		String[] names = value.split(",", -1);
		EnumSet<Button> buttons = EnumSet.noneOf(Button.class);
		for (String name : names) {
			if (name.isBlank()) {
				throw new IllegalArgumentException("button list contains an empty button.");
			}
			Button button = parseButton(name);
			if (!buttons.add(button)) {
				throw new IllegalArgumentException("button '" + name + "' is repeated.");
			}
		}
		return Set.copyOf(buttons);
	}

	private static Set<Button> parseButtonsAtLine(String value, int line) throws PlanParseException {
		try {
			return parseButtons(value);
		} catch (IllegalArgumentException exception) {
			throw error(line, exception.getMessage());
		}
	}

	private static Button parseButtonAtLine(String value, int line) throws PlanParseException {
		try {
			return parseButton(value);
		} catch (IllegalArgumentException exception) {
			throw error(line, exception.getMessage());
		}
	}

	private static int parseTicksAtLine(String[] parts, int valueIndex, int line, String syntax) throws PlanParseException {
		if (parts.length != valueIndex + 1) {
			throw error(line, "expected: " + syntax + ".");
		}
		try {
			return parseBracketedTicks(parts[valueIndex]);
		} catch (IllegalArgumentException exception) {
			throw error(line, exception.getMessage());
		}
	}

	private static int parseBracketedTicks(String value) {
		return parseTicks(parseBracketedScalar(value, "ticks"));
	}

	private static float parseBracketedFloat(String value, String name) {
		return parseFiniteFloat(parseBracketedScalar(value, name), name);
	}

	private static String parseBracketedScalar(String value, String name) {
		if (!value.matches("\\[[^\\[\\]]+]")) {
			throw new IllegalArgumentException(name + " must use square brackets.");
		}
		String contents = value.substring(1, value.length() - 1).strip();
		if (contents.isEmpty() || contents.matches(".*\\s+.*")) {
			throw new IllegalArgumentException(name + " must contain one value in square brackets.");
		}
		return contents;
	}

	private static float[] parseBracketedVector(String value, int expected, String name, int line)
			throws PlanParseException {
		String[] coordinates = parseBracketedValues(value, expected, name, line);
		float[] parsed = new float[expected];
		try {
			for (int i = 0; i < expected; i++) {
				parsed[i] = parseFiniteFloat(coordinates[i], name);
			}
		} catch (IllegalArgumentException exception) {
			throw error(line, exception.getMessage());
		}
		return parsed;
	}

	private static String[] parseBracketedValues(String value, int expected, String name, int line)
			throws PlanParseException {
		if (!value.startsWith("[") || !value.endsWith("]")) {
			throw error(line, name + " must use square brackets.");
		}
		String[] coordinates = value.substring(1, value.length() - 1).strip().split("\\s+");
		if (coordinates.length != expected || coordinates[0].isEmpty()) {
			throw error(line, name + " must contain exactly " + expected + " values.");
		}
		return coordinates;
	}

	private static String[] splitOptions(String line) {
		List<String> options = new ArrayList<>();
		Matcher matcher = OPTION_TOKEN.matcher(line);
		while (matcher.find()) {
			options.add(matcher.group());
		}
		return options.toArray(String[]::new);
	}

	private static int parseTicks(String value) {
		try {
			int ticks = Integer.parseInt(value);
			if (ticks < 1 || ticks > MAX_ACTION_TICKS) {
				throw new IllegalArgumentException("ticks must be between 1 and " + MAX_ACTION_TICKS + ".");
			}
			return ticks;
		} catch (NumberFormatException exception) {
			throw new IllegalArgumentException("ticks must be a whole number.");
		}
	}

	private static int parseIdAtLine(String value, int line) throws PlanParseException {
		if (!value.matches("\\[[1-9][0-9]*]")) {
			throw error(line, "persistent look ID must use the format [positive-number].");
		}
		try {
			return Integer.parseInt(value.substring(1, value.length() - 1));
		} catch (NumberFormatException exception) {
			throw error(line, "persistent look ID must be a whole number.");
		}
	}

	private static float parseFiniteFloat(String value, String name) {
		try {
			float number = Float.parseFloat(value);
			if (!Float.isFinite(number)) {
				throw new IllegalArgumentException(name + " must be a finite number.");
			}
			return number;
		} catch (NumberFormatException exception) {
			throw new IllegalArgumentException(name + " must be a number.");
		}
	}

	private static double parseFiniteDouble(String value, String name) {
		try {
			double number = Double.parseDouble(value);
			if (!Double.isFinite(number)) {
				throw new IllegalArgumentException(name + " must be a finite number.");
			}
			return number;
		} catch (NumberFormatException exception) {
			throw new IllegalArgumentException(name + " must be a number.");
		}
	}

	private static void requireCount(String[] parts, int expected, String syntax) {
		if (parts.length != expected) {
			throw new IllegalArgumentException("expected: " + syntax + ".");
		}
	}

	private static int skipBlankAndCommentLines(String[] lines, int index) {
		while (index < lines.length && withoutComment(lines[index]).isBlank()) {
			index++;
		}
		return index;
	}

	private static String withoutComment(String line) {
		int comment = line.indexOf('#');
		return comment < 0 ? line : line.substring(0, comment);
	}

	private static PlanParseException error(int line, String message) {
		return new PlanParseException("Line " + (line + 1) + ": " + message);
	}
}
