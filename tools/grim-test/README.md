# Grim test server

A local server set up like 2b2t as far as is publicly known, for checking what Grim makes of Myriad's modules
before anyone tries them there. Scripts drive the server (`./cmd`) and the dev client (`./client`), and read Grim's
flags (`./flags`), so a test can be run from a terminal (or by an agent) start to finish.

Needs JDK 21 for the server (path in `versions.env`) and Python 3.

## How close it is to 2b2t

| Matches | Unknown, left at defaults |
|---|---|
| Paper 1.21.4 with ViaVersion and ViaBackwards, so newer and older clients join | 2b2t's Grim version and its config (it may be a fork) |
| GrimAC, current build | Whether 2b2t turns on Grim's experimental checks (`./start.sh --experimental` to try with them) |
| 2b2t's world seed, hard survival, PvP, no spawn protection, Nether and End | Paper tweaks, the queue proxy, plugins |
| About 2b2t's ping, with `./lag.py` | |

Passing here means "passes current Grim as configured here", not "safe on 2b2t". Grim alerts on every violation in
this setup (it normally waits for several); that only changes the messages, not what it cancels or sets back.

## Use

```bash
./setup.sh                 # once: downloads Paper, Grim, ViaVersion and ViaBackwards into server/ (pinned in versions.env)
./start.sh                 # starts in the background (--experimental for Grim's experimental checks)
./lag.py 100 15            # optional, in another terminal: localhost:25566 with 100 ms each way, 15 ms jitter

# from the repo root: the dev client, straight into the server
./gradlew runClient -PquickPlayServer=localhost:25565 -Pusername=GrimTester   # as 1.20.4 through ViaFabricPlus; -Pvia=native for 26.2
./cmd "op GrimTester"      # once; ops still get checked (Grim only exempts grim.exempt), and see alerts in chat
```

Then a test is: set the scene, mark the log, act, read the flags.

```bash
./cmd "clear GrimTester" "item replace entity GrimTester armor.chest with elytra" "tp GrimTester 0 220 0 0 0"
./flags mark
./client ".set elytra_fly mode altitude" ".toggle elytra_fly" "wait 200" status ".toggle elytra_fly"
./flags                    # "no flags", or each flag with Grim's details (exit code 1)
```

| Script | Does |
|---|---|
| `./cmd "<command>" ...` | runs server console commands over RCON and prints their output |
| `./client "<step>" ...` | runs steps in the dev client and prints what it logged (`./client` alone lists the steps) |
| `./flags mark`, `./flags`, `./flags all`, `./flags follow` | Grim's flags since the mark, this run, or live |
| `./lag.py [ms] [jitter]` | a latency proxy on the lag port |
| `./setup.sh --update` | shows the newest builds, to move `versions.env` to on purpose |
| `./stop.sh` | stops the server |

The client steps come from `DevConsole` in core, which dev runs turn on: Myriad commands (`.toggle kill_aura on`),
server commands as the player (`/gamemode survival`), `hold forward,sprint,jump 40`, `look <yaw> <pitch>`,
`select <1-9>`, `attack`, `use`, `wait <ticks>`, `respawn`, `status`, `connect <host:port>` (from a disconnect
screen: `./cmd "kick GrimTester"` first, then `connect localhost:25566` to play through `./lag.py`), and
`selftest [ticks] [-module]`, which turns every module on for a while and off again and reports any that failed or
whose handlers threw.

Recast's obstacle tests take Baritone's path when the dev client has it (a Baritone jar in `run/mods`, e.g. copied from
your own instance) and the mine-and-fill path when it doesn't; the Baritone-only tests skip without it. Run both ways
after changing Recast's obstacle handling.

Grim simulates each player as the version they joined with, so test as 1.20.4 (the default) and, for anything
version-dependent, again with `-Pvia=native`; `./cmd "viaversion list"` shows what the server sees. Elytra wear needs
ping to show: a landing the server misses (its packet handled in the same server tick as the next hop's) lets its
glide run on, so run the elytra tests through `./lag.py 100 80` too (and `./cmd "tick rate 14"` for 2b2t's TPS).

## The Essentials suite

```bash
./suite              # every test (about 3 minutes)
./suite kill_aura    # the tests whose names contain a word
./suite --list
```

With the server and the dev client running, `./suite` turns every module off, then for each test builds a scene of
its own (mostly on a stone floor around x=1000, away from the rest of the world), runs the client steps, checks the
result on the server (the husk is dead, the block is gone, the totem is in the off hand, the elytra didn't wear) and
fails the test if Grim flagged anything while the module ran. It covers the self-test, Kill Aura (holding and switch),
Packet Mine, Scaffold, Offhand, Auto Armor, Auto Eat, Stack Replenish, Elytra Fly and No Durability, and that Auto
detects Grim. Run it after changing core or Essentials; add a test when you add a module.
