# BRIEF — Baarcha MC

## Goal
Run a full Minecraft Java Edition server in the sandbox, exposed through a
100% free public tunnel (no credit card anywhere in the chain), with a web
dashboard for control.

## Scope delivered
1. `scripts/setup-server.sh` — installs Temurin JRE 25 if no java ≥17, downloads
   latest stable Paper via Fill v3 API into ./server/paper.jar (sha256-checked),
   accepts EULA.
2. `server.properties` tuned: online-mode=false, motd="Baarcha MC", survival,
   difficulty normal, view-distance 8; re-enforced every startup.
3. `scripts/launch-server.sh` — headless (`nogui`) launch, heap auto-fit to
   cgroup limit.
4. Tunnel: playit.gg agent only. runs from first boot; without credentials it
   prints a claim URL (shown on dashboard + written to CLAIM_URL); with
   PLAYIT_SECRET_KEY in Keys panel it connects to the user's account right
   away. Assigned public host:port captured into PLAYIT_ADDRESS and surfaced as
   /api/status.tunnel_address.
5. Dashboard `/`: status pill, RAM, last 50 console lines (auto-refresh),
   Start/Stop/Restart, POST /command console pipe, plus playit configuration
   panel with copy buttons for address & claim link.
6. Boot chain: sandbox npm start → supervisor.js watches three children:
   java Paper (25565), playit agent, web dashboard; auto-restart each on death
   and after machine restart.

## User decisions
- Follow-up request (2026-09-17): replace Cloudflare/cloudflared entirely with
  playit.gg because the user has no credit card and wants 100% free.
  playit key optional; claim URL flow is the primary no-card path.
- Follow-up decision (2026-09-18, verbatim intent): "he never wants to see a
  playit claim link again, even if the machine or server restarts" — secret is
  backed up (config/playit-secret.backup.toml) and auto-restored; claim mode only
  when no secret AND no backup exist. Plus: player limit raised to
  max-players=1000, enforced every startup like online-mode=false.
- Three operators m9cherif3/m9cherif/m9cherif13 stay OP level 4 with
  bypassesPlayerLimit=true; online-mode stays false (cracked clients ok).

## Assumptions
- Agent being unclaimed-but-running counts as healthy for watchdog purposes
  (verified process stays alive while awaiting browser-based claim).
- Free plan yields stable xxx.craft.playit.gg:<port>; mc.chrif.net custom
  hostname may require Hostinger SRV record or playit domain settings.

## Acceptance criteria
- build passes (`node --check` all js files; bash -n all scripts). ✓
- java listening on 25565. ✓
- playit binary downloaded at setup if missing; running (claim mode) with
  visible claim URL on dashboard. ✓ (address appears once account linked)
- No cloudflared references remain in app code/config. ✓

## User decisions (multi-version, 2026-09-18)
- Server must accept ALL client versions; user's own client is 26.2 while Paper
  is 26.3. Multi-version via plugins ONLY — server jar NOT downgraded.
- Installed from GitHub releases (official ViaVersion org):
  - ViaVersion 5.12.0 — https://github.com/ViaVersion/ViaVersion/releases/download/5.12.0/ViaVersion-5.12.0.jar
    sha256 72c40a6a702d67f226fc9a0d8ad82aba1483fdabe2e6159bcdddb2dc070750b0, 6503778 bytes
  - ViaBackwards 5.12.0 — https://github.com/ViaVersion/ViaBackwards/releases/download/5.12.0/ViaBackwards-5.12.0.jar
    sha256 194e9250224632274d7b3c17e411e031a9223c1863c6f5138d53c721f07ab78d, 1430899 bytes
- Both jars verified as valid zip/jar containing root plugin.yml before boot.
- Boot confirmed single-pass ("Enabling ViaVersion v5.12.0", "ViaVersion detected
  server version: 26.3 (777)", "Enabling ViaBackwards v5.12.0"); no second restart needed.
- ViaVersion config left at defaults (no blocks, packet-limiter default);
  compatible with online-mode=false by design — nothing changed.
- Player instruction unchanged: expressing-omitted.tun.ply.gg:24838 (or bare
  host via SRV) — now any modern client version works there.
- Untested here, honestly: an actual join from the user's real 26.2 client
  needs their machine.

## User decisions (server identity, 2026-09-19)
- Animated MOTD via official MiniMOTD plugin (jpenilla/MiniMOTD v2.1.8 bukkit jar,
  latest release with published asset: newer tags 2.2.x ship zero assets on GitHub).
  Installed as server/plugins/MiniMOTD-2.1.8.jar; sha256
  ebe19a2d15793495a90cef9cea65ab0843dea282d7c9cbe2d53fb5c0beebc100.
- Animated config: 4 gradient MOTD frames in plugins/MiniMOTD/main.conf. Line 1 is
  always some gradient of "Baarcha MC"; line 2 is
  "24/7 Tunisian server - All versions welcome!" on blue/pink/green/red gradients.
  Dark-navy-friendly palette deliberate. Node: MiniMOTD picks one entry RANDOMLY per
  status ping (no timer knob); client re-pings make cycle read roughly every second.
- Custom 64x64 icon written by scripts/gen-server-icon.py (pure python zlib+struct;
  no PIL in sandbox): dark navy bg, golden crescent + star, small white "BM"
  letters bottom. Saved at server/server-icon.png. Verified bit-identical via
  status-favicon decode vs local file (0/4096 pixel diff).

## Acceptance criteria (identity pass, all tested live)
- Boot log shows "[MiniMOTD] Enabling MiniMOTD v2.1.8". ✓
- Paper serves our exact icon in status responses. ✓
- Tunnel status ping over expressing-omitted.tun.ply.gg:24838 succeeds (~167 ms),
  flattened MOTD is exactly "Baarcha MC" + "24/7 Tunisian server..." and cycles
  colors across 4 distinct frames. ✓
- online-mode=false / max-players=1000 / ops.json / playit secret re-verified. ✓
Honest limit: how the animation and crescent LOOK on a real client screen cannot
be judged from sandbox pixel text alone; ask user for screenshot.

## User decision (hub polish, 2026-09-27)
- "No mob can spawn on the hub" + players get a Gamemodes compass.
- HubCompass plugin v1.0.0: doMobSpawning=false enforced + CreatureSpawnEvent
  guard; compass in hotbar slot 0 on join/respawn, right-click opens gamemode
  menu (Bedwars, PvP, 12 entries).
- Deliberate scope per user: gamemodes NOT implemented — clicking an entry
  just closes the menu and gives no items. Ready to wire later.

## User decision ("A Game" gamemode, 2026-09-27)
- Design approved (DESIGN-a-game.md): 3 maps, countdown, dead=spectator,
  everyone-dies=auto-restart, loot after countdown, last man standing.
- Implemented as AGame plugin v0.1.0 (own agame_world void world, 3 procedural
  arenas: Plains Pit / Nether Ruins / Sky Islands) + HubCompass v1.0.1 wiring
  the "A Game" compass entry to /agame join. Boot-verified; full multiplayer
  flow needs players in-game.

## Open questions

- RESOLVED 2026-09-19 — external-vantage test pass on the "still times out"
  report. Findings: (1) DNS A=147.185.221.214 AAAA=2602:fbaf:800::d6;
  (2) IPv4 handshake into the tunnel succeeds 6/6 from sandbox + TCP connect
  OK from 20/20 check-host.net countries (0.02–1.5 s); (3) v6 could not be
  dialed from inside (no global IPv6 in sandbox) — mcsrvstat.us failed the
  AAAA path BUT its control test on hypixel.net fails identically, so that is
  the checker's quirk, not proof; mcstatus.io reports online:true; (4) NEW:
  playit publishes an SRV record, so the BARE hostname works as Direct Connect
  input too. Dashboard now offers both spellings.
- Fixed along the way: PLAYIT_ADDRESS was never written post-claim because the
  tunnel.sh log-grep only matched host:PORT and playit logs only print the
  bare host => /api/status showed tunnel_address:null. Watcher now queries
  `playit tunnels list`. (Earlier resolution: no claim link will ever appear
  again — secret persists.)
- Player-facing rule (unchanged conclusion, refined instructions): use
  `expressing-omitted.tun.ply.gg:24838` or just `expressing-omitted.tun.ply.gg`
  in Direct Connect — NEVER a numeric IP (edge RSTs any non-assigned-domain
  handshake). If it hangs at "Pinging…", delete + re-add the entry.

- Still open (minor): why did the playit agent crash-loop ~18x/hour on
  2026-09-17? Harmless while the secret persists, but worth a look if claim
  mode ever shows again.
- OPEN (2026-09-18 ~18:30, asked via bridge): user set playit dashboard to
  IPv4-only + region Germany; full supervised restart done and works, but the
  allocation still reads ip_type=both / region=global and DNS still publishes
  an AAAA (TTL 300). Waiting on whether they saved BOTH agent-level and
  per-tunnel panels — re-check `tunnels list` + DoH A/AAAA/SRV after that,
  and re-verify PLAYIT_ADDRESS in case re-allocation changes host or port.
- Player-facing address verified working post-restart:
  expressing-omitted.tun.ply.gg:24838 (unchanged), Paper 26.3 online, 5/5 pings.
- CORRECTED 2026-09-19: cynthia-miniatures.tun.ply.gg:33738 is a DIFFERENT
  tunnel of the user's playit account (they run several servers side by side;
  cynthia points at edge IP ...213 while ours sits on ...214). Never associate
  it with this project.dashboard Direct Connect strings stay exactly:
  expressing-omitted.tun.ply.gg (bare + SRV flow) or :24838.
- Verification pass same day: allocation JSON for OUR tunnel still reads
  ip_type="both" / region="global" and DoH still returns an AAAA alongside A
  on expressing-omitted (user's IPv4-only/Germany pref not visible to playit
  yet). Inside handshake over IPv4 succeeds ~21 ms, Paper 26.3, MOTD Baarcha
  MC; mcstatus.io independently reports online=true.

## User decision (hub go-live, 2026-09-22)
- User: "make the changes" — apply staged hub to LIVE world (was verified valid in staging).
- Executed: graceful console `stop` -> region .mca swap -> watchdog restart;
  spawn set to 0/67/0 via console setworldspawn AND confirmed written into
  level.dat. Public playit ping green afterwards on first try.
- During go-live a real defect in the original stage was found and fixed:
  palette entries had been bare strings like 'minecraft:stone_brick_stairs[facing=north]'
  which Paper silently downgraded to default state; regenerated with proper
  {id, properties} compounds and re-applied (3 short cycles total). Stair facing
  + slab type now verified intact on the live server side.

## User decision (full gamemode suite, 2026-09-30)
- User: "now make the full all gamemodes after you see in the net deeply the
  gamemodes" — the 11 compass entries that were display-only are now real.
- Built as ONE plugin, `Arcade` v0.1.0, not 11 plugins: a shared engine
  (GameSession state machine + GameMode base + Arena block-stamp helper +
  Scoreboards sidebar + Hooks interfaces) with one small class per mode.
  Rationale: the box has 1 vCPU / 1983 MB, so rounds are serialised (one
  active session at a time) and 11 jars would have been pure duplication.
- Shared void world `minecraft:arcade_world`; each mode owns a 1000-block X
  band (skywars 5000, pvp 6000... buildfights 11000) so arenas never overlap.
- Mechanics researched from the well-known versions of each mode:
  - SkyWars: floating islands, per-island chest, richer mid island, mid
    chests restock every 45s, last one standing.
  - PvP: FFA, killstreak announcements, 15 kills or most-kills at 5:00.
  - KitPvP: identical kit for all, instant respawn, x3/x5 combo multiplier
    that decays after 8s without a kill, 4:00 round.
  - Duels: 1v1 best-of-3, health reset each round.
  - Sumo: 7x7 platform, no blocks, knockback that scales with the lead so a
    duel can't stall, first to 2 rounds.
  - Parkour: generated meandering course, emerald checkpoints, falls cost
    progress, fastest finish wins.
  - Skyblock: 3x3 void islands, resource ladder wood>stone>iron>diamond.
  - Murder Mystery: 1 murderer / 1 detective / rest innocent, murderer knife,
    detective bow+1 arrow, innocents throw snowballs to distract, 4:00 timer
    (innocents win on timeout).
  - Capture the Flag: red vs blue, steal enemy wool, return to own pedestal,
    dropped flags return after 5s, 3 caps or lead at 5:00.
  - Hide and Seek: hiders wear scenery (sneak+right-click), the finder
    converts to a hider so one player can't end it alone, 5:00 timer.
  - Build Fights: random theme, 4:00 build, 30s vote via /bfgame vote <n>.
- HubCompass v1.2.0 now dispatches every menu entry to its mode's command
  (label -> command map, resolved via the clicked item's plain-text name).
- Commands: /arcade join <id> | leave | list | stop (arcade.admin) | /bfgame.
- Boot-verified: 11 arenas stamped (self-check line in latest.log), no errors,
  Done (53.5s), /health 200, 25565 open.

## Gamemode runtime verification (2026-10-03, Arcade v0.2.3 live)
Verified with headless bot clients (mineflayer 1.20.4) playing real matches,
plus server-log evidence:
- PvP — FULL loop proven in one fresh session (server/logs/latest.log
  19:20:31-19:22:16): join -> 10s countdown -> GO! -> 15 scored kills ->
  killstreak announcements -> "FighterA wins!" -> both back on the hub.
- Sumo — round loop verified live after the arena fix (latest.log 19:43:30,
  19:50:16, 19:52:37: melee -> knockback -> "BotBeta fell out of the world"
  -> "BotAlpha wins (1/2)"). A complete best-of-3 with the match winner is
  archived in server/logs/2026-09-30-11.log.gz (20:44:57).
- Murder Mystery — full loop observed with the real player (archived log
  2026-10-03-1.log.gz, 18:25: role reveal -> GO! -> "m9cherif3 the murderer
  wins!").
- Defects found and fixed while verifying (Arcade v0.2.2 and v0.2.3):
  1. PvP/KitPvP/Duels spawn points sat one block INSIDE their spawn
     pedestals, so players could not move (spawn Y 1 -> 2).
  2. Score/round maps persisted across matches, so a finished match's totals
     leaked into the next one; they are now cleared at every session start,
     and deaths after the winner is announced no longer score.
- Not yet gameplay-verified: SkyWars, KitPvP, Duels, Parkour, Skyblock,
  Capture the Flag, Hide and Seek, Build Fights (boot arena self-check and
  registration only). Sumo's round-2 combat also stalls in the headless
  harness for client-side reasons (post-teleport position desync), so its
  post-fix best-of-3 was not played end-to-end by bots.

## User decisions (server rules + colours, 2026-10-03, Arcade 0.3.1 / AGame 0.1.2)
- "only the op players can destroy blocks": Arcade/ProtectionListener cancels
  BlockBreakEvent for everyone who is not an operator, in every world, with the
  message "Only operators can break blocks." Verified live: a non-op bot's dig
  left the block intact and got the message; the op bot destroyed it.
- "in the hub only the ops can hit players": in the hub world, player damage
  (melee or projectile) from a non-operator is cancelled with "Only operators
  can hit players in the hub." Game arenas are deliberately NOT restricted -
  that is where fighting belongs. Verified live: non-op attacker dealt 0
  damage, op attacker took the target from 20.0 to 17.3 HP.
- "in the games there is not colors, there is &": Arcade and AGame rendered
  their '&'-coded strings raw. Both now translate '&' to the section sign
  before deserialising, so players see real colours. Verified on the wire
  (the server sends {"color":"gold"} / {"color":"gray"} components, and no
  literal '&' codes reach the client).
- Consequence of the block rule: spawn-protection set to 0 in
  server/server.properties. The plugin now enforces the rule globally, and the
  radius-16 vanilla rule only shadowed it (it cancelled hub breaks before any
  plugin handler ran, so players got the vanilla "can't build here" message
  instead of the server's rule).

## User decision (Parkour Panic map, 2026-10-03, Arcade v0.4.0) - DELIVERED
- User supplied the map via a Google Drive link (same file the minecraftmaps
  link pointed at). It downloaded only after the file was shared publicly:
  Drive serves a "too large to virus-scan" warning page for the 59 MB zip whose
  hidden uuid field has to be replayed against
  drive.usercontent.google.com/download?id=...&export=download&confirm=t&uuid=...
- That archive is a BEDROCK world (a Java map converted to Bedrock), so Paper
  cannot load it. The ORIGINAL Java Edition release was obtained instead from
  the map author's Drive link (1QYEIq18eg2R3o3-e5AqfeBv7JB8FlxOC), which is a
  proper Java world save: region chunks, DIM-1/DIM1, data/map_*.dat structures
  and the parkourpanic datapack.
- Installed at server/parkour_panic (players/stats/advancements/session.lock
  stripped) and loaded by Arcade as world `minecraft:parkour_panic`,
  spawn (1, 75, 52).
- GOTCHA worth remembering: Paper's world container on this box is "." (the
  server dir), and on first load Paper MIGRATED the legacy world itself into
  world/dimensions/minecraft/parkour_panic - so the folder is no longer where
  it was dropped. Arcade checks both layouts before loading.
- Parkour mode now plays the map when it is installed: it stops stamping its
  generated course, seats players at the map spawn, gives flight, rescues
  anyone who falls out of the world, and runs a 300 s round (the map's own
  command blocks/functions drive the course). It falls back to the generated
  course when the map is absent.
- Verified live (scripts/verify/parkour-map-check.js): a bot joining /arcade
  parkour lands on the map at 1.0,74.5,52.0 standing on smooth_quartz, sees
  GO!, and /arcade leave returns it to the hub.

## Open item (was): Parkour Panic map import
- Requested: use https://dl.minecraftmaps.com/parkour-panic-v2.2.zip as the
  parkour map, and the same link as the PvP arena map.
- BLOCKED: the host answers 403 with a Cloudflare "Just a moment..." challenge
  for the file, the map page and the bare domain from this sandbox (no JS
  engine to clear the challenge). Every path tried returns 403.
- To finish: the user attaches the zip through Freebuff's Files (it lands in
  public/media/) or provides a mirror that is not behind Cloudflare.
- The archive has NEVER been opened - every request is blocked - so its format
  is unknown and the import route cannot be fixed yet: a world save can be
  loaded as its own world and pointed at by the modes, a .schematic has to be
  stamped into an arena band, and other formats need their own path. Once the
  file is in the workspace the route follows from what is actually inside it.
  The existing procedural parkour course and PvP arena keep working meanwhile.
- SECOND LINK (2026-10-03, Google Drive) - RESOLVED: once the file was
  shared publicly the download worked (see the delivered section above). The
  remaining open question is whether the PvP ARENA should also use this map;
  the user's first message pasted the same URL for both modes and no separate
  PvP map link has arrived. The PvP mode keeps its own procedural arena.
- Also unresolved: the same URL was pasted for both Parkour and PvP. Confirm
  whether PvP should really use the parkour map or a different one.
