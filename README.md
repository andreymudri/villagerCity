# Villager City

A Minecraft **Java Edition** mod for **NeoForge 1.21.1** where villages are living settlements. Villagers
gather real resources, build and upgrade their village on their own, and advance through ages in the spirit
of *Age of Empires II*. A player who helps a village grow into a city (20+ houses) can become its mayor and
command it RTS-style.

> **Status: early development.** Slice 1 is done: a vanilla village detects itself, hires a lumberjack and a
> builder, gathers wood, and builds a house block by block, including after a save and reload. A follow-up
> fix run is in progress: until it lands, villagers ignore `doMobGriefing` and can chop player-built log walls,
> so play-test in a throwaway world.

## What works today (slice 1)

- **Village detection:** every 5 seconds, any vanilla village (a bell with a villager nearby) within 128 blocks
  of a player is registered.
- **Storehouse:** the village places a 27-slot storehouse near its bell. Player deposits are recorded in a
  per-player contribution ledger.
- **Jobs:** the first two adult villagers with no vanilla profession become a **lumberjack** (given a stone axe)
  and a **builder**.
- **Lumberjack:** fells natural trees, collects the drops, replants a sapling, and hauls logs to the storehouse.
- **Builder:** once the storehouse holds a full blueprint's materials, claims a plot near the bell, withdraws the
  materials, and builds `villagercity:blueprint/starter_house` (5×5×5 oak house) block by block.
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

1. Start `./gradlew --no-daemon runClient` and create a world with **Allow Cheats: ON**.
2. Find a village with `/locate structure #minecraft:village` and teleport there.
3. Make sure it has two **adult villagers without a profession** (not nitwits). Break a couple of
   workstations or use spawn eggs if every villager is employed.
4. Run `/villagercity village` (op only) to see the nearest village's id, age, house count, storehouse
   contents, and each citizen's job and current task.
5. Villagers cannot craft yet, so stock the storehouse with the starter house materials: roughly 25 cobblestone,
   57 oak planks, 12 oak logs, 2 glass, an oak door and a red bed (two of each is safe). The lumberjack supplies
   more logs over time.
6. Watch the builder claim a plot and build; `/villagercity village` shows the house count go up when it
   finishes. Villagers work only during the day.

To install into a normal launcher instead, install NeoForge 21.1.250 with its installer and copy the built jar
into `~/.minecraft/mods/`.

## Project layout

```
src/main/java/dev/andreymudri/villagercity/
  village/      village registry, detection, data, codecs, ticker, job assignment, plot planning
  citizen/      citizen attachment, task scheduler, primitive tasks (move, break, place, pick up, deposit, withdraw)
  job/          lumberjack and builder jobs, tree finder
  blueprint/    structure-template blueprints and their material lists
  storehouse/   storehouse block, block entity, placement
  command/      /villagercity debug command
  gametest/     in-game GameTests
src/test/java/  JUnit tests for pure logic
src/main/resources/data/villagercity/structure/   blueprint and test-area templates (generated)
tools/nbt_structures.py                          generator for the structure files
docs/specs/, docs/plans/                         design spec and implementation plans
```

## License

All rights reserved (no open-source license has been chosen yet).
