# Diorama 1.7.10 isolated build

Current development build: **0.8.1-gtnh.4-dev** (shared decoded-section slice; no new in-game feature). Client command `/dioramaprobe` displays a fixed texture; no terrain map, HUD or persistent map data is connected. Do not treat it as a functional map release.

## Baseline

Java25 + Forge1614 + lwjgl3ify + GTNHLib + UniMixins, **without Angelica**. Angelica/OptiFine are later separate optional test environments, not mandatory dependencies. The user confirmed the .1 display probe visually on 2026-11-11; the .4 decoded-section build is game-untested. Shaders remain off by default and shader-pack tests are deferred. The assistant does not launch or deploy any instance.

Build inputs are pinned to [ExampleMod template](https://github.com/GTNewHorizons/ExampleMod1.7.10/tree/bd8e4160b677466de3cfff2c4011a3806fa74942), GTNH convention2.0.33/settings0.2.2, Gradle9.7.1, compiler/daemon25, Forge10.13.4.1614/stable12. GTNHLib compile dependency is0.11.48; observed user runtime is0.11.51. Real pack compatibility and Java8 product support remain unestablished. There is no Diorama Mixin requirement yet.

[Source policy](source-policy.json) directly selects canonical [Mipmaps](../src/main/java/dev/diorama/bake/Mipmaps.java), Facing/Projection/MapStyle, TileKey/TileImage and the primitive Looks/FaceColor/ShapeFaces and DecodedSections cores plus small local platform classes; [geometry probes](src/probe/java/dev/diorama/gtnh/probe/GeometryProbe.java) exercise those shared contracts. No copied algorithm tree, generated-modern cache, or integration into the modern Stonecutter project graph.

The latest canonical implementation remains the source and architecture reference; older version branches are only API comparisons. No Vulkan adapter or speculative backend framework is being added. Current progress: shared geometry, the modern snapshot fog-RGB boundary, primitive appearance data and decoded-section caches/IDs/masks are extracted. Modern BlockState rules remain in StateLooks; modern palette/copycat numbering remains in the VoxelReader adapter. The 1.7.10 input providers, snapshot/tint boundaries and complete CPU TileBaker closure remain pending. The fog boundary is Nether terrain haze, not the minimap unknown-area decorative fog.

## Repository hygiene

Keep build inputs, tests and reusable design instructions tracked. Machine-only notes, instance inventories, downloaded references and run evidence belong under the ignored `/.local/` directory; existing per-session GTNH diaries are also ignored. Generated source/bytecode, JAR outputs and audit reports stay under ignored build directories. Check staged files and maintain ignore rules on each milestone; wrapper JARs remain tracked.

## Commands (repository root)

```sh
# Build/package + selected offline/native/pixel checks; never launches Minecraft:
./gtnh/gradlew -p gtnh assemble verifyBaseline
# Full strict compatibility checks; known failures remain RED:
./gtnh/gradlew -p gtnh build --continue
# Same transformed bytes, actual GTNHLib runtime API version on Java25:
./gtnh/gradlew -p gtnh probeRuntime135Java25
```

`verifyBaseline` audits source origins, legacy metadata/version, reobfuscated class set/Java8 base bytecode and absence of probe/game/modern-loader code; runs native25 fixtures plus display-pixel parity and four native/downgraded/runtime-API lanes for geometry, appearance and decoded sections, with local style checks. **It does not replace strict compatibility, GL or game testing.** Probe classes/artifacts are separate from the mod jar.

The full pipeline is compile -> predowngrade jar -> downgraded dev jar -> reobfuscated jar. Deploy only the unsuffixed production artifact; not `dev`, `predowngrade`, `probe-*` or sources. Main configuration permits native21/25 MR entries where needed; the .4 build emits23 Java8 base classes and12 Java21 MR classes (records plus classes retained for their nest/API relationships), both explicitly audited. Game loader MR scanner behavior remains untested.

Offline compatibility2.0.1 API is not the same as runtime1.3.5 requested by GTNHLib. Never put both APIs on one probe classpath. `probeRuntime135Java25` can take `-PruntimeProbeApi=<installed jar>`; its API bytes must match the reviewed runtime artifact. Supporting harness dependencies are recorded separately, not added to the mod jar.

## Appearance core checks

`verifyAppearance` runs the canonical [appearance core](../src/main/java/dev/diorama/bake/Looks.java) with a synthetic provider and no Minecraft classpath. The [probe](src/probe/java/dev/diorama/bake/AppearanceProbe.java) covers all payload fields, same-look comparisons, ordered shape dedup/growth, face colours/geometry, water links, copycat composition/capacity and bounded concurrent first-use publication (12 rounds, six readers). It does not test modern block rules/models, registry reload, GL or actual legacy world input, and is not a JMM proof. The existing copycat capacity fallback during an in-flight insertion is preserved and tested separately from spare-capacity publication.

## Decoded-section checks

`verifySections` executes the canonical [DecodedSections](../src/main/java/dev/diorama/bake/DecodedSections.java) with numeric descriptors and a bulk-index fixture, without Minecraft on its classpath. The [probe](src/probe/java/dev/diorama/bake/SectionProbe.java) covers uniform/packed/dense/lazy paths, four rotated and arbitrary column maps, nonzero air, solid id zero, mask identity, partial shapes, real composed-look IDs, sparse highest-bit/negative-height boundaries and once-only caches. Masks-first must not materialize ID arrays; IDs-first reuses them.

The modern BitStorage adapter is invoked once per bulk decode, not per voxel/ray. These checks do not test its actual bit layout/reflection, real block-state numbering/rotation, UNKNOWN/missing-chunk cache pinning, world capture, casters/light or terrain pixels; full VoxelReader/TileBaker are not yet selected for the legacy build.

## Known compatibility failures

Target8 with JVM Downgrader2.0.1 differs from native Java25: nested record text includes the outer class name; single-element `List.of(null)` accepts null instead of throwing NPE. Exact record text is strict by default; `-PstrictRecordText=false` is diagnostic only and does not fix List semantics. Existing strict gates stay red, and no shared source workaround is applied. The [compatibility probe](src/probe/java/dev/diorama/gtnh/probe/CompatibilityProbe.java) retains the failing assertions.

Game handoff: [display probe steps](../docs/gtnh-display-smoke.md). Architecture and port boundaries: [technical design](../docs/gtnh-port-design.md). Optional rendering integration: [Angelica research](../docs/gtnh-angelica-research.md). Pixel tests do not establish GL compatibility, renderer support or full map functionality.
