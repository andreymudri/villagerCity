# Street-first village growth

Play-testing on 2026-09-17 on a savanna mountain village showed the builder idle with "waiting for a buildable plot
near the bell" while the slope around it was full of usable terraces. Research into vanilla's jigsaw village
generation (1.21.1 sources, cited below) showed our placement rules differ from vanilla's in the one way that
matters on a slope: we reject ground for being uneven, and vanilla never does.

This spec replaces how a village decides **where** the next house goes. It does not change what a house is, who
builds it, or how materials are handled.

## What vanilla does, and what we take from it

| Vanilla (`JigsawPlacement`, `Beardifier`, `PlainVillagePools`) | What we take |
|---|---|
| Houses attach to **streets**, never to houses; streets are the only terrain-following pieces | Houses attach to **path cells** |
| A piece's floor comes from **one surface sample** at the connector column | A plot's floor comes from the **path cell** it attaches to |
| The only rejection is **bounding-box overlap** — never slope, never height | Rejection is overlap, fluid, reach, and the paver's earth budget |
| Slope is absorbed by **bearding**: earth grown below a piece, shaved above, falloff radius 12 | The **paver** cuts and fills, budget 80 blocks, at most 6 per column |
| Growth is **breadth-first from the start piece**, depth limit 6, radius 80 | Breadth-first from the bell, hop limit 6, reach 64 |
| ~11% of connectors deliberately build **nothing** (`empty()` weight 10 of 87) | A slot may be skipped, so villages do not look packed |

References: `JigsawPlacement.java:186-217` (the frontier loop), `:405-424` (Y per connector), `:432-434` (the only
rejection), `JigsawStructure.java:114` (radius 80), `Structures.java:219` (depth 6), `PlainVillagePools.java:74,147`
(streets terrain-matching, houses rigid), `Beardifier.java:105-117,149-173` (the beard and its radius),
`Structure.java:158-161` (mean corner height).

## The street graph

A village keeps a **street graph**: the path cells it has laid, each with the position of the cell it grew from.
The bell's own cell is the root, at hop 0.

**Growing a street.** When the village has no path slot free for a house (see below), the paver extends the graph:

- it takes the street end with the fewest hops, preferring ends whose ground is level with their neighbour;
- it lays a run of up to 8 cells outward, away from the bell, climbing at most **1 block per cell**, exactly as
  paths already do;
- the run stops early at the reach limit, at a fluid it cannot bridge, or where the ground would need more than a
  1-block step. It never crosses a house, plot or existing path cell;
- each cell is surfaced as it is today (dirt path over dirt, oak planks over a drop or fluid).

Street ends are saved on the village with their hop count, so growth survives a reload.

**Reach and hops.** A street cell is only laid within **64** blocks of the bell (Chebyshev, up from 48) and at most
**6 hops** from it, one hop being one street run. Six runs of up to 8 cells climbing 1 block each is up to 48 blocks
of climb, which is how a vanilla village walks up a mountain.

## Where a house goes

A plot is a **path-adjacent pad**: its footprint plus margin touches at least one path cell, and it does not
overlap any house, plot, storehouse or path.

- **The floor is the path cell's height**, not the mean of the ground and not the highest column. The house sits
  level with the street it opens onto, and the paver makes the ground meet it.
- The pad is accepted when the paver can do the work: **at most 80 blocks** of cut plus fill, and **no single
  column more than 6** off the floor. `MAX_HEIGHT_VARIANCE` is deleted — flatness is no longer a rule, the earth
  budget is.
- Columns must be natural ground with no fluid, as today.
- A loose clamp of **±32 blocks from the bell** stays, only to keep pads out of caves and off overhangs. It is not
  the growth limit; the hop and reach limits are.
- Candidate pads are ordered by: fewest hops on the street they touch, then least earthwork, then nearest the bell.
- A village with a paver uses this rule. A village without one accepts only pads needing no earthwork, so a village
  with no paver behaves as it does today.

**Skipping a slot.** One pad in eight is skipped at random (seeded per position, so it is stable across reloads),
so a village has gaps like a vanilla one.

## Order of work

The village builds in this order, which is what makes growth street-first:

1. If a path-adjacent pad exists, the builder claims it and the paver prepares it.
2. If none does, the paver grows the street graph by one run, then step 1 is tried again.
3. If the graph cannot grow either (every end blocked or out of reach), the village reports
   `waitingFor = "room to grow"` and retries later.

The existing "path from a finished house to the bell" job is gone: houses are already on the street.

## Doors

Houses are built on a street, so the paver no longer has to open a house door to start a route from it. Where it
still opens one (reaching a house built by the old rule), the door it opened is **recorded on the village**, not on
the job, and closed once the paver is clear. A door recorded as opened is closed on the next village tick even if
the paver died, the chunk unloaded or the server restarted in between. This closes a known low finding from phase 2,
where a wooden door could be left open forever if the paver instance went away mid-route.

## Command output

`/villagercity village` gains a line: `streets: N cells, M ends, deepest H hops`. `/villagercity why` reports the
street rules too: the hop count of the nearest street, the earthwork the pad would need, and which of the four
acceptance rules turned it down.

## Testing

GameTests cover:

- a street run climbs a slope at 1 block per cell and stops at the reach limit;
- a house is only planned on a pad touching a path cell, and its floor matches that cell;
- a pad needing more than 80 blocks of earth, or one column more than 6 off, is refused;
- an uneven pad within budget is accepted, where today's flatness rule would refuse it;
- a village without a paver still only takes pads needing no earthwork;
- growth crosses a 20-block rise in several hops, and never in one;
- the skip rule leaves gaps and is stable across a reload;
- street ends, hop counts and the graph round-trip through the village codec;
- a failed search on a large village still costs under 12 ms.

## Out of scope

- Street junctions, squares and crossings — a street is a run of cells, not a road network.
- Houses facing the street, or any change to the blueprint.
- Bridges longer than the paths already build.
- Lighting and crafting, which are unaffected.
- Retiring or moving houses already built by the old rule; they stay where they are and count as anchors.
