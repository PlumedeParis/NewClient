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
| **Freecam** | Render | Moves only the camera. Your player stays still and sends exactly what an idle player sends. Attack, use and pick-block are blocked while it is on. |
| **BaseFinder** | World | Scores chunks for player-made blocks and entities, detects stashes, filters out natural structures. Toasts, chat, sound, saved waypoints. |
| **SusChunk** | World | Flags hidden activity: dug tunnels, buried storage, lights underground, portals, Nether rails, farms. Optional old/new chunk overlay for trails. |
| **PlayerDetector** | Misc | Visual-range alerts, friends, radar HUD, ESP and nametags, logout spots, join/leave alerts, encounter log. |

![X-Ray (exposed only)](docs/xray.png)

### X-Ray and 2b2t's anti-xray

2b2t hides real ores that are fully enclosed in stone and sends **fake ores** in their place. The real positions never reach your client, so **no client-side X-Ray can see enclosed ores**. Anything claiming otherwise is either showing the fakes or asking the server (which gets you kicked).

What *does* work: blocks touching air, water or lava must be sent truthfully. With **Exposed Only** (on by default), X-Ray and BlockESP show only those, which means **real** ores in caves, ravines, lava lakes and tunnels, with all the fake noise filtered out.

X-Ray hooks vanilla / Fabric API (Indigo) chunk meshing; it does not work if **Sodium** is installed.

## Interface

| ClickGUI | Block picker | Saved finds |
|---|---|---|
| ![](docs/clickgui.png) | ![](docs/block_picker.png) | ![](docs/finds.png) |

* **Right Shift** opens the GUI. Left click: toggle. Right click: settings. Middle click: bind. Start typing to search.
* **★ Finds**: every base, stash and sus chunk saved on the current server. You can copy coords and delete entries.
* **HUD**: watermark, module list, coords plus converted Nether/Overworld coords, nearby bases, player radar and toasts.
* **Client panel**: accent colors and chroma, opacity, blur, HUD toggles, and "Hide Coords" streamer mode.

Everything is saved to `.minecraft/config/zero/` (`config.json`, plus `finds/<server>.json` and `finds/<server>.log`).

## Build it yourself

```
./gradlew build               # -> build/libs/zero-<version>.jar
./gradlew runClientGameTest   # headless in-game test with screenshots
```

Requires JDK 21. The screenshots above come from that automated test (small window, software rendering).

## Anticheat notes

* No module sends packets, or changes movement, rotation, interaction or inventory.
* Freecam keeps the real player as the camera entity, so vanilla keeps sending normal idle position updates.
* Chat messages and sounds from the client are local only.
* Fabric API itself tells the server it is a Fabric client, like any Fabric setup. ZERØ adds nothing to that.
