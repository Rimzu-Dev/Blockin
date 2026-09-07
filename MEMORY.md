# Blockin — Project Memory

Full rewrite of an original early block-building game engine into a new game,
**Blockin**, living in `D:\Games\Blockin Source`. All code is in package
`com.insanestudios.blockin` (no third-party game code or names involved; the
mod layer was authored by the user and is integrated at the root level).

---

## Layout

- `com/insanestudios/blockin/` — engine root and game UI (all one flat package:
  the former `loki` subpackage was flattened away)
  - `Blockin.java` — main game class (window, loop, world build, mouse look,
    break/place, render passes, crosshair)
  - `SaveManager.java` — multi-save slots in the user's Documents folder
    (`Documents/Blockin/saves`); legacy-save migration, seed meta, next-name
  - `PlayerController.java` — player physics input + camera pose
  - `GameClock.java` — fixed-timestep clock (`advanceTime()` → `ticks`, `a`)
  - `HitResult.java` — record `(int x, int y, int z, int o, int f)`
  - `TextureLoader.java` — generic PNG load/upload helpers
  - `MainMenu.java`, `GameHUD.java`, `Update.java` (hub), `SoundSettingsMenu.java`,
    `WindowPatch.java` — game UI / menu layer
  - `Physics/` — `Box` (collision volume), `PlayerPhysics` (with `Slide`), `Mob_Pig`
  - `World/` — `Level`, `LevelListener`, `Chunk`, `ChunkBuilder`, `TerrainGen`,
    `WaterSystem`, `Tesselator`, `Frustum`, `LevelRenderer`
  - `Blocks/` — `Block`, `WaterBlock`, `BlockAtlas`, `BlockLoader`,
    `BlockCodeCompiler`, `BlockSpec` (+ core block definitions as XML folders
    `Blocks/<Name>/<Name>.xml` with their pngs: `Stone/`, `Grass/`, `Water/`,
    `Dirt/`, `Sand/`, `Log/` (`Log Side.png`/`Log Top.png`, names with spaces),
    `Leafs/Leafs.png`; legacy root `dirt.png`, `stone.png`, `water.png` still
    loaded)
  - `Sound/` — `SoundEngine` (procedural)
  - `UI/Blocks/{Grass,Stone}.png` — hotbar icons
- `launcher/` — WPF `BlockinLauncher` source, builds to
  `D:\Games\Blockin\Launcher\BlockinLauncher.exe`
- `lib/`, `natives/`, `Mods/` — LWJGL 2 jar files + natives, mod staging
- `build.bat` / `build.ps1` — compile + stage everything into the game dir

## Runtime (important)

The game does **not** run from the source tree. It runs from
`D:\Games\Blockin`:

```
java -cp "D:\Games\Blockin;lwjgl.jar;lwjgl_util.jar" \
     -Djava.library.path="D:\Games\Blockin\natives" \
     --enable-native-access=ALL-UNNAMED \
     --add-opens=java.base/java.lang=ALL-UNNAMED \
     com.insanestudios.blockin.Blockin
```

Working directory = `D:\Games\Blockin`. **Saves no longer live here.**
Worlds are stored per-slot under the user's Documents folder
(`<Documents>/Blockin/saves/<World Name>/level.dat` + `meta.properties`
with name/seed/lastPlayed); on Windows the real Documents is resolved via the
registry "Personal" shell folder (survives OneDrive redirection) with a
`user.home\Documents` fallback. On first run a legacy `level.dat` in the
working directory is migrated into a "World 1" slot automatically, so old saves
survive. `Mods` is read from the working dir (walked up to find a `Mods` folder).

Save format (new): `0x5A` magic byte + `0x4C564C31` ("LVL1") marker +
`long seed` + `int depth` + `int count` + per-chunk `(int cx, int cz, bytes)`
(16x16xdepth each; skylight recomputed on load). Legacy detection on load:
first byte == `0x5A` → versioned, else the old header-less flat
`width*depth*height` array is migrated into the corner chunks (footprint dims
must match the file's). `buildWorld` uses `SaveManager.seedOf(name)` (not a
fresh random seed) when loading so regenerated distant chunks match the saved
seed (versioned saves also carry it in the header and Level adopts it).

Optional save tail (player position): after the chunks a boolean flag + 5
floats (feet x/y/z, yaw, pitch) is written only when `setSavedPlayer` was
called (`Blockin` does it before every save: ENTER, window-close/cleanup).
Loading reads it with plain `read()` (returns -1 at clean EOF), so old LVL1
saves and saves with no position load fine and `hasSavedPlayer()` stays false;
a truncated tail drops only the position, never the world. `buildWorld`
applies it via `PlayerController.restoreState` (falls back to `resetPos` if
the saved spot is now solid); `xo/yo/zo` seeded so the camera doesn't swoop.
`RestoreState`'s x/z are body-center, y is feet; player box is rebuilt exactly.

### Launch assets in `D:\Games\Blockin`
- `com/insanestudios/blockin/**/*.class` — compiled game (deployed by
  build scripts / launcher Build button)
- `com/insanestudios/blockin/**/*.png` — block/UI textures (copied on build;
  resource paths start `/com/insanestudios/blockin/...`)
- `lwjgl.jar`, `lwjgl_util.jar`, `natives/*.dll` (lwjgl64, OpenAL64, …)
- `Mods/` — `Blocks/Planks/` (folder mod, id 3) + `Added Blocks.zip` (zip mod,
  Plank id 4)
- `Launcher/BlockinLauncher.exe` — the launcher

## Key decisions

- LWJGL 2 kept (jars already owned; no downloading). Named keys, mouse look.
- Java 26 (javac 26.0.2.1) compiles everything; launcher targets
  `net8.0-windows`, WPF, exe output `BlockinLauncher`.
- **Placement face**: `doPlace()` must switch on `hit.f()` (the selected face,
  indexes 0-5 from `LevelRenderer`), NOT `hit.o()`. `o` is always 0 (it is the
  unused "tag" stack value), so switching on it made every placement go to the
  block BELOW the target (case 0 → `y--`) and silently abort when that cell
  was already solid. The switch maps face → neighbour: 0→y-1, 1→y+1, 2→z-1,
  3→z+1, 4→x-1, else x+1.
- **`Mob_Pig` rests ON the surface, never buried**: `surfaceY` uses `y+1.55F`
  minus the cube half-height so the 0.9-tall pig's box bottom lands 0.55 above
  the grass top. (Its old `y+1.1F` buried the ±0.45 box.) `surfaceY` also
  clamps the sample column 1 in from the world edges so pigs never start on
  the cliff.
- `GameHUD` (rewritten): E or TAB toggles the inventory (mouse grab released
  while open, re-grabbed on close), ESC closes it; mouse wheel cycles the
  hotbar. It implements `Update.HudModule` and exposes static
  `isInventoryOpen()` / `getHeldBlockId()` for `Blockin` gating + placement.
  The inventory is a 9-column grid of every `BlockLoader.getAll()` block (so
  XML mods appear automatically); icons = `Block.getIconTexture()` cached in
  `init()`; hotbar stays visible below the grid; click (in grid or hotbar)
  selects the held block. Chrome reuses the `MainMenu` static text helpers and
  `GameHUD.UI.modernPanel` (translucent panel + accent border).
- **`GameHUD.java` had been clobbered** into an unrelated stub declaring
  `package com.example.hud` with only a `UI.modernPanel` helper — it compiled
  fine but silently into `com/example/hud`, so `Blockin`/`Update` couldn't see
  it (5 `cannot find symbol: GameHUD` errors). Restored to
  `package com.insanestudios.blockin` + the full HUD module.
- **Block hotbar icons draw a clean cube outline** with explicit segment lines
  (top diamond, two vertical side edges, bottom edge) instead of a single dark
  4-point polygon — the old polygon crossed its own interior and drew stray
  black lines through the icon.
- Mouse/input must be polled **every** tick (`Mouse.poll()` +
  `Keyboard.poll()` at the top of `Blockin.tick()`): the menu is the only
  other poller, so without this all input (look, movement, keys, break/place,
  wheel) freezes once PLAY is chosen.
- **Picking needs `glSelectBuffer` BEFORE `glRenderMode(GL_SELECT)`** in
  `LevelRenderer.pick()`. If the order is wrong the driver keeps a tiny internal
  buffer, everything overflows (`glRenderMode` returns -1) and nothing lands in
  our buffer → pick always returns null. The pass also clears depth inside
  select mode (only the front-most faces per ray record) and bounds
  `processHits` by `SELECT_BUFFER_SIZE/3` so an overflow still yields the
  closest record instead of a fabricated (0,0,0) hit. Name-stack order is
  bottom-first (x,y,z,tag,face) — the GL record carries exactly that.
- **Controls: left click = place, right click = break** (`Blockin.handleMouse`);
  a swap made it the reverse and the user asked for standard MC-style bindings.
- **`MainMenu.choose()` must NOT set `active=false`.** The menu stays active
  until the game consumes the choice (`startGame()` sets both `active=false`
  and grabs the mouse). If `choose()` deactivates early, `Blockin.tick()` never
  sees the PLAY choice → `buildWorld()` never runs → no world, flat sky, no
  HUD (player/level never bound), mouse never grabbed. All three symptoms share
  that one cause.
- Collision volume class is named `Box` (deliberately no
  axis-aligned-bounding-box naming).
- **`GL_CULL_FACE` is never enabled.** 2D overlays (menu/HUD) render through a
  mirrored orthographic projection whose winding would be culled away entirely;
  disabling culling also keeps terrain visible regardless of winding.
- **Mouse capture is owned by menu/game transitions** (`MainMenu` /
  `Blockin.buildWorld`), *never* grabbed in `GameHUD.init()`.
- Old `Tile.java` removed — picking/highlighting now uses
  `Block.renderFace(t, x, y, z, face)` via `BlockLoader.get(type)`.
- Chunk display-list rebuild throttled to `rebuiltThisFrame >= 2` per frame;
  atlas texture id resolved **before** `glNewList`.
- `BlockAtlas` cells: GRASS_TOP=0, GRASS_SIDE=1, DIRT_BOTTOM=2, STONE=3,
  WATER=4, SAND=5, GRAVEL=6, LOG_SIDE=7, LOG_TOP=8, LEAVES=9; cell size 256;
  POT only. `seedCoreCells` is idempotent and records each classpath resource
  path (`reserveResource`); the **core built-in blocks are now driven by their
  XML definitions** in `Blocks/<Name>/<Name>.xml`. `reserveResource`/`findEquivalent`
  pixel-deduplicates textures, so a folder-local copy of a core png reuses the
  already-seeded cell (verified: Grass top→0, side→1, bottom→2; Water→4; Sand→5;
  Log side→7, top→8; Leaves→9). `water.png`, `Sand/sand.png`,
  `Log/{Log Side,Log Top}.png` and `Leafs/Leafs.png` are loaded via
  `reserveResourceOrGenerate` (real png wins, procedural generator is the
  fallback); Gravel is now the only fully procedural core cell.
- **XML block mod system** (folder, zip, top-level `Blocks/`, and the core
  blocks themselves all use the same pipeline): a block XML accepts `<id>`
  (auto-assigned at clash/omission), required `<name>`, `<description>`,
  `<type>` (`solid` | `transparent` | `liquid`), textures — `<texture>` (one
  face shared) with optional `<top>/<side>/<bottom>` overrides (each relative
  to the XML folder; defaults `top.png`/`side.png`/`bottom.png`), `<icon>`,
  `<soundStep>/<soundPlace>/<soundBreak>`, and an optional `<java>` file.
  BlockLoader scans (in order): classpath core `Blocks/<Name>/<Name>.xml`
  (id-pinned, hardcoded entries remain the fallback), top-level `Blocks/*`
  next to `Mods/`, `Mods/Blocks/*` (prefers the folder-named xml, then
  `block.xml`, then any xml; legacy `block.properties` still loads), then
  `Mods/*.zip` (info.xml `<block file=.../>` + `block.xml` entries). `liquid`
  builds a `WaterBlock`, `transparent`/`solid` a plain `Block`
  (`BlockSpec.build()`); Loader fills in description/type only if a custom
  factory left them empty. Zip mods may also ship `<java>` (extracted to
  `%TEMP%\blockin-mods\<zipbase>` and compiled). Auto ids start at 9.
- **`BlockCodeCompiler`**: compiles a mod `.java` at load with
  `javax.tools.ToolProvider` into `<source-dir>/bin` (wiped first; classpath =
  `java.class.path`; source package decl honored), loads it via `URLClassLoader`
  (parent = BlockLoader's loader) and calls the static
  `public static Block create(BlockSpec spec)` factory. Returns null (and the
  caller uses `spec.build()`) when a JRE lacks the compiler, compile fails, or
  no valid factory exists. `register()` re-base-descriptions/types as needed.
- `Level.isSolidTile`/`isLiquid` now consult `BlockLoader.isLiquid(id)`, so any
  `liquid` XML block is non-solid and swimmable; `isWater` stays `id==3` for
  WaterSystem.
- `BlockLoader`: STONE=1, GRASS=2, WATER=3, DIRT=4, SAND=5, GRAVEL=6, LOG=7,
  LEAVES=8, AIR=0; custom ids from `nextId=9`. Registration order in
  `loadAll()`: seed atlas → hardcoded eight → `loadCoreXmlBlocks()` (id-pinned
  overlay of Stone, Grass, Water, Dirt, Sand, Log, Leaves) → folder mods →
  zip mods.
- `SoundEngine`: `javax.sound.sampled` `SourceDataLine`, 44100 Hz / 16-bit /
  mono / big-endian; fields `stepVolume=0.6F`, `breakVolume=0.8F`,
  `placeVolume=0.7F`, `stepEnabled/breakEnabled/placeEnabled`;
  `playStep/playJump/playBreak/playPlace`, `warmUp()`, `shutdown()`.
  `write()` must be **non-blocking full-or-drop** into a 64 KB line buffer:
  the old blocking `line.write()` of a 0.22 s whoosh through an 8 KB buffer
  stalled the tick thread ~100 ms on every jump and left audio backlog after
  walking. Steps now play a staged WAV
  `com/insanestudios/blockin/Sound/Sounds/Walking/Walking_Grass.wav`
  (decoded via `AudioSystem` to 16-bit mono BE, trailing silence trimmed,
  capped ~0.6 s; procedural thump is the no-WAV fallback). build.ps1 stages
  `.wav` alongside `.png`.

- **FBX models** (`com/insanestudios/blockin/Models/`): pure-Java, no deps.
  `FbxParser` parses both ASCII (7.x text) and binary (6.x-7.x, little-endian,
  LZ4-inflated arrays; strips 23-byte `Kaydara FBX Binary` magic + 1 version
  byte) into a shared `FbxNode` tree; binary props are scalars or arrays, copied
  losslessly as `double[]`/`int[]` and read via `Node.doubles()/ints()/
  firstInt()`. `toModel(root, dir)` builds one triangle-`Model` per FBX:
  triangulates `PolygonVertexIndex` (negated index = last vert, then a fan),
  applies per-model `Lcl Translation/Rotation(Euler XYZ)/Scaling` from
  `Properties70`/`Lcl Rotation`, reads normals/UVs honoring
  `MappingInformationType` (ByControlPoint vs ByPolygonVertex) and
  `ReferenceInformationType` (Direct vs IndexToDirect), and computes flat normals
  when the mesh has none. The first `Color`/`DiffuseColor` sets vertex tint; the
  first `Texture` `FileName` (resolved: basename, or `textures/<file>` next to the
  FBX) becomes the GL texture via `ModelLoader.loadTexture`. `ModelLoader`:
  `warmUp()` scans `Mods/Models/**/*.fbx`, `load()` registers each under its
  relative name, `render(name[,x,y,z,scale])` draws it, `models` map + `loadCount`
  exposed; `Update.init()` calls `ModelLoader.warmUp()`; GameHUD shows
  `models: <count>`. Render with texture if `Model.tex>=0` else `glColor3f` tint.
  Current gaps: no animation/bones/skinning, no embedded media, `.glb/.obj` not
  covered, binary control-point UV edge cases approximate.

  **Bones/skinning** (added, `Models/`): `Scope`-class wiring parses
  `SubDeformer/Deformer` objects (types Skin/Cluster), clusters carry
  `Indexes`+`Weights` (control point influence, capped at 4 bones/vertex and
  normalized) and `TransformLink` (global bind matrix, row-major, inverted into
  the bone's bind). Connections wire geometry←Skin, Skin←Cluster, Cluster←bone
  Model, and bone←parent-bone hierarchy. Per-bone local RST comes from the bone
  Model's `Properties70`. `Model` gains `Bone[] bones`, `skinVerts/skinNormals`
  (bind-pose control points; normals = ByControlPoint mesh normals, else
  averaged face normals), `cornerCtrl` mapping, `skinBones/skinWeights`, and a
  per-frame CPU skin pass in `render()`:
      pose = globalInverse * Σ w_i * (currentGlobal_i * inv(TransformLink_i))
  with `globalInverse` = inverse of the mesh Model's own RST (folded in
  `MatOps` row-major math: `mul/inv/rotRST/mulPt/mulDir/addM`; translation lives
  in m[3]/m[7]/m[11]). Skins only the FIRST geometry of a file; unweighted
  control points keep bind pose. `Model.setBoneRotation(name, degX, degY, degZ)`
  tweaks a bone's local euler each frame for basic animation. Matrix convention:
  row-major storage, column vectors v'=M*v, Euler applied Z→Y→X (degrees in).
  Gaps: no animation takes/curves yet, no PreRotation/PostRotation, bones on
  multi-geometry files unsupported.
- Jump sound is **edge-triggered** (`jumpKeyHeld` latch in `PlayerController`):
  holding SPACE re-jumps every grounded tick but only synthesizes the whoosh on
  key-down; `trackSteps()` also ignores movement below `1e-4` and **gates step
  sounds on actual movement keys**, so idle steps can't fire. When the body
  drifts while no key is held, a `driftDiag` line is printed to console + the
  HUD debug overlay so the cause can be caught (physics at rest is provably
  static; any reported idle steps indicate unexpected horizontal drift).
- **Infinite world** (`Level` is now chunk-streamed): `Level(128,64,128)`
  keeps width 128 (x) / height 64 (z) as the eagerly generated spawn footprint
  (also matches legacy saves exactly) and depth 128 (y, vertical, finite). The
  world is horizontally unbounded: blocks live in `Map<Long, byte[]>` column
  chunks (16x16, full depth), indexed `(y*16 + lz)*16 + lx`, keyed by
  `((long)cx<<32) | (cz & 0xFFFFFFFFL)` with `floorDiv/floorMod` chunk math so
  negative coords work. `Level.ensureChunk(cx,cz)` inserts the chunk BEFORE
  filling so `setRaw` recursion during generation never loops; it fills terrain
  via `TerrainGen.fillChunk` (all 8 y-subchunks), plants trees, then computes
  per-column skylight into `Map<Long,int[]>` (16x16, `0.8F` below light depth).
  `getCubes` clamps only Y; `getBrightness` returns 1.0F for ungenerated
  chunks. `getSurfaceY`/`isOcean` scan downward and lazily generate.
  `ChunkBuilder.terrain` only forces the spawn-footprint chunks.
- **Chunk/tile name remap**: the old `Tile` concept (air/stone/grass) became
  `BlockLoader`: AIR=0, STONE=1, GRASS=2, WATER=3, DIRT=4, SAND=5, GRAVEL=6,
  LOG=7, LEAVES=8 (nextId=9). `isTile` = {0,1,2} ≠ block? see `Level.isTile`
  (any solid or water); `isSolidTile` excludes water (byte compare vs WATER);
  `isLightBlocker` = `isTile` so water+leaves block skylight; `isLiquid` =
  tile==WATER. Update.init order is `BlockLoader.loadAll()` first.
- **TerrainGen** (`World/TerrainGen.java`): deterministic generation via seeded
  hash/value noise (`hash/lattice/noise2/noise3/fbm2/fbm3`) — 2D height field
  with **no island falloff** (infinite terrain), heights clamp [4, depth-6]
  (observed [25,40] locally, sea 30); 3D fbm caves `<-0.5` only below
  `height-3`; layer rules (surface GRASS when height≥32 else SAND, dirt/sand
  below, gravel pockets `(hash&0xFF)<2`, ocean water from surface+1..SEA_LEVEL-1
  when `surface<SEA_LEVEL`); ctor `TerrainGen(seed, depth)` (width/height
  dropped with the falloff). Trees: `plantTreesForChunk(level,cx,cz)` scans the
  chunk plus a 2-block halo (border canopies belong to their trunk's chunk)
  with ~0.2% chance on grass (LOG trunk 4-5 starting AT `ground` = first air
  above the surface block — the trunk sits flush on grass, not 1 up; LEAVES
  spheres `dx²+dz²<=5`). Halo lookups lazily generate neighbours, which is safe
  because `ensureChunk` pre-inserts. Same-seed fully deterministic; seed 42
  spawn-footprint composition ≈ AIR 792017 / STONE 216345 / GRASS 3703 /
  WATER 4866 / DIRT 11005 / SAND 17847 / GRAVEL 1672 / LOG 83 / LEAVES 1038.
- **Water rendering**: `BlockAtlas` WATER=4, SAND=5, GRAVEL=6, LOG_SIDE=7,
  LOG_TOP=8, LEAVES=9 — procedurally generated in `seedCoreCells` (seeded
  Random, e.g. `0xF0950ADCL`); BlockLoader WATER uses `WaterBlock.render`
  through the shared Tesselator with per-vertex alpha 0.62 and the same layer
  gate as opaque blocks (`br == c* ^ layer == 1`), drawing each face only when
  the neighbour is air. `Tesselator` is now RGBA (4 floats per colour,
  `color(r,g,b,a)`, opaque blocks default alpha 1); `Blockin.renderWorld3D`
  enables `GL_BLEND`/`SRC_ALPHA,ONE_MINUS_SRC_ALPHA` around the whole world
  pass and disables it after (blend is identity for alpha-1 opaque geometry;
  leaves still go through the alpha test). The old immediate-mode
  `GL11.glVertex3f` behind a `glBegin`-less tesselator silently dropped every
  water face — never mix immediate-mode quads into the batched pipeline.
- **WaterSystem** (`World/WaterSystem.java`): event-driven queue (static
  ArrayDeque + `marked`/`flowLeft`/`prev` maps, `MAX_FLOW=16`,
  `MAX_STEPS_PER_TICK=512`). `onBlockChanged` (called from `Blockin.doBreak/
  doPlace`) enqueues the changed cell + 6 neighbours; `update()` runs each tick.
  Rules: a cell falls down in ONE leap to its floor; then spreads sideways.
  Destinations remember `prev` (cell they flowed from) so water can't wobble
  back and forth. Cells with a full budget (`left==MAX_FLOW`) are **sources**
  and stay put while pushing neighbours. Cells inside an ocean basin
  (`level.isOcean`) are infinite sources — they refill instantly after moving,
  so digging into the sea pours water back in until level with the surface.
  Non-basin water is finite: it streams outward ≤MAX_FLOW and settles. Known
  quirk: a single 1-wide water trail drains forward (streaming) rather than
  pooling wide; pits/holes refill correctly (verified: 4-deep pit beside ocean
  fills; the basin's own refill can make the map-wide count drift by a cell).
  Cell keys pack x/z at 21 bits each (signed, sign-extended on unpack) + 10-bit
  y, since the old 8-bit fields capped coords at 0..255.
- **Swim physics** (`PlayerController`): `inWater` sampled at feet+0.3 / top-0.2
  via `level.isLiquid`; water moveSpeed 0.012, gravity −0.004 (vs −0.008 air),
  SPACE swims +0.06, terminal clamp ±0.09; ground jump +0.16 (was +0.12), air
  gravity −0.008 (was −0.005) — stronger gravity + slightly higher jump;
  jumps gated `!inWater`;
  `resetPos()` prefers a non-ocean column (≤32 tries, spawn at surfaceY+1.9,
  feet flush on the top face) so the player starts on land; pig spawns require
  `surfaceY >= SEA_LEVEL`.
- **Fall-through fix** ("sometimes sink through sand/ground"): spawn was
  0.1 below the surface block's top (center surfaceY+1.8 ⇒ feet surfaceY+0.9
  vs top face surfaceY+1), and `Box.clipYCollide` only caught a falling body
  when feet ≥ cube top — so any sub-face drift (spawn, float error) made the
  floor unrecoverable. Now `resetPos` uses +1.9 (feet = top face) and the
  falling branch clips when `c.y0 >= this.y1 - 0.1F` (pushes an embedded body
  back up flush; no effect on normal falls/landings). Verified headless:
  flat-sand 120k ticks minGap=0.0, real TerrainGen 90k ticks no penetration,
  256² terrain scan has 0 surface holes / 0 sand-over-air, stair/diagonal run
  PASS, spawn probe now lands at the top face instead of a block deep.
- `GameHUD` reads `PlayerController`/`Level` directly (no reflection):
  E/TAB inventory toggle, wheel slot switch, 9-column block grid, typed debug
  overlay.
- `MainMenu` branded "Blockin by insanestudios"; Settings hosts the
  SoundSettingsMenu overlay (volume cycling; routes poll/render while open).

- **LevelRenderer is streamed**: `Map<Long, Chunk>` keyed (cx,cy,cz) kept in a
  chunk-radius ring around the player (FAR 12 / SHORT 6 chunks, all 8
  y-subchunks); far chunks are `Chunk.dispose()`d (glDeleteLists) on drop;
  edit/light events only repaint chunks that already exist (`setDirty` uses
  floorDiv and skips missing keys); `pick()` may lazily generate columns via
  `isSolidTile`. New Chunk objects `glGenLists` in their ctor (GL context is
  current during render).

## Menu / game flow (Blockin.java)

1. `initDisplay()` → title "Blockin", 1920×1080 (fallback desktop mode),
   vsync, GL state, `WindowPatch.init()`, `Update.init()`.
2. Loop: `Update.pollMenu()` while `Update.menuActive()`.
   - `menuChoice() == MainMenu.PLAY` → `buildWorld()` → PLAYING
   - `menuChoice() == MainMenu.QUIT` → close
3. Playing: E or TAB → `GameHUD` inventory (mouse ungrabbed, no look/movement);
   WASD/space/shift movement, mouse look, left = break, right = place
   (180 ms repeat, self-clip guard), ENTER = save, ESC = quit.
4. Every frame: render world (two layers 0/1), pigs, block highlight,
   `Update.render()`, crosshair.

## Launcher specifics

- `launcher/MainWindow.xaml.cs`: builds via response file (`blockin_sources.txt`)
  into BuildPath, then `CopyResources()` stages `com/**/*.png` + all PNGs into
  BuildPath and merges `Mods` into GamePath\Mods.
- Run args always include `--enable-native-access=ALL-UNNAMED
  --add-opens=java.base/java.lang=ALL-UNNAMED` (both debug and release), then
  `-cp` (buildDir;jars;.) + `-Djava.library.path=<natives>` +
  `com.insanestudios.blockin.Blockin`.
- Settings default (`launcher_settings.json` next to the exe):
  - GamePath   = `D:\Games\Blockin`
  - SourcePath = `D:\Games\Blockin Source`
  - BuildPath  = `D:\Games\Blockin`
  - NativesPath = `D:\Games\Blockin\natives`
  - JarFiles   = `lwjgl.jar`, `lwjgl_util.jar`

## Headless tests

Handy harnesses live in `%TEMP%\opencode\gentest` (`java -cp "D:\Games\Blockin;<gentest>" <Class>`)
without initializing LWJGL — the atlas is CPU-side until GL upload. `XmlModTest`
creates temp folder mods + a zip under `%TEMP%\opencode\gentest\xmlmod-test`,
sets `mc.modsDir` to it, and verifies: core XML built-ins drive ids 1-4
(descriptions, liquid→WaterBlock, resource/tile dedup), folder/zip/`Blocks` mod
registration, auto-id allocation, and a compiled `.java` factory actually ran.
`SaveTest`/`MigrateTest` cover the versioned + legacy save formats;
`GenTest` exercises terrain + water flow.

## Smoke-test command (worked)

Ran `java -cp D:\Games\Blockin;lwjgl.jar;lwjgl_util.jar
-Djava.library.path=D:\Games\Blockin\natives ... com.insanestudios.blockin.Blockin`
from `D:\Games\Blockin` for ~10 s — boots to menu, BlockLoader registers
stone/grass + Planks(id 3) + Plank(id 4). Harmless JVM warnings about
`sun.misc.Unsafe` from LWJGL 2 on Java 26.

## Gotchas

- **Diagnosing a "cannot find symbol" that looks like a missing file**: a
  wrongly-declared `package` line compiles with exit 0 and silently deposits the
  class in the wrong package dir. If `javac` succeeds but consumers still can't
  see the class, check the source's package declaration, not the build cmd.
  (`GameHUD.java` hit exactly this: `package com.example.hud`.)
- **javac arg files / arg passing on Windows**: passing ALL sources as one
  space-joined PowerShell string makes javac see a single bogus file; pass the
  *array* so PowerShell expands it into separate args. The old quoted
  `@sources.txt` route is less reliable (one quoted token can get dropped in
  batch). Reliable: `workdir = "D:\Games\Blockin Source"`, sources as relative
  forward-slash paths (no spaces), `-cp "D:/Games/Blockin/lwjgl.jar;D:/Games/
  Blockin/lwjgl_util.jar"`, `-d "D:/Games/Blockin"`. LWJGL jars live at the
  **game root**, not `lib/`.
- PowerShell `Copy-Item -Recurse src dst` **nests** the source folder when the
  destination already exists (`dst/src/...`). Use the `Copy-Merge` helper
  (build.ps1) or xcopy with trailing backslash (build.bat) instead.
- `javac @sources.txt` argfile: paths must be relative (spaces in
  `D:\Games\Blockin Source` break quoted full paths) or quoted.
- `javac -d` never deletes stale `.class` files — wipe the deployed
  `com\insanestudios` tree before rebuilding after a refactor.
- Source tree must stay free of `.class` litter (built artifacts go straight
  to the game dir).
- `Math.max(Math.min(...))` on floats widens to double − cast explicitly
  before assigning to `int` (was a compile error in SoundEngine).
- Do **not** capture texture creation inside `glNewList` (atlas laziness) —
  resolve the bound texture first; chunk render also binds in immediate mode.
- Chunk/Level change fan-out: `Level.setTile` fires `tileChanged` →
  `LevelRenderer.setDirty(...)`; the terrain generator uses package-private
  `Level.setRaw` to skip listener work.
- If `dotnet build` can't overwrite `Launcher\BlockinLauncher.exe`, the
  launcher is currently running — close it and rebuild.
- **LWJGL 2 `Keyboard.isKeyDown` can stick a key "down" forever** when the
  release event is dropped (focus change / hitch). Symptom: player drifts in
  0.6-block bursts and footsteps keep firing while "standing still". Fix:
  `PlayerController` mirrors key state from the `Keyboard.next()` event stream
  (`keyDown[]`) and `Arrays.fill`s it false on `Display.isActive()` regain —
  never trust raw `isKeyDown` for movement/step gating.
- `SoundSettingsMenu` called a `MainMenu.drawBevelButtonText` that no longer
  exists (button helpers were renamed to `drawModernButtonText`) — that broke
  the whole build; keep menu callers in sync on button-helper renames.