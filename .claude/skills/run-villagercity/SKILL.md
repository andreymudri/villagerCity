---
name: run-villagercity
description: Build, test, and run the Villager City NeoForge mod. Use when asked to build the mod, run its GameTests, launch the dev Minecraft client, or screenshot/verify the mod running in-game.
---

Villager City is a Minecraft NeoForge mod (Gradle/`neoForgeGradle`, Java 21). It has no
in-process UI to click through with Playwright — the primary agent-facing "run the app and
watch it work" path is `scripts/gametest.sh`, which boots a real headless dedicated server
and runs the mod's ~230 in-game `GameTest`s (village detection, lumberjack, builder, plots,
storehouse) against actual simulated world ticks. For a real 3D window, `./gradlew runClient`
launches the mod inside Minecraft; there's no scripted-input driver for that GLFW window (no
DOM/accessibility layer to hook), so visual verification is a manual screenshot, documented
below.

All paths below are relative to the repo root (`villagerCity/`).

## Prerequisites

- Java 21, pinned in `mise.toml` (`mise install` pulls Temurin 21). The system default `java`
  here is 17 — Gradle will fail to build/run without `JAVA_HOME` pointed at 21:

```bash
export JAVA_HOME=$(mise where java@temurin-21)
```

- `/tmp` on this machine is a small tmpfs, so point Gradle at a real cache dir (also avoids
  filling `/tmp`):

```bash
export TMPDIR="$HOME/.cache/tmp-villagercity" && mkdir -p "$TMPDIR"
```

Both exports are required before every `./gradlew` invocation below.

## Build

```bash
./gradlew --no-daemon -Dorg.gradle.workers.max=4 build
```

Produces `build/libs/villagercity-<version>.jar` and runs the JUnit (pure-logic) tests. First
run downloads/decompiles Minecraft and NeoForge — several minutes; later runs are seconds
(configuration cache + up-to-date checks).

## Run (agent path): GameTests

```bash
scripts/gametest.sh
```

This runs `./gradlew --no-daemon -Dorg.gradle.workers.max=4 runGameTestServer`, tees the full
log to `build/gametest.log`, and exits non-zero unless the log contains
`All <N> required tests passed`. Plain `runGameTestServer` alone exits 0 even when no test
ran, so always go through the script, not the raw Gradle task.

Verified this session: `All 234 required tests passed :)` in `build/gametest.log`, script
exit 0.

Runtime: ~2-3 minutes once `build/moddev` artifacts are already generated (first invocation
in a clean checkout pays the same multi-minute Minecraft download/decompile cost as `build`).

This is the harness to reach for on any change to citizen/job/village/storehouse/builder
logic — it's what most PRs here actually touch, and it exercises the real simulation loop
(ticking villagers, block placement, plot claiming) far more precisely than clicking through
the 3D client ever could.

## Run (human/visual path): dev client

```bash
export JAVA_HOME=$(mise where java@temurin-21)
export TMPDIR="$HOME/.cache/tmp-villagercity" && mkdir -p "$TMPDIR"
nohup ./gradlew --no-daemon --no-configuration-cache runClient > "$TMPDIR/runClient.log" 2>&1 &
```

Opens a real Minecraft window (NeoForge dev client, mod loaded) on the desktop. Runs until the
window is closed; there's no headless/offscreen mode for this task. See Gotchas below for why
`--no-configuration-cache` is required here specifically (it is **not** needed for `build` or
`runGameTestServer`).

To confirm it's actually up (not just process-alive) rather than launching blind, poll its
log for the point where rendering has started:

```bash
timeout 120 bash -c 'until grep -q "Backend library: LWJGL" "$TMPDIR/runClient.log" 2>/dev/null; do sleep 1; done'
```

### Screenshotting it

No scripted-input driver exists for this window (Minecraft's GLFW window has no DOM or
accessibility tree to hook, unlike a browser or Electron app) — screenshotting is manual, via
the compositor. On this Hyprland/Wayland machine:

```bash
hyprctl monitors -j   # find which output the Minecraft window is on
grim -o <OUTPUT-NAME> /tmp/shots/villagercity-client.png
```

**Do not `grim` the whole desktop** (no `-o`/`-g`) — this machine runs other windows (browser
tabs, chat apps) on other monitors, and a full-desktop grab captures them too. Target only the
output the game window is on.

Verified this session: game window rendered correctly (world geometry, hotbar with tools/
blocks visible) — screenshot at the time showed live gameplay, not a blank/crashed frame.

## Test

Both of the above are the test surfaces for this project:
- `./gradlew --no-daemon -Dorg.gradle.workers.max=4 build` — JUnit tests (pure logic).
- `scripts/gametest.sh` — in-game GameTests (simulation behavior). Treat as required for any
  behavioral change; it's slower but it's the real regression suite.

## Gotchas

- **`runClient` can crash with a `FileSystemNotFoundException` during "Crash during font
  initialization", then immediately mask the real cause with `Exception in thread
  "crash-report" java.awt.HeadlessException`.** This happened on a `runClient` invocation that
  reused a stale Gradle configuration cache entry (log showed `Reusing configuration cache`).
  The NeoForge "early display" splash screen fails to resolve a font from its `union:` jar
  path, then its own crash-dialog handler (`DisplayWindow.crashElegantly`, tries
  `Desktop.getDesktop()`) throws `HeadlessException` and kills the whole process with exit 1 —
  even though `DISPLAY`/`WAYLAND_DISPLAY` were set correctly and the GPU was detected fine
  moments earlier in the same log. Fix: pass `--no-configuration-cache` on `runClient`. Not
  needed for `build` or `runGameTestServer` — only hit it on `runClient`.
- **`JAVA_HOME` must be exported before *every* `./gradlew` call in a fresh shell** — the
  system default `java` here is 17, and the project targets a Java 21 toolchain. Forgetting
  this is the most likely first failure in a new session.
- **`scripts/gametest.sh` fails "silently informative" rather than crashing** if
  `runGameTestServer` itself exits non-zero (bad state, timeout) — read the tail of
  `build/gametest.log` it prints rather than just the exit code.

## Troubleshooting

- **`Exception in thread "crash-report" java.awt.HeadlessException` right after `runClient`
  fails**: this is a secondary failure masking a real one — scroll up in the log to the actual
  `ERROR DISPLAY`/font-init exception. If it's the `FileSystemNotFoundException` font crash,
  re-run with `--no-configuration-cache` (see Gotchas).
- **`gametest.sh` reports "no passing summary found"**: `runGameTestServer` ran but never
  reached a clean summary line (crash mid-run, or tests still in progress when the process
  died) — read `build/gametest.log` tail the script prints; look for a Java stack trace before
  the point it cuts off.
