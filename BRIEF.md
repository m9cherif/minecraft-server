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
