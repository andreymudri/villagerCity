# Terrain preparation, crafting and village lighting

This came out of play-testing on 2026-09-17. Villages on hills run out of flat plots, so the builder sits idle.
New houses have no torches, and mobs spawn among the houses.

The user chose:

- a new **paver** citizen who levels plots and lays a path from each new house to the bell, with steps and bridges;
- earth dug out is reused as fill, with cobblestone when it runs short and oak planks for bridges;
- a new **lamplighter** citizen who lights the village until no ground in it is dark enough for mobs to spawn;
- a torch inside the starter house;
- a new **artisan** citizen who crafts and smelts, with vanilla recipes, what the other jobs are short of, at a
  crafting table and furnace next to the storehouse.

## Jobs and hiring

- `JobType` gains `ARTISAN` (no tool), `PAVER` (hired with a stone pickaxe) and `LAMPLIGHTER` (no tool).
- The village hires in this order: lumberjack, builder, artisan, paver, lamplighter. Each job gets one citizen from the
  adult villagers with no profession, as today.
- A village without a paver keeps working as it does now, on flat plots only.

## Plots on uneven ground

- `PlotPlanner` checks the plot plus its one-block margin, and accepts a spot when:
  - every column is natural ground with no fluid;
  - the ground height varies by at most **6**;
  - the earthwork (blocks to cut plus blocks to fill) is at most **80**.

  The floor level is the ground height that needs the least earthwork, with ties going to the higher level.
- A spot that needs any earthwork is only chosen while the village has a paver on its roster.
- `Plot` gains a saved `prepared` flag. A plot that needs no earthwork is created already prepared. Plots in old
  saves load as prepared.
- The builder does not place blueprint blocks on an unprepared plot. `waitingFor` says "the paver to prepare the
  plot".

## Site preparation (paver)

The paver takes the oldest unprepared plot and works in two passes:

1. **Cut.** It breaks every natural ground block from the top down, inside the footprint plus margin, from the
   floor level up to the height of the blueprint. Grass, flowers and snow layers in the way are broken as well.
2. **Fill.** Working bottom-up, it fills every open cell inside the footprint plus margin, from the ground up to
   one below the floor level.

Rules for both passes:

- Fill uses whatever the paver carries (dirt or cobblestone from digging), then dirt, cobblestone or stone taken
  from the storehouse.
- It picks up what it dug and stores it once its inventory is full or the plot is done. Nobody is credited for it.
- Every break and place asks `WorldPermissions`, exactly as the builder does. A plot that keeps failing is handed
  back unprepared after five failures in a row, and another plot is tried after the usual retry delay.
- Once nothing is left to cut or fill, the plot is marked `prepared`.

## Paths to the bell (paver)

When a house is finished, the village queues a path job from the cell in front of the house's door to the bell.
The path ends early where it meets an existing path cell.

**Route.** A* on columns, where each state is (x, z, path height). Moves go to the 4 neighbours, with the height
changing by at most 1 per step. Costs:

- 1 per step;
- +2 for each block of earthwork (the path height is off the ground height);
- +3 for bridge cells (over fluid, or over a drop of more than 2).

Houses, plots, the storehouse, non-natural blocks at path height, and anything more than 64 steps long are all
off-limits. If no route exists, the path is skipped and the reason is logged.

**Building.** For each cell, in order:

- natural blocks above the path surface, up to two blocks of headroom, are cut;
- a solid dirt block is placed under the surface where the ground is missing, or oak planks when the cell is a
  bridge;
- grass or dirt on the surface becomes a dirt path (the shovel conversion). Other surfaces stay as they are.

A height change of 1 between cells is a step a villager can walk up. Path cells are saved on the village, so
plots never cover them and the lamplighter lights them.

## Lighting

**Starter house.** The blueprint gains one wall torch on an inside wall, on the second layer, so it needs one
torch in its materials.

**Dark spots.** The lamplighter scans the village area (a square of the village radius around the bell) in the
background, 256 columns per scan pass. A column is a dark spot when its surface cell (the heightmap without
leaves) meets all of these:

- the cell is a valid zombie spawn (`isValidSpawn` on the block below, plus 2 blocks of empty space);
- it has block light 0;
- it is not inside a house, plot or storehouse footprint.

**Lighting a spot.** The lamplighter:

1. takes up to 16 torches from the storehouse (`waitingFor` "torches" when there are none);
2. walks to the nearest known dark spot;
3. places a standing torch on it, or on a neighbour when the spot is a path cell (a torch never goes on a path);
4. re-checks the spots within 12 blocks once the light has updated.

Placement goes through `WorldPermissions`.

**When it is done.** When a full scan finds no dark spot, the lamplighter idles with "the village is lit" and
rescans every 60 seconds (new houses and cut trees open up new dark ground).

## Crafting (artisan)

**Workshop.**

- Once the village has a storehouse, it places a crafting table and a furnace on free ground within 3 blocks of
  it. They are placed the way the storehouse is (`WorldPermissions`, never over a block), and their positions are
  saved on the village.
- If a workshop block is broken, the village places it again.

**Demand.** Each time the artisan plans, it adds up what the village is short of in the storehouse:

- the builder's current plot's missing materials, or a full starter house's materials when the builder has no
  plot;
- torches up to 16, while the village has a lamplighter.

Each shortfall item becomes an order. Orders with the smallest shortfall come first.

**Recipe planning (`CraftPlanner`).** This is a pure function of the storehouse counts and the orders, and is
unit-tested.

- For each order, it takes the vanilla recipe that yields the item and whose ingredients the storehouse can cover,
  crafting intermediate items recursively up to 4 levels deep:
  - `crafting` recipes, shaped or shapeless, that leave no remainder items;
  - `smelting` recipes.
- When an ingredient accepts several items, it takes the one the storehouse holds most of. An item the builder
  itself needs is only used beyond what the builder needs.
- The storehouse is simulated as steps are chosen, so two orders never count the same logs twice.
- The result is an ordered list of steps: craft recipe R n times, or smelt item I n times. An order that cannot
  be covered stays unmet. `waitingFor` names its missing base materials, for example "sand for glass".

**Crafting.** For a craft step, the artisan:

1. takes the ingredients for up to one stack of output from the storehouse;
2. walks to the crafting table;
3. crafts one result every 10 ticks, swinging its arm;
4. stores the output back in the storehouse.

**Smelting.** For a smelt step, the artisan:

1. takes the input and enough fuel;
2. puts them in the workshop furnace's input and fuel slots;
3. comes back when the furnace's output slot holds the result, or after the smelt time;
4. stores the result.

Fuel is charcoal, coal, logs or planks, whichever the storehouse holds most of beyond what orders need.

Nobody is credited or charged for what the artisan moves.

## Command output

`/villagercity village` also shows:

- each plot's prepared flag;
- the number of queued paths;
- the number of known dark spots;
- the workshop positions;
- the artisan's current orders.

## Testing

GameTests cover:

- the planner picks an uneven spot only with a paver, and at the level with the least earthwork;
- the paver cuts and fills a sloped plot and marks it prepared;
- fill reuses dug blocks, then storehouse cobblestone;
- the builder waits for preparation;
- a path is built with a step and a bridge, and never through a house;
- the lamplighter lights a dark area until no spawnable dark spot remains, never puts a torch on a path, and waits
  for torches;
- the starter house needs a torch;
- hiring order, with codecs round-tripping `prepared`, the path cells and the workshop;
- `CraftPlanner` for:
  - oak door from logs;
  - torches from logs through sticks and charcoal;
  - a bed from white wool and red dye;
  - glass from sand;
  - no double counting of logs between two orders;
  - an order it cannot meet;
- the artisan crafts planks and a door into the storehouse, and smelts glass in the furnace;
- the builder starts a house from logs alone once the artisan has crafted the rest.

## Out of scope

- Streets linking houses to each other.
- Retaining walls and stairs blocks.
- Lighting caves and building interiors.
- Automatic crafting of things no job demands.
- Crafting that needs a smithing table, stonecutter or loom.
- Recipes that leave remainder items (buckets, bottles).
