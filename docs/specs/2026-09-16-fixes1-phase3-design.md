# fixes1 phase 3: storehouse exits, leaving citizens, resumable plots

Three medium findings from fixes1 phases 1 and 2 must be fixed before `run/fixes1` merges into `main`. They
land as a third phase of run `fixes1` (Tasks 7 and 8 of `docs/plans/2026-09-16-slice1-followup-fixes.md`),
not as a separate run, because they build on code that only exists on `run/fixes1`.

## Problems

1. **Storehouse credit leaks through non-click exits** (phase 2 security review, reproduced). `StorehouseMenu`
   charges debt only for withdrawals made by clicking. Items a hopper pulls from under the storehouse, or that
   drop when the storehouse is broken, can be clicked back in for full `DEPOSIT` credit, as often as you like.
2. **A citizen that changes dimension stays on the roster forever** (phase 1 correctness review, reproduced).
   `CitizenRoster` ignores `CHANGED_DIMENSION` because `shouldDestroy()` is false, so `jobCount` stays 1 and
   the village never hires a replacement.
3. **The builder stalls after a plot ends without a house** (e2e debugger, confirmed while designing this
   fix). `claimPlot` returns nothing while any plot exists and otherwise requires a full blueprint's
   materials. So:
   - an abandoned plot's walls have already used up materials, and a new plot rarely gets a full set again;
   - a builder that dies or leaves the dimension keeps its plot, and `plots()` is never empty again, so no
     builder ever claims one.

## Decisions

### 1. Close the storehouse exits (Task 7)

- **Hoppers:** `StorehouseBlockEntity.canTakeItem(Container target, int slot, ItemStack stack)` returns false,
  so hoppers and hopper minecarts cannot pull items out. Inserting still works and credits nobody. The mod
  registers no `IItemHandler` capability for the storehouse, so NeoForge's `VanillaInventoryCodeHooks` falls
  back to the vanilla `Container` path, which checks `canTakeItem`. The test proves this rather than
  assuming it.
- **Breaking:** `StorehouseBlock.playerWillDestroy` (server side only) adds the total item count inside to
  the breaking player's debt with the village that owns the storehouse, then marks the registry dirty. The
  contents still drop as they do today. Breaking a storehouse that belongs to no village changes nothing.
- **Explosions:** the block's explosion resistance is raised to obsidian's (1200) while its destroy time
  stays the same as a barrel's, so TNT and creepers cannot open it. Pistons already cannot move a block
  entity.
- **Not covered, and accepted:** commands such as `/setblock` and `/data`, and other mods' item pipes that
  bypass `canTakeItem`.

### 2. Citizens who change dimension leave the roster (Task 8)

`CitizenRoster.onLeave` treats `CHANGED_DIMENSION` like a destroying removal. `UNLOADED_TO_CHUNK` and
`UNLOADED_WITH_PLAYER` still keep the entry. If the villager comes back before its data is cleared, the
existing reconciliation in `JobAssignment.assign` writes it back into the roster.

### 3. Plots outlive their builder (Task 8)

`Plot` gains an optional builder and two fields:

| Field | Codec | Meaning |
|---|---|---|
| `builder` (`@Nullable UUID`) | `optionalFieldOf("builder")` | null when no builder holds the plot |
| `retryAt` (`long`) | `optionalFieldOf("retry_at", 0L)` | game time from which a builder may take the plot |
| `abandons` (`int`) | `optionalFieldOf("abandons", 0)` | times a builder gave up on it |

Existing saves load unchanged: a saved builder stays the builder, `retryAt` 0 and `abandons` 0. A five-argument
constructor stays for existing callers.

- **Abandon** (`MAX_CONSECUTIVE_FAILURES` in a row): `abandons + 1`. At `MAX_ABANDONS = 3` the plot is
  removed and its walls stay, as today. Below that the plot is released: builder null, `retryAt = gameTime +
  RETRY_TICKS` (2400).
- **Builder leaves** (the villager is destroyed or changes dimension, in `CitizenRoster`): every plot it holds
  is released with `retryAt = 0` and `abandons` unchanged. Leaving is not the plot's fault.
- **Claim order** in `BuilderJob.claimPlot`:
  1. a released plot whose `retryAt` has passed is assigned to this builder, and no material check happens
     at claim time;
  2. otherwise, if any plot exists, claim nothing (still one plot at a time);
  3. otherwise, claim a new plot, which needs the full blueprint's materials as today.

  A resumed plot then goes through the existing `plan` path, which withdraws only the materials for
  unfinished placements and waits while the storehouse lacks them.
- `VillageData` gets `releasePlot(UUID plotId, long retryAt, boolean abandoned)`,
  `releasePlotsBuiltBy(UUID builder)` and `assignPlot(UUID plotId, UUID builder)`. Because `Plot` is a record,
  each one replaces the entry in the list. `plotBuiltBy` skips released plots.
- `/villagercity village` keeps showing the plot count. No UI change.

## Testing

GameTests, written so they fail first:

- **Task 7** (`StorehouseTests`):
  - a hopper under a stocked storehouse pulls nothing after 60 ticks;
  - a hopper above it still inserts, and nobody is credited;
  - a mock server player breaking the storehouse through its game mode owes debt equal to the contents, and
    clicking the dropped items back in earns 0 credit;
  - an explosion next to the storehouse leaves it standing.
- **Task 8:**
  - the reviewer's dimension repro: the lumberjack goes to the nether, a replacement is hired;
  - a builder discarded mid-plot releases it, a new builder takes it over, and the house finishes using only
    the remaining materials;
  - an abandoned plot cannot be claimed before `retryAt`, can be after it, and is dropped on the third
    abandon (`BuilderTests.abandonsPlotAfterRepeatedFailures` is updated to match);
  - the new `Plot` fields survive a codec round trip, and a plot saved without them loads.

## Out of scope

- Per-player debt handed between players (low, reproduced). It needs village-level accounting, which comes with
  mayorship (sub-project 5).
- Roster entries already stuck in development saves from before this fix.
- The other low findings in `.fleetmates/fixes1/followups.md`.
