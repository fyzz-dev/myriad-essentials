# Changelog

Myriad Essentials is versioned on its own, apart from [Myriad](https://github.com/fyzz-dev/myriad) core. Each
release says which core it needs (`myriad_version` in `gradle.properties`, the `depends` in `fabric.mod.json`).

## 0.2.15

Needs Myriad 0.2.9 (for the ping 2b2t's tab list doesn't show, which Recast's chestplate swaps wait on).

- Elytra Fly Recast with No Durability: take-offs go like every other glide start, the elytra on, the start and the
  chestplate back on in one tick. They were left to vanilla (the elytra put on, its jump press starting the glide), so
  the server glided you on the elytra to the landing: on the first hop, and the first after eating, a Baritone
  hand-over or a setback. Those were all the wear 0.2.14 had left (7 points in about 146,000 blocks on 2b2t); with this,
  none in 53,000. The client glides from that start too, or it took off again the next tick (Grim's ElytraA).
- Recast and eating: Auto Eat eats while you bounce. Recast cancelled any food used while the chestplate was on, which
  with 0.2.14 was nearly always, so hunger ran down until you couldn't sprint (and bouncing slowed to a crawl). While you
  eat, or Auto Eat is about to (between one item and the next), no glides start and nothing is swapped, as each swap
  would stop the eating; the hops go on, slower, and running slow then doesn't count as being stuck.
- Recast: when Grim learns the glide stopped before the glide hold could keep it from it (its ping answered first),
  the glide starts again at once instead of waiting out the hop, which was a setback.
- Recast with Baritone: the hand-over is only done once you're at the lane's height. Baritone could leave you on top of
  what was in the way, just above its goal, and Recast took that as there, ran into it again and handed over again,
  every other tick, until you stepped in.
- Recast's notifications: one at a time, each replacing the last ("Lane blocked: Baritone is taking you past it" once,
  counted, instead of a stack of them on a broken stretch).
- Elytra Tweaks no longer opens the elytra again for a glide that drops where the landing can't hurt (Recast turned off
  mid-hop, a foot above the highway): it skimmed the ground for 30 blocks or more, wearing the elytra.

## 0.2.14

Needs Myriad 0.2.8.

- Elytra Fly Recast with Elytra Tweaks' No Durability no longer wears the elytra on 2b2t. 2b2t counts every tick the
  server glides you towards the wear, however short each glide and across landings (vanilla only wears a glide that
  lasts 20 ticks, which a hop never does), so bouncing wore about 2.5 durability every 1000 blocks there. Now the
  chestplate is worn through each hop: every glide start puts the elytra on, starts the glide and puts the chestplate
  back on in the same tick, and the glide hold keeps you gliding through the rest of the hop. A hop that runs long (off
  an edge) gets another start every 8 ticks. The server glides about 9% of the time instead of about 60%, at the same
  speed: about 46,000 blocks on 2b2t without a point of wear.

## 0.2.13

Needs Myriad 0.2.8.

- Offhand takes a totem from the hotbar first while you move, so it goes over with the swap-hands key at once; one
  from the main inventory waits a tick for a click Grim takes. The spare Hotbar Totem is refilled only while you aren't
  moving.
- Auto Armor no longer misses the still tick it asks for (its delay started on the request), so it equips every piece
  while you sprint.
- Elytra Fly Recast, Clear with Baritone: one hand-over per obstacle. Baritone gets a goal at the first clear spot past
  it and gets you there however it can, mining and placing (allowed for the hand-over, and with the building blocks you
  carry, obsidian say, added to the ones it builds with, all put back after). Once you're there you face along the lane
  again, at the pitch you had, and bouncing goes on. If Baritone stops short, or gets no closer for 15 s, the lane
  counts as blocked (If Blocked: wait or disconnect). Before, a stall stopped Baritone and gave it a new goal a few
  blocks on, up to three times (its goal flickering on and off), then fell back to mining and filling itself, and it
  left you looking wherever Baritone had turned you. Mining and filling itself is now only without Baritone.
- Air Place goes as far along your look as the server takes, up to the new Distance maximum of 6: farther than that,
  it comes back to the farthest space your look enters within reach (about 5 blocks ahead). It used to place exactly
  Distance away, capped at 4.5, and refused that whenever the space's centre was past 4.5, so it seemed to work or
  not depending on where you looked.
- Grim test suite: offhand sprinting (main inventory and hotbar), auto armor sprinting, stack replenish walking and
  kill aura walking; the dev console's `hold sprint` presses the sprint key itself, which ViaFabricPlus as 1.20.4
  needs to sprint. The arena is cleared in two fills: one was over the server's 32768-block limit and failed, so
  blocks from earlier tests stayed.

## 0.2.12

Needs Myriad 0.2.7.

- Elytra Fly's Obstacles is now Clear or Stop. Clear walks round what's in the lane first (Baritone, to the first clear
  spot past it), and without Baritone, or when it finds no way (or stalls for 10 s), mines through blocks and fills
  holes, placing only at floor level, never above your feet, sneaking up to the edge. Stop stops there. Saved Mine and
  Baritone settings become Clear.
- If Blocked (new): when the way can't be cleared (with Stop, anything in the lane), wait there or disconnect.
- Recast keeps the lane's height from where you first stand. Off the line or below that height (knocked or fallen off
  the highway), Baritone walks you back onto the lane at its height, building up if it must, before bouncing on.
- While Recast has you on foot (Baritone, mining, filling), a jump held into the air no longer opens the elytra.
- The flight physics use exactly the rotation the movement packet carries (core's continuous yaw and nudged pitch,
  through vanilla's own look maths), so Grim's simulation can't drift from it. Once something had turned you a long
  way silently (Baritone walking you round an obstacle), the plain lane angles drifted by a hair a tick and Grim
  flagged Simulation after the bounce went on.
- No Durability with several elytras: the same elytra keeps being used (the next swap takes from where the last one
  went), spares stay where they are, and Auto Armor leaves the chest slot alone while you're off the ground or Recast
  is on. With Chest set to Elytra it put the best elytra on over each chestplate swap, so each of your elytras wore in
  turn and they piled up in the hotbar.
- No Durability brings in what's missing (the chestplate, or the elytra back) only once its last swap has settled, and
  says so only when there really is none: the "needs a chestplate" warning came from reading the chest slot mid-swap.
  With no hotbar slot free (or holding a spare elytra) it says so once and doesn't click; it used to try every tick,
  letting go of your keys each time, which broke the bounce. Recast fetches it before it starts bouncing, and mid-bounce
  only at a still moment (Grim refuses clicks while you sprint).
- Auto Armor lets go of your keys for a tick before it clicks (Grim cancels clicks while you move).
- Suite: tests for spare elytras, a full hotbar, filling a hole, Disconnect, walking round with Baritone and getting
  back onto the lane; tests that teleport mark Grim's flags after the teleport (it was counted against the module).

## 0.2.11

Needs Myriad 0.2.7.

- No Render: Chat hides the chat however it's drawn. Other mods can replace the HUD's chat element and draw it
  themselves, which skipped the hook before; the open chat screen still shows it. Portal Overlay also stops the screen
  warp while you stand in a portal (it only hid the purple overlay; Nausea only stopped nausea's own warp).
- Recast with No Durability cuts a long glide sooner and in more places. On a diagonal highway the server misses
  far more of the landings (its glides ran 12 to 15 ticks, cut by the chestplate a few ticks short of the 20 that
  wear the elytra); the cut now comes 10 ticks after the glide's start, which leaves room for ping spikes and 2b2t's
  lag, and also runs while the bounce pauses after a setback (only with the ground close below). Measured as 1.20.4
  through 200 ms of ping with 80 ms of jitter on a 500-block diagonal: longest server glide 12 ticks (was 15, and 21
  once with Grim flags), no wear, no flags; at 14 TPS no wear either (one run had a brief Simulation flag).
- It also only swaps once the server has answered its last swap: with jitter the client can show for a tick what the
  server had before, and a swap made on that puts the client out of step with Grim (Simulation flags, setbacks).
- Tried and left out: wearing the chestplate between every start, so the server never glides more than a moment (as
  some 2b2t clients do). Under 80 ms of jitter it flags Grim's Simulation where the bounce touches the ground.

## 0.2.10

Needs Myriad 0.2.7 (`Inventory.spare`, which No Break uses).

- No Break (new): armour, elytras and the tools and weapons in your hands are swapped out before they break, so they can
  be mended later. Another piece of the same kind with more uses left goes on (another elytra in mid-air, another
  pickaxe for the pickaxe); with none, armour comes off and a tool goes into the inventory, while the elytra you're
  flying with stays on until you land. Armor Uses (20) and Tool Uses (10) set when. What it puts away is left alone by
  Auto Armor, Auto Tool, Elytra Tweaks and core's tool picking for breaks (Packet Mine, Recast's mining).
- Elytra Fly's Recast with No Durability no longer wears the elytra with ping. The server can miss a landing (its
  packet handled in the same server tick as the next hop's, with jitter or low TPS) and glide on through the next hop;
  the cut that ends a long glide with the chestplate counted from your last touchdown, so it never came. It now counts
  from the glide's start until the server stops it. Measured as 1.20.4 through 200 ms of ping with 80 ms of jitter:
  30 s of bouncing wore the elytra by 2 (chestplate in the hotbar) and 5 (chestplate only in the inventory) before,
  0 after, also at 14 TPS.
- Recast with No Durability brings a chestplate from the inventory into the hotbar, as Elytra Tweaks does, and says so
  if there's none.
- Recast under a ceiling too low to hop (a two-high tunnel, a roof over the lane) walks along the lane, sprinting, until
  there's room overhead, then bounces on, in every Obstacles mode. Before, Stop waited forever, Mine dug the ceiling out
  until it had about 34 clear blocks ahead, and Baritone could walk you to such a spot and get stuck there; Baritone's
  spot past an obstacle now has room to hop when there's one.
- No Render: Chat hides chat messages on screen; they still show while chat is open.
- Dev client: ViaFabricPlus, joining servers as 1.20.4 like most 2b2t players (`-Pvia=native` for 26.2).
- Grim test server: ViaBackwards, so 1.20.4 clients can join. Suite: the elytra wear checks read the damage the server
  stores (they read nothing and always passed); tests for No Break, Recast's wear and a low ceiling; Scaffold judged on
  the path it walked (slower as 1.20.4); Elytra Tweaks' No Durability test flies higher, clear of the hills its fall
  guard rightly waits for.

## 0.2.9

Needs Myriad 0.2.6.

- Auto Eat picks food by why it eats. Low health eats Health Foods first (new setting; golden apples by default), the
  strongest first: an enchanted golden apple's absorption and regeneration before a plain one. Hunger eats the most
  saturating ordinary food (golden carrots before steak) and, with Save Health Foods (on by default), golden apples
  only when there's nothing else, the plain ones first. Food is found anywhere in the inventory, not just the hotbar:
  it's borrowed into the hotbar and put back where it was afterwards.
- Grim suite: an Auto Eat food choice test.

## 0.2.8

Needs Myriad 0.2.6.

- Auto Disconnect: Beds and Anchors leave when one could kill you (with Myriad 0.2.6, which counts the exploding block
  as gone: before, its own block shielded you in the maths and they never left). Falls and Void are gone: the server
  keeps you where you were, falling, so you landed as you rejoined (on the Grim test server it left 25 blocks into a
  fall, and the rejoin died two seconds later).
- Grim suite: an Auto Disconnect test (low health, a charged anchor, rejoining after each).

## 0.2.7

Needs Myriad 0.2.5.

- Block Highlight draws on a layer under other highlights, so the block you look at no longer hides Storage's (and
  other modules') highlights behind it on screen: with Through Walls on, a wall you looked at hid every chest behind
  it. Storage leaves out the block Block Highlight is on, which shows Block Highlight's look; the other half of a
  double chest keeps Storage's.

## 0.2.6

Needs Myriad 0.2.4 (it sends Elytra Fly's held pitch again after a setback; see its changelog).

- Elytra Fly's Recast is steadier left alone:
  - The highway is fixed when you turn it on (the nearest 45° to where you face, through the middle of your block),
    so looking around never steers it, and drifting off that line (a setback, the way round an obstacle) turns the
    flight a little back onto it: from 0.4 blocks off, back on the line within a second.
  - It watches far enough ahead to stop in time, growing with speed (about 33 blocks at 42 bps; it was 3.5, less than
    two ticks of travel), for blocks in the way, cobwebs, holes at least two deep, and chunks that haven't loaded
    (it waits for those).
  - It stops the way a player would: no more jumps, then it lands and slides to a halt along the lane. It used to
    zero your speed mid-air, which Grim's movement simulation doesn't expect, and the landing tick steered towards the
    camera.
  - Mine walks up to the block and mines it with the breaking service's Grim-safe packet mining; with Baritone, it
    walks to the first spot past the obstacle with a floor and room to bounce, and bounces on once back on the line.
    Holes and unbreakable blocks go to Baritone when it's installed, and otherwise stop and wait.
  - Stuck (no progress for two seconds) or set back three times in five seconds, it walks past with Baritone or
    turns off with a warning instead of trying again forever.
  - Two new Grim suite tests: a straight 10 s run (272 blocks, on the line) and an ender chest plus a hole.
- Elytra Tweaks' No Durability no longer lands you with fall damage you didn't earn. While it swaps, the server
  takes you as falling, not gliding, and counts every block you come down (only rising clears it): gliding down to
  land afterwards cost all of it, up to a lethal fall from a gentle landing (a test flight banked 78 blocks; Grim test
  server, no lag). It now keeps that count as the server does, and once it's past a safe fall it looks ahead far
  enough (below you and along your flight, for a round trip plus the second or so the server needs to let go of it)
  to put the elytra back on in time; the landing test now touches down at full health. Swaps also stop for rising
  ground, walls and trees ahead, not only for the ground straight below, and stopping never drops you out of the
  glide in mid-air (it waits the one tick until the glide can start again). New suite test: a No Durability landing.
- Elytra flight can be left alone (tested at 2b2t-like ping, 100 ms each way, on the Grim test server):
  - Altitude no longer gets set back every round trip after a single setback (with Myriad 0.2.4, which sends the held
    pitch again after one); 300 ticks at that ping, no flags.
  - No Durability, stopping in mid-air, waits for the server's stop for the last swap (still on its way at that
    ping) and starts the glide again, instead of letting that stop drop you out of the glide 15 blocks up with the
    whole fall still counted: the landing test went from 3 deaths in 3 to 14 safe landings in 15 (the one death
    was in a run of the old test, which put you above its floor before building it). It also stops
    swapping before chunks you'd fly into have loaded.
  - Elytra Tweaks catches a glide that drops in mid-air for any other reason (no ground, water, ladder or vehicle):
    it opens the elytra again at once, as a player would, and says so in chat.
  - Altitude watches the ground ahead (160 blocks, from loaded chunks) and raises its cruise height to stay 24 blocks
    above it; ground closer than it can clear that way makes it pull up and keep climbing, so a cliff it can't get
    over ends in a slow stall against it rather than a crash at full speed.
  - Altitude lands while the elytra can still take it: once it has fewer uses left than the way down takes (at a
    conservative 3 blocks a second, plus 30), it comes down at 20° and levels out near the ground. From 225 blocks
    up with 102 uses left it landed unhurt with 50 to spare.
  - Two more suite tests (a No Durability landing, an elytra wearing out in Altitude); the landing test now fails if
    you died and respawned.
- Full Bright works with shader packs in Gamma mode too: packs light the world themselves and ignored the brightened
  lightmap, so caves stayed dark. They're now told you have night vision, which they brighten by, without a real
  effect on you; vanilla's look is unchanged.

## 0.2.5

Needs Myriad 0.2.3.

- Free Look's scroll distance works next to Boze, which sets the third-person camera's distance itself and used to
  keep it at 4 blocks.

## 0.2.4

Needs Myriad 0.2.3.

- Free Look: the scroll wheel moves the camera nearer or further (1 to 30 blocks, easing between steps; it starts at
  vanilla's 4 each time), and the camera orbits the middle of your player instead of its eyes. Blocks still pull the
  camera in so it never ends up inside a wall.

## 0.2.3

Needs Myriad 0.2.3.

- Blocks' Outline mode keeps its silhouettes on the GPU per chunk (only the outside faces of each clump), so even a
  very common block costs nothing per frame. Before, every block was sent again each frame, and picking stone dropped
  the frame rate badly.
- Blocks' Block Colors gives each block one colour (its default look's): a bed's head and foot (white and the dye on
  a map) or a log's end and bark no longer split into separately outlined shapes.
- Block Highlight no longer flickers against Storage's or Blocks' highlight of the same block (an opening chest).
- ESP's outlines show over Storage's and Blocks' (core draws entities above shapes), so players behind a highlighted
  chest wall stay visible.

## 0.2.2

Needs Myriad 0.2.2.

- Block Highlight is always its own outline around just the block you're looking at (half a double chest, one end of a
  bed), drawn over Blocks' and Storage's highlights. Before, it merged with them where they touched and lost that edge.

## 0.2.1

Needs Myriad 0.2.1.

- Blocks has a Mode: Boxes (as before) or Outline, the shader outline ESP and Storage have, around each block's
  shape; touching blocks share one outline, so a vein or a portal is one shape.
- Block Highlight (Render): your own look for the block you're looking at, in place of vanilla's thin black outline.
  Outline (the shader outline, with glow, fill and gradient) or Box (fill, lines or both), following the block's real
  shape, and only where it's visible unless you turn on Through Walls.

## 0.2.0

Needs Myriad 0.2.0 (highlights).

- ESP has an Outline mode: a shader outline around each entity's exact shape (armour and held items included), with
  Glow, Fill (none, solid or a dot grid), Gradient and Through Walls. Box options are hidden in that mode.
- Storage has a Mode: Boxes (as before) or Outline, the same shader outline around every container shown; touching
  containers share one outline, and chest minecarts, chest boats and pack animals are outlined by their shape.
- The dot fill's default grid is half the size (2.5 px apart, 1 px dots).
- Nametags hides the vanilla name tag (and the score under it) on players and mobs it draws its own tag for; the
  vanilla username showed above Myriad's before (fixed in core).

## 0.1.2

- No Render only listens for blocks and entities while it hides some (a block list, Vines, an entity list or Dead
  Entities). Before, its block hook ran for every block of every chunk the game rebuilt, tens of thousands of calls a
  second while moving, even with nothing to hide.

## 0.1.1

The first release from its own repository, replacing 0.1.0 (withdrawn): the stock modules (Combat, Movement,
Player, Render, World) and HUD elements, Grim-safe on 2b2t by default. Needs Myriad 0.1.0 or later.

- Velocity's Explosions setting works: explosion knockback was never reaching the module.
- Zoom eases over a set time (Duration, in ms; 0 snaps) instead of Smooth and Speed, and zooms at an even rate
  through the ease. A saved Smooth off becomes Duration 0.
- Auto Eat eats at 10 health or 16 hunger by default (18 or more keeps natural regeneration going), and gives up on
  a bite that runs past the food's own eating time.
- Elytra Fly finds obstacles by sweeping your hitbox along the lane, so a block only counts if you'd actually hit it.
- Armor HUD: Hide Undamaged and Text Scale replace Show Full and Percent Scale.
- `contact.sources` points at this repository, so Myriad's updater finds Essentials releases.
