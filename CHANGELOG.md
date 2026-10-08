# Changelog

Myriad Essentials is versioned on its own, apart from [Myriad](https://github.com/fyzz-dev/myriad) core. Each
release says which core it needs (`myriad_version` in `gradle.properties`, the `depends` in `fabric.mod.json`).

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
