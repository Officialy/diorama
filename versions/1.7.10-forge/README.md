# Diorama for Minecraft 1.7.10 (Forge) — Preview

A client-side isometric minimap and fullscreen map. The GT New Horizons modpack itself is not required.

## Install

- **Minecraft:** 1.7.10 with Forge 10.13.4.1614.
- **Required mods:** GTNHLib 0.11.48 or later, UniMixins 0.3.1 or later, and the dependencies required by those versions.
- **Java:** Java 8 was smoke-tested. For Java 25, use a correctly configured lwjgl3ify setup (tested with lwjgl3ify 3.0.26).

Put the 1.7.10 Forge jar in the client's `mods` folder. The server does not need Diorama. These are tested setups, not a compatibility guarantee for every mod combination.

## What the preview supports

- CPU terrain baking, an isometric minimap and a fullscreen map with drag, scroll zoom and recentering.
- `M` opens the fullscreen map, `B` adds a waypoint and `U` lists waypoints. Rebind these under Minecraft's Controls; the minimap visibility key is unbound by default.
- Surface and cave views, with a cave slice that follows the player by default. In the fullscreen cave map, Shift+scroll adjusts the cut by four blocks; the Follow player height button restores automatic mode. Singleplayer surface tiles and waypoints are saved per dimension. Multiplayer surface tiles and waypoints can optionally persist with an explicit identity; cave tiles remain session-only.
- New terrain is drawn from nearby, loaded chunks as you explore; panning does not load distant chunks. Some block appearances are incomplete. The newer versions' HUD zoom, radar, drawing and GPU baking are not included.

## Modded dimensions and multiplayer persistence

All client dimensions with the audited 256-block storage height are accepted. Sky-visible dimensions
use the surface map, no-sky dimensions use cave mapping (the vanilla End retains surface mapping).
Modded dimension IDs have independent cache folders; other heights remain unsupported.

Multiplayer persistence is disabled by default. For a single, stable world on a server, add both JVM
arguments (replace the example values with the exact address in the Multiplayer menu and a unique
world label):

```text
-Ddiorama.remoteServer=example.org:25565
-Ddiorama.remoteWorld=main-world-2026
```

Only the exact configured endpoint can persist. The local namespace is a hash of endpoint and
world label; map tiles and waypoints are separated by dimension. Changing the label creates a new
namespace, so use a new label after a server reset or when changing sub-worlds. Do not enable
this on proxy networks that switch worlds under the same endpoint unless you can guarantee
that the label identifies the current world. The server provides no reliable sub-world ID.
Do not run two client instances against the same cache simultaneously. No server installation is
required. Cave tiles are still session-only.

## Experimental legacy geometry capture

The fallback appearance path uses atlas-frame colours, map colours and metadata-based slab/straight
stair outlines, thin panes, fences, walls, doors, ladders, rails, beds and other representative
legacy shapes. Connected shapes, tile entities and modded metadata conventions remain approximate. Unknown registry states remain unknown. The optional JVM flag
`-Ddiorama.experimentalGeometry=true` enables a passive RenderBlocks/Tessellator observer, sampling
custom render types and some connected vanilla blocks on the client appearance owner's thread. A second optional flag,
`-Ddiorama.experimentalGeometryWorkers=true`, permits detached samples from renderer workers only
when the source is the active WorldClient, a matching vanilla ChunkCache, or a recognized Angelica
WorldSlice. Unknown world-access wrappers are skipped. World/resource epochs fence publication.
With `-Ddiorama.terrainMetrics=true`, the log reports captured states and worker-captured states.
It does not invoke renderers, draw anything or modify vertex buffers. A bounded sample of up to
256 textured vertices per state is reduced to up to sixteen local cuboids or an enclosing box.
UV rectangles are resolved against the stitched atlas; available CPU frame data is sampled on a
bounded 8x8 grid, otherwise full-sprite colour is used. Projected quad areas weight top/side colour.
No GPU readback is performed; renderer vertex lighting/tints are not applied. Geometry is a
representative state approximation, not a per-position neighbour or tile-entity model. New observations request a coalesced recapture of nearby tiles at most once per second. Angelica versions or optimized renderer paths may bypass these hooks; no compatibility or visual quality
claim is made without client testing. The option defaults off.

## Known compatibility issue

Strict JVM downgrade probes report differences in nested record `toString()` and `List.of(null)` behavior. No in-game impact has been observed in the Java 8 smoke test. The strict checks remain enabled.

## Build and verify

Building requires JDK 25 even if you play on Java 8. From the repository root (use `gradlew.bat` on Windows):

```sh
./versions/1.7.10-forge/gradlew -p versions/1.7.10-forge assemble verifyBaseline
./versions/1.7.10-forge/gradlew -p versions/1.7.10-forge build --continue
```

The reobfuscated installable jar is in `versions/1.7.10-forge/build/libs/`; do not distribute the dev, probe, sources or pre-downgrade jars. `verifyBaseline` checks the artifact and offline baseline; the full build adds strict compatibility checks. Neither command launches Minecraft or replaces client acceptance testing.

Build inputs are pinned in the [build script](build.gradle.kts), [Gradle properties](gradle.properties) and [dependency file](dependencies.gradle). The [source policy](source-policy.json) selects canonical sources and platform adapters. The optional EndlessIDs adapter compiles against version 1.7.4 without bundling it. See the [port design](../docs/gtnh-port-design.md) for architecture and scope.

### Client diagnostics

`/dioramaterrain status` reports the current dimension, world and chunk provider classes,
appearance-table usage and observed geometry count. `/dioramaterrain missing` adds up to ten
unresolved block states ranked by failed preparation attempts (not cell counts). Tracking is
bounded to 512 distinct unresolved states per capture session. The appearance table now holds
32,768 entries rather than 4,096, reducing exhaustion in large modpacks.

### Client acceptance checklist

Use the same area and camera angle on the GTNH server before and after installing the build.
Test the default fallback first, then enable experimental geometry and finally worker capture.
Use F3+A to request chunk remeshing if the initial meshes predate the map session. With terrain
metrics enabled, compare unknown-look cells and observed-state counters, and check frame time,
resource reload, world switch and reconnect. This is not a substitute for testing actual modded
blocks and Angelica versions in game.
