# Changelog

Myriad Essentials is versioned on its own, apart from [Myriad](https://github.com/fyzz-dev/myriad) core. Each
release says which core it needs (`myriad_version` in `gradle.properties`, the `depends` in `fabric.mod.json`).

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
