# ZERØ

A **strictly client-side** utility client for **Fabric 1.21.11**, made for finding ores and locating bases on anarchy servers like 2b2t.

> **Zero server interaction.** ZERØ never sends a packet, never registers a network channel and never interacts with the server.
> It only reads what the server already sends to every client (chunks, entities, tab list) and draws on your screen.

## Download

**[⬇ Latest ZERØ jar](https://github.com/PlumedeParis/NewClient/releases/latest)**: built automatically by GitHub Actions on every push.

Install: **Fabric Loader** (0.17+) and **Fabric API** for **1.21.11**, put the `zero-x.y.z.jar` in `.minecraft/mods`, then press **Right Shift** in game.

![ZERØ in game: StorageESP, BaseFinder, SusChunk, BlockESP seen from Freecam](docs/overview.png)

## Modules

| Module | Category | What it does |
|---|---|---|
| **BlockESP** | Render | Highlights chosen blocks through walls: per-block colors, box/outline/fill, tracers, labels. Searchable block picker with presets. **Exposed Only** mode ignores anti-xray fake ores. |
| **StorageESP** | Render | Chests (double chests merged), trapped chests, ender chests, shulkers in their dye color, barrels, hoppers, furnaces, dispensers, brewing stands, chest minecarts / chest boats. Per-type colors, tracers and per-chunk "N storage" counts. |
| **XRay** | Render | Hides everything except your chosen blocks, with fullbright. Works with Fabric API's renderer. See the anti-xray note below. |
| **SpawnerESP** | Render | Every mob spawner and trial spawner through walls, colored by mob type (zombie, skeleton, blaze, pig...), with labels, tracers and an optional 16-block activation-range box. |
| **Crosshair** | Render | Custom pixel crosshair with an editor: paint on a 15x15 grid (left paint / right erase), mirror X/Y, 7 presets, color swatches, outline, scale, target color and attack-cooldown bar. Live preview on light and dark backgrounds. |
| **Freecam** | Render | Moves only the camera. Your player stays still and sends exactly what an idle player sends. Attack, use and pick-block are blocked while it is on. |
| **PlayerDetector** | Misc | Visual-range alerts, friends, radar HUD, ESP and nametags, logout spots, join/leave alerts, encounter log. |

![Crosshair editor](docs/crosshair_editor.png)

### World

| Module | What it does |
|---|---|
| **SeedOreFinder** | Type the world seed and ZERØ predicts ores (diamonds, debris, emeralds, gold, iron, redstone, lapis, copper, coal, quartz). It runs Minecraft 1.21.11's **own ore generation code** locally, from the seed, against a private copy of the terrain you have loaded. A live **seed match %** compares predictions with ores you can actually see, so you know immediately whether the seed works on that server. |

**Measured accuracy** (automated test against the real world generator, inner 3×3 chunks):

| Ore | Precision | Recall |
|---|---|---|
| Diamonds | 100% | 100% |
| Redstone | 100% | 100% |
| Ancient debris | 100% | 100% |
| Nether gold | 99.8% | 100% |
| Iron | 100% | 90.6% |

**Limits (important):** it only works if the server uses vanilla ore generation with that exact seed. Many Paper servers randomize ore "feature seeds" precisely to stop seed-based finders; if that is the case the seed match % will stay near 0% and no client can fix it. It also only matches chunks generated in 1.18+ (not 2b2t's old 1.12 chunks). Predictions in unloaded chunks use estimated terrain and are less precise (shown with `~`).

### Hunt (base finding, DonutSMP-style techniques)

| Module | Technique |
|---|---|
| **BaseFinder** | Player-made blocks / stashes, natural structures filtered out. |
| **SusChunk** | Hidden activity: dug tunnels, buried storage, lights, portals, farms, chunk age. |
| **AmethystFinder** | Buds are grouped into whole geodes (across chunk borders). Generation gives ~25% fully grown clusters; buds only grow while a player keeps the chunk loaded. Only geodes where almost everything is fully grown (default 80%+) are highlighted, with the odds of that happening naturally (e.g. "1 in 2M"). Natural geodes are ignored. |
| **GrowthFinder** | Kelp tops generate at age 20–23; age 24–25 is only reachable by growing in a loaded chunk, which is hard proof of long loading. |
| **RotatedDeepslate** | Natural deepslate is always upright (axis Y). Sideways deepslate (axis X/Z) was placed by a player, typically patching a tunnel or a base entrance. Also flags cobbled deepslate (outside ancient cities). |
| **SpawnerFinder** | Spawners with a non-natural mob or no dungeon/mineshaft/fortress/stronghold/bastion around them were placed by a player. Shows the mob type. Also detects mob farms (big same-type mob clusters) and dropped-item piles. |
| **BaseRadar** | For zones full of bases: merges the evidence of every Hunt module (and StorageESP) into one target per location, ranks them (smart = evidence weighted by distance), shows beams only on the top few, a ranked HUD list and a mini radar. Mark targets as checked with **K** (or automatically after standing near one for 3 s) so you work through the area one base at a time; checked targets are saved per server. Quiet Mode replaces the per-module alert spam with one alert per strong new target. |
| **TunnelFinder** | Highlights 1x1 shafts dug straight down, 1-wide 2-high tunnels and 1-wide staircases. Natural caves are never that straight and regular. |

![Hunt modules: tunnel, shaft, staircase, rotated deepslate, grown kelp, geode, spawner/farm tracers](docs/hunt.png)

> Krypton is closed-source, so ZERØ does not copy it; these modules implement the base-hunting techniques DonutSMP players use, all from data your client already receives.

![BaseRadar: ranked targets, mini radar, beams on the top targets](docs/base_radar.png)

![X-Ray (exposed only)](docs/xray.png)

### X-Ray and 2b2t's anti-xray

2b2t hides real ores that are fully enclosed in stone and sends **fake ores** in their place. The real positions never reach your client, so **no client-side X-Ray can see enclosed ores**. Anything claiming otherwise is either showing the fakes or asking the server (which gets you kicked).

What *does* work: blocks touching air, water or lava must be sent truthfully. With **Exposed Only** (on by default), X-Ray and BlockESP show only those, which means **real** ores in caves, ravines, lava lakes and tunnels, with all the fake noise filtered out.

X-Ray hooks vanilla / Fabric API (Indigo) chunk meshing; it does not work if **Sodium** is installed.

## Interface

| ClickGUI | Block picker | Saved finds |
|---|---|---|
| ![](docs/clickgui.png) | ![](docs/block_picker.png) | ![](docs/finds.png) |

* **Text boxes** support **Ctrl+V** paste (Cmd+V on Mac), Ctrl+C copy, Ctrl+X cut, Ctrl+A select all, Ctrl+Backspace delete word. Ctrl+V in the GUI pastes into search.
* **Right Shift** opens the GUI. Left click: toggle. Right click: settings. Middle click: bind. Start typing to search.
* **★ Finds**: every base, stash and sus chunk saved on the current server. You can copy coords and delete entries.
* **HUD**: watermark, module list, coords plus converted Nether/Overworld coords, nearby bases, player radar and toasts.
* **Performance options** (Client panel): Max Labels, Label Range, Detail Distance (far boxes skip outlines), Scan Budget (auto-halved below 30 FPS) and a Perf HUD showing ZERØ's own cost per frame.
* **Client panel**: accent colors and chroma, opacity, blur, HUD toggles, and "Hide Coords" streamer mode.

Everything is saved to `.minecraft/config/zero/` (`config.json`, plus `finds/<server>.json` and `finds/<server>.log`).

## Build it yourself

```
./gradlew build               # -> build/libs/zero-<version>.jar
./gradlew runClientGameTest   # headless in-game tests: features + seed-prediction accuracy
```

Requires JDK 21. The screenshots above come from that automated test (small window, software rendering).

## Anticheat notes

* No module sends packets, or changes movement, rotation, interaction or inventory.
* Freecam keeps the real player as the camera entity, so vanilla keeps sending normal idle position updates.
* Chat messages and sounds from the client are local only.
* Fabric API itself tells the server it is a Fabric client, like any Fabric setup. ZERØ adds nothing to that.
