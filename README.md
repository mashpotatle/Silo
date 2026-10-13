# Silo

Silo is a client-side Fabric mod for running short, explicit player-control plans.
It executes timed movement/button presses, waits, camera rotations, and waits for
the player to reach specified coordinates; it does not provide coordinate
navigation or obstacle-aware pathfinding.

## Commands

- `/silo plan` opens the plan editor. Saving validates and persists the plan to
  `config/silo-plan.txt`.
- `/silo run` starts the saved plan.
- `/silo stop` stops the plan and restores the control-key states captured when
  it started.
- `/silo status` reports whether a plan is running and its current step.

## Plan syntax

Plans can use named `:Action_...` groups. Groups run in order; commands within a
group run concurrently. Use exactly four spaces before commands and exactly
eight spaces before a `time`, `CordA`, `CordR`, `pers`, or `rand` line. Numeric
options in this format use square brackets. Use spaces, not tabs, in the
Minecraft editor. Blank lines and text after `#` are ignored. Durations are in
game ticks (20 ticks per second), with a maximum of 1,200 ticks per timed
command.

```text
:Action_1
    look [180 8]
        pers [30]
        rand [12] [2] [100]

:Action_2 # Hold both buttons at the same time
    hold left
        time [300]
    hold attack
        time [150]

:Action_3 # Wait before starting these concurrent actions
    sleep [20]
    hold left
        time [300]
    click attack

:Action_4
    end [30]

:Action_5
    hold left
        CordA [34 64 -12]
    hold attack
        CordR [34.5 64 -12.5] [34.7 64 -12.7]
```

- `:Action_<name>` starts a group. Groups run from top to bottom.
- `look [yaw pitch]` sets the camera rotation in degrees. Pitch must be from
  -90 through 90.
- `pers [id]` indented under a `look` keeps that look active across later
  groups until `end [id]` or the plan ends. IDs must be positive integers.
  Starting another persistent look makes it the active camera controller;
  ending it resumes the previously started persistent look, if any.
- `rand [yaw-range] [pitch-range] [speed-ticks]` indented under a persistent
  `look` smoothly moves the camera between random offsets around its yaw and
  pitch. Ranges are 0–180 degrees for yaw and 0–90 degrees for pitch; the
  speed is the number of ticks used to move between targets (1–1,200).
- `end [id]` stops the persistent look with that ID without resetting the
  camera angle.
- `hold <button>` with a nested `time [ticks]` holds a button for that many
  ticks. Multiple holds in one group start together; shorter holds release
  while longer holds continue.
- `hold <button>` with nested `CordA [x y z]` holds the button until the player
  reaches that block. With nested `CordR [x1 y1 z1] [x2 y2 z2]`, it holds until
  the player is inside the inclusive axis-aligned coordinate range between the
  two corners. Coordinate holds keep their action group (and therefore later
  groups) waiting until the target is reached.
- `click attack` or `click use` sends a one-tick attack or use action.
- `sleep [ticks]` optionally delays all commands in that group.
- Buttons are `forward`, `back`, `left`, `right`, `jump`, `sneak`, `sprint`,
  `attack` (break the targeted block or attack the targeted entity), and `use`
  (right click). Attack keeps checking the crosshair while held, including as
  the player moves.

The previous one-command-per-line form (`move`, `hold <buttons> <ticks>`,
`wait`, and `look`) remains supported for existing plans and retains its
original unbracketed numeric syntax.

Plans are limited to 100 actions and 8,192 characters. Coordinate holds only
wait for the specified position; they do not navigate or avoid obstacles.

Silo logs command use, plan loading/saving, and each executed action to the
Minecraft console and `logs/latest.log`.

## Build

Run `./gradlew build` with the Java version required by `gradle.properties`.
