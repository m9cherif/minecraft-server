# "A Game" — Gamemode Design Draft (for review before implementation)

Author: Buffy · 2026-09-27 · Status: **DRAFT — needs user sign-off**

## 1. What the user asked for (verbatim intent)

A new gamemode called **"a game"** where:

1. Players choose from **3 maps**.
2. A **countdown** happens before play begins.
3. **Dead players become spectators**.
4. The game **restarts when all players die** (last-man-standing loop).
5. After the countdown the **arena contains items** (loot spawns in the arena).
6. **Goal: last man standing** wins.

## 2. Deliverable shape

One new Paper plugin: **`AGame` v0.1.0** — source in
`scripts/agame/src/`, built with the proven JDK-25 recipe
(BRAIN.md), installed as `server/plugins/AGame-0.1.0.jar`.
HubCompass keeps its own plugin; the only touch-point is wiring the
"A Game" menu entry (see §8).

## 3. Core components (4 classes + 1 manager)

| Component | Responsibility |
|---|---|
| `AGamePlugin` (main) | onEnable: load maps, register listeners + commands, reset state to IDLE. |
| `GameManager` (singleton, per-plugin) | Holds `GameState`, active map, participants set, countdown task, win-check. All state mutations go through it. |
| `ArenaMap` (data) | name, display icon, spawn points (list of Location), loot tables + loot spawn points, spectator spawn, world reference. |
| `LootManager` | Populates arena chests/floor items after countdown from per-map loot tables. |
| Listeners (`GameListener`) | Death → spectator, quit handling, damage guards in lobby, block/place restrictions. |

## 4. Game states (state machine)

```
IDLE ──(enough players join queue)──▶ COUNTDOWN ──(0)──▶ PLAYING
  ▲                                     │                   │
  └──────(players leave / cancel)───────┘                   │
  ▲                                                         │
  └───────(one player left alive → winner → reset)──────────┘
```

- **IDLE** — waiting in hub. Compass menu shows map votes/join.
- **COUNTDOWN** — 10s (configurable) scoreboard/action-bar countdown with
  sounds. Teleport to map spawns at start of countdown; players frozen
  (no damage, no movement via velocity guard or simply accept free movement
  — decide in §9).
- **PLAYING** — loot spawned (§6), PvP enabled inside arena, dead →
  spectator (§5). Win check after every death: if alive participants ≤ 1
  (or all dead) → **GAME OVER**.
- **GAME OVER** (sub-state, 5s) — announce winner ("X is the last man
  standing!"), fireworks, then **auto-restart**: everyone teleports back to
  hub spawn (0.514/76.0/0.4 via the same exact spot), inventories cleared of
  game items, compass re-given, state → IDLE (or straight back to COUNTDOWN
  if ≥2 players remain in queue — "restarts when all players die" per user).
- **Restart-when-all-die variant**: if ALL players die simultaneously
  (e.g., final void/duel double-kill), declare no winner and restart loop.

## 5. Player roles

| Role | Where | Abilities | Restrictions |
|---|---|---|---|
| Lobby/hub player | hub world | everything normal | nothing changed |
| **Player** (alive) | arena world | PvP, pick up loot, move | leave game via `/agame leave` → hub + eliminated |
| **Spectator** (dead) | same arena | fly, invisibility, no target | no damage, no block/place, no pickup; teleported to spectator spawn or freed to fly over arena |

Implementation: on death, `PlayerRespawnEvent` → set `GameMode.SPECTATOR`
(immediate respawn via `minecraft:immediate_respawn` gamerule inside arena
world keeps flow snappy), keep participant record flagged
`alive=false`. On game end, restore `GameMode.SURVIVAL` + hub teleport.

Death messages: broadcast "X was eliminated (Y left)".

## 6. Arena items (loot) after countdown

- Each map defines **loot chests** (coordinates, tier: common/rare) and/or
  **floor item spawns**.
- At PLAYING transition, `LootManager` fills chests and drops floor items
  from tiered tables (weapons, armor, food, blocks — 26.3 items).
- Optional drip-feed: every 45s a random chest refills one item (keeps
  late-game alive). Config flag, default off for v0.1.

## 7. The 3 maps — where do arenas live?

**Chosen approach: single arena world, 3 map regions inside it.**
The server's overworld is a pure void — the cheapest reliable place for 3
separated arena builds is one extra Bukkit world (e.g., flat void world
`agame_world` created via plugin `WorldCreator`) with three platforms
stamped 500 blocks apart, OR 3 worlds. Design picks **one void world +
3 regions** (memory-friendly on 1 vCPU / 2 GB box — one world, three
prebuilt platform areas at fixed coords, e.g. x=0 / x=1000 / x=2000).

Map templates are **staged as .schematic-style region files the same way the
hub was** (existing `scripts/mchub.py`/`hub_build.py` pipeline knowledge) or
built procedurally in code (platform + walls + pillars). For v0.1:
**procedurally generated platforms** (code builds flat arena + obstacle
pillars) so no schematic pipeline work is needed; schematic-stamped custom
maps can replace them later.

| Map | Theme | Size | Distinct feature |
|---|---|---|---|
| Map 1 "Plains Pit" | grass platform | 64×64 | open field, scattered trees |
| Map 2 "Nether Ruins" | netherrack/nether-brick | 64×64 | maze-ish pillar cover |
| Map 3 "Sky Islands" | 3 sub-platforms + gaps | ~80×80 | falling into void = death |

## 8. Integration with existing plugins

- **HubCompass**: the "A Game" entry (new icon, e.g. `CLOCK`) in the
  gamemodes menu stops being a no-op — clicking it runs the AGame **join
  flow** (§9). Implementation choice to avoid plugin interdependency:
  AGame exposes a soft-depend command `/agame join`; HubCompass calls
  `Bukkit.dispatchCommand(player, "agame join")` on the A Game slot click.
  HubCompass gets a softdepend on AGame in its plugin.yml.
- **OnJoinSpawn**: unaffected — hub spawn enforcement stays; AGame only
  teleports players while in a game, and after game end players land on the
  exact hub spot anyway (OnJoinSpawn covers relogs mid-game: a relogging
  player is teleported to hub = treated as "left the game" → eliminated;
  GameManager listens PlayerQuitEvent for cleanup).
- **Mob guard**: arenas live in their own world, so HubCompass's hub-world
  CreatureSpawnEvent guard doesn't block arena mobs — but AGame worlds set
  `minecraft:spawn_mobs=false` gamerule anyway (last-man-standing is PvP
  only; no PvE surprises).

## 9. Join / map-selection flow

1. Player right-clicks compass → Gamemodes menu → clicks **"A Game"**.
2. Second menu: **3 map cards** (+ "Random" filler head item). Click =
   join that map's queue. Player stays in hub until countdown.
3. When a map queue has ≥ `min-players` (default **2**; allow solo start
   with flag for testing), **COUNTDOWN 10s** starts; further joiners to
   that map teleport in during countdown; late joiners after PLAYING are
   told "game in progress".
4. At 0: teleport to per-map spawn points (spread evenly), clear
   inventories (game items only), give nothing, spawn loot, begin.

## 10. Win / restart logic (per user spec)

- Alive count recomputed on every death and quit.
- **1 alive → winner** — announce, fireworks, 5s → everyone to hub, state
  IDLE, then if ≥2 players still queued for a map, countdown starts again.
- **0 alive → restart** (user: "restarts when all players die") — no
  winner, straight back to countdown.
- Last-man-standing loop = infinite; players leave anytime via
  `/agame leave` or by relogging.

## 11. Commands (v0.1)

| Command | Permission | Effect |
|---|---|---|
| `/agame join` | everyone | opens map menu |
| `/agame leave` | everyone | back to hub, eliminated |
| `/agame start <map>` | op | force countdown (testing) |
| `/agame stop` | op | cancel game, everyone to hub |
| `/agame status` | everyone | state, players alive per map |

## 12. Verification plan (before merge)

1. `javac` compile (JDK-25 recipe) + jar → verify plugin.yml inside jar.
2. Console restart → `Enabling AGame v0.1.0` + Done + port + /health.
3. Procedural arena generation verified via console `execute if block` on
   known platform coords for all 3 maps.
4. Solo smoke test via `/agame start <map>` (op) — needs one player
   in-world… **sandbox limitation**: real multiplayer behavior (countdown
   sync, death→spectator, win check) needs the user in-game; report
   honestly which parts were code-verified vs runtime-unverified.
5. PR `claude/a-game-gamemode` → freebuff, merge per workflow.

## 13. Explicitly deferred (ask user after v0.1 works)

- Custom schematic maps instead of procedural platforms.
- Per-map kits/loadouts; drip-feed loot; chests refill.
- Scoreboard sidebar with alive count & border timer.
- Multiple simultaneous games (v0.1 = one active game per map queue,
  concurrent maps allowed since regions are independent).

## 14. Open decisions for the user (blocking nothing in v0.1)

1. **Min players to start** — 2 OK? (or solo-testable start?)
2. **Countdown length** — 10s OK?
3. **Mid-game relog = eliminated** — acceptable?
4. Map themes (§7 table) — any theme preferences for the 3 arenas?
