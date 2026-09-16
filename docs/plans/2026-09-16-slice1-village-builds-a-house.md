# Slice 1 — A Village Builds a House by Itself: Implementation Plan

Spec: `docs/specs/2026-09-16-villager-city-design.md` §5.

## Global Constraints

- Minecraft 1.21.1, NeoForge 21.1.250, ModDevGradle plugin `net.neoforged.moddev` 2.0.147, Gradle wrapper 9.2.1
- Java 21 (`mise.toml` pins `java = "temurin-21"`; export `JAVA_HOME=$(mise where java@temurin-21)` when mise is not activated)
- Parchment mappings `2024.11.17` for 1.21.1
- Mod id `villagercity`; base package `dev.andreymudri.villagercity`
- No mixins in slice 1. The only access transformer line is the one in Task 5
- Zero runtime dependencies beyond NeoForge; test-only JUnit Jupiter 5.10.2
- `/tmp` is a quota-limited tmpfs: export `TMPDIR=$HOME/.cache/tmp-villagercity` (mkdir -p it) before any Gradle command
- Gradle commands always pass `--no-daemon -Dorg.gradle.workers.max=4`
- Every task must leave these green: `./gradlew --no-daemon -Dorg.gradle.workers.max=4 build` (compiles and runs JUnit) and `scripts/gametest.sh` (runs every GameTest; fails if no test ran)
- GameTests live in `src/main/java/dev/andreymudri/villagercity/gametest/`, annotated `@GameTestHolder(VillagerCity.MODID)` + `@PrefixGameTestTemplate(false)`, template `GameTestSupport.TEST_AREA`
- Every GameTest that registers a village uses its own unique `batch` name: the village registry is shared by the whole level, and batches run one after another
- The registry is per level and shared: tests must remove the villages they create before succeeding
- Decompiled 1.21.1 sources for checking a signature: `~/.cache/villagercity-research/src/` (use `/usr/bin/grep -a`, not `grep`)
- Commit messages: single-line conventional commits in English (`feat:`, `test:`, `build:`, `docs:`); author Andrey Mudri only — never add `Co-Authored-By`, session links, or any tool attribution

## Destination

In a fresh world, a vanilla village with two unemployed villagers registers itself, places a storehouse,
turns one villager into a lumberjack who fells trees and stores the logs, and the other into a builder
who builds `villagercity:blueprint/starter_house` block by block — and the end-to-end GameTests in
Task 14 prove it, including resuming a build after the villager and registry are saved and reloaded.

## Not Yet Specified

- Should villages keep evolving in unloaded chunks by abstract catch-up or by force-loading? Decided in sub-project 8 after measurement; slice 1 keeps all village state in `VillageData` so either can drive it.
- How should a village choose between several blueprints and biome styles once more than one exists?

## Out of Scope

- Ages, research, and age-gated unlocks — sub-project 4; slice 1 stores `VillageAge.DARK` only
- Any UI beyond `/villagercity village` — mayorship and the RTS screen are sub-project 5
- Procedural roads and districts — sub-project 3; slice 1 uses the spiral plot rule
- Detecting villages on chunk load — slice 1 scans around players every 100 ticks, which avoids touching POI data during chunk loading
- Serializing in-flight task state — tasks are rebuilt by re-planning from `CitizenData` and `VillageData` after a load, which the Task 14 reload test proves

---

### Task 1: project scaffold, structure generator, test harness

**Files:**
- Create: `build.gradle`
- Create: `settings.gradle`
- Create: `gradle.properties`
- Create: `gradlew`
- Create: `gradlew.bat`
- Create: `gradle/wrapper/gradle-wrapper.jar`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `mise.toml`
- Create: `.gitignore`
- Create: `.gitattributes`
- Create: `src/main/templates/META-INF/neoforge.mods.toml`
- Create: `src/main/java/dev/andreymudri/villagercity/VillagerCity.java`
- Create: `src/main/java/dev/andreymudri/villagercity/gametest/GameTestSupport.java`
- Create: `src/main/java/dev/andreymudri/villagercity/gametest/SmokeTests.java`
- Create: `tools/nbt_structures.py`
- Create: `src/main/resources/data/villagercity/structure/empty.nbt`
- Create: `src/main/resources/data/villagercity/structure/test_area.nbt`
- Create: `src/main/resources/data/villagercity/structure/blueprint/starter_house.nbt`
- Create: `scripts/gametest.sh`
- Test: `src/test/java/dev/andreymudri/villagercity/VillagerCityTest.java`

- [ ] **Step 1:** Copy the Gradle wrapper and git attributes from the verified MDK checkout (commit `16ba484` of `github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle`):

```bash
R=~/.cache/villagercity-research/mdk
cp "$R/gradlew" "$R/gradlew.bat" "$R/.gitattributes" .
mkdir -p gradle/wrapper && cp "$R/gradle/wrapper/gradle-wrapper.jar" "$R/gradle/wrapper/gradle-wrapper.properties" gradle/wrapper/
chmod +x gradlew
```

- [ ] **Step 2:** Create `mise.toml`:

```toml
[tools]
java = "temurin-21"
```

- [ ] **Step 3:** Create `settings.gradle`:

```groovy
pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

plugins {
    id 'org.gradle.toolchains.foojay-resolver-convention' version '1.0.0'
}
```

- [ ] **Step 4:** Create `gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx1G
org.gradle.daemon=false
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true

parchment_minecraft_version=1.21.1
parchment_mappings_version=2024.11.17
minecraft_version=1.21.1
minecraft_version_range=[1.21.1]
neo_version=21.1.250
loader_version_range=[1,)

mod_id=villagercity
mod_name=Villager City
mod_license=All Rights Reserved
mod_version=0.1.0
mod_group_id=dev.andreymudri.villagercity
```

- [ ] **Step 5:** Create `build.gradle`:

```groovy
plugins {
    id 'java-library'
    id 'net.neoforged.moddev' version '2.0.147'
    id 'idea'
}

tasks.named('wrapper', Wrapper).configure {
    distributionType = Wrapper.DistributionType.BIN
}

version = mod_version
group = mod_group_id

base {
    archivesName = mod_id
}

java.toolchain.languageVersion = JavaLanguageVersion.of(21)

neoForge {
    version = project.neo_version

    parchment {
        mappingsVersion = project.parchment_mappings_version
        minecraftVersion = project.parchment_minecraft_version
    }

    runs {
        client {
            client()
            systemProperty 'neoforge.enabledGameTestNamespaces', project.mod_id
        }
        server {
            server()
            programArgument '--nogui'
            systemProperty 'neoforge.enabledGameTestNamespaces', project.mod_id
        }
        gameTestServer {
            type = "gameTestServer"
            systemProperty 'neoforge.enabledGameTestNamespaces', project.mod_id
        }
        configureEach {
            systemProperty 'forge.logging.markers', 'REGISTRIES'
            logLevel = org.slf4j.event.Level.INFO
        }
    }

    mods {
        "${mod_id}" {
            sourceSet(sourceSets.main)
        }
    }

    unitTest {
        enable()
        testedMod = mods."${mod_id}"
    }
}

dependencies {
    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

tasks.named('test', Test) {
    useJUnitPlatform()
}

var generateModMetadata = tasks.register("generateModMetadata", ProcessResources) {
    var replaceProperties = [
            minecraft_version      : minecraft_version,
            minecraft_version_range: minecraft_version_range,
            neo_version            : neo_version,
            loader_version_range   : loader_version_range,
            mod_id                 : mod_id,
            mod_name               : mod_name,
            mod_license            : mod_license,
            mod_version            : mod_version,
    ]
    inputs.properties replaceProperties
    expand replaceProperties
    from "src/main/templates"
    into "build/generated/sources/modMetadata"
}
sourceSets.main.resources.srcDir generateModMetadata
neoForge.ideSyncTask generateModMetadata

tasks.withType(JavaCompile).configureEach {
    options.encoding = 'UTF-8'
}

idea {
    module {
        downloadSources = true
        downloadJavadoc = true
    }
}
```

- [ ] **Step 6:** Create `src/main/templates/META-INF/neoforge.mods.toml`:

```toml
modLoader="javafml"
loaderVersion="${loader_version_range}"
license="${mod_license}"

[[mods]]
modId="${mod_id}"
version="${mod_version}"
displayName="${mod_name}"
description='''
Villages that grow on their own: villagers gather real resources, build, and expand.
'''

[[dependencies.${mod_id}]]
modId="neoforge"
type="required"
versionRange="[${neo_version},)"
ordering="NONE"
side="BOTH"

[[dependencies.${mod_id}]]
modId="minecraft"
type="required"
versionRange="${minecraft_version_range}"
ordering="NONE"
side="BOTH"
```

- [ ] **Step 7:** Create `.gitignore`:

```gitignore
.gradle
build/
!gradle/wrapper/gradle-wrapper.jar
.idea/
*.iml
out/
.vscode/
bin/
run/
repo/
```

- [ ] **Step 8:** Create `src/main/java/dev/andreymudri/villagercity/VillagerCity.java`:

```java
package dev.andreymudri.villagercity;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(VillagerCity.MODID)
public final class VillagerCity {
    public static final String MODID = "villagercity";
    public static final Logger LOGGER = LogUtils.getLogger();

    public VillagerCity(IEventBus modBus) {
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
```

- [ ] **Step 9:** Create `tools/nbt_structures.py` — it writes vanilla structure-template NBT with the standard library only. It was run against Minecraft 1.21.1: the blueprint loads as 125 blocks and places with both bed halves and the door intact.

```python
#!/usr/bin/env python3
"""Writes the vanilla structure-template NBT files this mod ships.

Usage: python3 tools/nbt_structures.py            # (re)generate all files
       python3 tools/nbt_structures.py --check    # exit 1 if any file on disk differs

Minecraft 1.21.1 reads templates from data/<ns>/structure/<path>.nbt (gzip, big-endian NBT).
"""
import gzip
import io
import struct
import sys
from pathlib import Path

DATA_VERSION = 3955  # Minecraft 1.21.1
ROOT = Path(__file__).resolve().parent.parent
STRUCTURE_DIR = ROOT / "src/main/resources/data/villagercity/structure"

TAG_END, TAG_INT, TAG_STRING, TAG_LIST, TAG_COMPOUND = 0, 3, 8, 9, 10


def _str(out, s):
    b = s.encode("utf-8")
    out.write(struct.pack(">H", len(b)))
    out.write(b)


def _tag_type(v):
    if isinstance(v, int):
        return TAG_INT
    if isinstance(v, str):
        return TAG_STRING
    if isinstance(v, list):
        return TAG_LIST
    if isinstance(v, dict):
        return TAG_COMPOUND
    raise TypeError(type(v))


def _payload(out, v):
    t = _tag_type(v)
    if t == TAG_INT:
        out.write(struct.pack(">i", v))
    elif t == TAG_STRING:
        _str(out, v)
    elif t == TAG_LIST:
        elem = _tag_type(v[0]) if v else TAG_END
        out.write(struct.pack(">bi", elem, len(v)))
        for e in v:
            _payload(out, e)
    else:
        for k, e in v.items():
            out.write(struct.pack(">b", _tag_type(e)))
            _str(out, k)
            _payload(out, e)
        out.write(struct.pack(">b", TAG_END))


def encode(root):
    raw = io.BytesIO()
    raw.write(struct.pack(">b", TAG_COMPOUND))
    _str(raw, "")
    _payload(raw, root)
    buf = io.BytesIO()
    # mtime=0 keeps the output byte-identical across runs, so --check works.
    with gzip.GzipFile(fileobj=buf, mode="wb", mtime=0) as gz:
        gz.write(raw.getvalue())
    return buf.getvalue()


def template(size, blocks):
    """blocks: dict[(x, y, z)] -> (name, {prop: value})."""
    palette, index, entries = [], {}, []
    for pos in sorted(blocks, key=lambda p: (p[1], p[2], p[0])):
        name, props = blocks[pos]
        key = (name, tuple(sorted(props.items())))
        if key not in index:
            index[key] = len(palette)
            state = {"Name": name}
            if props:
                state["Properties"] = dict(sorted(props.items()))
            palette.append(state)
        entries.append({"pos": list(pos), "state": index[key]})
    return {
        "DataVersion": DATA_VERSION,
        "size": list(size),
        "palette": palette,
        "blocks": entries,
        "entities": [],
    }


def starter_house():
    """5x5x5 oak house: cobblestone floor, log corners, plank walls and roof,
    a door in the z=0 wall, glass windows east/west, a bed inside.
    Interior cells are explicit air so the builder clears obstructions."""
    b = {}
    for x in range(5):
        for z in range(5):
            b[(x, 0, z)] = ("minecraft:cobblestone", {})
            b[(x, 4, z)] = ("minecraft:oak_planks", {})
            for y in (1, 2, 3):
                edge = x in (0, 4) or z in (0, 4)
                corner = x in (0, 4) and z in (0, 4)
                if corner:
                    b[(x, y, z)] = ("minecraft:oak_log", {"axis": "y"})
                elif edge:
                    b[(x, y, z)] = ("minecraft:oak_planks", {})
                else:
                    b[(x, y, z)] = ("minecraft:air", {})
    door = {"facing": "south", "hinge": "left", "open": "false", "powered": "false"}
    b[(2, 1, 0)] = ("minecraft:oak_door", dict(door, half="lower"))
    b[(2, 2, 0)] = ("minecraft:oak_door", dict(door, half="upper"))
    b[(0, 2, 2)] = ("minecraft:glass", {})
    b[(4, 2, 2)] = ("minecraft:glass", {})
    b[(1, 1, 3)] = ("minecraft:red_bed", {"facing": "north", "part": "foot", "occupied": "false"})
    b[(1, 1, 2)] = ("minecraft:red_bed", {"facing": "north", "part": "head", "occupied": "false"})
    return template((5, 5, 5), b)


def empty(size):
    return template(size, {})


FILES = {
    "empty.nbt": lambda: empty((9, 5, 9)),
    "test_area.nbt": lambda: empty((48, 12, 48)),
    "blueprint/starter_house.nbt": starter_house,
}


def main():
    check = "--check" in sys.argv
    stale = []
    for rel, build in FILES.items():
        path = STRUCTURE_DIR / rel
        data = encode(build())
        if check:
            if not path.exists() or path.read_bytes() != data:
                stale.append(rel)
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
            print(f"wrote {path.relative_to(ROOT)} ({len(data)} bytes)")
    if stale:
        print("stale structure files: " + ", ".join(stale))
        sys.exit(1)


if __name__ == "__main__":
    main()
```

- [ ] **Step 10:** Generate the three structure files and confirm the check passes:

```bash
python3 tools/nbt_structures.py && python3 tools/nbt_structures.py --check
```

- [ ] **Step 11:** Create `src/main/java/dev/andreymudri/villagercity/gametest/GameTestSupport.java`. The test area is 48×12×48; after setup the game puts stone at relative y=-1 and y=-2 and a barrier ceiling at y=13, so `prepareArea` lays a grass floor at y=0 and everything stands at y=1.

```java
package dev.andreymudri.villagercity.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;

public final class GameTestSupport {
    public static final String TEST_AREA = "test_area";
    public static final int AREA_SIZE = 48;
    public static final int DAY_TIME = 1000;

    private GameTestSupport() {
    }

    /**
     * Grass floor at relative y=0 over the whole area, and a frozen daytime so villagers are never
     * scheduled to sleep mid-test (the GameTest server leaves the daylight cycle on).
     */
    public static void prepareArea(GameTestHelper helper) {
        for (int x = 0; x < AREA_SIZE; x++) {
            for (int z = 0; z < AREA_SIZE; z++) {
                helper.setBlock(x, 0, z, Blocks.GRASS_BLOCK);
            }
        }
        helper.getLevel().getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, helper.getLevel().getServer());
        helper.getLevel().setDayTime(DAY_TIME);
    }

    public static Villager spawnVillager(GameTestHelper helper, int x, int y, int z) {
        Villager villager = helper.spawn(EntityType.VILLAGER, x, y, z);
        villager.setPersistenceRequired();
        return villager;
    }
}
```

- [ ] **Step 12:** Create `src/main/java/dev/andreymudri/villagercity/gametest/SmokeTests.java`. It also guarantees the GameTest server always has at least one test (with none, the server crashes but Gradle still exits 0):

```java
package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class SmokeTests {
    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void testAreaLoads(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        helper.assertBlockPresent(Blocks.GRASS_BLOCK, new BlockPos(24, 0, 24));
        helper.assertBlockPresent(Blocks.AIR, new BlockPos(24, 1, 24));
        helper.succeed();
    }
}
```

- [ ] **Step 13:** Create `scripts/gametest.sh` and make it executable (`chmod +x`). A passing run must print the "All N required tests passed" summary; the exit code alone is not trusted.

```bash
#!/usr/bin/env bash
# Runs every GameTest. Fails unless the server reports that all required tests passed.
set -uo pipefail
cd "$(dirname "$0")/.."
export TMPDIR="${TMPDIR:-$HOME/.cache/tmp-villagercity}"
mkdir -p "$TMPDIR" build
log="build/gametest.log"
./gradlew --no-daemon -Dorg.gradle.workers.max=4 runGameTestServer >"$log" 2>&1
status=$?
/usr/bin/grep -a -E "required tests passed|tests? failed|GameTestAssertException|failed!" "$log" | tail -40
if [ "$status" -ne 0 ]; then
    echo "gametest: gradle exited $status (full log: $log)"
    tail -60 "$log"
    exit "$status"
fi
if ! /usr/bin/grep -a -q -E "All [0-9]+ required tests passed" "$log"; then
    echo "gametest: no passing summary found (full log: $log)"
    tail -60 "$log"
    exit 1
fi
```

- [ ] **Step 14:** Create `src/test/java/dev/andreymudri/villagercity/VillagerCityTest.java`:

```java
package dev.andreymudri.villagercity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class VillagerCityTest {
    @Test
    void idUsesModNamespace() {
        assertEquals("villagercity:blueprint/starter_house", VillagerCity.id("blueprint/starter_house").toString());
    }
}
```

- [ ] **Step 15:** Run `./gradlew --no-daemon -Dorg.gradle.workers.max=4 build` and `scripts/gametest.sh`; both must pass ("All 1 required tests passed").

- [ ] **Step 16:** Commit: `build: NeoForge 1.21.1 scaffold with GameTest harness and structure generator`

### Task 2: contribution ledger

**Files:**
- Create: `src/main/java/dev/andreymudri/villagercity/village/ContributionCategory.java`
- Create: `src/main/java/dev/andreymudri/villagercity/village/ContributionLedger.java`
- Test: `src/test/java/dev/andreymudri/villagercity/village/ContributionLedgerTest.java`

**Depends:** T1

- [ ] **Step 1:** Write the failing test `src/test/java/dev/andreymudri/villagercity/village/ContributionLedgerTest.java`:

```java
package dev.andreymudri.villagercity.village;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContributionLedgerTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void recordsAndSumsPerCategory() {
        ContributionLedger ledger = new ContributionLedger();
        ledger.record(A, ContributionCategory.DEPOSIT, 5);
        ledger.record(A, ContributionCategory.DEPOSIT, 7);
        assertEquals(12, ledger.total(A, ContributionCategory.DEPOSIT));
        assertEquals(12, ledger.total(A));
        assertEquals(0, ledger.total(B));
    }

    @Test
    void rejectsNonPositiveAmounts() {
        ContributionLedger ledger = new ContributionLedger();
        assertThrows(IllegalArgumentException.class, () -> ledger.record(A, ContributionCategory.DEPOSIT, 0));
        assertThrows(IllegalArgumentException.class, () -> ledger.record(A, ContributionCategory.DEPOSIT, -3));
    }

    @Test
    void emptyLedgerHasNoTopContributor() {
        assertEquals(Optional.empty(), new ContributionLedger().topContributor());
    }

    @Test
    void topContributorBreaksTiesBySmallestUuid() {
        ContributionLedger ledger = new ContributionLedger();
        ledger.record(B, ContributionCategory.DEPOSIT, 10);
        ledger.record(A, ContributionCategory.DEPOSIT, 10);
        assertEquals(Optional.of(A), ledger.topContributor());
        ledger.record(B, ContributionCategory.DEPOSIT, 1);
        assertEquals(Optional.of(B), ledger.topContributor());
    }

    @Test
    void snapshotRoundTripsAndIsDetached() {
        ContributionLedger ledger = new ContributionLedger();
        ledger.record(A, ContributionCategory.DEPOSIT, 3);
        Map<UUID, Map<ContributionCategory, Long>> snapshot = ledger.snapshot();
        ledger.record(A, ContributionCategory.DEPOSIT, 4);
        assertEquals(3L, snapshot.get(A).get(ContributionCategory.DEPOSIT));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put(B, Map.of()));
        ContributionLedger copy = ContributionLedger.fromSnapshot(snapshot);
        assertEquals(3, copy.total(A, ContributionCategory.DEPOSIT));
        assertTrue(copy.topContributor().isPresent());
    }
}
```

- [ ] **Step 2:** Run `./gradlew --no-daemon -Dorg.gradle.workers.max=4 test` — it fails to compile (classes missing).

- [ ] **Step 3:** Create `src/main/java/dev/andreymudri/villagercity/village/ContributionCategory.java`:

```java
package dev.andreymudri.villagercity.village;

import net.minecraft.util.StringRepresentable;

/** What a player did for a village. Slice 1 records deposits only. */
public enum ContributionCategory implements StringRepresentable {
    DEPOSIT("deposit");

    private final String serializedName;

    ContributionCategory(String serializedName) {
        this.serializedName = serializedName;
    }

    @Override
    public String getSerializedName() {
        return serializedName;
    }
}
```

- [ ] **Step 4:** Create `src/main/java/dev/andreymudri/villagercity/village/ContributionLedger.java`:

```java
package dev.andreymudri.villagercity.village;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Per-player contribution totals for one village. */
public final class ContributionLedger {
    private final Map<UUID, EnumMap<ContributionCategory, Long>> totals = new HashMap<>();

    public void record(UUID player, ContributionCategory category, long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("contribution amount must be positive: " + amount);
        }
        totals.computeIfAbsent(player, k -> new EnumMap<>(ContributionCategory.class)).merge(category, amount, Long::sum);
    }

    public long total(UUID player, ContributionCategory category) {
        EnumMap<ContributionCategory, Long> byCategory = totals.get(player);
        return byCategory == null ? 0 : byCategory.getOrDefault(category, 0L);
    }

    public long total(UUID player) {
        EnumMap<ContributionCategory, Long> byCategory = totals.get(player);
        return byCategory == null ? 0 : byCategory.values().stream().mapToLong(Long::longValue).sum();
    }

    /** The player with the highest total; ties go to the smallest UUID so the result is deterministic. */
    public Optional<UUID> topContributor() {
        return totals.keySet().stream()
                .min(Comparator.comparingLong((UUID player) -> -total(player)).thenComparing(Comparator.naturalOrder()));
    }

    /** Deep, immutable copy of the totals. */
    public Map<UUID, Map<ContributionCategory, Long>> snapshot() {
        Map<UUID, Map<ContributionCategory, Long>> copy = new HashMap<>();
        totals.forEach((player, byCategory) -> copy.put(player, Map.copyOf(byCategory)));
        return Map.copyOf(copy);
    }

    public static ContributionLedger fromSnapshot(Map<UUID, Map<ContributionCategory, Long>> snapshot) {
        ContributionLedger ledger = new ContributionLedger();
        snapshot.forEach((player, byCategory) -> byCategory.forEach((category, amount) -> {
            if (amount > 0) {
                ledger.record(player, category, amount);
            }
        }));
        return ledger;
    }
}
```

- [ ] **Step 5:** Run `./gradlew --no-daemon -Dorg.gradle.workers.max=4 build` — all tests pass.

- [ ] **Step 6:** Commit: `feat: contribution ledger`

### Task 3: village data model and codecs

**Files:**
- Create: `src/main/java/dev/andreymudri/villagercity/village/VillageAge.java`
- Create: `src/main/java/dev/andreymudri/villagercity/village/Footprint.java`
- Create: `src/main/java/dev/andreymudri/villagercity/village/BuildingRecord.java`
- Create: `src/main/java/dev/andreymudri/villagercity/village/Plot.java`
- Create: `src/main/java/dev/andreymudri/villagercity/village/VillageData.java`
- Create: `src/main/java/dev/andreymudri/villagercity/village/VillageCodecs.java`
- Test: `src/test/java/dev/andreymudri/villagercity/village/VillageDataTest.java`

**Depends:** T2

- [ ] **Step 1:** Write the failing test `src/test/java/dev/andreymudri/villagercity/village/VillageDataTest.java`:

```java
package dev.andreymudri.villagercity.village;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.Test;

class VillageDataTest {
    private static final Vec3i HOUSE = new Vec3i(5, 5, 5);

    @Test
    void footprintIntersectsAndInflates() {
        Footprint a = Footprint.of(new BlockPos(0, 64, 0), HOUSE);
        assertEquals(new Footprint(0, 0, 4, 4), a);
        assertTrue(a.intersects(new Footprint(4, 4, 6, 6)));
        assertFalse(a.intersects(new Footprint(5, 0, 9, 4)));
        assertTrue(a.inflate(1).intersects(new Footprint(5, 0, 9, 4)));
        assertThrows(IllegalArgumentException.class, () -> new Footprint(3, 0, 2, 0));
    }

    @Test
    void addHouseExpandsRadiusToCoverIt() {
        VillageData village = new VillageData(UUID.randomUUID(), new BlockPos(0, 64, 0), 10);
        village.addHouse(new BuildingRecord("villagercity:blueprint/starter_house", new BlockPos(20, 64, 0), HOUSE));
        assertEquals(1, village.houseCount());
        // farthest corner (24, 4): ceil(sqrt(592)) = 25, plus padding 8
        assertEquals(33, village.radius());
        assertTrue(village.contains(new BlockPos(33, 70, 0)));
        assertFalse(village.contains(new BlockPos(34, 64, 0)));
    }

    @Test
    void addHouseNeverShrinksRadius() {
        VillageData village = new VillageData(UUID.randomUUID(), new BlockPos(0, 64, 0), 48);
        village.addHouse(new BuildingRecord("minecraft:home", new BlockPos(2, 64, 2), new Vec3i(1, 1, 1)));
        assertEquals(48, village.radius());
    }

    @Test
    void plotsAreFoundByBuilderAndRemovedById() {
        VillageData village = new VillageData(UUID.randomUUID(), BlockPos.ZERO, 16);
        UUID builder = UUID.randomUUID();
        Plot plot = new Plot(UUID.randomUUID(), "villagercity:blueprint/starter_house", new BlockPos(5, 0, 5), HOUSE, builder);
        village.addPlot(plot);
        assertEquals(plot, village.plotBuiltBy(builder).orElseThrow());
        assertTrue(village.plotBuiltBy(UUID.randomUUID()).isEmpty());
        assertTrue(village.removePlot(plot.id()));
        assertTrue(village.plots().isEmpty());
    }

    @Test
    void occupiedFootprintsCoverCenterStorehouseHousesAndPlots() {
        VillageData village = new VillageData(UUID.randomUUID(), new BlockPos(0, 64, 0), 32);
        village.setStorehousePos(new BlockPos(3, 64, 0));
        village.addHouse(new BuildingRecord("minecraft:home", new BlockPos(10, 64, 10), new Vec3i(1, 1, 1)));
        village.addPlot(new Plot(UUID.randomUUID(), "x:y", new BlockPos(-10, 64, -10), HOUSE, UUID.randomUUID()));
        assertEquals(4, village.occupiedFootprints().size());
        assertTrue(village.occupiedFootprints().contains(new Footprint(0, 0, 0, 0)));
        assertTrue(village.occupiedFootprints().contains(new Footprint(3, 0, 3, 0)));
        assertTrue(village.occupiedFootprints().contains(new Footprint(-10, -10, -6, -6)));
    }

    @Test
    void newVillageStartsInDarkAgeAndManaged() {
        VillageData village = new VillageData(UUID.randomUUID(), BlockPos.ZERO, VillageData.DEFAULT_RADIUS);
        assertEquals(VillageAge.DARK, village.age());
        assertTrue(village.managed());
        assertEquals(null, village.storehousePos());
    }
}
```

- [ ] **Step 2:** Run `./gradlew --no-daemon -Dorg.gradle.workers.max=4 test` — compilation fails.

- [ ] **Step 3:** Create `src/main/java/dev/andreymudri/villagercity/village/VillageAge.java`:

```java
package dev.andreymudri.villagercity.village;

import net.minecraft.util.StringRepresentable;

public enum VillageAge implements StringRepresentable {
    DARK("dark"),
    FEUDAL("feudal"),
    CASTLE("castle"),
    IMPERIAL("imperial"),
    NETHERITE("netherite");

    private final String serializedName;

    VillageAge(String serializedName) {
        this.serializedName = serializedName;
    }

    @Override
    public String getSerializedName() {
        return serializedName;
    }
}
```

- [ ] **Step 4:** Create `src/main/java/dev/andreymudri/villagercity/village/Footprint.java`:

```java
package dev.andreymudri.villagercity.village;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

/** Inclusive horizontal rectangle in block coordinates. */
public record Footprint(int minX, int minZ, int maxX, int maxZ) {
    public Footprint {
        if (maxX < minX || maxZ < minZ) {
            throw new IllegalArgumentException("inverted footprint: " + minX + "," + minZ + " .. " + maxX + "," + maxZ);
        }
    }

    public static Footprint of(BlockPos origin, Vec3i size) {
        return new Footprint(origin.getX(), origin.getZ(), origin.getX() + size.getX() - 1, origin.getZ() + size.getZ() - 1);
    }

    public boolean intersects(Footprint other) {
        return minX <= other.maxX && maxX >= other.minX && minZ <= other.maxZ && maxZ >= other.minZ;
    }

    public Footprint inflate(int by) {
        return new Footprint(minX - by, minZ - by, maxX + by, maxZ + by);
    }

    public boolean contains(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }
}
```

- [ ] **Step 5:** Create `src/main/java/dev/andreymudri/villagercity/village/BuildingRecord.java`:

```java
package dev.andreymudri.villagercity.village;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

/** A finished building. Vanilla homes adopted at registration use blueprint "minecraft:home" and size 1x1x1. */
public record BuildingRecord(String blueprint, BlockPos origin, Vec3i size) {
    public static final String VANILLA_HOME = "minecraft:home";

    public BuildingRecord {
        origin = origin.immutable();
    }

    public Footprint footprint() {
        return Footprint.of(origin, size);
    }
}
```

- [ ] **Step 6:** Create `src/main/java/dev/andreymudri/villagercity/village/Plot.java`:

```java
package dev.andreymudri.villagercity.village;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

/** A building in progress, claimed by one builder. */
public record Plot(UUID id, String blueprint, BlockPos origin, Vec3i size, UUID builder) {
    public Plot {
        origin = origin.immutable();
    }

    public Footprint footprint() {
        return Footprint.of(origin, size);
    }
}
```

- [ ] **Step 7:** Create `src/main/java/dev/andreymudri/villagercity/village/VillageData.java`:

```java
package dev.andreymudri.villagercity.village;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

/**
 * Everything a village knows about itself. Holds no entity or level references, so it can be
 * saved and driven by either future offscreen-simulation mechanism.
 */
public final class VillageData {
    public static final int DEFAULT_RADIUS = 48;
    public static final int RADIUS_PADDING = 8;
    private static final Vec3i SINGLE_BLOCK = new Vec3i(1, 1, 1);

    private final UUID id;
    private final BlockPos center;
    private int radius;
    private VillageAge age;
    private @Nullable BlockPos storehousePos;
    private final List<BuildingRecord> houses;
    private final List<Plot> plots;
    private final ContributionLedger ledger;
    private boolean managed;

    public VillageData(UUID id, BlockPos center, int radius) {
        this(id, center, radius, VillageAge.DARK, null, List.of(), List.of(), new ContributionLedger(), true);
    }

    public VillageData(UUID id, BlockPos center, int radius, VillageAge age, @Nullable BlockPos storehousePos,
                       List<BuildingRecord> houses, List<Plot> plots, ContributionLedger ledger, boolean managed) {
        this.id = id;
        this.center = center.immutable();
        this.radius = radius;
        this.age = age;
        this.storehousePos = storehousePos == null ? null : storehousePos.immutable();
        this.houses = new ArrayList<>(houses);
        this.plots = new ArrayList<>(plots);
        this.ledger = ledger;
        this.managed = managed;
    }

    public UUID id() {
        return id;
    }

    public BlockPos center() {
        return center;
    }

    public int radius() {
        return radius;
    }

    public void setRadius(int radius) {
        this.radius = radius;
    }

    public VillageAge age() {
        return age;
    }

    public @Nullable BlockPos storehousePos() {
        return storehousePos;
    }

    public void setStorehousePos(@Nullable BlockPos pos) {
        this.storehousePos = pos == null ? null : pos.immutable();
    }

    public ContributionLedger ledger() {
        return ledger;
    }

    /** When false, the village ticker leaves this village alone (used by focused GameTests). */
    public boolean managed() {
        return managed;
    }

    public void setManaged(boolean managed) {
        this.managed = managed;
    }

    public List<BuildingRecord> houses() {
        return Collections.unmodifiableList(houses);
    }

    public int houseCount() {
        return houses.size();
    }

    public void addHouse(BuildingRecord house) {
        houses.add(house);
        radius = Math.max(radius, farthestCorner(house.footprint()) + RADIUS_PADDING);
    }

    public List<Plot> plots() {
        return Collections.unmodifiableList(plots);
    }

    public void addPlot(Plot plot) {
        plots.add(plot);
    }

    public boolean removePlot(UUID plotId) {
        return plots.removeIf(plot -> plot.id().equals(plotId));
    }

    public Optional<Plot> plotBuiltBy(UUID builder) {
        return plots.stream().filter(plot -> plot.builder().equals(builder)).findFirst();
    }

    /** Horizontal (cylindrical) membership test. */
    public boolean contains(BlockPos pos) {
        long dx = pos.getX() - center.getX();
        long dz = pos.getZ() - center.getZ();
        return dx * dx + dz * dz <= (long) radius * radius;
    }

    public List<Footprint> occupiedFootprints() {
        List<Footprint> occupied = new ArrayList<>();
        occupied.add(Footprint.of(center, SINGLE_BLOCK));
        if (storehousePos != null) {
            occupied.add(Footprint.of(storehousePos, SINGLE_BLOCK));
        }
        houses.forEach(house -> occupied.add(house.footprint()));
        plots.forEach(plot -> occupied.add(plot.footprint()));
        return occupied;
    }

    private int farthestCorner(Footprint footprint) {
        int farthest = 0;
        for (int x : new int[] {footprint.minX(), footprint.maxX()}) {
            for (int z : new int[] {footprint.minZ(), footprint.maxZ()}) {
                long dx = x - center.getX();
                long dz = z - center.getZ();
                farthest = Math.max(farthest, (int) Math.ceil(Math.sqrt(dx * dx + dz * dz)));
            }
        }
        return farthest;
    }
}
```

- [ ] **Step 8:** Create `src/main/java/dev/andreymudri/villagercity/village/VillageCodecs.java`:

```java
package dev.andreymudri.villagercity.village;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.Vec3i;
import net.minecraft.util.StringRepresentable;

public final class VillageCodecs {
    public static final Codec<ContributionCategory> CATEGORY = StringRepresentable.fromEnum(ContributionCategory::values);
    public static final Codec<VillageAge> AGE = StringRepresentable.fromEnum(VillageAge::values);

    public static final Codec<ContributionLedger> LEDGER = Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.unboundedMap(CATEGORY, Codec.LONG))
            .xmap(ContributionLedger::fromSnapshot, ContributionLedger::snapshot);

    public static final Codec<BuildingRecord> BUILDING = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("blueprint").forGetter(BuildingRecord::blueprint),
            BlockPos.CODEC.fieldOf("origin").forGetter(BuildingRecord::origin),
            Vec3i.CODEC.fieldOf("size").forGetter(BuildingRecord::size)
    ).apply(i, BuildingRecord::new));

    public static final Codec<Plot> PLOT = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(Plot::id),
            Codec.STRING.fieldOf("blueprint").forGetter(Plot::blueprint),
            BlockPos.CODEC.fieldOf("origin").forGetter(Plot::origin),
            Vec3i.CODEC.fieldOf("size").forGetter(Plot::size),
            UUIDUtil.CODEC.fieldOf("builder").forGetter(Plot::builder)
    ).apply(i, Plot::new));

    public static final Codec<VillageData> VILLAGE = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(VillageData::id),
            BlockPos.CODEC.fieldOf("center").forGetter(VillageData::center),
            Codec.INT.fieldOf("radius").forGetter(VillageData::radius),
            AGE.optionalFieldOf("age", VillageAge.DARK).forGetter(VillageData::age),
            BlockPos.CODEC.optionalFieldOf("storehouse").forGetter(v -> java.util.Optional.ofNullable(v.storehousePos())),
            BUILDING.listOf().fieldOf("houses").forGetter(VillageData::houses),
            PLOT.listOf().fieldOf("plots").forGetter(VillageData::plots),
            LEDGER.fieldOf("ledger").forGetter(VillageData::ledger),
            Codec.BOOL.optionalFieldOf("managed", true).forGetter(VillageData::managed)
    ).apply(i, (id, center, radius, age, storehouse, houses, plots, ledger, managed) ->
            new VillageData(id, center, radius, age, storehouse.orElse(null), houses, plots, ledger, managed)));

    private VillageCodecs() {
    }
}
```

- [ ] **Step 9:** Run `./gradlew --no-daemon -Dorg.gradle.workers.max=4 build` — all tests pass. (The codec round-trip is exercised by the registry GameTest in Task 6.)

- [ ] **Step 10:** Commit: `feat: village data model and codecs`

### Task 4: plot rules and plot planner

**Files:**
- Create: `src/main/java/dev/andreymudri/villagercity/village/plot/Column.java`
- Create: `src/main/java/dev/andreymudri/villagercity/village/plot/PlotRules.java`
- Create: `src/main/java/dev/andreymudri/villagercity/village/plot/PlotPlanner.java`
- Create: `src/main/java/dev/andreymudri/villagercity/gametest/PlotPlannerTests.java`
- Test: `src/test/java/dev/andreymudri/villagercity/village/plot/PlotRulesTest.java`

**Depends:** T3

- [ ] **Step 1:** Write the failing test `src/test/java/dev/andreymudri/villagercity/village/plot/PlotRulesTest.java`:

```java
package dev.andreymudri.villagercity.village.plot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.andreymudri.villagercity.village.Footprint;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlotRulesTest {
    private static Column ground(int y) {
        return new Column(y, true, false);
    }

    @Test
    void flatNaturalGroundIsBuildable() {
        assertTrue(PlotRules.isBuildable(List.of(ground(64), ground(65), ground(64))));
        assertEquals(65, PlotRules.buildY(List.of(ground(64), ground(65), ground(64))));
    }

    @Test
    void rejectsSteepMissingArtificialOrWetGround() {
        assertFalse(PlotRules.isBuildable(List.of(ground(64), ground(66))));
        assertFalse(PlotRules.isBuildable(List.of(ground(64), Column.MISSING)));
        assertFalse(PlotRules.isBuildable(List.of(ground(64), new Column(64, false, false))));
        assertFalse(PlotRules.isBuildable(List.of(ground(64), new Column(64, false, true))));
        assertFalse(PlotRules.isBuildable(List.of()));
    }

    @Test
    void overlapIncludesTheMargin() {
        Footprint house = new Footprint(0, 0, 4, 4);
        assertTrue(PlotRules.overlapsAny(new Footprint(5, 0, 9, 4), List.of(house)));
        assertFalse(PlotRules.overlapsAny(new Footprint(6, 0, 10, 4), List.of(house)));
    }

    @Test
    void spiralStartsAtCenterAndGrowsByRings() {
        List<int[]> spiral = PlotRules.spiral(2, 1);
        assertEquals(25, spiral.size());
        assertEquals(0, spiral.get(0)[0]);
        assertEquals(0, spiral.get(0)[1]);
        for (int i = 1; i <= 8; i++) {
            assertEquals(1, Math.max(Math.abs(spiral.get(i)[0]), Math.abs(spiral.get(i)[1])));
        }
        Set<Long> unique = new HashSet<>();
        spiral.forEach(o -> unique.add(((long) o[0] << 32) ^ (o[1] & 0xffffffffL)));
        assertEquals(25, unique.size());
    }

    @Test
    void spiralHonoursStep() {
        List<int[]> spiral = PlotRules.spiral(4, 2);
        assertEquals(25, spiral.size());
        spiral.forEach(o -> {
            assertEquals(0, o[0] % 2);
            assertEquals(0, o[1] % 2);
        });
    }

    @Test
    void withinReachChecksEveryCorner() {
        assertTrue(PlotRules.withinReach(new Footprint(-4, -4, 4, 4), 0, 0, 4));
        assertFalse(PlotRules.withinReach(new Footprint(-4, -4, 5, 4), 0, 0, 4));
    }
}
```

- [ ] **Step 2:** Run `./gradlew --no-daemon -Dorg.gradle.workers.max=4 test` — compilation fails.

- [ ] **Step 3:** Create `src/main/java/dev/andreymudri/villagercity/village/plot/Column.java`:

```java
package dev.andreymudri.villagercity.village.plot;

/** One sampled ground column: the first free y above the ground, whether the ground is natural, whether it is fluid. */
public record Column(int groundY, boolean natural, boolean fluid) {
    public static final Column MISSING = new Column(Integer.MIN_VALUE, false, false);

    public boolean present() {
        return groundY != Integer.MIN_VALUE;
    }
}
```

- [ ] **Step 4:** Create `src/main/java/dev/andreymudri/villagercity/village/plot/PlotRules.java`:

```java
package dev.andreymudri.villagercity.village.plot;

import dev.andreymudri.villagercity.village.Footprint;
import java.util.ArrayList;
import java.util.List;

/** Pure plot-validity rules; no world access. */
public final class PlotRules {
    public static final int MAX_HEIGHT_VARIANCE = 1;
    public static final int MARGIN = 1;

    private PlotRules() {
    }

    public static boolean isBuildable(List<Column> columns) {
        if (columns.isEmpty()) {
            return false;
        }
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (Column column : columns) {
            if (!column.present() || !column.natural() || column.fluid()) {
                return false;
            }
            min = Math.min(min, column.groundY());
            max = Math.max(max, column.groundY());
        }
        return max - min <= MAX_HEIGHT_VARIANCE;
    }

    public static int buildY(List<Column> columns) {
        return columns.stream().mapToInt(Column::groundY).max().orElseThrow();
    }

    /** True when the candidate, grown by {@link #MARGIN}, touches any occupied footprint. */
    public static boolean overlapsAny(Footprint candidate, List<Footprint> occupied) {
        Footprint grown = candidate.inflate(MARGIN);
        return occupied.stream().anyMatch(grown::intersects);
    }

    /** Horizontal offsets ring by ring (Chebyshev distance), center first; within a ring ordered by dz then dx. */
    public static List<int[]> spiral(int radius, int step) {
        if (radius < 0 || step < 1) {
            throw new IllegalArgumentException("radius must be >= 0 and step >= 1");
        }
        List<int[]> offsets = new ArrayList<>();
        offsets.add(new int[] {0, 0});
        for (int ring = step; ring <= radius; ring += step) {
            for (int dz = -ring; dz <= ring; dz += step) {
                for (int dx = -ring; dx <= ring; dx += step) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) == ring) {
                        offsets.add(new int[] {dx, dz});
                    }
                }
            }
        }
        return offsets;
    }

    public static boolean withinReach(Footprint footprint, int centerX, int centerZ, int reach) {
        return Math.abs(footprint.minX() - centerX) <= reach && Math.abs(footprint.maxX() - centerX) <= reach
                && Math.abs(footprint.minZ() - centerZ) <= reach && Math.abs(footprint.maxZ() - centerZ) <= reach;
    }
}
```

- [ ] **Step 5:** Create `src/main/java/dev/andreymudri/villagercity/village/plot/PlotPlanner.java`. Ground is found by scanning each column downward from `center.y + 8`, not from a heightmap: heightmaps see the GameTest barrier ceiling and any roof, while a column scan that starts buried (a hillside or a building) is rejected.

```java
package dev.andreymudri.villagercity.village.plot;

import dev.andreymudri.villagercity.village.Footprint;
import dev.andreymudri.villagercity.village.VillageData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Slice 1 plot rule: spiral out from the village center and take the first buildable spot. */
public final class PlotPlanner {
    public static final int SEARCH_MARGIN = 16;
    public static final int STEP = 2;
    public static final int SCAN_UP = 8;
    public static final int SCAN_DOWN = 8;
    public static final int MAX_CANDIDATES = 1500;

    private PlotPlanner() {
    }

    /** Returns the origin (minimum corner, at the first free y above ground) of a buildable plot. */
    public static Optional<BlockPos> find(ServerLevel level, VillageData village, Vec3i size) {
        BlockPos center = village.center();
        int reach = village.radius() + SEARCH_MARGIN;
        List<Footprint> occupied = village.occupiedFootprints();
        Map<Long, Column> cache = new HashMap<>();
        int checked = 0;
        for (int[] offset : PlotRules.spiral(reach, STEP)) {
            if (checked++ >= MAX_CANDIDATES) {
                break;
            }
            int minX = center.getX() + offset[0] - size.getX() / 2;
            int minZ = center.getZ() + offset[1] - size.getZ() / 2;
            Footprint footprint = new Footprint(minX, minZ, minX + size.getX() - 1, minZ + size.getZ() - 1);
            if (!PlotRules.withinReach(footprint, center.getX(), center.getZ(), reach)
                    || PlotRules.overlapsAny(footprint, occupied)) {
                continue;
            }
            Footprint area = footprint.inflate(PlotRules.MARGIN);
            List<Column> columns = new ArrayList<>();
            for (int x = area.minX(); x <= area.maxX(); x++) {
                for (int z = area.minZ(); z <= area.maxZ(); z++) {
                    final int cx = x;
                    final int cz = z;
                    columns.add(cache.computeIfAbsent(BlockPos.asLong(cx, 0, cz), k -> sample(level, cx, cz, center.getY())));
                }
            }
            if (PlotRules.isBuildable(columns)) {
                return Optional.of(new BlockPos(minX, PlotRules.buildY(columns), minZ));
            }
        }
        return Optional.empty();
    }

    static Column sample(ServerLevel level, int x, int z, int referenceY) {
        int top = referenceY + SCAN_UP;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, top, z);
        if (!level.isLoaded(pos)) {
            return Column.MISSING;
        }
        for (int y = top; y >= referenceY - SCAN_DOWN; y--) {
            BlockState state = level.getBlockState(pos.setY(y));
            if (!state.getFluidState().isEmpty()) {
                return new Column(y + 1, false, true);
            }
            if (state.blocksMotion() && !state.is(BlockTags.LEAVES)) {
                if (y == top) {
                    return Column.MISSING;
                }
                boolean natural = state.is(BlockTags.DIRT) || state.is(BlockTags.SAND)
                        || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(Blocks.GRAVEL);
                return new Column(y + 1, natural, false);
            }
        }
        return Column.MISSING;
    }
}
```

- [ ] **Step 6:** Create `src/main/java/dev/andreymudri/villagercity/gametest/PlotPlannerTests.java`:

```java
package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.village.BuildingRecord;
import dev.andreymudri.villagercity.village.Footprint;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.plot.PlotPlanner;
import dev.andreymudri.villagercity.village.plot.PlotRules;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class PlotPlannerTests {
    private static final Vec3i HOUSE = new Vec3i(5, 5, 5);

    private static VillageData village(GameTestHelper helper) {
        return new VillageData(UUID.randomUUID(), helper.absolutePos(new BlockPos(24, 1, 24)), 4);
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void findsFlatGroundNearCenter(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = village(helper);
        BlockPos origin = PlotPlanner.find(helper.getLevel(), village, HOUSE).orElseThrow(() -> new AssertionError("no plot found"));
        BlockPos relative = helper.relativePos(origin);
        helper.assertTrue(relative.getY() == 1, "plot should sit on the grass floor, got y=" + relative.getY());
        helper.assertTrue(relative.getX() >= 2 && relative.getX() <= 41 && relative.getZ() >= 2 && relative.getZ() <= 41,
                "plot outside the search reach: " + relative);
        helper.assertFalse(PlotRules.overlapsAny(Footprint.of(origin, HOUSE), village.occupiedFootprints()), "plot overlaps the bell");
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void avoidsWater(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        for (int x = 0; x < GameTestSupport.AREA_SIZE; x++) {
            for (int z = 0; z < 24; z++) {
                helper.setBlock(x, 0, z, Blocks.WATER);
            }
        }
        BlockPos origin = PlotPlanner.find(helper.getLevel(), village(helper), HOUSE).orElseThrow(() -> new AssertionError("no plot found"));
        int relZ = helper.relativePos(origin).getZ();
        helper.assertTrue(relZ >= 25, "plot margin touches water: z=" + relZ);
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void avoidsExistingHouses(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = village(helper);
        village.addHouse(new BuildingRecord("minecraft:home", helper.absolutePos(new BlockPos(22, 1, 22)), HOUSE));
        village.setRadius(4);
        BlockPos origin = PlotPlanner.find(helper.getLevel(), village, HOUSE).orElseThrow(() -> new AssertionError("no plot found"));
        helper.assertFalse(PlotRules.overlapsAny(Footprint.of(origin, HOUSE), village.occupiedFootprints()), "plot overlaps the house");
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void rejectsTreesAndBuildings(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        for (int x = 0; x < GameTestSupport.AREA_SIZE; x++) {
            for (int z = 0; z < GameTestSupport.AREA_SIZE; z++) {
                if ((x + z) % 7 == 0) {
                    helper.setBlock(x, 1, z, Blocks.OAK_LOG);
                }
            }
        }
        helper.assertTrue(PlotPlanner.find(helper.getLevel(), village(helper), HOUSE).isEmpty(), "plot found through logs");
        helper.succeed();
    }
}
```

- [ ] **Step 7:** Run `./gradlew --no-daemon -Dorg.gradle.workers.max=4 build` and `scripts/gametest.sh` — both pass.

- [ ] **Step 8:** Commit: `feat: spiral plot planner with column-scan ground rules`

### Task 5: blueprints

**Files:**
- Create: `src/main/resources/META-INF/accesstransformer.cfg`
- Create: `src/main/java/dev/andreymudri/villagercity/blueprint/BlueprintPlacement.java`
- Create: `src/main/java/dev/andreymudri/villagercity/blueprint/Blueprint.java`
- Create: `src/main/java/dev/andreymudri/villagercity/blueprint/Blueprints.java`
- Create: `src/main/java/dev/andreymudri/villagercity/gametest/BlueprintTests.java`

**Depends:** T1

- [ ] **Step 1:** Create `src/main/resources/META-INF/accesstransformer.cfg` (verified: ModDevGradle picks it up automatically and `palettes.get(0).blocks()` then compiles):

```
public net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate palettes
```

- [ ] **Step 2:** Write the failing GameTest `src/main/java/dev/andreymudri/villagercity/gametest/BlueprintTests.java`:

```java
package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.blueprint.Blueprint;
import dev.andreymudri.villagercity.blueprint.BlueprintPlacement;
import dev.andreymudri.villagercity.blueprint.Blueprints;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class BlueprintTests {
    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void starterHouseLoads(GameTestHelper helper) {
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow(() -> new AssertionError("blueprint missing"));
        helper.assertTrue(blueprint.size().equals(new Vec3i(5, 5, 5)), "size " + blueprint.size());
        helper.assertTrue(blueprint.placements().size() == 125, "placements " + blueprint.placements().size());
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void materialsCountEachDoubleBlockOnce(GameTestHelper helper) {
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        Map<net.minecraft.world.item.Item, Integer> expected = Map.of(
                Items.COBBLESTONE, 25, Items.OAK_PLANKS, 57, Items.OAK_LOG, 12,
                Items.GLASS, 2, Items.RED_BED, 1, Items.OAK_DOOR, 1);
        helper.assertTrue(blueprint.requiredMaterials().equals(expected), "materials " + blueprint.requiredMaterials());
        helper.assertTrue(Blueprint.costOf(Blocks.RED_BED.defaultBlockState().setValue(BlockStateProperties.BED_PART, BedPart.HEAD)) == Items.AIR, "bed head costs");
        helper.assertTrue(Blueprint.costOf(Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)) == Items.AIR, "upper door costs");
        helper.assertTrue(Blueprint.costOf(Blocks.AIR.defaultBlockState()) == Items.AIR, "air costs");
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void placementsAreBottomUpSolidsFirst(GameTestHelper helper) {
        List<BlueprintPlacement> placements = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow().placements();
        for (int i = 1; i < placements.size(); i++) {
            BlueprintPlacement previous = placements.get(i - 1);
            BlueprintPlacement current = placements.get(i);
            helper.assertTrue(previous.offset().getY() <= current.offset().getY(), "layer order broken at " + i);
            if (previous.offset().getY() == current.offset().getY()) {
                helper.assertFalse(!previous.state().blocksMotion() && current.state().blocksMotion(), "non-solid before solid at " + i);
            }
        }
        helper.assertTrue(placements.get(0).state().is(Blocks.COBBLESTONE), "first placement " + placements.get(0));
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void missingBlueprintIsEmpty(GameTestHelper helper) {
        helper.assertTrue(Blueprints.load(helper.getLevel(), VillagerCity.id("blueprint/does_not_exist")).isEmpty(), "missing blueprint loaded");
        helper.succeed();
    }
}
```

- [ ] **Step 3:** Create `src/main/java/dev/andreymudri/villagercity/blueprint/BlueprintPlacement.java`:

```java
package dev.andreymudri.villagercity.blueprint;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** One block of a blueprint, relative to the plot origin. */
public record BlueprintPlacement(BlockPos offset, BlockState state) {
    public BlueprintPlacement {
        offset = offset.immutable();
    }
}
```

- [ ] **Step 4:** Create `src/main/java/dev/andreymudri/villagercity/blueprint/Blueprint.java`:

```java
package dev.andreymudri.villagercity.blueprint;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** A structure template in build order: bottom layer first, solid blocks before non-solid ones. */
public final class Blueprint {
    public static final Comparator<BlueprintPlacement> BUILD_ORDER = Comparator
            .comparingInt((BlueprintPlacement p) -> p.offset().getY())
            .thenComparingInt(p -> p.state().blocksMotion() ? 0 : 1)
            .thenComparingInt(p -> p.offset().getZ())
            .thenComparingInt(p -> p.offset().getX());

    private final ResourceLocation id;
    private final Vec3i size;
    private final List<BlueprintPlacement> placements;

    public Blueprint(ResourceLocation id, Vec3i size, Collection<BlueprintPlacement> placements) {
        this.id = id;
        this.size = size;
        this.placements = placements.stream().sorted(BUILD_ORDER).toList();
    }

    public ResourceLocation id() {
        return id;
    }

    public Vec3i size() {
        return size;
    }

    public List<BlueprintPlacement> placements() {
        return placements;
    }

    public Map<Item, Integer> requiredMaterials() {
        return materialsFor(placements);
    }

    public static Map<Item, Integer> materialsFor(Collection<BlueprintPlacement> placements) {
        Map<Item, Integer> materials = new LinkedHashMap<>();
        for (BlueprintPlacement placement : placements) {
            Item cost = costOf(placement.state());
            if (cost != Items.AIR) {
                materials.merge(cost, 1, Integer::sum);
            }
        }
        return materials;
    }

    /** The item consumed to place this state; AIR when free (air, the second half of a bed or door, or no item form). */
    public static Item costOf(BlockState state) {
        if (state.isAir()) {
            return Items.AIR;
        }
        if (state.hasProperty(BlockStateProperties.BED_PART) && state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) {
            return Items.AIR;
        }
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            return Items.AIR;
        }
        return state.getBlock().asItem();
    }
}
```

- [ ] **Step 5:** Create `src/main/java/dev/andreymudri/villagercity/blueprint/Blueprints.java`:

```java
package dev.andreymudri.villagercity.blueprint;

import dev.andreymudri.villagercity.VillagerCity;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** Loads blueprints from data/<ns>/structure/<path>.nbt. A broken or missing file is logged once and yields empty. */
public final class Blueprints {
    public static final ResourceLocation STARTER_HOUSE = VillagerCity.id("blueprint/starter_house");

    private static final Set<ResourceLocation> REPORTED = ConcurrentHashMap.newKeySet();

    private Blueprints() {
    }

    public static Optional<Blueprint> load(ServerLevel level, ResourceLocation id) {
        Optional<StructureTemplate> template;
        try {
            template = level.getStructureManager().get(id);
        } catch (RuntimeException e) {
            report(id, "failed to read: " + e);
            return Optional.empty();
        }
        if (template.isEmpty() || template.get().palettes.isEmpty()) {
            report(id, "missing or empty");
            return Optional.empty();
        }
        List<BlueprintPlacement> placements = template.get().palettes.get(0).blocks().stream()
                .filter(info -> !info.state().is(Blocks.STRUCTURE_VOID))
                .map(info -> new BlueprintPlacement(info.pos(), info.state()))
                .toList();
        return Optional.of(new Blueprint(id, template.get().getSize(), placements));
    }

    private static void report(ResourceLocation id, String problem) {
        if (REPORTED.add(id)) {
            VillagerCity.LOGGER.error("Blueprint {} {}; builders will not use it", id, problem);
        }
    }
}
```

- [ ] **Step 6:** Run `./gradlew --no-daemon -Dorg.gradle.workers.max=4 build` and `scripts/gametest.sh` — both pass.

- [ ] **Step 7:** Commit: `feat: blueprint loading with build order and material costs`

### Task 6: village registry and detector

**Files:**
- Create: `src/main/java/dev/andreymudri/villagercity/village/VillageRegistry.java`
- Create: `src/main/java/dev/andreymudri/villagercity/village/VillageDetector.java`
- Create: `src/main/java/dev/andreymudri/villagercity/gametest/VillageTestSupport.java`
- Create: `src/main/java/dev/andreymudri/villagercity/gametest/VillageRegistryTests.java`

**Depends:** T3

- [ ] **Step 1:** Create `src/main/java/dev/andreymudri/villagercity/gametest/VillageTestSupport.java`:

```java
package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.VillageRegistry;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

public final class VillageTestSupport {
    private VillageTestSupport() {
    }

    /** Removes every registered village whose center is within merge distance of the given relative position. */
    public static void removeVillagesNear(GameTestHelper helper, BlockPos relative) {
        VillageRegistry registry = VillageRegistry.get(helper.getLevel());
        BlockPos pos = helper.absolutePos(relative);
        List<UUID> stale = registry.all().stream()
                .filter(v -> VillageRegistry.horizontalDistanceSqr(v.center(), pos) <= (long) VillageRegistry.MERGE_DISTANCE * VillageRegistry.MERGE_DISTANCE)
                .map(VillageData::id)
                .toList();
        stale.forEach(registry::remove);
    }

    /** Places a bell and registers a village there with the given radius, clearing any leftover village nearby first. */
    public static VillageData freshVillage(GameTestHelper helper, BlockPos relativeBell, int radius, boolean managed) {
        removeVillagesNear(helper, relativeBell);
        helper.setBlock(relativeBell, Blocks.BELL);
        VillageRegistry registry = VillageRegistry.get(helper.getLevel());
        VillageData village = registry.register(helper.absolutePos(relativeBell));
        village.setRadius(radius);
        village.setManaged(managed);
        registry.setDirty();
        return village;
    }

    public static void remove(GameTestHelper helper, VillageData village) {
        VillageRegistry.get(helper.getLevel()).remove(village.id());
    }
}
```

- [ ] **Step 2:** Write the failing GameTest `src/main/java/dev/andreymudri/villagercity/gametest/VillageRegistryTests.java`:

```java
package dev.andreymudri.villagercity.gametest;

import com.mojang.serialization.JsonOps;
import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.village.BuildingRecord;
import dev.andreymudri.villagercity.village.ContributionCategory;
import dev.andreymudri.villagercity.village.Plot;
import dev.andreymudri.villagercity.village.VillageCodecs;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.VillageDetector;
import dev.andreymudri.villagercity.village.VillageRegistry;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class VillageRegistryTests {
    private static final BlockPos BELL = new BlockPos(24, 1, 24);

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_registry_roundtrip")
    public static void registryRoundTripsThroughNbt(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 12, false);
        village.setStorehousePos(helper.absolutePos(new BlockPos(20, 1, 24)));
        village.addHouse(new BuildingRecord("villagercity:blueprint/starter_house", helper.absolutePos(new BlockPos(5, 1, 5)), new Vec3i(5, 5, 5)));
        village.addPlot(new Plot(UUID.randomUUID(), "villagercity:blueprint/starter_house", helper.absolutePos(new BlockPos(30, 1, 30)), new Vec3i(5, 5, 5), UUID.randomUUID()));
        village.ledger().record(UUID.randomUUID(), ContributionCategory.DEPOSIT, 42);

        VillageRegistry registry = VillageRegistry.get(helper.getLevel());
        CompoundTag saved = registry.save(new CompoundTag(), helper.getLevel().registryAccess());
        VillageRegistry loaded = VillageRegistry.load(saved, helper.getLevel().registryAccess());
        VillageData copy = loaded.get(village.id());
        helper.assertTrue(copy != null, "village lost on reload");
        String before = VillageCodecs.VILLAGE.encodeStart(JsonOps.INSTANCE, village).getOrThrow().toString();
        String after = VillageCodecs.VILLAGE.encodeStart(JsonOps.INSTANCE, copy).getOrThrow().toString();
        helper.assertTrue(before.equals(after), "round trip changed data:\n" + before + "\n" + after);
        VillageTestSupport.remove(helper, village);
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_detect_register")
    public static void detectorRegistersBellWithVillager(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageTestSupport.removeVillagesNear(helper, BELL);
        helper.setBlock(BELL, Blocks.BELL);
        GameTestSupport.spawnVillager(helper, 20, 1, 20);
        helper.succeedWhen(() -> {
            List<VillageData> registered = VillageDetector.scan(helper.getLevel(), helper.absolutePos(BELL), 16);
            VillageData village = VillageRegistry.get(helper.getLevel()).nearest(helper.absolutePos(BELL), 1);
            helper.assertTrue(village != null, "village not registered (scan returned " + registered.size() + ")");
            helper.assertTrue(village.radius() == VillageData.DEFAULT_RADIUS, "radius " + village.radius());
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_detect_lonely_bell")
    public static void detectorIgnoresBellWithoutVillagers(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageTestSupport.removeVillagesNear(helper, BELL);
        helper.setBlock(BELL, Blocks.BELL);
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(VillageDetector.scan(helper.getLevel(), helper.absolutePos(BELL), 16).isEmpty(), "registered a bell with no villagers");
            helper.assertTrue(VillageRegistry.get(helper.getLevel()).nearest(helper.absolutePos(BELL), 1) == null, "village exists");
            helper.succeed();
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_detect_merge")
    public static void detectorMergesNearbyBells(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageTestSupport.removeVillagesNear(helper, BELL);
        helper.setBlock(new BlockPos(10, 1, 10), Blocks.BELL);
        helper.setBlock(new BlockPos(38, 1, 38), Blocks.BELL);
        GameTestSupport.spawnVillager(helper, 24, 1, 24);
        helper.runAfterDelay(5, () -> {
            List<VillageData> registered = VillageDetector.scan(helper.getLevel(), helper.absolutePos(BELL), 32);
            helper.assertTrue(registered.size() == 1, "expected one village, got " + registered.size());
            VillageTestSupport.remove(helper, registered.get(0));
            helper.succeed();
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_detect_homes")
    public static void detectorAdoptsExistingHomes(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageTestSupport.removeVillagesNear(helper, BELL);
        helper.setBlock(BELL, Blocks.BELL);
        helper.setBlock(new BlockPos(10, 1, 11), Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.FOOT));
        helper.setBlock(new BlockPos(10, 1, 10), Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.HEAD));
        GameTestSupport.spawnVillager(helper, 20, 1, 20);
        helper.runAfterDelay(5, () -> {
            List<VillageData> registered = VillageDetector.scan(helper.getLevel(), helper.absolutePos(BELL), 16);
            helper.assertTrue(registered.size() == 1, "expected one village, got " + registered.size());
            helper.assertTrue(registered.get(0).houseCount() == 1, "houses " + registered.get(0).houseCount());
            VillageTestSupport.remove(helper, registered.get(0));
            helper.succeed();
        });
    }
}
```

- [ ] **Step 3:** Create `src/main/java/dev/andreymudri/villagercity/village/VillageRegistry.java`:

```java
package dev.andreymudri.villagercity.village;

import com.mojang.serialization.Codec;
import dev.andreymudri.villagercity.VillagerCity;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** All villages of one level, saved as data/villagercity_villages.dat. */
public final class VillageRegistry extends SavedData {
    public static final String NAME = "villagercity_villages";
    public static final int MERGE_DISTANCE = 64;
    private static final Codec<List<VillageData>> LIST_CODEC = VillageCodecs.VILLAGE.listOf();
    private static final SavedData.Factory<VillageRegistry> FACTORY = new SavedData.Factory<>(VillageRegistry::new, VillageRegistry::load, null);

    private final Map<UUID, VillageData> villages = new LinkedHashMap<>();

    public static VillageRegistry get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public static VillageRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        VillageRegistry registry = new VillageRegistry();
        if (tag.contains("villages")) {
            LIST_CODEC.parse(NbtOps.INSTANCE, tag.get("villages"))
                    .resultOrPartial(error -> VillagerCity.LOGGER.error("Failed to load villages: {}", error))
                    .ifPresent(list -> list.forEach(village -> registry.villages.put(village.id(), village)));
        }
        return registry;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        LIST_CODEC.encodeStart(NbtOps.INSTANCE, List.copyOf(villages.values()))
                .resultOrPartial(error -> VillagerCity.LOGGER.error("Failed to save villages: {}", error))
                .ifPresent(encoded -> tag.put("villages", encoded));
        return tag;
    }

    public @Nullable VillageData get(UUID id) {
        return villages.get(id);
    }

    public Collection<VillageData> all() {
        return Collections.unmodifiableCollection(villages.values());
    }

    /** Returns the village whose center is within merge distance of the bell, or registers a new one there. */
    public VillageData register(BlockPos bell) {
        VillageData existing = nearest(bell, MERGE_DISTANCE);
        if (existing != null) {
            return existing;
        }
        VillageData village = new VillageData(UUID.randomUUID(), bell, VillageData.DEFAULT_RADIUS);
        villages.put(village.id(), village);
        setDirty();
        return village;
    }

    public void remove(UUID id) {
        if (villages.remove(id) != null) {
            setDirty();
        }
    }

    public @Nullable VillageData nearest(BlockPos pos, int maxDistance) {
        long max = (long) maxDistance * maxDistance;
        return villages.values().stream()
                .filter(village -> horizontalDistanceSqr(village.center(), pos) <= max)
                .min(Comparator.comparingLong(village -> horizontalDistanceSqr(village.center(), pos)))
                .orElse(null);
    }

    public @Nullable VillageData villageAt(BlockPos pos) {
        return villages.values().stream().filter(village -> village.contains(pos)).findFirst().orElse(null);
    }

    public static long horizontalDistanceSqr(BlockPos a, BlockPos b) {
        long dx = a.getX() - b.getX();
        long dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }
}
```

- [ ] **Step 4:** Create `src/main/java/dev/andreymudri/villagercity/village/VillageDetector.java`:

```java
package dev.andreymudri.villagercity.village;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;

/** Finds vanilla villages: a bell (meeting POI) with at least one villager within 64 blocks. */
public final class VillageDetector {
    public static final int PLAYER_SCAN_RADIUS = 128;
    public static final int VILLAGER_RADIUS = 64;

    private VillageDetector() {
    }

    public static void scanAroundPlayers(ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            scan(level, player.blockPosition(), PLAYER_SCAN_RADIUS);
        }
    }

    /** Registers every unregistered qualifying bell within the radius; returns the villages created by this call. */
    public static List<VillageData> scan(ServerLevel level, BlockPos around, int radius) {
        VillageRegistry registry = VillageRegistry.get(level);
        List<BlockPos> bells = level.getPoiManager()
                .findAll(type -> type.is(PoiTypes.MEETING), pos -> true, around, radius, PoiManager.Occupancy.ANY)
                .sorted(Comparator.comparingDouble(pos -> pos.distSqr(around)))
                .toList();
        List<VillageData> created = new ArrayList<>();
        for (BlockPos bell : bells) {
            if (!level.isLoaded(bell) || registry.nearest(bell, VillageRegistry.MERGE_DISTANCE) != null) {
                continue;
            }
            if (level.getEntitiesOfClass(Villager.class, new AABB(bell).inflate(VILLAGER_RADIUS)).isEmpty()) {
                continue;
            }
            VillageData village = registry.register(bell);
            adoptHomes(level, village);
            created.add(village);
        }
        return created;
    }

    /** Every bed (home POI) inside the village radius becomes a vanilla house record. */
    public static void adoptHomes(ServerLevel level, VillageData village) {
        level.getPoiManager()
                .findAll(type -> type.is(PoiTypes.HOME), village::contains, village.center(), village.radius(), PoiManager.Occupancy.ANY)
                .forEach(home -> village.addHouse(new BuildingRecord(BuildingRecord.VANILLA_HOME, home, new Vec3i(1, 1, 1))));
        VillageRegistry.get(level).setDirty();
    }
}
```

- [ ] **Step 5:** Run `./gradlew --no-daemon -Dorg.gradle.workers.max=4 build` and `scripts/gametest.sh` — both pass.

- [ ] **Step 6:** Commit: `feat: village registry and bell detector`

### Task 7: storehouse block

**Files:**
- Create: `src/main/java/dev/andreymudri/villagercity/storehouse/StorehouseContent.java`
- Create: `src/main/java/dev/andreymudri/villagercity/storehouse/StorehouseBlock.java`
- Create: `src/main/java/dev/andreymudri/villagercity/storehouse/StorehouseBlockEntity.java`
- Create: `src/main/resources/assets/villagercity/blockstates/storehouse.json`
- Create: `src/main/resources/assets/villagercity/models/block/storehouse.json`
- Create: `src/main/resources/assets/villagercity/models/item/storehouse.json`
- Create: `src/main/resources/assets/villagercity/lang/en_us.json`
- Create: `src/main/resources/data/villagercity/loot_table/blocks/storehouse.json`
- Create: `src/main/resources/data/minecraft/tags/block/mineable/axe.json`
- Create: `src/main/java/dev/andreymudri/villagercity/gametest/StorehouseTests.java`

**Depends:** T6

- [ ] **Step 1:** Write the failing GameTest `src/main/java/dev/andreymudri/villagercity/gametest/StorehouseTests.java`:

```java
package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import dev.andreymudri.villagercity.storehouse.StorehouseContent;
import dev.andreymudri.villagercity.village.ContributionCategory;
import dev.andreymudri.villagercity.village.VillageData;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class StorehouseTests {
    private static final BlockPos STORE = new BlockPos(20, 1, 24);

    private static StorehouseBlockEntity place(GameTestHelper helper) {
        helper.setBlock(STORE, StorehouseContent.BLOCK.get());
        return helper.getBlockEntity(STORE);
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void citizenInsertAndExtract(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        StorehouseBlockEntity storehouse = place(helper);
        helper.assertTrue(storehouse.insertFromCitizen(new ItemStack(Items.OAK_LOG, 64)).isEmpty(), "first stack rejected");
        helper.assertTrue(storehouse.insertFromCitizen(new ItemStack(Items.OAK_LOG, 10)).isEmpty(), "second stack rejected");
        helper.assertTrue(storehouse.count(Items.OAK_LOG) == 74, "count " + storehouse.count(Items.OAK_LOG));
        helper.assertTrue(storehouse.hasAll(Map.of(Items.OAK_LOG, 74)), "hasAll false");
        helper.assertFalse(storehouse.hasAll(Map.of(Items.OAK_LOG, 75)), "hasAll true for too many");
        ItemStack taken = storehouse.extractForCitizen(Items.OAK_LOG, 70);
        helper.assertTrue(taken.getCount() == 64, "extract is capped at one stack, got " + taken.getCount());
        helper.assertTrue(storehouse.count(Items.OAK_LOG) == 10, "left " + storehouse.count(Items.OAK_LOG));
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_storehouse_ledger")
    public static void playerDepositsAreCreditedCitizenOnesAreNot(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, new BlockPos(24, 1, 24), 12, false);
        StorehouseBlockEntity storehouse = place(helper);
        village.setStorehousePos(helper.absolutePos(STORE));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);

        storehouse.startOpen(player);
        storehouse.setItem(0, new ItemStack(Items.OAK_LOG, 10));
        storehouse.insertFromCitizen(new ItemStack(Items.COBBLESTONE, 5));
        storehouse.stopOpen(player);

        long credited = village.ledger().total(player.getUUID(), ContributionCategory.DEPOSIT);
        helper.assertTrue(credited == 10, "credited " + credited);
        VillageTestSupport.remove(helper, village);
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void dropsContentsWhenBroken(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        StorehouseBlockEntity storehouse = place(helper);
        storehouse.insertFromCitizen(new ItemStack(Items.OAK_LOG, 7));
        helper.getLevel().destroyBlock(helper.absolutePos(STORE), false);
        helper.succeedWhen(() -> helper.assertEntityPresent(EntityType.ITEM, STORE, 2.0));
    }
}
```

- [ ] **Step 2:** Create `src/main/java/dev/andreymudri/villagercity/storehouse/StorehouseContent.java`. Registration goes through `RegisterEvent` on a self-routing `@EventBusSubscriber` so each feature registers its own entries without a shared registry file:

```java
package dev.andreymudri.villagercity.storehouse;

import dev.andreymudri.villagercity.VillagerCity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.RegisterEvent;

@EventBusSubscriber(modid = VillagerCity.MODID)
public final class StorehouseContent {
    public static final DeferredHolder<Block, StorehouseBlock> BLOCK =
            DeferredHolder.create(Registries.BLOCK, VillagerCity.id("storehouse"));
    public static final DeferredHolder<Item, BlockItem> ITEM =
            DeferredHolder.create(Registries.ITEM, VillagerCity.id("storehouse"));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<StorehouseBlockEntity>> BLOCK_ENTITY =
            DeferredHolder.create(Registries.BLOCK_ENTITY_TYPE, VillagerCity.id("storehouse"));

    private StorehouseContent() {
    }

    @SubscribeEvent
    public static void onRegister(RegisterEvent event) {
        event.register(Registries.BLOCK, VillagerCity.id("storehouse"),
                () -> new StorehouseBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.BARREL)));
        event.register(Registries.ITEM, VillagerCity.id("storehouse"),
                () -> new BlockItem(BLOCK.get(), new Item.Properties()));
        event.register(Registries.BLOCK_ENTITY_TYPE, VillagerCity.id("storehouse"),
                () -> BlockEntityType.Builder.of(StorehouseBlockEntity::new, BLOCK.get()).build(null));
    }
}
```

- [ ] **Step 3:** Create `src/main/java/dev/andreymudri/villagercity/storehouse/StorehouseBlock.java`:

```java
package dev.andreymudri.villagercity.storehouse;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public class StorehouseBlock extends BaseEntityBlock {
    public static final MapCodec<StorehouseBlock> CODEC = simpleCodec(StorehouseBlock::new);

    public StorehouseBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new StorehouseBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (level.getBlockEntity(pos) instanceof StorehouseBlockEntity storehouse) {
            player.openMenu(storehouse);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        Containers.dropContentsOnDestroy(state, newState, level, pos);
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
```

- [ ] **Step 4:** Create `src/main/java/dev/andreymudri/villagercity/storehouse/StorehouseBlockEntity.java`. Player deposits are measured as the per-item increase between opening and closing the menu; citizen inserts and extracts while a player has it open are applied to that player's snapshot so they are not credited:

```java
package dev.andreymudri.villagercity.storehouse;

import dev.andreymudri.villagercity.village.ContributionCategory;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.VillageRegistry;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class StorehouseBlockEntity extends BaseContainerBlockEntity {
    public static final int SIZE = 27;

    private NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
    private final Map<UUID, Map<Item, Integer>> openSnapshots = new HashMap<>();

    public StorehouseBlockEntity(BlockPos pos, BlockState state) {
        super(StorehouseContent.BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.villagercity.storehouse");
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> items) {
        this.items = items;
    }

    @Override
    public int getContainerSize() {
        return SIZE;
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return ChestMenu.threeRows(containerId, inventory, this);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, items, registries);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, items, registries);
    }

    @Override
    public void startOpen(Player player) {
        if (level != null && !level.isClientSide && !player.isSpectator()) {
            openSnapshots.put(player.getUUID(), counts());
        }
    }

    @Override
    public void stopOpen(Player player) {
        Map<Item, Integer> before = openSnapshots.remove(player.getUUID());
        if (before == null || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        Map<Item, Integer> after = counts();
        Set<Item> seen = new HashSet<>(before.keySet());
        seen.addAll(after.keySet());
        long deposited = 0;
        for (Item item : seen) {
            deposited += Math.max(0, after.getOrDefault(item, 0) - before.getOrDefault(item, 0));
        }
        if (deposited <= 0) {
            return;
        }
        VillageRegistry registry = VillageRegistry.get(serverLevel);
        for (VillageData village : registry.all()) {
            if (worldPosition.equals(village.storehousePos())) {
                village.ledger().record(player.getUUID(), ContributionCategory.DEPOSIT, deposited);
                registry.setDirty();
                return;
            }
        }
    }

    public Map<Item, Integer> counts() {
        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    public int count(Item item) {
        return counts().getOrDefault(item, 0);
    }

    public boolean hasAll(Map<Item, Integer> wanted) {
        Map<Item, Integer> counts = counts();
        return wanted.entrySet().stream().allMatch(e -> counts.getOrDefault(e.getKey(), 0) >= e.getValue());
    }

    /** Inserts as much as fits; returns what did not fit. Not credited to any player. */
    public ItemStack insertFromCitizen(ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack rest = stack.copy();
        int before = rest.getCount();
        for (int i = 0; i < SIZE && !rest.isEmpty(); i++) {
            ItemStack slot = items.get(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, rest)) {
                int move = Math.min(rest.getCount(), slot.getMaxStackSize() - slot.getCount());
                if (move > 0) {
                    slot.grow(move);
                    rest.shrink(move);
                }
            }
        }
        for (int i = 0; i < SIZE && !rest.isEmpty(); i++) {
            if (items.get(i).isEmpty()) {
                int move = Math.min(rest.getCount(), rest.getMaxStackSize());
                items.set(i, rest.split(move));
            }
        }
        int inserted = before - rest.getCount();
        if (inserted > 0) {
            adjustSnapshots(stack.getItem(), inserted);
            setChanged();
        }
        return rest.isEmpty() ? ItemStack.EMPTY : rest;
    }

    /** Removes up to one stack of the item (capped at its max stack size). Not credited to any player. */
    public ItemStack extractForCitizen(Item item, int amount) {
        int wanted = Math.min(amount, new ItemStack(item).getMaxStackSize());
        ItemStack taken = new ItemStack(item, 0);
        for (int i = SIZE - 1; i >= 0 && taken.getCount() < wanted; i--) {
            ItemStack slot = items.get(i);
            if (slot.is(item)) {
                ItemStack part = slot.split(wanted - taken.getCount());
                if (taken.isEmpty()) {
                    taken = part;
                } else {
                    taken.grow(part.getCount());
                }
            }
        }
        if (!taken.isEmpty()) {
            adjustSnapshots(item, -taken.getCount());
            setChanged();
        }
        return taken.isEmpty() ? ItemStack.EMPTY : taken;
    }

    private void adjustSnapshots(Item item, int delta) {
        for (Map<Item, Integer> snapshot : openSnapshots.values()) {
            snapshot.merge(item, delta, Integer::sum);
        }
    }
}
```

- [ ] **Step 5:** Create `src/main/resources/assets/villagercity/blockstates/storehouse.json`:

```json
{
  "variants": {
    "": { "model": "villagercity:block/storehouse" }
  }
}
```

- [ ] **Step 6:** Create `src/main/resources/assets/villagercity/models/block/storehouse.json`:

```json
{
  "parent": "minecraft:block/cube_column",
  "textures": {
    "end": "minecraft:block/barrel_top",
    "side": "minecraft:block/barrel_side"
  }
}
```

- [ ] **Step 7:** Create `src/main/resources/assets/villagercity/models/item/storehouse.json`:

```json
{
  "parent": "villagercity:block/storehouse"
}
```

- [ ] **Step 8:** Create `src/main/resources/assets/villagercity/lang/en_us.json`:

```json
{
  "block.villagercity.storehouse": "Storehouse",
  "container.villagercity.storehouse": "Storehouse"
}
```

- [ ] **Step 9:** Create `src/main/resources/data/villagercity/loot_table/blocks/storehouse.json`:

```json
{
  "type": "minecraft:block",
  "pools": [
    {
      "rolls": 1,
      "entries": [{ "type": "minecraft:item", "name": "villagercity:storehouse" }],
      "conditions": [{ "condition": "minecraft:survives_explosion" }]
    }
  ],
  "random_sequence": "villagercity:blocks/storehouse"
}
```

- [ ] **Step 10:** Create `src/main/resources/data/minecraft/tags/block/mineable/axe.json`:

```json
{
  "replace": false,
  "values": ["villagercity:storehouse"]
}
```

- [ ] **Step 11:** Run `./gradlew --no-daemon -Dorg.gradle.workers.max=4 build` and `scripts/gametest.sh` — both pass.

- [ ] **Step 12:** Commit: `feat: storehouse block with contribution tracking`

### Task 8: citizen data and task scheduler

**Files:**
- Create: `src/main/java/dev/andreymudri/villagercity/citizen/JobType.java`
- Create: `src/main/java/dev/andreymudri/villagercity/citizen/CitizenData.java`
- Create: `src/main/java/dev/andreymudri/villagercity/citizen/CitizenRuntime.java`
- Create: `src/main/java/dev/andreymudri/villagercity/citizen/CitizenAttachments.java`
- Create: `src/main/java/dev/andreymudri/villagercity/citizen/Task.java`
- Create: `src/main/java/dev/andreymudri/villagercity/citizen/TaskContext.java`
- Create: `src/main/java/dev/andreymudri/villagercity/citizen/TaskSequence.java`
- Create: `src/main/java/dev/andreymudri/villagercity/citizen/Job.java`
- Create: `src/main/java/dev/andreymudri/villagercity/citizen/Jobs.java`
- Create: `src/main/java/dev/andreymudri/villagercity/citizen/TaskScheduler.java`
- Create: `src/main/java/dev/andreymudri/villagercity/gametest/ScriptedJob.java`
- Create: `src/main/java/dev/andreymudri/villagercity/gametest/CitizenTestSupport.java`
- Create: `src/main/java/dev/andreymudri/villagercity/gametest/TaskSchedulerTests.java`

**Depends:** T6

- [ ] **Step 1:** Create `src/main/java/dev/andreymudri/villagercity/citizen/Task.java`:

```java
package dev.andreymudri.villagercity.citizen;

/** One unit of citizen work, ticked by the scheduler until it stops returning RUNNING. */
public interface Task {
    enum Status { RUNNING, SUCCESS, FAILED }

    default void start(TaskContext ctx) {
    }

    Status tick(TaskContext ctx);

    /** Called once when the task ends for any reason other than being paused. */
    default void stop(TaskContext ctx) {
    }
}
```

- [ ] **Step 2:** Create `src/main/java/dev/andreymudri/villagercity/citizen/TaskContext.java`:

```java
package dev.andreymudri.villagercity.citizen;

import dev.andreymudri.villagercity.village.VillageData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;

public record TaskContext(ServerLevel level, Villager villager, VillageData village, CitizenData citizen) {
    public long gameTime() {
        return level.getGameTime();
    }
}
```

- [ ] **Step 3:** Create `src/main/java/dev/andreymudri/villagercity/citizen/TaskSequence.java`:

```java
package dev.andreymudri.villagercity.citizen;

import java.util.List;

/** Runs tasks in order; fails as soon as one fails. */
public final class TaskSequence implements Task {
    private final List<Task> tasks;
    private int index;

    public TaskSequence(List<Task> tasks) {
        this.tasks = List.copyOf(tasks);
    }

    public static TaskSequence of(Task... tasks) {
        return new TaskSequence(List.of(tasks));
    }

    @Override
    public void start(TaskContext ctx) {
        index = 0;
        if (!tasks.isEmpty()) {
            tasks.get(0).start(ctx);
        }
    }

    @Override
    public Status tick(TaskContext ctx) {
        if (index >= tasks.size()) {
            return Status.SUCCESS;
        }
        Task current = tasks.get(index);
        Status status = current.tick(ctx);
        if (status == Status.RUNNING) {
            return Status.RUNNING;
        }
        current.stop(ctx);
        if (status == Status.FAILED) {
            index = tasks.size();
            return Status.FAILED;
        }
        index++;
        if (index >= tasks.size()) {
            return Status.SUCCESS;
        }
        tasks.get(index).start(ctx);
        return Status.RUNNING;
    }

    @Override
    public void stop(TaskContext ctx) {
        if (index < tasks.size()) {
            tasks.get(index).stop(ctx);
        }
    }

    public Task currentStep() {
        return tasks.get(Math.min(index, tasks.size() - 1));
    }
}
```

- [ ] **Step 4:** Create `src/main/java/dev/andreymudri/villagercity/citizen/Job.java`:

```java
package dev.andreymudri.villagercity.citizen;

import javax.annotation.Nullable;

/** Decides a citizen's next task. Jobs keep no state that must survive a save; they re-plan from world and village state. */
public interface Job {
    /** The next task to run, or null when there is nothing to do right now (the scheduler retries later). */
    @Nullable
    Task plan(TaskContext ctx);

    default void onTaskFinished(TaskContext ctx, Task task, Task.Status status) {
    }
}
```

- [ ] **Step 5:** Create `src/main/java/dev/andreymudri/villagercity/citizen/JobType.java`:

```java
package dev.andreymudri.villagercity.citizen;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

public enum JobType implements StringRepresentable {
    NONE("none"),
    LUMBERJACK("lumberjack"),
    BUILDER("builder");

    public static final Codec<JobType> CODEC = StringRepresentable.fromEnum(JobType::values);

    private final String serializedName;

    JobType(String serializedName) {
        this.serializedName = serializedName;
    }

    @Override
    public String getSerializedName() {
        return serializedName;
    }
}
```

- [ ] **Step 6:** Create `src/main/java/dev/andreymudri/villagercity/citizen/Jobs.java`:

```java
package dev.andreymudri.villagercity.citizen;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/** JobType to Job factory. Filled once during mod construction. */
public final class Jobs {
    private static final Map<JobType, Supplier<Job>> FACTORIES = new EnumMap<>(JobType.class);

    private Jobs() {
    }

    public static synchronized void register(JobType type, Supplier<Job> factory) {
        FACTORIES.put(type, factory);
    }

    public static synchronized @Nullable Job create(JobType type) {
        Supplier<Job> factory = FACTORIES.get(type);
        return factory == null ? null : factory.get();
    }
}
```

- [ ] **Step 7:** Create `src/main/java/dev/andreymudri/villagercity/citizen/CitizenData.java`:

```java
package dev.andreymudri.villagercity.citizen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.item.ItemStack;

/**
 * Saved per-villager state. The tool is the canonical stack: the scheduler keeps it in the main hand,
 * because vanilla trading overwrites the main hand to show trade items.
 */
public final class CitizenData {
    public static final Codec<CitizenData> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.optionalFieldOf("village").forGetter(d -> Optional.ofNullable(d.villageId)),
            JobType.CODEC.optionalFieldOf("job", JobType.NONE).forGetter(CitizenData::job),
            ItemStack.OPTIONAL_CODEC.optionalFieldOf("tool", ItemStack.EMPTY).forGetter(CitizenData::tool)
    ).apply(i, (village, job, tool) -> new CitizenData(village.orElse(null), job, tool)));

    private @Nullable UUID villageId;
    private JobType job;
    private ItemStack tool;

    public CitizenData() {
        this(null, JobType.NONE, ItemStack.EMPTY);
    }

    public CitizenData(@Nullable UUID villageId, JobType job, ItemStack tool) {
        this.villageId = villageId;
        this.job = job;
        this.tool = tool;
    }

    public @Nullable UUID villageId() {
        return villageId;
    }

    public JobType job() {
        return job;
    }

    public ItemStack tool() {
        return tool;
    }

    public void clear() {
        villageId = null;
        job = JobType.NONE;
        tool = ItemStack.EMPTY;
    }
}
```

- [ ] **Step 8:** Create `src/main/java/dev/andreymudri/villagercity/citizen/CitizenRuntime.java`:

```java
package dev.andreymudri.villagercity.citizen;

import javax.annotation.Nullable;

/** Transient per-villager scheduler state; never saved. After a load, jobs rebuild tasks by re-planning. */
public final class CitizenRuntime {
    @Nullable Job job;
    @Nullable JobType jobType;
    @Nullable Task current;
    long idleUntil;
    @Nullable Job forcedJob;

    /** Runs this job instead of the one registered for the citizen's JobType (GameTests only). */
    public void forceJob(@Nullable Job job) {
        this.forcedJob = job;
        this.current = null;
        this.idleUntil = 0;
    }

    public @Nullable Task currentTask() {
        return current;
    }

    public @Nullable Job activeJob() {
        return forcedJob != null ? forcedJob : job;
    }
}
```

- [ ] **Step 9:** Create `src/main/java/dev/andreymudri/villagercity/citizen/CitizenAttachments.java`:

```java
package dev.andreymudri.villagercity.citizen;

import dev.andreymudri.villagercity.VillagerCity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.schedule.Activity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.registries.RegisterEvent;

@EventBusSubscriber(modid = VillagerCity.MODID)
public final class CitizenAttachments {
    /** An activity with no behaviours: while active, vanilla WORK/IDLE/MEET/PLAY behaviours cannot start. */
    public static final DeferredHolder<Activity, Activity> CITY_TASK =
            DeferredHolder.create(Registries.ACTIVITY, VillagerCity.id("city_task"));
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<CitizenData>> CITIZEN =
            DeferredHolder.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, VillagerCity.id("citizen"));
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<CitizenRuntime>> RUNTIME =
            DeferredHolder.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, VillagerCity.id("citizen_runtime"));

    private CitizenAttachments() {
    }

    @SubscribeEvent
    public static void onRegister(RegisterEvent event) {
        event.register(Registries.ACTIVITY, VillagerCity.id("city_task"), () -> new Activity("villagercity:city_task"));
        event.register(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, VillagerCity.id("citizen"),
                () -> AttachmentType.builder(() -> new CitizenData()).serialize(CitizenData.CODEC).build());
        event.register(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, VillagerCity.id("citizen_runtime"),
                () -> AttachmentType.builder(() -> new CitizenRuntime()).build());
    }
}
```

- [ ] **Step 10:** Create `src/main/java/dev/andreymudri/villagercity/citizen/TaskScheduler.java`. This is the verified mechanism: register the behaviour-less activity again every tick (`refreshBrain` drops it on profession change, growing up and load), force it unless yielding, erase `WALK_TARGET` (some CORE behaviours still set it), and back off by switching to the scheduled activity, because a forced villager never goes to REST on its own:

```java
package dev.andreymudri.villagercity.citizen;

import com.google.common.collect.ImmutableList;
import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.VillageRegistry;
import java.util.Optional;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

@EventBusSubscriber(modid = VillagerCity.MODID)
public final class TaskScheduler {
    public static final Set<Activity> YIELD_ACTIVITIES = Set.of(Activity.PANIC, Activity.REST, Activity.RAID, Activity.PRE_RAID, Activity.HIDE);
    public static final int IDLE_RETRY_TICKS = 40;

    private TaskScheduler() {
    }

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Villager villager) || !(villager.level() instanceof ServerLevel level)) {
            return;
        }
        Optional<CitizenData> citizen = villager.getExistingData(CitizenAttachments.CITIZEN.get());
        if (citizen.isPresent()) {
            tickCitizen(level, villager, citizen.get());
        }
    }

    public static void tickCitizen(ServerLevel level, Villager villager, CitizenData citizen) {
        CitizenRuntime runtime = villager.getData(CitizenAttachments.RUNTIME);
        Brain<Villager> brain = villager.getBrain();
        Activity cityTask = CitizenAttachments.CITY_TASK.get();
        if (citizen.job() == JobType.NONE || citizen.villageId() == null) {
            release(level, villager, runtime);
            return;
        }
        VillageData village = VillageRegistry.get(level).get(citizen.villageId());
        if (village == null) {
            citizen.clear();
            release(level, villager, runtime);
            return;
        }
        brain.addActivity(cityTask, ImmutableList.of());
        if (shouldYield(level, villager)) {
            if (brain.isActive(cityTask)) {
                brain.setActiveActivityIfPossible(scheduledActivity(level, villager));
            }
            return;
        }
        if (!brain.isActive(cityTask)) {
            brain.setActiveActivityIfPossible(cityTask);
        }
        brain.eraseMemory(MemoryModuleType.WALK_TARGET);
        equipTool(villager, citizen);

        TaskContext ctx = new TaskContext(level, villager, village, citizen);
        if (runtime.forcedJob == null && runtime.jobType != citizen.job()) {
            if (runtime.current != null) {
                runtime.current.stop(ctx);
                runtime.current = null;
            }
            runtime.job = Jobs.create(citizen.job());
            runtime.jobType = citizen.job();
        }
        Job job = runtime.activeJob();
        if (job == null) {
            return;
        }
        if (runtime.current == null) {
            if (level.getGameTime() < runtime.idleUntil) {
                return;
            }
            Task next = job.plan(ctx);
            if (next == null) {
                runtime.idleUntil = level.getGameTime() + IDLE_RETRY_TICKS;
                return;
            }
            runtime.current = next;
            next.start(ctx);
        }
        Task.Status status = runtime.current.tick(ctx);
        if (status != Task.Status.RUNNING) {
            Task finished = runtime.current;
            runtime.current = null;
            finished.stop(ctx);
            job.onTaskFinished(ctx, finished, status);
        }
    }

    public static boolean shouldYield(ServerLevel level, Villager villager) {
        Brain<Villager> brain = villager.getBrain();
        return villager.isTrading()
                || villager.isSleeping()
                || YIELD_ACTIVITIES.stream().anyMatch(brain::isActive)
                || scheduledActivity(level, villager) == Activity.REST;
    }

    public static Activity scheduledActivity(ServerLevel level, Villager villager) {
        return villager.getBrain().getSchedule().getActivityAt((int) (level.getDayTime() % 24000L));
    }

    private static void equipTool(Villager villager, CitizenData citizen) {
        if (!citizen.tool().isEmpty() && villager.getMainHandItem() != citizen.tool()) {
            villager.setItemSlot(EquipmentSlot.MAINHAND, citizen.tool());
        }
    }

    private static void release(ServerLevel level, Villager villager, CitizenRuntime runtime) {
        runtime.current = null;
        runtime.job = null;
        runtime.jobType = null;
        Activity cityTask = CitizenAttachments.CITY_TASK.get();
        if (villager.getBrain().isActive(cityTask)) {
            villager.getBrain().setActiveActivityIfPossible(scheduledActivity(level, villager));
        }
    }
}
```

- [ ] **Step 11:** Create `src/main/java/dev/andreymudri/villagercity/gametest/ScriptedJob.java`:

```java
package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.citizen.Job;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.annotation.Nullable;

/** A job that hands out a fixed list of tasks once each and records how they finished. */
public final class ScriptedJob implements Job {
    private final Deque<Task> tasks;
    public final List<Task.Status> results = new CopyOnWriteArrayList<>();

    public ScriptedJob(Task... tasks) {
        this.tasks = new ArrayDeque<>(Arrays.asList(tasks));
    }

    @Override
    public @Nullable Task plan(TaskContext ctx) {
        return tasks.poll();
    }

    @Override
    public void onTaskFinished(TaskContext ctx, Task task, Task.Status status) {
        results.add(status);
    }
}
```

- [ ] **Step 12:** Create `src/main/java/dev/andreymudri/villagercity/gametest/CitizenTestSupport.java`:

```java
package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.citizen.CitizenAttachments;
import dev.andreymudri.villagercity.citizen.CitizenData;
import dev.andreymudri.villagercity.citizen.Job;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.village.VillageData;
import javax.annotation.Nullable;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;

public final class CitizenTestSupport {
    private CitizenTestSupport() {
    }

    /** Makes the villager a citizen of the village; when a job is given it runs instead of the registered one. */
    public static CitizenData enroll(Villager villager, VillageData village, JobType type, ItemStack tool, @Nullable Job forced) {
        CitizenData data = new CitizenData(village.id(), type, tool);
        villager.setData(CitizenAttachments.CITIZEN, data);
        villager.getData(CitizenAttachments.RUNTIME).forceJob(forced);
        return data;
    }
}
```

- [ ] **Step 13:** Write `src/main/java/dev/andreymudri/villagercity/gametest/TaskSchedulerTests.java`:

```java
package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.citizen.CitizenAttachments;
import dev.andreymudri.villagercity.citizen.CitizenData;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import dev.andreymudri.villagercity.village.VillageData;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class TaskSchedulerTests {
    private static final BlockPos BELL = new BlockPos(2, 1, 2);

    /** Never finishes; counts how often it was ticked. */
    private static final class CountingTask implements Task {
        final AtomicInteger ticks = new AtomicInteger();

        @Override
        public Status tick(TaskContext ctx) {
            ticks.incrementAndGet();
            return Status.RUNNING;
        }
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_sched_force")
    public static void forcesCityActivityWhileTaskRuns(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        Villager villager = GameTestSupport.spawnVillager(helper, 24, 1, 24);
        CountingTask task = new CountingTask();
        CitizenTestSupport.enroll(villager, village, JobType.LUMBERJACK, ItemStack.EMPTY, new ScriptedJob(task));
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(villager.getBrain().isActive(CitizenAttachments.CITY_TASK.get()), "city_task not active");
            helper.assertTrue(task.ticks.get() >= 30, "task ticked " + task.ticks.get());
            helper.assertFalse(villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET), "vanilla walk target present");
            VillageTestSupport.remove(helper, village);
            helper.succeed();
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_sched_panic", timeoutTicks = 200)
    public static void pausesWhilePanicking(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        Villager villager = GameTestSupport.spawnVillager(helper, 24, 1, 24);
        CountingTask task = new CountingTask();
        CitizenTestSupport.enroll(villager, village, JobType.LUMBERJACK, ItemStack.EMPTY, new ScriptedJob(task));
        AtomicLong panicTick = new AtomicLong(-1);
        AtomicInteger countAtPanic = new AtomicInteger();
        helper.runAfterDelay(5, () -> villager.hurt(helper.getLevel().damageSources().generic(), 0.5f));
        helper.onEachTick(() -> {
            if (panicTick.get() < 0 && villager.getBrain().isActive(Activity.PANIC)) {
                panicTick.set(helper.getTick());
                countAtPanic.set(task.ticks.get());
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(panicTick.get() >= 0 && helper.getTick() >= panicTick.get() + 10, "waiting for panic");
            helper.assertTrue(villager.getBrain().isActive(Activity.PANIC), "panic ended too early");
            helper.assertTrue(task.ticks.get() == countAtPanic.get(), "task ticked during panic");
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_sched_night")
    public static void yieldsToRestAtNight(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        helper.getLevel().setDayTime(13000);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        Villager villager = GameTestSupport.spawnVillager(helper, 24, 1, 24);
        CountingTask task = new CountingTask();
        CitizenTestSupport.enroll(villager, village, JobType.LUMBERJACK, ItemStack.EMPTY, new ScriptedJob(task));
        helper.runAfterDelay(40, () -> {
            boolean ticked = task.ticks.get() > 0;
            boolean forced = villager.getBrain().isActive(CitizenAttachments.CITY_TASK.get());
            helper.getLevel().setDayTime(GameTestSupport.DAY_TIME);
            VillageTestSupport.remove(helper, village);
            helper.assertFalse(ticked, "task ran at night");
            helper.assertFalse(forced, "city_task active at night");
            helper.succeed();
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_sched_tool")
    public static void reEquipsToolAfterMainHandIsCleared(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        Villager villager = GameTestSupport.spawnVillager(helper, 24, 1, 24);
        CitizenData data = CitizenTestSupport.enroll(villager, village, JobType.LUMBERJACK, new ItemStack(Items.STONE_AXE), new ScriptedJob(new CountingTask()));
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(villager.getMainHandItem() == data.tool(), "tool not equipped");
            villager.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        });
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(villager.getMainHandItem() == data.tool(), "tool not re-equipped");
            VillageTestSupport.remove(helper, village);
            helper.succeed();
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_sched_release")
    public static void releasesCitizenWhenVillageIsRemoved(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        Villager villager = GameTestSupport.spawnVillager(helper, 24, 1, 24);
        CitizenData data = CitizenTestSupport.enroll(villager, village, JobType.LUMBERJACK, ItemStack.EMPTY, new ScriptedJob(new CountingTask()));
        helper.runAfterDelay(10, () -> VillageTestSupport.remove(helper, village));
        helper.runAfterDelay(15, () -> {
            helper.assertTrue(data.job() == JobType.NONE, "job kept after village removal");
            helper.assertFalse(villager.getBrain().isActive(CitizenAttachments.CITY_TASK.get()), "city_task still active");
            helper.succeed();
        });
    }
}
```

- [ ] **Step 14:** Run `./gradlew --no-daemon -Dorg.gradle.workers.max=4 build` and `scripts/gametest.sh` — both pass.

- [ ] **Step 15:** Commit: `feat: citizen attachment and task scheduler over a behaviour-less activity`

### Task 9: primitive tasks

**Files:**
- Create: `src/main/java/dev/andreymudri/villagercity/citizen/Inventories.java`
- Create: `src/main/java/dev/andreymudri/villagercity/citizen/task/MoveTo.java`
- Create: `src/main/java/dev/andreymudri/villagercity/citizen/task/BreakBlock.java`
- Create: `src/main/java/dev/andreymudri/villagercity/citizen/task/PlaceBlock.java`
- Create: `src/main/java/dev/andreymudri/villagercity/citizen/task/PickUpItems.java`
- Create: `src/main/java/dev/andreymudri/villagercity/citizen/task/Deposit.java`
- Create: `src/main/java/dev/andreymudri/villagercity/citizen/task/Withdraw.java`
- Create: `src/main/java/dev/andreymudri/villagercity/gametest/PrimitiveTaskTests.java`

**Depends:** T7, T8

- [ ] **Step 1:** Write the failing GameTest `src/main/java/dev/andreymudri/villagercity/gametest/PrimitiveTaskTests.java`:

```java
package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.citizen.Inventories;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.task.BreakBlock;
import dev.andreymudri.villagercity.citizen.task.Deposit;
import dev.andreymudri.villagercity.citizen.task.MoveTo;
import dev.andreymudri.villagercity.citizen.task.PickUpItems;
import dev.andreymudri.villagercity.citizen.task.PlaceBlock;
import dev.andreymudri.villagercity.citizen.task.Withdraw;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import dev.andreymudri.villagercity.storehouse.StorehouseContent;
import dev.andreymudri.villagercity.village.VillageData;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class PrimitiveTaskTests {
    private static final BlockPos BELL = new BlockPos(2, 1, 2);

    private static void assertResults(GameTestHelper helper, ScriptedJob job, Task.Status... expected) {
        helper.assertTrue(job.results.equals(List.of(expected)), "results " + job.results);
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_task_move", timeoutTicks = 400)
    public static void moveToReachesTarget(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        Villager villager = GameTestSupport.spawnVillager(helper, 10, 1, 10);
        BlockPos target = helper.absolutePos(new BlockPos(30, 1, 30));
        ScriptedJob job = new ScriptedJob(new MoveTo(target, 1.5));
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, job);
        helper.succeedWhen(() -> {
            assertResults(helper, job, Task.Status.SUCCESS);
            helper.assertTrue(villager.distanceToSqr(Vec3.atCenterOf(target)) <= 1.5 * 1.5, "not at target");
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_task_stuck", timeoutTicks = 700)
    public static void moveToFailsWhenTargetIsWalledIn(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        for (int x = 28; x <= 32; x++) {
            for (int z = 28; z <= 32; z++) {
                for (int y = 1; y <= 4; y++) {
                    boolean shell = x == 28 || x == 32 || z == 28 || z == 32 || y == 4;
                    if (shell) {
                        helper.setBlock(x, y, z, Blocks.STONE);
                    }
                }
            }
        }
        Villager villager = GameTestSupport.spawnVillager(helper, 10, 1, 10);
        ScriptedJob job = new ScriptedJob(new MoveTo(helper.absolutePos(new BlockPos(30, 1, 30)), 1.5));
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, job);
        helper.succeedWhen(() -> {
            assertResults(helper, job, Task.Status.FAILED);
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_task_break")
    public static void breakBlockUsesToolTimeAndDrops(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        BlockPos log = new BlockPos(12, 1, 10);
        helper.setBlock(log, Blocks.OAK_LOG);
        Villager villager = GameTestSupport.spawnVillager(helper, 10, 1, 10);
        ItemStack axe = new ItemStack(Items.STONE_AXE);
        ScriptedJob job = new ScriptedJob(new BreakBlock(helper.absolutePos(log)));
        long start = helper.getTick();
        CitizenTestSupport.enroll(villager, village, JobType.LUMBERJACK, axe, job);
        helper.succeedWhen(() -> {
            assertResults(helper, job, Task.Status.SUCCESS);
            helper.assertTrue(helper.getTick() - start >= BreakBlock.breakTicks(helper.getLevel(), helper.absolutePos(log), Blocks.OAK_LOG.defaultBlockState(), axe.copy()),
                    "broke too fast");
            helper.assertBlockPresent(Blocks.AIR, log);
            helper.assertEntityPresent(EntityType.ITEM, log, 2.0);
            helper.assertTrue(axe.getDamageValue() == 1, "axe damage " + axe.getDamageValue());
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_task_break_bedrock")
    public static void breakBlockFailsOnUnbreakable(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        helper.setBlock(new BlockPos(12, 1, 10), Blocks.BEDROCK);
        Villager villager = GameTestSupport.spawnVillager(helper, 10, 1, 10);
        ScriptedJob job = new ScriptedJob(new BreakBlock(helper.absolutePos(new BlockPos(12, 1, 10))));
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, job);
        helper.succeedWhen(() -> {
            assertResults(helper, job, Task.Status.FAILED);
            helper.assertBlockPresent(Blocks.BEDROCK, new BlockPos(12, 1, 10));
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_task_place")
    public static void placeBlockConsumesItemAndFailsWithout(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        Villager villager = GameTestSupport.spawnVillager(helper, 10, 1, 10);
        villager.getInventory().addItem(new ItemStack(Items.OAK_PLANKS, 1));
        BlockPos first = helper.absolutePos(new BlockPos(12, 1, 10));
        BlockPos second = helper.absolutePos(new BlockPos(12, 1, 12));
        ScriptedJob job = new ScriptedJob(
                new PlaceBlock(first, Blocks.OAK_PLANKS.defaultBlockState(), Items.OAK_PLANKS),
                new PlaceBlock(second, Blocks.OAK_PLANKS.defaultBlockState(), Items.OAK_PLANKS));
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, job);
        helper.succeedWhen(() -> {
            assertResults(helper, job, Task.Status.SUCCESS, Task.Status.FAILED);
            helper.assertBlockPresent(Blocks.OAK_PLANKS, new BlockPos(12, 1, 10));
            helper.assertBlockPresent(Blocks.AIR, new BlockPos(12, 1, 12));
            helper.assertTrue(Inventories.count(villager.getInventory(), s -> s.is(Items.OAK_PLANKS)) == 0, "plank not consumed");
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_task_pickup", timeoutTicks = 300)
    public static void pickUpItemsCollectsMatchingDrops(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        Villager villager = GameTestSupport.spawnVillager(helper, 10, 1, 10);
        BlockPos drop = helper.absolutePos(new BlockPos(14, 1, 10));
        helper.getLevel().addFreshEntity(new ItemEntity(helper.getLevel(), drop.getX() + 0.5, drop.getY(), drop.getZ() + 0.5, new ItemStack(Items.OAK_LOG, 3)));
        helper.getLevel().addFreshEntity(new ItemEntity(helper.getLevel(), drop.getX() + 0.5, drop.getY(), drop.getZ() + 1.5, new ItemStack(Items.DIRT, 2)));
        ScriptedJob job = new ScriptedJob(new PickUpItems(drop, 3.0, s -> s.is(ItemTags.LOGS)));
        CitizenTestSupport.enroll(villager, village, JobType.LUMBERJACK, ItemStack.EMPTY, job);
        helper.succeedWhen(() -> {
            assertResults(helper, job, Task.Status.SUCCESS);
            helper.assertTrue(Inventories.count(villager.getInventory(), s -> s.is(Items.OAK_LOG)) == 3, "logs not picked up");
            helper.assertTrue(Inventories.count(villager.getInventory(), s -> s.is(Items.DIRT)) == 0, "picked up non-matching item");
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_task_storage")
    public static void depositThenWithdraw(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        BlockPos store = new BlockPos(11, 1, 10);
        helper.setBlock(store, StorehouseContent.BLOCK.get());
        Villager villager = GameTestSupport.spawnVillager(helper, 10, 1, 10);
        villager.getInventory().addItem(new ItemStack(Items.OAK_LOG, 10));
        BlockPos storeAbs = helper.absolutePos(store);
        ScriptedJob job = new ScriptedJob(
                new Deposit(storeAbs, s -> true),
                new Withdraw(storeAbs, Map.of(Items.OAK_LOG, 4)),
                new Withdraw(storeAbs, Map.of(Items.OAK_LOG, 50)));
        CitizenTestSupport.enroll(villager, village, JobType.LUMBERJACK, ItemStack.EMPTY, job);
        helper.succeedWhen(() -> {
            assertResults(helper, job, Task.Status.SUCCESS, Task.Status.SUCCESS, Task.Status.FAILED);
            StorehouseBlockEntity storehouse = helper.getBlockEntity(store);
            helper.assertTrue(storehouse.count(Items.OAK_LOG) == 6, "stored " + storehouse.count(Items.OAK_LOG));
            helper.assertTrue(Inventories.count(villager.getInventory(), s -> s.is(Items.OAK_LOG)) == 4, "carried");
            VillageTestSupport.remove(helper, village);
        });
    }
}
```

- [ ] **Step 2:** Create `src/main/java/dev/andreymudri/villagercity/citizen/Inventories.java`:

```java
package dev.andreymudri.villagercity.citizen;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class Inventories {
    private Inventories() {
    }

    public static int count(Container container, Predicate<ItemStack> filter) {
        int total = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty() && filter.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    public static Map<Item, Integer> counts(Container container) {
        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty()) {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    /** What the container lacks to cover the needed amounts. */
    public static Map<Item, Integer> missing(Map<Item, Integer> needed, Container container) {
        Map<Item, Integer> have = counts(container);
        Map<Item, Integer> missing = new LinkedHashMap<>();
        needed.forEach((item, amount) -> {
            int lack = amount - have.getOrDefault(item, 0);
            if (lack > 0) {
                missing.put(item, lack);
            }
        });
        return missing;
    }
}
```

- [ ] **Step 3:** Create `src/main/java/dev/andreymudri/villagercity/citizen/task/MoveTo.java`:

```java
package dev.andreymudri.villagercity.citizen.task;

import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/** Walks until within reach of the target block's center. Fails after 200 ticks without getting closer. */
public final class MoveTo implements Task {
    public static final double SPEED = 0.6;
    public static final int STUCK_TICKS = 200;
    public static final int REPATH_TICKS = 20;

    private final BlockPos target;
    private final double reach;
    private double bestDistanceSqr;
    private long lastProgressTick;
    private long lastPathTick;

    public MoveTo(BlockPos target, double reach) {
        this.target = target.immutable();
        this.reach = reach;
    }

    @Override
    public void start(TaskContext ctx) {
        bestDistanceSqr = Double.MAX_VALUE;
        lastProgressTick = ctx.gameTime();
        lastPathTick = Long.MIN_VALUE;
    }

    @Override
    public Status tick(TaskContext ctx) {
        Villager villager = ctx.villager();
        PathNavigation navigation = villager.getNavigation();
        Vec3 center = Vec3.atCenterOf(target);
        double distanceSqr = villager.distanceToSqr(center);
        if (distanceSqr <= reach * reach) {
            navigation.stop();
            return Status.SUCCESS;
        }
        long now = ctx.gameTime();
        if (distanceSqr < bestDistanceSqr - 0.25) {
            bestDistanceSqr = distanceSqr;
            lastProgressTick = now;
        } else if (now - lastProgressTick > STUCK_TICKS) {
            navigation.stop();
            return Status.FAILED;
        }
        boolean ours = target.equals(navigation.getTargetPos());
        if ((navigation.isDone() || !ours) && now - lastPathTick >= (ours ? REPATH_TICKS : 0)) {
            Path path = navigation.createPath(target, Math.max(0, (int) Math.floor(reach)));
            if (path != null) {
                navigation.moveTo(path, SPEED);
            }
            lastPathTick = now;
        }
        villager.getLookControl().setLookAt(center);
        return Status.RUNNING;
    }

    public BlockPos target() {
        return target;
    }
}
```

- [ ] **Step 4:** Create `src/main/java/dev/andreymudri/villagercity/citizen/task/BreakBlock.java` (break time: `ceil(1 / (toolSpeed / hardness / (canHarvest ? 30 : 100)))`; verified: oak log with an iron axe takes 10 ticks):

```java
package dev.andreymudri.villagercity.citizen.task;

import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Breaks one block with the held tool at player-like speed, dropping its loot and wearing the tool. */
public final class BreakBlock implements Task {
    public static final double REACH = 6.0;

    private final BlockPos pos;
    private BlockState expected;
    private int required;
    private int progress;

    public BreakBlock(BlockPos pos) {
        this.pos = pos.immutable();
    }

    @Override
    public void start(TaskContext ctx) {
        expected = ctx.level().getBlockState(pos);
        progress = 0;
        required = breakTicks(ctx.level(), pos, expected, ctx.villager().getMainHandItem());
    }

    @Override
    public Status tick(TaskContext ctx) {
        ServerLevel level = ctx.level();
        Villager villager = ctx.villager();
        BlockState current = level.getBlockState(pos);
        if (current.isAir()) {
            return Status.SUCCESS;
        }
        if (current != expected) {
            expected = current;
            progress = 0;
            required = breakTicks(level, pos, current, villager.getMainHandItem());
        }
        if (required < 0 || villager.distanceToSqr(Vec3.atCenterOf(pos)) > REACH * REACH) {
            return Status.FAILED;
        }
        villager.getLookControl().setLookAt(Vec3.atCenterOf(pos));
        progress++;
        if (progress % 5 == 1) {
            villager.swing(InteractionHand.MAIN_HAND);
        }
        if (progress < required) {
            level.destroyBlockProgress(villager.getId(), pos, Math.min(9, progress * 10 / required));
            return Status.RUNNING;
        }
        level.destroyBlockProgress(villager.getId(), pos, -1);
        ItemStack tool = villager.getMainHandItem();
        Block.dropResources(current, level, pos, level.getBlockEntity(pos), villager, tool);
        level.destroyBlock(pos, false, villager);
        if (!tool.isEmpty() && tool.isDamageableItem()) {
            tool.hurtAndBreak(1, level, villager, item -> {
            });
        }
        return Status.SUCCESS;
    }

    @Override
    public void stop(TaskContext ctx) {
        ctx.level().destroyBlockProgress(ctx.villager().getId(), pos, -1);
    }

    /** Ticks to break the state with the tool; -1 when unbreakable. */
    public static int breakTicks(ServerLevel level, BlockPos pos, BlockState state, ItemStack tool) {
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0) {
            return -1;
        }
        if (hardness == 0) {
            return 1;
        }
        float speed = tool.getDestroySpeed(state);
        boolean harvest = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
        float perTick = speed / hardness / (harvest ? 30f : 100f);
        return Math.max(1, (int) Math.ceil(1f / perTick));
    }
}
```

- [ ] **Step 5:** Create `src/main/java/dev/andreymudri/villagercity/citizen/task/PlaceBlock.java`. Placement uses `Block.UPDATE_CLIENTS` only, so the first half of a bed or door is not popped by a shape update before its second half is placed:

```java
package dev.andreymudri.villagercity.citizen.task;

import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Places one block state, consuming its cost item from the villager's inventory. */
public final class PlaceBlock implements Task {
    public static final double REACH = 5.0;
    public static final int PLACE_DELAY_TICKS = 4;
    public static final int BLOCKED_TIMEOUT_TICKS = 100;

    private final BlockPos pos;
    private final BlockState state;
    private final Item cost;
    private int waited;
    private int blocked;

    public PlaceBlock(BlockPos pos, BlockState state, Item cost) {
        this.pos = pos.immutable();
        this.state = state;
        this.cost = cost;
    }

    @Override
    public Status tick(TaskContext ctx) {
        if (++waited < PLACE_DELAY_TICKS) {
            return Status.RUNNING;
        }
        ServerLevel level = ctx.level();
        Villager villager = ctx.villager();
        BlockState current = level.getBlockState(pos);
        if (current == state) {
            return Status.SUCCESS;
        }
        if (villager.distanceToSqr(Vec3.atCenterOf(pos)) > REACH * REACH) {
            return Status.FAILED;
        }
        if (!current.isAir() && !current.canBeReplaced()) {
            return Status.FAILED;
        }
        if (state.blocksMotion() && !level.getEntitiesOfClass(LivingEntity.class, new AABB(pos)).isEmpty()) {
            if (villager.getBoundingBox().intersects(new AABB(pos))) {
                double side = villager.getX() < pos.getX() + 0.5 ? -2.0 : 2.0;
                villager.getNavigation().moveTo(pos.getX() + 0.5 + side, pos.getY(), pos.getZ() + 0.5, MoveTo.SPEED);
            }
            return ++blocked > BLOCKED_TIMEOUT_TICKS ? Status.FAILED : Status.RUNNING;
        }
        if (cost != Items.AIR) {
            ItemStack taken = villager.getInventory().removeItemType(cost, 1);
            if (taken.isEmpty()) {
                return Status.FAILED;
            }
        }
        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
        SoundType sound = state.getSoundType();
        level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0f) / 2.0f, sound.getPitch() * 0.8f);
        villager.swing(InteractionHand.MAIN_HAND);
        return Status.SUCCESS;
    }
}
```

- [ ] **Step 6:** Create `src/main/java/dev/andreymudri/villagercity/citizen/task/PickUpItems.java`:

```java
package dev.andreymudri.villagercity.citizen.task;

import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/** Walks to and collects matching item entities near a point until none are left or 200 ticks pass. */
public final class PickUpItems implements Task {
    public static final int TIMEOUT_TICKS = 200;
    public static final double GRAB_DISTANCE = 1.5;

    private final BlockPos around;
    private final double radius;
    private final Predicate<ItemStack> filter;
    private long deadline;
    private long lastPathTick;

    public PickUpItems(BlockPos around, double radius, Predicate<ItemStack> filter) {
        this.around = around.immutable();
        this.radius = radius;
        this.filter = filter;
    }

    @Override
    public void start(TaskContext ctx) {
        deadline = ctx.gameTime() + TIMEOUT_TICKS;
        lastPathTick = Long.MIN_VALUE;
    }

    @Override
    public Status tick(TaskContext ctx) {
        long now = ctx.gameTime();
        if (now > deadline) {
            return Status.SUCCESS;
        }
        Villager villager = ctx.villager();
        List<ItemEntity> items = ctx.level().getEntitiesOfClass(ItemEntity.class, new AABB(around).inflate(radius),
                item -> item.isAlive() && filter.test(item.getItem()) && villager.getInventory().canAddItem(item.getItem()));
        if (items.isEmpty()) {
            return Status.SUCCESS;
        }
        ItemEntity nearest = items.stream().min(Comparator.comparingDouble(villager::distanceToSqr)).orElseThrow();
        if (villager.distanceToSqr(nearest) <= GRAB_DISTANCE * GRAB_DISTANCE) {
            ItemStack stack = nearest.getItem();
            int before = stack.getCount();
            ItemStack rest = villager.getInventory().addItem(stack);
            villager.take(nearest, before - rest.getCount());
            if (rest.isEmpty()) {
                nearest.discard();
            } else {
                nearest.setItem(rest);
            }
            return Status.RUNNING;
        }
        if (villager.getNavigation().isDone() || now - lastPathTick >= MoveTo.REPATH_TICKS) {
            villager.getNavigation().moveTo(nearest, MoveTo.SPEED);
            lastPathTick = now;
        }
        return Status.RUNNING;
    }
}
```

- [ ] **Step 7:** Create `src/main/java/dev/andreymudri/villagercity/citizen/task/Deposit.java`:

```java
package dev.andreymudri.villagercity.citizen.task;

import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Moves matching inventory stacks into the storehouse; whatever does not fit stays in the inventory. */
public final class Deposit implements Task {
    public static final double REACH = 3.0;

    private final BlockPos storehouse;
    private final Predicate<ItemStack> filter;

    public Deposit(BlockPos storehouse, Predicate<ItemStack> filter) {
        this.storehouse = storehouse.immutable();
        this.filter = filter;
    }

    @Override
    public Status tick(TaskContext ctx) {
        if (ctx.villager().distanceToSqr(Vec3.atCenterOf(storehouse)) > REACH * REACH) {
            return Status.FAILED;
        }
        if (!(ctx.level().getBlockEntity(storehouse) instanceof StorehouseBlockEntity entity)) {
            return Status.FAILED;
        }
        SimpleContainer inventory = ctx.villager().getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && filter.test(stack)) {
                inventory.setItem(i, entity.insertFromCitizen(stack));
            }
        }
        return Status.SUCCESS;
    }
}
```

- [ ] **Step 8:** Create `src/main/java/dev/andreymudri/villagercity/citizen/task/Withdraw.java`:

```java
package dev.andreymudri.villagercity.citizen.task;

import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Takes the wanted amounts from the storehouse; fails if the storehouse is short or the inventory overflows. */
public final class Withdraw implements Task {
    private final BlockPos storehouse;
    private final Map<Item, Integer> wanted;

    public Withdraw(BlockPos storehouse, Map<Item, Integer> wanted) {
        this.storehouse = storehouse.immutable();
        this.wanted = new LinkedHashMap<>(wanted);
    }

    @Override
    public Status tick(TaskContext ctx) {
        if (ctx.villager().distanceToSqr(Vec3.atCenterOf(storehouse)) > Deposit.REACH * Deposit.REACH) {
            return Status.FAILED;
        }
        if (!(ctx.level().getBlockEntity(storehouse) instanceof StorehouseBlockEntity entity) || !entity.hasAll(wanted)) {
            return Status.FAILED;
        }
        SimpleContainer inventory = ctx.villager().getInventory();
        for (Map.Entry<Item, Integer> entry : wanted.entrySet()) {
            int left = entry.getValue();
            while (left > 0) {
                ItemStack taken = entity.extractForCitizen(entry.getKey(), left);
                if (taken.isEmpty()) {
                    return Status.FAILED;
                }
                left -= taken.getCount();
                ItemStack rest = inventory.addItem(taken);
                if (!rest.isEmpty()) {
                    entity.insertFromCitizen(rest);
                    return Status.FAILED;
                }
            }
        }
        return Status.SUCCESS;
    }
}
```

- [ ] **Step 9:** Run `./gradlew --no-daemon -Dorg.gradle.workers.max=4 build` and `scripts/gametest.sh` — both pass.

- [ ] **Step 10:** Commit: `feat: primitive citizen tasks for moving, breaking, placing and hauling`

### Task 10: lumberjack job

**Files:**
- Create: `src/main/java/dev/andreymudri/villagercity/job/TreeFinder.java`
- Create: `src/main/java/dev/andreymudri/villagercity/job/ChopTree.java`
- Create: `src/main/java/dev/andreymudri/villagercity/job/Replant.java`
- Create: `src/main/java/dev/andreymudri/villagercity/job/LumberjackJob.java`
- Create: `src/main/java/dev/andreymudri/villagercity/gametest/LumberjackTests.java`

**Depends:** T4, T9

- [ ] **Step 1:** Write the failing GameTest `src/main/java/dev/andreymudri/villagercity/gametest/LumberjackTests.java`:

```java
package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.citizen.CitizenAttachments;
import dev.andreymudri.villagercity.citizen.CitizenData;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.job.LumberjackJob;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import dev.andreymudri.villagercity.storehouse.StorehouseContent;
import dev.andreymudri.villagercity.village.VillageData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class LumberjackTests {
    private static final BlockPos BELL = new BlockPos(40, 1, 40);
    private static final BlockPos STORE = new BlockPos(22, 1, 24);

    /** Five-log oak with a 3x3 canopy on its top three logs and a cap. */
    static void plantTree(GameTestHelper helper, BlockPos base) {
        for (int y = 0; y < 5; y++) {
            helper.setBlock(base.above(y), Blocks.OAK_LOG);
        }
        for (int y = 2; y <= 5; y++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos leaf = base.offset(dx, y, dz);
                    if (!(dx == 0 && dz == 0 && y < 5)) {
                        helper.setBlock(leaf, Blocks.OAK_LEAVES);
                    }
                }
            }
        }
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_lumberjack_chop", timeoutTicks = 1500)
    public static void chopsTreeStoresLogsAndReplants(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 6, false);
        helper.setBlock(STORE, StorehouseContent.BLOCK.get());
        village.setStorehousePos(helper.absolutePos(STORE));
        BlockPos base = new BlockPos(26, 1, 26);
        plantTree(helper, base);
        Villager villager = GameTestSupport.spawnVillager(helper, 22, 1, 22);
        villager.getInventory().addItem(new ItemStack(Items.OAK_SAPLING));
        CitizenData data = CitizenTestSupport.enroll(villager, village, JobType.LUMBERJACK, new ItemStack(Items.STONE_AXE), new LumberjackJob());
        helper.succeedWhen(() -> {
            StorehouseBlockEntity storehouse = helper.getBlockEntity(STORE);
            helper.assertTrue(storehouse.count(Items.OAK_LOG) == 5, "stored logs " + storehouse.count(Items.OAK_LOG));
            helper.assertBlockPresent(Blocks.OAK_SAPLING, base);
            helper.assertTrue(data.tool().getDamageValue() == 5, "axe damage " + data.tool().getDamageValue());
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_lumberjack_posts", timeoutTicks = 300)
    public static void ignoresLogsWithoutLeaves(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 6, false);
        helper.setBlock(STORE, StorehouseContent.BLOCK.get());
        village.setStorehousePos(helper.absolutePos(STORE));
        for (int y = 1; y <= 3; y++) {
            helper.setBlock(new BlockPos(26, y, 26), Blocks.OAK_LOG);
        }
        Villager villager = GameTestSupport.spawnVillager(helper, 22, 1, 22);
        CitizenTestSupport.enroll(villager, village, JobType.LUMBERJACK, new ItemStack(Items.STONE_AXE), new LumberjackJob());
        helper.runAfterDelay(200, () -> {
            helper.assertBlockPresent(Blocks.OAK_LOG, new BlockPos(26, 1, 26));
            helper.assertTrue(villager.getData(CitizenAttachments.RUNTIME).currentTask() == null, "lumberjack is working on a post");
            VillageTestSupport.remove(helper, village);
            helper.succeed();
        });
    }
}
```

- [ ] **Step 2:** Create `src/main/java/dev/andreymudri/villagercity/job/TreeFinder.java`:

```java
package dev.andreymudri.villagercity.job;

import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.plot.PlotRules;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Finds natural trees: a log standing on dirt whose trunk touches non-persistent leaves. */
public final class TreeFinder {
    public static final int SEARCH_RADIUS = 24;
    public static final int VILLAGE_MARGIN = 32;
    public static final int MAX_TRUNK = 32;
    public static final int SCAN_DOWN = 6;
    public static final int SCAN_UP = 12;
    private static final List<int[]> SPIRAL = PlotRules.spiral(SEARCH_RADIUS, 1);

    public record Tree(BlockPos base, List<BlockPos> logs, Block logBlock) {
    }

    private TreeFinder() {
    }

    public static Optional<Tree> findNearest(ServerLevel level, BlockPos from, VillageData village) {
        int villageReach = village.radius() + VILLAGE_MARGIN;
        for (int[] offset : SPIRAL) {
            int x = from.getX() + offset[0];
            int z = from.getZ() + offset[1];
            if (Math.abs(x - village.center().getX()) > villageReach || Math.abs(z - village.center().getZ()) > villageReach) {
                continue;
            }
            if (!level.isLoaded(new BlockPos(x, from.getY(), z))) {
                continue;
            }
            for (int y = from.getY() - SCAN_DOWN; y <= from.getY() + SCAN_UP; y++) {
                BlockPos pos = new BlockPos(x, y, z);
                if (!level.getBlockState(pos).is(BlockTags.LOGS) || !level.getBlockState(pos.below()).is(BlockTags.DIRT)) {
                    continue;
                }
                Optional<Tree> tree = trunk(level, pos);
                if (tree.isPresent()) {
                    return tree;
                }
                break;
            }
        }
        return Optional.empty();
    }

    /** Collects up to 32 connected logs of the base's kind, upward and sideways; empty when no natural leaves touch them. */
    public static Optional<Tree> trunk(ServerLevel level, BlockPos base) {
        Block log = level.getBlockState(base).getBlock();
        List<BlockPos> logs = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(base.immutable());
        seen.add(base.immutable());
        boolean leaves = false;
        while (!queue.isEmpty() && logs.size() < MAX_TRUNK) {
            BlockPos pos = queue.poll();
            logs.add(pos);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = 0; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos next = pos.offset(dx, dy, dz);
                        if (!seen.add(next)) {
                            continue;
                        }
                        BlockState state = level.getBlockState(next);
                        if (state.is(log)) {
                            queue.add(next);
                        } else if (state.is(BlockTags.LEAVES) && state.hasProperty(LeavesBlock.PERSISTENT) && !state.getValue(LeavesBlock.PERSISTENT)) {
                            leaves = true;
                        }
                    }
                }
            }
        }
        if (!leaves) {
            return Optional.empty();
        }
        logs.sort(Comparator.comparingInt(BlockPos::getY));
        return Optional.of(new Tree(base.immutable(), List.copyOf(logs), log));
    }
}
```

- [ ] **Step 3:** Create `src/main/java/dev/andreymudri/villagercity/job/ChopTree.java`:

```java
package dev.andreymudri.villagercity.job;

import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import dev.andreymudri.villagercity.citizen.task.BreakBlock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.phys.Vec3;

/** Breaks each trunk log bottom-up; logs out of reach or already gone are skipped, a failed log does not abort the rest. */
final class ChopTree implements Task {
    private final Deque<BlockPos> remaining;
    private @Nullable BreakBlock current;

    ChopTree(List<BlockPos> logs) {
        this.remaining = new ArrayDeque<>(logs);
    }

    @Override
    public Status tick(TaskContext ctx) {
        while (current == null) {
            BlockPos next = remaining.poll();
            if (next == null) {
                return Status.SUCCESS;
            }
            if (!ctx.level().getBlockState(next).is(BlockTags.LOGS)
                    || ctx.villager().distanceToSqr(Vec3.atCenterOf(next)) > BreakBlock.REACH * BreakBlock.REACH) {
                continue;
            }
            current = new BreakBlock(next);
            current.start(ctx);
        }
        Status status = current.tick(ctx);
        if (status != Status.RUNNING) {
            current.stop(ctx);
            current = null;
        }
        return Status.RUNNING;
    }

    @Override
    public void stop(TaskContext ctx) {
        if (current != null) {
            current.stop(ctx);
        }
    }
}
```

- [ ] **Step 4:** Create `src/main/java/dev/andreymudri/villagercity/job/Replant.java`:

```java
package dev.andreymudri.villagercity.job;

import dev.andreymudri.villagercity.citizen.Inventories;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import dev.andreymudri.villagercity.citizen.task.PlaceBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/** Plants a sapling at the stump when possible; never fails — a missing sapling just skips replanting. */
final class Replant implements Task {
    private final BlockPos pos;
    private final Item sapling;

    Replant(BlockPos pos, Item sapling) {
        this.pos = pos.immutable();
        this.sapling = sapling;
    }

    @Override
    public Status tick(TaskContext ctx) {
        ServerLevel level = ctx.level();
        if (!(sapling instanceof BlockItem blockItem)
                || !level.getBlockState(pos).isAir()
                || !level.getBlockState(pos.below()).is(BlockTags.DIRT)
                || Inventories.count(ctx.villager().getInventory(), stack -> stack.is(sapling)) == 0
                || ctx.villager().distanceToSqr(Vec3.atCenterOf(pos)) > PlaceBlock.REACH * PlaceBlock.REACH) {
            return Status.SUCCESS;
        }
        ctx.villager().getInventory().removeItemType(sapling, 1);
        level.setBlock(pos, blockItem.getBlock().defaultBlockState(), Block.UPDATE_ALL);
        return Status.SUCCESS;
    }
}
```

- [ ] **Step 5:** Create `src/main/java/dev/andreymudri/villagercity/job/LumberjackJob.java`:

```java
package dev.andreymudri.villagercity.job;

import dev.andreymudri.villagercity.citizen.Inventories;
import dev.andreymudri.villagercity.citizen.Job;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import dev.andreymudri.villagercity.citizen.TaskSequence;
import dev.andreymudri.villagercity.citizen.task.Deposit;
import dev.andreymudri.villagercity.citizen.task.MoveTo;
import dev.andreymudri.villagercity.citizen.task.PickUpItems;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Fells the nearest natural tree, collects the drops, replants, and hauls logs to the storehouse. */
public final class LumberjackJob implements Job {
    public static final int DEPOSIT_THRESHOLD = 16;
    public static final double DROP_RADIUS = 6.0;

    static final Map<Block, Item> SAPLINGS = Map.of(
            Blocks.OAK_LOG, Items.OAK_SAPLING,
            Blocks.SPRUCE_LOG, Items.SPRUCE_SAPLING,
            Blocks.BIRCH_LOG, Items.BIRCH_SAPLING,
            Blocks.JUNGLE_LOG, Items.JUNGLE_SAPLING,
            Blocks.ACACIA_LOG, Items.ACACIA_SAPLING,
            Blocks.DARK_OAK_LOG, Items.DARK_OAK_SAPLING,
            Blocks.CHERRY_LOG, Items.CHERRY_SAPLING,
            Blocks.MANGROVE_LOG, Items.MANGROVE_PROPAGULE);

    static boolean isLog(ItemStack stack) {
        return stack.is(ItemTags.LOGS);
    }

    static boolean isHaul(ItemStack stack) {
        return stack.is(ItemTags.LOGS) || stack.is(ItemTags.SAPLINGS) || stack.is(Items.STICK) || stack.is(Items.APPLE);
    }

    static boolean isDeposit(ItemStack stack) {
        return stack.is(ItemTags.LOGS) || stack.is(Items.STICK) || stack.is(Items.APPLE);
    }

    @Override
    public @Nullable Task plan(TaskContext ctx) {
        SimpleContainer inventory = ctx.villager().getInventory();
        BlockPos storehouse = ctx.village().storehousePos();
        int logs = Inventories.count(inventory, LumberjackJob::isLog);
        Optional<TreeFinder.Tree> tree = logs >= DEPOSIT_THRESHOLD
                ? Optional.empty()
                : TreeFinder.findNearest(ctx.level(), ctx.villager().blockPosition(), ctx.village());
        if (logs >= DEPOSIT_THRESHOLD || (tree.isEmpty() && Inventories.count(inventory, LumberjackJob::isDeposit) > 0)) {
            return storehouse == null ? null : TaskSequence.of(new MoveTo(storehouse, 2.5), new Deposit(storehouse, LumberjackJob::isDeposit));
        }
        if (tree.isEmpty()) {
            return null;
        }
        TreeFinder.Tree found = tree.get();
        return TaskSequence.of(
                new MoveTo(found.base(), 2.5),
                new ChopTree(found.logs()),
                new PickUpItems(found.base(), DROP_RADIUS, LumberjackJob::isHaul),
                new MoveTo(found.base(), 2.5),
                new Replant(found.base(), SAPLINGS.getOrDefault(found.logBlock(), Items.AIR)));
    }
}
```

- [ ] **Step 6:** Run `./gradlew --no-daemon -Dorg.gradle.workers.max=4 build` and `scripts/gametest.sh` — both pass.

- [ ] **Step 7:** Commit: `feat: lumberjack job fells trees, replants and hauls logs`

### Task 11: builder job

**Files:**
- Create: `src/main/java/dev/andreymudri/villagercity/job/BuilderJob.java`
- Create: `src/main/java/dev/andreymudri/villagercity/gametest/BuilderTests.java`

**Depends:** T4, T5, T9

- [ ] **Step 1:** Write the failing GameTest `src/main/java/dev/andreymudri/villagercity/gametest/BuilderTests.java`:

```java
package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.blueprint.Blueprint;
import dev.andreymudri.villagercity.blueprint.BlueprintPlacement;
import dev.andreymudri.villagercity.blueprint.Blueprints;
import dev.andreymudri.villagercity.citizen.Inventories;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.job.BuilderJob;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import dev.andreymudri.villagercity.storehouse.StorehouseContent;
import dev.andreymudri.villagercity.village.BuildingRecord;
import dev.andreymudri.villagercity.village.Plot;
import dev.andreymudri.villagercity.village.VillageData;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class BuilderTests {
    private static final BlockPos BELL = new BlockPos(24, 1, 24);
    private static final BlockPos STORE = new BlockPos(20, 1, 24);

    /** Places a storehouse holding `copies` full sets of the starter house materials. */
    static StorehouseBlockEntity stockedStorehouse(GameTestHelper helper, VillageData village, Blueprint blueprint, int copies) {
        helper.setBlock(STORE, StorehouseContent.BLOCK.get());
        village.setStorehousePos(helper.absolutePos(STORE));
        StorehouseBlockEntity storehouse = helper.getBlockEntity(STORE);
        for (Map.Entry<Item, Integer> entry : blueprint.requiredMaterials().entrySet()) {
            int left = entry.getValue() * copies;
            while (left > 0) {
                int stack = Math.min(left, new ItemStack(entry.getKey()).getMaxStackSize());
                storehouse.insertFromCitizen(new ItemStack(entry.getKey(), stack));
                left -= stack;
            }
        }
        return storehouse;
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_builder_house", timeoutTicks = 4000)
    public static void buildsStarterHouseFromStoredMaterials(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 4, false);
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        stockedStorehouse(helper, village, blueprint, 1);
        Villager villager = GameTestSupport.spawnVillager(helper, 22, 1, 22);
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, new BuilderJob());
        helper.succeedWhen(() -> {
            helper.assertTrue(village.houseCount() == 1, "houses " + village.houseCount());
            helper.assertTrue(village.plots().isEmpty(), "plot still open");
            helper.assertTrue(Inventories.count(villager.getInventory(), s -> s.getItem() instanceof BlockItem) == 0, "leftovers not deposited");
            BuildingRecord house = village.houses().get(0);
            for (BlueprintPlacement placement : blueprint.placements()) {
                BlockPos pos = house.origin().offset(placement.offset());
                helper.assertTrue(helper.getLevel().getBlockState(pos).is(placement.state().getBlock()),
                        "wrong block at " + helper.relativePos(pos) + ": " + helper.getLevel().getBlockState(pos));
            }
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_builder_obstruction", timeoutTicks = 4000)
    public static void clearsObstructionsInsideThePlot(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 4, false);
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        stockedStorehouse(helper, village, blueprint, 1);
        Villager villager = GameTestSupport.spawnVillager(helper, 12, 1, 14);
        BlockPos origin = new BlockPos(8, 1, 8);
        village.addPlot(new Plot(UUID.randomUUID(), blueprint.id().toString(), helper.absolutePos(origin), blueprint.size(), villager.getUUID()));
        BlockPos obstruction = origin.offset(3, 2, 3);
        helper.setBlock(obstruction, Blocks.STONE);
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, new BuilderJob());
        helper.succeedWhen(() -> {
            helper.assertTrue(village.houseCount() == 1, "houses " + village.houseCount());
            helper.assertBlockPresent(Blocks.AIR, obstruction);
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_builder_abandon", timeoutTicks = 3000)
    public static void abandonsPlotAfterRepeatedFailures(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 4, false);
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        stockedStorehouse(helper, village, blueprint, 2);
        Villager villager = GameTestSupport.spawnVillager(helper, 12, 1, 14);
        BlockPos origin = new BlockPos(8, 1, 8);
        UUID plotId = UUID.randomUUID();
        village.addPlot(new Plot(plotId, blueprint.id().toString(), helper.absolutePos(origin), blueprint.size(), villager.getUUID()));
        // second placement in build order is the wall plank at offset (1,1,0)
        helper.setBlock(origin.offset(1, 1, 0), Blocks.BEDROCK);
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, new BuilderJob());
        helper.succeedWhen(() -> {
            helper.assertTrue(village.plots().stream().noneMatch(p -> p.id().equals(plotId)), "plot not abandoned");
            helper.assertBlockPresent(Blocks.COBBLESTONE, origin);
            helper.assertBlockPresent(Blocks.BEDROCK, origin.offset(1, 1, 0));
            VillageTestSupport.remove(helper, village);
        });
    }
}
```

- [ ] **Step 2:** Create `src/main/java/dev/andreymudri/villagercity/job/BuilderJob.java`:

```java
package dev.andreymudri.villagercity.job;

import dev.andreymudri.villagercity.blueprint.Blueprint;
import dev.andreymudri.villagercity.blueprint.BlueprintPlacement;
import dev.andreymudri.villagercity.blueprint.Blueprints;
import dev.andreymudri.villagercity.citizen.Inventories;
import dev.andreymudri.villagercity.citizen.Job;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import dev.andreymudri.villagercity.citizen.TaskSequence;
import dev.andreymudri.villagercity.citizen.task.BreakBlock;
import dev.andreymudri.villagercity.citizen.task.Deposit;
import dev.andreymudri.villagercity.citizen.task.MoveTo;
import dev.andreymudri.villagercity.citizen.task.PickUpItems;
import dev.andreymudri.villagercity.citizen.task.PlaceBlock;
import dev.andreymudri.villagercity.citizen.task.Withdraw;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import dev.andreymudri.villagercity.village.BuildingRecord;
import dev.andreymudri.villagercity.village.Plot;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.VillageRegistry;
import dev.andreymudri.villagercity.village.plot.PlotPlanner;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Claims a plot once the storehouse holds a full blueprint's materials, withdraws what is missing,
 * then clears and places blocks in build order. Progress lives in the world and the plot record,
 * so a reloaded builder resumes by skipping blocks that are already right.
 */
public final class BuilderJob implements Job {
    public static final int MAX_CONSECUTIVE_FAILURES = 5;
    public static final double WORK_REACH = 4.0;

    private int consecutiveFailures;

    @Override
    public @Nullable Task plan(TaskContext ctx) {
        ServerLevel level = ctx.level();
        VillageData village = ctx.village();
        SimpleContainer inventory = ctx.villager().getInventory();
        BlockPos storehouse = village.storehousePos();
        UUID self = ctx.villager().getUUID();

        Optional<Plot> mine = village.plotBuiltBy(self);
        if (mine.isEmpty()) {
            if (storehouse != null && Inventories.count(inventory, BuilderJob::isBuildingMaterial) > 0) {
                return deposit(storehouse);
            }
            mine = claimPlot(ctx);
            if (mine.isEmpty()) {
                return null;
            }
        }
        Plot plot = mine.get();
        Optional<Blueprint> blueprint = Blueprints.load(level, ResourceLocation.parse(plot.blueprint()));
        if (blueprint.isEmpty()) {
            village.removePlot(plot.id());
            VillageRegistry.get(level).setDirty();
            return null;
        }
        List<BlueprintPlacement> unfinished = blueprint.get().placements().stream()
                .filter(placement -> !isDone(level, plot, placement))
                .toList();
        if (unfinished.isEmpty()) {
            village.removePlot(plot.id());
            village.addHouse(new BuildingRecord(plot.blueprint(), plot.origin(), plot.size()));
            VillageRegistry.get(level).setDirty();
            consecutiveFailures = 0;
            return storehouse != null && Inventories.count(inventory, BuilderJob::isBuildingMaterial) > 0 ? deposit(storehouse) : null;
        }
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            village.removePlot(plot.id());
            VillageRegistry.get(level).setDirty();
            consecutiveFailures = 0;
            return null;
        }
        Map<Item, Integer> missing = Inventories.missing(Blueprint.materialsFor(unfinished), inventory);
        if (!missing.isEmpty()) {
            if (storehouse == null || !(level.getBlockEntity(storehouse) instanceof StorehouseBlockEntity entity) || !entity.hasAll(missing)) {
                return null;
            }
            return TaskSequence.of(new MoveTo(storehouse, 2.5), new Withdraw(storehouse, missing));
        }
        BlueprintPlacement next = unfinished.get(0);
        BlockPos pos = plot.origin().offset(next.offset());
        BlockState current = level.getBlockState(pos);
        if (next.state().isAir() || (!current.isAir() && !current.canBeReplaced())) {
            return TaskSequence.of(
                    new MoveTo(pos, WORK_REACH),
                    new BreakBlock(pos),
                    new PickUpItems(pos, 2.0, BuilderJob::isBuildingMaterial));
        }
        return TaskSequence.of(
                new MoveTo(pos, WORK_REACH),
                new PlaceBlock(pos, next.state(), Blueprint.costOf(next.state())));
    }

    @Override
    public void onTaskFinished(TaskContext ctx, Task task, Task.Status status) {
        consecutiveFailures = status == Task.Status.FAILED ? consecutiveFailures + 1 : 0;
    }

    /** A placement is done when the block type matches (door and bed states may legitimately change) or, for air, when the cell is empty. */
    public static boolean isDone(ServerLevel level, Plot plot, BlueprintPlacement placement) {
        BlockState current = level.getBlockState(plot.origin().offset(placement.offset()));
        if (placement.state().isAir()) {
            return current.isAir() || !current.getFluidState().isEmpty();
        }
        return current.is(placement.state().getBlock());
    }

    static boolean isBuildingMaterial(ItemStack stack) {
        return stack.getItem() instanceof BlockItem;
    }

    private static Task deposit(BlockPos storehouse) {
        return TaskSequence.of(new MoveTo(storehouse, 2.5), new Deposit(storehouse, BuilderJob::isBuildingMaterial));
    }

    private static Optional<Plot> claimPlot(TaskContext ctx) {
        ServerLevel level = ctx.level();
        VillageData village = ctx.village();
        BlockPos storehouse = village.storehousePos();
        if (storehouse == null || !village.plots().isEmpty()) {
            return Optional.empty();
        }
        Optional<Blueprint> blueprint = Blueprints.load(level, Blueprints.STARTER_HOUSE);
        if (blueprint.isEmpty()
                || !(level.getBlockEntity(storehouse) instanceof StorehouseBlockEntity entity)
                || !entity.hasAll(blueprint.get().requiredMaterials())) {
            return Optional.empty();
        }
        Optional<BlockPos> origin = PlotPlanner.find(level, village, blueprint.get().size());
        if (origin.isEmpty()) {
            return Optional.empty();
        }
        Plot plot = new Plot(UUID.randomUUID(), blueprint.get().id().toString(), origin.get(), blueprint.get().size(), ctx.villager().getUUID());
        village.addPlot(plot);
        VillageRegistry.get(level).setDirty();
        return Optional.of(plot);
    }
}
```

- [ ] **Step 3:** Run `./gradlew --no-daemon -Dorg.gradle.workers.max=4 build` and `scripts/gametest.sh` — both pass.

- [ ] **Step 4:** Commit: `feat: builder job claims plots and builds blueprints block by block`

### Task 12: village lifecycle — ticker, storehouse placement, job assignment

**Files:**
- Create: `src/main/java/dev/andreymudri/villagercity/village/VillageTicker.java`
- Create: `src/main/java/dev/andreymudri/villagercity/village/JobAssignment.java`
- Create: `src/main/java/dev/andreymudri/villagercity/storehouse/StorehouseService.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/VillagerCity.java`
- Create: `src/main/java/dev/andreymudri/villagercity/gametest/VillageLifecycleTests.java`

**Depends:** T7, T10, T11

- [ ] **Step 1:** Write the failing GameTest `src/main/java/dev/andreymudri/villagercity/gametest/VillageLifecycleTests.java`:

```java
package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.citizen.CitizenAttachments;
import dev.andreymudri.villagercity.citizen.CitizenData;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.storehouse.StorehouseContent;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.VillageTicker;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class VillageLifecycleTests {
    private static final BlockPos BELL = new BlockPos(24, 1, 24);

    private static long countJob(List<Villager> villagers, JobType job) {
        return villagers.stream()
                .map(v -> v.getExistingData(CitizenAttachments.CITIZEN.get()).map(CitizenData::job).orElse(JobType.NONE))
                .filter(job::equals)
                .count();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_life_assign")
    public static void placesStorehouseAndAssignsOneLumberjackAndOneBuilder(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        List<Villager> villagers = List.of(
                GameTestSupport.spawnVillager(helper, 20, 1, 20),
                GameTestSupport.spawnVillager(helper, 28, 1, 20),
                GameTestSupport.spawnVillager(helper, 20, 1, 28));
        VillageTicker.tickVillage(helper.getLevel(), village);
        helper.assertTrue(village.storehousePos() != null, "no storehouse position");
        helper.assertTrue(helper.getLevel().getBlockState(village.storehousePos()).is(StorehouseContent.BLOCK.get()), "storehouse block missing");
        helper.assertTrue(countJob(villagers, JobType.LUMBERJACK) == 1, "lumberjacks " + countJob(villagers, JobType.LUMBERJACK));
        helper.assertTrue(countJob(villagers, JobType.BUILDER) == 1, "builders " + countJob(villagers, JobType.BUILDER));
        Villager lumberjack = villagers.stream()
                .filter(v -> v.getExistingData(CitizenAttachments.CITIZEN.get()).map(d -> d.job() == JobType.LUMBERJACK).orElse(false))
                .findFirst().orElseThrow();
        helper.assertTrue(lumberjack.getData(CitizenAttachments.CITIZEN).tool().is(Items.STONE_AXE), "lumberjack has no axe");
        VillageTicker.tickVillage(helper.getLevel(), village);
        helper.assertTrue(countJob(villagers, JobType.LUMBERJACK) == 1 && countJob(villagers, JobType.BUILDER) == 1, "second tick changed jobs");
        VillageTestSupport.remove(helper, village);
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_life_storehouse")
    public static void replacesMissingStorehouse(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        VillageTicker.tickVillage(helper.getLevel(), village);
        BlockPos first = village.storehousePos();
        helper.assertTrue(first != null, "no storehouse");
        helper.getLevel().destroyBlock(first, false);
        VillageTicker.tickVillage(helper.getLevel(), village);
        helper.assertTrue(village.storehousePos() != null, "storehouse not replaced");
        helper.assertTrue(helper.getLevel().getBlockState(village.storehousePos()).is(StorehouseContent.BLOCK.get()), "replacement block missing");
        VillageTestSupport.remove(helper, village);
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_life_skip")
    public static void skipsNitwitsBabiesAndEmployedVillagers(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        Villager nitwit = GameTestSupport.spawnVillager(helper, 20, 1, 20);
        nitwit.setVillagerData(nitwit.getVillagerData().setProfession(VillagerProfession.NITWIT));
        Villager baby = GameTestSupport.spawnVillager(helper, 28, 1, 20);
        baby.setAge(-24000);
        Villager farmer = GameTestSupport.spawnVillager(helper, 20, 1, 28);
        farmer.setVillagerData(farmer.getVillagerData().setProfession(VillagerProfession.FARMER));
        VillageTicker.tickVillage(helper.getLevel(), village);
        List<Villager> villagers = List.of(nitwit, baby, farmer);
        helper.assertTrue(countJob(villagers, JobType.LUMBERJACK) + countJob(villagers, JobType.BUILDER) == 0, "assigned an ineligible villager");
        VillageTestSupport.remove(helper, village);
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_life_refill")
    public static void refillsJobAfterCitizenDies(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        Villager a = GameTestSupport.spawnVillager(helper, 20, 1, 20);
        Villager b = GameTestSupport.spawnVillager(helper, 28, 1, 20);
        VillageTicker.tickVillage(helper.getLevel(), village);
        Villager lumberjack = countJob(List.of(a), JobType.LUMBERJACK) == 1 ? a : b;
        lumberjack.discard();
        Villager replacement = GameTestSupport.spawnVillager(helper, 20, 1, 28);
        VillageTicker.tickVillage(helper.getLevel(), village);
        helper.assertTrue(countJob(List.of(replacement), JobType.LUMBERJACK) == 1, "lumberjack not replaced");
        VillageTestSupport.remove(helper, village);
        helper.succeed();
    }
}
```

- [ ] **Step 2:** Create `src/main/java/dev/andreymudri/villagercity/storehouse/StorehouseService.java`:

```java
package dev.andreymudri.villagercity.storehouse;

import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.plot.PlotPlanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

public final class StorehouseService {
    private static final Vec3i SIZE = new Vec3i(1, 1, 1);

    private StorehouseService() {
    }

    /** Places a storehouse at the first buildable spot near the bell when the village has none, or when its block is gone. */
    public static void ensureStorehouse(ServerLevel level, VillageData village) {
        BlockPos pos = village.storehousePos();
        if (pos != null) {
            if (!level.isLoaded(pos) || level.getBlockState(pos).is(StorehouseContent.BLOCK.get())) {
                return;
            }
            village.setStorehousePos(null);
        }
        PlotPlanner.find(level, village, SIZE).ifPresent(spot -> {
            level.setBlock(spot, StorehouseContent.BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
            village.setStorehousePos(spot);
        });
    }
}
```

- [ ] **Step 3:** Create `src/main/java/dev/andreymudri/villagercity/village/JobAssignment.java`:

```java
package dev.andreymudri.villagercity.village;

import dev.andreymudri.villagercity.citizen.CitizenAttachments;
import dev.andreymudri.villagercity.citizen.CitizenData;
import dev.andreymudri.villagercity.citizen.JobType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

/** Slice 1: keep one lumberjack and one builder, drawn from adult villagers with no vanilla profession. */
public final class JobAssignment {
    public static final List<JobType> SLICE_JOBS = List.of(JobType.LUMBERJACK, JobType.BUILDER);
    public static final int VERTICAL_RANGE = 32;

    private JobAssignment() {
    }

    public static void assign(ServerLevel level, VillageData village) {
        AABB area = new AABB(village.center()).inflate(village.radius(), VERTICAL_RANGE, village.radius());
        Map<JobType, Integer> filled = new EnumMap<>(JobType.class);
        List<Villager> candidates = new ArrayList<>();
        for (Villager villager : level.getEntitiesOfClass(Villager.class, area, Villager::isAlive)) {
            CitizenData data = villager.getExistingData(CitizenAttachments.CITIZEN.get()).orElse(null);
            if (data != null && data.villageId() != null) {
                if (village.id().equals(data.villageId()) && data.job() != JobType.NONE) {
                    filled.merge(data.job(), 1, Integer::sum);
                }
                if (!village.id().equals(data.villageId()) || data.job() != JobType.NONE) {
                    continue;
                }
            }
            if (!villager.isBaby() && villager.getVillagerData().getProfession() == VillagerProfession.NONE) {
                candidates.add(villager);
            }
        }
        candidates.sort(Comparator.comparing(Entity::getUUID));
        Iterator<Villager> next = candidates.iterator();
        for (JobType job : SLICE_JOBS) {
            if (filled.getOrDefault(job, 0) == 0 && next.hasNext()) {
                employ(next.next(), village, job);
            }
        }
    }

    public static void employ(Villager villager, VillageData village, JobType job) {
        ItemStack tool = job == JobType.LUMBERJACK ? new ItemStack(Items.STONE_AXE) : ItemStack.EMPTY;
        villager.setData(CitizenAttachments.CITIZEN, new CitizenData(village.id(), job, tool));
    }
}
```

- [ ] **Step 4:** Create `src/main/java/dev/andreymudri/villagercity/village/VillageTicker.java`:

```java
package dev.andreymudri.villagercity.village;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.storehouse.StorehouseService;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Every 100 game ticks: detect villages around players, then keep each managed, loaded village supplied and staffed. */
@EventBusSubscriber(modid = VillagerCity.MODID)
public final class VillageTicker {
    public static final int INTERVAL_TICKS = 100;

    private VillageTicker() {
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getGameTime() % INTERVAL_TICKS != 0) {
            return;
        }
        VillageDetector.scanAroundPlayers(level);
        VillageRegistry registry = VillageRegistry.get(level);
        for (VillageData village : List.copyOf(registry.all())) {
            if (village.managed()) {
                tickVillage(level, village);
            }
        }
        registry.setDirty();
    }

    public static void tickVillage(ServerLevel level, VillageData village) {
        if (!level.isLoaded(village.center())) {
            return;
        }
        StorehouseService.ensureStorehouse(level, village);
        JobAssignment.assign(level, village);
    }
}
```

- [ ] **Step 5:** Modify `src/main/java/dev/andreymudri/villagercity/VillagerCity.java` — register the job factories in the constructor. The full file becomes:

```java
package dev.andreymudri.villagercity;

import com.mojang.logging.LogUtils;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.citizen.Jobs;
import dev.andreymudri.villagercity.job.BuilderJob;
import dev.andreymudri.villagercity.job.LumberjackJob;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(VillagerCity.MODID)
public final class VillagerCity {
    public static final String MODID = "villagercity";
    public static final Logger LOGGER = LogUtils.getLogger();

    public VillagerCity(IEventBus modBus) {
        Jobs.register(JobType.LUMBERJACK, LumberjackJob::new);
        Jobs.register(JobType.BUILDER, BuilderJob::new);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
```

- [ ] **Step 6:** Run `./gradlew --no-daemon -Dorg.gradle.workers.max=4 build` and `scripts/gametest.sh` — both pass.

- [ ] **Step 7:** Commit: `feat: village ticker places storehouses and assigns lumberjack and builder`

### Task 13: debug command

**Files:**
- Create: `src/main/java/dev/andreymudri/villagercity/command/VillageCommand.java`
- Create: `src/main/java/dev/andreymudri/villagercity/gametest/VillageCommandTests.java`

**Depends:** T7, T8

- [ ] **Step 1:** Write the failing GameTest `src/main/java/dev/andreymudri/villagercity/gametest/VillageCommandTests.java`:

```java
package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.command.VillageCommand;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import dev.andreymudri.villagercity.storehouse.StorehouseContent;
import dev.andreymudri.villagercity.village.VillageData;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class VillageCommandTests {
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_command_describe")
    public static void describeListsVillageStorehouseAndCitizens(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, new BlockPos(24, 1, 24), 8, false);
        BlockPos store = new BlockPos(20, 1, 24);
        helper.setBlock(store, StorehouseContent.BLOCK.get());
        village.setStorehousePos(helper.absolutePos(store));
        StorehouseBlockEntity storehouse = helper.getBlockEntity(store);
        storehouse.insertFromCitizen(new ItemStack(Items.OAK_LOG, 5));
        Villager villager = GameTestSupport.spawnVillager(helper, 22, 1, 22);
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, new ScriptedJob());

        String text = String.join("\n", VillageCommand.describe(helper.getLevel(), village));
        helper.assertTrue(text.contains(village.id().toString()), "missing id:\n" + text);
        helper.assertTrue(text.contains("age: dark"), "missing age:\n" + text);
        helper.assertTrue(text.contains("houses: 0"), "missing house count:\n" + text);
        helper.assertTrue(text.contains("oak_log x5"), "missing storehouse contents:\n" + text);
        helper.assertTrue(text.contains("job=builder") && text.contains("task=idle"), "missing citizen:\n" + text);
        VillageTestSupport.remove(helper, village);
        helper.succeed();
    }
}
```

- [ ] **Step 2:** Create `src/main/java/dev/andreymudri/villagercity/command/VillageCommand.java`:

```java
package dev.andreymudri.villagercity.command;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.citizen.CitizenAttachments;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.VillageRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** /villagercity village — prints the nearest village's state. */
@EventBusSubscriber(modid = VillagerCity.MODID)
public final class VillageCommand {
    public static final int SEARCH_DISTANCE = 256;

    private VillageCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("villagercity")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("village").executes(context -> show(context.getSource()))));
    }

    private static int show(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        VillageData village = VillageRegistry.get(level).nearest(BlockPos.containing(source.getPosition()), SEARCH_DISTANCE);
        if (village == null) {
            source.sendFailure(Component.literal("No village within " + SEARCH_DISTANCE + " blocks"));
            return 0;
        }
        for (String line : describe(level, village)) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    public static List<String> describe(ServerLevel level, VillageData village) {
        List<String> lines = new ArrayList<>();
        lines.add("Village " + village.id());
        lines.add("center: " + village.center().toShortString() + "  radius: " + village.radius() + "  age: " + village.age().getSerializedName());
        lines.add("houses: " + village.houseCount() + "  plots in progress: " + village.plots().size());
        BlockPos storehouse = village.storehousePos();
        if (storehouse == null) {
            lines.add("storehouse: none");
        } else if (level.isLoaded(storehouse) && level.getBlockEntity(storehouse) instanceof StorehouseBlockEntity entity) {
            lines.add("storehouse " + storehouse.toShortString() + ": " + format(entity.counts()));
        } else {
            lines.add("storehouse " + storehouse.toShortString() + ": not loaded");
        }
        AABB area = new AABB(village.center()).inflate(village.radius(), 32, village.radius());
        for (Villager villager : level.getEntitiesOfClass(Villager.class, area)) {
            villager.getExistingData(CitizenAttachments.CITIZEN.get())
                    .filter(data -> village.id().equals(data.villageId()))
                    .ifPresent(data -> {
                        Task task = villager.getData(CitizenAttachments.RUNTIME).currentTask();
                        lines.add("citizen " + villager.getUUID() + " job=" + data.job().getSerializedName()
                                + " task=" + (task == null ? "idle" : task.getClass().getSimpleName()));
                    });
        }
        return lines;
    }

    private static String format(Map<Item, Integer> counts) {
        if (counts.isEmpty()) {
            return "empty";
        }
        return counts.entrySet().stream()
                .map(entry -> BuiltInRegistries.ITEM.getKey(entry.getKey()).getPath() + " x" + entry.getValue())
                .collect(Collectors.joining(", "));
    }
}
```

- [ ] **Step 3:** Run `./gradlew --no-daemon -Dorg.gradle.workers.max=4 build` and `scripts/gametest.sh` — both pass.

- [ ] **Step 4:** Commit: `feat: /villagercity village debug command`

### Task 14: end-to-end GameTests

**Files:**
- Create: `src/main/java/dev/andreymudri/villagercity/gametest/EndToEndTests.java`

**Depends:** T12, T13

- [ ] **Step 1:** Write `src/main/java/dev/andreymudri/villagercity/gametest/EndToEndTests.java`. The first test runs the whole slice with no forced jobs: the ticker registers nothing on its own here (there are no players), so the village is registered directly, marked managed, and the real ticker staffs it and places the storehouse. The storehouse is stocked with everything except logs, so the house only gets built from wood the lumberjack gathers. The second test saves the builder and the registry mid-build and reloads both.

```java
package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.blueprint.Blueprint;
import dev.andreymudri.villagercity.blueprint.BlueprintPlacement;
import dev.andreymudri.villagercity.blueprint.Blueprints;
import dev.andreymudri.villagercity.citizen.CitizenAttachments;
import dev.andreymudri.villagercity.citizen.CitizenData;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.job.BuilderJob;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import dev.andreymudri.villagercity.storehouse.StorehouseContent;
import dev.andreymudri.villagercity.village.Plot;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.VillageRegistry;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class EndToEndTests {
    private static final BlockPos BELL = new BlockPos(24, 1, 24);

    private static void stockAllButLogs(StorehouseBlockEntity storehouse, Blueprint blueprint) {
        for (Map.Entry<Item, Integer> entry : blueprint.requiredMaterials().entrySet()) {
            if (entry.getKey() != Items.OAK_LOG) {
                storehouse.insertFromCitizen(new ItemStack(entry.getKey(), entry.getValue()));
            }
        }
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_e2e_village", timeoutTicks = 12000)
    public static void villageBuildsHouseFromGatheredWood(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, true);
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        LumberjackTests.plantTree(helper, new BlockPos(8, 1, 8));
        LumberjackTests.plantTree(helper, new BlockPos(8, 1, 38));
        LumberjackTests.plantTree(helper, new BlockPos(38, 1, 8));
        GameTestSupport.spawnVillager(helper, 23, 1, 23);
        GameTestSupport.spawnVillager(helper, 25, 1, 23);
        AtomicBoolean stocked = new AtomicBoolean();
        helper.onEachTick(() -> {
            BlockPos storehouse = village.storehousePos();
            if (!stocked.get() && storehouse != null
                    && helper.getLevel().getBlockEntity(storehouse) instanceof StorehouseBlockEntity entity) {
                stockAllButLogs(entity, blueprint);
                stocked.set(true);
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(stocked.get(), "storehouse never placed");
            helper.assertTrue(village.houseCount() == 1, "houses " + village.houseCount() + ", plots " + village.plots().size());
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_e2e_reload", timeoutTicks = 6000)
    public static void builderResumesAfterSaveAndReload(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        ServerLevel level = helper.getLevel();
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 4, false);
        Blueprint blueprint = Blueprints.load(level, Blueprints.STARTER_HOUSE).orElseThrow();
        BuilderTests.stockedStorehouse(helper, village, blueprint, 1);
        Villager builder = GameTestSupport.spawnVillager(helper, 22, 1, 22);
        builder.setData(CitizenAttachments.CITIZEN, new CitizenData(village.id(), JobType.BUILDER, ItemStack.EMPTY));
        UUID villageId = village.id();
        UUID builderId = builder.getUUID();
        AtomicBoolean reloaded = new AtomicBoolean();
        AtomicReference<Plot> plotAtReload = new AtomicReference<>();

        helper.onEachTick(() -> {
            if (reloaded.get()) {
                return;
            }
            VillageData live = VillageRegistry.get(level).get(villageId);
            Plot plot = live == null ? null : live.plotBuiltBy(builderId).orElse(null);
            if (plot == null) {
                return;
            }
            long placed = blueprint.placements().stream()
                    .filter(p -> !p.state().isAir())
                    .filter(p -> BuilderJob.isDone(level, plot, p))
                    .count();
            if (placed < 20) {
                return;
            }
            Villager current = (Villager) level.getEntity(builderId);
            CompoundTag entityTag = new CompoundTag();
            current.save(entityTag);
            current.discard();
            Entity copy = EntityType.create(entityTag, level).orElseThrow();
            level.addFreshEntity(copy);

            CompoundTag registryTag = VillageRegistry.get(level).save(new CompoundTag(), level.registryAccess());
            level.getDataStorage().set(VillageRegistry.NAME, VillageRegistry.load(registryTag, level.registryAccess()));
            plotAtReload.set(plot);
            reloaded.set(true);
        });

        helper.succeedWhen(() -> {
            helper.assertTrue(reloaded.get(), "reload never happened");
            VillageData live = VillageRegistry.get(level).get(villageId);
            helper.assertTrue(live != null && live != village, "registry was not replaced");
            helper.assertTrue(live.houseCount() == 1, "houses after reload " + live.houseCount());
            Plot plot = plotAtReload.get();
            for (BlueprintPlacement placement : blueprint.placements()) {
                helper.assertTrue(BuilderJob.isDone(level, plot, placement), "unfinished at " + helper.relativePos(plot.origin().offset(placement.offset())));
            }
            VillageRegistry.get(level).remove(villageId);
        });
    }
}
```

- [ ] **Step 2:** Run `./gradlew --no-daemon -Dorg.gradle.workers.max=4 build` and `scripts/gametest.sh` — both pass.

- [ ] **Step 3:** Commit: `test: end-to-end village growth and build resume after reload`

### Task 15: movement reliability — path accuracy and pause-safe timers

Found while running Task 14: `villagebuildshousefromgatheredwood` fails about half the time. `MoveTo` asks the
pathfinder for accuracy `floor(reach)`, which counts a Manhattan distance of `reach` as arrived, while
`MoveTo` measures Euclidean distance to the block center (with a +0.5 in y). With reach 4.0 a target 4 blocks
away is "reached" for the pathfinder (1-node path) but 4.03+ away for `MoveTo`, so the builder never moves and
re-claims the same plot forever. `PickUpItems` has the same flaw through `navigation.moveTo(entity)` (accuracy
1 against a 1.5 grab distance), which makes the lumberjack leave logs on the ground. Both tasks also time out
on absolute game time, so ticks spent paused by the scheduler (night, panic, trading) count against them.

**Files:**
- Modify: `src/main/java/dev/andreymudri/villagercity/citizen/task/MoveTo.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/citizen/task/PickUpItems.java`
- Modify: `src/main/java/dev/andreymudri/villagercity/job/LumberjackJob.java`
- Create: `src/main/java/dev/andreymudri/villagercity/gametest/MovementTests.java`

**Depends:** T12

**Model:** capable

- [ ] **Step 1:** Write `src/main/java/dev/andreymudri/villagercity/gametest/MovementTests.java`. Each test must fail on the current code for the stated reason before Step 2; record the failing output.

```java
package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.citizen.Inventories;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.task.MoveTo;
import dev.andreymudri.villagercity.citizen.task.PickUpItems;
import dev.andreymudri.villagercity.village.VillageData;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class MovementTests {
    private static final BlockPos BELL = new BlockPos(2, 1, 2);

    /** Before the fix: accuracy floor(4.0)=4 treats the start as arrived, the villager never moves, FAILED after 200 ticks. */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_move_reach_edge", timeoutTicks = 400)
    public static void moveToArrivesWhenTargetIsExactlyReachBlocksAway(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        Villager villager = GameTestSupport.spawnVillager(helper, 10, 1, 10);
        BlockPos target = helper.absolutePos(new BlockPos(14, 1, 10));
        ScriptedJob job = new ScriptedJob(new MoveTo(target, 4.0));
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, job);
        helper.succeedWhen(() -> {
            helper.assertTrue(job.results.equals(List.of(Task.Status.SUCCESS)), "results " + job.results);
            helper.assertTrue(villager.distanceToSqr(Vec3.atCenterOf(target)) <= 4.0 * 4.0, "not within reach");
            VillageTestSupport.remove(helper, village);
        });
    }

    /** Before the fix: moveTo(entity) uses accuracy 1, the neighbouring block counts as arrived, the item stays 1.9 away. */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_pickup_edge", timeoutTicks = 300)
    public static void pickUpItemsReachesItemInNeighbouringBlock(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        Villager villager = GameTestSupport.spawnVillager(helper, 10, 1, 10);
        Vec3 stand = helper.absoluteVec(new Vec3(10.5, 1, 10.05));
        villager.moveTo(stand.x, stand.y, stand.z);
        Vec3 itemAt = helper.absoluteVec(new Vec3(10.5, 1, 11.95));
        helper.getLevel().addFreshEntity(new ItemEntity(helper.getLevel(), itemAt.x, itemAt.y, itemAt.z, new ItemStack(Items.OAK_LOG, 1)));
        ScriptedJob job = new ScriptedJob(new PickUpItems(BlockPos.containing(itemAt), 3.0, s -> s.is(ItemTags.LOGS)));
        CitizenTestSupport.enroll(villager, village, JobType.LUMBERJACK, ItemStack.EMPTY, job);
        helper.succeedWhen(() -> {
            helper.assertTrue(job.results.equals(List.of(Task.Status.SUCCESS)), "results " + job.results);
            helper.assertTrue(Inventories.count(villager.getInventory(), s -> s.is(Items.OAK_LOG)) == 1, "log not picked up");
            VillageTestSupport.remove(helper, village);
        });
    }

    /** Before the fix: the 300-tick REST pause exhausts the absolute 200-tick deadline and the task returns SUCCESS empty-handed. */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_pickup_pause", timeoutTicks = 900)
    public static void pickUpItemsSurvivesNightPause(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        Villager villager = GameTestSupport.spawnVillager(helper, 10, 1, 10);
        BlockPos drop = helper.absolutePos(new BlockPos(30, 1, 10));
        helper.getLevel().addFreshEntity(new ItemEntity(helper.getLevel(), drop.getX() + 0.5, drop.getY(), drop.getZ() + 0.5, new ItemStack(Items.OAK_LOG, 3)));
        helper.runAtTickTime(1, () -> helper.getLevel().setDayTime(13000));
        helper.runAtTickTime(320, () -> helper.getLevel().setDayTime(1000));
        ScriptedJob job = new ScriptedJob(new PickUpItems(drop, 3.0, s -> s.is(ItemTags.LOGS)));
        CitizenTestSupport.enroll(villager, village, JobType.LUMBERJACK, ItemStack.EMPTY, job);
        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getTick() > 320, "still night");
            helper.assertTrue(job.results.equals(List.of(Task.Status.SUCCESS)), "results " + job.results);
            helper.assertTrue(Inventories.count(villager.getInventory(), s -> s.is(Items.OAK_LOG)) == 3, "logs carried " + Inventories.count(villager.getInventory(), s -> s.is(Items.OAK_LOG)));
            VillageTestSupport.remove(helper, village);
        });
    }
}
```

If a test does not fail on the current code for its stated reason, adjust its setup (positions, pause length) until it does, and say what changed. Do not weaken an assertion to make it pass.

- [ ] **Step 2:** In `MoveTo.java`, measure the stuck timeout in ticks the task actually ran, and request a path accuracy that guarantees the path end is within `reach`:

```java
    private int ticksRun;
    private int bestTick;
    private int lastPathTick;

    /** Manhattan accuracy whose worst case (all of it vertical, plus the +0.5 to the block center) stays within reach. */
    static int pathAccuracy(double reach) {
        return Math.max(0, (int) Math.floor(reach - 1.0));
    }

    @Override
    public void start(TaskContext ctx) {
        bestDistanceSqr = Double.MAX_VALUE;
        ticksRun = 0;
        bestTick = 0;
        lastPathTick = -REPATH_TICKS;
    }
```

In `tick`, increment `ticksRun` first and use it everywhere `now` / `ctx.gameTime()` was used for `lastProgressTick` and `lastPathTick`; call `navigation.createPath(target, pathAccuracy(reach))`. Update the class Javadoc to say the 200 ticks count only ticks the task ran.

- [ ] **Step 3:** In `PickUpItems.java`, replace the absolute `deadline` with a `ticksRun` counter compared against `TIMEOUT_TICKS`, and replace `villager.getNavigation().moveTo(nearest, MoveTo.SPEED)` with a path to the item's block at accuracy 0, re-pathing when the nearest item changes block:

```java
        BlockPos itemBlock = nearest.blockPosition();
        PathNavigation navigation = villager.getNavigation();
        boolean ours = itemBlock.equals(navigation.getTargetPos());
        if (navigation.isDone() || !ours || ticksRun - lastPathTick >= MoveTo.REPATH_TICKS) {
            Path path = navigation.createPath(itemBlock, 0);
            if (path != null) {
                navigation.moveTo(path, MoveTo.SPEED);
            }
            lastPathTick = ticksRun;
        }
```

- [ ] **Step 4:** In `LumberjackJob.java`, remove the `MoveTo(found.base(), STUMP_REACH)` step and the `STUMP_REACH` constant with its Javadoc: with Step 3 it no longer does anything `PickUpItems` does not.

- [ ] **Step 5:** Run `./gradlew --no-daemon -Dorg.gradle.workers.max=4 build`, then `scripts/gametest.sh` five times in a row. All five runs must report every required test passed, including `chopstreestoreslogsandreplants` and `pickupitemscollectsmatchingdrops`. Report the pass count of each run.

- [ ] **Step 6:** Commit: `fix: path accuracy within reach and pause-safe timers for movement tasks`
