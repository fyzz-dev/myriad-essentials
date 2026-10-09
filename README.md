# Myriad Essentials

The stock modules and HUD elements for [Myriad](https://myriadclient.dev/) ([source](https://github.com/fyzz-dev/myriad)). Essentials is an ordinary
addon: it's built on Myriad's public API like any other (the build fails if it touches internals), it's versioned and
released on its own, and Myriad runs without it. Everything here is made for 2b2t and passes Grim by default.

Put `myriad-essentials-<v>.jar` in `mods/` next to `myriad-<v>.jar` (from the
[latest release](https://github.com/fyzz-dev/myriad-essentials/releases/latest)) and press **Right Shift** in game.

## Modules

In the standard categories; each module's settings explain its options.

| | |
|---|---|
| Combat | **Offhand** (totem, crystal, golden apple or shield, falling back to a totem when it matters; sword gap), **Auto Armor** (best armour; a bind flips chestplate and elytra), **Auto Disconnect** (health, totem pops, totems left, armour, players, beds, anchors, crystals, creepers, chosen entities; turns off or waits for the reason to clear), **Kill Aura** (Grim-safe: faces the target with move fix and hits when this tick's or the last sent rotation lands on its hitbox within reach, so the turn and the hit share a tick; full charge, vanilla packet order; Switch holds the best weapon on the server only, from the hotbar or inventory, charging it before the target is in reach, or attacks only while you hold one) |
| Movement | **Elytra Fly** (highway bouncing, or Altitude: pitch 40 style flight without fireworks, about 27 b/s; with Elytra Tweaks' No Durability, long glides are cut with a chestplate so the elytra never wears; mines or, with Baritone, walks round obstacles), **Elytra Tweaks** (Rocket Boost: rockets push as hard as Grim allows, full speed at once, about 34 b/s straight and 42 on diagonals, holding height; No Durability: the elytra never wears out, swapped with a chestplate from your off hand or hotbar, the swap sounds muted), **Inventory Move** (walk with screens open, arrow keys to look), **Velocity** |
| Player | **Auto Eat** (pauses Baritone), **Auto Tool** (silent; borrows tools from your inventory and puts them back), **Inventory Tweaks** (right-click a shulker box or ender chest in your inventory to open it on the spot), **Middle Click** (friend, experience, rocket or pearl by what you point at), **No Break** (armour, elytras and tools swapped out before they break, so they can be mended; another piece of the same kind goes on, and what's put away is left alone by Auto Armor, Auto Tool and mining), **Packet Mine** (hit once and the block is mined for you with your hand free, timed to Grim's own allowances so it finishes early without flags; Fast mode finishes every block early with decoy starts, as 2b2t clients do, with double break; hold and drag to queue blocks, auto rebreak, tools borrowed from your inventory and put back), **Reach** (starts at vanilla; Grim flags more), **Stack Replenish** (tops up hotbar stacks and refills used-up slots), **Wall Interact**, **X Carry** |
| Render | **ESP**, **Blocks** (highlight chosen blocks, like spawners or beds, in their own colours), **Block Highlight** (your own outline for the block you're looking at), **Storage**, **Tracers**, **Nametags** (players, mobs, items, pearl owners), **Tooltips** (shulker, ender chest and map previews, durability, food), **Full Bright** (gamma or night vision), **Free Look**, **Freecam**, **View Model**, **Zoom** |
| World | **Air Place** (place blocks in mid-air where you look, through the off hand as 2b2t clients do; current Grim builds refuse it), **Scaffold** (blocks under you as you walk, clicking faces you can see; sprints without breaking stride, unaimed as 2b2t clients place, or Rotate to face each block for stricter Grim; bridges corners, towers when you jump) |

HUD elements: Watermark (with the logo), Module List, Coordinates, Armor, Binds, Chest Count, Direction, Effects,
FPS, HP, Player Count, Speed, Totems, TPS. Baritone options do nothing when Baritone isn't installed.

Modules behave differently on a server running Grim (2b2t) and on one with only the vanilla checks: Myriad's
anti-cheat profile (Auto, Grim, Vanilla) decides, and Essentials stays Grim-safe either way.

## Building

Requires JDK 25+. Myriad comes from its maven (`https://fyzz-dev.github.io/myriad`); `myriad_version` in
`gradle.properties` picks the release.

```bash
./gradlew build                      # build/libs/myriad-essentials-<v>.jar
./gradlew runClient -PopenDesktop    # Myriad + Essentials, the menu open on the title screen
./gradlew runClient -PquickPlay="New World"               # …or straight into a singleplayer world
./gradlew runClient -PquickPlayServer=localhost:25565 -Pusername=GrimTester   # …or onto the Grim test server
```

The dev client has ViaFabricPlus and joins servers as 1.20.4, as most 2b2t players do; `-Pvia=native` joins as the
client's own version (or `-Pvia=1.21.4`, any version ViaFabricPlus knows).

```bash
./gradlew build -PcoreDir=../myriad  # against the core jar built in a Myriad checkout next to this one
```

`-PcoreDir` is for changing core and Essentials together: build core's jar there (`./gradlew :core:jar`), build
here against it, and nothing needs publishing or a version bump until the core change is released. The other way
round, Myriad's "everything" dev client loads this checkout's jar with `-PessentialsDir=../myriad-essentials`.

## Testing against Grim

[`tools/grim-test/`](tools/grim-test) runs a local 2b2t-like server with Grim, with scripts to run server commands,
drive the dev client and read Grim's flags. `tools/grim-test/suite` is the Essentials test suite: every module
switched on and off (`selftest`), then each main module in a scene of its own, checked on the server and against
Grim's flags. Run it after changing core or Essentials; add a test when you add a module.

## Releasing

Bump `mod_version` in `gradle.properties`, add the section to `CHANGELOG.md`, then
`git tag v<version> && git push origin v<version>`. The workflow builds, attaches the jar to a GitHub release and
publishes it to Myriad's maven.

## Credits

Several modules take their ideas from [Lambda](https://github.com/lambda-client/lambda), whose take on 2b2t
utilities (packet mining, elytra bouncing, inventory tweaks) was a big inspiration. The code here is written from
scratch for Myriad's services.
