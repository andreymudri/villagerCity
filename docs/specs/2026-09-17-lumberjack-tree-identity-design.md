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
- **Removing:** a `BreakEvent` listener at `LOWEST` removes the position, and so does a placement of a non-log.
  Any other stale entry (a log burnt, exploded or broken by a citizen) can only stop a future tree at that exact
  position from being felled. That is the safe direction, so no other cleanup is attempted.
- **Not covered:** logs placed before the mod was installed, and commands (`/setblock`, `/fill`).

### 2. A tree touching a placed log or a structure is not a tree

`TreeFinder.trunk` rejects the whole tree when any walked log is in `PlacedLogs`. It also rejects the tree when any
walked log lies inside a generated structure piece
(`level.structureManager().getStructureWithPieceAt(pos, s -> true).isValid()`). The result: village houses, and
decor trees inside village pieces, are never felled.

### 3. Neighbouring trees are separate trees

The "supported" rule from `5587cf0` no longer rejects. After walking, a log that stands on a log of its kind
outside the walk belongs to another trunk, so it is removed from the tree. So is every log reachable from the
base only through it: connectivity from the base is recomputed until stable. A neighbouring tree keeps its trunk,
and branches that reach the felled tree may go with it.

The other rules stay:
- ground layers within 1 block of the base, with branches spreading up to 6;
- at most 256 logs;
- at least 4 natural leaves;
- a log of the same kind above the base.

### 4. Felling survives interruption

- **Interrupted break:** when `ChopTree` stops a break to walk back, the log it was breaking goes back onto the
  front of the queue.
- **Remembered felling:** `VillageData` keeps a saved set `felling` of tree bases (codec
  `optionalFieldOf("felling")`, a list of `BlockPos`). `LumberjackJob` adds the base when it starts a felling and
  removes it when the task finishes with the base no longer a log. A felling cut short by a failed walk-back, a
  reload or a released citizen therefore stays remembered.
- **Finding a remembered tree:** `TreeFinder.findNearest` accepts a remembered base without the leaves rule,
  because top-down felling removes the canopy first. Every other rule still applies. `LumberjackJob.plan` drops
  entries whose base is no longer a log.

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
- **Existing tests:** all of them stay green.
