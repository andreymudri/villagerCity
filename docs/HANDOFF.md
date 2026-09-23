# villagerCity handoff — 2026-09-23

## Current work
Nothing is in flight. The street-first work is finished and pushed. That covers run `street` (3-wide streets,
houses 5 apart), run `followups` (every review follow-up), and two small fixes made by hand afterwards. The user
played the build on `main` for about 1h24 in the dev client (closed normally at 14:21, no crash) and asked to stop
here and resume another time. One known issue is deliberately left for later (see Open findings).

## Branches and worktrees
| repo | path | branch | tip | pushed? | safe to remove? |
|---|---|---|---|---|---|
| villagerCity | `~/Work/projetos/villagerCity` | `run/street-followups` | `a97f306ddef02126892687cdaa77e8668270d412` | yes | main worktree, keep |
| villagerCity | `~/.cache/villagercity-playtest` | `main` | `38b7c11ab19f89b13a955a25ad3a0a978a278a40`, plus this handoff commit | yes | keep: it holds `main` for plan commits and `runClient` |

Remote branches: `main` 38b7c11, plus this handoff commit; `run/street-followups` a97f306; `run/street-first`
008c6ea; `run/works` 6ddb3c0. No agent worktrees and no fleetmates task branches remain. Older local branches from
runs `slice1` and `fixes1` and `fix/*` predate this work and were left alone.

## User decisions
- 2026-09-22: streets are 3 blocks wide, and houses stand at least 5 blocks apart.
- 2026-09-22: review is capped at 2 rounds per phase. After that, only a reproduced high finding blocks.
- 2026-09-22: `cb5c227` (published, with a Claude co-author line) stays as it is: no history rewrite.
- 2026-09-22 and 2026-09-23: execution mode for these runs is a fleet (fleetmates). "Finalize autonomamente" and
  "pode fazer tudo" authorized integrating, merging to `main` and pushing without asking.
- 2026-09-23: the artisan's spare-log problem is left for later ("por agora deixa assim mesmo").
- Standing rule (CLAUDE.md): commits are authored by Andrey Mudri only, with no Co-Authored-By or tool attribution.

## Done and verified
- **Run `street`** (`.fleetmates/street/`): gates 1-4 PASS. Merged into `main` at `0241e20`; run tip `008c6ea`.
- **Run `followups`** (`.fleetmates/followups/`, plan `docs/plans/2026-09-23-street-followups.md`): gates 1-2 PASS,
  each after 1 fix round. Merged into `main` at `0230abf`; run tip `a97f306`. It added:
  - lot-based skip (10x10 lots, one in 8 left empty beside streets);
  - a clear slice ahead of a one-cell street;
  - doors that close in every case;
  - the builder seeing the paver's refusals;
  - `VillageDemand` counting only plot 0's builder;
  - `/villagercity why` matching the planner;
  - a search-cost test that bounds block reads.
- **Hand fixes on `main`:**
  - `bb2dea0`: the slice-ahead `why` test really asks the planner.
  - `c7af5ff`: `why` reports that the builder waits for the paver's first street, through
    `BuilderJob.waitsForFirstStreet`.
  - `38b7c11`: the gathered-wood end-to-end failure now carries the village report.
- **Last test run:** `scripts/gametest.sh` on `38b7c11`: "All 313 required tests passed". Build and
  `python3 tools/nbt_structures.py --check` are green. Each new test was proven with a mutant.

## Open findings
- **low** · `craft/` artisan planning · the artisan can turn spare logs into planks nobody needs (README Known issues).
  Nothing has been tried yet. It touches how the village decides what to craft, so it needs its own plan.
- **watch** · `gametest/EndToEndTests.java` `villageBuildsHouseFromGatheredWood` · failed twice ("houses 0, plots
  0"), both times in runs with deliberately mutated code, never on the real code.
  - It did not reproduce in 48 copies over two full suites.
  - No fix was made. The failure message now includes `VillageCommand.describe`, so a repeat failure will say where
    the village stalled.

## Environment blockers
- `/tmp` is a quota-limited tmpfs. Export `TMPDIR=$HOME/.cache/tmp-villagercity` before Gradle.
- Java: `JAVA_HOME=$HOME/.local/share/mise/installs/java/temurin-21.0.12+101.0.LTS`. Gradle always runs with
  `--no-daemon -Dorg.gradle.workers.max=4`.
- fleetmates 2.0.1 cannot gate or prune a run after it is merged into its base: it re-derives phase 1. Remove
  worktrees and branches by hand after checking that each is clean and contained in `main`.
- The fleetmates run records (`.fleetmates/`) are gitignored and exist only on this machine.

## Exact next step
Verify the state, then pick the next piece of work with the user:

    cd ~/Work/projetos/villagerCity && git fetch origin && git log --oneline -1 origin/main

Expected: `origin/main` at this handoff's commit, the child of `38b7c11`. If the next work is the artisan's
spare-log problem, start with a written plan in `docs/plans/` (brainstorm, then plan, then fleet or inline).
