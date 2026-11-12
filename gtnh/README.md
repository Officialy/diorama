# Diorama 1.7.10 isolated build

Current development build: **0.8.1-gtnh.7-dev** (canonical CPU baker with prepared numeric inputs; no new in-game feature). Client command `/dioramaprobe` displays a fixed texture; no terrain map, HUD or persistent map data is connected. Do not treat it as a functional map release.

## Baseline

Java25 + Forge1614 + lwjgl3ify + GTNHLib + UniMixins, **without Angelica**. Angelica/OptiFine are later separate optional test environments, not mandatory dependencies. The user confirmed the .1 display probe visually on 2026-11-11; the .7 CPU bake build is game-untested. Shaders remain off by default and shader-pack tests are deferred. The assistant does not launch or deploy any instance.

Build inputs are pinned to [ExampleMod template](https://github.com/GTNewHorizons/ExampleMod1.7.10/tree/bd8e4160b677466de3cfff2c4011a3806fa74942), GTNH convention2.0.33/settings0.2.2, Gradle9.7.1, compiler/daemon25, Forge10.13.4.1614/stable12. GTNHLib compile dependency is0.11.48; observed user runtime is0.11.51. Real pack compatibility and Java8 product support remain unestablished. There is no Diorama Mixin requirement yet.

[Source policy](source-policy.json) directly selects canonical [Mipmaps](../src/main/java/dev/diorama/bake/Mipmaps.java), Facing/Projection/MapStyle, TileKey/TileImage and the primitive Looks/FaceColor/ShapeFaces, DecodedSections, TintBlend and VoxelReaderCore cores and the canonical CPU TileBaker closure plus small local platform classes; [geometry probes](src/probe/java/dev/diorama/gtnh/probe/GeometryProbe.java) exercise those shared contracts. No copied algorithm tree, generated-modern cache, or integration into the modern Stonecutter project graph.

The latest canonical implementation remains the source and architecture reference; older version branches are only API comparisons. No Vulkan adapter or speculative backend framework is being added. Current progress: shared geometry, the modern snapshot fog-RGB boundary, primitive appearance data, decoded-section caches/IDs/masks and height-keyed tint averaging/view remapping are extracted. The numeric VoxelReaderCore now owns job-local chunk pinning, last-section caches, solidity/caster masks, solid height and light lookup defaults. Modern BlockState rules remain in StateLooks; palette/copycat numbering and snapshot/light adapters remain in VoxelReader; biome zoom/resolvers stay in BiomeTints. The complete original CPU TileBaker now compiles with same-FQN prepared numeric snapshot/reader adapters; SnapshotHeights, saved-ray queries and sun math are shared. Real 1.7.10 world capture, model/colour preparation, snapshot rotation and legacy spawn rules remain pending. The fog boundary is Nether terrain haze, not the minimap unknown-area decorative fog.

## Repository hygiene

Keep build inputs, tests and reusable design instructions tracked. Machine-only notes, instance inventories, downloaded references and run evidence belong under the ignored `/.local/` directory; existing per-session GTNH diaries are also ignored. Generated source/bytecode, JAR outputs and audit reports stay under ignored build directories. Reviewed shared hashes normalize CRLF to LF; provenance still records physical input hashes, so Windows checkout newlines cannot silently alter algorithm review or break a fresh LF checkout. Check staged files and maintain ignore rules on each milestone; wrapper JARs remain tracked. Development is currently local-only: no push or release preparation until the basic functional loop is complete and the user requests it.

## Commands (repository root)

```sh
# Build/package + selected offline/native/pixel checks; never launches Minecraft:
./gtnh/gradlew -p gtnh assemble verifyBaseline
# Full strict compatibility checks; known failures remain RED:
./gtnh/gradlew -p gtnh build --continue
# Same transformed bytes, actual GTNHLib runtime API version on Java25:
./gtnh/gradlew -p gtnh probeRuntime135Java25
```

`verifyBaseline` audits source origins, legacy metadata/version, reobfuscated class set/Java8 base bytecode and absence of probe/game/modern-loader code; runs native25 fixtures plus display-pixel parity and four native/downgraded/runtime-API lanes for geometry, appearance, decoded sections, tint blending, numeric voxel reading, full/patch terrain baking and bake utilities, with local style checks. **It does not replace strict compatibility, GL or game testing.** Probe classes/artifacts are separate from the mod jar.

The full pipeline is compile -> predowngrade jar -> downgraded dev jar -> reobfuscated jar. Deploy only the unsuffixed production artifact; not `dev`, `predowngrade`, `probe-*` or sources. Main configuration permits native21/25 MR entries where needed; the .7 build emits45 Java8 base classes and22 Java21 MR classes (records plus classes retained for their nest/API relationships), both explicitly audited. Game loader MR scanner behavior remains untested.

Offline compatibility2.0.1 API is not the same as runtime1.3.5 requested by GTNHLib. Never put both APIs on one probe classpath. `probeRuntime135Java25` can take `-PruntimeProbeApi=<installed jar>`; its API bytes must match the reviewed runtime artifact. Supporting harness dependencies are recorded separately, not added to the mod jar.

## Appearance core checks

`verifyAppearance` runs the canonical [appearance core](../src/main/java/dev/diorama/bake/Looks.java) with a synthetic provider and no Minecraft classpath. The [probe](src/probe/java/dev/diorama/bake/AppearanceProbe.java) covers all payload fields, same-look comparisons, ordered shape dedup/growth, face colours/geometry, water links, copycat composition/capacity and bounded concurrent first-use publication (12 rounds, six readers). It does not test modern block rules/models, registry reload, GL or actual legacy world input, and is not a JMM proof. The existing copycat capacity fallback during an in-flight insertion is preserved and tested separately from spare-capacity publication.

## Decoded-section checks

`verifySections` executes the canonical [DecodedSections](../src/main/java/dev/diorama/bake/DecodedSections.java) with numeric descriptors and a bulk-index fixture, without Minecraft on its classpath. The [probe](src/probe/java/dev/diorama/bake/SectionProbe.java) covers uniform/packed/dense/lazy paths, four rotated and arbitrary column maps, nonzero air, solid id zero, mask identity, partial shapes, real composed-look IDs, sparse highest-bit/negative-height boundaries and once-only caches. Masks-first must not materialize ID arrays; IDs-first reuses them.

The modern BitStorage adapter is invoked once per bulk decode, not per voxel/ray. These section checks do not test its actual bit layout/reflection, real block-state numbering/rotation, world capture or terrain pixels. Numeric reader checks below cover UNKNOWN pinning and casters/light separately; the modern VoxelReader adapter is not selected for the legacy build. The original TileBaker uses the prepared legacy adapter in separate terrain checks below.

## Tint blend checks

`verifyTints` runs the canonical [TintBlend](../src/main/java/dev/diorama/bake/TintBlend.java) with prepared numeric samples and a separate uncached scalar/long-sum oracle. The [probe](src/probe/java/dev/diorama/bake/TintBlendProbe.java) covers 126 cases: four RGB channels, radii0/1/3/7, negative chunk origins, terraced heights, missing neighbours, valid black, all-unknown fallback, alpha removal/integer division, flat-height cache counts and rotated/arbitrary column maps. The original Integer.MIN_VALUE cache-sentinel corner case is preserved explicitly, not silently fixed.

The modern adapter keeps Minecraft biome zoom, colour resolvers (including custom water), fallback quart lookup and lazy snapshot tint publication; only averaging/cache/remapping moved. These compile-checked bindings are not executed by numeric fixtures: actual biome palettes/zoom, resolver hooks, concurrent snapshot publication, live world capture and actual game terrain remain untested. Prepared-input terrain bakes are checked separately below.

## Numeric voxel reader checks

`verifyReader` runs the canonical [VoxelReaderCore](../src/main/java/dev/diorama/bake/VoxelReaderCore.java) through a prepared numeric provider in the [probe](src/probe/java/dev/diorama/bake/VoxelReaderProbe.java). It checks present/missing chunk pinning, direct-table collisions, independent state/solid caches, nonzero air and solid ID zero, half-open world bounds, mask identity, solid heights, scalar caster expectations and captured-light coordinates/defaults. Unread caster neighbours must not trigger source loads; cached caster results retain the original order-dependent conservative behavior. Pocket/lid/ceiling queries share the original algorithms.

The primitive map dependency is fastutil8.5.18, already requested by pinned GTNHLib; offline lanes pin it separately without adding Minecraft/Forge to the probe classpath or embedding the library. Transformed lanes use only transformed probe/core bytes plus dependency jars, not native probe classes. These checks do not execute modern palette/copycat numbering, real snapshot capture/light storage, legacy providers, GL or terrain baking, and do not establish performance.

## Original CPU terrain checks

`verifyTerrain` executes the canonical [TileBaker](../src/main/java/dev/diorama/bake/TileBaker.java), not a reduced tracer, through [prepared numeric snapshots](src/main/java/dev/diorama/data/ChunkSnapshot.java) and a thin [reader adapter](src/main/java/dev/diorama/bake/VoxelReader.java). The [terrain probe](src/probe/java/dev/diorama/bake/TerrainProbe.java) checks a spatially varying full512-square surface and three aligned32-square patches at every mip, coverage and block-light level; independent alpha-weighted mip sums; water/glass/half-slabs and grass tint effects; known air versus UNKNOWN; saved pixel/light preservation; captured cave light, unseen fog and lifted roof scenes. Snapshot copy ownership, nonzero air and look-domain rejection, complete vertical-span rejection and missing sky-light-layer defaults are tested. Material/lighting comparisons are bounded expectations, not an independent exact rendering oracle.

Inputs are explicitly prepared: null/one-ID/4096-ID sections, complete world section span, private captured light, column tops, four blended RGB arrays and one chunk fog RGB. Reader inputs carry stable air and Looks identity; unsupported generic sources or mismatched domains/spans are rejected. Tints are read-only by contract. Input producers must prepare the final orientation; rotated snapshots are not yet implemented. Source roots are filtered separately so canonical and platform classes with the same FQN never compile together.

`verifyBakeUtilities` checks shared [saved-ray queries](../src/main/java/dev/diorama/bake/TileSpotQueries.java), sun math and the legacy slime seed against scalar row enumeration, known-angle expectations and an independent48-bit LCG with signed32 overflow cases. Modern RNG still delegates to Minecraft. These tests do **not** establish live capture, legacy spawn eligibility (the modern zero-block-light rule is not a1.7.10 rule), models/registries, rotated legacy inputs, GL, persistent maps, MR scanning or performance. No new game command, terrain HUD or world connection is enabled by this build.

## Known compatibility failures

Target8 with JVM Downgrader2.0.1 differs from native Java25: nested record text includes the outer class name; single-element `List.of(null)` accepts null instead of throwing NPE. Exact record text is strict by default; `-PstrictRecordText=false` is diagnostic only and does not fix List semantics. Existing strict gates stay red, and no shared source workaround is applied. The [compatibility probe](src/probe/java/dev/diorama/gtnh/probe/CompatibilityProbe.java) retains the failing assertions.

Game handoff: [display probe steps](../docs/gtnh-display-smoke.md). Architecture and port boundaries: [technical design](../docs/gtnh-port-design.md). Optional rendering integration: [Angelica research](../docs/gtnh-angelica-research.md). Pixel tests do not establish GL compatibility, renderer support or full map functionality.
