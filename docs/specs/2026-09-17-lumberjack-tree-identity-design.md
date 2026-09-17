# Lumberjack: which logs are a tree

After fixes1, the user asked to verify that the lumberjack accepts branched and 2x2 trees. A probe using real
vanilla tree features found floating logs, rejected big trees, and a pathing bug. The first fixes, on branch
`fix/lumberjack-fells-whole-trees` (`77cb50b`, `5587cf0`, `f512178`), then drew two reviews. Together they
showed that shape rules alone cannot separate a natural tree from a log build. Every rule either chopped some
player build (frames on cobblestone or stripped-log posts, lamp posts next to a tree) or rejected real trees
(neighbouring dark oaks and cherries). The user chose to track placed logs.

## Decisions

### 1. Placed logs are remembered

`PlacedLogs` is a `SavedData` per level (`data/villagercity_placed_logs.dat`) holding the positions of logs that an
entity placed. The set is saved as a long array.

- **Adding:** an `EntityPlaceEvent` listener at `EventPriority.LOWEST`, which does not receive cancelled events,
  records the position when the event's entity is non-null and the block now at that position is in `#logs`. This
  covers players and citizens: the builder places the starter house's logs through `PlaceBlock`. The listener
  reads the level's current state, not `getPlacedBlock()`, because for non-player entities NeoForge reports the
  snapshot's old state there.
  A multi-block placement records every log it placed.
- **Growth is not placement:** a placement that replaced a sapling or a fungus is bone meal growing a tree. It
  records nothing, and clears any entry at the positions it filled.
- **Removing:** an entry is forgotten only once its position no longer holds a log. A placement of a non-log forgets
  it at once. A `BreakEvent` only queues the position for a check at the end of that level tick, because the event
  fires before the break happens: another listener may cancel it, or a mod may post one just to ask permission. A
  sweep every 200 ticks covers removals no event reports (fire, explosions, commands), skipping unloaded chunks and
  moving pistons.
- **Pistons:** on `PistonEvent.Pre` (at `LOWEST`), the logs remembered within 14 blocks of the piston are noted,
  read from an index of entries by chunk. On `Post`, each noted log whose position no longer holds a log, and whose
  next position along the motion holds a moving block (not the piston head) carrying a log along that same motion,
  moves its entry there. Both checks matter when two pistons fire in the same tick.
- **Crash safety:** chunks are written when they unload, but saved data only with the level. When a chunk holding
  remembered logs unloads while the data is dirty, the data is written too.
- **Not covered:** logs placed before the mod was installed, and commands (`/setblock`, `/fill`).

### 2. A tree touching a placed log or a structure is not a tree

`TreeFinder.shape` rejects the whole tree when any of its logs touches (in its 3x3x3) a remembered log of any kind:
a stripped trunk log, a spruce beam, oak wood. It also rejects a tree when a log other than the base rests on a
block no tree grows over (planks, cobblestone, bricks), which catches untracked log walls on a foundation. Natural
supports are anything that does not block motion, logs, leaves, `#overworld_carver_replaceables` (stone, dirt, sand,
sandstone, terracotta, ores, snow), ice, and what generates beside trees: pumpkins, melons, huge mushrooms, bamboo,
mossy cobblestone boulders, cocoa, bee nests, azaleas, dripstone, moss, amethyst and obsidian. It also rejects the tree when any
of its logs lies inside a generated structure piece. `insideStructure` reads the chunk's structure references and
the starts they point to without loading chunks. A start that is not in memory counts as covering the position,
which only postpones felling there. The result: village houses, and decor trees inside village pieces, are never
felled.

### 3. Neighbouring trees are separate trees

The "supported" rule from `5587cf0` no longer rejects. The walk goes layer by layer upward. A log that stands on a
log of its kind outside the tree belongs to another trunk, so neither it nor anything reached only through it
joins the tree. The layers below are complete when a layer is walked, so one pass decides this, and the 256-log cap
counts only the tree's own logs. The earlier walk capped every connected log before pruning, so it rejected most
trees of a dense dark oak farm. A neighbouring tree keeps its trunk, and branches that reach the felled tree may go
with it.

The other rules stay:
- the tree's logs on the base layer fit in a 2x2, with branches spreading up to 6 from that 2x2, so every trunk
  column walks the same tree;
- at most 256 logs.

**Natural** trees also need at least 4 natural leaves and a log of the same kind in the 3x3 above the scanned base.
`trunk` requires both. `shape` reports them as a flag.

A tree is named by its **base**: the ground-layer log with the smallest x, then the smallest z. Any corner of a
2x2 trunk gives the same base. Fellings and the lumberjack's avoid list are keyed on it. `findNearest` skips a
column whose own position, or the one west, north or north-west of it, is an avoided base, and a column whose log
belongs to a tree already walked in the same search, so avoided trees cost no walk.

### 4. Felling survives interruption

- **Interrupted break:** when `ChopTree` stops a break to walk back, the log it was breaking goes back onto the
  front of the queue.
- **Base last:** `ChopTree` breaks top-down and breaks the base after every other log, including the rest of a 2x2
  ground layer.
- **Checked again before breaking:** right before breaking a log, `ChopTree` skips it if it is no longer the tree's
  block, or if it is now in `PlacedLogs`. A log someone put in the tree after planning stays.
- **Remembered felling:** `VillageData` keeps a saved set `felling` of tree bases (codec
  `optionalFieldOf("felling")`, a list of `BlockPos`). `ChopTree` adds the base once it breaks the first log, so an
  unreachable tree is never remembered. `LumberjackJob` removes it when the task finishes with the base no longer a
  log, and `plan` drops loaded entries whose base is gone. A felling cut short by a failed walk-back, a
  reload or a released citizen therefore stays remembered.
- **Finding a remembered tree:** `TreeFinder.findNearest` accepts a remembered base without the leaves and
  log-above rules, because top-down felling removes them first. A ground layer left as four stumps is still found.
  Every other rule still applies. `LumberjackJob.plan` drops entries whose base is no longer a log.

## Testing (GameTests, failing first where the old code allows)

- **Placed logs:**
  - a mock player placing an oak log records it, and breaking it forgets it;
  - a citizen `PlaceBlock` of a log records it;
  - a cancelled placement records nothing;
  - the data survives a save round trip.
- **Frames and posts:** frames of player-placed logs next to a tree are rejected, on oak-log, cobblestone and
  stripped-log posts, as is a player-placed lamp post two blocks from a tree. Placement is simulated by adding the
  positions to `PlacedLogs`.
- **Structures:** a log inside a generated structure piece is rejected. This is a unit-level check with a real
  village piece if feasible, otherwise by placing a structure template.
- **Neighbouring trees:** pairs of vanilla dark oak and cherry trees 3 to 6 blocks apart are both accepted, and
  felling one leaves the other's trunk standing.
- **Interruptions:**
  - a villager teleported away mid-break (6 ticks into a log) leaves no floating log;
  - a felling interrupted after its canopy logs are gone is found again and finished.
- **Review round 2 (every test was shown failing with its fix reverted):**
  - a cancelled break and a break query keep the entry, and a real break forgets it after the tick;
  - a sweep forgets a log removed without an event;
  - a placed stone forgets the log it replaced;
  - a multi-block placement records every log;
  - a bone-meal tree records nothing and clears a stale entry;
  - a sticky piston carries the entry out and back;
  - a tree inside a structure piece is rejected;
  - the 3x3 log-above rule, including a different kind above;
  - every corner of a 2x2 trunk names the same base;
  - every tree of a 5x5 dark oak farm with 1-block gaps is accepted;
  - logs replaced after planning (another kind, or placed) stay standing;
  - four stumps of a remembered felling are found and cleared, and the felling is forgotten;
  - a remembered felling survives a save.
- **Review round 3:**
  - a stripped trunk log, placed beams of other log kinds, and a placed post touching a branch protect the tree;
  - untracked log walls on cobblestone, planks or stone bricks are not absorbed into a tree;
  - three natural leaves are not a canopy, four are, and persistent leaves do not count;
  - a structure whose start chunk is not loaded still covers, without loading it;
  - a 2x2 trunk of 256 logs is a tree, one of 260 is not;
  - every corner of a 2x2 trunk walks the same logs;
  - 81 avoided dark oaks are searched in under 6 ms;
  - an unreachable tree is not remembered as felling;
  - pistons carry rows of logs and slime-attached logs out and back, and a sweep during the move keeps them;
  - the sweep runs on its own, and neither loads nor forgets logs in unloaded chunks;
  - adding and removing marks the data dirty, and a chunk unload writes it;
  - a crimson fungus grown with bone meal is not placed.
- **Review round 4:**
  - dark oaks, acacias and fancy oaks whose branches rest on pumpkins, melons, huge mushrooms, bamboo, mossy
    cobblestone, sandstone or calcite are still trees;
  - a piston firing in the same tick as another never moves the entry of a log it did not push.
- **Known, not fixed:**
  - rows of 1x1 trees with no gap merge into one ground layer wider than 2x2 and are never felled;
  - felling one tree of a dense grove can take branch logs, and a shifted trunk segment, from a neighbour;
  - a tree whose branch rests on a man-made block is never felled;
  - a player breaking the base mid-felling leaves the rest floating.
- **Existing tests:** all of them stay green.
