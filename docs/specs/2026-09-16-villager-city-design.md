# Villager City — Design

Date: 2026-09-16
Status: vision agreed; slice 1 ready for planning

## 1. Vision

A Minecraft **Java Edition** mod where villages are living settlements that grow on their own,
gather real resources, build and upgrade structures, advance through ages in the spirit of
*Age of Empires II*, and compete with rival villages. A player who helps a village grow into a
city (20+ houses) can become its **mayor** and command it RTS-style.

Reference for the basic concept: the Bedrock addon *Villagers can build villages* (CurseForge).
This mod goes past it with economy, ages, mayorship, rivals, and cross-dimension expansion.

## 2. Platform decisions

| Decision | Choice | Reason |
|---|---|---|
| Delivery | Mod (not data pack or plugin) | Needs custom AI and UI; singleplayer install is one jar |
| Edition / loader | Java, NeoForge 1.21.1 | Largest modpack version, stable APIs |
| Play mode | Singleplayer first | Multiplayer is not a goal, but no design choice may assume one player |
| Villager AI | Keep vanilla `Villager` entity; attach our data; run our own task scheduler | Keeps trading, curing, raids, golems, and mod compatibility |

## 3. Game design (agreed)

### 3.1 Control model
- **Before mayorship:** villages are fully autonomous. They decide what to build and who does
  what. The player helps by depositing materials, building, defending, and trading.
- **As mayor:** full RTS command of that city — place buildings, assign jobs, queue research,
  set priorities.

### 3.2 Economy
Real gathering. Lumberjacks fell real trees, miners dig, farmers farm. Everything flows into a
village **storehouse**; builders withdraw from it. Player deposits count toward contribution.

### 3.3 Ages
Researched, AoE-style: advancing costs resources and requires specific buildings. Each age unlocks
professions, buildings, and an equipment tier.

| Age | Gear tier | Notable unlocks |
|---|---|---|
| Dark | Stone / leather | Lumberjack, miner, farmer, hauler, builder, storehouse |
| Feudal | Iron / chainmail | Blacksmith, guard, walls, market |
| Castle | Iron + enchantments | Knight, archer, mason |
| Imperial | Diamond | Village merging, advanced buildings |
| Netherite | Netherite | Camps in the Nether and the End |

Size labels (hamlet → village → town → city) are derived from house count and are independent of
age. **City = 20+ houses.** The exact unlock lists per age are set in the Ages sub-project spec.

### 3.4 Mayorship
Each village keeps a **contribution ledger** per player (deposits, blocks built, defense, trade).
When a village reaches city size, the top contributor above a threshold is offered mayorship.

### 3.5 Conflict
Defense plus rival villages. Guards, archers, and knights wear and use gear; pillager raids scale
with age. Other evolved villages can become rivals: raids, border disputes, diplomacy.
Merging happens peacefully (diplomacy) or by conquest. No player-commanded armies or siege units.

### 3.6 Buildings and layout
- Buildings are **blueprints**: vanilla structure `.nbt` files, one per building type, biome style,
  and upgrade level. Authored in-game with structure blocks. Upgrading swaps in the next level.
- Town layout (roads, plots, districts, walls) is **procedural**, so every city grows differently.

### 3.7 Which villages
**All naturally generated vanilla villages** join the system in the Dark Age. Existing houses are
adopted as Dark Age buildings. Rivals therefore appear naturally.

### 3.8 Unloaded villages
Villages **must keep evolving** while no player is nearby. The mechanism — abstract catch-up
simulation vs. force-loading active villages — is **decided after performance measurement** in
sub-project 8. Until then, earlier sub-projects must keep village state serializable and
separate from entity state, so either mechanism can drive it.

## 4. Roadmap (sub-projects)

Each gets its own spec → plan → implementation cycle.

1. **Core** — village registry, citizen attachment, task scheduler, equipment use, contribution ledger
2. **Economy** — gathering jobs, hauling, storehouse
3. **Construction** — blueprints, builder, procedural plots/roads, upgrades, house count
4. **Ages** — research, five ages, per-age unlocks and gear tiers
5. **Mayorship** — threshold/offer flow, RTS command UI
6. **Conflict** — guards, scaled raids, rivals, diplomacy, merging/conquest
7. **Netherite expansion** — Nether and End camps
8. **Offscreen simulation** — measure, then pick the mechanism

## 5. Slice 1 — "A village builds a house by itself"

A vertical slice through sub-projects 1–3 that proves the riskiest parts: task AI, pathfinding,
real gathering, and block-by-block construction.

### 5.1 Success criteria
In a fresh world, with no player input beyond standing nearby, a vanilla village:
1. is detected and registered;
2. assigns one villager as lumberjack and one as builder;
3. the lumberjack, holding an axe, fells a tree, replants a sapling, and deposits logs in the
   storehouse;
4. the builder picks a plot, withdraws the blueprint's materials, and places the house block by
   block;
5. the new house is registered, and the village house count increases by one;
6. all of the above survives a save and reload mid-task.

### 5.2 Components

**`VillageRegistry`** (`SavedData`, per `ServerLevel`)
- Detects vanilla villages: a bell (`meeting` POI) with at least one villager within 64 blocks.
  Scans a 128-block radius around each player every 100 ticks (not on chunk load, which would
  touch POI data mid-load).
- Owns a map of `VillageId → VillageData`. Two bells within 64 blocks of each other are one
  village; the first registered is the center.
- Interface: `register(BlockPos bell)`, `get(VillageId)`, `villageAt(BlockPos)`, `all()`.

**`VillageData`** (plain serializable record, no entity references)
- `id`, `center`, `bounds`, `age` (always `DARK` in slice 1), `storehousePos`,
  `houses: List<BuildingRecord>`, `plots: List<Plot>`, `ledger: ContributionLedger`.
- Existing vanilla houses are adopted as `BuildingRecord`s: every `home` (bed) POI inside bounds
  counts as one house.

**`CitizenAttachment`** (NeoForge data attachment on `Villager`, serialized)
- `villageId`, `job: JobType` (`NONE`, `LUMBERJACK`, `BUILDER`), `tool` (the canonical tool stack,
  re-equipped every tick because vanilla trading overwrites the main hand).
- In-flight tasks are **not** serialized. After a load, jobs re-plan from `CitizenData`,
  `VillageData` and the world (a builder skips blocks already placed), which is how criterion 6
  is met.
- Job assignment in slice 1: the first two adult villagers with vanilla profession `none`
  (nitwits and employed villagers excluded) become lumberjack, then builder.
- The registry runs a **village tick** every 100 game ticks per village: job assignment,
  storehouse checks, and builder plot decisions happen there.

**`TaskScheduler`**
- Each tick for an employed citizen: if vanilla priority activities are active (`PANIC`, `REST`,
  `RAID`, `PRE_RAID`, `HIDE`), the schedule says `REST`, or the villager is trading or sleeping, our
  task pauses and keeps its state. Otherwise the current `Task` ticks.
- Suppression mechanism (verified in-game, no mixin): a registered `villagercity:city_task`
  activity with no behaviours is re-added and forced every tick, and `WALK_TARGET` is erased.
  While it is active, vanilla WORK/IDLE/MEET/PLAY behaviours cannot start; CORE triggers (panic,
  raid, bell) still take over on their own.
- `Task` interface: `start(ctx)`, `tick(ctx) → RUNNING | SUCCESS | FAILED`, `save()`, `load()`.
- Primitive tasks: `MoveTo`, `BreakBlock`, `PlaceBlock`, `PickUpItems`, `Deposit`, `Withdraw`.
- A job produces a sequence of primitive tasks. A `FAILED` task makes the job re-plan from scratch.
- A `MoveTo` that makes no progress for 200 ticks (10 s) fails.

**`Storehouse`**
- A mod block (`villagercity:storehouse`) with a 27-slot inventory and a block entity.
- The village tick places it at the first valid 1×1 plot near the bell when the village has
  none.
- Player insertions record `(player, item, count)` in the `ContributionLedger`. Citizen
  insertions do not.

**`ContributionLedger`**
- Per-player totals by category; slice 1 only records `DEPOSIT` as item count. Mayorship rules
  are not in this slice.

**Jobs**
- `LumberjackJob`: find the nearest log block with leaves attached within 32 blocks of the village
  bounds, walk there, break the connected trunk (logs only, max 32 blocks), pick up drops, replant
  a matching sapling if it has one, carry logs to the storehouse. Chopping time uses vanilla
  block-break speed for the held tool; the axe loses durability per log. The lumberjack is given
  a stone axe on job assignment in slice 1.
- `BuilderJob`: if the village has no plot in progress and a blueprint's materials are all in the
  storehouse, claim a plot, reserve (withdraw) the materials, walk to the plot, place blocks in
  layer order (bottom to top, solids before non-solids), then register the house.
  Blocks already in the target position that match the blueprint are skipped; obstructing blocks
  are broken first and their drops deposited.

**`Blueprint`**
- Loaded from `data/villagercity/structure/blueprint/*.nbt` (vanilla `StructureTemplate` format;
  1.21.1 reads the singular `structure` folder).
- Exposes `size`, ordered block placements, and `requiredMaterials(): Map<Item, Integer>`
  (block → its item form; blocks with no item form are skipped).
- Slice 1 ships one blueprint, `villagercity:blueprint/starter_house`: a 5×5×5 oak house with a
  door, windows and a bed, generated by `tools/nbt_structures.py`. Later blueprints can be
  authored in-game with a structure block.

**`PlotPlanner` (slice 1 rule)**
- Candidate positions spiral outward from the bell in 2-block steps. A plot is valid if the
  blueprint footprint is within village radius + 16, the footprint plus a 1-block margin has
  natural ground (dirt, sand, stone, gravel) varying by at most 1, there are no fluids, and it
  does not overlap the bell, the storehouse, a building record or a plot.
- Ground is found by scanning each column down from 8 blocks above the bell, not from a
  heightmap: a column that starts inside a solid block (hillside or building) is rejected.
- The first valid candidate wins. Procedural roads and districts belong to sub-project 3.

**Debug command**
- `/villagercity village` — shows the nearest village's id, age, house count, storehouse contents,
  citizens and their jobs and current tasks.

### 5.3 Data flow
```
bell POI loads → VillageRegistry.register → VillageData (+ storehouse placed)
             → citizens assigned (CitizenAttachment)
Lumberjack: tree → logs → Storehouse
Builder:   Storehouse has materials? → PlotPlanner → withdraw → place → VillageData.houses += 1
```

### 5.4 Error handling
- Missing blueprint file or bad NBT: log an error once at load and disable the builder job.
  The mod must not crash.
- Storehouse destroyed: its items drop as normal. The village marks the storehouse as missing,
  and jobs that need it idle until it is replaced (slice 1 re-places it on the next village tick).
- Citizen dies mid-task: its reserved materials are gone (they were carried). The next unemployed
  villager takes the job.
- Plot blocked by the player mid-build: obstructions are broken as usual. If a block can't be
  broken (unbreakable or protected), the plot is abandoned and its placed blocks stay.
- Tree gone before arrival: the task fails and the job re-plans.

### 5.5 Testing
- **Unit tests (JUnit):** `ContributionLedger`, `Blueprint.requiredMaterials`, plot validity
  rules on synthetic height maps, `VillageData` serialization round-trip.
- **NeoForge GameTests:**
  - village registration from a bell + villager;
  - lumberjack chops a placed tree and deposits the logs;
  - builder builds the blueprint from a pre-filled storehouse;
  - save/reload mid-build resumes and finishes.

### 5.6 Out of scope for slice 1
Ages and research, UI beyond the debug command, mayorship, conflict and rivals, other professions,
armor, multiple blueprints and biome styles, procedural roads, offscreen simulation.
