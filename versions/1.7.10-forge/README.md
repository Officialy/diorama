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
- New terrain is progressively captured from received chunks within the configured render distance (up to 32 chunks). Nearby chunks and visible tiles take priority; background baking also fills the surrounding footprint. Panning never requests distant server chunks. Use `-Ddiorama.captureRadius=8` to override the capture radius if memory or tick cost is excessive. Live chunk capture stays on the client thread with a soft 3ms budget and at most eight attempts per tick; detached validation and raster baking run on a worker. Some block appearances are incomplete. The newer versions' HUD zoom, radar, drawing and GPU baking are not included.

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

### Progressive render-distance baking

Completed tile bakes can be displayed provisionally when newer chunks arrive. Dirty tiles are
refined after first renders, and only stable frames are written to persistent storage. Detached
tile jobs run concurrently (default 1–3 workers depending on logical CPUs; cap 4). Set
`-Ddiorama.bakeWorkers=3` to override. Capture remains owner-thread-only; defaults to a soft
4 ms budget, up to 12 chunk attempts per tick. Optional `-Ddiorama.captureBudgetMs=6` and
`-Ddiorama.capturesPerTick=16` increase throughput at the cost of frame time.
`-Ddiorama.backgroundCapture=true` keeps capture enabled with the minimap disabled.

### Map throughput

The client now defaults to the canonical single-ray Fast quality mode (one camera sample per pixel
instead of four). Use `-Ddiorama.fastBake=false` to restore full antialiasing. The capture path caches
state-to-appearance lookups within each synchronous chunk capture, preserving snow context and epoch
checks; visible tiles receive the first available bake slot. This does not accelerate server chunk
transmission or guarantee a fourfold end-to-end speedup.

### Progressive map previews

Enabled by default: each new tile (and changed chunk coverage) first receives a coarse 128x128
single-ray preview expanded into its normal 512x512 raster. Previously refined pixels are retained.
Detached background workers then run a full-resolution, four-sample refinement with canonical
geometry and lighting. The coarse image is never written to the persistent detail cache or used
as confirmed history by the refinement. Set `-Ddiorama.progressiveBake=false` to restore the old
single-pass mode; `-Ddiorama.fastBake=true` controls that mode. `/dioramaterrain status` reports
coarse preview completions. The coarse pass has 16,384 primary rays versus 1,048,576 in the full
pass, but snapshot validation, shading and other costs mean this is not a measured speed ratio.

When multiple bake workers are available, one slot is reserved for coarse coverage so expensive
refinements cannot occupy every worker while exploring. Zoomed-out overviews discard evicted
provisional children rather than retaining them as confirmed history.

### Retained map tiles

Visible tile access, not scheduler lookups, controls eviction. Confirmed surface tiles are retained
in a secondary session cache even without multiplayer disk persistence. Set `-Ddiorama.residentTiles=192`,
`-Ddiorama.historyTiles=192` and `-Ddiorama.uploadsPerFrame=3` to tune the defaults. Each retained raster
uses approximately 1 MiB; resident texture and staging costs are additional. History clears on session
reset. Texture updates reuse existing allocations when possible and uploads have a soft 3ms frame budget.

### Waypoint navigation

Death waypoints use the local player's alive-to-dead observation and the existing waypoint book
and persistence rules. An optional post-health-packet hook also observes brief death transitions before the next tick. Servers that do not transmit a death health state may still bypass it. Death markers are red;
enabled waypoints have minimap edge arrows and in-world camera-facing distance labels.
Disable these features with `-Ddiorama.deathWaypoints=false` or `-Ddiorama.worldWaypoints=false`.

### Legacy fog and zoom filtering

Unknown areas use world-anchored procedural cloud colouring behind the transparent map raster.
This is not upstream's volumetric mask shader. Disable with `-Ddiorama.mapFog=false`.
At native resolution and above, magnification is nearest-neighbour to preserve crisp lines;
`-Ddiorama.smoothZoom=true` selects linear magnification instead. The canonical full tile remains
512x512 (four pixels per map unit), so filtering does not invent detail above the source resolution.

Snapshot selection now inverts the tile's projected lighting/ray bounds and visits only candidate
chunk slots, caching detached input arrays until a capture changes. Minimap off-screen bearings use
horizontal displacement rather than waypoint height, with a nearest-waypoint distance label.

Repeated chunk refreshes no longer request another coarse preview unless coverage is new, and
unrelated cave tiles no longer invalidate on every block edit. Texture subuploads reuse a fixed
native staging buffer. For a sharper initial preview, `-Ddiorama.coarseStride=2` traces a 256x256
preview (four times more primary rays than the default 128x128). `8` selects a 64x64 preview.

The progressive scheduler now permits coalesced coarse gap updates while a tile is still provisional,
with a 250 ms per-tile throttle and periodic refinement turns. A refinement invalidated by newly
arriving data retains displayed coarse coverage without writing provisional pixels to storage.

Map clouds, smooth zoom, world waypoints and death waypoints can be toggled from Minimap Settings.
JVM properties specify initial defaults before a saved preference exists.

### Navigation parity experiments

The fullscreen map can rotate in 90-degree screen-space steps without rebaking or changing disk keys;
this is not a new isometric camera facing. Enable local tracked-entity radar with `-Ddiorama.entityRadar=true`
(and player markers separately with `-Ddiorama.playerRadar=true`, where permitted by server rules).
Invisible entities and sneaking players are excluded. Waypoint icons can be chosen in the edit screen;
DWP1-compatible name prefixes store the selection. `-Ddiorama.waypointBeams=true` enables optional
nearby vertical beams. LOD1 overviews stream from disk on zoom-out and confirmed child writes also
queue a parent update, preserving previously known quadrants. These features require native client testing.

### Experimental GPU terrain preview

Enable `-Ddiorama.gpuBake=true` to use a GLSL heightfield raymarch on OpenGL 3.0-compatible
clients. It packs detached surface colour/height columns off-thread and submits at most one
pending render-tick job. GPU readback is synchronous and bounded to 128x128; errors or timeouts
fall back to CPU. The result is only a provisional surface preview, not the upstream full
geometry/lighting GPU backend. Caves and final four-sample refinements stay on CPU. Disabled by default.

### Dynamic close-zoom tiles

Above six GUI pixels per projected map unit, idle workers can produce a separate 1024x1024
single-sample canonical terrain raster for up to four visible tiles. It is not saved into the
512x512 cache. Stable base pixels stay visible until the optional higher-resolution tile arrives.
Disable with `-Ddiorama.highResolution=false`. It requires at least two available worker slots
and prioritizes the existing exploration pipeline. GPU previews do not substitute for this refinement.

### Experimental volumetric-style fog

`-Ddiorama.volumetricFog=true` selects a GLSL 1.20 eight-layer height-field cloud effect with
world-anchored animated noise and a per-tile alpha-mask edge pass. The shader is a legacy
approximation of upstream, not a byte-for-byte port of its global blurred coverage mask;
tile seams, blending and Angelica compatibility still need visual testing. Unsupported contexts
fall back to the procedural background. Disabled by default.

### Optional full-resolution GPU refinement

`-Ddiorama.gpuRefine=true` enables a separate 512x512 surface voxel raymarch, with optional
`-Ddiorama.gpuFourSamples=true`. This uploads 16-bit state ids, face colours, biome tints and
bounded box geometry to a GL 3.0 texture pipeline. The shader traverses voxels and intersects
shape boxes, with approximate directional shading and transparent material compositing. It does
not yet reproduce upstream's shadow, sky occlusion or water passes. GPU raster results are kept
separate from confirmed CPU history and never written to the authoritative map cache. Missing
coverage, unsupported contexts or failed GL work fall back to canonical CPU rendering. The
volume upload and synchronous readback can introduce frame-time spikes; benchmark before use.

Overview generation now happens on the disk worker after the confirmed child write, rather than
composing on the client tick and competing for a second queue slot. Added detached regression
coverage for GPU voxel/shape/tint packing and durable two-child overview updates.

Advanced minimap settings exposes radar, player radar, beams, volumetric-style fog and high-resolution
close zoom without restarting. The GPU terrain flags still require restart. Saved preferences override
JVM defaults. Radar is disabled by default; only use it where the server permits it.

The fork CI also runs the exact embedded GPU and fog shaders in a headless Mesa compatibility
context, checking shader compilation/linking, known and unknown terrain, four-sample voxel
coverage and the fully known fog mask. This is a shader-level test, not an Angelica runtime test.

On GL 3.2+, voxel refinement now queues a pixel-pack buffer and a GPU fence, polls without waiting
on later render ticks and copies only after completion. GL 3.0 uses synchronous readback. GPU
preview/refinement completions, fallbacks and disable status are reported by `/dioramaterrain status`.
The first large texture upload and driver execution are still on the render thread and need profiling.

The screen-space rotation control has been removed. Terrain texture sampling clamps to edge rather than wrapping to the opposite edge of a tile. Rendering settings are persisted and applied in-game; JVM flags seed defaults only. Existing confirmed terrain is retained when adjusting settings.

Fog now follows upstream's eight elevated layers, seeded tileable noise, shadow colours, and blurred known-share coverage. A single viewport-wide mask is composed from uploaded tiles (including HD), so banks can cross tile boundaries. The legacy implementation does not yet reproduce upstream's half-resolution compositing, remembered-map smoke or zoom-dependent mask hierarchy. GL fallback remains the simple cloud background.

### In-game render benchmark

Open Map > Minimap Settings > Advanced > Rendering / performance > Benchmark / Auto-config.
It pauses normal map scheduling, freezes up to four captured surface tile inputs, warms the renderer,
builds full-quality CPU references and tests CPU worker counts 1–4 with full quality and preview strides
2/4/8, then GPU preview and one/four-sample GPU refinement. Each candidate runs two passes; actual GPU
failure is rejected rather than timed as CPU fallback. Tile time, frame p95, coverage recall/precision and
RGB error are shown. The recommendation is quality-gated and frame-time penalized. Apply explicitly to
persist the suggested bake settings. Results never enter map storage. Capture budgets, radius and cache
sizes remain independently adjustable; this controlled benchmark does not measure server chunk delivery,
exploration capture throughput or every combined CPU/GPU scheduling profile. Escape cancels the test.

### Waypoint interoperability and navigation

Waypoint list supports name search, nearest/name/newest ordering (enabled first), and opening existing
points in the map or copying XYZ from their editor. `/dioramaterrain import-waypoints` creates/reads
`diorama-waypoint-import` inside the instance. Copy only the selected world's JourneyMap **5.x** JSON
or the selected world AND dimension's Xaero text file there, then run the command. JourneyMap numeric
dimensions are filtered; legacy Nether x/z are divided by eight to match JourneyMap 5's own display.
Xaero text has no dimension field, so selection of the correct dimension is the user's responsibility.
Source files remain unchanged; duplicate label/coordinate pairs are skipped and existing visibility is
preserved. Maximum 128 points per book remains. JourneyMap 6 NBT files are not yet supported.
