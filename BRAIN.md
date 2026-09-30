# BRAIN — Baarcha MC

## What this is
Minecraft Java Edition (Paper) server inside the sandbox; Express dashboard to
control/observe it. Public connectivity via a free playit.gg agent (replaced
Cloudflare/cloudflared entirely on 2026-09-17).
Entry point: `node server.js` → `supervisor.js` (npm start).

## Current state
- WORLD RESET + HUB REAPPLIED (2026-09-26 ~18:47): overworld CLEARED (all
  region files deleted, nether/end untouched) and hub7834921.schematic
  re-staged via scripts/hub_build.py (numpy+nbtlib pip-installed) into
  world/dimensions/minecraft/overworld/region/{r.-1.-1,r.-1.0,r.0.-1,r.0.0}.mca
  — 100 chunks, floor top y=66, hub centered chunk (0,0). World spawn set to
  0 67 0 via console setworldspawn. playerdata/advancements/stats WIPED so
  every player (re)spawns at the hub; OnJoinSpawn plugin still enforces spawn
  on every join/relog regardless of logout position/world. Backup of the old
  world at backups/world-20260926-184212/ (31MB, gitignored). Verified: java
  restarted cleanly, 25565 open, tunnel status OK 92ms, TPS 20/20/20.
  GOTCHA: swapping regions while java runs corrupts state — stop java
  (console stop), do the copy inside the watchdog's ~20s relaunch cooldown.
- 24/7 KEEPALIVE + OOM ROOT CAUSE (2026-09-26 ~18:30): the kernel OOM-killed
  java (`Memory cgroup out of memory: Killed process 4719 (java)`) because the
  70% heap (1433MB) + node + playit exceeded the 2GB cgroup; load hit 9.7 with
  83MB free and kswapd thrashing. Players saw "huge ping / not working" because
  the tunnel stays up while java is dead — connect succeeds, nothing answers.
  Fixes: heap 55% (1126MB) in launch-server.sh; supervisor.js now suppresses
  uncaughtException/unhandledRejection and stays up on SIGTERM/SIGINT so the
  watchdog (5s poll, 20s cooldown) can always relaunch java/playit. GC flags
  added: AlwaysPreTouch, PerfDisableSharedMem, G1NewSize 30/40,
  MaxTenuringThreshold=4, UseStringDeduplication. Verified live: TPS 19.9/20,
  tunnel status handshake 685ms, java relaunched with new flags after a stop.
  NOTE: console.cmds works only while the `tail -f | java` pipe is healthy;
  if console stops responding, the pipe was severed and only a watchdog
  relaunch (kill java) fixes it — appended commands silently pile up.
  REAL ping floor for players is ~7ms edge + client->playit edge distance;
  tunnel handshake measured 63-844ms from sandbox. Single vCPU is the ceiling
  for TPS under load, not RAM.
- BOOT RESTORED + TUNNEL NEEDS RELINK (2026-09-26 ~14:05): the sandbox came
  back from a project export that did NOT carry two things.
  (1) `project-export.js` was absent even though `webapp-dashboard.js`
  requires it, so `node server.js` died with MODULE_NOT_FOUND and nothing
  listened on 3000. Rebuilt it (pure Node, zlib deflate, no new deps) with the
  same exclusions the old one used — node_modules/.git/jdk/jre/backups/logs/
  crash-reports/cache/libraries/versions, the `playit` and `paper.jar`
  binaries, *.pid/*.log/*.part/*.tmp, and every copy of the playit secret.
  Self-exclusion is why it went missing: the export never contained the module
  the dashboard needs to produce the export. If project-export.js is ever
  regenerated, keep exporting it.
  (2) The playit secret is GONE: no `server/playit.secret.toml`, no
  `config/playit-secret.backup.toml`, PLAYIT_SECRET_KEY unset. The agent
  therefore started in CLAIM MODE and `PLAYIT_ADDRESS` was deleted, so the
  public address expressing-omitted.tun.ply.gg:24838 is stale/unreachable
  until the agent is re-linked. Fix = user sets PLAYIT_SECRET_KEY in the Keys
  panel (or approves the one-time claim link the dashboard shows at
  /api/status `playit_claim_url`). Paper itself is healthy: 25565 open,
  plugins loaded, ops verified.
- PROJECT EXPORT button live (2026-09-26): dashboard header "Export Project"
  navigates a plain <a> to `api/export-project` (relative, so subpath-hosted
  dashboards resolve right) and lets the server's Content-Disposition name the
  file. Do NOT go back to fetch()+Blob+createObjectURL+anchor.click(): a ~25 MB
  blob plus synthetic click silently no-ops inside the sandboxed public preview
  frame — that was the original "button does nothing" bug. Zip itself is built
  per request by project-export.js (pure Node, no `zip` binary, no new deps).
  Excludes node_modules/.git/jdk/jre/backups/logs/crash-reports/cache/
  libraries/versions, playit + paper.jar binaries, *.pid/*.log, and every copy
  of the playit secret. Verify:
  `curl -sD- -o /tmp/x.zip http://127.0.0.1:3000/api/export-project && unzip -t /tmp/x.zip | tail -2`
- HUB IS LIVE (2026-09-22 ~18:07): staged overworld regions applied to
  world/dimensions/minecraft/overworld/region/{r.-1.-1,r.-1.0,r.0.-1,r.0.0}.mca
  (100 chunks, floor top y=66, hub centered chunk 0,0), spawn set to 0 67 0,
  verified through playit tunnel. Backup at backups/world-20260922-180138/
  (12 .mca + level.dat + sha256-before.txt). See "palette gotcha" below.
- SERVER IDENTITY LIVE (2026-09-19 ~19:38): MiniMOTD 2.1.8 enabled with 4 gradient
  MOTD frames ("Baarcha MC" / "24/7 Tunisian server - All versions welcome!"),
  custom 64x64 crescent+star "BM" icon at server/server-icon.png generated by
  scripts/gen-server-icon.py. Verified through the public tunnel: status ping
  cycles all 4 frames; served favicon pixel-identical to local file.
- FULL RESTART 2026-09-18 ~18:20 after user set playit dashboard to IPv4-only +
  region Germany. Sequence: console `stop` (worlds saved cleanly, java exit ~2 s,
  pid 131→1819) → TERM to playit (exit ~1 s) → watchdog relaunched both within
  seconds, zero manual starts. Post-restart handshake via public address:
  Paper 26.3 / MOTD Baarcha MC / max_players=1000, 5/5 OK over IPv4 (~450 ms).
  Secret files bit-identical pre/post (sha256 af0ff3bb…797f), ops.json intact.
- SETTINGS PROPAGATION STILL PENDING at ~18:30: allocation JSON still says
  ip_type="both" region="global", DoH AAAA 2602:fbaf:800::d6 still published,
  A unchanged 147.185.221.214, port_start still 24838, PLAYIT_ADDRESS unchanged.
  User asked via bridge whether they hit Save on BOTH the agent-level network
  prefs and each tunnel (per-tunnel overrides win). If they did, allow >10 min /
  next restart for playit to re-allocate, then re-run the checks in this note.
- DIAGNOSED 2026-09-18 ~17:20 after user report of "Pinging…" hang + Direct
  Connect timeout: NOTHING is broken server-side. java binds :::25565
  (wildcard), agent session alive, mapping expressing-omitted.tun.ply.gg =>
  127.0.0.1:25565 intact, end-to-end MC status ping through public address
  succeeds 10/10 over IPv4 (~70 ms). Root cause of the user's symptoms is
  client-side entry format (see playit edge gotcha below). REAL VALID ADDRESS
  FOR PLAYERS: expressing-omitted.tun.ply.gg:24838 — must be typed WITH the
  ":24838" suffix and as the hostname, not a bare IP. Port 24839 no longer
  answers (edge now times out on it).
- CLAIMED & CONNECTED (2026-09-18 16:53): secret at server/playit.secret.toml
  (+ auto backup). Public MC endpoint expressing-omitted.tun.ply.gg:24838.
  Dashboard /api/status: playit_running=true, mc_port_open=true,
  tunnel_configured=true, claim_pending=false.
- Working & verified: Paper 26.3 build 8 on Temurin JRE 25.0.4.1, world up,
  port 25565 open, console pipe live (`server/console.cmds`), ops.json enforced,
  watchdog respawns crashed java AND the playit agent, logs rotate-trimmed.
- Claim-code churn gotcha (2026-09-18): an unclaimed agent mints a NEW random
  claim code every boot (~18 codes in one hour); if the process dies, the code
  the user has open in their browser becomes void. `playit start` CANNOT pin a
  given code — only recovery is approval of whatever the running agent shows,
  or `playit claim exchange <code>` racing a browser page for a possibly-stale
  code (ran 9 min against 0a87f86a63, never matched once its owning process
  died). Fastest path to bind: approve within minutes of agent (re)start while
  CLAIM_URL is fresh, before any crash-loop mints newer codes.
- Claim persistence ("never claim again", 2026-09-18): tunnel.sh NEVER deletes/
  rotates/moves server/playit.secret.toml once present — existing file always
  wins with `--secret_path`, no claim mode. Each boot backs it up to
  config/playit-secret.backup.toml and restores from that backup if the main
  file vanishes. Claim mode runs ONLY when both files are absent. Verified via
  stubbed-agent matrix + two live watchdog relaunch cycles.
- Player cap: launch-server.sh enforce_props sets max-players=1000 on every
  startup (same pattern as online-mode=false). Ops m9cherif3/m9cherif/m9cherif13
  keep level 4 + bypassesPlayerLimit=true.
- Tunnel: playit.gg agent v0.15.26 at ./playit (downloaded by scripts/tunnel.sh
  from github.com/playit-cloud/playit-agent releases). Claim pending now:
  current one-time URL in CLAIM_URL / playit_claim_url (/api/status). Once
  approved, secret lands at server/playit.secret.toml and no claim ever shows
  again; dashboard hides the claim row whenever a secret exists anywhere.
- Dashboard <status>: claim panel visible ONLY while claim_pending=true;
  connected view shows just tunnel_address (client JS + server render agree).
- Force-reclaim procedure (legacy, used 2026-09-17): still possible but now
  requires ALSO deleting config/playit-secret.backup.toml after moving the
  main secret aside, else tunnel.sh restores the old secret at next boot.
- Post-claim flash of APIError Auth(InvalidAgentKey) means the saved secret was
  rejected/revoked (playit returned AgentDisabledOverLimit); reclaiming fixes it
  unless the account's agent limit is genuinely reached. Revocation resurrects
  fresh claim codes even though nothing locally deleted the secret — that's
  server-side, not a local bug.

## Stack & key choices
- No system java and no sudo → bundled userland JRE in `./jre` (Temurin 25).
- PaperMC v2 API retired; Fill v3 used for jar URL + sha256.
- Heap auto-sizes to ~70% of /sys/fs/cgroup/memory.max.
- Console stdin = `tail -n0 -f server/console.cmds | java …`.
- Dashboard content-negotiates: curl/Accept:text/html returns HTML, plain GET /
  returns machine-readable JSON.
- /api/status fields now: status, tunnel_configured, tunnel_address,
  playit_claim_url, playit_running, plus older ram/log fields.

## Current state (ViaVersion era, 2026-09-18)
- Multi-version support INSTALLED: ViaVersion 5.12.0 + ViaBackwards 5.12.0 in
  server/plugins/ (GitHub-release originals, sha256 recorded in BRIEF.md).
  Loaded clean on first watchdog relaunch after console `stop`; no second
  restart required. Public ping through playit still OK post-plugin.

## Gotchas & environment facts
- MiniMOTD config keys are kebab-case (`motd-enabled`, `player-count-settings`)
  because Configurate's default NamingScheme is LOWER_CASE_DASHED — verified by
  reading the shaded NamingSchemes class in the plugin jar. main.conf round-trips
  through load+save on every boot; don't rename its keys.
- MiniMOTD has NO fixed rotation interval: each ping randomly selects one entry of
  `motds`. "Animation" = several frames chosen at random while the client re-pings.
  Extra per-host MOTDs go in plugins/MiniMOTD/extra-configs/*.conf (virtual hosts
  configured in plugin_settings.conf, proxy-only).
- MiniMOTD's icon handling only injects icons from plugins/MiniMOTD/icons/*;
  with that folder EMPTY it leaves the vanilla server/server-icon.png untouched,
  so the two coexist cleanly. Paper never logs an explicit "icon loaded" line —
  confirm via status-request favicon instead.
- jpenilla/MiniMOTD recent release tags (2.2.x) publish ZERO GitHub assets; last
  tag whose releases API lists jars is v2.1.8 (asset minimotd-bukkit-2.1.8.jar).
  Update-checker banner about newer versions appearing in logs is expected/harmless.
- No PIL anywhere. scripts/gen-server-icon.py hand-writes the PNG (zlib+struct),
  RGBA color-type 6 and every pixel alpha=255 exactly, else Minecraft silently
  rejects/misrenders it (first draft wrote RGB triples under an RGBA header; decoded
  length check caught it). minecraft's accepted range for icon size is strictly 64x64.
- ViaVersion dump command prints its result as a chat component only — it does
  NOT land in logs/latest.log or stdio.log; don't chase that URL. Supported-range
  evidence lives in release notes (`Added 26.3 client support!` for ViaVersion,
  `26.1->26.2` translation path ViaBackwards-side) instead.
- MULTI-TUNNEL ACCOUNT GOTCHA (2026-09-19): the user's playit account holds
  SEVERAL tunnels for different projects. cynthia-miniatures.tun.ply.gg:33738
  (edge .213) belongs to a DIFFERENT server — do not wire it into this app.
  OURS is whatever `./playit --secret_path server/playit.secret.toml tunnels
  list` reports for the tunnel whose origin is 127.0.0.1:25565 on THIS machine;
  today that's expressing-omitted.tun.ply.gg ports 24838-24839 (edge .214).
  Always identify our allocation from the local agent, never from task prose.
- EXTERNAL-VANTAGE VERDICT (2026-09-19): IPv4 path is globally healthy —
  check-host.net TCP-connects 147.185.221.214:24838 from 20/20 nodes (0.02–1.5 s,
  EU+US+ME+Asia). AAAA exists (2602:fbaf:800::d6) but sandbox has no global v6
  (`Network unreachable`; only ::1 on lo) so the v6 edge could NOT be tested
  directly. mcsrvstat.us is NOT evidence of dead v6: it also reports online:false
  for hypixel.net (control fail). mcstatus.io external status: online:true.
- SRV RECORD DISCOVERY (2026-09-19): playit auto-publishes
  `_minecraft._tcp.expressing-omitted.tun.ply.gg → 1 1 24838 expressing…`,
  so typing the BARE hostname in Direct Connect works — clients honor SRV and
  connect to :24838 themselves. Verified end-to-end via DoH-resolved simulation.
  Only typed-string forms that work: `expressing-omitted.tun.ply.gg` (SRV flow)
  and `expressing-omitted.tun.ply.gg:24838` (direct). Anything else breaks:
  bare IP or even 214.ip.gl.ply.gg as handshake hostfield gets RST (~41 ms);
  edge omits-port dial to :25565 hangs => "Pinging…". Edge STRICTLY validates
  the handshake hostname against the assigned domain.
- tunnel.sh ADDR bug fixed 2026-09-19: watcher grepped log for host:PORT pairs
  but playit's TUNNELS banner prints only the bare hostname (zero ':port'
  matches in whole log), so PLAYIT_ADDRESS stayed empty and /api/status showed
  tunnel_address:null forever. Fix: call `./playit --secret_path <file>
  tunnels list`, parse assigned_domain+port_start from JSON, fall back to bare
  hostname from log. Widened fallback regex to make hostname-only match legal.
- playit CLI offers no knob to pick IPv4-only allocation: alloc ip_type="both"
  is platform-assigned. (`tunnels prepare` has no family flag.)
- Playit edge behavior (probed 2026-09-18): answering UDP-free TCP properly ONLY
  on the assigned pair member 24838; a handshake whose server-address field is
  a bare IP gets RST immediately (~40 ms), and connections to edge:25565 (what
  a client dials when you omit ":port") just hang — those two behaviors ARE the
  classic "stuck at Pinging…" / "Direct Connect times out" reports. Clinically
  verified: full-hostname handshake @24838 returns status JSON 10/10 over IPv4.
- 2026-09-18 diagnostic pass: java binds :::25565 (wildcard, revealed via
  /proc/net/tcp* since ss/netstat are MISSING — so are ps/pgrep/xxd);
  `grep -h ' 0A ' /proc/net/tcp*` lists LISTEN sockets; inode→pid mapping
  via /proc/*/fd. mcping.py kept at /tmp/mcping.py (varint framing, proto 767).
- jimp install under pnpm fails (ERR_PNPM_UNEXPECTED_STORE, store-dir mismatch);
  when a screenshot Reads as "[image omitted]", pure-python zlib+PNG filter
  decode (/tmp/png2.py pattern) renders pixels as ASCII with no new deps.
  This PNG was genuinely 856x512 RGBA despite the Read tool's omission.
- Real repo org: `playit-cloud/playit-agent` — task prompt said
  "playit-commutergo" which 404s. Latest stable is v1.x (daemon+cli pair over
  IPC); v0.15.26 was chosen because its single binary does the whole job
  (runs foreground, prints claim URL, saves secret with --secret_path).
  Asset name: `playit-linux-amd64`, tag-pinned download URL.
- `-w/--secret_wait` blocks forever waiting for a secret file and never prints
  a claim URL — the flag combo we first shipped broke claim mode; relying on
  `--secret_path <path>` alone gets both flows right.
- Claim persist-to-file path defaults to ~/.config/playit_gg/playit.toml;
  overridden via --secret_path to ./server/playit.secret.toml so claims survive
  snapshots/remixes.
- `ps`, `pkill`, `pgrep` are missing; iterate `/proc/[0-9]*/cmdline` instead.
  Killing by matching words can kill your own shell (exit 144): exclude $$.
- Bridge screenshot returns JPEG even when saved as .png; jimp (pnpm add jimp)
  succeeds where PIL and system image tools are absent. Reading certain JPEGs
  may show "[image omitted]" — verify pixels statistically then.
- Raw Minecraft traffic cannot ride an HTTP-only CDN CNAME — that's precisely
  why playit (native TCP forwarder) replaces cloudflared here.

## Schematic -> Anvil staging (2026-09-22)
- HUB PALETTE GOTCHA (cost two debug cycles, verified by reading Paper's own
  "Recoverable errors" log lines): chunk block_states palette entries MUST be
  compounds keyed `{"id": "minecraft:x", "properties": {...}}`. A bare string
  like "minecraft:stairs[facing=north]" logs "Non [a-z0-9/._-] character" and a
  compound named `Name`/`Properties` logs "No key id in MapLike -> using
  default"; BOTH drop the block to its default state silently (stair facing /
  slab half lost) while the server keeps booting green. hub_build.py now emits
  id/properties; after the fix the section warnings count == 0.
- Paper re-saves chunks in ITS canonical form: palettes become
  Compound({'': String('minecraft:air')}) or bare Strings for defaults and full
  property maps for real states, so post-boot .mca bytes never equal staged
  bytes. Verify semantically (state census), not by sha256.
- Console 'data get block' only answers for block ENTITIES ("The target block
  is not a block entity" for plain blocks; "not loaded" means the chunk isn't
  loaded — use `forceload add X Z` first). Better live probe:
  `setblock ... say marker` + `execute if block`.
- Graceful-stop recipe that worked twice cleanly: printf 'stop\n' >>
  server/console.cmds, poll kill -0 PID at 0.1 s (exit ~1-2 s); watchdog
  auto-relaunches java ~20 s later (its coordinator cooldown), during which the
  region swap must finish — atomic per-file renames make any coincidental launch
  safe either way.
- Multi-applied staging workflow: regenerate -> stop -> cp-to-tmp+mv -> verify
  sha vs stage -> watchdog restart. Each full cycle took <40 s wall clock.
- Library verdict: amulet-core fails on py3.13 (meson needs ninja >=1.8.2,
  not installable here); nbtlib 2.0.4 (+numpy) installs clean. System pip is
  PEP-668 locked and /tmp is mounted noexec -- use the venv at
  /home/sandbox/hubenv (/tmp files are fine as outputs, just not binaries).
- /tmp/mcping2.py = working raw status pinger (varint framing, proto 767;
  handshake body = varint(0)+varint(PROTO)+host+port+state). /tmp is wiped
  between tasks; recreate from this note if needed.
- Paper jar is version id "26.3", world_version/DataVersion 5023.
- scripts/hub_build.py parses server/plugins/hub7834921.schematic via nbtlib
  (145x55x142 legacy Alpha format), maps only the ids present in its histogram
  to modern names, writes /tmp/hub-stage-overworld/region/r.X.Z.mca covering
  chunks x:-5..4 z:-5..4 (100 chunks incl. empty), floor-top y=66 center chunk
  (0,0). Verify: `/home/sandbox/hubenv/bin/python scripts/hub_build.py`.
- Cross-check tool used at finish decodes every section and counts non-air
  voxels; must print exactly 84_974 (= W*H*L minus source air count).
- Gotcha that cost a debug cycle: when emitting 1.18+ sections, palette index
  0 defaults into every unwritten cell. Unless slot 0 of a section's palette
  is `minecraft:air`, unseen cells silently become the first real block.
- nbtlib API notes: `nbtlib.File(root_compound)` writes bare-root NBT;
  pass byteorder="big" to write(); read with File.parse(BytesIO(payload));
  LongArray needs ">i8"/">u8" little wrangling -- pack BitStorage low-to-high.

## Dead ends
- api.papermc.io/v2 endpoints — retired (410); do not switch back.
- Quick trycloudflare tunnels and Cloudflare named tunnels — removed entirely;
  don't resurrect them.
- mv-style log rotation loses live writer fds — copy-truncate stays.

## Decisions
- playit.gg is the only tunnel; PLAYIT_SECRET_KEY optional (required:false in
  sandbox.yaml). Without key: claim mode + CLAIM_URL shown on dashboard. With
  key: --secret handed straight to the agent.
- Operator list m9cherif3/m9cherif/m9cherif13 hardcoded in scripts/gen-ops.js,
  merged idempotently into ops.json each boot (level 4, bypass limit true);
  op-watch.sh re-ops via console once 'Done' appears; online-mode=false also
  still re-enforced every startup via launch-server.sh enforce_props.
- mc.chrif.net custom domain path documented as Hostinger SRV record
  _minecraft._tcp.mc.chrif.net pointing at the playit address (free-friendly);
  A/CNAME alternative noted. Honest banner says xxx.craft.playit.gg works
  immediately and custom domain may need playit domain settings or upgrade.

## Void world + hub-only overworld (2026-09-27, applied)
- `server/server.properties`: `level-type=minecraft\:flat` +
  `generator-settings={"layers":[],"biome":"minecraft:the_void"}` = fully void
  overworld with NO terrain and NO bedrock anywhere (verified at chunk 62,62:
  air at y=0 and y=64).
- GOTCHA: do NOT just delete the overworld dimension dir — Paper fails to boot
  with "Overworld settings missing" because it needs
  `dimensions/minecraft/overworld/data/minecraft/world_gen_settings.dat`, whose
  generator must match server.properties. Deleting the ENTIRE `server/world/`
  dir (level.dat + all dims) makes Paper regenerate every dim from the new
  properties in ~30s. Nether/End also regenerated fresh.
- GOTCHA: console pipe severs silently after java dies/relaunches; appended
  `stop` lines go nowhere. After relaunch, test with `list` first; if dead,
  kill the java pid — watchdog relaunches within seconds.
- Procedure that worked: console stop (verify java gone) -> mv server/world to
  backups/ -> let watchdog boot Paper on void generator -> stop again -> cp the
  4 hub r.*.mca from /tmp/hub-stage-overworld/region/ into
  world/dimensions/minecraft/overworld/region/ -> boot -> setworldspawn 0 69 0.
- Hub floor top solid block at hub center is y=68 (stone_bricks); spawn must be
  0 69 0 so players stand ON the hub. Previous 0 67 0 spawned inside/below the
  surface (void-under-map complaint). Verify with
  `execute if block 0 68 0 #minecraft:base_stone_overworld run say OK`.
- Pre-change world backup: backups/world-pre-void-delete-20260927-141307/.
- Multi-line console writes via `echo "a" > cmds` then `echo "b" >> cmds` in
  ONE bash line can merge/garble (execute-parse errors); send one command per
  sleep-2 append instead.

## Exact fixed spawn 0.514/76.0/0.4 (2026-09-27, applied)
- User complaint: spawn "sometimes in floor, sometimes right, sometimes
  elsewhere" — vanilla join placement varied around the spawn block.
- Fix: OnJoinSpawn plugin v1.0.1 teleports every join/relog to an EXACT
  hardcoded Location (0.514, 76.0, 0.4, yaw/pitch 0) instead of
  getSpawnLocation(); no vanilla scatter can move it.
- Also set the world spawn block to match (setworldspawn 0 76 0) and placed a
  solid polished_andesite block at (0,75,0) — verified via setblock+execute —
  so the spawn Y=76 stands on solid floor, not air.
- GOTCHA: sandbox has NO javac; Paper 26.3's paper-api jar is class-file v69
  (Java 25) so javac 21 refuses it. Working recipe: download Temurin JDK 25
  from api.adoptium.net to /tmp, compile against
  server/libraries/io/papermc/paper/paper-api/**.jar PLUS all
  server/libraries/net/kyori/*.jar (adventure + annotations deps needed on
  the javac classpath). /tmp binaries do NOT survive; server/plugins/*.jar
  written via terminal DID persist (gitignored).
- GOTCHA: relative-path bug — a jar built with `cd scripts/onjoinspawn &&
  jar --create --file ../../server/plugins/...` landed in
  scripts/server/plugins/ (workspace root-relative resolution surprise);
  boot then loaded the OLD v1.0.0 jar from server/plugins. Always verify the
  installed jar's plugin.yml version matches the build.
- Note: `spawnRadius` gamerule DOES NOT EXIST on Paper 26.3 (removed
  vanilla rule); scatter control is not needed with the exact-Location
  teleport anyway.
- v1.0.2 (2026-09-27): death respawn too — added @EventHandler
  onPlayerRespawn(PlayerRespawnEvent) calling e.setRespawnLocation(exactSpawn())
  so dying players (even with a bed/anchor set) land at the same exact
  0.514/76.0/0.4 spot. Verified "Enabling OnJoinSpawn v1.0.2" in boot log +
  Done + port 25565 open + /health 200. A real death test needs the user (no
  player in-world from sandbox).

## HubCompass plugin (2026-09-27, PR)
- Source: scripts/hubcompass/src/com/baarcha/hubcompass/HubCompass.java +
  plugin.yml; jar server/plugins/HubCompass-1.0.0.jar (gitignored).
- Features per user request: (1) hub mob-spawn prevention — sets
  GameRule.DO_MOB_SPAWNING=false on main world at every enable AND a
  CreatureSpawnEvent guard cancelling all spawns except CUSTOM reason;
  (2) "Gamemodes" compass in hotbar slot 0 on join+respawn (not duplicated,
  cannot be dropped); right-click opens 18-slot menu with 12 gamemode icons
  (Bedwars, Skywars, PvP, ...).
- IMPORTANT per user: gamemodes are DISPLAY-ONLY for now — clicking any entry
  cancels the click and closes the menu, NO items are given. Wire real
  gamemodes later by replacing closeInventory() in onMenuClick.
- GOTCHAS found this build (update the OnJoinSpawn recipe mentally):
  paper-api 26.3 needs MORE classpath jars for broader API surface:
  org.jetbrains annotations (downloaded annotations-26.0.2-1.jar from Maven
  Central to /tmp/jb-annotations.jar — javac crashes with CompletionFailure
  without it), and guava + failureaccess (Material.getItemAttributes refs
  Multimap). Material.BED no longer exists -> RED_BED.
- GOTCHA: gamerule RENAMES in 1.21.11+/26.x: doMobSpawning ->
  minecraft:spawn_mobs, doInsomnia -> minecraft:spawn_phantoms, spawnMonsters
  -> minecraft:spawn_monsters, spawnRadius -> minecraft:respawn_radius (full
  table on nodecraft 1.21.11 gamerule page). Console confirmed: spawn_mobs
  already false (set by plugin), spawn_monsters + spawn_phantoms set false.
  Old `gamerule doMobSpawning false` errors with Incorrect argument.
- Verified live: "[HubCompass] Enabling HubCompass v1.0.1", Done, port open,
  /health 200, no entities present (kill @e probe found none).
  Real compass-in-hand + menu-open test needs the user in-game.

## AGame gamemode (2026-09-27, PR) — design in DESIGN-a-game.md
- Source scripts/agame/src/com/baarcha/agame/*.java (6 classes) + plugin.yml;
  jar server/plugins/AGame-0.1.0.jar (gitignored). HubCompass v1.0.1 wires
  the "A Game" menu entry (CLOCK icon) to `Bukkit.dispatchCommand(p,"agame join")`;
  AGame is SOFTDEPEND, no compile-time coupling either direction.
- Architecture: AGamePlugin (main+commands) / GameManager (state machine
  IDLE->COUNTDOWN(10s)->PLAYING->GAMEOVER(5s)) / ArenaMap (data+procedural
  build) / LootManager (7 chests/map, tiered COMMON+RARE tables) /
  MapsMenu (9-slot, title "A Game - choose a map") / GameListener /
  VoidGenerator. One shared void world agame_world (plugin-created via
  WorldCreator+VoidGenerator), 3 arenas at x=0/1000/2000, y=100.
- Flow per user spec: pick 1 of 3 maps -> countdown -> teleports + inventory
  saved/cleared -> loot spawns at FIGHT -> last man standing; death =
  drops cleared + respawn as SPECTATOR at arena sky view; everyone dead =
  restart without winner; win/quit/relog = restore hub inventory + teleport
  exact hub spawn; /agame leave anytime; /agame start <map> + stop (op, perm
  agame.admin) for testing.
- COMPILE recipe additions beyond OnJoinSpawn's: bungeecord-chat jar (any
  CommandSender#sendMessage overload) and guava+failureaccess are REQUIRED;
  org.jetbrains annotations-26.0.2-1.jar from Maven Central REQUIRED (javac
  CompletionFailure crash without it). ChunkGenerator overrides that exist
  in 26.3: shouldGenerate{Noise,Surface,Bedrock,Caves,Decorations}() and
  getDefaultPopulators(World) — NOT (WorldInfo), no getBaseHeightMaterial,
  no shouldGenerateMobs().
- VERIFICATION GOTCHA (cost ~30 min): console `execute if block ... run say
  X` is SILENT on Paper 26.3 — no log line whether true or false (plain `say`
  works). Do NOT poll console.cmds for execute probes. Instead add a boot
  self-check to the plugin that reads blocks back and logs them:
  "arena self-check: GRASS_BLOCK @plains, NETHER_BRICKS @nether, END_STONE
  @sky" — verified live at boot. Multi-command appends also lag several
  minutes on this box; send one command per terminal call.
- Verified live: Loading+Enabling AGame v0.1.0, agame_world created, arena
  self-check pass, Done, port 25565, /health 200. NOT runtime-tested (needs
  users in-game): countdown sync, death->spectator, loot chest contents,
  win/restart loop.

## Creative owner accounts (PR #9, HubCompass v1.1.0 / AGame v0.1.1)
- User asked to make m9cherif3 creative while OFFLINE. Two blockers found:
  1) `server/console.cmds` pipe passes the WHOLE line to the server as ONE
     argument, so multi-word commands break: `gamemode m9cherif3 creative`
     returns "Unknown game mode: m9cherif3 ... m9cherif3 creative<--[HERE]"
     (leading slash makes no difference). Single-word commands (`say X`,
     `stop`) work fine.
  2) m9cherif3 has NO player data at all (server/world/playerdata is empty),
     so even a correctly parsed offline `gamemode` would fail.
- Solution: HubCompass holds CREATIVE_PLAYERS (currently just m9cherif3,
  matched case-insensitively) and calls setGameMode(CREATIVE) on
  PlayerJoinEvent, logging "<name> joined in Creative". Vanilla keeps the
  gamemode across death, so respawn needs nothing.
- AGame must not clobber it: GameManager now keeps savedGameModes (saved in
  saveHubInventory) and restoreToHub uses that instead of hardcoded SURVIVAL.
  Without this, a creative owner returning from a match dropped to survival.
- To add more owner accounts, extend the CREATIVE_PLAYERS list in
  scripts/hubcompass/src/.../HubCompass.java and rebuild HubCompass.

## Arcade suite (PR #10, Arcade v0.1.0 / HubCompass v1.2.0)
- ONE plugin for 11 gamemodes, not 11 jars. Engine: GameSession (shared
  COUNTDOWN->PLAYING->GAMEOVER state machine + hub inventory/gamemode save &
  restore) / GameMode (abstract base: identity, build, kit, tick, winner,
  timeLimit, scoreboard lines) / Arena (relative block-stamp helper) /
  Scoreboards (per-player sidebar) / Hooks (ModeListener, DamageListener,
  MoveListener, InteractListener, DropListener, HungerFree).
- Rounds are SERIALISED: one active session at a time (1 vCPU / 1983 MB).
  Shared void world `minecraft:arcade_world` (note the namespace in the log);
  each mode owns a 1000-block X band: skywars 5000, pvp 6000, kitpvp 7000,
  duels 8000, sumo 9000, parkour 10000, skyblock 11000, murdermystery 12000,
  ctf 13000, hideandseek 14000, buildfights 15000.
- API GOTCHAS hit while building (Paper 26.3):
  * A class named `GameMode` in your own package SHADOWS org.bukkit.GameMode —
    you cannot import the Bukkit one. Fully qualify `org.bukkit.GameMode`
    everywhere in that package. (Cost: one confusing compile round.)
  * `EntityDamageEvent` has NO getDamager(); cast to
    EntityDamageByEntityEvent first.
  * `Material.WOOD` is gone -> OAK_PLANKS. `Material.RED_LEATHER_CHESTPLATE`/
    BLUE_... gone -> use LeatherArmorMeta.setColor() on LEATHER_CHESTPLATE.
  * `Material.WOOL` gone -> WHITE_WOOL.
  * Sound constants: ENTITY_ENDER_DRONG_GROWTH does not exist ->
    ENTITY_ENDER_DRAGON_GROWL. playSound(Location, Sound, float) is invalid;
    the 4-arg (Location, Sound, volume, pitch) form is required.
  * Scoreboard: `board.getScore(String)` does NOT exist; use
    `board.getScores(String)` and setScore on each returned Score.
    `Objective.displayName(Component)` exists (setDisplayName is the legacy
    String form). `ChatColor.of(Color)` is gone — build invisible entries as
    "§" + 2 hex digits instead.
  * `ItemMeta.color(Color)` does not exist -> cast to LeatherArmorMeta.
- JAR BUILDING TRAP: compiling hubcompass + arcade into ONE output dir and
  jarring from it ships the other plugin's classes inside the jar. Compile
  each plugin into its own fresh dir before jarring.
- Roles for Murder Mystery / Hide and Seek are assigned in
  GameMode.onRoundStart(), not giveKit(): the cast needs the full player list.
- Boot-verified: 11/11 arenas stamped ("arena self-check: skywars=SANDSTONE,
  pvp=SMOOTH_STONE, ..."), modes registered, no errors, Done (53.5s).
- NOT runtime-verified: all 11 modes need real players (countdown, roles,
  kills, scoring, voting, win conditions). Ask the user to try them.
