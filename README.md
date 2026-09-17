# Villager City

A Minecraft **Java Edition** mod for **NeoForge 1.21.1** where villages are living settlements. Villagers
gather real resources, build and upgrade their village on their own, and advance through ages in the spirit
of *Age of Empires II*. A player who helps a village grow into a city (20+ houses) can become its mayor and
command it RTS-style.

> **Status: early development.** Slice 1 is done: a vanilla village detects itself, hires a lumberjack and a
> builder, gathers wood, and builds a house block by block, including after a save and reload. The slice 1
> follow-up fixes have landed: citizens respect `doMobGriefing` and protection mods, and the storehouse credit
> can no longer be farmed. The lumberjack now fells every vanilla sapling tree whole and never a build, and the
> builder finds plots in hilly villages. Known open issues are listed under [Known issues](#known-issues).

## What works today (slice 1)

- **Village detection:** every 5 seconds, any vanilla village (a bell with a villager nearby) within 128 blocks
  of a player is registered.
- **Storehouse:** the village places a storehouse near its bell. It holds any number of each item, one entry per
  item (named or enchanted items get their own entry), in a scrollable screen showing the counts.
  - Taking items: left click takes a stack, right click takes half a stack, and shift-click takes as many as fit
    in your inventory.
  - Storing items: click the grid with a stack on the cursor to store it (right click stores one), or shift-click
    a stack in your inventory.
  - Credit and debt: items a player stores are recorded in a per-player contribution ledger. Items a player takes
    out become that player's debt, which later deposits repay before they earn credit.
  - Protection: hoppers and item pipes can insert but not extract, a player who breaks the storehouse owes
    everything that was inside, and explosions, withers and the ender dragon cannot break it. Storehouses from
    older saves keep their contents.
- **World rules:** citizens only break or place blocks when `doMobGriefing` is on and no protection event
  cancels the change.
- **Jobs:** the first two adult villagers with no vanilla profession become a **lumberjack** (given a stone axe)
  and a **builder**.
- **Jobs survive absence:** workers are kept on a saved village roster, so a worker in an unloaded chunk is not
  replaced. A worker that dies, converts or changes dimension frees its job for a new hire.
- **Lumberjack:** fells whole natural trees, including branched and 2×2 trees (dark oak, mega spruce, mega
  jungle, acacia, cherry), top log first and the trunk base last, so no logs are left floating. It collects the
  drops, replants a sapling, and hauls logs to the storehouse. A felling cut short (a reload, nightfall) is
  finished later. A tree it cannot reach or is not allowed to cut is skipped for two minutes.
- **Builds are safe from the lumberjack:** logs placed by players, citizens or other mods' block placers are
  remembered, and pistons carry that memory along. A tree touching any remembered log, a tree inside a
  generated structure (village houses), and logs resting on a man-made foundation are never felled. Trees grown
  with bone meal count as natural.
- **Builder:** once the storehouse holds a full blueprint's materials, claims a plot near the bell, withdraws the
  materials, and builds `villagercity:blueprint/starter_house` (5×5×5 oak house) block by block. Plots are flat
  natural ground (no caves or overhangs) up to 48 blocks from the bell and 16 above or below it, that the builder
  can walk to. A plot whose builder gives up is retried after two minutes and dropped after the third try, and a
  dropped spot is never picked again; a plot whose builder dies or leaves is taken over at once. A resumed plot
  needs only the materials for the missing blocks.
- **Trapped citizens dig out:** a worker stuck in a cave that cannot path to the storehouse or its plot digs a
  staircase through natural ground (stone, dirt, sand, gravel, ores) toward it. It never digs build blocks, blocks
  next to water or lava, or anything `doMobGriefing` or a protection mod forbids. A builder only abandons a plot
  for failures at the plot itself, not for failing to reach the storehouse.
- **Save-safe:** in-flight work is not saved; jobs re-plan from the world after a reload.

Villagers still trade, sleep, panic, and react to raids as usual: the mod pauses its own work whenever vanilla
needs the villager.

## Roadmap

| # | Sub-project | Contents |
|---|---|---|
| 1 | Core | Village registry, citizen tasks, equipment, contribution ledger |
| 2 | Economy | Miners, farmers, haulers |
| 3 | Construction | More blueprints, procedural roads and districts, upgrades |
| 4 | Ages | Researched ages: Dark, Feudal, Castle, Imperial, Netherite |
| 5 | Mayorship | Mayor offer from contribution score, RTS command UI |
| 6 | Conflict | Guards, scaled raids, rival villages, merging |
| 7 | Netherite expansion | Camps in the Nether and the End |
| 8 | Offscreen simulation | Keep villages evolving while unloaded |

The full design is in [`docs/specs/2026-09-16-villager-city-design.md`](docs/specs/2026-09-16-villager-city-design.md).

## Requirements

- Java 21 (`mise.toml` pins Temurin 21; with mise, `mise install`)
- Nothing else: the Gradle wrapper downloads Gradle, NeoForge and Minecraft on first build

## Development

`/tmp` on the main dev machine is a small tmpfs, so Gradle is pointed at a cache directory:

```bash
export JAVA_HOME=$(mise where java@temurin-21)
export TMPDIR=$HOME/.cache/tmp-villagercity && mkdir -p "$TMPDIR"
```

| Task | Command |
|---|---|
| Launch a dev client with the mod loaded | `./gradlew --no-daemon runClient` |
| Build the jar (`build/libs/villagercity-<version>.jar`) and run JUnit | `./gradlew --no-daemon -Dorg.gradle.workers.max=4 build` |
| Run every in-game GameTest | `scripts/gametest.sh` |
| Regenerate the shipped structure `.nbt` files | `python3 tools/nbt_structures.py` |
| Check the `.nbt` files are up to date | `python3 tools/nbt_structures.py --check` |

`scripts/gametest.sh` fails unless the server reports that every required test passed; plain
`runGameTestServer` exits 0 even when no test ran. The full log is written to `build/gametest.log`.

The first `runClient` or `build` downloads and decompiles Minecraft and takes several minutes.

## Play-testing

1. Start `./gradlew --no-daemon runClient` and create a world with **Allow Cheats: ON**. Seed
   `-1729032610845472033` is expected to have a village near spawn (not yet verified).
2. Find a village with `/locate structure #minecraft:village` and teleport there.
3. Make sure it has two **adult villagers without a profession** (not nitwits). Break a couple of
   workstations or use spawn eggs if every villager is employed.
4. Run `/villagercity village` (op only) to see the nearest village's id, age, house count, storehouse
   contents, every plot's progress, and each citizen's job, position and current step, for example
   `task=walking to 864, 88, -1070 (15 blocks, stuck)` or `task=placing oak_planks at 842, 73, -1028`.
   An idle citizen says what it waits for, for example
   `task=idle (waiting for materials: oak_planks x57)` or `task=idle (waiting for a buildable plot near the
   bell)`; `task=sleeping`, `task=trading` or `task=rest` mean vanilla has the villager for now.
5. Villagers cannot craft yet, so stock the storehouse with the starter house materials: exactly 25 cobblestone,
   57 oak planks, 12 oak logs, 2 glass, an oak door and a red bed. Only these exact items count (birch planks do
   not). The lumberjack supplies more logs over time, of whatever trees grow nearby.
6. Watch the builder claim a plot and build; `/villagercity village` shows the house count go up when it
   finishes. Villagers work only during the day.

To install into a normal launcher instead, install NeoForge 21.1.250 with its installer and copy the built jar
into `~/.minecraft/mods/`.

## Known issues

- A player can withdraw storehouse items and hand them to another player, who earns deposit credit for them.
- Breaking a storehouse that holds a lot drops everything as item entities, which can lag the game.
- A player can mine blocks the builder placed and deposit them for credit.
- Villagers with nothing to do stand still instead of returning to their vanilla routine.
- A plot the builder gives up on three times is left as a partial ruin, and the next house again needs a full
  set of materials.
- The starter house needs oak specifically; villages among birch or spruce need the player to bring oak.
- Lumberjack limits:
  - log builds placed before the mod was installed, or with commands, are protected only by their shape
    (foundation, structure pieces);
  - a row of 1×1 trees with no gap between the trunks is never felled;
  - a tree whose branch rests on a man-made block is never felled;
  - mangroves are not felled, and a 2×2 tree is replanted with a single sapling;
  - felling a tree in a dense grove can take branch logs from its neighbours;
  - drops from the top of a very tall tree can land out of pickup range.

## Project layout

```
src/main/java/dev/andreymudri/villagercity/
  village/      village registry, detection, data, codecs, ticker, job assignment, plot planning
  citizen/      citizen attachment, task scheduler, primitive tasks (move, break, place, pick up, deposit, withdraw)
  job/          lumberjack and builder jobs, tree finder
  blueprint/    structure-template blueprints and their material lists
  storehouse/   storehouse block, block entity, menu, network payloads, placement
  client/       storehouse screen
  command/      /villagercity debug command
  gametest/     in-game GameTests
src/test/java/  JUnit tests for pure logic
src/main/resources/data/villagercity/structure/   blueprint and test-area templates (generated)
tools/nbt_structures.py                          generator for the structure files
docs/specs/, docs/plans/                         design spec and implementation plans
```

## License

All rights reserved (no open-source license has been chosen yet).
